package com.googledrive.storage.server;

import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.messages.MensajeControl;
import com.googledrive.core.messages.MensajeReplicacion;
import com.googledrive.core.messages.TipoMensajeControl;
import com.googledrive.core.models.PeticionArchivo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServicioReplicacion {
    private static final int MAX_REINTENTOS = 2;

    private final ContextoNodo contexto;
    private final Set<String> nodosPendientesSync = ConcurrentHashMap.newKeySet();

    public ServicioReplicacion(ContextoNodo contexto) {
        this.contexto = contexto;
    }

    public boolean replicarEscritura(PeticionArchivo peticion, byte[] payload, int lamport, String nodeIdOrigen) {
        if (!contexto.soyCoordinador()) {
            return false;
        }

        try {
            String checksum;
            if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.SUBIR) {
                checksum = contexto.getGestorArchivos().guardarArchivo(
                        peticion.getNombreArchivo(),
                        new ByteArrayInputStream(payload),
                        peticion.getTamanoBytes());
            } else {
                checksum = contexto.getGestorArchivos().editarArchivo(
                        peticion.getNombreArchivo(),
                        new ByteArrayInputStream(payload),
                        peticion.getTamanoBytes(),
                        lamport,
                        nodeIdOrigen);
            }

            if (peticion.getChecksum() != null && !peticion.getChecksum().equals(checksum)) {
                return false;
            }

            MensajeReplicacion replicacion = new MensajeReplicacion(
                    peticion.getTipoOperacion(),
                    peticion.getNombreArchivo(),
                    payload,
                    peticion.getTamanoBytes(),
                    lamport,
                    nodeIdOrigen,
                    checksum);

            propagarReplicacion(replicacion);
            return true;
        } catch (Exception e) {
            contexto.getRegistroEventos().registrar(lamport, "REPLICACION_FALLIDA local: " + e.getMessage());
            return false;
        }
    }

    public void propagarReplicacion(MensajeReplicacion replicacion) {
        List<NodoInfo> destinos = new ArrayList<>();
        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                continue;
            }
            if (!contexto.getServicioHeartbeats().estaVivo(nodo.getId())) {
                continue;
            }
            destinos.add(nodo);
        }

        if (destinos.isEmpty()) {
            return;
        }

        for (NodoInfo destino : destinos) {
            boolean ack = enviarReplicacionConReintento(destino, replicacion);
            if (!ack) {
                contexto.getRegistroEventos().registrar(replicacion.getTimestampLamport(),
                        "OMISION_MENSAJE destino=" + destino.getId());
                contexto.getRegistroEventos().registrar(replicacion.getTimestampLamport(),
                        "REPLICACION_OK/FALLIDA destino=" + destino.getId() + " resultado=FALLIDA");
                contexto.getServicioHeartbeats().marcarSospechoso(destino.getId());
            } else {
                contexto.getRegistroEventos().registrar(replicacion.getTimestampLamport(),
                        "REPLICACION_OK/FALLIDA destino=" + destino.getId() + " resultado=OK");
            }
        }
    }

    private boolean enviarReplicacionConReintento(NodoInfo destino, MensajeReplicacion replicacion) {
        for (int i = 0; i <= MAX_REINTENTOS; i++) {
            MensajeControl msg = new MensajeControl(TipoMensajeControl.REPLICATE,
                    contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
            msg.setReplicacion(replicacion);
            msg.setTimestampLamport(contexto.getRelojLamport().incrementar());

            MensajeControl respuesta = ClienteControlNodo.enviarConRespuesta(destino, msg);
            if (respuesta != null && respuesta.getTipo() == TipoMensajeControl.REPLICATE_ACK) {
                return true;
            }
        }
        return false;
    }

    public void aplicarReplicacion(MensajeReplicacion rep) throws Exception {
        int lamport = contexto.getRelojLamport().actualizar(rep.getTimestampLamport());
        InputStream in = new ByteArrayInputStream(rep.getContenido() != null ? rep.getContenido() : new byte[0]);

        if (rep.getOperacion() == PeticionArchivo.Operacion.SUBIR) {
            contexto.getGestorArchivos().guardarArchivo(rep.getNombreArchivo(), in, rep.getTamanoBytes());
        } else if (rep.getOperacion() == PeticionArchivo.Operacion.EDITAR) {
            contexto.getGestorArchivos().editarArchivo(
                    rep.getNombreArchivo(), in, rep.getTamanoBytes(), lamport, rep.getNodeIdOrigen());
        }
    }

    public void sincronizarNodosRecuperados() {
        if (!contexto.soyCoordinador()) {
            return;
        }
        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                continue;
            }
            if (nodosPendientesSync.remove(nodo.getId()) || contexto.getServicioHeartbeats().necesitaSync(nodo.getId())) {
                sincronizarNodo(nodo);
            }
        }
    }

    public void sincronizarNodo(NodoInfo destino) {
        try {
            for (String archivo : contexto.getGestorArchivos().listarArchivos()) {
                byte[] contenido = contexto.getGestorArchivos().leerArchivoCompleto(archivo);
                MensajeReplicacion rep = new MensajeReplicacion(
                        PeticionArchivo.Operacion.SUBIR,
                        archivo,
                        contenido,
                        contenido.length,
                        contexto.getRelojLamport().incrementar(),
                        contexto.getNodoId(),
                        null);
                MensajeControl msg = new MensajeControl(TipoMensajeControl.SYNC_RESPONSE,
                        contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
                msg.setReplicacion(rep);
                ClienteControlNodo.enviarConRespuesta(destino, msg);
            }
            contexto.getRegistroEventos().registrar(
                    contexto.getRelojLamport().incrementar(), "SYNC_COMPLETADO destino=" + destino.getId());
        } catch (Exception e) {
            contexto.getRegistroEventos().registrar("SYNC_FALLIDO destino=" + destino.getId() + " " + e.getMessage());
        }
    }

    public void marcarParaSync(String nodoId) {
        nodosPendientesSync.add(nodoId);
    }
}
