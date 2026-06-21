package com.googledrive.coordination;

import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.messages.MensajeCoordinacion;
import com.googledrive.core.metrics.MetricasCoordinacion;
import com.googledrive.storage.server.ContextoNodo;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServicioExclusionMutua {
    private static final long TIMEOUT_PERMISOS_MS = 5000;

    public enum EstadoSeccionCritica {
        LIBERADO, BUSCANDO, OCUPANDO
    }

    private final ContextoNodo contexto;
    private volatile boolean activo = true;

    private final Map<String, EstadoSeccionCritica> estadosPorArchivo = new ConcurrentHashMap<>();
    private final Map<String, Integer> tiemposDeMisSolicitudes = new ConcurrentHashMap<>();
    private final Map<String, List<MensajeCoordinacion>> solicitudesDiferidas = new ConcurrentHashMap<>();
    private final Map<String, Integer> permisosRecibidos = new ConcurrentHashMap<>();
    private final Map<String, Integer> permisosNecesarios = new ConcurrentHashMap<>();
    private final Map<String, Object> candadosDeEspera = new ConcurrentHashMap<>();

    public ServicioExclusionMutua(ContextoNodo contexto) {
        this.contexto = contexto;
    }

    public void iniciarEscucha() {
        int puertoLocal = contexto.getNodoInfo().getPuertoMutex();
        Thread hilo = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(puertoLocal)) {
                System.out.println("[Ricart] " + contexto.getNodoId() + " escuchando mutex en puerto " + puertoLocal);
                while (activo) {
                    Socket conexion = serverSocket.accept();
                    new Thread(() -> manejarMensajeEntrante(conexion)).start();
                }
            } catch (IOException e) {
                if (activo) {
                    System.err.println("[Ricart] Error en escucha: " + e.getMessage());
                }
            }
        }, "Ricart-" + contexto.getNodoId());
        hilo.setDaemon(true);
        hilo.start();
    }

    public void detener() {
        activo = false;
    }

    private void manejarMensajeEntrante(Socket socket) {
        try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
            MensajeCoordinacion mensaje = (MensajeCoordinacion) ois.readObject();
            MetricasCoordinacion.registrarMensaje();
            int lamport = contexto.getRelojLamport().actualizar(mensaje.getTiempoLamport());

            if (mensaje.getTipo() == MensajeCoordinacion.Tipo.SOLICITUD_ACCESO) {
                evaluarSolicitudExterna(mensaje, lamport);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.PERMISO_CONCEDIDO) {
                evaluarPermisoRecibido(mensaje);
            }
        } catch (Exception e) {
            System.err.println("[Ricart] Error procesando mensaje: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private synchronized void evaluarSolicitudExterna(MensajeCoordinacion msjExterno, int lamport) {
        String archivo = msjExterno.getNombreArchivo();
        EstadoSeccionCritica estado = estadosPorArchivo.getOrDefault(archivo, EstadoSeccionCritica.LIBERADO);
        boolean diferir = false;
        Integer miTiempo = tiemposDeMisSolicitudes.get(archivo);

        if (estado == EstadoSeccionCritica.OCUPANDO) {
            diferir = true;
        } else if (estado == EstadoSeccionCritica.BUSCANDO && miTiempo != null) {
            boolean yoPrimero = miTiempo < msjExterno.getTiempoLamport();
            boolean empateGanoYo = miTiempo.equals(msjExterno.getTiempoLamport())
                    && contexto.getNodoId().compareTo(msjExterno.getIdNodoOrigen()) < 0;
            diferir = yoPrimero || empateGanoYo;
        }

        if (diferir) {
            solicitudesDiferidas.computeIfAbsent(archivo, k -> new ArrayList<>()).add(msjExterno);
            contexto.getRegistroEventos().registrar(lamport,
                    "RICART_DIFIERE origen=" + msjExterno.getIdNodoOrigen() + " archivo=" + archivo);
        } else {
            enviarPermiso(msjExterno.getIdNodoOrigen(), archivo);
        }
    }

    private void evaluarPermisoRecibido(MensajeCoordinacion msjPermiso) {
        String archivo = msjPermiso.getNombreArchivo();
        int total = permisosRecibidos.getOrDefault(archivo, 0) + 1;
        permisosRecibidos.put(archivo, total);

        int necesarios = permisosNecesarios.getOrDefault(archivo, 0);
        if (total >= necesarios) {
            Object candado = candadosDeEspera.get(archivo);
            if (candado != null) {
                synchronized (candado) {
                    candado.notifyAll();
                }
            }
        }
    }

    public void solicitarAccesoCritico(String archivo) {
        int tiempoSolicitud = contexto.getRelojLamport().incrementar();
        int nodosVivos = contarNodosVivosExcluyendoSelf();

        synchronized (this) {
            estadosPorArchivo.put(archivo, EstadoSeccionCritica.BUSCANDO);
            tiemposDeMisSolicitudes.put(archivo, tiempoSolicitud);
            permisosRecibidos.put(archivo, 0);
            permisosNecesarios.put(archivo, nodosVivos);
            candadosDeEspera.putIfAbsent(archivo, new Object());
        }

        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                continue;
            }
            if (!contexto.getServicioHeartbeats().estaVivo(nodo.getId())) {
                continue;
            }
            enviarMensajeA(nodo, new MensajeCoordinacion(
                    MensajeCoordinacion.Tipo.SOLICITUD_ACCESO,
                    tiempoSolicitud, contexto.getNodoId(), archivo));
        }

        if (nodosVivos > 0) {
            Object candado = candadosDeEspera.get(archivo);
            long deadline = System.currentTimeMillis() + TIMEOUT_PERMISOS_MS;
            synchronized (candado) {
                while (permisosRecibidos.getOrDefault(archivo, 0) < nodosVivos) {
                    long restante = deadline - System.currentTimeMillis();
                    if (restante <= 0) {
                        contexto.getRegistroEventos().registrar(contexto.getRelojLamport().incrementar(),
                                "RICART_TIMEOUT archivo=" + archivo + " permisos="
                                        + permisosRecibidos.getOrDefault(archivo, 0) + "/" + nodosVivos);
                        break;
                    }
                    try {
                        candado.wait(restante);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        synchronized (this) {
            estadosPorArchivo.put(archivo, EstadoSeccionCritica.OCUPANDO);
        }
        contexto.getRegistroEventos().registrar(contexto.getRelojLamport().obtener(),
                "RICART_ENTRA archivo=" + archivo);
    }

    public void liberarAccesoCritico(String archivo) {
        List<MensajeCoordinacion> pendientes;
        contexto.getRelojLamport().incrementar();

        synchronized (this) {
            estadosPorArchivo.put(archivo, EstadoSeccionCritica.LIBERADO);
            tiemposDeMisSolicitudes.remove(archivo);
            permisosNecesarios.remove(archivo);
            pendientes = new ArrayList<>(solicitudesDiferidas.getOrDefault(archivo, new ArrayList<>()));
            solicitudesDiferidas.remove(archivo);
            permisosRecibidos.put(archivo, 0);
        }

        contexto.getRegistroEventos().registrar(contexto.getRelojLamport().obtener(),
                "RICART_SALE archivo=" + archivo);

        for (MensajeCoordinacion peticion : pendientes) {
            enviarPermiso(peticion.getIdNodoOrigen(), archivo);
        }
    }

    private void enviarPermiso(String nodoDestinoId, String archivo) {
        int tiempo = contexto.getRelojLamport().incrementar();
        NodoInfo destino = contexto.getConfiguracion().getNodo(nodoDestinoId);
        if (destino != null) {
            enviarMensajeA(destino, new MensajeCoordinacion(
                    MensajeCoordinacion.Tipo.PERMISO_CONCEDIDO, tiempo, contexto.getNodoId(), archivo));
        }
    }

    private void enviarMensajeA(NodoInfo destino, MensajeCoordinacion mensaje) {
        try (Socket socket = new Socket(destino.getHost(), destino.getPuertoMutex());
             ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {
            oos.writeObject(mensaje);
            oos.flush();
            MetricasCoordinacion.registrarMensaje();
        } catch (IOException e) {
            contexto.getRegistroEventos().registrar(contexto.getRelojLamport().incrementar(),
                    "OMISION_MENSAJE ricart destino=" + destino.getId() + " " + e.getMessage());
            contexto.getServicioHeartbeats().marcarSospechoso(destino.getId());
        }
    }

    private int contarNodosVivosExcluyendoSelf() {
        int count = 0;
        for (NodoInfo nodo : contexto.getConfiguracion().getTodosLosNodos()) {
            if (nodo.getId().equals(contexto.getNodoId())) {
                continue;
            }
            if (contexto.getServicioHeartbeats().estaVivo(nodo.getId())) {
                count++;
            }
        }
        return count;
    }
}
