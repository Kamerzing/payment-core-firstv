package com.example.payments2.payment_core2.ledger;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.operations.OperationRepository;
import com.example.payments2.payment_core2.operations.OperationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
	private final LedgerRepository ledger;
	private final OperationRepository operations;

	public LedgerService(LedgerRepository ledger, OperationRepository operations) {
		this.ledger = ledger;
		this.operations = operations;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public OperationResponse post(UUID actor, String type, UUID source, UUID target, long amount, long fee,
			int basisPoints, UUID original, List<Posting> postings) {
		var changes = new HashMap<UUID, Long>();
		BigInteger sum = BigInteger.ZERO;
		for (var posting : postings) {
			if (posting.accountId() == null || posting.amountMinor() == 0
					|| changes.putIfAbsent(posting.accountId(), posting.amountMinor()) != null) {
				throw new IllegalArgumentException("Postings require distinct accounts and nonzero changes.");
			}
			sum = sum.add(BigInteger.valueOf(posting.amountMinor()));
		}
		if (changes.size() < 2 || sum.signum() != 0) throw new IllegalArgumentException("Postings must balance.");
		var accounts = ledger.lockAccounts(List.copyOf(changes.keySet()));
		if (!accounts.stream().map(LedgerRepository.Account::id).toList().containsAll(changes.keySet())) throw new IllegalArgumentException("Unknown ledger account.");
		var walletTotals = new HashMap<UUID, BigInteger>();
		for (var account : accounts) {
			long balance;
			try {
				balance = Math.addExact(account.balanceMinor(), changes.getOrDefault(account.id(), 0L));
			} catch (ArithmeticException exception) {
				throw new ApiException(HttpStatus.CONFLICT, "BALANCE_OVERFLOW", "The resulting balance is too large.");
			}
			if (!account.kind().equals("SETTLEMENT") && balance < 0) {
				throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "The account has insufficient funds.");
			}
			if (account.walletId() != null) walletTotals.merge(account.walletId(), BigInteger.valueOf(balance), BigInteger::add);
		}
		if (walletTotals.values().stream().anyMatch(total -> total.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)) {
			throw new ApiException(HttpStatus.CONFLICT, "BALANCE_OVERFLOW", "The wallet total is too large.");
		}
		UUID id = operations.draft(actor, type, source, target, amount, fee, basisPoints, original);
		// Accounts remain locked; the database applies every posting to its derived balance.
		for (var account : accounts) if (changes.containsKey(account.id())) ledger.insert(id, new Posting(account.id(), changes.get(account.id())));
		return operations.post(id);
	}
}
