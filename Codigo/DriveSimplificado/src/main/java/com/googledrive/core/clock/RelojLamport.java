package com.googledrive.core.clock;

import java.util.concurrent.atomic.AtomicInteger;

public class RelojLamport {
    private final AtomicInteger contador = new AtomicInteger(0);

    public int incrementar() {
        return contador.incrementAndGet();
    }

    public int actualizar(int relojRecibido) {
        int tiempoActual;
        int tiempoNuevo;
        do {
            tiempoActual = contador.get();
            tiempoNuevo = Math.max(tiempoActual, relojRecibido) + 1;
        } while (!contador.compareAndSet(tiempoActual, tiempoNuevo));
        return tiempoNuevo;
    }

    public int obtener() {
        return contador.get();
    }
}
