package com.example.payments2.payment_core2.holds;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.example.payments2.payment_core2.operations.FeePolicy.Terms;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import com.example.payments2.payment_core2.common.web.ApiException;

@Repository
public class HoldRepository {
	private final JdbcTemplate jdbc;
	public HoldRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public HoldResponse insert(UUID wallet, CreateHoldRequest input, Terms terms, UUID operation) {
		UUID id = UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO payment_hold (id, wallet_id, target_wallet_id, type, amount_minor, fee_minor,
				fee_basis_points, creation_operation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
				""", HoldRepository::map, id, wallet, input.target_wallet_id(), input.type(), terms.amount(), terms.fee(), terms.basisPoints(), operation);
	}

	public Optional<HoldResponse> find(UUID id, UUID wallet, boolean lock, boolean skipLocked) {
		return jdbc.query("SELECT * FROM payment_hold WHERE id = ? AND (?::uuid IS NULL OR wallet_id = ?)"
				+ (lock ? " FOR UPDATE" : "") + (skipLocked ? " SKIP LOCKED" : ""), HoldRepository::map, id, wallet, wallet).stream().findFirst();
	}

	public List<HoldResponse> list(UUID wallet, int limit, int offset) {
		return jdbc.query("SELECT * FROM payment_hold WHERE wallet_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
				HoldRepository::map, wallet, limit, offset);
	}

	public List<UUID> due() {
		return jdbc.query("SELECT id FROM payment_hold WHERE state = 'ACTIVE' AND expires_at <= clock_timestamp() ORDER BY expires_at, id LIMIT 100",
				(rs, row) -> rs.getObject("id", UUID.class));
	}

	public boolean expired(UUID id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at <= clock_timestamp() FROM payment_hold WHERE id = ?", Boolean.class, id));
	}

	public HoldResponse complete(UUID id, String state, UUID operation) {
		try {
			return jdbc.queryForObject("""
				UPDATE payment_hold SET state = ?, completion_operation_id = ?, completed_at = clock_timestamp()
				WHERE id = ? AND state = 'ACTIVE' RETURNING *
				""", HoldRepository::map, state, operation, id);
		} catch (DataAccessException exception) {
			if (exception.getMostSpecificCause() instanceof SQLException sql && "P5001".equals(sql.getSQLState())) {
				throw new ApiException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "The hold has expired.");
			}
			throw exception;
		}
	}

	public UUID account(UUID wallet, String kind) {
		return jdbc.queryForObject("SELECT id FROM ledger_account WHERE wallet_id = ? AND kind = ?", UUID.class, wallet, kind);
	}

	public UUID owner(UUID wallet) {
		return jdbc.queryForObject("SELECT owner_id FROM wallet WHERE id = ?", UUID.class, wallet);
	}

	private static HoldResponse map(ResultSet rs, int row) throws SQLException {
		var completed = rs.getTimestamp("completed_at");
		return new HoldResponse(rs.getObject("id", UUID.class), rs.getObject("wallet_id", UUID.class), rs.getObject("target_wallet_id", UUID.class),
				rs.getString("type"), rs.getString("state"), rs.getString("amount_minor"), rs.getString("fee_minor"), rs.getInt("fee_basis_points"),
				Long.toString(Math.addExact(rs.getLong("amount_minor"), rs.getLong("fee_minor"))), rs.getObject("creation_operation_id", UUID.class),
				rs.getObject("completion_operation_id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
				completed == null ? null : completed.toInstant());
	}
}
