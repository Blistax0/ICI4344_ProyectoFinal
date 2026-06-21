package com.googledrive.client.load;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConsolidadorLogs {
    private static final Pattern PATRON_LAMPORT = Pattern.compile("\\[Lamport:(\\d+)\\]");
    private static final List<String> EVENTOS_RELEVANTES = List.of(
            "NODO_CAIDO", "COORDINADOR_CAIDO", "ELECCION", "COORDINADOR", "NUEVO_COORDINADOR",
            "NODO_RECUPERADO", "REPLICACION", "OMISION_MENSAJE", "SYNC_",
            "RICART_ENTRA", "RICART_SALE", "RICART_DIFIERE", "RICART_TIMEOUT");

    public static String consolidar(String directorioSalida) throws IOException {
        Files.createDirectories(Paths.get(directorioSalida));
        String archivoSalida = directorioSalida + "/eventos_corrida_" + System.currentTimeMillis() + ".txt";

        List<LineaEvento> eventos = new ArrayList<>();
        File dir = new File(".");
        File[] logs = dir.listFiles((d, name) -> name.startsWith("eventos_nodo") && name.endsWith(".log"));

        if (logs != null) {
            for (File log : logs) {
                try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
                    String linea;
                    while ((linea = reader.readLine()) != null) {
                        if (esRelevante(linea)) {
                            eventos.add(new LineaEvento(extraerLamport(linea), linea));
                        }
                    }
                }
            }
        }

        eventos.sort(Comparator.comparingInt(LineaEvento::lamport).thenComparing(LineaEvento::linea));

        try (PrintWriter pw = new PrintWriter(archivoSalida)) {
            pw.println("=== Eventos consolidados con marcas Lamport ===");
            pw.println("Generado: " + new Date());
            pw.println("Total eventos: " + eventos.size());
            pw.println();
            for (LineaEvento ev : eventos) {
                pw.println(ev.linea());
            }
        }

        return archivoSalida;
    }

    private static boolean esRelevante(String linea) {
        return EVENTOS_RELEVANTES.stream().anyMatch(linea::contains);
    }

    private static int extraerLamport(String linea) {
        Matcher m = PATRON_LAMPORT.matcher(linea);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private record LineaEvento(int lamport, String linea) {}
}
