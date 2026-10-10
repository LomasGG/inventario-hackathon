package com.equipo.inventario.receiving;

import com.equipo.inventario.api.ApiErrors;
import com.equipo.inventario.ledger.LedgerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/unmatched-receipts")
public class UnmatchedController {

    public record UnmatchedReceipt(long id, int purchaseLineId, String partNumber, String description,
                                   int quantity, String unitPrice, String currency, String receivedAt,
                                   String workOrderCode) {}

    public record ResolveRequest(Integer partId, Integer locationId) {}

    private record Row(long id, int purchaseLineId, String partNumber, String description, int quantity,
                       BigDecimal unitPrice, String currency, Instant receivedAt, String workOrderCode,
                       boolean resolved) {}

    private final JdbcTemplate jdbc;
    private final ReceivingService receiving;

    public UnmatchedController(JdbcTemplate jdbc, ReceivingService receiving) {
        this.jdbc = jdbc;
        this.receiving = receiving;
    }

    private List<Row> rows(String where, Object... args) {
        return jdbc.query("SELECT * FROM unmatched_receipts " + where, (rs, i) -> new Row(
            rs.getLong("id"), rs.getInt("purchase_line_id"), rs.getString("part_number"),
            rs.getString("description"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"),
            rs.getString("currency"), rs.getTimestamp("received_at").toInstant(),
            rs.getString("work_order_code"), rs.getBoolean("resolved")), args);
    }

    @GetMapping
    public Map<String, Object> list() {
        List<UnmatchedReceipt> items = rows("WHERE NOT resolved ORDER BY id").stream()
            .map(r -> new UnmatchedReceipt(r.id(), r.purchaseLineId(), r.partNumber(), r.description(),
                r.quantity(), r.unitPrice() == null ? null : r.unitPrice().toPlainString(),
                r.currency(), r.receivedAt().toString(), r.workOrderCode()))
            .toList();
        return Map.of("items", items);
    }

    @PostMapping("/{id}/resolve")
    @Transactional
    public LedgerService.Movement resolve(@PathVariable long id, @RequestBody ResolveRequest req) {
        if (req == null || req.partId() == null) {
            throw new ApiErrors.Unprocessable("Falta part_id");
        }
        List<Row> found = rows("WHERE id = ? FOR UPDATE", id);
        if (found.isEmpty()) {
            throw new ApiErrors.NotFound("No existe la recepción " + id);
        }
        Row r = found.get(0);
        if (r.resolved()) {
            throw new ApiErrors.Conflict("La recepción " + id + " ya estaba resuelta");
        }
        int locationId = req.locationId() == null ? ReceivingService.RECEIVING_LOCATION : req.locationId();
        if (jdbc.queryForList("SELECT location_id FROM locations WHERE location_id = ?",
                Integer.class, locationId).isEmpty()) {
            throw new ApiErrors.NotFound("No existe la ubicación " + locationId);
        }

        LedgerService.Movement movement = receiving.receive(req.partId(), locationId, r.quantity(),
            r.receivedAt(), r.purchaseLineId(),
            r.unitPrice() == null ? null : r.unitPrice().toPlainString(), r.currency(),
            "Relacionada a mano: línea " + r.purchaseLineId(), r.workOrderCode());

        jdbc.update("UPDATE unmatched_receipts SET resolved = true WHERE id = ?", id);
        return movement;
    }
}