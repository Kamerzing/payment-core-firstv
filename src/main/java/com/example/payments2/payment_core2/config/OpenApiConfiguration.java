package com.example.payments2.payment_core2.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

	@Bean
	OpenAPI paymentApi() {
		return new OpenAPI().info(new Info()
				.title("Payment Core API")
				.version("v1")
				.description("Учебные рублёвые кошельки. Используются только моделируемые средства."));
	}
}
