package com.cts.aegisgraph.graphweaver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class AmlAlertProducer {

    private static final Logger log = LoggerFactory.getLogger(AmlAlertProducer.class);

    private final StreamBridge bridge;
    private final String topic;

    public AmlAlertProducer(StreamBridge bridge, @Value("${aegis.graphweaver.alert-topic:aegis.aml.alerts}") String topic) {
        this.bridge = bridge;
        this.topic = topic;
    }

    @EventListener
    public void on(PatternMatch match) {
        AmlAlertEvent alert = AmlAlertEvent.from(match);
        // key = signature + origin account -> alerts for the same origin stay ordered on one partition
        Message<AmlAlertEvent> msg = MessageBuilder.withPayload(alert)
                .setHeader(KafkaHeaders.KEY, (alert.signature() + "|" + alert.accountRefs().getFirst()).getBytes(UTF_8))
                .build();
        try {
            if (!bridge.send(topic, msg)) log.error("Alert {} not accepted by binder", alert.alertId());
            else log.info("AML_ALERT_EVENT {} {} risk={} -> {}", alert.alertId(), alert.signature(), alert.riskScore(), topic);
        } catch (RuntimeException e) {
            log.error("Alert {} publish failed", alert.alertId(), e); // detection must not break on a broker outage
        }
    }
}
