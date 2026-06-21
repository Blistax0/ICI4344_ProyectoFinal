package com.googledrive.storage.server;

import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.models.PeticionArchivo;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public class ClienteDatosNodo {
    private static final int TIMEOUT_MS = 30000;

    private ClienteDatosNodo() {}

    public static boolean proxyPeticion(NodoInfo coordinador, PeticionArchivo peticion, byte[] payload,
                                        ObjectOutputStream oosCliente, OutputStream outCliente) {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket(coordinador.getHost(), coordinador.getPuertoDatos())) {
                socket.setSoTimeout(TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                ObjectOutputStream oos = new ObjectOutputStream(out);
                ObjectInputStream ois = new ObjectInputStream(in);

                oos.writeObject(peticion);
                oos.flush();
                out.write(payload);
                out.flush();

                String respuesta = ois.readUTF();
                oosCliente.writeUTF(respuesta);
                oosCliente.flush();

                if (peticion.getTipoOperacion() == PeticionArchivo.Operacion.EDITAR && respuesta.startsWith("EXITO")) {
                    byte[] buf = new byte[8192];
                    int leidos;
                    while ((leidos = in.read(buf)) != -1) {
                        outCliente.write(buf, 0, leidos);
                    }
                    outCliente.flush();
                }
                return respuesta.startsWith("EXITO");
            }
        } catch (Exception e) {
            System.err.println("[Datos] Fallo proxy al coordinador: " + e.getMessage());
            return false;
        }
    }
}
