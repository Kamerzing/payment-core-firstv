package com.example.payments2.payment_core2.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationRepository {
	private final JdbcTemplate jdbc;

	public OperationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public UUID draft(UUID actor, String type, UUID source, UUID target, long amount, long fee, int basisPoints, UUID original) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO payment_operation (id, actor_id, type, source_wallet_id, target_wallet_id,
				amount_minor, fee_minor, fee_basis_points, original_operation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, actor, type, source, target, amount, fee, basisPoints, original);
		return id;
	}

	public OperationResponse post(UUID id) {
		jdbc.update("UPDATE payment_operation SET state = 'POSTED', posted_at = clock_timestamp() WHERE id = ?", id);
		return find(id, false).orElseThrow();
	}

	public Optional<OperationResponse> find(UUID id, boolean lock) {
		return jdbc.query("SELECT * FROM payment_operation WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
				OperationRepository::map, id).stream().findFirst();
	}

	public boolean hasReversal(UUID original) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM payment_operation WHERE original_operation_id = ?)", Boolean.class, original));
	}

	public List<OperationResponse> list(UUID owner, boolean admin, int limit, int offset) {
		return jdbc.query("""
				SELECT o.* FROM payment_operation o WHERE o.state = 'POSTED' AND (? OR EXISTS (
				SELECT 1 FROM wallet w WHERE w.owner_id = ? AND w.id IN (o.source_wallet_id, o.target_wallet_id)))
				ORDER BY o.created_at DESC, o.id LIMIT ? OFFSET ?
				""", OperationRepository::map, admin, owner, limit, offset);
	}

	public boolean ownedBy(OperationResponse operation, UUID actor) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM wallet WHERE owner_id = ? AND id IN (?, ?))
				""", Boolean.class, actor, operation.source_wallet_id(), operation.target_wallet_id()));
	}

	private static OperationResponse map(ResultSet rs, int row) throws SQLException {
		long amount = rs.getLong("amount_minor");
		long fee = rs.getLong("fee_minor");
		var posted = rs.getTimestamp("posted_at");
		return new OperationResponse(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("state"),
				rs.getObject("source_wallet_id", UUID.class), rs.getObject("target_wallet_id", UUID.class),
				Long.toString(amount), Long.toString(fee), rs.getInt("fee_basis_points"), Long.toString(Math.addExact(amount, fee)),
				rs.getObject("original_operation_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
				posted == null ? null : posted.toInstant());
	}
}
