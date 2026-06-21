package com.googledrive.core.config;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConfiguracionRed {
    private final Map<String, NodoInfo> nodosPorId = new LinkedHashMap<>();
    private final List<NodoInfo> nodosOrdenados = new ArrayList<>();

    public static ConfiguracionRed cargar(String rutaArchivo) throws IOException {
        ConfiguracionRed config = new ConfiguracionRed();
        int contador = 1;
        try (BufferedReader reader = new BufferedReader(new FileReader(rutaArchivo))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#")) {
                    continue;
                }
                String[] partes = linea.split("=", 2);
                if (partes.length != 2) {
                    continue;
                }
                String id = partes[0].trim();
                String[] hostPuertos = partes[1].trim().split(":");
                if (hostPuertos.length < 2) {
                    throw new IOException("Formato invalido en nodos.txt: " + linea);
                }
                String host = hostPuertos[0];
                int puertoDatos = Integer.parseInt(hostPuertos[1]);
                int puertoControl = hostPuertos.length >= 3
                        ? Integer.parseInt(hostPuertos[2])
                        : puertoDatos + 100;
                int puertoMutex = hostPuertos.length >= 4
                        ? Integer.parseInt(hostPuertos[3])
                        : puertoControl + 100;
                NodoInfo nodo = new NodoInfo(id, contador++, host, puertoDatos, puertoControl, puertoMutex);
                config.nodosPorId.put(id, nodo);
                config.nodosOrdenados.add(nodo);
            }
        }
        if (config.nodosOrdenados.size() < 3) {
            throw new IOException("Se requieren al menos 3 nodos en " + rutaArchivo);
        }
        return config;
    }

    public NodoInfo getNodo(String id) {
        return nodosPorId.get(id);
    }

    public List<NodoInfo> getTodosLosNodos() {
        return Collections.unmodifiableList(nodosOrdenados);
    }

    public Optional<NodoInfo> getNodoConMayorId() {
        return nodosOrdenados.stream()
                .max((a, b) -> Integer.compare(a.getIdNumerico(), b.getIdNumerico()));
    }

    public NodoInfo getNodoAleatorio() {
        int idx = (int) (Math.random() * nodosOrdenados.size());
        return nodosOrdenados.get(idx);
    }
}
