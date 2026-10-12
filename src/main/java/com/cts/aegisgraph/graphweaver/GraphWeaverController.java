package com.cts.aegisgraph.graphweaver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Dev/test endpoints; disabled unless aegis.graphweaver.simulator-enabled=true. */
@RestController
@RequestMapping("/api/graphweaver")
@ConditionalOnProperty(name = "aegis.graphweaver.simulator-enabled", havingValue = "true")
public class GraphWeaverController {

    private final TransactionSimulator sim;
    private final PatternDetectionService detection;

    public GraphWeaverController(TransactionSimulator sim, PatternDetectionService detection) {
        this.sim = sim;
        this.detection = detection;
    }

    @PostMapping("/simulate/fan-out-fan-in")
    Map<String, Integer> fan(@RequestParam(defaultValue = "20") int intermediaries) {
        return Map.of("sent", sim.fanOutFanIn(intermediaries));
    }

    @PostMapping("/simulate/circular")
    Map<String, Integer> circular(@RequestParam(defaultValue = "6") int hops) {
        return Map.of("sent", sim.circular(hops));
    }

    @PostMapping("/simulate/noise")
    Map<String, Integer> noise(@RequestParam(defaultValue = "200") int count) {
        return Map.of("sent", sim.noise(count));
    }

    /** Runs a detection pass now; returns only matches not already reported by the scheduler. */
    @PostMapping("/detect")
    List<PatternMatch> detect() {
        return detection.detect();
    }
}
