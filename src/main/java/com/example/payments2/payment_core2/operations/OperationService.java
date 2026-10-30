package com.example.payments2.payment_core2.operations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.payments2.payment_core2.audit.AuditService;
import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.ledger.LedgerRepository;
import com.example.payments2.payment_core2.ledger.LedgerService;
import com.example.payments2.payment_core2.ledger.Posting;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationService {
	private final FinancialAccess access;
	private final FeePolicy fees;
	private final IdempotencyService idempotency;
	private final LedgerService ledger;
	private final LedgerRepository postings;
	private final OperationRepository operations;
	private final AuditService audit;

	public OperationService(FinancialAccess access, FeePolicy fees, IdempotencyService idempotency,
			LedgerService ledger, LedgerRepository postings, OperationRepository operations, AuditService audit) {
		this.access = access;
		this.fees = fees;
		this.idempotency = idempotency;
		this.ledger = ledger;
		this.postings = postings;
		this.operations = operations;
		this.audit = audit;
	}

	@Transactional(timeout = 15)
	public OperationResponse deposit(String username, String key, MonetaryRequests.Deposit request) {
		var actor = access.actor(username, true);
		long amount = Money.positive(request.amount_minor());
		if (request.wallet_id() == null) throw Money.invalid();
		return idempotency.execute(actor.id(), key, "DEPOSIT|" + request.wallet_id() + "|" + amount,
				OperationResponse.class, () -> {
			var result = ledger.post(actor.id(), "DEPOSIT", null, request.wallet_id(), amount, 0, 0, null,
					List.of(new Posting(LedgerRepository.SETTLEMENT, -amount),
							new Posting(access.account(request.wallet_id(), "AVAILABLE"), amount)));
			audit.append(actor.id(), "DEPOSIT", result.id());
			return result;
		});
	}

	@Transactional(timeout = 15)
	public OperationResponse transfer(String username, String key, MonetaryRequests.Transfer request) {
		var actor = access.actor(username, false);
		long amount = Money.positive(request.amount_minor());
		long expectedFee = Money.nonnegative(request.expected_fee_minor());
		if (request.target_wallet_id() == null || request.expected_fee_basis_points() == null) throw Money.invalid();
		String canonical = "TRANSFER|" + request.target_wallet_id() + "|" + amount + "|" + expectedFee + "|" + request.expected_fee_basis_points();
		return idempotency.execute(actor.id(), key, canonical, OperationResponse.class, () -> {
			var terms = fees.confirmed(request.amount_minor(), request.expected_fee_minor(), request.expected_fee_basis_points());
			UUID source = access.ownWallet(actor.id());
			if (source.equals(request.target_wallet_id())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER", "A transfer requires a different wallet.");
			}
			var changes = new ArrayList<Posting>();
			changes.add(new Posting(access.account(source, "AVAILABLE"), -terms.total()));
			changes.add(new Posting(access.account(request.target_wallet_id(), "AVAILABLE"), terms.amount()));
			addFee(changes, terms.fee());
			var result = ledger.post(actor.id(), "TRANSFER", source, request.target_wallet_id(), terms.amount(),
					terms.fee(), terms.basisPoints(), null, changes);
			audit.append(actor.id(), "TRANSFER", result.id());
			return result;
		});
	}

	@Transactional(timeout = 15)
	public OperationResponse withdraw(String username, String key, MonetaryRequests.Withdrawal request) {
		var actor = access.actor(username, false);
		long amount = Money.positive(request.amount_minor());
		long expectedFee = Money.nonnegative(request.expected_fee_minor());
		if (request.expected_fee_basis_points() == null) throw Money.invalid();
		String canonical = "WITHDRAWAL|" + amount + "|" + expectedFee + "|" + request.expected_fee_basis_points();
		return idempotency.execute(actor.id(), key, canonical, OperationResponse.class, () -> {
			var terms = fees.confirmed(request.amount_minor(), request.expected_fee_minor(), request.expected_fee_basis_points());
			UUID source = access.ownWallet(actor.id());
			var changes = new ArrayList<Posting>();
			changes.add(new Posting(access.account(source, "AVAILABLE"), -terms.total()));
			changes.add(new Posting(LedgerRepository.SETTLEMENT, terms.amount()));
			addFee(changes, terms.fee());
			var result = ledger.post(actor.id(), "WITHDRAWAL", source, null, terms.amount(), terms.fee(), terms.basisPoints(), null, changes);
			audit.append(actor.id(), "WITHDRAWAL", result.id());
			return result;
		});
	}

	@Transactional(timeout = 15)
	public OperationResponse reverse(String username, String key, UUID originalId) {
		var actor = access.actor(username, true);
		if (originalId == null) throw Money.invalid();
		return idempotency.execute(actor.id(), key, "REVERSAL|" + originalId, OperationResponse.class, () -> {
			var original = operations.find(originalId, true).orElseThrow(OperationService::notFound);
			if (!Set.of("DEPOSIT", "TRANSFER", "WITHDRAWAL", "CAPTURE_TRANSFER", "CAPTURE_WITHDRAWAL").contains(original.type()) || !"POSTED".equals(original.state())) {
				throw new ApiException(HttpStatus.CONFLICT, "NOT_REVERSIBLE", "This operation cannot be reversed.");
			}
			if (operations.hasReversal(originalId)) {
				throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVERSED", "The operation has already been reversed.");
			}
			UUID reserved = original.type().startsWith("CAPTURE_") ? postings.postings(originalId).stream()
					.filter(posting -> posting.amountMinor() < 0).map(Posting::accountId).findFirst().orElseThrow() : null;
			UUID available = reserved == null ? null : postings.availableForReserved(reserved);
			var changes = postings.postings(originalId).stream().map(posting -> new Posting(
					posting.accountId().equals(reserved) ? available : posting.accountId(), Math.negateExact(posting.amountMinor()))).toList();
			var result = ledger.post(actor.id(), "REVERSAL", original.target_wallet_id(), original.source_wallet_id(),
					Long.parseLong(original.amount_minor()), Long.parseLong(original.fee_minor()), original.fee_basis_points(), originalId, changes);
			audit.append(actor.id(), "REVERSAL", result.id());
			return result;
		});
	}

	@Transactional(readOnly = true)
	public List<OperationResponse> list(String username, boolean admin, int limit, int offset) {
		var actor = access.actor(username, admin);
		if (limit < 1 || limit > 100 || offset < 0) throw Money.invalid();
		return operations.list(actor.id(), admin, limit, offset);
	}

	@Transactional(readOnly = true)
	public OperationResponse get(String username, UUID id, boolean admin) {
		var actor = access.actor(username, admin);
		var result = operations.find(id, false).orElseThrow(OperationService::notFound);
		if (!admin && !operations.ownedBy(result, actor.id())) throw notFound();
		return result;
	}

	private static void addFee(List<Posting> changes, long fee) {
		if (fee > 0) changes.add(new Posting(LedgerRepository.FEES, fee));
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND", "The operation was not found.");
	}
}
