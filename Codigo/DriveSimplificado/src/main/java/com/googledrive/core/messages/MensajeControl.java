package com.googledrive.core.messages;

import java.io.Serializable;

public class MensajeControl implements Serializable {
    private static final long serialVersionUID = 2L;

    private TipoMensajeControl tipo;
    private String nodoOrigen;
    private int idNumericoOrigen;
    private String nodoDestino;
    private int timestampLamport;
    private MensajeReplicacion replicacion;
    private byte[] datosSync;
    private long valorMetrica;
    private String coordinadorId;

    public MensajeControl() {}

    public MensajeControl(TipoMensajeControl tipo, String nodoOrigen, int idNumericoOrigen) {
        this.tipo = tipo;
        this.nodoOrigen = nodoOrigen;
        this.idNumericoOrigen = idNumericoOrigen;
    }

    public TipoMensajeControl getTipo() { return tipo; }
    public void setTipo(TipoMensajeControl tipo) { this.tipo = tipo; }

    public String getNodoOrigen() { return nodoOrigen; }
    public void setNodoOrigen(String nodoOrigen) { this.nodoOrigen = nodoOrigen; }

    public int getIdNumericoOrigen() { return idNumericoOrigen; }
    public void setIdNumericoOrigen(int idNumericoOrigen) { this.idNumericoOrigen = idNumericoOrigen; }

    public String getNodoDestino() { return nodoDestino; }
    public void setNodoDestino(String nodoDestino) { this.nodoDestino = nodoDestino; }

    public int getTimestampLamport() { return timestampLamport; }
    public void setTimestampLamport(int timestampLamport) { this.timestampLamport = timestampLamport; }

    public MensajeReplicacion getReplicacion() { return replicacion; }
    public void setReplicacion(MensajeReplicacion replicacion) { this.replicacion = replicacion; }

    public byte[] getDatosSync() { return datosSync; }
    public void setDatosSync(byte[] datosSync) { this.datosSync = datosSync; }

    public long getValorMetrica() { return valorMetrica; }
    public void setValorMetrica(long valorMetrica) { this.valorMetrica = valorMetrica; }

    public String getCoordinadorId() { return coordinadorId; }
    public void setCoordinadorId(String coordinadorId) { this.coordinadorId = coordinadorId; }
}
