package com.example.payments2.payment_core2;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import javax.sql.DataSource;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, PaymentCore2ApplicationTests.TestEndpoints.class})
class PaymentCore2ApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Autowired
	DataSource dataSource;

	@Autowired
	ObjectMapper objectMapper;

	@Value("${local.server.port}")
	int port;

	@Test
	void servesBundledWebPanelWithoutAuthenticationAndKeepsApiProtected() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk());
		mvc.perform(get("/index.html")).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(containsString("id=\"root\"")))
				.andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
		mvc.perform(get("/api/v1/holds")).andExpect(status().isUnauthorized());
	}

	@Test
	void migratesEmptyPostgres18AndDoesNotReapplyMigration() {
		assertThat(jdbc.queryForObject("SHOW server_version_num", Integer.class)).isBetween(180000, 189999);
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
		flyway.validate();
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
				.isEqualTo(5);
	}

	@Test
	void upgradesVersionOneWithoutLosingExistingSessions() {
		String schema = "migration_upgrade_test";
		try {
			Flyway.configure().dataSource(dataSource).schemas(schema).target("1").load().migrate();
			jdbc.update("""
					INSERT INTO migration_upgrade_test.spring_session
					(primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
					VALUES (?, ?, 1, 1, 1800, 1800001)
					""", java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());
			var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).load();
			assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM migration_upgrade_test.spring_session", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM migration_upgrade_test.ledger_account", Integer.class)).isEqualTo(2);
			assertThat(upgrade.migrate().migrationsExecuted).isZero();
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS migration_upgrade_test CASCADE");
		}
	}

	@Test
	void exposesStatusAndReadinessWithoutDatabaseDetails() throws Exception {
		mvc.perform(get("/api/v1/status").header("X-Request-ID", "local-check-1"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Request-ID", "local-check-1"))
				.andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
		mvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk());
	}

	@Test
	void exposesOpenApiAndSwaggerLocally() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/status']").exists());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void returnsJsonForAnonymousRequestsWithoutRedirectOrSavedSession() throws Exception {
		mvc.perform(get("/api/v1/wallets/me").header("X-Request-ID", "denied-42"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.request_id").value("denied-42"))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	@WithMockUser(roles = "USER")
	void rejectsUserFromAdminEndpoints() throws Exception {
		mvc.perform(get("/api/v1/admin/users"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void returnsConsistentCsrfError() throws Exception {
		mvc.perform(post("/api/v1/transfers").header("X-Request-ID", "csrf-check"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CSRF_INVALID"))
				.andExpect(jsonPath("$.request_id").value("csrf-check"));
	}

	@Test
	void replacesUnsafeAndAmbiguousRequestIds() throws Exception {
		for (String value : new String[] {"x".repeat(65), "unsafe value", "bad\r\nheader"}) {
			var response = mvc.perform(get("/api/v1/wallets/me").header("X-Request-ID", value))
					.andExpect(status().isUnauthorized()).andReturn().getResponse();
			String id = response.getHeader("X-Request-ID");
			assertThat(id).matches("[0-9a-f-]{36}");
			assertThat(objectMapper.readTree(response.getContentAsString()).get("request_id").asText()).isEqualTo(id);
		}
		mvc.perform(get("/api/v1/status").header("X-Request-ID", "first", "second"))
				.andExpect(header().string("X-Request-ID", not("first")));
	}

	@Test
	@WithMockUser
	void normalizesValidationAndMalformedJsonErrors() throws Exception {
		for (String payload : new String[] {"{\"name\":\"\"}", "{invalid-json"}) {
			mvc.perform(post("/api/v1/test/validation").with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content(payload))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
					.andExpect(jsonPath("$.request_id").isNotEmpty())
					.andExpect(jsonPath("$.trace").doesNotExist());
		}
	}

	@Test
	@WithMockUser
	void normalizesMissingRoutesAndUnsupportedMethods() throws Exception {
		mvc.perform(get("/api/v1/missing"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(post("/api/v1/status").with(csrf()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	@WithMockUser
	void hidesInternalExceptionDetails() throws Exception {
		mvc.perform(get("/api/v1/test/failure"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret-database-detail"))));
	}

	@Test
	void persistsCsrfSessionInPostgresAndAcceptsTokenOnlyWithItsCookie() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			var response = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
			assertThat(setCookie).contains("PAYMENT_SESSION=", "HttpOnly", "SameSite=Lax").doesNotContain("Secure");
			String cookie = setCookie.split(";", 2)[0];
			String sessionId = new String(java.util.Base64.getDecoder().decode(cookie.split("=", 2)[1]),
					java.nio.charset.StandardCharsets.UTF_8);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?",
					Integer.class, sessionId)).isEqualTo(1);
			var json = objectMapper.readTree(response.body());
			String header = json.get("header_name").asText();
			String token = json.get("token").asText();
			var acceptedCsrf = client.send(HttpRequest.newBuilder(uri("/api/v1/transfers"))
					.header("Cookie", cookie).header(header, token).POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString());
			// CSRF passed; the next filter correctly requires authentication.
			assertThat(acceptedCsrf.statusCode()).isEqualTo(401);
			var missingCookie = client.send(HttpRequest.newBuilder(uri("/api/v1/transfers"))
					.header(header, token).POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(missingCookie.statusCode()).isEqualTo(403);
			assertThat(objectMapper.readTree(missingCookie.body()).get("code").asText()).isEqualTo("CSRF_INVALID");
		}
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	@TestComponent
	@RestController
	static class TestEndpoints {

		@PostMapping("/api/v1/test/validation")
		void validate(@Valid @RequestBody TestInput input) {
		}

		@GetMapping("/api/v1/test/failure")
		void fail() {
			throw new IllegalStateException("secret-database-detail");
		}

		record TestInput(@NotBlank String name) {
		}
	}
}
