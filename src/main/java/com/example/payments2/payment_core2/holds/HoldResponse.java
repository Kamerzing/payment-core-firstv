package com.example.payments2.payment_core2.holds;

import java.time.Instant;
import java.util.UUID;

public record HoldResponse(UUID id, UUID wallet_id, UUID target_wallet_id, String type, String state,
		String amount_minor, String fee_minor, int fee_basis_points, String total_minor,
		UUID creation_operation_id, UUID completion_operation_id, Instant created_at, Instant expires_at, Instant completed_at) {
}
