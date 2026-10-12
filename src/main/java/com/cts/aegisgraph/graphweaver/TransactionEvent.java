package com.cts.aegisgraph.graphweaver;

import java.math.BigDecimal;
import java.util.Objects;

/** Payment message on the stream. Invalid payloads throw here and are routed to the DLQ by the binder. */
public record TransactionEvent(
        String transactionId,
        String sourceIban, String sourceBic,
        String targetIban, String targetBic,
        BigDecimal amount, String currency,
        long timestamp,            // epoch ms (event time)
        String channel) {          // SWIFT, SIC, SEPA, ...

    public TransactionEvent {
        Objects.requireNonNull(transactionId, "transactionId");
        sourceIban = iban(sourceIban);
        targetIban = iban(targetIban);
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be > 0");
        if (currency == null || currency.length() != 3) throw new IllegalArgumentException("currency must be ISO-4217");
        currency = currency.toUpperCase();
        if (timestamp <= 0) throw new IllegalArgumentException("timestamp must be epoch ms");
        channel = channel == null ? "UNKNOWN" : channel.toUpperCase();
    }

    public String sourceRef() { return "account:" + sourceIban; }
    public String targetRef() { return "account:" + targetIban; }

    private static String iban(String s) {
        return Objects.requireNonNull(s, "iban").replaceAll("\\s", "").toUpperCase();
    }
}
