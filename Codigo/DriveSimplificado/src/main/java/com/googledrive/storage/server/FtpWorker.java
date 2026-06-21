package com.googledrive.storage.server;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.models.PeticionArchivo;

public class FtpWorker implements Runnable {
    private static final long ESPERA_COORDINADOR_MS = 3000;
    private static final long INTERVALO_POLL_MS = 200;
    private static final String ARCHIVO_COMPARTIDO = "documento_compartido.txt";

    private final Socket socket;
    private final ContextoNodo contexto;

    public FtpWorker(Socket socket, ContextoNodo contexto) {
        this.socket = socket;
        this.contexto = contexto;
    }

    @Override
    public void run() {
        try (
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                ObjectInputStream ois = new ObjectInputStream(in);
                ObjectOutputStream oos = new ObjectOutputStream(out)) {

            PeticionArchivo peticion = (PeticionArchivo) ois.readObject();
            GestorArchivosLocal gestor = contexto.getGestorArchivos();

            if (peticion.esEscritura()) {
                byte[] payload = leerBytes(in, peticion.getTamanoBytes());
                int lamport = contexto.getRelojLamport().actualizar(peticion.getTimestampLamport());
                String nodeOrigen = peticion.getNodeIdOrigen() != null ? peticion.getNodeIdOrigen() : "cliente";
                peticion.setTimestampLamport(lamport);

                boolean requiereMutex = peticion.getTipoOperacion() == PeticionArchivo.Operacion.EDITAR
                        && ARCHIVO_COMPARTIDO.equals(peticion.getNombreArchivo());
                if (requiereMutex) {
                    contexto.getServicioMutex().solicitarAccesoCritico(ARCHIVO_COMPARTIDO);
                }
                try {
                    if (!contexto.soyCoordinador()) {
                        NodoInfo coordinador = resolverCoordinador();
                        if (coordinador == null) {
                            oos.writeUTF("ERROR: Coordinador no disponible tras re-eleccion.");
                            oos.flush();
                            return;
                        }
                        ClienteDatosNodo.proxyPeticion(coordinador, peticion, payload, oos, out);
                        return;
                    }

                    boolean ok = contexto.getServicioReplicacion().replicarEscritura(peticion, payload, lamport, nodeOrigen);
                    if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.SUBIR) {
                        oos.writeUTF(ok ? "EXITO: Archivo subido correctamente." : "ERROR: Fallo en subida o replicacion.");
                    } else {
                        if (ok) {
                            oos.writeUTF("EXITO: Archivo editado. Sincronizando estado...");
                            oos.flush();
                            gestor.enviarArchivo(peticion.getNombreArchivo(), out);
                        } else {
                            oos.writeUTF("ERROR: Edicion corrupta o fallo de replicacion.");
                        }
                    }
                } finally {
                    if (requiereMutex) {
                        contexto.getServicioMutex().liberarAccesoCritico(ARCHIVO_COMPARTIDO);
                    }
                }
            } else if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.DESCARGAR) {
                gestor.enviarArchivo(peticion.getNombreArchivo(), out);
            }
            oos.flush();

        } catch (SocketTimeoutException e) {
            System.err.println("Timeout de conexion en FtpWorker: " + e.getMessage());
        } catch (EOFException | java.net.SocketException e) {
            System.err.println("Error de red: El cliente se desconecto abruptamente. " + e.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error de ejecucion en FtpWorker: " + e.getMessage());
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                System.err.println("Fallo al cerrar socket: " + e.getMessage());
            }
        }
    }

    private NodoInfo resolverCoordinador() {
        NodoInfo coordinador = contexto.getInfoCoordinador();
        if (coordinador != null && contexto.getServicioHeartbeats().estaVivo(coordinador.getId())) {
            return coordinador;
        }

        contexto.getBully().iniciarEleccion();
        long deadline = System.currentTimeMillis() + ESPERA_COORDINADOR_MS;
        while (System.currentTimeMillis() < deadline) {
            coordinador = contexto.getInfoCoordinador();
            if (coordinador != null && contexto.getServicioHeartbeats().estaVivo(coordinador.getId())) {
                return coordinador;
            }
            try {
                Thread.sleep(INTERVALO_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private byte[] leerBytes(InputStream in, long tamano) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        while (total < tamano) {
            int leidos = in.read(chunk, 0, (int) Math.min(chunk.length, tamano - total));
            if (leidos == -1) {
                break;
            }
            buffer.write(chunk, 0, leidos);
            total += leidos;
        }
        return buffer.toByteArray();
    }
}
