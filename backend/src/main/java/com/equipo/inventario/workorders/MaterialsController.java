package com.equipo.inventario.workorders;

import com.equipo.inventario.reservations.Json;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** GET /work-orders/{code}/materials: una línea por cada necesidad vigente de la orden. */
@RestController
public class MaterialsController {

    private final JdbcTemplate jdbc;

    public MaterialsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/work-orders/{code}/materials")
    public ResponseEntity<Map<String, Object>> materials(@PathVariable String code) {
        List<Integer> ids = jdbc.queryForList("SELECT work_order_id FROM work_orders WHERE code = ?", Integer.class, code);
        if (ids.isEmpty()) {
            return ResponseEntity.status(404).body(Json.obj("detail", "La orden " + code + " no existe"));
        }
        // Los alias de las columnas son exactamente los nombres que pide el contrato
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT inspection_item_id, bom_line_id, part_id, name, required, reserved, issued,
                       CASE WHEN required IS NULL THEN NULL ELSE GREATEST(required - reserved - issued, 0) END AS missing
                FROM material_lines
                WHERE work_order_id = ? AND active
                ORDER BY bom_line_id
                """, ids.get(0));
        return ResponseEntity.ok(Json.obj("work_order_id", ids.get(0), "code", code, "lines", lines));
    }
}
