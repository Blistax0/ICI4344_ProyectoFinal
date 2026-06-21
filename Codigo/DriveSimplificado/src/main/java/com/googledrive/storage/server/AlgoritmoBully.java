package com.googledrive.storage.server;

import com.googledrive.core.models.MensajeCoordinacion;
import com.googledrive.core.models.RegistroMembresia;
import com.googledrive.core.utils.RelojLamport;

import java.util.concurrent.atomic.AtomicBoolean;

public class AlgoritmoBully {
    private final String idNodoLocal;
    private final ServicioExclusionMutua servicioCoordinacion;
    private final RelojLamport relojLogico;
    
    private String idCoordinadorActual = null;
    private final AtomicBoolean eleccionEnCurso = new AtomicBoolean(false);
    private final AtomicBoolean recibiOk = new AtomicBoolean(false);

    public AlgoritmoBully(String idNodoLocal, ServicioExclusionMutua servicioCoordinacion, RelojLamport relojLogico) {
        this.idNodoLocal = idNodoLocal;
        this.servicioCoordinacion = servicioCoordinacion;
        this.relojLogico = relojLogico;
    }

    public String getIdCoordinadorActual() {
        return idCoordinadorActual;
    }

    public boolean soyCoordinador() {
        return idNodoLocal.equals(idCoordinadorActual);
    }

    public void iniciarEleccion() {
        if (!eleccionEnCurso.compareAndSet(false, true)) {
            return; // Ya hay una elección en curso
        }
        System.out.println("\n[Bully] Iniciando elección de coordinador desde " + idNodoLocal);
        recibiOk.set(false);
        int tiempo = relojLogico.registrarEnvio();

        boolean soyMayor = true;
        for (String nodoDestino : RegistroMembresia.obtenerNodos().keySet()) {
            if (!RegistroMembresia.estaCaido(nodoDestino) && nodoDestino.compareTo(idNodoLocal) > 0) {
                soyMayor = false;
                servicioCoordinacion.enviarMensajeA(nodoDestino, new MensajeCoordinacion(MensajeCoordinacion.Tipo.ELECCION, tiempo, idNodoLocal, ""));
            }
        }

        if (soyMayor) {
            proclamarseCoordinador();
        } else {
            // Esperar respuestas OK_ELECCION
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    if (!recibiOk.get()) {
                        System.out.println("[Bully] No recibí respuestas. Me proclamo coordinador.");
                        proclamarseCoordinador();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    private void proclamarseCoordinador() {
        idCoordinadorActual = idNodoLocal;
        eleccionEnCurso.set(false);
        System.out.println("\n***************************************************");
        System.out.println("[Bully] EL NODO " + idNodoLocal + " ES EL NUEVO COORDINADOR");
        System.out.println("***************************************************\n");
        int tiempo = relojLogico.registrarEnvio();
        for (String nodoDestino : RegistroMembresia.obtenerNodos().keySet()) {
            if (!nodoDestino.equals(idNodoLocal) && !RegistroMembresia.estaCaido(nodoDestino)) {
                servicioCoordinacion.enviarMensajeA(nodoDestino, new MensajeCoordinacion(MensajeCoordinacion.Tipo.COORDINADOR, tiempo, idNodoLocal, ""));
            }
        }
    }

    public void recibirMensaje(MensajeCoordinacion msj) {
        if (msj.getTipo() == MensajeCoordinacion.Tipo.ELECCION) {
            System.out.println("[Bully] Recibí ELECCION de " + msj.getIdNodoOrigen());
            int tiempo = relojLogico.registrarEnvio();
            servicioCoordinacion.enviarMensajeA(msj.getIdNodoOrigen(), new MensajeCoordinacion(MensajeCoordinacion.Tipo.OK_ELECCION, tiempo, idNodoLocal, ""));
            iniciarEleccion();
        } else if (msj.getTipo() == MensajeCoordinacion.Tipo.OK_ELECCION) {
            System.out.println("[Bully] Recibí OK_ELECCION de " + msj.getIdNodoOrigen() + ". Deteniendo mi candidatura.");
            recibiOk.set(true);
        } else if (msj.getTipo() == MensajeCoordinacion.Tipo.COORDINADOR) {
            idCoordinadorActual = msj.getIdNodoOrigen();
            eleccionEnCurso.set(false);
            System.out.println("\n[Bully] Reconozco a " + msj.getIdNodoOrigen() + " como nuevo coordinador.\n");
        }
    }
}
