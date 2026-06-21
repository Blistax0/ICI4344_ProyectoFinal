package com.googledrive.storage.server;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import com.googledrive.core.utils.RelojLamport;

public class NodoAlmacenamiento {
    
    private int puertoClientes;
    private String idNodoLocal;
    
    private ServicioExclusionMutua servicioCoordinacion;
    private RelojLamport relojLogico;
    
    private static final int HILOS_MAXIMOS = 50;
    private boolean servidorEncendido = true;

    public NodoAlmacenamiento(String idNodoLocal, int puertoClientes) {
        this.idNodoLocal = idNodoLocal;
        this.puertoClientes = puertoClientes;
        this.relojLogico = new RelojLamport();
        this.servicioCoordinacion = new ServicioExclusionMutua(idNodoLocal, relojLogico);
    }

    public void iniciarServicio() {
        System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
        System.setProperty("javax.net.ssl.keyStorePassword", "password");

        // Levantar servicio para coordinación entre nodos
        servicioCoordinacion.iniciarEscuchaCoordinacion();
        servicioCoordinacion.iniciarMonitor();

        // Iniciar elección de coordinador (Bully) al levantar el nodo
        System.out.println("-> Iniciando elección de coordinador inicial...");
        servicioCoordinacion.getAlgoritmoBully().iniciarEleccion();
        
        // Esperamos un momento para que se defina el coordinador
        try { Thread.sleep(4000); } catch (InterruptedException e) {}

        String coord = servicioCoordinacion.getAlgoritmoBully().getIdCoordinadorActual();
        if (coord != null && !coord.equals(idNodoLocal)) {
            System.out.println("-> Solicitando sincronización de archivos al coordinador " + coord);
            int tiempo = relojLogico.registrarEnvio();
            servicioCoordinacion.enviarMensajeA(coord, new com.googledrive.core.models.MensajeCoordinacion(
                com.googledrive.core.models.MensajeCoordinacion.Tipo.SOLICITUD_SYNC, tiempo, idNodoLocal, ""));
        }

        ExecutorService piscinaDeHilos = Executors.newFixedThreadPool(HILOS_MAXIMOS);
        
        try {
            SSLServerSocketFactory fabricaCriptografica = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            try (SSLServerSocket socketSeguro = (SSLServerSocket) fabricaCriptografica.createServerSocket(puertoClientes)) {
                System.out.println("-> " + idNodoLocal.toUpperCase() + " iniciado (Puerto clientes: " + puertoClientes + ")");
                
                while (servidorEncendido) {
                    Socket clienteExterno = socketSeguro.accept();
                    clienteExterno.setSoTimeout(30000); 
                    
                    piscinaDeHilos.execute(new WorkerCliente(clienteExterno, servicioCoordinacion, relojLogico));
                }
            }
        } catch (IOException e) {
            System.err.println("Error iniciando NodoAlmacenamiento: " + e.getMessage());
        } finally {
            piscinaDeHilos.shutdown();
        }
    }

    public static void main(String[] args) {
        String idNodo = "nodo1";
        int puerto = 9000;

        if (args.length >= 2) {
            idNodo = args[0];
            puerto = Integer.parseInt(args[1]);
        } else {
            System.out.println("Usando valores por defecto (nodo1 / 9000)");
        }

        new NodoAlmacenamiento(idNodo, puerto).iniciarServicio();
    }
}