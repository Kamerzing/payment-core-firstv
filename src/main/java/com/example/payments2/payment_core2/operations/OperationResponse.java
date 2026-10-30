package com.example.payments2.payment_core2.operations;

import java.time.Instant;
import java.util.UUID;

public record OperationResponse(UUID id, String type, String state, UUID source_wallet_id,
		UUID target_wallet_id, String amount_minor, String fee_minor, int fee_basis_points,
		String total_minor, UUID original_operation_id, Instant created_at, Instant posted_at) {
}
