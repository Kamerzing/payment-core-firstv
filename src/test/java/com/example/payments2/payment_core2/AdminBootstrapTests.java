package com.example.payments2.payment_core2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class AdminBootstrapTests {

	@Autowired PostgreSQLContainer postgres;
	@Autowired JdbcTemplate jdbc;
	@Autowired org.springframework.context.ApplicationContext context;
	@TempDir Path temporaryDirectory;

	@Test
	void packagedCommandCreatesAdminWithoutHttpAndRefusesSecondBootstrap() throws Exception {
		assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
		var created = runCommand("first.admin", "An initial test password 2026!", "first.log");
		assertThat(created.exitCode()).withFailMessage(created.output()).isZero();
		assertThat(created.output()).contains("Initial administrator created.")
				.doesNotContain("An initial test password 2026!", "$argon2id$", "Tomcat started");
		assertThat(jdbc.queryForObject("SELECT username FROM app_user WHERE role = 'ADMIN'", String.class))
				.isEqualTo("first.admin");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet", Integer.class)).isEqualTo(1);
		var duplicate = runCommand("other.admin", "A different test password!", "second.log");
		assertThat(duplicate.exitCode()).isNotZero();
		assertThat(duplicate.output()).contains("An administrator already exists.")
				.doesNotContain("A different test password!", "$argon2id$");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
		var invalid = runCommand("bad username", "short", "invalid.log");
		assertThat(invalid.exitCode()).isNotZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
	}

	private CommandResult runCommand(String username, String password, String logName) throws Exception {
		Path output = temporaryDirectory.resolve(logName);
		var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-jar", System.getProperty("payment.bootstrap.jar"), "--bootstrap-admin", "--credentials-stdin");
		builder.environment().put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
		builder.environment().put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
		builder.environment().put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
		builder.environment().put("SPRING_MAIN_BANNER_MODE", "off");
		builder.environment().put("LOGGING_LEVEL_ROOT", "WARN");
		builder.redirectErrorStream(true).redirectOutput(output.toFile());
		var process = builder.start();
		try {
			try (var input = process.getOutputStream()) {
				input.write((username + "\n" + password + "\n").getBytes(StandardCharsets.UTF_8));
			}
			assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("Bootstrap command must terminate").isTrue();
			return new CommandResult(process.exitValue(), Files.readString(output));
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private record CommandResult(int exitCode, String output) {
	}
}
