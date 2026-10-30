package com.example.payments2.payment_core2;

import java.util.Arrays;

import com.example.payments2.payment_core2.users.AdminBootstrapCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentCore2Application {

	public static void main(String[] args) throws Exception {
		if (Arrays.asList(args).contains("--migrate-only") || Arrays.asList(args).contains("--reconcile-only")) {
			MaintenanceCommand.run(args);
			return;
		}
		if (Arrays.asList(args).contains("--bootstrap-admin")) {
			AdminBootstrapCommand.run(args);
			return;
		}
		SpringApplication.run(PaymentCore2Application.class, args);
	}

}
