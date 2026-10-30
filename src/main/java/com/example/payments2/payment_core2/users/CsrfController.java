package com.example.payments2.payment_core2.users;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

	@GetMapping("/api/v1/auth/csrf")
	public CsrfResponse csrf(CsrfToken csrfToken) {
		return new CsrfResponse(csrfToken.getToken(), csrfToken.getHeaderName());
	}

	public record CsrfResponse(String token, @JsonProperty("header_name") String headerName) {
	}
}
