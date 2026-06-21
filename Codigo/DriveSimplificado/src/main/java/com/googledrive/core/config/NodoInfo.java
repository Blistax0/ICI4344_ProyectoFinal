package com.googledrive.core.config;

public class NodoInfo {
    private final String id;
    private final int idNumerico;
    private final String host;
    private final int puertoDatos;
    private final int puertoControl;
    private final int puertoMutex;

    public NodoInfo(String id, int idNumerico, String host, int puertoDatos, int puertoControl, int puertoMutex) {
        this.id = id;
        this.idNumerico = idNumerico;
        this.host = host;
        this.puertoDatos = puertoDatos;
        this.puertoControl = puertoControl;
        this.puertoMutex = puertoMutex;
    }

    public String getId() { return id; }
    public int getIdNumerico() { return idNumerico; }
    public String getHost() { return host; }
    public int getPuertoDatos() { return puertoDatos; }
    public int getPuertoControl() { return puertoControl; }
    public int getPuertoMutex() { return puertoMutex; }

    @Override
    public String toString() {
        return id + "@" + host + ":" + puertoDatos + "/" + puertoControl + "/" + puertoMutex;
    }
}
