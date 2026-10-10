package com.equipo.inventario.reservations;

import com.equipo.inventario.ledger.LedgerService;
import com.equipo.inventario.shared.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Reservas y faltantes. Viven como columnas de material_lines (una fila por orden + línea de la lista de partes):
 *   required = cuánto necesita la orden   reserved = cuánto tiene apartado   issued = cuánto ya se llevó el técnico
 *   missing  = required - reserved - issued  (lo que falta)
 *
 * NO lleva @Transactional: siempre corre dentro de la transacción de quien lo llama
 * (el EventDispatcher, o la API de B1).
 */
@Service
public class ReservationService {

    /** Una fila de material_lines con el código de su orden. */
    private record Line(int workOrderId, String code, int bomLineId, int inspectionItemId, Integer partId,
                        String name, Integer required, int reserved, int issued, Timestamp shortageOpenedAt) {
    }

    private static final String SELECT_LINE = """
            SELECT ml.work_order_id, wo.code, ml.bom_line_id, ml.inspection_item_id, ml.part_id, ml.name,
                   ml.required, ml.reserved, ml.issued, ml.shortage_opened_at
            FROM material_lines ml JOIN work_orders wo USING (work_order_id)
            """;

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final EventPublisher publisher;
    private final SuggestionService suggestions;

    public ReservationService(JdbcTemplate jdbc, LedgerService ledger, EventPublisher publisher,
                              SuggestionService suggestions) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.publisher = publisher;
        this.suggestions = suggestions;
    }

    /**
     * Deja una línea con lo que le toca reservar y faltar (el corazón de B2).
     * @param previousMissing lo que faltaba ANTES de esta aprobación (null si no faltaba nada o no existía).
     *                        Sirve para publicar stock.shortage_detected solo cuando el faltante es nuevo o cambió.
     */
    public void reconcile(int workOrderId, int bomLineId, Integer previousMissing) {
        Line line = findActiveLine(workOrderId, bomLineId);
        if (line == null) {
            return;
        }

        // 1. Sin pieza o sin cantidad: no se puede reservar, es faltante "a ciegas".
        if (line.partId() == null || line.required() == null) {
            Integer missing = line.required() == null ? null : Math.max(0, line.required() - line.issued());
            if (missing != null && missing == 0) { // ya se entregó todo lo que pedía
                jdbc.update("UPDATE material_lines SET reserved = 0 WHERE work_order_id = ? AND bom_line_id = ?",
                        workOrderId, bomLineId);
                closeShortage(workOrderId, bomLineId);
                return;
            }
            jdbc.update("UPDATE material_lines SET reserved = 0, shortage_opened_at = COALESCE(shortage_opened_at, now()) "
                    + "WHERE work_order_id = ? AND bom_line_id = ?", workOrderId, bomLineId);
            if (line.shortageOpenedAt() == null) {
                publishShortage(line, missing);
            }
            return;
        }

        ledger.lockPart(line.partId()); // candado de la pieza (regla 2): nadie más toca su saldo mientras

        int target = Math.max(0, line.required() - line.issued());
        int reserved = line.reserved();
        if (reserved > target) {
            reserved = target; // la necesidad bajó: se libera el excedente
        }
        if (reserved < target) {
            int take = Math.min(Math.max(0, ledger.availableFor(line.partId())), target - reserved);
            if (take > 0) {
                reserved += take;
                publishReserved(line, take);
            }
        }
        saveReserved(line, reserved);

        int missing = Math.max(0, line.required() - reserved - line.issued());
        if (missing > 0) {
            jdbc.update("UPDATE material_lines SET shortage_opened_at = COALESCE(shortage_opened_at, now()) "
                    + "WHERE work_order_id = ? AND bom_line_id = ?", workOrderId, bomLineId);
            if (previousMissing == null || previousMissing != missing) {
                publishShortage(line, missing);
            }
        } else {
            closeShortage(workOrderId, bomLineId);
        }
        suggestions.recalculate(line.partId());
    }

    /**
     * Surte faltantes con lo que acaba de llegar (lo llama B1 después de registrar la entrada).
     * Primero la orden que viene en la compra (si sigue viva), luego los demás del más antiguo al más nuevo.
     */
    public void allocateIncoming(int partId, int qty, String workOrderCode) {
        List<Line> shortages = jdbc.query(SELECT_LINE + """
                WHERE ml.part_id = ? AND ml.active AND NOT wo.deleted
                  AND ml.required IS NOT NULL AND ml.required - ml.reserved - ml.issued > 0
                ORDER BY (wo.code = ?) DESC, ml.shortage_opened_at NULLS LAST, ml.work_order_id, ml.bom_line_id
                """, (rs, i) -> mapLine(rs), partId, workOrderCode == null ? "" : workOrderCode);

        for (Line line : shortages) {
            int available = ledger.availableFor(partId);
            if (available <= 0) {
                break;
            }
            int missing = line.required() - line.reserved() - line.issued();
            int take = Math.min(missing, available);
            saveReserved(line, line.reserved() + take);
            if (take == missing) {
                closeShortage(line.workOrderId(), line.bomLineId());
            }
            publishReserved(line, take);
            publisher.publish("stock.shortage_resolved", line.code(),
                    Json.obj("work_order_id", line.workOrderId(), "part_id", partId, "quantity", take));
        }
        suggestions.recalculate(partId);
    }

    /**
     * Pasa de reservado a entregado cuando el técnico retira (lo llama B1 en POST /issues).
     * Si se retira más de lo reservado, el resto cuenta como entregado en la línea que todavía lo necesite.
     */
    public void consumeForIssue(int workOrderId, int partId, int qty) {
        List<Line> lines = jdbc.query(SELECT_LINE + """
                WHERE ml.work_order_id = ? AND ml.part_id = ? AND ml.active
                ORDER BY ml.bom_line_id
                """, (rs, i) -> mapLine(rs), workOrderId, partId);

        int remaining = qty;
        for (Line line : lines) { // primero lo reservado
            int used = Math.min(line.reserved(), remaining);
            if (used > 0) {
                jdbc.update("UPDATE material_lines SET reserved = reserved - ?, issued = issued + ? "
                        + "WHERE work_order_id = ? AND bom_line_id = ?", used, used, workOrderId, line.bomLineId());
                remaining -= used;
            }
        }
        if (remaining > 0) { // luego, lo que todavía necesite alguna línea
            for (Line line : jdbc.query(SELECT_LINE + "WHERE ml.work_order_id = ? AND ml.part_id = ? AND ml.active "
                    + "AND ml.required IS NOT NULL ORDER BY ml.bom_line_id", (rs, i) -> mapLine(rs), workOrderId, partId)) {
                int need = Math.max(0, line.required() - line.reserved() - line.issued());
                int used = Math.min(need, remaining);
                if (used > 0) {
                    jdbc.update("UPDATE material_lines SET issued = issued + ? WHERE work_order_id = ? AND bom_line_id = ?",
                            used, workOrderId, line.bomLineId());
                    remaining -= used;
                }
            }
        }
        jdbc.update("""
                UPDATE material_lines SET shortage_opened_at = NULL
                WHERE work_order_id = ? AND part_id = ? AND required IS NOT NULL AND required - reserved - issued <= 0
                """, workOrderId, partId);
        suggestions.recalculate(partId);
    }

    /** work_order.deleted: libera todas las reservas activas de la orden y cierra sus faltantes. */
    public void releaseOrder(int workOrderId) {
        List<Integer> parts = jdbc.queryForList("""
                UPDATE material_lines SET reserved = 0, active = false, shortage_opened_at = NULL
                WHERE work_order_id = ? AND active RETURNING part_id
                """, Integer.class, workOrderId);
        parts.stream().distinct().forEach(suggestions::recalculate);
    }

    /** inspection.voided: libera y cierra solo las líneas cuya necesidad vigente venía de esa inspección. */
    public void releaseInspection(int workOrderId, int inspectionId) {
        List<Integer> parts = jdbc.queryForList("""
                UPDATE material_lines SET reserved = 0, active = false, shortage_opened_at = NULL
                WHERE work_order_id = ? AND inspection_id = ? AND active RETURNING part_id
                """, Integer.class, workOrderId, inspectionId);
        parts.stream().distinct().forEach(suggestions::recalculate);
    }

        /**
     * Un conteo o ajuste dejó menos existencia que lo reservado (available < 0):
     * se quitan reservas de la orden más nueva a la más vieja hasta que available vuelva a 0,
     * y lo quitado pasa a ser faltante (stock.shortage_detected). Lo llama B1 en POST /counts.
     */
        public void ajustarReservasASaldo(int partId) {
            int exceso = -ledger.availableFor(partId);
            if (exceso <= 0) {
                return;
            }
            List<Line> lines = jdbc.query(SELECT_LINE + """
                    WHERE ml.part_id = ? AND ml.active AND NOT wo.deleted AND ml.reserved > 0
                    ORDER BY wo.opened_at DESC NULLS LAST, ml.work_order_id DESC, ml.bom_line_id DESC
                    """, (rs, i) -> mapLine(rs), partId);
            for (Line line : lines) {
                if (exceso <= 0) {
                    break;
                }
                int quitar = Math.min(line.reserved(), exceso);
                int reserved = line.reserved() - quitar;
                saveReserved(line, reserved);
                exceso -= quitar;
                jdbc.update("UPDATE material_lines SET shortage_opened_at = COALESCE(shortage_opened_at, now()) "
                        + "WHERE work_order_id = ? AND bom_line_id = ?", line.workOrderId(), line.bomLineId());
                int missing = line.required() == null ? quitar
                        : Math.max(0, line.required() - reserved - line.issued());
                publishShortage(line, missing);
            }
            suggestions.recalculate(partId);
        }

    // --- ayudantes ---------------------------------------------------------------------

    private Line findActiveLine(int workOrderId, int bomLineId) {
        List<Line> found = jdbc.query(SELECT_LINE + """
                WHERE ml.work_order_id = ? AND ml.bom_line_id = ? AND ml.active AND NOT wo.deleted
                """, (rs, i) -> mapLine(rs), workOrderId, bomLineId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Line mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Line(rs.getInt("work_order_id"), rs.getString("code"), rs.getInt("bom_line_id"),
                rs.getInt("inspection_item_id"), (Integer) rs.getObject("part_id"), rs.getString("name"),
                (Integer) rs.getObject("required"), rs.getInt("reserved"), rs.getInt("issued"),
                rs.getTimestamp("shortage_opened_at"));
    }

    private void saveReserved(Line line, int reserved) {
        jdbc.update("UPDATE material_lines SET reserved = ? WHERE work_order_id = ? AND bom_line_id = ?",
                reserved, line.workOrderId(), line.bomLineId());
    }

    private void closeShortage(int workOrderId, int bomLineId) {
        jdbc.update("UPDATE material_lines SET shortage_opened_at = NULL WHERE work_order_id = ? AND bom_line_id = ?",
                workOrderId, bomLineId);
    }

    private void publishReserved(Line line, int quantity) {
        publisher.publish("stock.reserved", line.code(), Json.obj(
                "reservation_id", UUID.randomUUID().toString(),
                "work_order_id", line.workOrderId(),
                "part_id", line.partId(),
                "quantity", quantity,
                "inspection_item_id", line.inspectionItemId()));
    }

    private void publishShortage(Line line, Integer missingQuantity) {
        publisher.publish("stock.shortage_detected", line.code(), Json.obj(
                "work_order_id", line.workOrderId(),
                "part_id", line.partId(),
                "name", line.name(),
                "missing_quantity", missingQuantity,
                "inspection_item_id", line.inspectionItemId()));
    }
}
