package com.equipo.inventario.reservations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** GET /shortages (faltantes abiertos) y GET /reorder-suggestions (sugerencias de compra vigentes). */
@RestController
public class ShortagesController {

    private final JdbcTemplate jdbc;

    public ShortagesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/shortages")
    public Map<String, Object> shortages(@RequestParam(name = "work_order_code", required = false) String workOrderCode,
                                         @RequestParam(name = "part_id", required = false) Integer partId) {
        // Faltante abierto = línea activa de una orden viva, y: sin pieza, sin cantidad, o con algo que falta
        StringBuilder sql = new StringBuilder("""
                SELECT ml.work_order_id, wo.code AS work_order_code, ml.part_id, ml.name,
                       CASE WHEN ml.required IS NULL THEN NULL
                            ELSE GREATEST(ml.required - ml.reserved - ml.issued, 0) END AS missing_quantity,
                       ml.inspection_item_id,
                       to_char(COALESCE(ml.shortage_opened_at, now()) AT TIME ZONE 'UTC',
                               'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS opened_at
                FROM material_lines ml JOIN work_orders wo USING (work_order_id)
                WHERE ml.active AND NOT wo.deleted
                  AND (ml.part_id IS NULL OR ml.required IS NULL OR ml.required - ml.reserved - ml.issued > 0)
                """);
        List<Object> args = new ArrayList<>();
        if (workOrderCode != null) {
            sql.append(" AND wo.code = ?");
            args.add(workOrderCode);
        }
        if (partId != null) {
            sql.append(" AND ml.part_id = ?");
            args.add(partId);
        }
        sql.append(" ORDER BY ml.shortage_opened_at NULLS LAST, ml.work_order_id, ml.bom_line_id");
        return Json.obj("items", jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/reorder-suggestions")
    public Map<String, Object> suggestions() {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT rs.part_id, p.sku, p.name, rs.suggested_quantity, rs.work_order_ids
                FROM reorder_suggestions rs LEFT JOIN parts p USING (part_id)
                ORDER BY rs.part_id
                """, (rs, i) -> Json.obj(
                "part_id", rs.getInt("part_id"),
                "sku", rs.getString("sku"),
                "name", rs.getString("name"),
                "suggested_quantity", rs.getInt("suggested_quantity"),
                "work_order_ids", Arrays.asList((Integer[]) rs.getArray("work_order_ids").getArray())));
        return Json.obj("items", items);
    }
}
