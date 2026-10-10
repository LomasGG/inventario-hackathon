package com.equipo.inventario.dev;

import com.equipo.inventario.consumer.EventDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SOLO PARA PROBAR: manda un evento al consumidor como si llegara de Kafka. */
@RestController
@RequestMapping("/dev")
public class DevController {

    private final EventDispatcher dispatcher;
    private final ObjectMapper json = new ObjectMapper(); // Jackson 2, como los handlers

    public DevController(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/events")
    public Map<String, String> event(@RequestBody String body) throws Exception {
        JsonNode event = json.readTree(body);
        dispatcher.process("dev", event);
        return Map.of("procesado", event.path("event_type").asText());
    }
}