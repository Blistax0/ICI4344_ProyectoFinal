package com.googledrive.core.metrics;

import java.util.concurrent.atomic.LongAdder;

public class MetricasCoordinacion {
    private static final LongAdder mensajesCoordinacion = new LongAdder();

    public static void registrarMensaje() {
        mensajesCoordinacion.increment();
    }

    public static long obtenerTotal() {
        return mensajesCoordinacion.sum();
    }

    public static void reiniciar() {
        mensajesCoordinacion.reset();
    }
}
