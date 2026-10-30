package com.example.payments2.payment_core2.holds;

import java.util.UUID;
import com.example.payments2.payment_core2.operations.StrictStringDeserializer;
import com.example.payments2.payment_core2.operations.StrictIntegerDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateHoldRequest(@NotNull String type, UUID target_wallet_id,
		@JsonDeserialize(using = StrictStringDeserializer.class) String amount_minor,
		@JsonDeserialize(using = StrictStringDeserializer.class) String expected_fee_minor,
		@JsonDeserialize(using = StrictIntegerDeserializer.class) @NotNull @Min(0) @Max(10000) Integer expected_fee_basis_points) {
}
