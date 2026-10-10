package com.equipo.inventario.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Lo que pasa al arrancar el servicio, en este orden:
 *   1. Revisa si la base está vacía (ANTES de procesar nada; si no, nunca la vería vacía).
 *   2. Lee shop.catalog completo, de principio a fin (regla 3: catálogo primero).
 *   3. Prende el ShopListener, que escucha los 4 buzones.
 */
@Component
public class CatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrap.class);

    private final JdbcTemplate jdbc;
    private final ConsumerFactory<String, String> consumerFactory;
    private final EventDispatcher dispatcher;
    private final StartupState startup;
    private final KafkaListenerEndpointRegistry registry;
    /** Propio (Jackson 2): Spring Boot 4 solo registra el de Jackson 3. */
    private final ObjectMapper mapper = new ObjectMapper();

    public CatalogBootstrap(JdbcTemplate jdbc, ConsumerFactory<String, String> consumerFactory,
                            EventDispatcher dispatcher, StartupState startup,
                            KafkaListenerEndpointRegistry registry) {
        this.jdbc = jdbc;
        this.consumerFactory = consumerFactory;
        this.dispatcher = dispatcher;
        this.startup = startup;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Integer processed = jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
        boolean fresh = processed == null || processed == 0;
        startup.markRebuild(fresh);
        log.info("Arranque: base {}", fresh ? "vacía (se releerá todo)" : "con datos");

        loadCatalog();

        MessageListenerContainer listener = registry.getListenerContainer(ShopListener.LISTENER_ID);
        if (listener == null) {
            throw new IllegalStateException("No existe el listener " + ShopListener.LISTENER_ID);
        }
        listener.start();
        log.info("ShopListener escuchando los 4 tópicos");
    }

    /** Un consumidor propio lee shop.catalog del inicio al último mensaje que exista ahorita. */
    private void loadCatalog() {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("catalog-bootstrap", "-bootstrap")) {
            List<TopicPartition> partitions = consumer.partitionsFor(ShopListener.CATALOG).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            if (partitions.isEmpty()) {
                log.warn("{} todavía no existe: ¿está arriba el kit?", ShopListener.CATALOG);
                return;
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            int count = 0;
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    try {
                        dispatcher.process(record.topic(), mapper.readTree(record.value()));
                        count++;
                    } catch (Exception e) {
                        // No se pierde: el ShopListener lo vuelve a leer y, si sigue fallando, va a la DLQ.
                        log.error("Catálogo, offset {}: {}", record.offset(), e.getMessage());
                    }
                }
            }
            log.info("Catálogo cargado: {} eventos leídos", count);
        } catch (Exception e) {
            log.error("No se pudo cargar el catálogo al arrancar: {}", e.getMessage());
        }
    }
}
