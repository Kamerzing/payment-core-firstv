package com.example.payments2.payment_core2.operations;

import java.util.UUID;

import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialAccess {
	private final JdbcTemplate jdbc;

	public FinancialAccess(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Actor actor(String username, boolean requireAdmin) {
		var actor = jdbc.query("SELECT id, role FROM app_user WHERE username = ? AND enabled",
				(rs, row) -> new Actor(rs.getObject("id", UUID.class), rs.getString("role")),
				UserRepository.normalizeUsername(username)).stream().findFirst().orElseThrow(() ->
				new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required."));
		if (requireAdmin && !actor.admin()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.");
		}
		return actor;
	}

	public UUID ownWallet(UUID actor) {
		return jdbc.queryForObject("SELECT id FROM wallet WHERE owner_id = ?", UUID.class, actor);
	}

	public UUID account(UUID wallet, String kind) {
		if (wallet == null) throw Money.invalid();
		return jdbc.query("""
				SELECT a.id FROM ledger_account a JOIN wallet w ON w.id = a.wallet_id
				JOIN app_user u ON u.id = w.owner_id WHERE w.id = ? AND a.kind = ? AND u.enabled
				""", (rs, row) -> rs.getObject("id", UUID.class), wallet, kind).stream().findFirst().orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "The wallet was not found."));
	}

	public record Actor(UUID id, String role) {
		public boolean admin() {
			return "ADMIN".equals(role);
		}
	}
}
