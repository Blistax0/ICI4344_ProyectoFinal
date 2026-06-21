package com.googledrive.client.load;

import com.googledrive.core.models.PeticionArchivo;
import com.googledrive.core.utils.RelojLamport;
import com.googledrive.core.utils.Utils;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class GeneradorCargaDrive {
    private static final int NUM_HILOS = 50;
    private static final long DURACION_MS = 60_000;
    private static final long FALLA_INDUCIDA_MS = 30_000;
    private static final long REPORTE_INTERVALO_MS = 10_000;
    private static final String ARCHIVO_COMPARTIDO = "documento_carga.txt";
    private static final String DIR_LOGS = "logs";

    private static final LongAdder exitosas = new LongAdder();
    private static final LongAdder fallidas = new LongAdder();
    private static final List<Long> latencias = new CopyOnWriteArrayList<>();

    // Map nodo -> puerto cliente
    private static final Map<String, Integer> nodos = new HashMap<>();
    static {
        nodos.put("nodo1", 9000);
        nodos.put("nodo2", 9001);
        nodos.put("nodo3", 9002);
    }
    private static final Set<String> nodosActivos = ConcurrentHashMap.newKeySet();

    private static final RelojLamport relojCliente = new RelojLamport();
    private static final AtomicBoolean fallaInducida = new AtomicBoolean(false);
    private static final AtomicLong tiempoRecuperacionMs = new AtomicLong(-1);
    private static volatile long timestampFalla;

    public static void main(String[] args) throws Exception {
        String nodoFalla = args.length >= 1 ? args[0] : "nodo2"; // Fallar el nodo 2 por defecto

        System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "password");

        Files.createDirectories(Paths.get(DIR_LOGS));
        nodosActivos.addAll(nodos.keySet());

        System.out.println("=== Generador de Carga Drive ===");
        System.out.println("Hilos: " + NUM_HILOS + " | Duracion: " + (DURACION_MS / 1000) + "s");
        System.out.println("Falla inducida al nodo: " + nodoFalla + " en t=" + (FALLA_INDUCIDA_MS / 1000) + "s\n");

        long inicio = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(NUM_HILOS);
        ExecutorService pool = Executors.newFixedThreadPool(NUM_HILOS);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(() -> imprimirMetricasParciales(inicio), REPORTE_INTERVALO_MS, REPORTE_INTERVALO_MS, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> inducirFalla(nodoFalla), FALLA_INDUCIDA_MS, TimeUnit.MILLISECONDS);
        scheduler.execute(() -> medirRecuperacion(nodoFalla));

        for (int i = 0; i < NUM_HILOS; i++) {
            final int hiloId = i;
            pool.execute(() -> {
                try {
                    ejecutarCarga(hiloId, inicio);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(DURACION_MS + 15_000, TimeUnit.MILLISECONDS);
        pool.shutdownNow();
        scheduler.shutdownNow();

        long msgsCoordinacion = recolectarMensajesCoordinacion();
        imprimirMetricasFinales(inicio, msgsCoordinacion);
        guardarLogMetricas(inicio, nodoFalla, msgsCoordinacion);
    }

    private static void ejecutarCarga(int hiloId, long inicio) {
        SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        while (System.currentTimeMillis() - inicio < DURACION_MS) {
            String nodoId = getNodoAleatorioActivo();
            if (nodoId == null) break;
            
            int puerto = nodos.get(nodoId);

            int op = random.nextInt(100);
            long t0 = System.nanoTime();
            boolean ok = false;

            try {
                if (op < 40) {
                    ok = ejecutarEditar(ssf, puerto, hiloId);
                } else if (op < 70) {
                    ok = ejecutarDescargar(ssf, puerto);
                } else {
                    ok = ejecutarSubir(ssf, puerto, hiloId);
                }
            } catch (Exception e) {
                ok = false;
            }

            long latenciaMs = (System.nanoTime() - t0) / 1_000_000;
            latencias.add(latenciaMs);

            if (ok) exitosas.increment();
            else fallidas.increment();

            try {
                Thread.sleep(random.nextInt(50, 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static String getNodoAleatorioActivo() {
        List<String> activos = new ArrayList<>(nodosActivos);
        if (activos.isEmpty()) return null;
        return activos.get(ThreadLocalRandom.current().nextInt(activos.size()));
    }

    private static boolean ejecutarSubir(SSLSocketFactory ssf, int puerto, int hiloId) throws IOException {
        String nombre = "carga_h" + hiloId + "_" + System.nanoTime() + ".txt";
        byte[] bytes = ("Archivo de carga hilo " + hiloId).getBytes();
        int lamport = relojCliente.registrarEnvio();

        try (Socket socket = ssf.createSocket("127.0.0.1", puerto);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out);
             ObjectInputStream ois = new ObjectInputStream(in)) {

            PeticionArchivo peticion = new PeticionArchivo(PeticionArchivo.Operacion.SUBIR, nombre, bytes.length, lamport, "cliente-" + hiloId);
            peticion.setChecksum(Utils.calcularChecksum(bytes));
            oos.writeObject(peticion);
            oos.flush();
            out.write(bytes);
            out.flush();
            return ois.readUTF().startsWith("ÉXITO");
        }
    }

    private static boolean ejecutarDescargar(SSLSocketFactory ssf, int puerto) throws IOException {
        try (Socket socket = ssf.createSocket("127.0.0.1", puerto);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out)) {

            PeticionArchivo peticion = new PeticionArchivo(PeticionArchivo.Operacion.DESCARGAR, ARCHIVO_COMPARTIDO, 0);
            oos.writeObject(peticion);
            oos.flush();
            byte[] buf = new byte[8192];
            return in.read(buf) != -1;
        }
    }

    private static boolean ejecutarEditar(SSLSocketFactory ssf, int puerto, int hiloId) throws IOException {
        int lamport = relojCliente.registrarEnvio();
        byte[] bytes = ("Edicion hilo " + hiloId + " lamport=" + lamport + "\n").getBytes();

        try (Socket socket = ssf.createSocket("127.0.0.1", puerto);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out);
             ObjectInputStream ois = new ObjectInputStream(in)) {

            PeticionArchivo peticion = new PeticionArchivo(PeticionArchivo.Operacion.EDITAR, ARCHIVO_COMPARTIDO, bytes.length, lamport, "cliente-" + hiloId);
            peticion.setChecksum(Utils.calcularChecksum(bytes));
            oos.writeObject(peticion);
            oos.flush();
            out.write(bytes);
            out.flush();
            return ois.readUTF().startsWith("ÉXITO");
        }
    }

    private static void inducirFalla(String nodoFalla) {
        timestampFalla = System.currentTimeMillis();
        fallaInducida.set(true);
        nodosActivos.remove(nodoFalla);

        System.out.println("\n>>> FALLA INDUCIDA: deteniendo proceso del nodo " + nodoFalla + " <<<");
        String needle = "NodoAlmacenamiento " + nodoFalla;
        var targets = ProcessHandle.allProcesses()
                .filter(ph -> ph.info().commandLine().map(cmd -> cmd.contains(needle)).orElse(false))
                .toList();
        
        for (ProcessHandle ph : targets) {
            ph.destroyForcibly();
            System.out.println(">>> Proceso destruido exitosamente <<<");
        }
    }

    private static void medirRecuperacion(String nodoFalla) {
        while (!fallaInducida.get()) {
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }

        // Aquí como es Ricart-Agrawala sin coordinador central, la recuperación se considera
        // el tiempo hasta que la latencia vuelve a normalizarse o se logran X peticiones exitosas consecutivas
        // Como simplificación, esperamos a que las latencias posteriores a la falla existan.
        long tInicio = timestampFalla;
        int exitosasEsperadas = exitosas.intValue() + 10;
        
        while (System.currentTimeMillis() - tInicio < 20_000) {
            if (exitosas.intValue() >= exitosasEsperadas) {
                long tiempo = System.currentTimeMillis() - tInicio;
                tiempoRecuperacionMs.set(tiempo);
                System.out.println("\n>>> RECUPERACION DETECTADA: servicio continuo en " + tiempo + " ms <<<\n");
                return;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }
    }

    private static long recolectarMensajesCoordinacion() {
        long total = 0;
        SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        for (String nodoId : nodosActivos) {
            int puerto = nodos.get(nodoId);
            try (Socket socket = ssf.createSocket("127.0.0.1", puerto);
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(out);
                 ObjectInputStream ois = new ObjectInputStream(in)) {
                 
                PeticionArchivo req = new PeticionArchivo(PeticionArchivo.Operacion.METRICAS, "", 0);
                oos.writeObject(req);
                oos.flush();
                total += ois.readInt();
            } catch (Exception e) {}
        }
        return total;
    }

    private static void imprimirMetricasParciales(long inicio) {
        long elapsed = Math.max(1, (System.currentTimeMillis() - inicio) / 1000);
        double throughput = (double) exitosas.sum() / elapsed;
        double latProm = latencias.isEmpty() ? 0 : latencias.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf("[t=%ds] Throughput parcial: %.2f req/s | Exitosas: %d | Fallidas: %d | Lat prom: %.1f ms%n",
                elapsed, throughput, exitosas.sum(), fallidas.sum(), latProm);
    }

    private static void imprimirMetricasFinales(long inicio, long msgsCoordinacion) {
        long duracionSeg = Math.max(1, (System.currentTimeMillis() - inicio) / 1000);
        long totalExitosas = exitosas.sum();
        long totalFallidas = fallidas.sum();
        long total = totalExitosas + totalFallidas;

        long p95 = calcularP95(latencias);
        double promedio = latencias.isEmpty() ? 0 : latencias.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("\n========== METRICAS FINALES DE CARGA ==========");
        System.out.printf("Throughput:           %.2f req/s%n", (double) totalExitosas / duracionSeg);
        System.out.printf("Peticiones exitosas:  %d%n", totalExitosas);
        System.out.printf("Peticiones fallidas:  %d%n", totalFallidas);
        System.out.printf("Tasa de error:        %.2f%%%n", total == 0 ? 0 : (100.0 * totalFallidas / total));
        System.out.printf("Latencia promedio:    %.2f ms%n", promedio);
        System.out.printf("Latencia p95:         %d ms%n", p95);
        System.out.printf("Tiempo recuperacion:  %d ms%n", tiempoRecuperacionMs.get());
        System.out.printf("Msgs coordinacion:    %d%n", msgsCoordinacion);
        System.out.println("================================================");
    }

    private static long calcularP95(List<Long> datos) {
        if (datos.isEmpty()) return 0;
        List<Long> ordenadas = new ArrayList<>(datos);
        Collections.sort(ordenadas);
        return ordenadas.get((int) Math.min(ordenadas.size() - 1, Math.floor(0.95 * ordenadas.size())));
    }

    private static void guardarLogMetricas(long inicio, String nodoFalla, long msgsCoordinacion) {
        long duracionSeg = Math.max(1, (System.currentTimeMillis() - inicio) / 1000);
        long totalExitosas = exitosas.sum();
        long totalFallidas = fallidas.sum();
        
        String archivo = DIR_LOGS + "/carga_" + System.currentTimeMillis() + ".txt";
        try (PrintWriter pw = new PrintWriter(archivo)) {
            pw.println("GeneradorCargaDrive - corrida " + new Date());
            pw.println("nodo_falla_inducida=" + nodoFalla);
            pw.println("throughput_req_s=" + String.format("%.2f", (double) totalExitosas / duracionSeg));
            pw.println("latencia_promedio_ms=" + String.format("%.2f", latencias.stream().mapToLong(Long::longValue).average().orElse(0)));
            pw.println("latencia_p95_ms=" + calcularP95(latencias));
            pw.println("exitosas=" + totalExitosas);
            pw.println("fallidas=" + totalFallidas);
            pw.println("tiempo_recuperacion_ms=" + tiempoRecuperacionMs.get());
            pw.println("msgs_coordinacion=" + msgsCoordinacion);
        } catch (IOException e) {
            System.err.println("No se pudo guardar log de metricas: " + e.getMessage());
        }
        System.out.println("Log metricas: " + archivo);
    }
}
