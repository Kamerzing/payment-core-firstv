package com.example.payments2.payment_core2;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.users.CreateUserRequest;
import com.example.payments2.payment_core2.users.LoginRateLimiter;
import com.example.payments2.payment_core2.users.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class UserAccessTests {

	private static final String PASSWORD = "A long test password 2026!";

	@Autowired UserService users;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired LoginRateLimiter rateLimiter;
	@Autowired ObjectMapper json;
	@Value("${local.server.port}") int port;

	@BeforeEach
	void clearTestDatabase() {
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM login_attempt_bucket");
		jdbc.update("DELETE FROM ledger_account WHERE wallet_id IS NOT NULL");
		jdbc.update("DELETE FROM wallet");
		jdbc.update("DELETE FROM app_user");
	}

	@Test
	void bootstrapsOnlyOneAdminWithArgon2idAndAnEmptyWallet() {
		var admin = users.bootstrapAdmin(new CreateUserRequest("Initial.Admin", PASSWORD));
		assertThat(admin.username()).isEqualTo("initial.admin");
		assertThat(admin.role()).isEqualTo("ADMIN");
		String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, admin.id());
		assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
		assertThat(passwords.matches(PASSWORD, hash)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet WHERE owner_id = ?", Integer.class, admin.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_account WHERE wallet_id IS NOT NULL AND balance_minor = 0",
				Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_account WHERE wallet_id IS NULL", Integer.class)).isEqualTo(2);
		assertThatThrownBy(() -> users.bootstrapAdmin(new CreateUserRequest("second.admin", PASSWORD)))
				.isInstanceOf(ApiException.class).hasMessage("An administrator already exists.");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
	}

	@Test
	void serializesConcurrentBootstrapCommands() throws Exception {
		var barrier = new CyclicBarrier(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			List<Callable<Boolean>> commands = List.of(
					() -> bootstrapAtBarrier("first.admin", barrier),
					() -> bootstrapAtBarrier("other.admin", barrier));
			var results = executor.invokeAll(commands, 20, TimeUnit.SECONDS);
			assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet", Integer.class)).isEqualTo(1);
	}

	private boolean bootstrapAtBarrier(String username, CyclicBarrier barrier) throws Exception {
		barrier.await(10, TimeUnit.SECONDS);
		try {
			users.bootstrapAdmin(new CreateUserRequest(username, PASSWORD));
			return true;
		} catch (ApiException exception) {
			assertThat(exception.code()).isEqualTo("ADMIN_ALREADY_EXISTS");
			return false;
		}
	}

	@Test
	void adminCreatesUsersWithPrivateHashesAndPaginatedResults() throws Exception {
		users.bootstrapAdmin(new CreateUserRequest("initial.admin", PASSWORD));
		try (var admin = new Browser()) {
			admin.fetchCsrf();
			assertThat(admin.login("INITIAL.ADMIN", PASSWORD).statusCode()).isEqualTo(200);
			admin.fetchCsrf();
			var created = admin.post("/api/v1/admin/users", credentials("Alice", PASSWORD), true);
			assertThat(created.statusCode()).isEqualTo(201);
			var body = json.readTree(created.body());
			assertThat(body.get("username").asText()).isEqualTo("alice");
			assertThat(body.get("role").asText()).isEqualTo("USER");
			assertThat(created.body()).doesNotContain(PASSWORD, "password", "$argon2");
			assertThat(admin.post("/api/v1/admin/users", credentials("ALICE", PASSWORD), true).statusCode()).isEqualTo(409);
			var suppliedRole = admin.post("/api/v1/admin/users", json.writeValueAsString(java.util.Map.of(
					"username", "fixed.role", "password", PASSWORD, "role", "ADMIN")), true);
			assertThat(suppliedRole.statusCode()).isEqualTo(201);
			assertThat(json.readTree(suppliedRole.body()).get("role").asText()).isEqualTo("USER");
			assertThat(admin.post("/api/v1/admin/users", credentials("bad name", PASSWORD), true).statusCode()).isEqualTo(400);
			assertThat(admin.post("/api/v1/admin/users", credentials("short.password", "short"), true).statusCode()).isEqualTo(400);
			assertThat(admin.post("/api/v1/admin/users", credentials("long.password", "x".repeat(129)), true).statusCode()).isEqualTo(400);
			assertThat(admin.post("/api/v1/admin/users", credentials("no.csrf", PASSWORD), false).statusCode()).isEqualTo(403);
			var page = admin.get("/api/v1/admin/users?limit=1&offset=1");
			assertThat(page.statusCode()).isEqualTo(200);
			assertThat(json.readTree(page.body()).get("items").size()).isEqualTo(1);
			assertThat(page.body()).doesNotContain("password", "$argon2");
			assertThat(admin.get("/api/v1/admin/users?limit=101").statusCode()).isEqualTo(400);
			assertThat(admin.get("/api/v1/admin/users?offset=-1").statusCode()).isEqualTo(400);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet", Integer.class)).isEqualTo(3);
	}

	@Test
	void deniesUserAdministrationAfterRoleChangesInAnExistingSession() throws Exception {
		users.bootstrapAdmin(new CreateUserRequest("initial.admin", PASSWORD));
		try (var admin = new Browser()) {
			admin.fetchCsrf();
			assertThat(admin.login("initial.admin", PASSWORD).statusCode()).isEqualTo(200);
			admin.fetchCsrf();
			jdbc.update("UPDATE app_user SET role = 'USER' WHERE username = 'initial.admin'");
			assertThat(admin.post("/api/v1/admin/users", credentials("blocked.user", PASSWORD), true).statusCode()).isEqualTo(403);
			assertThat(admin.get("/api/v1/admin/users").statusCode()).isEqualTo(403);
			assertThat(json.readTree(admin.get("/api/v1/auth/me").body()).get("role").asText()).isEqualTo("USER");
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
	}

	@Test
	void deniesDisabledAdministratorWithAnExistingSession() throws Exception {
		users.bootstrapAdmin(new CreateUserRequest("initial.admin", PASSWORD));
		try (var admin = new Browser()) {
			admin.fetchCsrf();
			assertThat(admin.login("initial.admin", PASSWORD).statusCode()).isEqualTo(200);
			admin.fetchCsrf();
			jdbc.update("UPDATE app_user SET enabled = FALSE WHERE username = 'initial.admin'");
			assertThat(admin.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
			assertThat(admin.post("/api/v1/admin/users", credentials("blocked.user", PASSWORD), true).statusCode()).isEqualTo(401);
			assertThat(admin.get("/api/v1/admin/users").statusCode()).isEqualTo(401);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
	}

	@Test
	void rotatesLoginSessionAndCsrfThenDestroysSessionAtLogout() throws Exception {
		users.create(new CreateUserRequest("alice", PASSWORD));
		try (var browser = new Browser()) {
			browser.fetchCsrf();
			String anonymousCookie = browser.cookie;
			String oldToken = browser.csrf;
			assertThat(browser.post("/api/v1/auth/login", credentials("alice", PASSWORD), false).statusCode()).isEqualTo(403);
			var loggedIn = browser.login("alice", PASSWORD);
			assertThat(loggedIn.statusCode()).isEqualTo(200);
			assertThat(browser.cookie).isNotEqualTo(anonymousCookie);
			String authenticatedCookie = browser.cookie;
			assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(200);
			assertThat(browser.get("/api/v1/auth/me", anonymousCookie).statusCode()).isEqualTo(401);
			assertThat(browser.post("/api/v1/auth/logout", "{}", true).statusCode()).isEqualTo(403);
			browser.fetchCsrf();
			assertThat(browser.csrf).isNotEqualTo(oldToken);
			String sessionId = new String(Base64.getDecoder().decode(authenticatedCookie.split("=", 2)[1]), StandardCharsets.UTF_8);
			assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
					String.class, sessionId)).isEqualTo("alice");
			List<byte[]> attributes = jdbc.queryForList("""
					SELECT a.attribute_bytes FROM spring_session_attributes a
					JOIN spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ?
					""", byte[].class, sessionId);
			for (byte[] attribute : attributes) {
				assertThat(new String(attribute, StandardCharsets.ISO_8859_1)).doesNotContain(PASSWORD, "$argon2id$");
			}
			var logout = browser.post("/api/v1/auth/logout", "{}", true);
			assertThat(logout.statusCode()).isEqualTo(204);
			assertThat(logout.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.contains("Max-Age=0"));
			assertThat(browser.get("/api/v1/auth/me", authenticatedCookie).statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?", Integer.class, sessionId)).isZero();
		}
	}

	@Test
	void restrictsUsersToTheirOwnWalletAndAdminToItsOwnUserWallet() throws Exception {
		users.bootstrapAdmin(new CreateUserRequest("initial.admin", PASSWORD));
		var alice = users.create(new CreateUserRequest("alice", PASSWORD));
		var bob = users.create(new CreateUserRequest("bob", PASSWORD));
		UUID aliceWallet = jdbc.queryForObject("SELECT id FROM wallet WHERE owner_id = ?", UUID.class, alice.id());
		UUID bobWallet = jdbc.queryForObject("SELECT id FROM wallet WHERE owner_id = ?", UUID.class, bob.id());
		try (var browser = new Browser()) {
			assertThat(browser.get("/api/v1/wallets/me").statusCode()).isEqualTo(401);
			browser.fetchCsrf();
			assertThat(browser.login("alice", PASSWORD).statusCode()).isEqualTo(200);
			var wallet = browser.get("/api/v1/wallets/me");
			assertThat(wallet.statusCode()).isEqualTo(200);
			var body = json.readTree(wallet.body());
			assertThat(body.get("id").asText()).isEqualTo(aliceWallet.toString());
			assertThat(body.get("currency").asText()).isEqualTo("RUB");
			assertThat(body.get("available_minor").isString()).isTrue();
			assertThat(body.get("available_minor").asText()).isEqualTo("0");
			assertThat(body.get("reserved_minor").asText()).isEqualTo("0");
			assertThat(browser.get("/api/v1/wallets/" + aliceWallet).statusCode()).isEqualTo(200);
			var foreign = browser.get("/api/v1/wallets/" + bobWallet);
			assertThat(foreign.statusCode()).isEqualTo(404);
			assertThat(json.readTree(foreign.body()).get("code").asText()).isEqualTo("WALLET_NOT_FOUND");
			assertThat(browser.get("/api/v1/wallets/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
			assertThat(browser.get("/api/v1/admin/users").statusCode()).isEqualTo(403);
			browser.fetchCsrf();
			assertThat(browser.post("/api/v1/admin/users", credentials("intruder", PASSWORD), true).statusCode()).isEqualTo(403);
			assertThat(browser.post("/api/v1/users", credentials("public.user", PASSWORD), true).statusCode()).isEqualTo(404);
		}
		try (var admin = new Browser()) {
			admin.fetchCsrf();
			assertThat(admin.login("initial.admin", PASSWORD).statusCode()).isEqualTo(200);
			assertThat(admin.get("/api/v1/wallets/" + bobWallet).statusCode()).isEqualTo(404);
		}
	}

	@Test
	void returnsSameCredentialErrorForUnknownWrongPasswordAndDisabledUsers() throws Exception {
		users.create(new CreateUserRequest("disabled.user", PASSWORD));
		jdbc.update("UPDATE app_user SET enabled = FALSE WHERE username = 'disabled.user'");
		users.create(new CreateUserRequest("alice", PASSWORD));
		try (var browser = new Browser()) {
			browser.fetchCsrf();
			for (String username : List.of("unknown.user", "disabled.user", "alice")) {
				var response = browser.login(username, "wrong password");
				assertThat(response.statusCode()).isEqualTo(401);
				assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
				assertThat(response.body()).doesNotContain(username, PASSWORD, "password_hash", "disabled");
				assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
			}
			assertThat(browser.get("/api/v1/auth/me").statusCode()).isEqualTo(401);
		}
	}

	@Test
	void limitsLoginAttemptsAcrossSessionsAndUsernameCaseThenReopensExpiredWindow() throws Exception {
		users.create(new CreateUserRequest("alice", PASSWORD));
		for (int i = 0; i < 5; i++) {
			try (var browser = new Browser()) {
				browser.fetchCsrf();
				assertThat(browser.login(i % 2 == 0 ? "Alice" : "ALICE", "wrong password").statusCode()).isEqualTo(401);
			}
		}
		try (var browser = new Browser()) {
			browser.fetchCsrf();
			var limited = browser.login("alice", PASSWORD);
			assertThat(limited.statusCode()).isEqualTo(429);
			assertThat(json.readTree(limited.body()).get("code").asText()).isEqualTo("LOGIN_RATE_LIMITED");
			jdbc.update("UPDATE login_attempt_bucket SET expires_at = clock_timestamp() - interval '1 second'");
			assertThat(browser.login("alice", PASSWORD).statusCode()).isEqualTo(200);
		}
	}

	@Test
	void appliesAddressLimitAcrossUsernamesAndIgnoresForwardedAddress() throws Exception {
		try (var browser = new Browser()) {
			browser.fetchCsrf();
			for (int i = 0; i < 30; i++) {
				assertThat(browser.login("unknown." + i, "wrong password").statusCode()).isEqualTo(401);
			}
			var request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
					.header("Content-Type", "application/json").header("Cookie", browser.cookie)
					.header(browser.header, browser.csrf).header("X-Forwarded-For", "203.0.113.123")
					.POST(HttpRequest.BodyPublishers.ofString(credentials("new.unknown", PASSWORD))).build();
			assertThat(browser.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
		}
	}

	@Test
	void admitsOnlyFiveConcurrentAttemptsForOneAccount() throws Exception {
		var barrier = new CyclicBarrier(10);
		try (var executor = Executors.newFixedThreadPool(10)) {
			var commands = new ArrayList<Callable<Boolean>>();
			for (int i = 0; i < 10; i++) {
				int index = i;
				commands.add(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					try {
						rateLimiter.acquire("same.account", "192.0.2." + index);
						return true;
					} catch (ApiException exception) {
						assertThat(exception.code()).isEqualTo("LOGIN_RATE_LIMITED");
						return false;
					}
				});
			}
			var results = executor.invokeAll(commands, 20, TimeUnit.SECONDS);
			int admitted = 0;
			for (var result : results) if (result.get()) admitted++;
			assertThat(admitted).isEqualTo(5);
		}
		assertThat(jdbc.queryForObject("SELECT max(attempts) FROM login_attempt_bucket", Integer.class)).isEqualTo(5);
		jdbc.update("UPDATE login_attempt_bucket SET expires_at = clock_timestamp() - interval '1 second'");
		rateLimiter.removeExpiredBuckets();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt_bucket", Integer.class)).isZero();
	}

	@Test
	void rollsBackUserAndWalletWhenAccountCreationFails() {
		jdbc.execute("""
				CREATE FUNCTION test_reject_reserved_account() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
					IF NEW.kind = 'RESERVED' THEN RAISE EXCEPTION 'Test account creation failure'; END IF;
					RETURN NEW;
				END $$
				""");
		jdbc.execute("CREATE TRIGGER test_reject_reserved_account BEFORE INSERT ON ledger_account FOR EACH ROW EXECUTE FUNCTION test_reject_reserved_account()");
		try {
			assertThatThrownBy(() -> users.create(new CreateUserRequest("rollback.user", PASSWORD)))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_account WHERE wallet_id IS NOT NULL", Integer.class)).isZero();
		} finally {
			jdbc.execute("DROP TRIGGER test_reject_reserved_account ON ledger_account");
			jdbc.execute("DROP FUNCTION test_reject_reserved_account()");
		}
	}

	@Test
	void databaseRejectsDuplicateWalletsWrongCurrencyAndNegativeUserBalances() {
		var alice = users.create(new CreateUserRequest("alice", PASSWORD));
		UUID walletId = jdbc.queryForObject("SELECT id FROM wallet WHERE owner_id = ?", UUID.class, alice.id());
		assertThatThrownBy(() -> jdbc.update("INSERT INTO wallet (id, owner_id) VALUES (?, ?)", UUID.randomUUID(), alice.id()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE wallet SET currency = 'USD' WHERE id = ?", walletId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET balance_minor = -1 WHERE wallet_id = ?", walletId))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE ledger_account SET balance_minor = -1 WHERE kind = 'FEES'"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_account (id, wallet_id, kind) VALUES (?, ?, 'SETTLEMENT')",
				UUID.randomUUID(), walletId)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private String credentials(String username, String password) {
		return json.writeValueAsString(java.util.Map.of("username", username, "password", password));
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private class Browser implements AutoCloseable {
		final HttpClient client = HttpClient.newHttpClient();
		String cookie;
		String csrf;
		String header;

		void fetchCsrf() throws Exception {
			var response = get("/api/v1/auth/csrf");
			assertThat(response.statusCode()).isEqualTo(200);
			JsonNode body = json.readTree(response.body());
			csrf = body.get("token").asText();
			header = body.get("header_name").asText();
		}

		HttpResponse<String> login(String username, String password) throws Exception {
			return post("/api/v1/auth/login", credentials(username, password), true);
		}

		HttpResponse<String> get(String path) throws Exception {
			return get(path, cookie);
		}

		HttpResponse<String> get(String path, String explicitCookie) throws Exception {
			var builder = HttpRequest.newBuilder(uri(path)).GET();
			if (explicitCookie != null) builder.header("Cookie", explicitCookie);
			return send(builder.build());
		}

		HttpResponse<String> post(String path, String body, boolean includeCsrf) throws Exception {
			var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body));
			if (cookie != null) builder.header("Cookie", cookie);
			if (includeCsrf) builder.header(header, csrf);
			return send(builder.build());
		}

		HttpResponse<String> send(HttpRequest request) throws Exception {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			response.headers().firstValue("Set-Cookie").ifPresent(value -> cookie = value.split(";", 2)[0]);
			return response;
		}

		@Override
		public void close() {
			client.close();
		}
	}
}
