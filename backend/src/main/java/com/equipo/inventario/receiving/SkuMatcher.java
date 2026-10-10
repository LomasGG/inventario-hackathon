package com.equipo.inventario.receiving;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Relaciona el part_number de una compra con una pieza del catálogo. */
@Component
public class SkuMatcher {

    private final JdbcTemplate jdbc;

    public SkuMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Regla del contrato: ignorar mayúsculas y espacios al inicio y al final. */
    public static String normalize(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    /** Intento flexible: además quita espacios y guiones de en medio. */
    static String compact(String s) {
        return s == null ? null : normalize(s).replaceAll("[\\s\\-]", "");
    }

    /**
     * Devuelve el part_id si coincide con exactamente UNA pieza.
     * Si hay 0 o más de 1, devuelve null (va a revisión manual).
     */
    public Integer match(String partNumber) {
        List<Integer> exact = jdbc.queryForList(
            "SELECT part_id FROM parts WHERE sku_norm = ?",
            Integer.class, normalize(partNumber));
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null;          // SKU compartido: nunca adivinar

        List<Integer> flexible = jdbc.queryForList(
            "SELECT part_id FROM parts WHERE regexp_replace(sku_norm, '[[:space:]-]', '', 'g') = ?",
            Integer.class, compact(partNumber));
        return flexible.size() == 1 ? flexible.get(0) : null;
    }
}