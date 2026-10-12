package com.cts.aegisgraph;


import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;

@Configuration
public class GraphConfig {

    @Bean(destroyMethod = "close")
    TinkerGraph tinkerGraph() {
        return TinkerGraph.open(); // in-memory; swap for a remote Gremlin Server / JanusGraph behind the same traversal source later
    }

    @Bean
    GraphTraversalSource g(TinkerGraph graph) {
        return traversal().withEmbedded(graph);
    }
}
