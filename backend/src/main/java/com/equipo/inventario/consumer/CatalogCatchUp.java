package com.equipo.inventario.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Entre tópicos no hay orden: una compra puede llegar antes que el part.upserted de su pieza.
 * Antes de procesar una compra, se lee shop.catalog hasta el último mensaje que exista en ese
 * momento, para que el SkuMatcher vea todas las piezas (por ejemplo, dos con el mismo sku).
 * Lo que el ShopListener ya procesó se salta solo (processed_events).
 */
@Component
public class CatalogCatchUp implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CatalogCatchUp.class);
    private static final long MAX_WAIT_MS = 2000;

    private final ConsumerFactory<String, String> consumerFactory;
    private final EventDispatcher dispatcher;
    /** Propio (Jackson 2), igual que el ShopListener. */
    private final ObjectMapper mapper = new ObjectMapper();

    private Consumer<String, String> consumer;
    private List<TopicPartition> partitions = List.of();

    public CatalogCatchUp(ConsumerFactory<String, String> consumerFactory, EventDispatcher dispatcher) {
        this.consumerFactory = consumerFactory;
        this.dispatcher = dispatcher;
    }
    public synchronized void run() {
        try {
            if (consumer == null) {
                open();
                log.info("CatchUp: consumidor abierto para {} particiones de catálogo", partitions.size());
            }
            if (partitions.isEmpty()) {
                return;
            }
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
            int count = 0;
            while (behind(end) && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    try {
                        dispatcher.process(record.topic(), mapper.readTree(record.value()));
                        count++;
                    } catch (Exception e) {
                        log.warn("CatchUp: error en offset {}: {}", record.offset(), e.getMessage());
                    }
                }
            }
            if (count > 0 || behind(end)) {
                log.info("CatchUp: {} eventos de catálogo leídos, atrasado={}", count, behind(end));
            }
        } catch (Exception e) {
            log.warn("CatchUp: falló", e);
        }
    }

    
    private boolean behind(Map<TopicPartition, Long> end) {
        return partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp));
    }

    private void open() {
        consumer = consumerFactory.createConsumer("catalog-catchup", "-catchup");
        partitions = consumer.partitionsFor(ShopListener.CATALOG).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.close();
        }
    }
}