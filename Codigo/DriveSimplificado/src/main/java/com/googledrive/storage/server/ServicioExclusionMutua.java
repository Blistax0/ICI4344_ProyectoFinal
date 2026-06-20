package com.googledrive.storage.server;

import com.googledrive.core.models.MensajeCoordinacion;
import com.googledrive.core.models.RegistroMembresia;
import com.googledrive.core.utils.RelojLamport;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServicioExclusionMutua {
    private final String idNodoLocal;
    private final RelojLamport relojLogico;
    
    public enum EstadoSeccionCritica { 
        LIBERADO,  
        BUSCANDO,  
        OCUPANDO   
    }
    
    private final Map<String, EstadoSeccionCritica> estadosPorArchivo = new ConcurrentHashMap<>();
    private final Map<String, Integer> tiemposDeMisSolicitudes = new ConcurrentHashMap<>();
    private final Map<String, List<MensajeCoordinacion>> solicitudesDiferidas = new ConcurrentHashMap<>();
    private final Map<String, Integer> permisosRecibidos = new ConcurrentHashMap<>();
    private final Map<String, Object> candadosDeEspera = new ConcurrentHashMap<>();

    public ServicioExclusionMutua(String idNodoLocal, RelojLamport relojLogico) {
        this.idNodoLocal = idNodoLocal;
        this.relojLogico = relojLogico;
    }

    // Inicia hilo para escuchar las solicitudes de otros nodos
    public void iniciarEscuchaCoordinacion() {
        int puertoLocal = RegistroMembresia.obtenerPuerto(idNodoLocal);
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(puertoLocal)) {
                System.out.println("[Coordinación] " + idNodoLocal + " escuchando en puerto " + puertoLocal);
                while (true) {
                    Socket conexionExterna = serverSocket.accept();
                    manejarMensajeEntrante(conexionExterna);
                }
            } catch (IOException e) {
                System.err.println("Error en hilo de coordinación: " + e.getMessage());
            }
        }).start();
    }

    private void manejarMensajeEntrante(Socket socketExterno) {
        try (ObjectInputStream ois = new ObjectInputStream(socketExterno.getInputStream())) {
            MensajeCoordinacion mensaje = (MensajeCoordinacion) ois.readObject();
            
            relojLogico.registrarRecepcion(mensaje.getTiempoLamport());
            
            if (mensaje.getTipo() == MensajeCoordinacion.Tipo.SOLICITUD_ACCESO) {
                evaluarSolicitudExterna(mensaje);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.PERMISO_CONCEDIDO) {
                evaluarPermisoRecibido(mensaje);
            }
        } catch (Exception e) {
            System.err.println("Error procesando mensaje de coordinación.");
        } finally {
            try { socketExterno.close(); } catch (IOException e) {}
        }
    }

    // Lógica de Ricart-Agrawala para decidir si dar permiso o diferir la petición
    private synchronized void evaluarSolicitudExterna(MensajeCoordinacion msjExterno) {
        String archivoSolicitado = msjExterno.getNombreArchivo();
        EstadoSeccionCritica estadoActual = estadosPorArchivo.getOrDefault(archivoSolicitado, EstadoSeccionCritica.LIBERADO);
        
        boolean deboDiferirAlOtro = false;
        Integer tiempoDeMiSolicitud = tiemposDeMisSolicitudes.get(archivoSolicitado);
        
        // Diferir si ya tenemos el archivo, o si lo pedimos antes (menor timestamp o desempate por nombre)
        if (estadoActual == EstadoSeccionCritica.OCUPANDO) {
            deboDiferirAlOtro = true;
        } else if (estadoActual == EstadoSeccionCritica.BUSCANDO && tiempoDeMiSolicitud != null) {
            boolean miSolicitudFuePrimero = tiempoDeMiSolicitud < msjExterno.getTiempoLamport();
            boolean huboEmpateGanoYo = (tiempoDeMiSolicitud == msjExterno.getTiempoLamport()) 
                                        && (idNodoLocal.compareTo(msjExterno.getIdNodoOrigen()) < 0);
            
            if (miSolicitudFuePrimero || huboEmpateGanoYo) {
                deboDiferirAlOtro = true;
            }
        }
        
        if (deboDiferirAlOtro) {
            solicitudesDiferidas.computeIfAbsent(archivoSolicitado, k -> new ArrayList<>()).add(msjExterno);
            System.out.println("[Ricart-Agrawala] " + idNodoLocal + " DIFIERE a " + msjExterno.getIdNodoOrigen());
        } else {
            enviarPermiso(msjExterno.getIdNodoOrigen(), archivoSolicitado);
        }
    }

    private void evaluarPermisoRecibido(MensajeCoordinacion msjPermiso) {
        String archivo = msjPermiso.getNombreArchivo();
        int totalPermisos = permisosRecibidos.getOrDefault(archivo, 0) + 1;
        permisosRecibidos.put(archivo, totalPermisos);
        
        int nodosRestantes = RegistroMembresia.obtenerNodos().size() - 1;
        
        if (totalPermisos >= nodosRestantes) {
            Object candadoHiloLocal = candadosDeEspera.get(archivo);
            if (candadoHiloLocal != null) {
                synchronized (candadoHiloLocal) {
                    candadoHiloLocal.notifyAll(); // Despierta el hilo para editar
                }
            }
        }
    }

    // Pide acceso al resto de nodos y bloquea hasta recibir todos los permisos
    public void solicitarAccesoCritico(String archivo) {
        int tiempoDeMiSolicitud = relojLogico.registrarEnvio(); 
        
        synchronized (this) {
            estadosPorArchivo.put(archivo, EstadoSeccionCritica.BUSCANDO);
            tiemposDeMisSolicitudes.put(archivo, tiempoDeMiSolicitud);
            permisosRecibidos.put(archivo, 0);
            candadosDeEspera.putIfAbsent(archivo, new Object());
        }
        
        for (String nodoDestino : RegistroMembresia.obtenerNodos().keySet()) {
            if (!nodoDestino.equals(idNodoLocal)) {
                enviarMensajeA(nodoDestino, new MensajeCoordinacion(MensajeCoordinacion.Tipo.SOLICITUD_ACCESO, tiempoDeMiSolicitud, idNodoLocal, archivo));
            }
        }
        
        int nodosRestantes = RegistroMembresia.obtenerNodos().size() - 1;
        if (nodosRestantes > 0) {
            Object candado = candadosDeEspera.get(archivo);
            synchronized (candado) {
                while (permisosRecibidos.getOrDefault(archivo, 0) < nodosRestantes) {
                    try {
                        candado.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        synchronized (this) {
            estadosPorArchivo.put(archivo, EstadoSeccionCritica.OCUPANDO);
        }
        System.out.println("[" + relojLogico.obtenerTiempo() + "] ---> Nodo " + idNodoLocal + " ENTRA a (" + archivo + ")");
    }

    // Libera el recurso y envía permisos a los nodos que dejamos en espera
    public void liberarAccesoCritico(String archivo) {
        List<MensajeCoordinacion> solicitudesAprobadas;
        relojLogico.registrarEventoLocal();
        
        synchronized (this) {
            estadosPorArchivo.put(archivo, EstadoSeccionCritica.LIBERADO);
            tiemposDeMisSolicitudes.remove(archivo);
            solicitudesAprobadas = new ArrayList<>(solicitudesDiferidas.getOrDefault(archivo, new ArrayList<>()));
            solicitudesDiferidas.remove(archivo);
            permisosRecibidos.put(archivo, 0);
        }
        
        System.out.println("[" + relojLogico.obtenerTiempo() + "] <--- Nodo " + idNodoLocal + " SALE de (" + archivo + ")");
        
        for (MensajeCoordinacion peticionPendiente : solicitudesAprobadas) {
            enviarPermiso(peticionPendiente.getIdNodoOrigen(), archivo);
        }
    }

    private void enviarPermiso(String nodoDestino, String archivo) {
        int tiempoPermiso = relojLogico.registrarEnvio();
        enviarMensajeA(nodoDestino, new MensajeCoordinacion(MensajeCoordinacion.Tipo.PERMISO_CONCEDIDO, tiempoPermiso, idNodoLocal, archivo));
    }

    private void enviarMensajeA(String nodoDestino, MensajeCoordinacion mensaje) {
        int puertoDestino = RegistroMembresia.obtenerPuerto(nodoDestino);
        if (puertoDestino == -1) return; 
        
        try (Socket socketRed = new Socket(RegistroMembresia.obtenerHost(), puertoDestino);
             ObjectOutputStream flujoSalida = new ObjectOutputStream(socketRed.getOutputStream())) {
            
            flujoSalida.writeObject(mensaje);
        } catch (IOException e) {
            System.err.println("No se pudo conectar con " + nodoDestino + ". Ignorando (o marcando como caído)");
        }
    }
}
