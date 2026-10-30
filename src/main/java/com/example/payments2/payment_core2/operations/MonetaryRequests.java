package com.example.payments2.payment_core2.operations;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class MonetaryRequests {
	private MonetaryRequests() {
	}

	public record Deposit(@NotNull UUID wallet_id,
			@JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor) {
	}

	public record Transfer(@NotNull UUID target_wallet_id,
			@JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor,
			@JsonDeserialize(using = StrictStringDeserializer.class) String expected_fee_minor,
			@JsonDeserialize(using = StrictIntegerDeserializer.class) @NotNull @Min(0) @Max(10000) Integer expected_fee_basis_points) {
	}

	public record Withdrawal(@JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor,
			@JsonDeserialize(using = StrictStringDeserializer.class) String expected_fee_minor,
			@JsonDeserialize(using = StrictIntegerDeserializer.class) @NotNull @Min(0) @Max(10000) Integer expected_fee_basis_points) {
	}

	public record Quote(@NotNull String type,
			@JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor) {
	}

	public record Reversal(@NotNull UUID operation_id) {
	}
}
