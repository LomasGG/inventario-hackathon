package com.equipo.inventario.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Escucha los 4 buzones del taller y le pasa cada mensaje al EventDispatcher.
 *
 * autoStartup = "false": no arranca solo. CatalogBootstrap lo prende después de cargar
 * el catálogo completo (regla 3: catálogo primero).
 *
 * ConsumerSeekAware: si la base estaba vacía al arrancar, regresa al inicio de los
 * buzones para releer todo (regla 4: reconstrucción).
 */
@Component
public class ShopListener implements ConsumerSeekAware {

    public static final String LISTENER_ID = "shop";
    public static final String CATALOG = "shop.catalog";
    public static final String WORK_ORDERS = "shop.work_orders";
    public static final String INSPECTIONS = "shop.inspections";
    public static final String PURCHASING = "shop.purchasing";

    private static final Logger log = LoggerFactory.getLogger(ShopListener.class);

    private final EventDispatcher dispatcher;
    private final StartupState startup;
    private final CatalogCatchUp catalogCatchUp;
    /** Propio (Jackson 2): Spring Boot 4 solo registra el de Jackson 3. */
    private final ObjectMapper mapper = new ObjectMapper();

    public ShopListener(EventDispatcher dispatcher, StartupState startup, CatalogCatchUp catalogCatchUp) {
        this.dispatcher = dispatcher;
        this.startup = startup;
        this.catalogCatchUp = catalogCatchUp;
    }

    @KafkaListener(id = LISTENER_ID, groupId = "inventory", autoStartup = "false",
            topics = {CATALOG, WORK_ORDERS, INSPECTIONS, PURCHASING},
            properties = {"auto.offset.reset=earliest"})
        public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        JsonNode event = mapper.readTree(record.value()); // si el JSON viene roto, truena y va a la DLQ
        if (PURCHASING.equals(record.topic())) {
            catalogCatchUp.run(); // regla: que la compra vea todas las piezas publicadas antes que ella
        }
        dispatcher.process(record.topic(), event);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        if (startup.takeRebuild()) {
            log.info("Base vacía: releyendo los tópicos desde el inicio");
            callback.seekToBeginning(assignments.keySet());
        }
    }
}
