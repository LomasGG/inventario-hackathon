package com.equipo.inventario.reservations;

import org.springframework.stereotype.Service;

@Service
public class ReservationService {
    /** Surte faltantes con lo que acaba de llegar. */
    public void allocateIncoming(int partId, int qty, String workOrderCode) {
        // TODO B2
    }

    /** Pasa de reservado a entregado cuando el técnico retira. */
    public void consumeForIssue(int workOrderId, int partId, int qty) {
        // TODO B2
    }
}