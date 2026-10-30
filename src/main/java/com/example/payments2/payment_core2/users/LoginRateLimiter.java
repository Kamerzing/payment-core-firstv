package com.example.payments2.payment_core2.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import com.example.payments2.payment_core2.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginRateLimiter {

	private final JdbcTemplate jdbc;
	private final int accountLimit;
	private final int addressLimit;
	private final long windowSeconds;

	public LoginRateLimiter(JdbcTemplate jdbc,
			@Value("${payment.security.login.account-limit:5}") int accountLimit,
			@Value("${payment.security.login.address-limit:30}") int addressLimit,
			@Value("${payment.security.login.window:15m}") Duration window) {
		if (accountLimit < 1 || addressLimit < 1 || window.toSeconds() < 1) {
			throw new IllegalArgumentException("Login limits and window must be positive.");
		}
		this.jdbc = jdbc;
		this.accountLimit = accountLimit;
		this.addressLimit = addressLimit;
		this.windowSeconds = window.toSeconds();
	}

	@Transactional
	public void acquire(String username, String remoteAddress) {
		// Count all admitted attempts before password hashing; successful logins do not reset the window.
		acquireBucket("address:" + remoteAddress, addressLimit);
		acquireBucket("account:" + UserRepository.normalizeUsername(username), accountLimit);
	}

	private void acquireBucket(String key, int limit) {
		var admitted = jdbc.query("""
				INSERT INTO login_attempt_bucket (bucket_key, attempts, expires_at)
				VALUES (?, 1, clock_timestamp() + make_interval(secs => ?))
				ON CONFLICT (bucket_key) DO UPDATE SET
					attempts = CASE WHEN login_attempt_bucket.expires_at <= clock_timestamp()
						THEN 1 ELSE login_attempt_bucket.attempts + 1 END,
					expires_at = CASE WHEN login_attempt_bucket.expires_at <= clock_timestamp()
						THEN clock_timestamp() + make_interval(secs => ?) ELSE login_attempt_bucket.expires_at END
				WHERE login_attempt_bucket.expires_at <= clock_timestamp() OR login_attempt_bucket.attempts < ?
				RETURNING attempts
				""", (rs, row) -> rs.getInt(1), digest(key), windowSeconds, windowSeconds, limit);
		if (admitted.isEmpty()) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "Too many login attempts. Try later.");
		}
	}

	@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
	public void removeExpiredBuckets() {
		jdbc.update("""
				DELETE FROM login_attempt_bucket WHERE bucket_key IN
				(SELECT bucket_key FROM login_attempt_bucket WHERE expires_at <= clock_timestamp() LIMIT 1000)
				AND expires_at <= clock_timestamp()
				""");
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}
}
