package com.googledrive.client.sync;

import com.googledrive.core.clock.RelojLamport;
import com.googledrive.core.config.ConfiguracionRed;
import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.models.PeticionArchivo;
import com.googledrive.core.utils.Utils;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;

public class ClientePruebaSincronizacion {
    private static final String ARCHIVO_SYNC = "documento_compartido.txt";
    private static final RelojLamport reloj = new RelojLamport();

    public static void main(String[] args) throws Exception {
        String configPath = args.length >= 1 ? args[0] : "nodos.txt";
        ConfiguracionRed config = ConfiguracionRed.cargar(configPath);
        NodoInfo nodo = config.getNodo("nodo1");

        System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "password");

        System.out.println("=== Prueba de Sincronizacion Concurrente (TLS + Lamport) ===\n");

        Thread cliente1 = new Thread(() -> editarDocumento(nodo, "Usuario_1", "Hola, soy el Usuario 1 agregando texto.\n"));
        Thread cliente2 = new Thread(() -> editarDocumento(nodo, "Usuario_2", "Este es el aporte del Usuario 2 al documento.\n"));
        Thread cliente3 = new Thread(() -> editarDocumento(nodo, "Usuario_3", "Ahora el Usuario 3 escribe su parte.\n"));

        cliente1.start();
        cliente2.start();
        cliente3.start();

        cliente1.join();
        cliente2.join();
        cliente3.join();

        System.out.println("\n=== Prueba de Sincronizacion Finalizada ===");
    }

    private static void editarDocumento(NodoInfo nodo, String nombreUsuario, String textoAgregar) {
        try {
            Thread.sleep((int) (Math.random() * 1500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        int lamport = reloj.incrementar();

        try (Socket socket = ssf.createSocket(nodo.getHost(), nodo.getPuertoDatos());
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                ObjectOutputStream oos = new ObjectOutputStream(out);
                ObjectInputStream ois = new ObjectInputStream(in)) {

            byte[] bytesAporte = textoAgregar.getBytes();
            String md5 = Utils.calcularChecksum(bytesAporte);

            System.out.println("[" + nombreUsuario + "] Conectado a " + nodo.getId() + " (Lamport=" + lamport + ")");

            PeticionArchivo peticion = new PeticionArchivo(
                    PeticionArchivo.Operacion.EDITAR, ARCHIVO_SYNC, bytesAporte.length);
            peticion.setChecksum(md5);
            peticion.setTimestampLamport(lamport);
            peticion.setNodeIdOrigen(nombreUsuario);
            oos.writeObject(peticion);
            oos.flush();
            out.write(bytesAporte);
            out.flush();

            String confirmacion = ois.readUTF();
            System.out.println("[" + nombreUsuario + "] Servidor responde: " + confirmacion);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int leidos;
            while ((leidos = in.read(buf)) != -1) {
                buffer.write(buf, 0, leidos);
            }

            System.out.println("\n--- ESTADO DEL DOCUMENTO VISTO POR [" + nombreUsuario + "] ---\n"
                    + buffer + "\n--------------------------------------------------------\n");

        } catch (IOException e) {
            System.err.println("[" + nombreUsuario + "] Fallo en la conexion: " + e.getMessage());
        }
    }
}
