package com.equipo.inventario.shared;

import org.springframework.stereotype.Service;

@Service
public class EventPublisher {
    /** Publica un evento stock.* en inventory.events. */
    public void publish(String eventType, String key, Object data) {
        // TODO B1
    }
}