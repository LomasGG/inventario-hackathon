package com.equipo.inventario.ledger;

import org.springframework.stereotype.Service;

@Service
public class LedgerService {
    /** SELECT ... FOR UPDATE sobre la pieza. */
    public void lockPart(int partId) {
        // TODO B1
    }

    /** on_hand total menos lo reservado. */
    public int availableFor(int partId) {
        return 0; // TODO B1
    }
}