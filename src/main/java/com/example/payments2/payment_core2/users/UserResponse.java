package com.example.payments2.payment_core2.users;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserResponse(UUID id, String username, String role,
		@JsonProperty("created_at") Instant createdAt) {
}
