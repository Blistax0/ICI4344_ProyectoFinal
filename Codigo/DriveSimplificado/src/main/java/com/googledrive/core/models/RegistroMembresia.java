package com.googledrive.core.models;

import java.util.HashMap;
import java.util.Map;

public class RegistroMembresia {
    
    // Mapeo de nodos a puertos para comunicación interna (algoritmo distribuido)
    private static final Map<String, Integer> puertosCoordinacion = new HashMap<>();
    
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
}
