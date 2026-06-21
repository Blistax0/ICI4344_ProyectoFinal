package com.googledrive.core.models;

import java.io.Serializable;

public class PeticionArchivo implements Serializable {
    private static final long serialVersionUID = 2L;

    public enum Operacion { SUBIR, DESCARGAR, EDITAR }

    private Operacion tipoOperacion;
    private String nombreArchivo;
    private long tamanoBytes;
    private String checksum;
    private int timestampLamport;
    private String nodeIdOrigen;

    public PeticionArchivo(Operacion tipoOperacion, String nombreArchivo, long tamanoBytes) {
        this.tipoOperacion = tipoOperacion;
        this.nombreArchivo = nombreArchivo;
        this.tamanoBytes = tamanoBytes;
    }

    public Operacion getTipoOperacion() { return tipoOperacion; }
    public String getNombreArchivo() { return nombreArchivo; }
    public long getTamanoBytes() { return tamanoBytes; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public int getTimestampLamport() { return timestampLamport; }
    public void setTimestampLamport(int timestampLamport) { this.timestampLamport = timestampLamport; }
    public String getNodeIdOrigen() { return nodeIdOrigen; }
    public void setNodeIdOrigen(String nodeIdOrigen) { this.nodeIdOrigen = nodeIdOrigen; }

    public boolean esEscritura() {
        return tipoOperacion == Operacion.SUBIR || tipoOperacion == Operacion.EDITAR;
    }
}
