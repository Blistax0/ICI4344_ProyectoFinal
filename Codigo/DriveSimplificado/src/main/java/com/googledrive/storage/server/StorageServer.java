package com.googledrive.storage.server;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import com.googledrive.core.config.ConfiguracionRed;

public class StorageServer {
    private static final int MAX_HILOS = 50;

    private final ContextoNodo contexto;
    private final int puertoDatos;
    private volatile boolean activo = true;
    private ServidorControl servidorControl;
    private Thread hiloControl;

    public StorageServer(ContextoNodo contexto) {
        this.contexto = contexto;
        this.puertoDatos = contexto.getNodoInfo().getPuertoDatos();
    }

    public void iniciar() {
        System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
        System.setProperty("javax.net.ssl.keyStorePassword", "password");
        System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "password");

        contexto.iniciarServiciosDistribuidos();

        int puertoControl = contexto.getNodoInfo().getPuertoControl();
        servidorControl = new ServidorControl(contexto, puertoControl);
        hiloControl = new Thread(servidorControl, "ServidorControl-" + contexto.getNodoId());
        hiloControl.start();

        ExecutorService poolHilos = Executors.newFixedThreadPool(MAX_HILOS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            activo = false;
            servidorControl.detener();
            contexto.cerrar();
            poolHilos.shutdownNow();
        }));

        try {
            SSLServerSocketFactory ssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(puertoDatos)) {
                System.out.println("Nodo " + contexto.getNodoId() + " (TLS) datos=" + puertoDatos
                        + " control=" + puertoControl);

                while (activo) {
                    Socket socketCliente = serverSocket.accept();
                    socketCliente.setSoTimeout(30000);
                    System.out.println("Nueva conexion segura desde: "
                            + socketCliente.getInetAddress().getHostAddress());
                    poolHilos.execute(new FtpWorker(socketCliente, contexto));
                }
            }
        } catch (IOException e) {
            System.err.println("Fallo critico en el servidor de almacenamiento: " + e.getMessage());
        } finally {
            poolHilos.shutdown();
            contexto.cerrar();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java StorageServer <nodeId> [rutaNodos.txt]");
            System.err.println("Ejemplo: java StorageServer nodo1 ../../nodos.txt");
            System.exit(1);
        }

        String nodeId = args[0];
        String configPath = args.length >= 2 ? args[1] : "nodos.txt";

        try {
            ConfiguracionRed config = ConfiguracionRed.cargar(configPath);
            ContextoNodo contexto = new ContextoNodo(nodeId, config);
            new StorageServer(contexto).iniciar();
        } catch (Exception e) {
            System.err.println("No se pudo iniciar el nodo: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
