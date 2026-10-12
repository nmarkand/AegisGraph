package com.cts.aegisgraph.graphweaver;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire format published to the alert topic. */
public record AmlAlertEvent(
        String alertId,
        String eventType,            // always "AML_ALERT_EVENT" (routing key for consumers)
        PatternMatch.Signature signature,
        Severity severity,
        int riskScore,               // 0-100
        List<String> accountRefs,    // affected subgraph node ids
        List<String> edgeIds,        // affected TRANSFERRED_TO edge ids
        double totalAmount,
        long firstTimestamp,
        long lastTimestamp,
        Instant detectedAt) {

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public static AmlAlertEvent from(PatternMatch m) {
        Severity s = m.riskScore() >= 80 ? Severity.CRITICAL
                : m.riskScore() >= 60 ? Severity.HIGH
                : m.riskScore() >= 40 ? Severity.MEDIUM : Severity.LOW;
        return new AmlAlertEvent(UUID.randomUUID().toString(), "AML_ALERT_EVENT", m.signature(), s, m.riskScore(),
                m.accountRefs(), m.edgeIds(), m.totalAmount(), m.firstTimestamp(), m.lastTimestamp(), Instant.now());
    }
}
