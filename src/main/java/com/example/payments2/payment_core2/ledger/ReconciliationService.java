package com.example.payments2.payment_core2.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
	private final JdbcTemplate jdbc;

	public ReconciliationService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
	public Report reconcile() {
		Instant snapshot = jdbc.queryForObject("SELECT transaction_timestamp()", java.sql.Timestamp.class).toInstant();
		var mismatches = jdbc.query("""
				SELECT a.id, a.balance_minor, COALESCE(sum(p.amount_minor), 0) AS journal
				FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id
				GROUP BY a.id HAVING a.balance_minor::numeric <> COALESCE(sum(p.amount_minor), 0)
				ORDER BY a.id
				""", (rs, row) -> new Mismatch(rs.getObject("id", UUID.class), rs.getString("balance_minor"), rs.getString("journal")));
		long invalid = jdbc.queryForObject("""
				SELECT count(*) FROM (SELECT o.id FROM payment_operation o
				LEFT JOIN ledger_posting p ON p.operation_id = o.id GROUP BY o.id
				HAVING o.state <> 'POSTED' OR count(p.id) < 2 OR COALESCE(sum(p.amount_minor), 0) <> 0) invalid
				""", Long.class);
		String total = jdbc.queryForObject("SELECT COALESCE(sum(balance_minor), 0)::text FROM ledger_account", String.class);
		var reserved = jdbc.query("""
				SELECT a.id, a.balance_minor, COALESCE(h.total, 0) AS active_total FROM ledger_account a
				LEFT JOIN (SELECT wallet_id, sum(amount_minor::numeric + fee_minor) AS total
				FROM payment_hold WHERE state = 'ACTIVE' GROUP BY wallet_id) h ON h.wallet_id = a.wallet_id
				WHERE a.kind = 'RESERVED' AND a.balance_minor::numeric <> COALESCE(h.total, 0) ORDER BY a.id
				""", (rs, row) -> new Mismatch(rs.getObject("id", UUID.class), rs.getString("balance_minor"), rs.getString("active_total")));
		return new Report(mismatches.isEmpty() && reserved.isEmpty() && invalid == 0 && "0".equals(total), snapshot, total, invalid, mismatches, reserved);
	}

	public record Mismatch(UUID account_id, String cached_minor, String journal_minor) {
	}

	public record Report(boolean consistent, Instant snapshot_at, String total_minor, long invalid_operations,
			List<Mismatch> accounts, List<Mismatch> reserved_holds) {
	}
}
