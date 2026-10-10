package com.equipo.inventario.ledger;

import com.equipo.inventario.api.ApiErrors;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {

    /** Un movimiento, con la forma Movement del contrato. */
    public record Movement(long movementId, String type, int partId, int locationId,
                           int quantity, String occurredAt, String reference) {}

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Candado de la pieza (SELECT ... FOR UPDATE). Lanza 404 si no existe. */
    public void lockPart(int partId) {
        List<Integer> found = jdbc.queryForList(
            "SELECT part_id FROM parts WHERE part_id = ? FOR UPDATE", Integer.class, partId);
        if (found.isEmpty()) {
            throw new ApiErrors.NotFound("No existe la pieza " + partId);
        }
    }

    /** Existencia total de la pieza, en todas las ubicaciones. */
    public int onHand(int partId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(on_hand), 0) FROM stock WHERE part_id = ?", Integer.class, partId);
    }

    /** Existencia de la pieza en una ubicación. */
    public int onHandAt(int partId, int locationId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(on_hand), 0) FROM stock WHERE part_id = ? AND location_id = ?",
            Integer.class, partId, locationId);
    }

    /** Lo reservado de la pieza para órdenes vivas. */
    public int reserved(int partId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(m.reserved), 0)
            FROM material_lines m
            JOIN work_orders w ON w.work_order_id = m.work_order_id
            WHERE m.part_id = ? AND m.active AND NOT w.deleted""", Integer.class, partId);
    }

    /** on_hand total menos lo reservado. */
    public int availableFor(int partId) {
        return onHand(partId) - reserved(partId);
    }

    /** Inserta un movimiento y actualiza la existencia. quantity va con signo. */
    public Movement record(int partId, int locationId, String type, int quantity, Instant occurredAt,
                           String reference, Integer purchaseLineId, Integer workOrderId,
                           BigDecimal unitCost, String currency) {
        Long id = jdbc.queryForObject("""
            INSERT INTO movements (part_id, location_id, type, quantity, occurred_at, reference,
                                   purchase_line_id, work_order_id, unit_cost, currency)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING movement_id""",
            Long.class, partId, locationId, type, quantity, Timestamp.from(occurredAt), reference,
            purchaseLineId, workOrderId, unitCost, currency);

        jdbc.update("""
            INSERT INTO stock (part_id, location_id, on_hand) VALUES (?, ?, ?)
            ON CONFLICT (part_id, location_id)
            DO UPDATE SET on_hand = stock.on_hand + EXCLUDED.on_hand""",
            partId, locationId, quantity);

        return new Movement(id, type, partId, locationId, quantity, occurredAt.toString(), reference);
    }
}