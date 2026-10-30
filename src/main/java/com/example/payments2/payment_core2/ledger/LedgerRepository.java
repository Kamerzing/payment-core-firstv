package com.example.payments2.payment_core2.ledger;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
	public static final UUID SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
	public static final UUID FEES = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private final JdbcTemplate jdbc;

	public LedgerRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Account> lockAccounts(List<UUID> ids) {
		String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
		var arguments = new java.util.ArrayList<Object>(ids);
		arguments.addAll(ids);
		return jdbc.query("SELECT id, wallet_id, kind, balance_minor FROM ledger_account WHERE id IN ("
				+ placeholders + ") OR wallet_id IN (SELECT wallet_id FROM ledger_account WHERE id IN (" + placeholders
				+ ")) ORDER BY id FOR UPDATE", (rs, row) -> new Account(rs.getObject("id", UUID.class),
					rs.getObject("wallet_id", UUID.class), rs.getString("kind"), rs.getLong("balance_minor")), arguments.toArray());
	}

	public void insert(UUID operationId, Posting posting) {
		jdbc.update("INSERT INTO ledger_posting (id, operation_id, account_id, amount_minor) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), operationId, posting.accountId(), posting.amountMinor());
	}

	public List<Posting> postings(UUID operationId) {
		return jdbc.query("SELECT account_id, amount_minor FROM ledger_posting WHERE operation_id = ? ORDER BY account_id",
				(rs, row) -> new Posting(rs.getObject("account_id", UUID.class), rs.getLong("amount_minor")), operationId);
	}

	public UUID availableForReserved(UUID reserved) {
		return jdbc.queryForObject("""
				SELECT a.id FROM ledger_account a JOIN ledger_account r ON r.wallet_id = a.wallet_id
				WHERE r.id = ? AND r.kind = 'RESERVED' AND a.kind = 'AVAILABLE'
				""", UUID.class, reserved);
	}

	public record Account(UUID id, UUID walletId, String kind, long balanceMinor) {
	}
}
