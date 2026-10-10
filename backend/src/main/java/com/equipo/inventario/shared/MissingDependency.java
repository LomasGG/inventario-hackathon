package com.equipo.inventario.shared;

/** Se lanza cuando un evento llega antes que algo que necesita. */
public class MissingDependency extends RuntimeException {
    private final String waitingFor;   // ej. "part:305"

    public MissingDependency(String waitingFor) {
        super("Falta " + waitingFor);
        this.waitingFor = waitingFor;
    }

    public String waitingFor() {
        return waitingFor;
    }
}