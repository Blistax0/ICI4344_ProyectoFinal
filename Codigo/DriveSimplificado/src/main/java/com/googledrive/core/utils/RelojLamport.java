package com.googledrive.core.utils;

import java.util.concurrent.atomic.AtomicInteger;

public class RelojLamport {
    
    // AtomicInteger previene condiciones de carrera sin usar bloques synchronized
    private final AtomicInteger tiempoLogico;

    public RelojLamport() {
        this.tiempoLogico = new AtomicInteger(0);
    }

    public int registrarEventoLocal() {
        return tiempoLogico.incrementAndGet();
    }

    public int registrarEnvio() {
        return tiempoLogico.incrementAndGet();
    }

    public void registrarRecepcion(int tiempoRecibido) {
        int tiempoActual;
        int tiempoNuevo;
        // Bucle compareAndSet para actualizar atómicamente al max(local, recibido) + 1
        do {
            tiempoActual = tiempoLogico.get();
            tiempoNuevo = Math.max(tiempoActual, tiempoRecibido) + 1;
        } while (!tiempoLogico.compareAndSet(tiempoActual, tiempoNuevo));
    }

    public int obtenerTiempo() {
        return tiempoLogico.get();
    }
}
