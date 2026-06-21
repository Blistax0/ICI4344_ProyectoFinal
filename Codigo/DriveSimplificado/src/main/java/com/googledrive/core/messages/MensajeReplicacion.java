package com.googledrive.core.messages;

import com.googledrive.core.models.PeticionArchivo;
import java.io.Serializable;

public class MensajeReplicacion implements Serializable {
    private static final long serialVersionUID = 2L;

    private PeticionArchivo.Operacion operacion;
    private String nombreArchivo;
    private byte[] contenido;
    private long tamanoBytes;
    private int timestampLamport;
    private String nodeIdOrigen;
    private String checksum;

    public MensajeReplicacion() {}

    public MensajeReplicacion(PeticionArchivo.Operacion operacion, String nombreArchivo,
                              byte[] contenido, long tamanoBytes, int timestampLamport,
                              String nodeIdOrigen, String checksum) {
        this.operacion = operacion;
        this.nombreArchivo = nombreArchivo;
        this.contenido = contenido;
        this.tamanoBytes = tamanoBytes;
        this.timestampLamport = timestampLamport;
        this.nodeIdOrigen = nodeIdOrigen;
        this.checksum = checksum;
    }

    public PeticionArchivo.Operacion getOperacion() { return operacion; }
    public String getNombreArchivo() { return nombreArchivo; }
    public byte[] getContenido() { return contenido; }
    public long getTamanoBytes() { return tamanoBytes; }
    public int getTimestampLamport() { return timestampLamport; }
    public String getNodeIdOrigen() { return nodeIdOrigen; }
    public String getChecksum() { return checksum; }
}
