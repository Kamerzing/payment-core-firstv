package com.example.payments2.payment_core2.users;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

	private final JdbcTemplate jdbc;

	public UserRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public static String normalizeUsername(String username) {
		return username.toLowerCase(Locale.ROOT);
	}

	public Optional<UserDetails> findCredentials(String username) {
		return jdbc.query("SELECT username, password_hash, role, enabled FROM app_user WHERE username = ?",
				(rs, row) -> (UserDetails) User.withUsername(rs.getString("username"))
						.password(rs.getString("password_hash")).roles(rs.getString("role"))
						.disabled(!rs.getBoolean("enabled")).build(), normalizeUsername(username))
				.stream().findFirst();
	}

	public Optional<UserResponse> findByUsername(String username) {
		return jdbc.query("SELECT id, username, role, created_at FROM app_user WHERE username = ? AND enabled",
				(rs, row) -> new UserResponse(rs.getObject("id", UUID.class), rs.getString("username"),
						rs.getString("role"), rs.getTimestamp("created_at").toInstant()), normalizeUsername(username))
				.stream().findFirst();
	}

	public UserResponse insert(String username, String passwordHash, String role) {
		UUID id = UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, ?, ?)
				RETURNING id, username, role, created_at
				""", (rs, row) -> new UserResponse(rs.getObject("id", UUID.class), rs.getString("username"),
						rs.getString("role"), rs.getTimestamp("created_at").toInstant()),
				id, normalizeUsername(username), passwordHash, role);
	}

	public List<UserResponse> list(int limit, int offset) {
		return jdbc.query("SELECT id, username, role, created_at FROM app_user ORDER BY created_at, id LIMIT ? OFFSET ?",
				(rs, row) -> new UserResponse(rs.getObject("id", UUID.class), rs.getString("username"),
						rs.getString("role"), rs.getTimestamp("created_at").toInstant()), limit, offset);
	}

	public boolean hasAdminUnderBootstrapLock() {
		jdbc.execute("SELECT pg_advisory_xact_lock(702202602)");
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM app_user WHERE role = 'ADMIN')", Boolean.class));
	}
}
