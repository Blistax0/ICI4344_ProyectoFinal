package com.googledrive.fault;

import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.messages.MensajeControl;
import com.googledrive.core.messages.TipoMensajeControl;
import com.googledrive.core.metrics.MetricasCoordinacion;
import com.googledrive.storage.server.ClienteControlNodo;
import com.googledrive.storage.server.ContextoNodo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServicioHeartbeats {
    private static final long INTERVALO_EMISION_MS = 2000;

    private final ContextoNodo contexto;
    private final Map<String, Long> ultimoLatido = new ConcurrentHashMap<>();
    private final Set<String> nodosSospechosos = ConcurrentHashMap.newKeySet();
    private final Set<String> nodosNecesitanSync = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> tiempoCaida = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public ServicioHeartbeats(ContextoNodo contexto) {
        this.contexto = contexto;
        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                ultimoLatido.put(nodo.getId(), System.currentTimeMillis());
            } else {
                ultimoLatido.put(nodo.getId(), 0L);
            }
        }
    }

    public void iniciar() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::emitirHeartbeats, 0, INTERVALO_EMISION_MS, TimeUnit.MILLISECONDS);
    }

    public void detener() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void emitirHeartbeats() {
        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                continue;
            }
            MensajeControl hb = new MensajeControl(TipoMensajeControl.HEARTBEAT,
                    contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
            hb.setTimestampLamport(contexto.getRelojLamport().incrementar());
            if (ClienteControlNodo.enviarMensaje(nodo, hb)) {
                MetricasCoordinacion.registrarMensaje();
            }
        }
    }

    public void registrarLatido(String nodoId) {
        boolean eraSospechoso = nodosSospechosos.contains(nodoId);
        ultimoLatido.put(nodoId, System.currentTimeMillis());
        nodosSospechosos.remove(nodoId);

        if (eraSospechoso) {
            long tCaida = tiempoCaida.getOrDefault(nodoId, 0L);
            long tiempoRecuperacion = tCaida > 0 ? System.currentTimeMillis() - tCaida : 0;
            nodosNecesitanSync.add(nodoId);
            contexto.getRegistroEventos().registrar(
                    contexto.getRelojLamport().incrementar(),
                    "NODO_RECUPERADO=" + nodoId + " TIEMPO_RECUPERACION_MS=" + tiempoRecuperacion);
            tiempoCaida.remove(nodoId);
            contexto.getServicioReplicacion().marcarParaSync(nodoId);
            if (contexto.soyCoordinador()) {
                contexto.getServicioReplicacion().sincronizarNodosRecuperados();
            }
        }
    }

    public boolean estaVivo(String nodoId) {
        if (nodoId.equals(contexto.getNodoId())) {
            return true;
        }
        if (nodosSospechosos.contains(nodoId)) {
            return false;
        }
        Long ultimo = ultimoLatido.get(nodoId);
        if (ultimo == null || ultimo <= 0) {
            return false;
        }
        return System.currentTimeMillis() - ultimo < MonitorFallos.TIMEOUT_CAIDA_MS;
    }

    public void marcarCaido(String nodoId) {
        nodosSospechosos.add(nodoId);
        ultimoLatido.put(nodoId, 0L);
        tiempoCaida.putIfAbsent(nodoId, System.currentTimeMillis());
    }

    public void marcarSospechoso(String nodoId) {
        nodosSospechosos.add(nodoId);
    }

    public boolean necesitaSync(String nodoId) {
        return nodosNecesitanSync.contains(nodoId);
    }

    public void marcarSincronizado(String nodoId) {
        nodosNecesitanSync.remove(nodoId);
    }

    public Map<String, Long> getUltimosLatidos() {
        return ultimoLatido;
    }

    public boolean haRecibidoLatido(String nodoId) {
        Long ultimo = ultimoLatido.get(nodoId);
        return ultimo != null && ultimo > 0;
    }
}
