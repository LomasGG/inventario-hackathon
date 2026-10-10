package com.equipo.inventario.workorders;

import com.equipo.inventario.reservations.ReservationService;
import com.equipo.inventario.shared.EventHandler;
import com.equipo.inventario.shared.MissingDependency;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Inspecciones (tópico shop.inspections). Solo approved y voided mueven inventario;
 * submitted, rejected y discarded no tienen handler y no hacen nada.
 *
 * Regla del contrato: la necesidad vive por (orden, bom_line_id) y una aprobación posterior
 * la REEMPLAZA, no la suma. Las líneas que una aprobación no menciona se quedan como estaban.
 */
@Component
public class InspectionHandler implements EventHandler {

    private final JdbcTemplate jdbc;
    private final ReservationService reservations;

    public InspectionHandler(JdbcTemplate jdbc, ReservationService reservations) {
        this.jdbc = jdbc;
        this.reservations = reservations;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("inspection.approved", "inspection.voided");
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode d = event.path("data");
        int workOrderId = d.path("work_order_id").asInt();

        if ("inspection.voided".equals(event.path("event_type").asText())) {
            reservations.releaseInspection(workOrderId, d.path("inspection_id").asInt());
            return;
        }
        approve(event.path("key").asText(), workOrderId, d);
    }

    private void approve(String code, int workOrderId, JsonNode d) {
        // Orden borrada: la aprobación se ignora
        List<Boolean> deleted = jdbc.queryForList(
                "SELECT deleted FROM work_orders WHERE work_order_id = ?", Boolean.class, workOrderId);
        if (!deleted.isEmpty() && deleted.get(0)) {
            return;
        }

        // Solo crean necesidad los renglones a comprar: action = buy y condición distinta de ok
        List<JsonNode> needs = new ArrayList<>();
        for (JsonNode item : d.path("items")) {
            if ("buy".equals(item.path("action").asText()) && !"ok".equals(item.path("condition").asText())) {
                needs.add(item);
            }
        }

        // 1. Revisar dependencias ANTES de escribir nada (si falta algo, el evento queda pendiente completo)
        for (JsonNode item : needs) {
            Integer partId = integer(item.path("part_id"));
            if (partId != null && !exists("SELECT EXISTS (SELECT 1 FROM parts WHERE part_id = ?)", partId)) {
                throw new MissingDependency("part:" + partId);
            }
            int bomLineId = item.path("bom_line_id").asInt();
            if (integer(item.path("quantity")) == null
                    && !exists("SELECT EXISTS (SELECT 1 FROM bom_lines WHERE bom_line_id = ?)", bomLineId)) {
                throw new MissingDependency("bom_line:" + bomLineId);
            }
        }

        // 2. La orden no es dependencia: si no existe, se crea con el código de la key
        if (deleted.isEmpty()) {
            jdbc.update("INSERT INTO work_orders (work_order_id, code) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    workOrderId, code);
        }

        // 3. Fijar la necesidad de cada línea y reconciliar
        int inspectionId = d.path("inspection_id").asInt();
        for (JsonNode item : needs) {
            int bomLineId = item.path("bom_line_id").asInt();
            Integer required = integer(item.path("quantity"));
            if (required == null) { // sin cantidad: la de la lista de partes, o null si tampoco hay
                List<Integer> perUnit = jdbc.queryForList(
                        "SELECT qty_per_unit FROM bom_lines WHERE bom_line_id = ?", Integer.class, bomLineId);
                required = perUnit.isEmpty() ? null : perUnit.get(0);
            }

            List<Integer> before = jdbc.queryForList("""
                    SELECT CASE WHEN active AND required IS NOT NULL
                                THEN GREATEST(required - reserved - issued, 0) END
                    FROM material_lines WHERE work_order_id = ? AND bom_line_id = ?
                    """, Integer.class, workOrderId, bomLineId);
            Integer previousMissing = before.isEmpty() ? null : before.get(0);

            // Reemplaza la necesidad SIN tocar reserved ni issued
            jdbc.update("""
                    INSERT INTO material_lines (work_order_id, bom_line_id, inspection_id, inspection_item_id,
                                                part_id, name, required, active)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true)
                    ON CONFLICT (work_order_id, bom_line_id) DO UPDATE SET
                        inspection_id = EXCLUDED.inspection_id, inspection_item_id = EXCLUDED.inspection_item_id,
                        part_id = EXCLUDED.part_id, name = EXCLUDED.name, required = EXCLUDED.required,
                        active = true
                    """, workOrderId, bomLineId, inspectionId, item.path("inspection_item_id").asInt(),
                    integer(item.path("part_id")), item.path("name").asText(), required);

            reservations.reconcile(workOrderId, bomLineId, previousMissing);
        }
    }

    private boolean exists(String sql, int id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, id));
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
    }
}
