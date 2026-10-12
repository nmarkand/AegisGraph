package com.cts.aegisgraph.graphweaver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Dev-only: publishes synthetic payments to the real input topic, so the full Kafka path is exercised. */
@Service
@ConditionalOnProperty(name = "aegis.graphweaver.simulator-enabled", havingValue = "true")
public class TransactionSimulator {

    private final StreamBridge bridge;
    private final String topic;

    public TransactionSimulator(StreamBridge bridge,
                                @Value("${spring.cloud.stream.bindings.transactionIn-in-0.destination}") String topic) {
        this.bridge = bridge;
        this.topic = topic;
    }

    /** A -> n intermediaries (CHF 9,800, 10s apart) -> B (CHF 9,750, ~60s later). */
    public int fanOutFanIn(int intermediaries) {
        long base = base(), start = System.currentTimeMillis() - Duration.ofMinutes(8).toMillis();
        String a = iban(base), b = iban(base + 1);
        int sent = 0;
        for (int k = 0; k < intermediaries; k++) {
            String m = iban(base + 100 + k);
            long t = start + k * 10_000L;
            sent += send(a, m, 9_800, t) + send(m, b, 9_750, t + 60_000 + ThreadLocalRandom.current().nextInt(5_000));
        }
        return sent;
    }

    /** Ring of `hops` accounts, one transfer every 5 minutes, ~1% skimmed per hop, closing at the origin. */
    public int circular(int hops) {
        long base = base(), start = System.currentTimeMillis() - Duration.ofMinutes(40).toMillis();
        double amount = 9_900;
        int sent = 0;
        for (int k = 0; k < hops; k++) {
            sent += send(iban(base + k), iban(base + (k + 1) % hops), amount, start + k * Duration.ofMinutes(5).toMillis());
            amount *= 0.99;
        }
        return sent;
    }

    /** Random small transfers between 50 accounts - should NOT trigger anything. */
    public int noise(int count) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int sent = 0;
        for (int k = 0; k < count; k++) {
            int from = r.nextInt(50), to = (from + 1 + r.nextInt(49)) % 50;
            sent += send(iban(900_000_000L + from), iban(900_000_000L + to),
                    50 + r.nextDouble() * 4_950, System.currentTimeMillis() - r.nextLong(Duration.ofMinutes(50).toMillis()));
        }
        return sent;
    }

    private int send(String from, String to, double amount, long ts) {
        var e = new TransactionEvent(UUID.randomUUID().toString(), from, "AEGICHZZ", to, "AEGICHZZ",
                BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), "CHF", ts, "SIC");
        return bridge.send(topic, e) ? 1 : 0;
    }

    private static String iban(long n) { return "CH93" + String.format("%017d", n); }
    private static long base() { return ThreadLocalRandom.current().nextLong(1_000, 800_000) * 1_000; }
}
