package com.equipo.inventario.catalog;

import com.equipo.inventario.shared.EventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guarda el catálogo: piezas, ubicaciones y listas de partes (tópico shop.catalog).
 * "Upsert" = insertar si no existe, actualizar si ya existe (ON CONFLICT DO UPDATE).
 */
@Component
public class CatalogHandler implements EventHandler {

    private final JdbcTemplate jdbc;

    public CatalogHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("part.upserted", "location.upserted", "bom.upserted");
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode data = event.path("data");
        switch (event.path("event_type").asText()) {
            case "part.upserted" -> upsertPart(data);
            case "location.upserted" -> upsertLocation(data);
            case "bom.upserted" -> replaceBom(data);
            default -> { }
        }
    }

    private void upsertPart(JsonNode d) {
        String sku = text(d.path("sku"));
        String skuNorm = sku == null ? null : sku.trim().toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO parts (part_id, sku, sku_norm, name, family, unit_name, image_url, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (part_id) DO UPDATE SET
                    sku = EXCLUDED.sku, sku_norm = EXCLUDED.sku_norm, name = EXCLUDED.name,
                    family = EXCLUDED.family, unit_name = EXCLUDED.unit_name,
                    image_url = EXCLUDED.image_url, active = EXCLUDED.active
                """,
                d.path("part_id").asInt(), sku, skuNorm, d.path("name").asText(),
                text(d.path("family")), text(d.path("unit").path("name")), text(d.path("image_url")),
                d.path("active").asBoolean(true));
    }

    private void upsertLocation(JsonNode d) {
        jdbc.update("""
                INSERT INTO locations (location_id, code, name, is_workbench, active)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (location_id) DO UPDATE SET
                    code = EXCLUDED.code, name = EXCLUDED.name,
                    is_workbench = EXCLUDED.is_workbench, active = EXCLUDED.active
                """,
                d.path("location_id").asInt(), d.path("code").asText(), d.path("name").asText(),
                d.path("is_workbench").asBoolean(false), d.path("active").asBoolean(true));
    }

    /** bom.upserted trae la lista COMPLETA del modelo: se actualizan sus líneas y se borran las que ya no vienen. */
    private void replaceBom(JsonNode d) {
        int modelId = d.path("model_id").asInt();
        List<Integer> ids = new ArrayList<>();
        for (JsonNode line : d.path("lines")) {
            int bomLineId = line.path("bom_line_id").asInt();
            ids.add(bomLineId);
            jdbc.update("""
                    INSERT INTO bom_lines (bom_line_id, model_id, part_id, name, group_name, qty_per_unit, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (bom_line_id) DO UPDATE SET
                        model_id = EXCLUDED.model_id, part_id = EXCLUDED.part_id, name = EXCLUDED.name,
                        group_name = EXCLUDED.group_name, qty_per_unit = EXCLUDED.qty_per_unit,
                        sort_order = EXCLUDED.sort_order
                    """,
                    bomLineId, modelId, integer(line.path("part_id")), line.path("name").asText(),
                    text(line.path("group_name")), integer(line.path("qty_per_unit")), integer(line.path("sort_order")));
        }
        // Postgres entiende '{14,15,16}' como arreglo de enteros
        String keep = ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        jdbc.update("DELETE FROM bom_lines WHERE model_id = ? AND NOT (bom_line_id = ANY (?::int[]))", modelId, keep);
    }

    /** Texto, o null si el campo viene null o no viene. */
    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    /** Entero, o null si el campo viene null o no viene. */
    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
    }
}
