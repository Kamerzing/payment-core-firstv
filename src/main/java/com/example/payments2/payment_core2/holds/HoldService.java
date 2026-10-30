package com.example.payments2.payment_core2.holds;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.example.payments2.payment_core2.audit.AuditService;
import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.ledger.LedgerRepository;
import com.example.payments2.payment_core2.ledger.LedgerService;
import com.example.payments2.payment_core2.ledger.Posting;
import com.example.payments2.payment_core2.operations.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldService {
	private final HoldRepository holds;
	private final FinancialAccess access;
	private final FeePolicy fees;
	private final IdempotencyService idempotency;
	private final LedgerRepository accounts;
	private final LedgerService ledger;
	private final AuditService audit;

	public HoldService(HoldRepository holds, FinancialAccess access, FeePolicy fees, IdempotencyService idempotency,
			LedgerRepository accounts, LedgerService ledger, AuditService audit) {
		this.holds = holds; this.access = access; this.fees = fees; this.idempotency = idempotency;
		this.accounts = accounts; this.ledger = ledger; this.audit = audit;
	}

	@Transactional(timeout = 15)
	public HoldResponse create(String username, String key, CreateHoldRequest input) {
		var actor = access.actor(username, false);
		if (!Set.of("TRANSFER", "WITHDRAWAL").contains(input.type() == null ? "" : input.type())
				|| "TRANSFER".equals(input.type()) != (input.target_wallet_id() != null) || input.expected_fee_basis_points() == null) throw Money.invalid();
		long amount = Money.positive(input.amount_minor());
		long expectedFee = Money.nonnegative(input.expected_fee_minor());
		String canonical = "HOLD_CREATE|" + input.type() + "|" + input.target_wallet_id() + "|" + amount + "|" + expectedFee + "|" + input.expected_fee_basis_points();
		return idempotency.execute(actor.id(), key, canonical, HoldResponse.class, () -> {
			var terms = fees.confirmed(input.amount_minor(), input.expected_fee_minor(), input.expected_fee_basis_points());
			UUID wallet = access.ownWallet(actor.id());
			if (wallet.equals(input.target_wallet_id())) throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER", "A different wallet is required.");
			if (input.target_wallet_id() != null) access.account(input.target_wallet_id(), "AVAILABLE");
			var operation = ledger.post(actor.id(), "HOLD_CREATE", wallet, null, amount, terms.fee(), terms.basisPoints(), null,
					List.of(new Posting(holds.account(wallet, "AVAILABLE"), -terms.total()), new Posting(holds.account(wallet, "RESERVED"), terms.total())));
			var result = holds.insert(wallet, input, terms, operation.id());
			audit.append(actor.id(), "HOLD_CREATE", operation.id());
			return result;
		});
	}

	@Transactional(timeout = 15)
	public HoldResponse finish(String username, String key, UUID id, boolean capture) {
		var actor = access.actor(username, false);
		String action = capture ? "HOLD_CAPTURE" : "HOLD_CANCEL";
		return idempotency.execute(actor.id(), key, action + "|" + id, HoldResponse.class, () -> {
			var hold = holds.find(id, access.ownWallet(actor.id()), true, false).orElseThrow(HoldService::notFound);
			if (!"ACTIVE".equals(hold.state())) throw new ApiException(HttpStatus.CONFLICT, "HOLD_CLOSED", "The hold is already closed.");
			return transition(hold, actor.id(), capture, false);
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15)
	public boolean expire(UUID id) {
		var candidate = holds.find(id, null, true, true);
		if (candidate.isEmpty() || !"ACTIVE".equals(candidate.get().state()) || !holds.expired(id)) return false;
		transition(candidate.get(), holds.owner(candidate.get().wallet_id()), false, true);
		return true;
	}

	private HoldResponse transition(HoldResponse hold, UUID actor, boolean capture, boolean expiration) {
		long amount = Long.parseLong(hold.amount_minor());
		long fee = Long.parseLong(hold.fee_minor());
		long total = Long.parseLong(hold.total_minor());
		UUID available = holds.account(hold.wallet_id(), "AVAILABLE");
		var changes = new ArrayList<Posting>();
		changes.add(new Posting(holds.account(hold.wallet_id(), "RESERVED"), -total));
		if (capture) {
			UUID destination = "TRANSFER".equals(hold.type()) ? access.account(hold.target_wallet_id(), "AVAILABLE") : LedgerRepository.SETTLEMENT;
			changes.add(new Posting(destination, amount));
			if (fee > 0) changes.add(new Posting(LedgerRepository.FEES, fee));
		} else changes.add(new Posting(available, total));
		accounts.lockAccounts(changes.stream().map(Posting::accountId).toList());
		// clock_timestamp() is read only after all account locks have been acquired.
		boolean expired = holds.expired(hold.id());
		if (capture && expired) throw new ApiException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "The hold has expired.");
		String state = capture ? "CAPTURED" : expired || expiration ? "EXPIRED" : "CANCELLED";
		String type = capture ? "CAPTURE_" + hold.type() : "EXPIRED".equals(state) ? "HOLD_EXPIRE" : "HOLD_CANCEL";
		var operation = ledger.post(actor, type, hold.wallet_id(), capture ? hold.target_wallet_id() : null,
				amount, fee, hold.fee_basis_points(), null, changes);
		// Conditional SQL also enforces the deadline at the terminal transition itself.
		var result = holds.complete(hold.id(), state, operation.id());
		audit.append(actor, type, operation.id());
		return result;
	}

	@Transactional(readOnly = true)
	public List<HoldResponse> list(String username, int limit, int offset) {
		var actor = access.actor(username, false);
		if (limit < 1 || limit > 100 || offset < 0) throw Money.invalid();
		return holds.list(access.ownWallet(actor.id()), limit, offset);
	}

	@Transactional(readOnly = true)
	public HoldResponse get(String username, UUID id) {
		var actor = access.actor(username, false);
		return holds.find(id, access.ownWallet(actor.id()), false, false).orElseThrow(HoldService::notFound);
	}

	private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "The hold was not found."); }
}
