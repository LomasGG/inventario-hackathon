package com.equipo.inventario.reservations;

import com.equipo.inventario.shared.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sugerencias de compra (regla 18 del contrato).
 * Por pieza: suma lo que falta en sus faltantes abiertos (con cantidad conocida) y junta las órdenes.
 * Si cambió respecto a lo guardado, guarda y publica stock.reorder_suggested. Si ya no falta nada, la borra.
 */
@Service
public class SuggestionService {

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;

    public SuggestionService(JdbcTemplate jdbc, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    public void recalculate(Integer partId) {
        if (partId == null) {
            return; // una pieza sin identificar no se puede comprar
        }
        Integer total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(ml.required - ml.reserved - ml.issued), 0)::int
                FROM material_lines ml JOIN work_orders wo USING (work_order_id)
                WHERE ml.part_id = ? AND ml.active AND NOT wo.deleted
                  AND ml.required IS NOT NULL AND ml.required - ml.reserved - ml.issued > 0
                """, Integer.class, partId);
        List<Integer> orders = jdbc.queryForList("""
                SELECT DISTINCT ml.work_order_id
                FROM material_lines ml JOIN work_orders wo USING (work_order_id)
                WHERE ml.part_id = ? AND ml.active AND NOT wo.deleted
                  AND ml.required IS NOT NULL AND ml.required - ml.reserved - ml.issued > 0
                ORDER BY ml.work_order_id
                """, Integer.class, partId);

        if (total == null || total <= 0) {
            jdbc.update("DELETE FROM reorder_suggestions WHERE part_id = ?", partId);
            return;
        }

        List<Object[]> saved = jdbc.query(
                "SELECT suggested_quantity, work_order_ids FROM reorder_suggestions WHERE part_id = ?",
                (rs, i) -> new Object[]{rs.getInt(1), toList(rs.getArray(2))}, partId);
        boolean same = !saved.isEmpty() && saved.get(0)[0].equals(total) && saved.get(0)[1].equals(orders);
        if (same) {
            return;
        }

        String ids = orders.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        jdbc.update("""
                INSERT INTO reorder_suggestions (part_id, suggested_quantity, work_order_ids) VALUES (?, ?, ?::int[])
                ON CONFLICT (part_id) DO UPDATE SET
                    suggested_quantity = EXCLUDED.suggested_quantity, work_order_ids = EXCLUDED.work_order_ids
                """, partId, total, ids);
        publisher.publish("stock.reorder_suggested", "part:" + partId,
                Json.obj("part_id", partId, "suggested_quantity", total, "work_order_ids", orders));
    }

    private static List<Integer> toList(Array array) throws java.sql.SQLException {
        if (array == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList((Integer[]) array.getArray()));
    }
}
