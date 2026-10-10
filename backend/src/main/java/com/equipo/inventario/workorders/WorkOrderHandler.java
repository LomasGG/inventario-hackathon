package com.equipo.inventario.workorders;

import com.equipo.inventario.reservations.ReservationService;
import com.equipo.inventario.shared.EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Órdenes de trabajo (tópico shop.work_orders).
 * work_order.stage_changed no se atiende: no mueve inventario.
 */
@Component
public class WorkOrderHandler implements EventHandler {

    private final JdbcTemplate jdbc;
    private final ReservationService reservations;

    public WorkOrderHandler(JdbcTemplate jdbc, ReservationService reservations) {
        this.jdbc = jdbc;
        this.reservations = reservations;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("work_order.opened", "work_order.deleted");
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode d = event.path("data");
        int workOrderId = d.path("work_order_id").asInt();
        String code = d.path("code").asText(event.path("key").asText());

        if ("work_order.opened".equals(event.path("event_type").asText())) {
            // Inserta, o completa la orden si ya existía creada desde una inspección adelantada
            jdbc.update("""
                    INSERT INTO work_orders (work_order_id, code, model_id, opened_at)
                    VALUES (?, ?, ?, ?::timestamptz)
                    ON CONFLICT (work_order_id) DO UPDATE SET
                        code = EXCLUDED.code, model_id = EXCLUDED.model_id, opened_at = EXCLUDED.opened_at
                    """, workOrderId, code, d.path("model_id").asInt(), d.path("received_at").asText());
            return;
        }

        // work_order.deleted: se marca borrada (sin borrar la fila) y se libera todo lo suyo
        jdbc.update("""
                INSERT INTO work_orders (work_order_id, code, deleted) VALUES (?, ?, true)
                ON CONFLICT (work_order_id) DO UPDATE SET deleted = true
                """, workOrderId, code);
        reservations.releaseOrder(workOrderId);
    }
}
