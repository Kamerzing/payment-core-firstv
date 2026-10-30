package com.example.payments2.payment_core2.users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
		@NotBlank @Size(min = 12, max = 128) String password) {

	@Override
	public String toString() {
		return "CreateUserRequest[redacted]";
	}
}
