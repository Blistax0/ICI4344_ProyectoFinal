package com.googledrive.storage.server;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class GestorArchivosLocal {
    private final String directorioBase;
    private static final int BUFFER_SIZE = 8192;

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

    public GestorArchivosLocal(String nodeId) {
        this.directorioBase = "./storage_data_" + nodeId + "/";
        File dir = new File(directorioBase);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private ReentrantReadWriteLock obtenerLock(String nombreArchivo) {
        return fileLocks.computeIfAbsent(directorioBase + nombreArchivo, k -> new ReentrantReadWriteLock());
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String guardarArchivo(String nombreArchivo, InputStream redIn, long tamano) throws IOException {
        ReentrantReadWriteLock lock = obtenerLock(nombreArchivo);
        lock.writeLock().lock();

        try (FileOutputStream fos = new FileOutputStream(directorioBase + nombreArchivo);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesLeidos;
            long totalLeido = 0;

            while (totalLeido < tamano && (bytesLeidos = redIn.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesLeidos);
                md.update(buffer, 0, bytesLeidos);
                totalLeido += bytesLeidos;
            }
            bos.flush();
            return bytesToHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Fallo en guardado de archivo", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String enviarArchivo(String nombreArchivo, OutputStream redOut) throws IOException {
        ReentrantReadWriteLock lock = obtenerLock(nombreArchivo);
        lock.readLock().lock();

        File archivo = new File(directorioBase + nombreArchivo);
        if (!archivo.exists()) {
            lock.readLock().unlock();
            throw new FileNotFoundException("El archivo solicitado no existe en este nodo.");
        }

        try (FileInputStream fis = new FileInputStream(archivo);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesLeidos;

            while ((bytesLeidos = bis.read(buffer)) != -1) {
                redOut.write(buffer, 0, bytesLeidos);
                md.update(buffer, 0, bytesLeidos);
            }
            redOut.flush();
            return bytesToHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Fallo al enviar archivo", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String editarArchivo(String nombreArchivo, InputStream redIn, long tamano,
                                int lamport, String nodeId) throws IOException {
        ReentrantReadWriteLock lock = obtenerLock(nombreArchivo);
        lock.writeLock().lock();

        try (FileOutputStream fos = new FileOutputStream(directorioBase + nombreArchivo, true);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            MessageDigest md = MessageDigest.getInstance("MD5");
            String timestamp = String.format("[Nodo: %s | Lamport: %d] ", nodeId, lamport);
            bos.write(timestamp.getBytes());

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesLeidos;
            long totalLeido = 0;

            while (totalLeido < tamano && (bytesLeidos = redIn.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesLeidos);
                md.update(buffer, 0, bytesLeidos);
                totalLeido += bytesLeidos;
            }
            bos.write("\n".getBytes());
            bos.flush();

            return bytesToHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Fallo en edicion de archivo", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> listarArchivos() {
        List<String> archivos = new ArrayList<>();
        File dir = new File(directorioBase);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    archivos.add(f.getName());
                }
            }
        }
        return archivos;
    }

    public byte[] leerArchivoCompleto(String nombreArchivo) throws IOException {
        File archivo = new File(directorioBase + nombreArchivo);
        return Files.readAllBytes(archivo.toPath());
    }
}
