package com.cts.aegisgraph.graphweaver;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;


import static com.cts.aegisgraph.graphweaver.TransactionGraphService.EDGE;
import static java.util.stream.Collectors.joining;

@Service
public class PatternDetectionService {

    private static final Logger log = LoggerFactory.getLogger(PatternDetectionService.class);
    private static final int MAX_CYCLES_PER_RUN = 50;

    private record Hit(String key, PatternMatch match, List<Object> edgeIds) {}
    private record Hop(String a, String m, String b, Edge o, Edge i) {}
    private record Tx(Object id, String src, String dst, long ts, double amt) {}

    private final GraphTraversalSource g;
    private final GraphWeaverProperties props;
    private final TransactionGraphService txGraph;
    private final ApplicationEventPublisher events;
    private final Map<String, Long> reported = new ConcurrentHashMap<>();

    public PatternDetectionService(GraphTraversalSource g, GraphWeaverProperties props,
                                   TransactionGraphService txGraph, ApplicationEventPublisher events) {
        this.g = g;
        this.props = props;
        this.txGraph = txGraph;
        this.events = events;
    }

    /** Returns only newly detected signatures; each is promoted to permanent evidence and published once. */
    @Scheduled(fixedDelayString = "${aegis.graphweaver.detection-interval:PT30S}")
    public List<PatternMatch> detect() {
        long now = System.currentTimeMillis();
        long since = now - props.window().toMillis();
        reported.values().removeIf(t -> t < now - 2 * props.window().toMillis());

        return Stream.concat(fanOutFanIn(since).stream(), circular(since).stream())
                .filter(h -> reported.putIfAbsent(h.key(), now) == null)
                .map(h -> {
                    txGraph.promoteToPermanent(h.edgeIds());
                    events.publishEvent(h.match());
                    log.warn("{} risk={} accounts={}", h.match().signature(), h.match().riskScore(), h.match().accountRefs().size());
                    return h.match();
                }).toList();
    }

    /* ---------- Signature A: A -> {M1..Mn} -> B, sub-ceiling amounts, hop-2 within fanInWindow ---------- */

    private List<Hit> fanOutFanIn(long since) {
        double ceiling = props.structuringCeiling();
        long maxLag = props.fanInWindow().toMillis();

        List<Map<String, Object>> rows = g.E().hasLabel(EDGE)
                .has("timestamp", P.gte(since)).has("amount", P.lt(ceiling)).as("o")
                .outV().as("a")
                .select("o").inV().as("m")
                .outE(EDGE).has("timestamp", P.gte(since)).has("amount", P.lt(ceiling)).as("i")
                .where("i", P.gte("o")).by("timestamp")        // hop 2 not before hop 1
                .inV().as("b")
                .where("a", P.neq("b"))
                .<Object>select("a", "m", "b", "o", "i").toList();

        Map<List<String>, Map<String, Hop>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Edge o = (Edge) r.get("o"), i = (Edge) r.get("i");
            if (ts(i) - ts(o) > maxLag || !conserved(amt(o), amt(i))) continue;
            Hop h = new Hop(ref(r, "a"), ref(r, "m"), ref(r, "b"), o, i);
            groups.computeIfAbsent(List.of(h.a(), h.b()), k -> new LinkedHashMap<>())
                    .merge(h.m(), h, (x, y) -> ts(x.i()) <= ts(y.i()) ? x : y);   // one hop pair per intermediary
        }

        List<Hit> hits = new ArrayList<>();
        groups.forEach((pair, hops) -> {
            if (hops.size() < props.minIntermediaries()) return;
            List<String> accounts = new ArrayList<>(List.of(pair.get(0)));
            List<Object> edgeIds = new ArrayList<>();
            List<Long> all = new ArrayList<>(), cadence = new ArrayList<>();
            double total = 0;
            for (Hop h : hops.values()) {
                accounts.add(h.m());
                edgeIds.add(h.o().id());
                edgeIds.add(h.i().id());
                all.add(ts(h.o()));
                all.add(ts(h.i()));
                cadence.add(ts(h.o()));
                total += amt(h.o());
            }
            accounts.add(pair.get(1));
            hits.add(hit("FAN|" + pair.get(0) + ">" + pair.get(1) + "|" + hops.size(), PatternMatch.Signature.FAN_OUT_FAN_IN,
                    accounts, edgeIds, total, all, cadence, hops.size(), 2 * props.minIntermediaries()));
        });
        return hits;
    }

    /* ---------- Signature B: time-ordered, value-conserving cycle returning to origin within window ---------- */

    private List<Hit> circular(long since) {
        List<Edge> edges = g.E().hasLabel(EDGE).has("timestamp", P.gte(since)).order().by("timestamp").toList();
        CycleFinder finder = new CycleFinder();
        List<Tx> txs = edges.stream()
                .map(e -> new Tx(e.id(), e.outVertex().<String>value("ref"), e.inVertex().<String>value("ref"), ts(e), amt(e)))
                .toList();
        finder.run(txs);
        return finder.hits;
    }

    private final class CycleFinder {
        final Map<String, List<Tx>> adj = new HashMap<>();   // sorted by ts (input is ordered)
        final List<Hit> hits = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final long window = props.window().toMillis();

        void run(List<Tx> txs) {
            txs.forEach(t -> adj.computeIfAbsent(t.src(), k -> new ArrayList<>()).add(t));
            for (Tx first : txs) {
                if (hits.size() >= MAX_CYCLES_PER_RUN) return;
                List<Tx> path = new ArrayList<>(List.of(first));
                walk(first, path, new HashSet<>(Set.of(first.src(), first.dst())));
            }
        }

        void walk(Tx first, List<Tx> path, Set<String> visited) {
            Tx last = path.getLast();
            for (Tx n : adj.getOrDefault(last.dst(), List.of())) {
                if (n.ts() < last.ts()) continue;
                if (n.ts() - first.ts() > window) break;
                if (!conserved(last.amt(), n.amt())) continue;
                if (n.dst().equals(first.src())) {
                    if (path.size() + 1 >= props.minCycleHops()) record(path, n);
                } else if (path.size() + 1 < props.maxCycleHops() && visited.add(n.dst())) {
                    path.add(n);
                    walk(first, path, visited);
                    path.removeLast();
                    visited.remove(n.dst());
                }
            }
        }

        void record(List<Tx> path, Tx closing) {
            List<Tx> cyc = new ArrayList<>(path);
            cyc.add(closing);
            List<String> accounts = cyc.stream().map(Tx::src).toList();
            String key = "CIRC|" + accounts.stream().sorted().collect(joining(","));
            if (hits.size() >= MAX_CYCLES_PER_RUN || !seen.add(key)) return;
            List<Long> times = cyc.stream().map(Tx::ts).toList();
            List<Object> ids = cyc.stream().<Object>map(Tx::id).toList();
            hits.add(hit(key, PatternMatch.Signature.CIRCULAR_VELOCITY, accounts, ids, cyc.getFirst().amt(),
                    times, times, cyc.size(), props.maxCycleHops()));
        }
    }

    /* ---------- helpers ---------- */

    private boolean conserved(double in, double out) {
        return out <= in && out >= in * (1 - props.amountTolerance());
    }

    /** Risk = 50% frequency (count vs reference) + 50% timing consistency (1 / (1 + CV of inter-arrival gaps)). */
    private static int risk(List<Long> cadence, int count, double reference) {
        double freq = Math.min(1.0, count / reference);
        List<Long> s = cadence.stream().sorted().toList();
        double consistency = 0.5;
        if (s.size() > 2) {
            double[] d = IntStream.range(1, s.size()).mapToDouble(k -> s.get(k) - s.get(k - 1)).toArray();
            double mean = Arrays.stream(d).average().orElse(0);
            double sd = Math.sqrt(Arrays.stream(d).map(x -> (x - mean) * (x - mean)).average().orElse(0));
            consistency = mean == 0 ? 1.0 : 1.0 / (1.0 + sd / mean);
        }
        return (int) Math.round(100 * (0.5 * freq + 0.5 * consistency));
    }

    private Hit hit(String key, PatternMatch.Signature sig, List<String> accounts, List<Object> edgeIds, double total,
                    List<Long> all, List<Long> cadence, int count, double reference) {
        PatternMatch m = new PatternMatch(sig, accounts, edgeIds.stream().map(String::valueOf).toList(), total,
                Collections.min(all), Collections.max(all), risk(cadence, count, reference));
        return new Hit(key, m, edgeIds);
    }

    private static long ts(Edge e) { return e.<Long>value("timestamp"); }
    private static double amt(Edge e) { return e.<Double>value("amount"); }
    private static String ref(Map<String, Object> r, String k) { return ((Vertex) r.get(k)).<String>value("ref"); }
}
