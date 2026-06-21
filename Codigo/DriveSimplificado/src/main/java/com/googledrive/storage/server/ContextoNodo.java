package com.googledrive.storage.server;

import com.googledrive.coordination.BullyAlgorithm;
import com.googledrive.coordination.ServicioExclusionMutua;
import com.googledrive.core.clock.RelojLamport;
import com.googledrive.core.config.ConfiguracionRed;
import com.googledrive.core.config.NodoInfo;
import com.googledrive.core.logging.RegistroEventos;
import com.googledrive.fault.MonitorFallos;
import com.googledrive.fault.ServicioHeartbeats;

public class ContextoNodo {
    private final String nodoId;
    private final NodoInfo nodoInfo;
    private final ConfiguracionRed configuracion;
    private final RelojLamport relojLamport;
    private final GestorArchivosLocal gestorArchivos;
    private final RegistroEventos registroEventos;
    private final BullyAlgorithm bully;
    private final ServicioReplicacion servicioReplicacion;
    private final ServicioHeartbeats servicioHeartbeats;
    private final MonitorFallos monitorFallos;
    private final ServicioExclusionMutua servicioMutex;

    private volatile String coordinadorId;
    private volatile boolean soyCoordinador;
    private final long timestampInicio = System.currentTimeMillis();

    public static final long GRACE_PERIOD_MS = 10_000;

    public ContextoNodo(String nodoId, ConfiguracionRed configuracion) throws Exception {
        this.nodoId = nodoId;
        this.configuracion = configuracion;
        this.nodoInfo = configuracion.getNodo(nodoId);
        if (nodoInfo == null) {
            throw new IllegalArgumentException("Nodo no encontrado en configuracion: " + nodoId);
        }
        this.relojLamport = new RelojLamport();
        this.gestorArchivos = new GestorArchivosLocal(nodoId);
        this.registroEventos = new RegistroEventos(nodoId);
        this.bully = new BullyAlgorithm(this);
        this.servicioReplicacion = new ServicioReplicacion(this);
        this.servicioHeartbeats = new ServicioHeartbeats(this);
        this.monitorFallos = new MonitorFallos(this);
        this.servicioMutex = new ServicioExclusionMutua(this);
    }

    public void iniciarServiciosDistribuidos() {
        bully.iniciarEleccionInicial();
        servicioHeartbeats.iniciar();
        servicioMutex.iniciarEscucha();
        monitorFallos.iniciar();
        registroEventos.registrar(relojLamport.obtener(), "NODO_INICIADO puertoDatos=" + nodoInfo.getPuertoDatos());
    }

    public String getNodoId() { return nodoId; }
    public NodoInfo getNodoInfo() { return nodoInfo; }
    public ConfiguracionRed getConfiguracion() { return configuracion; }
    public RelojLamport getRelojLamport() { return relojLamport; }
    public GestorArchivosLocal getGestorArchivos() { return gestorArchivos; }
    public RegistroEventos getRegistroEventos() { return registroEventos; }
    public BullyAlgorithm getBully() { return bully; }
    public ServicioReplicacion getServicioReplicacion() { return servicioReplicacion; }
    public ServicioHeartbeats getServicioHeartbeats() { return servicioHeartbeats; }
    public MonitorFallos getMonitorFallos() { return monitorFallos; }
    public ServicioExclusionMutua getServicioMutex() { return servicioMutex; }

    public String getCoordinadorId() { return coordinadorId; }
    public void setCoordinadorId(String coordinadorId) {
        this.coordinadorId = coordinadorId;
        this.soyCoordinador = nodoId.equals(coordinadorId);
    }

    public boolean soyCoordinador() { return soyCoordinador; }

    public NodoInfo getInfoCoordinador() {
        return configuracion.getNodo(coordinadorId);
    }

    public long getTimestampInicio() { return timestampInicio; }

    public void cerrar() {
        servicioMutex.detener();
        servicioHeartbeats.detener();
        monitorFallos.detener();
        registroEventos.cerrar();
    }
}
