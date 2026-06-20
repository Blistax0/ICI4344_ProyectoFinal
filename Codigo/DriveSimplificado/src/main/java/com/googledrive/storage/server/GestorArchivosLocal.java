package com.googledrive.storage.server;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class GestorArchivosLocal {
    private static final String DIRECTORIO_BASE = "./storage_data/";
    private static final int BUFFER_SIZE = 8192;
    
    // mapa para guardar los locks de cada archivo y que no se pisen al escribir
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

    public GestorArchivosLocal() {
        File dir = new File(DIRECTORIO_BASE);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private ReentrantReadWriteLock obtenerLock(String nombreArchivo) {
        return fileLocks.computeIfAbsent(nombreArchivo, k -> new ReentrantReadWriteLock());
    }

    public void guardarArchivo(String nombreArchivo, InputStream redIn, long tamano) throws IOException {
        ReentrantReadWriteLock lock = obtenerLock(nombreArchivo);
        lock.writeLock().lock(); // bloqueamos para que nadie mas escriba aca (region critica)
        
        try (FileOutputStream fos = new FileOutputStream(DIRECTORIO_BASE + nombreArchivo);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesLeidos;
            long totalLeido = 0;
            
            // pasamos los bytes directo
            while (totalLeido < tamano && (bytesLeidos = redIn.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesLeidos);
                totalLeido += bytesLeidos;
            }
            bos.flush();
        } finally {
            lock.writeLock().unlock(); // soltamos el lock
        }
    }

    public void enviarArchivo(String nombreArchivo, OutputStream redOut) throws IOException {
        ReentrantReadWriteLock lock = obtenerLock(nombreArchivo);
        lock.readLock().lock(); // lock de lectura para que varios puedan leer a la vez
        
        File archivo = new File(DIRECTORIO_BASE + nombreArchivo);
        if (!archivo.exists()) {
            throw new FileNotFoundException("El archivo solicitado no existe en este nodo.");
        }

        try (FileInputStream fis = new FileInputStream(archivo);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesLeidos;
            
            while ((bytesLeidos = bis.read(buffer)) != -1) {
                redOut.write(buffer, 0, bytesLeidos);
            }
            redOut.flush();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void editarArchivo(String nombreArchivo, InputStream redIn, long tamano) throws IOException {
        ReentrantReadWriteLock lock = obtenerLock(nombreArchivo);
        lock.writeLock().lock(); // bloqueamos para escribir (region critica)
        
        // le ponemos true para que escriba al final del archivo y no borre lo que ya estaba
        try (FileOutputStream fos = new FileOutputStream(DIRECTORIO_BASE + nombreArchivo, true);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            // le mandamos la hora del server apenas entra al lock
            // asi queda guardado el orden real en el que entraron las ediciones
            String timestamp = "[" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] ";
            bos.write(timestamp.getBytes());

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesLeidos;
            long totalLeido = 0;
            
            while (totalLeido < tamano && (bytesLeidos = redIn.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesLeidos);
                totalLeido += bytesLeidos;
            }
            // un enter para que no quede todo en la misma linea
            bos.write("\n".getBytes());
            bos.flush();
        } finally {
            lock.writeLock().unlock(); // soltamos el lock
        }
    }
}