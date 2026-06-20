package com.googledrive.client.test;

import com.googledrive.core.models.PeticionArchivo;
import java.io.*;
import java.net.Socket;

public class ClientePruebaAlmacenamiento {
    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9000;

    public static void main(String[] args) {
        System.out.println("Iniciando prueba de red (Cliente -> Nodo de Almacenamiento)...");
        ejecutarPruebaSubida();
    }

    private static void ejecutarPruebaSubida() {
        // Se utiliza try-with-resources para garantizar el cierre del Socket
        try (Socket socket = new Socket(HOST, PUERTO);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             // El ObjectOutputStream debe inicializarse ANTES que el ObjectInputStream para evitar bloqueos
             ObjectOutputStream oos = new ObjectOutputStream(out);
             ObjectInputStream ois = new ObjectInputStream(in)) {

            // 1. Generar un archivo simulado en memoria
            String contenidoSimulado = "Este es un flujo de bytes de prueba para el sistema distribuido. Archivo generado correctamente.";
            byte[] bytesArchivo = contenidoSimulado.getBytes();
            String nombreArchivo = "prueba_conexion.txt";

            System.out.println("Conectado al servidor. Transfiriendo archivo: " + nombreArchivo);

            // 2. Transmisión de Control (Marshalling)
            PeticionArchivo peticion = new PeticionArchivo(
                PeticionArchivo.Operacion.SUBIR, 
                nombreArchivo, 
                bytesArchivo.length
            );
            oos.writeObject(peticion);
            oos.flush();

            // 3. Transmisión de Datos Binarios
            out.write(bytesArchivo);
            out.flush();

            // 4. Recepción de Confirmación
            String respuestaServidor = ois.readUTF();
            System.out.println("Respuesta del servidor: " + respuestaServidor);

        } catch (IOException e) {
            System.err.println("Fallo en la conexión de red: " + e.getMessage());
        }
    }
}