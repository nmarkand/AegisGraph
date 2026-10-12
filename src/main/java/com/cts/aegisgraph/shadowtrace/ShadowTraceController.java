package com.cts.aegisgraph.shadowtrace;


import com.cts.aegisgraph.graphweaver.GraphPersistenceService;
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
        try {
            ExtractedOwnership extracted = parser.parse(new ByteArrayResource(bytes), name);
            return new UploadResponse(name, extracted, persistence.persist(extracted, name));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Extraction failed: " + e.getMessage(), e);
        }
    }
}
