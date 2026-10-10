package com.equipo.inventario.receiving;

import com.equipo.inventario.shared.EventHandler;
import com.equipo.inventario.shared.EventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Maneja purchase.item_received.
 * Los eventos de Kafka se leen con Jackson 2 (com.fasterxml), igual que el consumidor de B2.
 */
@Component
public class PurchaseHandler implements EventHandler {

    public record UnmatchedReceiptEvent(int purchaseLineId, String partNumber, String description,
                                        int quantity) {}

    private final JdbcTemplate jdbc;
    private final SkuMatcher matcher;
    private final ReceivingService receiving;
    private final EventPublisher publisher;

    public PurchaseHandler(JdbcTemplate jdbc, SkuMatcher matcher,
                           ReceivingService receiving, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.matcher = matcher;
        this.receiving = receiving;
        this.publisher = publisher;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("purchase.item_received");
    }

    @Override
    public void handle(JsonNode event) {
        JsonNode d = event.path("data");
        String purpose = d.path("purpose").asText();
        int lineId = d.path("line_id").asInt();
        String requestNumber = d.path("request_number").asText();
        String partNumber = d.path("part_number").asText();
        String description = d.path("description").asText();
        int quantity = d.path("quantity").asInt();
        String unitPrice = text(d.path("unit_price"));
        String currency = text(d.path("currency"));
        Instant receivedAt = Instant.parse(d.path("received_at").asText());
        String workOrderCode = text(d.path("work_order_code"));

        // Regla 9: material para revender. No entra, no surte, no emite.
        if ("customer_order".equals(purpose)) {
            return;
        }

        Integer partId = matcher.match(partNumber);
        if (partId == null) {
            // Regla 10: no coincide o coincide con varias piezas -> revisión manual.
            int inserted = jdbc.update("""
                INSERT INTO unmatched_receipts (purchase_line_id, part_number, description, quantity,
                                                unit_price, currency, received_at, work_order_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (purchase_line_id) DO NOTHING""",
                lineId, partNumber, description, quantity,
                unitPrice == null ? null : new BigDecimal(unitPrice), currency,
                Timestamp.from(receivedAt), workOrderCode);
            if (inserted == 1) {
                publisher.publish("stock.unmatched_receipt", "purchase_line:" + lineId,
                    new UnmatchedReceiptEvent(lineId, partNumber, description, quantity));
            }
            return;
        }

        // Regla 11: entra a U-100.
        receiving.receive(partId, ReceivingService.RECEIVING_LOCATION, quantity, receivedAt,
            lineId, unitPrice, currency, "PR-" + requestNumber + "/" + lineId, workOrderCode);
    }

    /** Texto, o null si el campo viene null o no viene. */
    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }
}