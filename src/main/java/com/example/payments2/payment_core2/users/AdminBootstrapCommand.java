package com.example.payments2.payment_core2.users;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.example.payments2.payment_core2.PaymentCore2Application;
import jakarta.validation.Validation;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public final class AdminBootstrapCommand {

	private AdminBootstrapCommand() {
	}

	public static void run(String[] args) throws Exception {
		CreateUserRequest credentials = readCredentials(Arrays.asList(args).contains("--credentials-stdin"));
		try (var factory = Validation.buildDefaultValidatorFactory()) {
			if (!factory.getValidator().validate(credentials).isEmpty()) {
				throw new IllegalArgumentException("Username must be 3-64 ASCII characters; password must be 12-128 characters.");
			}
		}
		String[] springArgs = Arrays.stream(args)
				.filter(arg -> !arg.equals("--bootstrap-admin") && !arg.equals("--credentials-stdin")).toArray(String[]::new);
		var application = new SpringApplication(PaymentCore2Application.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		try (var context = application.run(springArgs)) {
			context.getBean(UserService.class).bootstrapAdmin(credentials);
			System.out.println("Initial administrator created.");
		}
	}

	private static CreateUserRequest readCredentials(boolean stdin) throws Exception {
		if (stdin) {
			var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			return new CreateUserRequest(reader.readLine(), reader.readLine());
		}
		Console console = System.console();
		if (console == null) {
			throw new IllegalStateException("Run java -jar in a terminal, or use --credentials-stdin with two input lines.");
		}
		String username = console.readLine("Administrator username: ");
		char[] password = console.readPassword("Password (12-128 characters): ");
		char[] confirmation = console.readPassword("Confirm password: ");
		try {
			if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
				throw new IllegalArgumentException("Passwords do not match.");
			}
			return new CreateUserRequest(username, new String(password));
		} finally {
			if (password != null) Arrays.fill(password, '\0');
			if (confirmation != null) Arrays.fill(confirmation, '\0');
		}
	}
}
