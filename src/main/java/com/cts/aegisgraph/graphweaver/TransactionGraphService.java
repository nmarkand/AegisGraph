package com.cts.aegisgraph.graphweaver;

import jakarta.annotation.PostConstruct;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TransactionGraphService {

    public static final String EDGE = "TRANSFERRED_TO";

    private final GraphTraversalSource g;
    private final TinkerGraph graph;
    private final GraphWeaverProperties props;
    private final ReentrantLock lock = new ReentrantLock(); // virtual-thread friendly; serialises upserts

    public TransactionGraphService(GraphTraversalSource g, TinkerGraph graph, GraphWeaverProperties props) {
        this.g = g;
        this.graph = graph;
        this.props = props;
    }

    @PostConstruct
    void indexes() {
        graph.createIndex("ref", Vertex.class);
        graph.createIndex("transactionId", Edge.class);
    }

    /** At-least-once safe: returns false for duplicates and self-transfers, true when a new edge was written. */
    public boolean ingest(TransactionEvent e) {
        if (e.sourceIban().equals(e.targetIban())) return false;
        lock.lock();
        try {
            if (g.E().hasLabel(EDGE).has("transactionId", e.transactionId()).hasNext()) return false;

            upsertAccount(e.sourceRef(), e.sourceIban(), e.sourceBic(), e.currency());
            upsertAccount(e.targetRef(), e.targetIban(), e.targetBic(), e.currency());

            boolean permanent = e.amount().doubleValue() >= props.permanentAmountThreshold();
            GraphTraversal<Vertex, Edge> t = g.V().has("BankAccount", "ref", e.sourceRef()).as("s")
                    .V().has("BankAccount", "ref", e.targetRef())
                    .addE(EDGE).from("s")
                    .property("transactionId", e.transactionId())
                    .property("amount", e.amount().doubleValue())
                    .property("currency", e.currency())
                    .property("timestamp", e.timestamp())
                    .property("channel", e.channel())
                    .property("permanent", permanent);
            if (!permanent) t.property("expiresAt", e.timestamp() + props.temporaryRetention().toMillis());
            t.iterate();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Evidence retention: pattern engine calls this with the edge ids of a matched signature. */
    public void promoteToPermanent(Collection<?> edgeIds) {
        if (edgeIds.isEmpty()) return;
        lock.lock();
        try {
            g.E(edgeIds.toArray()).property("permanent", true).properties("expiresAt").drop().iterate();
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "PT5M")
    public int purgeExpired() {
        lock.lock();
        try {
            List<Object> ids = g.E().hasLabel(EDGE).has("permanent", false)
                    .has("expiresAt", P.lt(System.currentTimeMillis())).id().toList();
            if (!ids.isEmpty()) g.E(ids.toArray()).drop().iterate();
            return ids.size();
        } finally {
            lock.unlock();
        }
    }

    private void upsertAccount(String ref, String iban, String bic, String currency) {
        GraphTraversal<Object, Vertex> create = __.addV("BankAccount")
                .property("ref", ref).property("iban", iban)
                .property("currency", currency).property("balance", 0.0);
        GraphTraversal<Vertex, Vertex> t = g.V().has("BankAccount", "ref", ref).fold().coalesce(__.unfold(), create);
        if (bic != null) t.property(VertexProperty.Cardinality.single, "bankBIC", bic);
        t.iterate();
    }
}
