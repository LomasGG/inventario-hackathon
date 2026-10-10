package com.equipo.inventario.catalog;

import com.equipo.inventario.api.ApiErrors;
import com.equipo.inventario.reservations.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Búsquedas de piezas para las pantallas:
 *   GET /parts?q=texto   buscar por nombre o SKU
 *   GET /scan?code=...   qué es un código escaneado con el celular
 *   GET /stock?q=texto   existencias, apartado y disponible por pieza
 *
 * OJO: B1 ya tiene /parts/{partId}/availability y /parts/{partId}/ledger. Aquí no se repiten.
 */
@RestController
public class PartsController {

    private final JdbcTemplate jdbc;

    public PartsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Nombre o SKU que CONTIENE el texto, sin importar mayúsculas (ILIKE). Máximo 50. */
    @GetMapping("/parts")
    public Map<String, Object> parts(@RequestParam(name = "q", required = false) String q) {
        StringBuilder sql = new StringBuilder("SELECT part_id, sku, name, family FROM parts");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" WHERE name ILIKE ? OR sku ILIKE ?");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY name, part_id LIMIT 50");
        return Json.obj("items", jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /**
     * El código va como parámetro (?code=) y no en la ruta, porque los SKU traen diagonales: 7014 CTA/P4.
     * Orden de búsqueda: ubicación U-###, pieza sin SKU P-<n>, SKU exacto, SKU normalizado.
     */
    @GetMapping("/scan")
    public Map<String, Object> scan(@RequestParam("code") String code) {
        String trimmed = code.trim();

        // 1. Ubicación: U- y tres dígitos (U-101)
        if (trimmed.matches("U-\\d{3}")) {
            List<Map<String, Object>> location = jdbc.queryForList(
                    "SELECT location_id, code, name FROM locations WHERE code = ?", trimmed);
            if (location.isEmpty()) {
                throw new ApiErrors.NotFound("No existe la ubicación " + trimmed);
            }
            return Json.obj("kind", "location", "location", location.get(0));
        }

        // 2. Pieza sin SKU: P- y su número de pieza (P-24)
        if (trimmed.matches("P-\\d+")) {
            int partId = Integer.parseInt(trimmed.substring(2));
            List<Map<String, Object>> part = jdbc.queryForList(
                    "SELECT part_id, sku, name FROM parts WHERE part_id = ?", partId);
            if (!part.isEmpty()) {
                return Json.obj("kind", "part", "parts", part);
            }
        }

        // 3. SKU exacto, tal cual se escaneó (con espacios, puntos y diagonales)
        List<Map<String, Object>> parts = jdbc.queryForList(
                "SELECT part_id, sku, name FROM parts WHERE sku = ? ORDER BY part_id", code);

        // 4. Si no, el SKU normalizado: sin espacios a los lados y en mayúsculas
        if (parts.isEmpty()) {
            parts = jdbc.queryForList("SELECT part_id, sku, name FROM parts WHERE sku_norm = ? ORDER BY part_id",
                    trimmed.toUpperCase(Locale.ROOT));
        }
        if (parts.isEmpty()) {
            throw new ApiErrors.NotFound("No encontramos el código " + trimmed);
        }
        // Si hay dos o más (SKU repetido), van todas: la pantalla le pide al técnico que elija
        return Json.obj("kind", "part", "parts", parts);
    }

    /**
     * Piezas con existencia o con algo apartado.
     *   on_hand   = suma de stock (tabla de B1) en todas las ubicaciones
     *   reserved  = suma de material_lines.reserved de líneas activas en órdenes vivas (tabla de B2)
     *   available = on_hand - reserved
     */
    @GetMapping("/stock")
    public Map<String, Object> stock(@RequestParam(name = "q", required = false) String q) {
        StringBuilder sql = new StringBuilder("""
                WITH on_hand AS (
                    SELECT part_id, SUM(on_hand)::int AS on_hand FROM stock GROUP BY part_id
                ), reserved AS (
                    SELECT ml.part_id, SUM(ml.reserved)::int AS reserved
                    FROM material_lines ml JOIN work_orders wo USING (work_order_id)
                    WHERE ml.active AND NOT wo.deleted AND ml.part_id IS NOT NULL
                    GROUP BY ml.part_id
                )
                SELECT p.part_id, p.sku, p.name,
                       COALESCE(oh.on_hand, 0) AS on_hand,
                       COALESCE(r.reserved, 0) AS reserved,
                       COALESCE(oh.on_hand, 0) - COALESCE(r.reserved, 0) AS available
                FROM parts p
                LEFT JOIN on_hand oh USING (part_id)
                LEFT JOIN reserved r USING (part_id)
                WHERE (COALESCE(oh.on_hand, 0) <> 0 OR COALESCE(r.reserved, 0) <> 0)
                """);
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (p.name ILIKE ? OR p.sku ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY p.name, p.part_id");
        return Json.obj("items", jdbc.queryForList(sql.toString(), args.toArray()));
    }
}
