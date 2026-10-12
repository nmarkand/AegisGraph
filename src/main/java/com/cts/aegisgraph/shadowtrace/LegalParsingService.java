package com.cts.aegisgraph.shadowtrace;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LegalParsingService {

    private static final String USER = """
            Extract all persons, companies, trusts/foundations, OWNS and CONTROLS relationships from this excerpt:
            ---
            {chunk}
            """;

    private final ChatClient client;
    private final TokenTextSplitter splitter;
    private final VectorStore vectorStore;

    public LegalParsingService(ChatClient legalParsingClient, TokenTextSplitter splitter, VectorStore vectorStore) {
        this.client = legalParsingClient;
        this.splitter = splitter;
        this.vectorStore = vectorStore;
    }

    public ExtractedOwnership parse(Resource pdf, String filename) {
        List<Document> chunks = splitter.apply(new TikaDocumentReader(pdf).read());
        chunks.forEach(c -> c.getMetadata().put("source", filename));
        vectorStore.add(chunks); // semantic search over legal text (later modules)

        return chunks.stream()
                .map(c -> client.prompt()
                        .user(u -> u.text(USER).param("chunk", c.getText()))
                        .call()
                        .entity(ExtractedOwnership.class))
                .reduce(ExtractedOwnership.EMPTY, ExtractedOwnership::merge);
    }
}
