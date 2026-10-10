package com.equipo.inventario.consumer;

import com.equipo.inventario.shared.EventHandler;
import com.equipo.inventario.shared.MissingDependency;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El "cartero": recibe cada evento y se lo pasa a la clase que lo atiende.
 *
 * Regla 1 (un evento = una transacción):
 *   1. Guarda el event_id en processed_events. Si ya estaba, es repetido: no hace nada.
 *   2. Busca el handler de su event_type y lo llama.
 *   3. Si algo truena, @Transactional deshace todo, incluido el paso 1.
 *
 * Regla 5 (pendientes): si el handler lanza MissingDependency, el evento se guarda
 * en pending_events. Después de cada evento de catálogo u orden, se reintentan.
 *
 * OJO: los handlers NO llevan @Transactional; ya corren dentro de esta transacción.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    /** Los eventos que pueden destrabar pendientes: traen piezas, listas de partes u órdenes. */
    private static final Set<String> UNLOCKERS = Set.of(
            "part.upserted", "bom.upserted", "location.upserted", "work_order.opened");

    private final Map<String, EventHandler> handlers = new HashMap<>();
    private final JdbcTemplate jdbc;
    private final PendingQueue pending;
    /** Propio (Jackson 2): Spring Boot 4 solo registra el de Jackson 3. */
    private final ObjectMapper mapper = new ObjectMapper();
    /** "Punto de guardado" dentro de la transacción: si el handler falla, se deshace solo lo suyo. */
    private final TransactionTemplate savepoint;

    /** Spring le pasa TODAS las clases que implementan EventHandler; aquí se arma el directorio tipo → handler. */
    public EventDispatcher(List<EventHandler> all, JdbcTemplate jdbc, PendingQueue pending,
                           PlatformTransactionManager transactionManager) {
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        for (EventHandler handler : all) {
            for (String type : handler.eventTypes()) {
                EventHandler previous = handlers.put(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("Dos handlers para " + type + ": "
                            + previous.getClass().getSimpleName() + " y " + handler.getClass().getSimpleName());
                }
            }
        }
        this.jdbc = jdbc;
        this.pending = pending;
        log.info("Handlers registrados para: {}", handlers.keySet());
    }

    @Transactional
    public void process(String topic, JsonNode event) {
        String eventId = event.path("event_id").asText("");
        String type = event.path("event_type").asText("");
        if (eventId.isEmpty() || type.isEmpty()) {
            throw new IllegalArgumentException("Evento sin event_id o event_type"); // irá a la DLQ
        }

        int inserted = jdbc.update(
                "INSERT INTO processed_events (event_id, event_type) VALUES (?::uuid, ?) ON CONFLICT DO NOTHING",
                eventId, type);
        if (inserted == 0) {
            log.debug("Repetido, se ignora: {} {}", type, eventId);
            return;
        }

        String key = event.path("key").asText("");
        if (isOrderTopic(topic) && pending.hasForKey(key)) {
            // Algo anterior de esta misma orden sigue esperando: este va detrás, para respetar el orden.
            pending.save(topic, event, "order:" + key);
            log.info("En fila detrás de su orden: {} {}", type, key);
            return;
        }

        try {
            apply(type, event);
        } catch (MissingDependency missing) {
            pending.save(topic, event, missing.waitingFor());
            log.info("Pendiente: {} {} espera {}", type, key, missing.waitingFor());
            return;
        }

        if (UNLOCKERS.contains(type)) {
            retryPending();
        }
    }

    /**
     * Llama al handler del tipo dentro de un punto de guardado: si lanza cualquier error
     * (MissingDependency incluida), lo que alcanzó a escribir se deshace y el error sigue hacia arriba.
     * Tipos sin handler (stage_changed, submitted...) no hacen nada.
     */
    private void apply(String type, JsonNode event) {
        EventHandler handler = handlers.get(type);
        if (handler == null) {
            log.debug("Sin handler para {}: no mueve inventario", type);
            return;
        }
        savepoint.executeWithoutResult(status -> handler.handle(event));
    }

    /** Reintenta los pendientes, del más antiguo al más nuevo. Los que ya pueden, se aplican y se borran. */
    private void retryPending() {
        Set<String> blockedKeys = new HashSet<>();
        for (PendingQueue.Pending row : pending.all()) {
            JsonNode event = parse(row.payload());
            String type = event.path("event_type").asText("");
            String key = event.path("key").asText("");

            if (isOrderTopic(row.topic()) && blockedKeys.contains(key)) {
                pending.stillWaiting(row.id(), "order:" + key);
                continue;
            }
            try {
                apply(type, event);
                pending.delete(row.id());
                log.info("Pendiente aplicado: {} {}", type, key);
            } catch (MissingDependency missing) {
                pending.stillWaiting(row.id(), missing.waitingFor());
                if (isOrderTopic(row.topic())) {
                    blockedKeys.add(key);
                }
            } catch (RuntimeException bug) {
                // Un error de código en un pendiente no debe tumbar el evento que lo destrabó.
                log.error("Pendiente {} {} falló: {}", type, key, bug.getMessage());
                pending.stillWaiting(row.id(), "error: " + bug.getMessage());
                if (isOrderTopic(row.topic())) {
                    blockedKeys.add(key);
                }
            }
        }
    }

    private static boolean isOrderTopic(String topic) {
        return ShopListener.WORK_ORDERS.equals(topic) || ShopListener.INSPECTIONS.equals(topic);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Pendiente con JSON roto", e);
        }
    }
}
