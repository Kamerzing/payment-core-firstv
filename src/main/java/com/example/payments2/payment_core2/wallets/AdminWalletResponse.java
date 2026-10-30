package com.example.payments2.payment_core2.wallets;

import java.util.UUID;

public record AdminWalletResponse(UUID id, UUID owner_id, String owner_username, boolean enabled,
		String currency, String available_minor, String reserved_minor) {
}
