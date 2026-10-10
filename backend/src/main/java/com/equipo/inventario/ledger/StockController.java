package com.equipo.inventario.ledger;

import com.equipo.inventario.api.ApiErrors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/parts/{partId}")
public class StockController {

    public record LocationBalance(int locationId, String code, int onHand, int reserved, int available) {}
    public record Availability(int partId, int onHand, int reserved, int available,
                               List<LocationBalance> locations) {}
    public record LedgerEntry(long movementId, String type, int partId, int locationId, int quantity,
                              String occurredAt, String reference, int balance) {}
    public record Ledger(int partId, List<LedgerEntry> entries) {}

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;

    public StockController(JdbcTemplate jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    private void requirePart(int partId) {
        if (jdbc.queryForList("SELECT part_id FROM parts WHERE part_id = ?", Integer.class, partId).isEmpty()) {
            throw new ApiErrors.NotFound("No existe la pieza " + partId);
        }
    }

    /** Saldo total y por ubicación. Una pieza conocida sin movimientos responde 200 con ceros. */
    @GetMapping("/availability")
    public Availability availability(@PathVariable int partId) {
        requirePart(partId);
        List<LocationBalance> locations = jdbc.query("""
            SELECT s.location_id, COALESCE(l.code, 'U-' || s.location_id) AS code, s.on_hand
            FROM stock s
            LEFT JOIN locations l ON l.location_id = s.location_id
            WHERE s.part_id = ?
            ORDER BY s.location_id""",
            (rs, i) -> new LocationBalance(rs.getInt("location_id"), rs.getString("code"),
                rs.getInt("on_hand"), 0, rs.getInt("on_hand")),
            partId);
        int onHand = locations.stream().mapToInt(LocationBalance::onHand).sum();
        int reserved = ledger.reserved(partId);
        return new Availability(partId, onHand, reserved, onHand - reserved, locations);
    }

    /** Kardex: del movimiento más reciente al más antiguo, con saldo corrido de todas las ubicaciones. */
    @GetMapping("/ledger")
    public Ledger ledger(@PathVariable int partId) {
        requirePart(partId);
        List<LedgerEntry> rows = jdbc.query(
            "SELECT * FROM movements WHERE part_id = ? ORDER BY occurred_at, movement_id",
            (rs, i) -> new LedgerEntry(rs.getLong("movement_id"), rs.getString("type"),
                rs.getInt("part_id"), rs.getInt("location_id"), rs.getInt("quantity"),
                rs.getTimestamp("occurred_at").toInstant().toString(), rs.getString("reference"), 0),
            partId);

        List<LedgerEntry> entries = new ArrayList<>();
        int balance = 0;
        for (LedgerEntry e : rows) {
            balance += e.quantity();
            entries.add(new LedgerEntry(e.movementId(), e.type(), e.partId(), e.locationId(),
                e.quantity(), e.occurredAt(), e.reference(), balance));
        }
        Collections.reverse(entries);
        return new Ledger(partId, entries);
    }
}