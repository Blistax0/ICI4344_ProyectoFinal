package com.googledrive.storage.server;

import com.googledrive.core.models.MensajeCoordinacion;
import com.googledrive.core.models.RegistroMembresia;
import com.googledrive.core.utils.RelojLamport;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MonitorNodos {
    private final String idNodoLocal;
    private final RelojLamport relojLogico;
    private final ServicioExclusionMutua servicioExclusionMutua;
    private final Map<String, Long> ultimosLatidos = new ConcurrentHashMap<>();
    
    private static final long TIMEOUT_CAIDA_MS = 5000;
    private static final long INTERVALO_HEARTBEAT_MS = 2000;
    private boolean activo = true;

    public MonitorNodos(String idNodoLocal, RelojLamport relojLogico, ServicioExclusionMutua servicioExclusionMutua) {
        this.idNodoLocal = idNodoLocal;
        this.relojLogico = relojLogico;
        this.servicioExclusionMutua = servicioExclusionMutua;
        
        for (String nodo : RegistroMembresia.obtenerNodos().keySet()) {
            ultimosLatidos.put(nodo, System.currentTimeMillis());
        }
    }

    public void registrarLatido(String idNodo) {
        ultimosLatidos.put(idNodo, System.currentTimeMillis());
    }

    public void iniciar() {
        // Hilo para enviar heartbeats
        new Thread(() -> {
            while (activo) {
                try {
                    Thread.sleep(INTERVALO_HEARTBEAT_MS);
                    enviarHeartbeats();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();

        // Hilo para detectar caídas
        new Thread(() -> {
            while (activo) {
                try {
                    Thread.sleep(1000);
                    revisarCaidas();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void enviarHeartbeats() {
        int tiempoActual = relojLogico.registrarEnvio();
        for (String nodoDestino : RegistroMembresia.obtenerNodos().keySet()) {
            if (!nodoDestino.equals(idNodoLocal) && !RegistroMembresia.estaCaido(nodoDestino)) {
                try (Socket socketRed = new Socket(RegistroMembresia.obtenerHost(), RegistroMembresia.obtenerPuerto(nodoDestino));
                     ObjectOutputStream flujoSalida = new ObjectOutputStream(socketRed.getOutputStream())) {
                    
                    MensajeCoordinacion hb = new MensajeCoordinacion(MensajeCoordinacion.Tipo.HEARTBEAT, tiempoActual, idNodoLocal, "");
                    flujoSalida.writeObject(hb);
                } catch (IOException e) {
                    // Si falla la conexión, la detección de caídas lo atrapará por timeout de recepción.
                }
            }
        }
    }

    private void revisarCaidas() {
        long ahora = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : ultimosLatidos.entrySet()) {
            String nodo = entry.getKey();
            if (nodo.equals(idNodoLocal) || RegistroMembresia.estaCaido(nodo)) continue;

            if (ahora - entry.getValue() > TIMEOUT_CAIDA_MS) {
                System.out.println(">>> NODO CAÍDO DETECTADO: " + nodo + " <<<");
                RegistroMembresia.marcarCaido(nodo);
                servicioExclusionMutua.notificarNodoCaido(nodo);
            }
        }
    }
}
