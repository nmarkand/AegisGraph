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
            - OWNS edges: legal/beneficial shareholding. percentage and votingRights are plain JSON numbers between 0 and 100,
              for example 35.5. Never write a % sign and never write text in those fields.
            - CONTROLS edges: non-ownership control (director, nominee director, protector, settlor, trustee, signatory).
              sourceRef is the controller, targetRef the controlled entity.
            - evidence = verbatim quote of at most 60 characters.
            - Skip names that only appear as witnesses, notaries, banks, lenders or charities.
            - Do NOT output sanctions, risk or PEP conclusions unless the text states them.
            Respond with JSON only, as compactly as possible.
            """;

    @Bean
    ChatClient legalParsingClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM)
                // Optional: ask Ollama to enforce the JSON schema (types included). Try it, remove it if the model rejects it.
                // .defaultAdvisors(org.springframework.ai.chat.client.AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .build();
    }

    @Bean
    TokenTextSplitter legalSplitter() {
        return TokenTextSplitter.builder().build();
    }

    @Bean
    VectorStore legalVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build(); // in-memory; swap for pgvector/Chroma/Qdrant later
    }
}
