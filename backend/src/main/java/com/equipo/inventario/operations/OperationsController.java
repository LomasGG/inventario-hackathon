package com.equipo.inventario.operations;

import com.equipo.inventario.api.ApiErrors;
import com.equipo.inventario.ledger.LedgerService;
import com.equipo.inventario.reservations.ReservationService;
import com.equipo.inventario.shared.EventPublisher;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationsController {

    // Lo que llega por la API
    public record IssueRequest(String workOrderCode, Integer partId, Integer locationId,
                               Integer quantity, Object issuedBy) {}
    public record TransferRequest(Integer partId, Integer fromLocationId, Integer toLocationId,
                                  Integer quantity) {}
    public record CountLine(Integer partId, Integer countedQuantity) {}
    public record CountRequest(Integer locationId, String reason, List<CountLine> lines) {}
    public record Adjustment(int partId, int delta) {}

    // Lo que se publica en inventory.events
    public record StockIssued(int workOrderId, int partId, int locationId, int quantity, Object issuedBy) {}
    public record StockTransferred(int partId, int fromLocationId, int toLocationId, int quantity) {}
    public record StockAdjusted(int partId, int locationId, int delta, String reason) {}

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final ReservationService reservations;
    private final EventPublisher publisher;

    public OperationsController(JdbcTemplate jdbc, LedgerService ledger,
                                ReservationService reservations, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.reservations = reservations;
        this.publisher = publisher;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private void requireLocation(int locationId) {
        if (jdbc.queryForList("SELECT location_id FROM locations WHERE location_id = ?",
                Integer.class, locationId).isEmpty()) {
            throw new ApiErrors.NotFound("No existe la ubicación " + locationId);
        }
    }

    /** Salida contra una orden (escaneo en piso). */
    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LedgerService.Movement issue(@RequestBody IssueRequest r) {
        if (r.workOrderCode() == null || r.partId() == null || r.locationId() == null
                || r.quantity() == null || r.quantity() < 1 || r.issuedBy() == null) {
            throw new ApiErrors.Unprocessable("Faltan datos de la salida");
        }
        List<Integer> orders = jdbc.queryForList(
            "SELECT work_order_id FROM work_orders WHERE code = ? AND NOT deleted",
            Integer.class, r.workOrderCode());
        if (orders.isEmpty()) {
            throw new ApiErrors.NotFound("No existe la orden " + r.workOrderCode());
        }
        int workOrderId = orders.get(0);
        requireLocation(r.locationId());
        ledger.lockPart(r.partId());

        int here = ledger.onHandAt(r.partId(), r.locationId());
        if (here < r.quantity()) {
            throw new ApiErrors.Conflict("No alcanza: en esa ubicación solo hay " + here);
        }
        int reservedForOrder = jdbc.queryForObject("""
            SELECT COALESCE(SUM(reserved), 0) FROM material_lines
            WHERE work_order_id = ? AND part_id = ? AND active""",
            Integer.class, workOrderId, r.partId());
        if (r.quantity() > ledger.availableFor(r.partId()) + reservedForOrder) {
            throw new ApiErrors.Conflict("No alcanza sin tomar piezas reservadas para otras órdenes");
        }

        LedgerService.Movement movement = ledger.record(r.partId(), r.locationId(), "issue",
            -r.quantity(), now(), r.workOrderCode(), null, workOrderId, null, null);
        reservations.consumeForIssue(workOrderId, r.partId(), r.quantity());
        publisher.publish("stock.issued", r.workOrderCode(),
            new StockIssued(workOrderId, r.partId(), r.locationId(), r.quantity(), r.issuedBy()));
        return movement;
    }

    /** Transferencia entre ubicaciones: dos movimientos, el total no cambia. */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LedgerService.Movement transfer(@RequestBody TransferRequest r) {
        if (r.partId() == null || r.fromLocationId() == null || r.toLocationId() == null
                || r.quantity() == null || r.quantity() < 1) {
            throw new ApiErrors.Unprocessable("Faltan datos de la transferencia");
        }
        if (r.fromLocationId().equals(r.toLocationId())) {
            throw new ApiErrors.Unprocessable("El origen y el destino son la misma ubicación");
        }
        requireLocation(r.fromLocationId());
        requireLocation(r.toLocationId());
        ledger.lockPart(r.partId());

        int here = ledger.onHandAt(r.partId(), r.fromLocationId());
        if (here < r.quantity()) {
            throw new ApiErrors.Conflict("No alcanza: en el origen solo hay " + here);
        }
        Instant t = now();
        String reference = "Transferencia " + r.fromLocationId() + " -> " + r.toLocationId();
        LedgerService.Movement out = ledger.record(r.partId(), r.fromLocationId(), "transfer_out",
            -r.quantity(), t, reference, null, null, null, null);
        ledger.record(r.partId(), r.toLocationId(), "transfer_in",
            r.quantity(), t, reference, null, null, null, null);
        publisher.publish("stock.transferred", "part:" + r.partId(),
            new StockTransferred(r.partId(), r.fromLocationId(), r.toLocationId(), r.quantity()));
        return out;
    }

    /** Conteo cíclico: un ajuste por cada pieza con diferencia. */
    @PostMapping("/counts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, List<Adjustment>> count(@RequestBody CountRequest r) {
        if (r.reason() == null || r.reason().isBlank()) {
            throw new ApiErrors.Unprocessable("Falta el motivo del conteo");
        }
        if (r.locationId() == null || r.lines() == null) {
            throw new ApiErrors.Unprocessable("Faltan la ubicación o las líneas del conteo");
        }
        requireLocation(r.locationId());

        List<Adjustment> adjustments = new ArrayList<>();
        Instant t = now();
        for (CountLine line : r.lines()) {
            if (line.partId() == null || line.countedQuantity() == null || line.countedQuantity() < 0) {
                throw new ApiErrors.Unprocessable("Línea de conteo inválida");
            }
            ledger.lockPart(line.partId());
            int delta = line.countedQuantity() - ledger.onHandAt(line.partId(), r.locationId());
            if (delta == 0) {
                // por si el saldo ya estaba por debajo de lo reservado
                reservations.ajustarReservasASaldo(line.partId());
                continue;
            }
            ledger.record(line.partId(), r.locationId(), "adjustment", delta, t, r.reason(),
                null, null, null, null);
            publisher.publish("stock.adjusted", "part:" + line.partId(),
                new StockAdjusted(line.partId(), r.locationId(), delta, r.reason()));
            adjustments.add(new Adjustment(line.partId(), delta));
            if (delta < 0) {
                reservations.ajustarReservasASaldo(line.partId()); // no dejar reservado más de lo que hay
            } else {
                reservations.allocateIncoming(line.partId(), delta, null); // lo encontrado surte faltantes
            }
        }
        return Map.of("adjustments", adjustments);
    }
}