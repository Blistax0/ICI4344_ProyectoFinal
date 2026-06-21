package com.googledrive.core.models;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RegistroMembresia {
    
    // Mapeo de nodos a puertos para comunicación interna (algoritmo distribuido)
    private static final Map<String, Integer> puertosCoordinacion = new HashMap<>();
    
    // Conjunto de nodos que han sido detectados como caídos
    private static final Set<String> nodosCaidos = ConcurrentHashMap.newKeySet();
    
    static {
        puertosCoordinacion.put("nodo1", 9100);
        puertosCoordinacion.put("nodo2", 9101);
        puertosCoordinacion.put("nodo3", 9102);
    }
    
    public static Map<String, Integer> obtenerNodos() {
        return puertosCoordinacion;
    }
    
    public static int obtenerPuerto(String idNodo) {
        return puertosCoordinacion.getOrDefault(idNodo, -1);
    }
    
    public static String obtenerHost() {
        return "127.0.0.1";
    }

    public static void marcarCaido(String idNodo) {
        nodosCaidos.add(idNodo);
    }

    public static boolean estaCaido(String idNodo) {
        return nodosCaidos.contains(idNodo);
    }
    
    public static int cantidadNodosActivos() {
        return puertosCoordinacion.size() - nodosCaidos.size();
    }
}
