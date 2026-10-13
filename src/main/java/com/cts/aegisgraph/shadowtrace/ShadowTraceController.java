package com.cts.aegisgraph.shadowtrace;

import com.cts.aegisgraph.graphweaver.GraphPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequestMapping("/api/shadowtrace")
public class ShadowTraceController {

    private static final Logger log = LoggerFactory.getLogger(ShadowTraceController.class);

    public record UploadResponse(String filename, ExtractedOwnership extracted, GraphPersistenceService.PersistenceResult graph) {}

    private final LegalParsingService parser;
    private final GraphPersistenceService persistence;

    public ShadowTraceController(LegalParsingService parser, GraphPersistenceService persistence) {
        this.parser = parser;
        this.persistence = persistence;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        if (bytes.length < 5 || !new String(bytes, 0, 5).startsWith("%PDF"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF documents are accepted");

        String name = file.getOriginalFilename() == null ? "unknown.pdf" : file.getOriginalFilename();
        long t0 = System.nanoTime();
        log.info("Upload received: {} ({} bytes)", name, bytes.length);
        try {
            ExtractedOwnership extracted = parser.parse(new ByteArrayResource(bytes), name);
            var graph = persistence.persist(extracted, name);
            log.info("Upload {} finished in {} s: {} persons, {} companies, {} trusts, {} owns, {} controls, {} skipped edges",
                    name, (System.nanoTime() - t0) / 1_000_000_000, extracted.persons().size(), extracted.companies().size(),
                    extracted.trusts().size(), extracted.owns().size(), extracted.controls().size(), graph.skippedEdges().size());
            return new UploadResponse(name, extracted, graph);
        } catch (RuntimeException e) {
            log.error("Upload failed for {}", name, e);   // full stack trace in the application log
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Extraction failed: " + root.getClass().getSimpleName() + ": " + root.getMessage(), e);
        }
    }
}
