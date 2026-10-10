package com.equipo.inventario.workorders;

import com.equipo.inventario.reservations.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /work-orders?open=true: órdenes no borradas, las más nuevas primero.
 * Sin open=true, devuelve todas (también las borradas).
 * No choca con /work-orders/{code}/materials: esa ruta tiene más partes.
 */
@RestController
public class WorkOrdersController {

    private final JdbcTemplate jdbc;

    public WorkOrdersController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/work-orders")
    public Map<String, Object> workOrders(@RequestParam(name = "open", defaultValue = "false") boolean open) {
        String sql = """
                SELECT work_order_id, code, model_id,
                       to_char(opened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS opened_at
                FROM work_orders
                """
                + (open ? " WHERE NOT deleted" : "")
                + " ORDER BY work_orders.opened_at DESC NULLS LAST, work_order_id DESC";
        return Json.obj("items", jdbc.queryForList(sql));
    }
}
