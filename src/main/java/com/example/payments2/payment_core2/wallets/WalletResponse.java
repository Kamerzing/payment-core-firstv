package com.example.payments2.payment_core2.wallets;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WalletResponse(UUID id, @JsonProperty("owner_id") UUID ownerId, String currency,
		@JsonProperty("available_minor") String availableMinor,
		@JsonProperty("reserved_minor") String reservedMinor) {
}
