package com.googledrive.core.models;

import java.io.Serializable;

public class PeticionArchivo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum Operacion { SUBIR, DESCARGAR, EDITAR }
    
    private Operacion tipoOperacion;
    private String nombreArchivo;
    private long tamanoBytes;
    private String checksum; // Para validación de integridad

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
}