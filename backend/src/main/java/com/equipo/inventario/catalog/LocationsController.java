package com.equipo.inventario.catalog;

import com.equipo.inventario.reservations.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /locations: las ubicaciones activas, ordenadas por código. La usan las pantallas para elegir estante. */
@RestController
public class LocationsController {

    private final JdbcTemplate jdbc;

    public LocationsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/locations")
    public Map<String, Object> locations() {
        return Json.obj("items", jdbc.queryForList("""
                SELECT location_id, code, name, is_workbench
                FROM locations
                WHERE active
                ORDER BY code
                """));
    }
}
