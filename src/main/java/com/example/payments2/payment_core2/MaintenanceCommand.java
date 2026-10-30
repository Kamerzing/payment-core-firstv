package com.example.payments2.payment_core2;

import java.util.Arrays;
import com.example.payments2.payment_core2.ledger.ReconciliationService;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

final class MaintenanceCommand {
	private MaintenanceCommand() {
	}

	static void run(String[] args) {
		boolean migrate = Arrays.asList(args).contains("--migrate-only");
		boolean reconcile = Arrays.asList(args).contains("--reconcile-only");
		if (migrate == reconcile || Arrays.asList(args).contains("--bootstrap-admin")) {
			throw new IllegalArgumentException("Choose exactly one maintenance command.");
		}
		var application = new SpringApplication(PaymentCore2Application.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		String[] springArgs = Arrays.stream(args)
				.filter(arg -> !arg.equals("--migrate-only") && !arg.equals("--reconcile-only")).toArray(String[]::new);
		try (var context = application.run(springArgs)) {
			if (migrate) {
				context.getBean(Flyway.class).validate();
				System.out.println("Database migrations validated.");
			} else {
				var report = context.getBean(ReconciliationService.class).reconcile();
				if (!report.consistent()) throw new IllegalStateException("Ledger reconciliation failed.");
				System.out.println("Ledger reconciliation passed.");
			}
		}
	}
}
