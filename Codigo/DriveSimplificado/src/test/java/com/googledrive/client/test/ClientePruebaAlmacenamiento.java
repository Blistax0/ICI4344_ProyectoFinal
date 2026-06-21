package com.googledrive.client.test;

import com.googledrive.core.config.ConfiguracionRed;
import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.models.PeticionArchivo;
import com.googledrive.core.utils.Utils;
import java.io.*;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;

public class ClientePruebaAlmacenamiento {
    public static void main(String[] args) throws Exception {
        String configPath = args.length >= 1 ? args[0] : "nodos.txt";
        ConfiguracionRed config = ConfiguracionRed.cargar(configPath);
        NodoInfo nodo = config.getNodo("nodo1");

        System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "password");

        System.out.println("Iniciando prueba de red TLS (Cliente -> " + nodo.getId() + ")...");
        ejecutarPruebaSubida(nodo);
    }

    private static void ejecutarPruebaSubida(NodoInfo nodo) {
        SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (Socket socket = ssf.createSocket(nodo.getHost(), nodo.getPuertoDatos());
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out);
             ObjectInputStream ois = new ObjectInputStream(in)) {

            String contenidoSimulado = "Este es un flujo de bytes de prueba para el sistema distribuido.";
            byte[] bytesArchivo = contenidoSimulado.getBytes();
            String nombreArchivo = "prueba_conexion.txt";

            String md5 = Utils.calcularChecksum(bytesArchivo);
            PeticionArchivo peticion = new PeticionArchivo(
                PeticionArchivo.Operacion.SUBIR, nombreArchivo, bytesArchivo.length);
            peticion.setChecksum(md5);
            peticion.setTimestampLamport(1);
            peticion.setNodeIdOrigen("cliente-test");

            oos.writeObject(peticion);
            oos.flush();
            out.write(bytesArchivo);
            out.flush();

            String respuestaServidor = ois.readUTF();
            System.out.println("Respuesta del servidor: " + respuestaServidor);

        } catch (IOException e) {
            System.err.println("Fallo en la conexion segura de red: " + e.getMessage());
        }
    }
}
