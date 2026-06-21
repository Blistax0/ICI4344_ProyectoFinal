package com.googledrive.storage.server;

import com.googledrive.core.models.MensajeCoordinacion;
import com.googledrive.core.models.RegistroMembresia;
import com.googledrive.core.utils.RelojLamport;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    
    private final MonitorNodos monitorNodos;
    private final AlgoritmoBully algoritmoBully;
    private final AtomicInteger mensajesCoordinacionEnviados = new AtomicInteger(0);

    public ServicioExclusionMutua(String idNodoLocal, RelojLamport relojLogico) {
        this.idNodoLocal = idNodoLocal;
        this.relojLogico = relojLogico;
        this.algoritmoBully = new AlgoritmoBully(idNodoLocal, this, relojLogico);
        this.monitorNodos = new MonitorNodos(idNodoLocal, relojLogico, this);
    }

    public AlgoritmoBully getAlgoritmoBully() {
        return algoritmoBully;
    }

    public void iniciarMonitor() {
        monitorNodos.iniciar();
    }
    
    public int getMensajesCoordinacion() {
        return mensajesCoordinacionEnviados.get();
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
            
            if (mensaje.getTipo() == MensajeCoordinacion.Tipo.HEARTBEAT) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.SOLICITUD_ACCESO) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
                evaluarSolicitudExterna(mensaje);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.PERMISO_CONCEDIDO) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
                evaluarPermisoRecibido(mensaje);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.ELECCION || 
                       mensaje.getTipo() == MensajeCoordinacion.Tipo.OK_ELECCION || 
                       mensaje.getTipo() == MensajeCoordinacion.Tipo.COORDINADOR) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
                algoritmoBully.recibirMensaje(mensaje);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.REPLICAR_ARCHIVO) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
                manejarReplicacion(mensaje);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.SOLICITUD_SYNC) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
                manejarSolicitudSync(mensaje);
            } else if (mensaje.getTipo() == MensajeCoordinacion.Tipo.RESPUESTA_SYNC) {
                monitorNodos.registrarLatido(mensaje.getIdNodoOrigen());
                manejarRespuestaSync(mensaje);
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
        
        int nodosRestantes = RegistroMembresia.cantidadNodosActivos() - 1;
        
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
            if (!nodoDestino.equals(idNodoLocal) && !RegistroMembresia.estaCaido(nodoDestino)) {
                enviarMensajeA(nodoDestino, new MensajeCoordinacion(MensajeCoordinacion.Tipo.SOLICITUD_ACCESO, tiempoDeMiSolicitud, idNodoLocal, archivo));
            }
        }
        
        Object candado = candadosDeEspera.get(archivo);
        synchronized (candado) {
            while (true) {
                int nodosRestantes = RegistroMembresia.cantidadNodosActivos() - 1;
                if (nodosRestantes <= 0 || permisosRecibidos.getOrDefault(archivo, 0) >= nodosRestantes) {
                    break;
                }
                try {
                    candado.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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

    public void enviarMensajeA(String nodoDestino, MensajeCoordinacion mensaje) {
        if (mensaje.getTipo() != MensajeCoordinacion.Tipo.HEARTBEAT) {
            mensajesCoordinacionEnviados.incrementAndGet();
        }
        int puertoDestino = RegistroMembresia.obtenerPuerto(nodoDestino);
        if (puertoDestino == -1) return; 
        
        try (Socket socketRed = new Socket(RegistroMembresia.obtenerHost(), puertoDestino);
             ObjectOutputStream flujoSalida = new ObjectOutputStream(socketRed.getOutputStream())) {
            
            flujoSalida.writeObject(mensaje);
        } catch (IOException e) {
            System.err.println("No se pudo conectar con " + nodoDestino + ". Ignorando (o marcando como caído)");
        }
    }

    public void notificarNodoCaido(String nodo) {
        for (Object candado : candadosDeEspera.values()) {
            synchronized (candado) {
                candado.notifyAll();
            }
        }
        if (nodo.equals(algoritmoBully.getIdCoordinadorActual())) {
            System.out.println("[ServicioExclusionMutua] El coordinador " + nodo + " ha caído. Iniciando elección...");
            algoritmoBully.iniciarEleccion();
        }
    }

    private void manejarReplicacion(MensajeCoordinacion msj) {
        System.out.println("[Replicación] Recibida réplica de " + msj.getNombreArchivo() + " desde " + msj.getIdNodoOrigen());
        try {
            GestorArchivosLocal gestor = new GestorArchivosLocal();
            // Simular InputStream con un ByteArrayInputStream
            ByteArrayInputStream bais = new ByteArrayInputStream(msj.getContenidoArchivo());
            gestor.guardarArchivo(msj.getNombreArchivo(), bais, msj.getContenidoArchivo().length);
        } catch (Exception e) {
            System.err.println("Error guardando réplica: " + e.getMessage());
        }
    }

    private void manejarSolicitudSync(MensajeCoordinacion msj) {
        if (!algoritmoBully.soyCoordinador()) return;
        System.out.println("[Sync] El coordinador atiende solicitud de sincronización de " + msj.getIdNodoOrigen());
        
        File dir = new File("./storage_data/");
        if (!dir.exists()) return;
        
        File[] archivos = dir.listFiles();
        if (archivos != null) {
            for (File f : archivos) {
                try {
                    byte[] contenido = java.nio.file.Files.readAllBytes(f.toPath());
                    MensajeCoordinacion respuesta = new MensajeCoordinacion(
                        MensajeCoordinacion.Tipo.RESPUESTA_SYNC, relojLogico.registrarEnvio(), 
                        idNodoLocal, f.getName(), contenido);
                    enviarMensajeA(msj.getIdNodoOrigen(), respuesta);
                } catch (IOException e) {
                    System.err.println("Error leyendo archivo para sync: " + e.getMessage());
                }
            }
        }
    }

    private void manejarRespuestaSync(MensajeCoordinacion msj) {
        System.out.println("[Sync] Sincronizado archivo faltante: " + msj.getNombreArchivo());
        try {
            GestorArchivosLocal gestor = new GestorArchivosLocal();
            ByteArrayInputStream bais = new ByteArrayInputStream(msj.getContenidoArchivo());
            gestor.guardarArchivo(msj.getNombreArchivo(), bais, msj.getContenidoArchivo().length);
        } catch (Exception e) {
            System.err.println("Error guardando archivo sync: " + e.getMessage());
        }
    }

    public void replicarATodos(String nombreArchivo) {
        try {
            File f = new File("./storage_data/" + nombreArchivo);
            if (!f.exists()) return;
            byte[] contenido = java.nio.file.Files.readAllBytes(f.toPath());
            int tiempo = relojLogico.registrarEnvio();
            
            for (String nodoDestino : RegistroMembresia.obtenerNodos().keySet()) {
                if (!nodoDestino.equals(idNodoLocal) && !RegistroMembresia.estaCaido(nodoDestino)) {
                    MensajeCoordinacion msj = new MensajeCoordinacion(
                        MensajeCoordinacion.Tipo.REPLICAR_ARCHIVO, tiempo, 
                        idNodoLocal, nombreArchivo, contenido);
                    enviarMensajeA(nodoDestino, msj);
                }
            }
        } catch (IOException e) {
            System.err.println("Error al leer archivo para replicar: " + e.getMessage());
        }
    }
}
