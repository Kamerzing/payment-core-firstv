package com.example.payments2.payment_core2.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

	@GetMapping("/api/v1/status")
	public StatusResponse status() {
		return new StatusResponse("UP");
	}

	public record StatusResponse(String status) {
	}
}
