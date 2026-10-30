package com.example.payments2.payment_core2.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.payments2.payment_core2.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public IdempotencyService(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public <T> T execute(UUID actor, String key, String canonicalRequest, Class<T> responseType, Supplier<T> command) {
		if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "A valid Idempotency-Key is required.");
		}
		String fingerprint = hash(canonicalRequest);
		// The unique index waits for a concurrent transaction. A rollback lets the waiter execute.
		int inserted = jdbc.update("""
				INSERT INTO command_result (actor_id, idempotency_key, request_hash) VALUES (?, ?, ?)
				ON CONFLICT (actor_id, idempotency_key) DO NOTHING
				""", actor, key, fingerprint);
		if (inserted == 0) {
			var saved = jdbc.queryForObject("SELECT request_hash, response_body FROM command_result WHERE actor_id = ? AND idempotency_key = ?",
					(rs, row) -> new Saved(rs.getString("request_hash"), rs.getString("response_body")), actor, key);
			if (!fingerprint.equals(saved.hash())) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "The key was used for a different request.");
			}
			return json.readValue(saved.body(), responseType);
		}
		T response = command.get();
		jdbc.update("UPDATE command_result SET response_body = ? WHERE actor_id = ? AND idempotency_key = ?",
				json.writeValueAsString(response), actor, key);
		return response;
	}

	private static String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Saved(String hash, String body) {
	}
}
