package com.googledrive.client.load;

import com.googledrive.core.clock.RelojLamport;
import com.googledrive.core.config.ConfiguracionRed;
import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.messages.MensajeControl;
import com.googledrive.core.messages.TipoMensajeControl;
import com.googledrive.core.models.PeticionArchivo;
import com.googledrive.core.utils.Utils;
import com.googledrive.storage.server.ClienteControlNodo;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
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
    private static final String ARCHIVO_COMPARTIDO = "documento_compartido.txt";
    private static final String DIR_LOGS = "logs";
    private static final String DIR_PIDS = ".pids";

    private static final LongAdder exitosas = new LongAdder();
    private static final LongAdder fallidas = new LongAdder();
    private static final LongAdder exitosasPreFalla = new LongAdder();
    private static final LongAdder fallidasPreFalla = new LongAdder();
    private static final LongAdder exitosasPostFalla = new LongAdder();
    private static final LongAdder fallidasPostFalla = new LongAdder();
    private static final List<Long> latencias = new CopyOnWriteArrayList<>();
    private static final List<Long> latenciasPreFalla = new CopyOnWriteArrayList<>();
    private static final List<Long> latenciasPostFalla = new CopyOnWriteArrayList<>();

    private static ConfiguracionRed config;
    private static final RelojLamport relojCliente = new RelojLamport();
    private static final Set<String> nodosActivos = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean fallaInducida = new AtomicBoolean(false);
    private static final AtomicLong tiempoRecuperacionMs = new AtomicLong(-1);
    private static volatile long timestampFalla;

    public static void main(String[] args) throws Exception {
        String configPath = args.length >= 1 ? args[0] : "nodos.txt";
        String nodoFalla = args.length >= 2 ? args[1] : "nodo3";

        System.setProperty("javax.net.ssl.trustStore", "keystore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", "password");

        Files.createDirectories(Paths.get(DIR_LOGS));

        config = ConfiguracionRed.cargar(configPath);
        config.getTodosLosNodos().forEach(n -> nodosActivos.add(n.getId()));

        System.out.println("=== Generador de Carga Drive ===");
        System.out.println("Hilos: " + NUM_HILOS + " | Duracion: " + (DURACION_MS / 1000) + "s");
        System.out.println("Falla inducida al nodo: " + nodoFalla + " en t=" + (FALLA_INDUCIDA_MS / 1000) + "s\n");

        long inicio = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(NUM_HILOS);
        ExecutorService pool = Executors.newFixedThreadPool(NUM_HILOS);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(() -> imprimirMetricasParciales(inicio), REPORTE_INTERVALO_MS,
                REPORTE_INTERVALO_MS, TimeUnit.MILLISECONDS);
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

        String eventosConsolidados = ConsolidadorLogs.consolidar(DIR_LOGS);
        System.out.println("Log consolidado: " + eventosConsolidados);
    }

    private static void ejecutarCarga(int hiloId, long inicio) {
        SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        while (System.currentTimeMillis() - inicio < DURACION_MS) {
            NodoInfo nodo = getNodoAleatorioActivo();
            if (nodo == null) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            int op = random.nextInt(100);
            long t0 = System.nanoTime();
            boolean ok = false;
            boolean postFalla = fallaInducida.get();

            try {
                if (op < 40) {
                    ok = ejecutarEditar(ssf, nodo, hiloId);
                } else if (op < 70) {
                    ok = ejecutarDescargar(ssf, nodo);
                } else {
                    ok = ejecutarSubir(ssf, nodo, hiloId);
                }
            } catch (Exception e) {
                ok = false;
            }

            long latenciaMs = (System.nanoTime() - t0) / 1_000_000;
            latencias.add(latenciaMs);
            if (postFalla) {
                latenciasPostFalla.add(latenciaMs);
            } else {
                latenciasPreFalla.add(latenciaMs);
            }

            if (ok) {
                exitosas.increment();
                if (postFalla) exitosasPostFalla.increment();
                else exitosasPreFalla.increment();
            } else {
                fallidas.increment();
                if (postFalla) fallidasPostFalla.increment();
                else fallidasPreFalla.increment();
            }

            try {
                Thread.sleep(random.nextInt(50, 200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static NodoInfo getNodoAleatorioActivo() {
        List<NodoInfo> activos = config.getTodosLosNodos().stream()
                .filter(n -> nodosActivos.contains(n.getId()))
                .toList();
        if (activos.isEmpty()) {
            return null;
        }
        return activos.get(ThreadLocalRandom.current().nextInt(activos.size()));
    }

    private static boolean ejecutarSubir(SSLSocketFactory ssf, NodoInfo nodo, int hiloId) throws IOException {
        String nombre = "carga_h" + hiloId + "_" + System.nanoTime() + ".txt";
        byte[] bytes = ("Archivo de carga hilo " + hiloId).getBytes();
        int lamport = relojCliente.incrementar();

        try (var socket = ssf.createSocket(nodo.getHost(), nodo.getPuertoDatos());
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out);
             ObjectInputStream ois = new ObjectInputStream(in)) {

            PeticionArchivo peticion = new PeticionArchivo(PeticionArchivo.Operacion.SUBIR, nombre, bytes.length);
            peticion.setChecksum(Utils.calcularChecksum(bytes));
            peticion.setTimestampLamport(lamport);
            peticion.setNodeIdOrigen("cliente-" + hiloId);
            oos.writeObject(peticion);
            oos.flush();
            out.write(bytes);
            out.flush();
            return ois.readUTF().startsWith("EXITO");
        }
    }

    private static boolean ejecutarDescargar(SSLSocketFactory ssf, NodoInfo nodo) throws IOException {
        try (var socket = ssf.createSocket(nodo.getHost(), nodo.getPuertoDatos());
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out)) {

            PeticionArchivo peticion = new PeticionArchivo(
                    PeticionArchivo.Operacion.DESCARGAR, ARCHIVO_COMPARTIDO, 0);
            oos.writeObject(peticion);
            oos.flush();
            byte[] buf = new byte[8192];
            return in.read(buf) != -1;
        }
    }

    private static boolean ejecutarEditar(SSLSocketFactory ssf, NodoInfo nodo, int hiloId) throws IOException {
        int lamport = relojCliente.incrementar();
        byte[] bytes = ("Edicion hilo " + hiloId + " lamport=" + lamport + "\n").getBytes();

        try (var socket = ssf.createSocket(nodo.getHost(), nodo.getPuertoDatos());
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out);
             ObjectInputStream ois = new ObjectInputStream(in)) {

            PeticionArchivo peticion = new PeticionArchivo(
                    PeticionArchivo.Operacion.EDITAR, ARCHIVO_COMPARTIDO, bytes.length);
            peticion.setChecksum(Utils.calcularChecksum(bytes));
            peticion.setTimestampLamport(lamport);
            peticion.setNodeIdOrigen("cliente-" + hiloId);
            oos.writeObject(peticion);
            oos.flush();
            out.write(bytes);
            out.flush();
            return ois.readUTF().startsWith("EXITO");
        }
    }

    private static void inducirFalla(String nodoFalla) {
        timestampFalla = System.currentTimeMillis();
        fallaInducida.set(true);
        nodosActivos.remove(nodoFalla);

        System.out.println("\n>>> FALLA INDUCIDA: deteniendo nodo " + nodoFalla + " <<<");

        File pidFile = new File(DIR_PIDS, nodoFalla + ".pid");
        if (pidFile.exists()) {
            try {
                String pid = Files.readString(pidFile.toPath()).trim();
                new ProcessBuilder("kill", "-9", pid).start().waitFor();
                System.out.println(">>> Nodo detenido via PID " + pid + " <<<");
                return;
            } catch (Exception e) {
                System.err.println("Fallo kill por PID: " + e.getMessage());
            }
        }

        try {
            new ProcessBuilder("pkill", "-9", "-f", "StorageServer " + nodoFalla).start().waitFor();
            System.out.println(">>> Nodo detenido via pkill <<<");
        } catch (Exception e) {
            System.err.println("No se pudo detener " + nodoFalla + ": " + e.getMessage());
        }
    }

    private static void medirRecuperacion(String nodoFalla) {
        while (!fallaInducida.get()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        long tInicio = timestampFalla;
        while (System.currentTimeMillis() - tInicio < 30_000) {
            String coordinador = consultarCoordinadorConsensuado();
            if (coordinador != null && !coordinador.equals(nodoFalla)) {
                long tiempo = System.currentTimeMillis() - tInicio;
                tiempoRecuperacionMs.set(tiempo);
                System.out.println("\n>>> RECUPERACION: nuevo coordinador=" + coordinador
                        + " en " + tiempo + " ms <<<\n");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("\n>>> ADVERTENCIA: no se detecto recuperacion en 30s <<<\n");
    }

    private static String consultarCoordinadorConsensuado() {
        Map<String, Integer> votos = new HashMap<>();
        MensajeControl req = new MensajeControl(TipoMensajeControl.COORDINADOR_QUERY, "cliente", 0);

        for (NodoInfo nodo : config.getTodosLosNodos()) {
            if (!nodosActivos.contains(nodo.getId())) {
                continue;
            }
            try {
                MensajeControl resp = ClienteControlNodo.enviarConRespuesta(nodo, req);
                if (resp != null && resp.getCoordinadorId() != null) {
                    votos.merge(resp.getCoordinadorId(), 1, Integer::sum);
                }
            } catch (Exception ignored) {}
        }

        return votos.entrySet().stream()
                .filter(e -> e.getValue() >= 1)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static long recolectarMensajesCoordinacion() {
        long total = 0;
        MensajeControl req = new MensajeControl(TipoMensajeControl.METRICS_REQUEST, "cliente", 0);
        for (NodoInfo nodo : config.getTodosLosNodos()) {
            if (!nodosActivos.contains(nodo.getId())) {
                continue;
            }
            try {
                MensajeControl resp = ClienteControlNodo.enviarConRespuesta(nodo, req);
                if (resp != null) {
                    total += resp.getValorMetrica();
                }
            } catch (Exception ignored) {}
        }
        return total;
    }

    private static void imprimirMetricasParciales(long inicio) {
        long elapsed = Math.max(1, (System.currentTimeMillis() - inicio) / 1000);
        double throughput = (double) exitosas.sum() / elapsed;
        double latProm = latencias.isEmpty() ? 0 :
                latencias.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf("[t=%ds] Throughput parcial: %.2f req/s | Exitosas: %d | Fallidas: %d | Lat prom: %.1f ms%n",
                elapsed, throughput, exitosas.sum(), fallidas.sum(), latProm);
    }

    private static void imprimirMetricasFinales(long inicio, long msgsCoordinacion) {
        long duracionSeg = Math.max(1, (System.currentTimeMillis() - inicio) / 1000);
        long totalExitosas = exitosas.sum();
        long totalFallidas = fallidas.sum();
        long total = totalExitosas + totalFallidas;

        long p95 = calcularP95(latencias);
        long p95Pre = calcularP95(latenciasPreFalla);
        long p95Post = calcularP95(latenciasPostFalla);
        double promedio = latencias.isEmpty() ? 0 :
                latencias.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("\n========== METRICAS FINALES DE CARGA ==========");
        System.out.printf("Throughput:           %.2f req/s%n", (double) totalExitosas / duracionSeg);
        System.out.printf("Peticiones exitosas:  %d%n", totalExitosas);
        System.out.printf("Peticiones fallidas:  %d%n", totalFallidas);
        System.out.printf("Tasa de error:        %.2f%%%n", total == 0 ? 0 : (100.0 * totalFallidas / total));
        System.out.printf("Latencia promedio:    %.2f ms%n", promedio);
        System.out.printf("Latencia p95:         %d ms%n", p95);
        System.out.printf("--- Pre-falla (0-30s) ---%n");
        System.out.printf("  Exitosas: %d | Fallidas: %d | p95: %d ms%n",
                exitosasPreFalla.sum(), fallidasPreFalla.sum(), p95Pre);
        System.out.printf("--- Post-falla (30-60s) ---%n");
        System.out.printf("  Exitosas: %d | Fallidas: %d | p95: %d ms%n",
                exitosasPostFalla.sum(), fallidasPostFalla.sum(), p95Post);
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
        long total = totalExitosas + totalFallidas;
        double promedio = latencias.isEmpty() ? 0 :
                latencias.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = calcularP95(latencias);

        String archivo = DIR_LOGS + "/carga_" + System.currentTimeMillis() + ".txt";
        try (PrintWriter pw = new PrintWriter(archivo)) {
            pw.println("GeneradorCargaDrive - corrida " + new Date());
            pw.println("nodo_falla_inducida=" + nodoFalla);
            pw.println("duracion_seg=" + duracionSeg);
            pw.println("throughput_req_s=" + String.format("%.2f", (double) totalExitosas / duracionSeg));
            pw.println("latencia_promedio_ms=" + String.format("%.2f", promedio));
            pw.println("latencia_p95_ms=" + p95);
            pw.println("exitosas=" + totalExitosas);
            pw.println("fallidas=" + totalFallidas);
            pw.println("tasa_error_pct=" + String.format("%.2f", total == 0 ? 0 : (100.0 * totalFallidas / total)));
            pw.println("exitosas_pre_falla=" + exitosasPreFalla.sum());
            pw.println("fallidas_pre_falla=" + fallidasPreFalla.sum());
            pw.println("latencia_p95_pre_falla_ms=" + calcularP95(latenciasPreFalla));
            pw.println("exitosas_post_falla=" + exitosasPostFalla.sum());
            pw.println("fallidas_post_falla=" + fallidasPostFalla.sum());
            pw.println("latencia_p95_post_falla_ms=" + calcularP95(latenciasPostFalla));
            pw.println("tiempo_recuperacion_ms=" + tiempoRecuperacionMs.get());
            pw.println("msgs_coordinacion=" + msgsCoordinacion);
            pw.println("muestras_latencia=" + latencias.size());
        } catch (IOException e) {
            System.err.println("No se pudo guardar log de metricas: " + e.getMessage());
        }
        System.out.println("Log metricas: " + archivo);
    }
}
