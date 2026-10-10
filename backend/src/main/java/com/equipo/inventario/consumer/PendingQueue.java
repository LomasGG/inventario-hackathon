package com.equipo.inventario.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * La tabla pending_events: eventos que llegaron antes que lo que necesitan (regla 5).
 * Solo guarda, lista, borra y cuenta intentos. Quién los reintenta: EventDispatcher.
 */
@Component
public class PendingQueue {

    /** Un evento en espera, tal como está en la tabla. */
    public record Pending(long id, String topic, String payload) {
    }

    private final JdbcTemplate jdbc;

    public PendingQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String topic, JsonNode event, String waitingFor) {
        jdbc.update("INSERT INTO pending_events (topic, payload, waiting_for) VALUES (?, ?::jsonb, ?)",
                topic, event.toString(), waitingFor);
    }

    /** Del más antiguo al más nuevo. */
    public List<Pending> all() {
        return jdbc.query("SELECT id, topic, payload::text AS payload FROM pending_events ORDER BY id",
                (rs, i) -> new Pending(rs.getLong("id"), rs.getString("topic"), rs.getString("payload")));
    }

    /** ¿Hay algo esperando de esta misma orden? (la key de los eventos de órdenes e inspecciones) */
    public boolean hasForKey(String key) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pending_events WHERE payload->>'key' = ?)", Boolean.class, key);
        return Boolean.TRUE.equals(exists);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM pending_events WHERE id = ?", id);
    }

    public void stillWaiting(long id, String waitingFor) {
        jdbc.update("UPDATE pending_events SET attempts = attempts + 1, waiting_for = ? WHERE id = ?", waitingFor, id);
    }
}
