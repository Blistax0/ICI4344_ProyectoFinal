package com.googledrive.storage.server;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StorageServer {
    private static final int PUERTO = 9000;
    private static final int MAX_HILOS = 50; // Pool de hilos para alta concurrencia
    private boolean activo = true;

    public void iniciar() {
        ExecutorService poolHilos = Executors.newFixedThreadPool(MAX_HILOS);
        
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            System.out.println("Nodo de Almacenamiento iniciado en puerto " + PUERTO);
            
            while (activo) {
                // Acepta conexiones entrantes de clientes
                Socket socketCliente = serverSocket.accept();
                System.out.println("Nueva conexión desde: " + socketCliente.getInetAddress().getHostAddress());
                
                // Asigna la conexión a un hilo independiente
                poolHilos.execute(new FtpWorker(socketCliente));
            }
        } catch (IOException e) {
            System.err.println("Fallo crítico en el servidor de almacenamiento: " + e.getMessage());
        } finally {
            poolHilos.shutdown();
        }
    }

    public static void main(String[] args) {
        new StorageServer().iniciar();
    }
}