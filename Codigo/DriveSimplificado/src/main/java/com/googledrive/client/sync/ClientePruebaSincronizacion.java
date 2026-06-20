package com.googledrive.client.sync;

import com.googledrive.core.models.PeticionArchivo;

import java.io.*;
import java.net.Socket;

public class ClientePruebaSincronizacion {
    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9000;
    private static final String ARCHIVO_SYNC = "documento_compartido.txt";

    public static void main(String[] args) {
        System.out.println("=== Iniciando Prueba de Sincronización Concurrente (Estilo Google Docs) ===");
        System.out.println("Se lanzarán 3 clientes simultáneos para editar el mismo archivo...\n");

        // creamos 3 hilos para probar que 3 usuarios distintos editen el archivo a la vez
        Thread cliente1 = new Thread(() -> editarDocumento("Usuario_1", "Hola, soy el Usuario 1 agregando texto.\n"));
        Thread cliente2 = new Thread(() -> editarDocumento("Usuario_2", "Este es el aporte del Usuario 2 al documento.\n"));
        Thread cliente3 = new Thread(() -> editarDocumento("Usuario_3", "Ahora el Usuario 3 escribe su parte.\n"));

        // le damos start a los hilos para que corran en paralelo y forzar la concurrencia
        cliente1.start();
        cliente2.start();
        cliente3.start();

        try {
            // esperamos que terminen los threads
            cliente1.join();
            cliente2.join();
            cliente3.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("\n=== Prueba de Sincronización Finalizada ===");
    }

    private static void editarDocumento(String nombreUsuario, String textoAgregar) {
        // le puse un delay random para que no lleguen exactamente al mismo milisegundo y se vea mas real
        // como si la gente se demorara distinto en escribir
        try {
            int delay = (int) (Math.random() * 1500);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try (Socket socket = new Socket(HOST, PUERTO);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                ObjectOutputStream oos = new ObjectOutputStream(out);
                ObjectInputStream ois = new ObjectInputStream(in)) {

            byte[] bytesAporte = textoAgregar.getBytes();

            System.out.println("[" + nombreUsuario + "] Conectado. Enviando edición...");

            // mandamos la peticion con el enum EDITAR
            PeticionArchivo peticion = new PeticionArchivo(
                    PeticionArchivo.Operacion.EDITAR,
                    ARCHIVO_SYNC,
                    bytesAporte.length);
            oos.writeObject(peticion);
            oos.flush();

            // mandamos el texto en bytes
            out.write(bytesAporte);
            out.flush();

            // esperamos la respuesta del server
            String confirmacion = ois.readUTF();
            System.out.println("[" + nombreUsuario + "] Servidor responde: " + confirmacion);

            // aca leemos todo el archivo de vuelta para ver como quedo despues de editarlo
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int leidos;
            // el read() se queda pegado hasta que el server corta la conexion, asi leemos todo de una
            while ((leidos = in.read(buf)) != -1) {
                buffer.write(buf, 0, leidos);
            }

            String estadoFinal = new String(buffer.toByteArray());
            System.out.println("\n--- ESTADO DEL DOCUMENTO VISTO POR [" + nombreUsuario + "] ---\n" + estadoFinal
                    + "--------------------------------------------------------\n");

        } catch (IOException e) {
            System.err.println("[" + nombreUsuario + "] Fallo en la conexión: " + e.getMessage());
        }
    }
}
