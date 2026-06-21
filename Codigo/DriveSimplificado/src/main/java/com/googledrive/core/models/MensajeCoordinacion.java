package com.googledrive.core.models;

import java.io.Serializable;

public class MensajeCoordinacion implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Tipo { SOLICITUD_ACCESO, PERMISO_CONCEDIDO, HEARTBEAT, ELECCION, OK_ELECCION, COORDINADOR, REPLICAR_ARCHIVO, SOLICITUD_SYNC, RESPUESTA_SYNC }

    private Tipo tipo;
    private int tiempoLamport;
    private String idNodoOrigen;
    private String nombreArchivo;
    private byte[] contenidoArchivo;

    public MensajeCoordinacion(Tipo tipo, int tiempoLamport, String idNodoOrigen, String nombreArchivo) {
        this(tipo, tiempoLamport, idNodoOrigen, nombreArchivo, null);
    }

    public MensajeCoordinacion(Tipo tipo, int tiempoLamport, String idNodoOrigen, String nombreArchivo, byte[] contenidoArchivo) {
        this.tipo = tipo;
        this.tiempoLamport = tiempoLamport;
        this.idNodoOrigen = idNodoOrigen;
        this.nombreArchivo = nombreArchivo;
        this.contenidoArchivo = contenidoArchivo;
    }

    public Tipo getTipo() { return tipo; }
    public int getTiempoLamport() { return tiempoLamport; }
    public String getIdNodoOrigen() { return idNodoOrigen; }
    public String getNombreArchivo() { return nombreArchivo; }
    public byte[] getContenidoArchivo() { return contenidoArchivo; }
    
    @Override
    public String toString() {
        return "MensajeCoordinacion{" +
                "tipo=" + tipo +
                ", tiempoLamport=" + tiempoLamport +
                ", origen='" + idNodoOrigen + '\'' +
                ", archivo='" + nombreArchivo + '\'' +
                '}';
    }
}
