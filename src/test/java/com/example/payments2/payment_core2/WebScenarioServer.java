package com.example.payments2.payment_core2;

import java.util.Map;
import com.example.payments2.payment_core2.users.CreateUserRequest;
import com.example.payments2.payment_core2.users.UserService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Ephemeral fixture server for browser tests; never uses the local .env or database. */
public class WebScenarioServer {
	public static void main(String[] args) {
		var postgres = new PostgreSQLContainer("postgres:18.6-alpine");
		postgres.start();
		Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
		Map<String,Object> properties = Map.of("spring.datasource.url",postgres.getJdbcUrl(),
				"spring.datasource.username",postgres.getUsername(),"spring.datasource.password",postgres.getPassword(),
				"server.port","18080", "payment.security.login.account-limit",100,
				"payment.security.login.address-limit",1000);
		try (var setup = new SpringApplicationBuilder(PaymentCore2Application.class).profiles("test")
				.web(WebApplicationType.NONE).initializers(context -> context.getEnvironment().getPropertySources()
						.addFirst(new MapPropertySource("browser-fixture", properties))).run("--spring.profiles.active=test")) {
			var users = setup.getBean(UserService.class);
			users.bootstrapAdmin(new CreateUserRequest("browser.admin", "Browser fixture password!"));
			users.create(new CreateUserRequest("browser.alice", "Browser fixture password!"));
			users.create(new CreateUserRequest("browser.bob", "Browser fixture password!"));
		}
		new SpringApplicationBuilder(PaymentCore2Application.class).profiles("test")
				.initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("browser-fixture", properties)))
				.run("--spring.profiles.active=test", "--server.address=127.0.0.1");
	}
}
