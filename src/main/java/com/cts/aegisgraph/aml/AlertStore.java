package com.cts.aegisgraph.aml;

import com.cts.aegisgraph.graphweaver.AmlAlertEvent;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/** In-memory ring buffer of recent alerts, read by the dashboard. */
@Component
public class AlertStore {

    private static final int MAX = 200;
    private final Deque<AmlAlertEvent> alerts = new ConcurrentLinkedDeque<>();

    public void add(AmlAlertEvent a) {
        alerts.addFirst(a);
        while (alerts.size() > MAX) alerts.pollLast();
    }

    public List<AmlAlertEvent> recent() { return List.copyOf(alerts); }

    public Optional<AmlAlertEvent> find(String id) {
        return alerts.stream().filter(a -> a.alertId().equals(id)).findFirst();
    }
}
