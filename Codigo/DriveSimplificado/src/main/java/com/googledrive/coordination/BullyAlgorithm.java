package com.googledrive.coordination;

import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.messages.MensajeControl;
import com.googledrive.core.messages.TipoMensajeControl;
import com.googledrive.core.metrics.MetricasCoordinacion;
import com.googledrive.storage.server.ClienteControlNodo;
import com.googledrive.storage.server.ContextoNodo;

import java.util.concurrent.atomic.AtomicBoolean;

public class BullyAlgorithm {
    private final ContextoNodo contexto;
    private final AtomicBoolean eleccionEnCurso = new AtomicBoolean(false);
    private volatile boolean recibiAnswer = false;

    public BullyAlgorithm(ContextoNodo contexto) {
        this.contexto = contexto;
    }

    public void iniciarEleccionInicial() {
        NodoInfo mayor = contexto.getConfiguracion().getNodoConMayorId().orElse(null);
        if (mayor != null) {
            contexto.setCoordinadorId(mayor.getId());
            if (mayor.getId().equals(contexto.getNodoId())) {
                declararCoordinador();
            }
        }
    }

    public synchronized void iniciarEleccion() {
        if (!eleccionEnCurso.compareAndSet(false, true)) {
            return;
        }
        recibiAnswer = false;
        contexto.getRegistroEventos().registrar(contexto.getRelojLamport().incrementar(), "ELECCION_INICIADA");
        int miId = contexto.getNodoInfo().getIdNumerico();

        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getIdNumerico() <= miId) {
                continue;
            }
            if (!contexto.getServicioHeartbeats().estaVivo(nodo.getId())) {
                continue;
            }
            MensajeControl msg = new MensajeControl(TipoMensajeControl.ELECTION,
                    contexto.getNodoId(), miId);
            msg.setTimestampLamport(contexto.getRelojLamport().incrementar());
            ClienteControlNodo.enviarMensaje(nodo, msg);
        }

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!recibiAnswer) {
            declararCoordinador();
        }
        eleccionEnCurso.set(false);
    }

    public void manejarEleccion(MensajeControl mensaje) {
        contexto.getRelojLamport().actualizar(mensaje.getTimestampLamport());
        MetricasCoordinacion.registrarMensaje();
        MensajeControl answer = new MensajeControl(TipoMensajeControl.ANSWER,
                contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
        answer.setTimestampLamport(contexto.getRelojLamport().incrementar());
        NodoInfo origen = contexto.getConfiguracion().getNodo(mensaje.getNodoOrigen());
        if (origen != null) {
            ClienteControlNodo.enviarMensaje(origen, answer);
        }
        iniciarEleccion();
    }

    public void manejarAnswer(MensajeControl mensaje) {
        contexto.getRelojLamport().actualizar(mensaje.getTimestampLamport());
        MetricasCoordinacion.registrarMensaje();
        recibiAnswer = true;
    }

    public void manejarCoordinador(MensajeControl mensaje) {
        contexto.getRelojLamport().actualizar(mensaje.getTimestampLamport());
        MetricasCoordinacion.registrarMensaje();
        contexto.setCoordinadorId(mensaje.getNodoOrigen());
        contexto.getRegistroEventos().registrar(mensaje.getTimestampLamport(),
                "NUEVO_COORDINADOR=" + mensaje.getNodoOrigen());
        contexto.getRegistroEventos().registrar(mensaje.getTimestampLamport(),
                "COORDINADOR=" + mensaje.getNodoOrigen());
    }

    public void declararCoordinador() {
        contexto.setCoordinadorId(contexto.getNodoId());
        int lamport = contexto.getRelojLamport().incrementar();
        contexto.getRegistroEventos().registrar(lamport, "NUEVO_COORDINADOR=" + contexto.getNodoId());
        contexto.getRegistroEventos().registrar(lamport, "COORDINADOR=" + contexto.getNodoId());
        MensajeControl msg = new MensajeControl(TipoMensajeControl.COORDINATOR,
                contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
        msg.setTimestampLamport(contexto.getRelojLamport().incrementar());

        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                continue;
            }
            ClienteControlNodo.enviarMensaje(nodo, msg);
        }
        contexto.getServicioReplicacion().sincronizarNodosRecuperados();
    }

    public void onCoordinadorCaido() {
        contexto.getRegistroEventos().registrar(contexto.getRelojLamport().incrementar(),
                "COORDINADOR_CAIDO iniciando eleccion");
        iniciarEleccion();
    }
}
