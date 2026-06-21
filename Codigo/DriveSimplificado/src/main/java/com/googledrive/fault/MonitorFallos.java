package com.googledrive.fault;

import com.googledrive.storage.server.ContextoNodo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorFallos {
    public static final long TIMEOUT_CAIDA_MS = 5000;
    private static final long INTERVALO_REVISION_MS = 2000;

    private final ContextoNodo contexto;
    private final Set<String> nodosYaCaidos = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    public MonitorFallos(ContextoNodo contexto) {
        this.contexto = contexto;
    }

    public void iniciar() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::revisarNodos, INTERVALO_REVISION_MS,
                INTERVALO_REVISION_MS, TimeUnit.MILLISECONDS);
    }

    public void detener() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void revisarNodos() {
        long ahora = System.currentTimeMillis();
        boolean enGracePeriod = ahora - contexto.getTimestampInicio() < ContextoNodo.GRACE_PERIOD_MS;
        Map<String, Long> latidos = contexto.getServicioHeartbeats().getUltimosLatidos();

        for (Map.Entry<String, Long> entry : latidos.entrySet()) {
            String nodoId = entry.getKey();
            if (nodoId.equals(contexto.getNodoId())) {
                continue;
            }
            long ultimo = entry.getValue();

            if (ultimo <= 0) {
                if (!enGracePeriod && nodosYaCaidos.add(nodoId)) {
                    contexto.getServicioHeartbeats().marcarCaido(nodoId);
                    contexto.getRegistroEventos().registrar(
                            contexto.getRelojLamport().incrementar(),
                            "NODO_CAIDO=" + nodoId + " (sin heartbeat)");
                    if (nodoId.equals(contexto.getCoordinadorId())) {
                        contexto.getBully().onCoordinadorCaido();
                    }
                }
                continue;
            }

            if (ahora - ultimo > TIMEOUT_CAIDA_MS && nodosYaCaidos.add(nodoId)) {
                contexto.getServicioHeartbeats().marcarCaido(nodoId);
                contexto.getRegistroEventos().registrar(
                        contexto.getRelojLamport().incrementar(), "NODO_CAIDO=" + nodoId);

                if (nodoId.equals(contexto.getCoordinadorId())) {
                    contexto.getBully().onCoordinadorCaido();
                }
            } else if (ahora - ultimo <= TIMEOUT_CAIDA_MS && ultimo > 0) {
                nodosYaCaidos.remove(nodoId);
            }
        }
    }
}
