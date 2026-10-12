package com.cts.aegisgraph.aml;

import com.cts.aegisgraph.graphweaver.AmlAlertEvent;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Read-only endpoints feeding the dashboard. */
@RestController
@RequestMapping("/api")
public class GraphViewController {

    public record GNode(String id, String label, Map<String, Object> props) {}
    public record GEdge(String id, String source, String target, String label, Map<String, Object> props) {}
    public record GGraph(Collection<GNode> nodes, List<GEdge> edges) {}
    public record Stats(Map<String, Long> vertices, Map<String, Long> edges, int alerts, long criticalAlerts) {}

    private final GraphTraversalSource g;
    private final AlertStore store;

    public GraphViewController(GraphTraversalSource g, AlertStore store) {
        this.g = g;
        this.store = store;
    }

    @GetMapping("/graph/stats")
    public Stats stats() {
        List<AmlAlertEvent> alerts = store.recent();
        return new Stats(counts(g.V().groupCount().by(T.label).next()), counts(g.E().groupCount().by(T.label).next()),
                alerts.size(), alerts.stream().filter(a -> a.severity() == AmlAlertEvent.Severity.CRITICAL).count());
    }

    @GetMapping("/graph/ownership")
    public GGraph ownership(@RequestParam(defaultValue = "250") int limit) {
        return toGraph(g.E().hasLabel("OWNS", "CONTROLS").limit(Math.min(limit, 1_000)).toList(), List.of());
    }

    @GetMapping("/alerts")
    public List<AmlAlertEvent> alerts() { return store.recent(); }

    @GetMapping("/alerts/{id}/graph")
    public GGraph alertGraph(@PathVariable String id) {
        AmlAlertEvent a = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown alert"));
        List<Edge> edges = g.E(a.edgeIds().stream().map(Long::valueOf).toArray()).toList();
        return toGraph(edges, g.V().has("ref", P.within(a.accountRefs())).toList());
    }

    private GGraph toGraph(List<Edge> edges, Collection<Vertex> extra) {
        Map<String, GNode> nodes = new LinkedHashMap<>();
        edges.forEach(e -> { put(nodes, e.outVertex()); put(nodes, e.inVertex()); });
        extra.forEach(v -> put(nodes, v));
        return new GGraph(nodes.values(), edges.stream().map(this::edge).toList());
    }

    private static void put(Map<String, GNode> nodes, Vertex v) {
        String ref = v.value("ref");
        nodes.computeIfAbsent(ref, k -> {
            Map<String, Object> p = new LinkedHashMap<>();
            v.properties().forEachRemaining(vp -> { if (!vp.key().equals("passportHash")) p.put(vp.key(), vp.value()); });
            return new GNode(ref, v.label(), p);
        });
    }

    private GEdge edge(Edge e) {
        Map<String, Object> p = new LinkedHashMap<>();
        e.properties().forEachRemaining(pr -> p.put(pr.key(), pr.value()));
        return new GEdge(String.valueOf(e.id()), e.outVertex().<String>value("ref"), e.inVertex().<String>value("ref"), e.label(), p);
    }

    private static Map<String, Long> counts(Map<Object, Long> m) {
        Map<String, Long> o = new TreeMap<>();
        m.forEach((k, v) -> o.put(String.valueOf(k), v));
        return o;
    }
}
