package com.googledrive.storage.server;

import com.googledrive.coordination.BullyAlgorithm;
import com.googledrive.core.messages.MensajeControl;
import com.googledrive.core.messages.TipoMensajeControl;
import com.googledrive.core.metrics.MetricasCoordinacion;
import com.googledrive.fault.ServicioHeartbeats;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ServidorControl implements Runnable {
    private final ContextoNodo contexto;
    private final int puertoControl;
    private volatile boolean activo = true;

    public ServidorControl(ContextoNodo contexto, int puertoControl) {
        this.contexto = contexto;
        this.puertoControl = puertoControl;
    }

    public void detener() {
        activo = false;
    }

    @Override
    public void run() {
        try {
            SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(puertoControl)) {
                System.out.println("[Control] Escuchando en puerto " + puertoControl);
                while (activo) {
                    try {
                        Socket socket = serverSocket.accept();
                        socket.setSoTimeout(5000);
                        new Thread(() -> procesarConexion(socket)).start();
                    } catch (Exception e) {
                        if (activo) {
                            System.err.println("[Control] Error aceptando: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Control] Fallo critico: " + e.getMessage());
        }
    }

    private void procesarConexion(Socket socket) {
        try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {

            MensajeControl mensaje = (MensajeControl) ois.readObject();
            MetricasCoordinacion.registrarMensaje();
            MensajeControl respuesta = manejarMensaje(mensaje);
            if (respuesta != null) {
                oos.writeObject(respuesta);
                oos.flush();
            }
        } catch (Exception e) {
            System.err.println("[Control] Error procesando mensaje: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private MensajeControl manejarMensaje(MensajeControl mensaje) {
        BullyAlgorithm bully = contexto.getBully();
        ServicioHeartbeats heartbeats = contexto.getServicioHeartbeats();
        ServicioReplicacion replicacion = contexto.getServicioReplicacion();

        switch (mensaje.getTipo()) {
            case ELECTION -> {
                bully.manejarEleccion(mensaje);
                return null;
            }
            case ANSWER -> {
                bully.manejarAnswer(mensaje);
                return null;
            }
            case COORDINATOR -> {
                bully.manejarCoordinador(mensaje);
                return null;
            }
            case HEARTBEAT -> {
                heartbeats.registrarLatido(mensaje.getNodoOrigen());
                return null;
            }
            case REPLICATE -> {
                try {
                    replicacion.aplicarReplicacion(mensaje.getReplicacion());
                    MensajeControl ack = new MensajeControl(TipoMensajeControl.REPLICATE_ACK,
                            contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
                    ack.setTimestampLamport(contexto.getRelojLamport().incrementar());
                    return ack;
                } catch (Exception e) {
                    contexto.getRegistroEventos().registrar("REPLICACION_RECIBIDA_FALLIDA: " + e.getMessage());
                    return null;
                }
            }
            case SYNC_RESPONSE -> {
                try {
                    replicacion.aplicarReplicacion(mensaje.getReplicacion());
                    heartbeats.marcarSincronizado(mensaje.getNodoOrigen());
                } catch (Exception e) {
                    contexto.getRegistroEventos().registrar("SYNC_RECIBIDO_FALLIDO: " + e.getMessage());
                }
                return null;
            }
            case METRICS_REQUEST -> {
                MensajeControl resp = new MensajeControl(TipoMensajeControl.METRICS_RESPONSE,
                        contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
                resp.setValorMetrica(MetricasCoordinacion.obtenerTotal());
                return resp;
            }
            case COORDINADOR_QUERY -> {
                MensajeControl resp = new MensajeControl(TipoMensajeControl.COORDINADOR_RESPONSE,
                        contexto.getNodoId(), contexto.getNodoInfo().getIdNumerico());
                resp.setCoordinadorId(contexto.getCoordinadorId());
                return resp;
            }
            default -> {
                return null;
            }
        }
    }
}
