package com.equipo.inventario.receiving;

import com.equipo.inventario.ledger.LedgerService;
import com.equipo.inventario.reservations.ReservationService;
import com.equipo.inventario.shared.EventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ReceivingService {

    /** Ubicación de recepción U-100. */
    public static final int RECEIVING_LOCATION = 100;

    public record StockReceived(int partId, int locationId, int quantity, String unitCost,
                                String currency, int purchaseLineId) {}

    private final LedgerService ledger;
    private final EventPublisher publisher;
    private final ReservationService reservations;

    public ReceivingService(LedgerService ledger, EventPublisher publisher, ReservationService reservations) {
        this.ledger = ledger;
        this.publisher = publisher;
        this.reservations = reservations;
    }

    public LedgerService.Movement receive(int partId, int locationId, int quantity, Instant receivedAt,
                                          int purchaseLineId, String unitPrice, String currency,
                                          String reference, String workOrderCode) {
        ledger.lockPart(partId);
        BigDecimal cost = unitPrice == null ? null : new BigDecimal(unitPrice);
        LedgerService.Movement movement = ledger.record(partId, locationId, "receipt", quantity,
            receivedAt, reference, purchaseLineId, null, cost, currency);

        publisher.publish("stock.received", "part:" + partId,
            new StockReceived(partId, locationId, quantity, unitPrice, currency, purchaseLineId));

        // B2 surte faltantes con lo que llegó (en la Etapa 1 todavía no hace nada).
        reservations.allocateIncoming(partId, quantity, workOrderCode);
        return movement;
    }
}