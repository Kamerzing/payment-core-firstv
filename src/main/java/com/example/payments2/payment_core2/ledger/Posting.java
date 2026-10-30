package com.example.payments2.payment_core2.ledger;

import java.util.UUID;

public record Posting(UUID accountId, long amountMinor) {
}
