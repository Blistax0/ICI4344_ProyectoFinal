package com.googledrive.storage.server;

import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.messages.MensajeControl;
import com.googledrive.core.metrics.MetricasCoordinacion;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public class ClienteControlNodo {
    private static final int TIMEOUT_MS = 3000;

    private ClienteControlNodo() {}

    public static boolean enviarMensaje(NodoInfo destino, MensajeControl mensaje) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket(destino.getHost(), destino.getPuertoControl())) {
                socket.setSoTimeout(TIMEOUT_MS);
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                oos.writeObject(mensaje);
                oos.flush();
                MetricasCoordinacion.registrarMensaje();
                return true;
            }
        } catch (Exception e) {
            System.err.println("[Control] Fallo enviando a " + destino.getId() + ": " + e.getMessage());
            return false;
        }
    }

    public static MensajeControl enviarConRespuesta(NodoInfo destino, MensajeControl mensaje) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket(destino.getHost(), destino.getPuertoControl())) {
                socket.setSoTimeout(TIMEOUT_MS);
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
                oos.writeObject(mensaje);
                oos.flush();
                MetricasCoordinacion.registrarMensaje();
                return (MensajeControl) ois.readObject();
            }
        } catch (Exception e) {
            System.err.println("[Control] Fallo enviando con respuesta a " + destino.getId() + ": " + e.getMessage());
            return null;
        }
    }
}
