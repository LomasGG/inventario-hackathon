package com.equipo.inventario.consumer;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Regla 6: si un evento truena (JSON roto, un bug), se reintenta 2 veces con medio segundo
 * de espera. Si sigue fallando, el mensaje original se manda a inventory.dlq con su misma key
 * y el consumo sigue con el siguiente.
 *
 * Spring Boot usa este bean solo para todos los @KafkaListener.
 * Partición -1 = que Kafka elija la partición según la key.
 */
@Configuration
public class KafkaErrorConfig {

    public static final String DLQ = "inventory.dlq";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer toDlq =
                new DeadLetterPublishingRecoverer(template, (record, error) -> new TopicPartition(DLQ, -1));
        return new DefaultErrorHandler(toDlq, new FixedBackOff(500L, 2L));
    }
}
