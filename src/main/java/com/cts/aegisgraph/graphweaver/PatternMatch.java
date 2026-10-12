package com.cts.aegisgraph.graphweaver;

import java.util.List;

/** Published as a Spring application event; Step 4 turns it into AML_ALERT_EVENT. */
public record PatternMatch(
        Signature signature,
        List<String> accountRefs,   // subgraph node ids ("account:<IBAN>")
        List<String> edgeIds,       // TRANSFERRED_TO edge ids (evidence)
        double totalAmount,
        long firstTimestamp,
        long lastTimestamp,
        int riskScore) {            // 0-100

    public enum Signature { FAN_OUT_FAN_IN, CIRCULAR_VELOCITY }
}
