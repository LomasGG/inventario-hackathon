package com.equipo.inventario.reservations;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Para armar el "data" de los eventos y las respuestas de la API.
 * Map.of() no acepta null, y el contrato SÍ los pide (part_id: null en un faltante sin identificar).
 *
 * Uso: Json.obj("part_id", 305, "missing_quantity", null)
 */
public final class Json {

    private Json() {
    }

    public static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
