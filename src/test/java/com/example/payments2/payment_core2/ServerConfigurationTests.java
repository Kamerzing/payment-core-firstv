package com.example.payments2.payment_core2;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "server"})
@Import(PostgresTestConfiguration.class)
class ServerConfigurationTests {

	@Autowired
	MockMvc mvc;

	@Value("${local.server.port}")
	int port;

	@Test
	void requiresSecureHttpOnlySessionCookieOnServer() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			var response = client.send(HttpRequest.newBuilder(
					URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf")).GET().build(),
					HttpResponse.BodyHandlers.discarding());
			assertThat(response.statusCode()).isEqualTo(200);
			String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
			assertThat(cookie).startsWith("PAYMENT_SESSION=").contains("Secure", "HttpOnly", "SameSite=Lax");
		}
	}

	@Test
	void deniesAnonymousAccessToDocumentationAndInternalActuatorEndpoints() throws Exception {
		for (String path : new String[] {"/v3/api-docs", "/swagger-ui/index.html", "/actuator/env"}) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		}
	}

	@Test
	void ignoresForwardedHeadersFromConnectionsOutsideTrustedProxy() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			var response = client.send(HttpRequest.newBuilder(
					URI.create("http://127.0.0.1:" + port + "/api/v1/status"))
					.header("X-Forwarded-For", "203.0.113.77")
					.header("X-Forwarded-Proto", "https").GET().build(), HttpResponse.BodyHandlers.discarding());
			assertThat(response.statusCode()).isEqualTo(200);
			// Spring Security emits HSTS only when the request is actually secure.
			assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
		}
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void doesNotRegisterDocumentationOrSensitiveActuatorEndpointsOnServer() throws Exception {
		for (String path : new String[] {"/v3/api-docs", "/swagger-ui/index.html", "/actuator/env"}) {
			mvc.perform(get(path)).andExpect(status().isNotFound());
		}
	}
}
