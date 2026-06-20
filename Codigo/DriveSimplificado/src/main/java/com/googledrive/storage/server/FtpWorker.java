package com.googledrive.storage.server;

import java.io.*;
import java.net.Socket;
import com.googledrive.core.models.PeticionArchivo;

public class FtpWorker implements Runnable {
    private Socket socket;

    public FtpWorker(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                // deserializamos el objeto (esto es el marshalling del proyecto)
                ObjectInputStream ois = new ObjectInputStream(in);
                ObjectOutputStream oos = new ObjectOutputStream(out)) {
            // leemos el objeto con los datos
            PeticionArchivo peticion = (PeticionArchivo) ois.readObject();
            GestorArchivosLocal gestor = new GestorArchivosLocal();

            if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.SUBIR) {
                gestor.guardarArchivo(peticion.getNombreArchivo(), in, peticion.getTamanoBytes());
                oos.writeUTF("EXITO: Archivo subido correctamente.");
            } else if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.DESCARGAR) {
                gestor.enviarArchivo(peticion.getNombreArchivo(), out);
            } else if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.EDITAR) {
                // escribimos el texto nuevo de forma segura (con los locks)
                gestor.editarArchivo(peticion.getNombreArchivo(), in, peticion.getTamanoBytes());
                // avisamos que salio bien
                oos.writeUTF("EXITO: Archivo editado. Sincronizando estado...");
                oos.flush();
                // le devolvemos el archivo entero para que el cliente lo tenga actualizado y sincronizado
                gestor.enviarArchivo(peticion.getNombreArchivo(), out);
            }
            oos.flush();

        } catch (EOFException | java.net.SocketException e) {
            // por si se cae el internet o el cliente se desconecta de la nada
            System.err.println("Error de red: El cliente se desconectó abruptamente. " + e.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error de ejecución en FtpWorker: " + e.getMessage());
        } finally {
            try {
                if (!socket.isClosed())
                    socket.close();
            } catch (IOException e) {
                System.err.println("Fallo al cerrar socket: " + e.getMessage());
            }
        }
    }
}