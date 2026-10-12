package com.cts.aegisgraph.seeder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/test")
@ConditionalOnProperty(name = "aegis.seeder.enabled", havingValue = "true")
public class SeedController {

    private final BulkTestDataGenerator generator;

    public SeedController(BulkTestDataGenerator generator) {
        this.generator = generator;
    }

    @PostMapping("/seed")
    public BulkTestDataGenerator.SeedReport seed(@RequestParam(defaultValue = "large") String scale,
                                                 @RequestParam(defaultValue = "42") long seed,
                                                 @RequestParam(defaultValue = "false") boolean direct) {
        BulkTestDataGenerator.Scale s;
        try {
            s = BulkTestDataGenerator.Scale.valueOf(scale.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scale must be small|medium|large");
        }
        try {
            return generator.seed(s, seed, direct);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
