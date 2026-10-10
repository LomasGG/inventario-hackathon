package com.equipo.inventario.consumer;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Recuerda si al arrancar la base estaba vacía (regla 4: reconstrucción).
 * CatalogBootstrap lo anota; ShopListener lo lee una sola vez para releer todo desde el inicio.
 */
@Component
public class StartupState {

    private final AtomicBoolean rebuild = new AtomicBoolean(false);

    public void markRebuild(boolean freshDatabase) {
        rebuild.set(freshDatabase);
    }

    /** Devuelve true solo la primera vez que se pregunta después de arrancar con la base vacía. */
    public boolean takeRebuild() {
        return rebuild.getAndSet(false);
    }
}
