package com.equipo.inventario.reservations;

import com.equipo.inventario.api.ApiErrors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /parts/{id}/reservations: qué órdenes vivas tienen apartada esa pieza, y cuántas. */
@RestController
public class ReservationsController {

    private final JdbcTemplate jdbc;

    public ReservationsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/parts/{id}/reservations")
    public Map<String, Object> reservations(@PathVariable("id") int partId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM parts WHERE part_id = ?)", Boolean.class, partId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiErrors.NotFound("La pieza " + partId + " no existe");
        }
        return Json.obj("items", jdbc.queryForList("""
                SELECT ml.work_order_id, wo.code AS work_order_code, SUM(ml.reserved)::int AS reserved
                FROM material_lines ml JOIN work_orders wo USING (work_order_id)
                WHERE ml.part_id = ? AND ml.active AND NOT wo.deleted AND ml.reserved > 0
                GROUP BY ml.work_order_id, wo.code
                ORDER BY wo.code
                """, partId));
    }
}
