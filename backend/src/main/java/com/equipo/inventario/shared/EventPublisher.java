package com.equipo.inventario.shared;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventPublisher {

    public static final String TOPIC = "inventory.events";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    /** Publica un evento stock.* en inventory.events. Si hay transacción, sale después del commit. */
    public void publish(String eventType, String key, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", UUID.randomUUID().toString());
        envelope.put("event_type", eventType);
        envelope.put("event_version", 1);
        envelope.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        envelope.put("source", "inventory");
        envelope.put("key", key);
        envelope.put("data", data);
        String json = mapper.writeValueAsString(envelope);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafka.send(TOPIC, key, json);
                }
            });
        } else {
            kafka.send(TOPIC, key, json);
        }
    }
}