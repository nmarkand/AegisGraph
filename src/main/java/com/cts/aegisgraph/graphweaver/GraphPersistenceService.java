package com.cts.aegisgraph.graphweaver;

import com.cts.aegisgraph.shadowtrace.ExtractedOwnership;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class GraphPersistenceService {

    public record PersistenceResult(long vertexTotal, long edgeTotal, List<String> skippedEdges) {}

    private final GraphTraversalSource g;
    private final String salt;

    public GraphPersistenceService(GraphTraversalSource g, @Value("${aegis.passport-salt:change-me}") String salt) {
        this.g = g;
        this.salt = salt;
    }

    // TinkerGraph is non-transactional; serialise writers so coalesce-upserts cannot race.
    public synchronized PersistenceResult persist(ExtractedOwnership x, String sourceDoc) {
        x.persons().forEach(p -> upsertVertex("Person", p.ref(),
                props("sanctionStatus", false, "passportHash", null),               // create-only defaults
                props("name", p.name(), "nationality", p.nationality(),
                      "passportHash", hash(p.documentNumber()),
                      "isPEP", p.pep())));
        x.companies().forEach(c -> upsertVertex("Company", c.ref(),
                props("sanctionStatus", false, "riskScore", 0.0),
                props("name", c.name(), "jurisdiction", c.jurisdiction(),
                      "registrationNumber", c.registrationNumber())));
        x.trusts().forEach(t -> upsertVertex("Trust", t.ref(), props(),
                props("name", t.name(), "type", t.type() == null ? null : t.type().name(),
                      "establishmentDate", t.establishmentDate(), "governingLaw", t.governingLaw())));

        List<String> skipped = new ArrayList<>();
        x.owns().forEach(e -> {
            if (!upsertEdge("OWNS", e.sourceRef(), e.targetRef(), props(),
                    props("percentage", e.percentage(), "votingRights", e.votingRights(),
                          "evidence", e.evidence(), "sourceDocument", sourceDoc)))
                skipped.add("OWNS " + e.sourceRef() + " -> " + e.targetRef());
        });
        x.controls().forEach(e -> {
            String role = e.role() == null ? "OTHER" : e.role().name();
            if (!upsertEdge("CONTROLS", e.sourceRef(), e.targetRef(), props("role", role),
                    props("evidence", e.evidence(), "sourceDocument", sourceDoc)))
                skipped.add("CONTROLS " + e.sourceRef() + " -> " + e.targetRef());
        });

        return new PersistenceResult(g.V().count().next(), g.E().count().next(), skipped);
    }

    /** Idempotent: find-or-create by (label, ref); defaults apply on create only, props overwrite on every run. */
    private void upsertVertex(String label, String ref, Map<String, Object> defaults, Map<String, Object> props) {
        GraphTraversal<Object, Vertex> create = __.addV(label).property("ref", ref);
        defaults.forEach((k, v) -> create.property(k, v));
        GraphTraversal<Vertex, Vertex> t = g.V().has(label, "ref", ref).fold().coalesce(__.unfold(), create);
        props.forEach((k, v) -> t.property(VertexProperty.Cardinality.single, k, v));
        t.iterate();
    }

    /** Idempotent per (source, target, label, matchProps); returns false when an endpoint is missing (LLM dangling ref). */
    private boolean upsertEdge(String label, String src, String tgt, Map<String, Object> match, Map<String, Object> props) {
        if (!g.V().has("ref", src).hasNext() || !g.V().has("ref", tgt).hasNext()) return false;
        GraphTraversal<Vertex, Edge> find = __.inE(label);
        match.forEach((k, v) -> find.has(k, v));
        GraphTraversal<Object, Edge> create = __.addE(label).from("a");
        match.forEach((k, v) -> create.property(k, v));
        GraphTraversal<Vertex, Edge> t = g.V().has("ref", src).as("a")
                .V().has("ref", tgt)
                .coalesce(find.where(__.outV().as("a")), create);
        props.forEach((k, v) -> t.property(k, v));
        t.iterate();
        return true;
    }

    private static Map<String, Object> props(Object... kv) {   // drops null values (TinkerGraph rejects them)
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private String hash(String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            String norm = documentNumber.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            return HexFormat.of().formatHex(md.digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
