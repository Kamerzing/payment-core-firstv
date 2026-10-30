package com.example.payments2.payment_core2.wallets;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

	private final JdbcTemplate jdbc;

	public WalletRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void create(UUID ownerId) {
		UUID walletId = UUID.randomUUID();
		jdbc.update("INSERT INTO wallet (id, owner_id) VALUES (?, ?)", walletId, ownerId);
		jdbc.update("INSERT INTO ledger_account (id, wallet_id, kind) VALUES (?, ?, 'AVAILABLE'), (?, ?, 'RESERVED')",
				UUID.randomUUID(), walletId, UUID.randomUUID(), walletId);
	}

	public List<AdminWalletResponse> list(UUID ownerId, int limit, int offset) {
		return jdbc.query("""
				SELECT w.id, w.owner_id, u.username, u.enabled, w.currency, a.balance_minor AS available, r.balance_minor AS reserved
				FROM wallet w JOIN app_user u ON u.id = w.owner_id
				JOIN ledger_account a ON a.wallet_id = w.id AND a.kind = 'AVAILABLE'
				JOIN ledger_account r ON r.wallet_id = w.id AND r.kind = 'RESERVED'
				WHERE (?::uuid IS NULL OR w.owner_id = ?) ORDER BY w.created_at, w.id LIMIT ? OFFSET ?
				""", (rs, row) -> new AdminWalletResponse(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
						rs.getString("username"), rs.getBoolean("enabled"),
						rs.getString("currency"), Long.toString(rs.getLong("available")), Long.toString(rs.getLong("reserved"))),
				ownerId, ownerId, limit, offset);
	}

	public Optional<WalletResponse> findOwned(String username, UUID walletId) {
		return jdbc.query("""
				SELECT w.id, w.owner_id, w.currency, a.balance_minor AS available, r.balance_minor AS reserved
				FROM wallet w JOIN app_user u ON u.id = w.owner_id
				JOIN ledger_account a ON a.wallet_id = w.id AND a.kind = 'AVAILABLE'
				JOIN ledger_account r ON r.wallet_id = w.id AND r.kind = 'RESERVED'
				WHERE u.username = ? AND u.enabled AND (?::uuid IS NULL OR w.id = ?)
				""", (rs, row) -> new WalletResponse(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
						rs.getString("currency"), Long.toString(rs.getLong("available")), Long.toString(rs.getLong("reserved"))),
				username, walletId, walletId).stream().findFirst();
	}
}
