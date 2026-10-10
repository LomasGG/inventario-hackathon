package com.equipo.inventario.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Cada clase que maneja eventos de Kafka implementa esto. */
public interface EventHandler {
    Set<String> eventTypes();      // ej. Set.of("purchase.item_received")
    void handle(JsonNode event);   // el sobre completo del evento
}