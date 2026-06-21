# Drive Simplificado - Proyecto Final ICI4344

Sistema distribuido de almacenamiento con 3 nodos, relojes de Lamport, eleccion Bully, exclusion mutua Ricart-Agrawala, heartbeats y generador de carga.

## Funciones principales

1. **Almacenamiento distribuido** (`SUBIR`, `DESCARGAR`): replicacion en los 3 nodos.
2. **Edicion colaborativa causal** (`EDITAR`): orden Lamport en el log del archivo compartido.

## Dos algoritmos de coordinacion

| Operacion | Algoritmo | Proposito |
|-----------|-----------|-----------|
| `SUBIR` / replicacion | **Bully** | Eleccion de coordinador y replicacion centralizada |
| `EDITAR` en `documento_compartido.txt` | **Ricart-Agrawala** | Exclusion mutua del recurso critico compartido |

Ricart usa puertos dedicados **9200-9202** (TCP plano). Bully, heartbeats y replicacion usan **9100-9102** (TLS).

## Requisitos

- Java 17+
- Maven 3.x (opcional; los scripts usan `javac` como fallback)
- `keytool` (JDK)

## Configuracion

- [`nodos.txt`](nodos.txt): membresia del cluster (`id=host:puertoDatos:puertoControl:puertoMutex`)
- Keystore TLS: `password`

## Demo en vivo (Requisito 4.8)

```bash
chmod +x scripts/iniciar_nodos.sh scripts/ejecutar_carga.sh

# 1. Levantar 3 nodos (guarda PIDs en Codigo/DriveSimplificado/.pids/)
./scripts/iniciar_nodos.sh

# 2. Prueba de carga: 50 hilos, 60s, falla inducida en t=30s (nodo3 = coordinador)
./scripts/ejecutar_carga.sh

# 3. Detener nodos
pkill -f StorageServer
```

### Que observar durante la demo

- Metricas parciales en consola cada 10 segundos (throughput, errores, latencia).
- A t=30s: mensaje `FALLA INDUCIDA` y `RECUPERACION: nuevo coordinador=nodo2`.
- Metricas finales: throughput, latencia p95, msgs coordinacion, tiempo recuperacion.
- Archivos generados en `Codigo/DriveSimplificado/logs/`:
  - `carga_<timestamp>.txt` — metricas completas
  - `eventos_corrida_<timestamp>.txt` — eventos Lamport consolidados
- Logs por nodo: `eventos_nodo1.log`, `eventos_nodo2.log`, `eventos_nodo3.log`
- Eventos Ricart: `RICART_ENTRA`, `RICART_SALE`, `RICART_DIFIERE` en logs consolidados

## Tolerancia a fallos (Requisito 2.4)

- **Heartbeats** cada 2s por canal TLS de control (puertos 9100-9102).
- **Timeout** de 5s: nodo declarado `NODO_CAIDO` en log con marca Lamport.
- **Re-eleccion Bully** automatica si cae el coordinador.
- **Reintento** en peticiones de escritura durante la re-eleccion (hasta 3s).
- **Deteccion de omision**: `OMISION_MENSAJE` si no hay ACK de replicacion.

## Metricas recolectadas (Requisito 3.2)

| Metrica | Donde |
|---------|-------|
| Throughput (req/s) | Consola + `logs/carga_*.txt` |
| Latencia promedio y p95 | Consola + `logs/carga_*.txt` |
| Mensajes de coordinacion | Consola + `logs/carga_*.txt` |
| Tasa de error pre/post falla | Consola + `logs/carga_*.txt` |
| Tiempo de recuperacion | Consola + `logs/carga_*.txt` |

## Ejecucion manual

```bash
cd Codigo/DriveSimplificado
find src/main/java -name "*.java" | xargs javac -d target/classes -encoding UTF-8
java -cp target/classes com.googledrive.storage.server.StorageServer nodo1 ../../nodos.txt
```
