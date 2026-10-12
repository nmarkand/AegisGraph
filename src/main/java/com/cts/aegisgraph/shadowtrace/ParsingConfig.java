package com.cts.aegisgraph.shadowtrace;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ParsingConfig {

    private static final String SYSTEM = """
            You are a legal-document extraction engine for Swiss bank KYC/AML compliance.
            Extract ONLY facts explicitly stated in the supplied text. Never infer or guess; use null when absent.
            Rules:
            - ref = "<type>:<lowercase-hyphenated-name>" where type is person|company|trust; identical entities MUST share one ref.
            - OWNS edges: legal/beneficial shareholding or interest. percentage and votingRights are numbers 0-100.
            - CONTROLS edges: non-ownership control (director, nominee director, protector, settlor, trustee, signatory).
              sourceRef is the controller, targetRef the controlled entity.
            - evidence = short verbatim quote (max 200 chars).
            - Do NOT output sanctions, risk or PEP conclusions unless the text states them.
            Respond with JSON only.
            """;

    @Bean
    ChatClient legalParsingClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM).build();
    }

    @Bean
    TokenTextSplitter legalSplitter() {
        return TokenTextSplitter.builder().build(); // chunk, min chars, min embed len, max chunks, keep separators
    }

    @Bean
    VectorStore legalVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build(); // in-memory; swap for pgvector/Chroma/Qdrant later
    }
}
