package com.cts.aegisgraph.shadowtrace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(LegalParsingService.class);

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
        try {
            vectorStore.add(chunks); // semantic search over legal text; must not block extraction
        } catch (RuntimeException e) {
            log.warn("Vector indexing skipped (is the embedding model pulled?): {}", e.toString());
        }

        long start = System.nanoTime();
        ExtractedOwnership result = ExtractedOwnership.EMPTY;
        int failed = 0;
        RuntimeException first = null;
        for (int i = 0; i < chunks.size(); i++) {
            final Document chunk = chunks.get(i);
            long t = System.nanoTime();
            try {
                ExtractedOwnership part = client.prompt()
                        .user(u -> u.text(USER).param("chunk", chunk.getText()))
                        .call()
                        .entity(ExtractedOwnership.class);
                if (part != null) result = result.merge(part);
            } catch (RuntimeException e) {   // one bad chunk (timeout, invalid JSON) must not lose the whole document
                failed++;
                if (first == null) first = e;
                log.warn("Chunk {}/{} failed: {}", i + 1, chunks.size(), e.toString());
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000_000;
            long eta = elapsed * (chunks.size() - i - 1) / (i + 1);
            log.info("Chunk {}/{} done in {} s | total so far: {} persons, {} companies, {} trusts, {} owns, {} controls | elapsed {} s, about {} s left",
                    i + 1, chunks.size(), (System.nanoTime() - t) / 1_000_000_000,
                    result.persons().size(), result.companies().size(), result.trusts().size(),
                    result.owns().size(), result.controls().size(), elapsed, eta);
        }
        if (failed == chunks.size() && first != null) throw first;   // everything failed: surface the real cause
        if (failed > 0) log.warn("{} of {} chunks failed; the result is partial", failed, chunks.size());
        return result;
    }
}
