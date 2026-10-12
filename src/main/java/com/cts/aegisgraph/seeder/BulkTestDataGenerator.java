package com.cts.aegisgraph.seeder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.cognizant.aegisgraph.*;
import com.cognizant.aegisgraph.graphweaver.*;
import com.cts.aegisgraph.graphweaver.*;
import com.cts.aegisgraph.shadowtrace.ExtractedOwnership;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import static com.cts.aegisgraph.graphweaver.TransactionGraphService.EDGE;


/** Demo/stress tooling. Only exists when aegis.seeder.enabled=true. */
@Service
@ConditionalOnProperty(name = "aegis.seeder.enabled", havingValue = "true")
public class BulkTestDataGenerator {

    public enum Scale {
        //    persons trusts companies noiseTx fan circ
        SMALL(20, 10, 70, 500, 1, 1),
        MEDIUM(60, 30, 210, 2_000, 2, 2),
        LARGE(120, 80, 400, 5_000, 3, 3);   // 600 entities, ~5,100 transactions

        final int persons, trusts, companies, noiseTx, fanPatterns, circPatterns;

        Scale(int persons, int trusts, int companies, int noiseTx, int fanPatterns, int circPatterns) {
            this.persons = persons; this.trusts = trusts; this.companies = companies;
            this.noiseTx = noiseTx; this.fanPatterns = fanPatterns; this.circPatterns = circPatterns;
        }
    }

    public record SeedReport(
            String scale, long seed, String mode,
            Map<String, Long> nodesCreated, Map<String, Long> edgesWritten,
            int transactionsPublished, long transactionsIngested, boolean ingestTimedOut,
            Map<String, Integer> patternsPlanted, Map<String, Integer> patternsDetected, int detectionTriggers,
            Map<String, Long> timingsMs, double ingestThroughputTxPerSec) {}

    private interface Emit { void tx(long from, long to, double amount, long ts); }

    private static final Logger log = LoggerFactory.getLogger(BulkTestDataGenerator.class);
    private static final Duration INGEST_TIMEOUT = Duration.ofSeconds(120);
    private static final List<String> FIRST = List.of("Anna", "Luca", "Marc", "Elena", "Viktor", "Sofia", "Hans", "Mei", "Omar", "Ivana", "Pierre", "Yuki");
    private static final List<String> LAST = List.of("Keller", "Rossi", "Petrov", "Müller", "Tan", "Haddad", "Novak", "Moreau", "Ivanov", "Weber", "Costa", "Sato");
    private static final List<String> COUNTRIES = List.of("CH", "DE", "IT", "RU", "SG", "AE", "FR", "CY", "LI", "BR");
    private static final List<String> JURISDICTIONS = List.of("BVI", "Cayman Islands", "Panama", "Jersey", "Liechtenstein", "Cyprus", "Seychelles", "Luxembourg", "Switzerland", "Singapore");
    private static final List<String> LAW = List.of("Jersey", "Liechtenstein", "Cayman Islands", "Switzerland", "BVI");
    private static final List<String> WORDS = List.of("Aurora", "Zenith", "Meridian", "Atlas", "Helvetia", "Orion", "Falcon", "Nimbus", "Vertex", "Solstice");
    private static final List<String> KIND = List.of("Holdings", "Capital", "Ventures", "Trading", "Invest", "Assets");
    private static final String[] CHANNELS = {"SIC", "SWIFT", "SEPA"};

    private final GraphTraversalSource g;
    private final GraphPersistenceService persistence;
    private final TransactionGraphService txGraph;
    private final PatternDetectionService detection;
    private final StreamBridge bridge;
    private final GraphWeaverProperties props;
    private final String topic;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger runs = new AtomicInteger();
    private final Map<PatternMatch.Signature, AtomicInteger> detected = new ConcurrentHashMap<>();

    public BulkTestDataGenerator(GraphTraversalSource g, GraphPersistenceService persistence,
                                 TransactionGraphService txGraph, PatternDetectionService detection,
                                 StreamBridge bridge, GraphWeaverProperties props,
                                 @Value("${spring.cloud.stream.bindings.transactionIn-in-0.destination}") String topic) {
        this.g = g; this.persistence = persistence; this.txGraph = txGraph;
        this.detection = detection; this.bridge = bridge; this.props = props; this.topic = topic;
    }

    @EventListener
    public void onMatch(PatternMatch m) {
        if (running.get()) detected.computeIfAbsent(m.signature(), k -> new AtomicInteger()).incrementAndGet();
    }

    /** @param direct true = bypass Kafka and call TransactionGraphService directly (no broker needed). */
    public SeedReport seed(Scale scale, long seed, boolean direct) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A seeding run is already in progress");
        try {
            int run = runs.incrementAndGet();           // namespaces refs/IBANs/ids so every run creates fresh data
            Random r = new Random(seed);
            detected.clear();
            long start = System.nanoTime();
            Map<String, Long> v0 = vertexCounts(), e0 = edgeCounts();

            long t = System.nanoTime();
            var persisted = persistence.persist(buildOwnership(scale, r, "r" + run), "bulk-seeder");
            long ownershipMs = ms(t);

            List<TransactionEvent> txs = buildTransactions(scale, r, run);
            long base = e0.getOrDefault(EDGE, 0L);
            Consumer<TransactionEvent> sink = direct ? txGraph::ingest : e -> bridge.send(topic, e);

            t = System.nanoTime();
            txs.forEach(sink);
            long publishMs = ms(t);

            t = System.nanoTime();
            long ingested = awaitIngest(base + txs.size()) - base;
            long ingestWaitMs = ms(t);

            t = System.nanoTime();
            detection.detect();
            long detectionMs = ms(t);

            Map<String, Integer> found = new TreeMap<>();
            for (PatternMatch.Signature s : PatternMatch.Signature.values()) found.put(s.name(), detected.getOrDefault(s, new AtomicInteger()).get());
            double tps = Math.round(ingested / Math.max(0.001, (publishMs + ingestWaitMs) / 1000.0) * 10) / 10.0;

            Map<String, Long> timings = new LinkedHashMap<>();
            timings.put("ownershipPersist", ownershipMs);
            timings.put("publish", publishMs);
            timings.put("ingestWait", ingestWaitMs);
            timings.put("detectionPass", detectionMs);
            timings.put("total", ms(start));

            SeedReport report = new SeedReport(scale.name(), seed, direct ? "direct" : "kafka",
                    diff(vertexCounts(), v0), diff(edgeCounts(), e0),
                    txs.size(), ingested, ingested < txs.size(),
                    Map.of(PatternMatch.Signature.FAN_OUT_FAN_IN.name(), scale.fanPatterns, PatternMatch.Signature.CIRCULAR_VELOCITY.name(), scale.circPatterns),
                    found, found.values().stream().mapToInt(Integer::intValue).sum(), timings, tps);
            log.info("Seed {} done in {} ms: nodes={} edges={} skippedOwnershipEdges={} detected={} ({} tx/s)",
                    scale, timings.get("total"), report.nodesCreated(), report.edgesWritten(),
                    persisted.skippedEdges().size(), found, tps);
            return report;
        } finally {
            running.set(false);
        }
    }

    /* ---------- Module 1: layered offshore ownership ---------- */

    private ExtractedOwnership buildOwnership(Scale s, Random r, String tag) {
        List<ExtractedOwnership.PersonNode> persons = new ArrayList<>();
        List<ExtractedOwnership.CompanyNode> companies = new ArrayList<>();
        List<ExtractedOwnership.TrustNode> trusts = new ArrayList<>();
        List<ExtractedOwnership.OwnsEdge> owns = new ArrayList<>();
        List<ExtractedOwnership.ControlsEdge> controls = new ArrayList<>();

        for (int i = 0; i < s.persons; i++)
            persons.add(new ExtractedOwnership.PersonNode("person:" + tag + "-p" + i, pick(FIRST, r) + " " + pick(LAST, r), pick(COUNTRIES, r),
                    "P" + (10_000_000 + r.nextInt(90_000_000)), r.nextInt(20) == 0));
        for (int i = 0; i < s.trusts; i++)
            trusts.add(new ExtractedOwnership.TrustNode("trust:" + tag + "-t" + i, pick(LAST, r) + " Family Trust " + i,
                    ExtractedOwnership.TrustType.values()[r.nextInt(ExtractedOwnership.TrustType.values().length)],
                    LocalDate.of(2005 + r.nextInt(20), 1 + r.nextInt(12), 1 + r.nextInt(28)).toString(), pick(LAW, r)));
        for (int i = 0; i < s.companies; i++)
            companies.add(new ExtractedOwnership.CompanyNode("company:" + tag + "-c" + i, pick(WORDS, r) + " " + pick(KIND, r) + " " + i + " Ltd",
                    pick(JURISDICTIONS, r), "REG-" + (1_000_000 + r.nextInt(9_000_000))));

        List<String> pRefs = persons.stream().map(ExtractedOwnership.PersonNode::ref).toList();
        List<String> tRefs = trusts.stream().map(ExtractedOwnership.TrustNode::ref).toList();
        List<String> cRefs = companies.stream().map(ExtractedOwnership.CompanyNode::ref).toList();
        List<String> top = new ArrayList<>(tRefs);
        top.addAll(pRefs);

        int nHold = Math.max(1, s.companies / 10), nMid = s.companies * 3 / 10;
        List<String> holdings = cRefs.subList(0, nHold);
        List<String> mids = cRefs.subList(nHold, nHold + nMid);
        List<String> shells = cRefs.subList(nHold + nMid, cRefs.size());
        List<String> holdAndMid = concat(holdings, mids);

        // layer 1: holdings <- trusts/persons ; layer 2: intermediates <- holdings (20% directly from top) ; layer 3: shells <- intermediates
        holdings.forEach(h -> addOwners(owns, r, h, top, 1, 3, 100));
        mids.forEach(m -> addOwners(owns, r, m, r.nextInt(5) == 0 ? top : holdings, 1, 3, 100));
        for (String sh : shells) {
            double main = 100;
            String sibling = pick(shells, r);
            if (r.nextInt(20) == 0 && !sibling.equals(sh)) {          // cross-holding between sibling shells (interlock / loops)
                double pct = 5 + r.nextInt(6);
                owns.add(new ExtractedOwnership.OwnsEdge(sibling, sh, pct, pct, "bulk-seeder"));
                main -= pct;
            }
            addOwners(owns, r, sh, r.nextInt(10) == 0 ? holdAndMid : mids, 1, 2, main);
        }

        // control: a small pool of nominee directors sits on many shells
        List<String> nominees = pRefs.subList(0, Math.max(3, s.persons / 20));
        for (String c : cRefs)
            for (int d = 0, k = 1 + r.nextInt(2); d < k; d++) {
                boolean nominee = r.nextInt(100) < 35;
                controls.add(new ExtractedOwnership.ControlsEdge(pick(nominee ? nominees : pRefs, r), c,
                        nominee ? ExtractedOwnership.ControlRole.NOMINEE_DIRECTOR : ExtractedOwnership.ControlRole.DIRECTOR, "bulk-seeder"));
            }
        for (String t : tRefs) {
            controls.add(new ExtractedOwnership.ControlsEdge(pick(pRefs, r), t, ExtractedOwnership.ControlRole.SETTLOR, "bulk-seeder"));
            controls.add(new ExtractedOwnership.ControlsEdge(r.nextBoolean() ? pick(pRefs, r) : pick(cRefs, r), t, ExtractedOwnership.ControlRole.TRUSTEE, "bulk-seeder"));
            controls.add(new ExtractedOwnership.ControlsEdge(pick(pRefs, r), t, ExtractedOwnership.ControlRole.PROTECTOR, "bulk-seeder"));
        }
        return new ExtractedOwnership(persons, companies, trusts, owns, controls);
    }

    private static void addOwners(List<ExtractedOwnership.OwnsEdge> out, Random r, String target, List<String> pool, int minK, int maxK, double total) {
        int k = Math.min(pool.size(), minK + r.nextInt(maxK - minK + 1));
        Set<String> owners = new LinkedHashSet<>();
        while (owners.size() < k) owners.add(pick(pool, r));
        int[] w = owners.stream().mapToInt(o -> 1 + r.nextInt(9)).toArray();
        int sum = Arrays.stream(w).sum(), i = 0;
        double allocated = 0;
        for (String o : owners) {
            double pct = round2(i == k - 1 ? total - allocated : total * w[i] / sum);
            allocated += pct;
            i++;
            out.add(new ExtractedOwnership.OwnsEdge(o, target, pct, pct, "bulk-seeder"));
        }
    }

    /* ---------- Module 2: noise + planted signatures ---------- */

    private List<TransactionEvent> buildTransactions(Scale s, Random r, int run) {
        long now = System.currentTimeMillis(), ns = (run + 1) * 1_000_000_000L;
        List<TransactionEvent> out = new ArrayList<>();
        Emit emit = (from, to, amount, ts) -> out.add(new TransactionEvent(
                "seed-r" + run + "-" + out.size(), iban(from), "AEGICHZZ", iban(to), "AEGICHZZ",
                BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), "CHF", ts,
                CHANNELS[(int) Math.floorMod(from + to, 3L)]));

        int accounts = Math.max(200, s.noiseTx / 6);                    // background noise; 2% large wires -> permanent edges
        for (int i = 0; i < s.noiseTx; i++) {
            int from = r.nextInt(accounts), to = (from + 1 + r.nextInt(accounts - 1)) % accounts;
            double amount = r.nextInt(50) == 0 ? 100_000 + r.nextDouble() * 1_900_000 : 50 + r.nextDouble() * 4_950;
            emit.tx(ns + 500_000 + from, ns + 500_000 + to, amount, now - r.nextLong(Duration.ofMinutes(55).toMillis()));
        }

        for (int p = 0; p < s.fanPatterns; p++) {                       // A -> n x M -> B, sub-ceiling, ~0.5% skim
            long a = ns + 100_000 + p * 1_000L, b = a + 1;
            long t0 = now - Duration.ofMinutes(12 + r.nextInt(38)).toMillis();
            for (int k = 0; k < props.minIntermediaries(); k++) {
                long m = a + 100 + k;
                double amount = 9_700 + r.nextInt(200);
                emit.tx(a, m, amount, t0 + k * 10_000L);
                emit.tx(m, b, amount * 0.995, t0 + k * 10_000L + 60_000 + r.nextInt(5_000));
            }
        }

        for (int p = 0; p < s.circPatterns; p++) {                      // ring, one hop / 3 min, ~1% lost per hop
            long base = ns + 300_000 + p * 1_000L;
            int hops = props.maxCycleHops();
            long t0 = now - Duration.ofMinutes(20 + r.nextInt(25)).toMillis();
            double amount = 9_900;
            for (int k = 0; k < hops; k++) {
                emit.tx(base + k, base + (k + 1) % hops, amount, t0 + k * Duration.ofMinutes(3).toMillis());
                amount *= 0.99;
            }
        }

        out.sort(Comparator.comparingLong(TransactionEvent::timestamp));   // interleave patterns with noise like a real stream
        return out;
    }

    /* ---------- helpers ---------- */

    private long awaitIngest(long target) {
        long deadline = System.nanoTime() + INGEST_TIMEOUT.toNanos();
        long count;
        while ((count = g.E().hasLabel(EDGE).count().next()) < target && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return count;
    }

    private Map<String, Long> vertexCounts() { return stringKeys(g.V().groupCount().by(T.label).next()); }
    private Map<String, Long> edgeCounts() { return stringKeys(g.E().groupCount().by(T.label).next()); }

    private static Map<String, Long> stringKeys(Map<Object, Long> m) {
        Map<String, Long> o = new TreeMap<>();
        m.forEach((k, v) -> o.put(String.valueOf(k), v));
        return o;
    }

    private static Map<String, Long> diff(Map<String, Long> after, Map<String, Long> before) {
        Map<String, Long> d = new TreeMap<>();
        after.forEach((k, v) -> { long n = v - before.getOrDefault(k, 0L); if (n != 0) d.put(k, n); });
        return d;
    }

    private static <T> T pick(List<T> l, Random r) { return l.get(r.nextInt(l.size())); }
    private static List<String> concat(List<String> a, List<String> b) { List<String> l = new ArrayList<>(a); l.addAll(b); return l; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static long ms(long fromNanos) { return (System.nanoTime() - fromNanos) / 1_000_000; }
    private static String iban(long n) { return "CH93" + String.format("%017d", n); }
}
