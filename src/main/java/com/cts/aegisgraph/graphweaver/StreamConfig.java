package com.cts.aegisgraph.graphweaver;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.function.Consumer;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(GraphWeaverProperties.class)
public class StreamConfig {

    /** Binding "transactionIn-in-0" (see application.yml). Exceptions -> retry -> DLQ. */
    @Bean
    Consumer<TransactionEvent> transactionIn(TransactionGraphService graph) {
        return graph::ingest;
    }
}
