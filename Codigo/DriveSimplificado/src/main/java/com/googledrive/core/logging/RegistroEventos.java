package com.googledrive.core.logging;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RegistroEventos {
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final PrintWriter writer;
    private final String nodoId;

    public RegistroEventos(String nodoId) throws IOException {
        this.nodoId = nodoId;
        this.writer = new PrintWriter(new FileWriter("eventos_" + nodoId + ".log", true), true);
    }

    public synchronized void registrar(int lamport, String evento) {
        String linea = String.format("[%s] [Lamport:%d] [Nodo:%s] %s",
                LocalDateTime.now().format(FORMATO), lamport, nodoId, evento);
        System.out.println(linea);
        writer.println(linea);
    }

    public synchronized void registrar(String evento) {
        registrar(0, evento);
    }

    public void cerrar() {
        writer.close();
    }
}
