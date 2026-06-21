# Drive Simplificado - Proyecto Final ICI4344

Sistema distribuido de almacenamiento con 3 nodos, relojes de Lamport, eleccion Bully, exclusion mutua Ricart-Agrawala, heartbeats y generador de carga.

## Funciones principales

1. **Almacenamiento distribuido** (`SUBIR`, `DESCARGAR`): replicacion en los 3 nodos.
2. **Edicion colaborativa causal** (`EDITAR`): orden Lamport en el log del archivo compartido.

## Requisitos tecnicos distribuidos (Seccion 2)

### 2.1 Topologia multinodo

- Tres nodos como minimo, cada uno en su propio proceso o JVM; lo ideal es repartirlos en maquinas distintas de la misma red local.
- Arquitectura peer-to-peer o de varios servidores. Queda descartado depender de un unico servidor que centralice toda la coordinacion.
- Los nodos deben conocerse entre si mediante un registro o un mecanismo de descubrimiento (lista de membresia).

**Implementacion en este proyecto:**

- Tres JVM independientes con [`StorageServer`](Codigo/DriveSimplificado/src/main/java/com/googledrive/storage/server/StorageServer.java), levantadas con [`iniciar_nodos.sh`](scripts/iniciar_nodos.sh) o [`iniciar_nodos.ps1`](scripts/iniciar_nodos.ps1).
- Arquitectura multiservidor: Bully elige coordinador rotativo; cualquier nodo atiende `DESCARGAR`; la escritura pasa por el coordinador y se replica al resto.
- Membresia estatica en [`nodos.txt`](nodos.txt), cargada por [`ConfiguracionRed`](Codigo/DriveSimplificado/src/main/java/com/googledrive/core/config/ConfiguracionRed.java).
- Puertos por nodo: datos **9000-9002**, control **9100-9102**, mutex Ricart **9200-9202**.

### 2.2 Ordenamiento de eventos (Unidad 5)

- Relojes de Lamport o vectoriales aplicados a los mensajes que intercambian los nodos.
- Al menos una de las dos funciones principales debe mostrar un ordenamiento causal correcto.
- Cada evento relevante debe quedar registrado en un log con su marca logica, para poder mostrar el orden en el informe.

**Implementacion en este proyecto:**

- [`RelojLamport`](Codigo/DriveSimplificado/src/main/java/com/googledrive/core/clock/RelojLamport.java) en peticiones de cliente, replicacion, Bully y Ricart-Agrawala.
- Funcion principal con orden causal: `EDITAR` en `documento_compartido.txt` escribe lineas `[Nodo: X | Lamport: N]` via [`GestorArchivosLocal`](Codigo/DriveSimplificado/src/main/java/com/googledrive/storage/server/GestorArchivosLocal.java).
- Logs con marca Lamport: [`RegistroEventos`](Codigo/DriveSimplificado/src/main/java/com/googledrive/core/logging/RegistroEventos.java) genera `eventos_nodo*.log`; [`ConsolidadorLogs`](Codigo/DriveSimplificado/src/main/java/com/googledrive/client/load/ConsolidadorLogs.java) produce el archivo consolidado ordenado.
- Prueba manual: [`ClientePruebaSincronizacion`](Codigo/DriveSimplificado/src/test/java/com/googledrive/client/sync/ClientePruebaSincronizacion.java).

### 2.3 Coordinacion distribuida (Unidad 6)

- Exclusion mutua distribuida (anillo, Ricart-Agrawala o Maekawa) para proteger un recurso critico compartido.
- Eleccion de coordinador con Bully o anillo, disparada al detectar que el coordinador actual cayo.
- Consenso simplificado (opcional): no implementado en esta version.

**Implementacion en este proyecto:**

| Operacion | Algoritmo | Proposito |
|-----------|-----------|-----------|
| `SUBIR` / replicacion | **Bully** | Eleccion de coordinador y replicacion centralizada |
| `EDITAR` en `documento_compartido.txt` | **Ricart-Agrawala** | Exclusion mutua del recurso critico compartido |

- Ricart-Agrawala: [`ServicioExclusionMutua`](Codigo/DriveSimplificado/src/main/java/com/googledrive/coordination/ServicioExclusionMutua.java) (puertos **9200-9202**, TCP plano).
- Bully: [`BullyAlgorithm`](Codigo/DriveSimplificado/src/main/java/com/googledrive/coordination/BullyAlgorithm.java), activado por heartbeats cuando cae el coordinador (puertos **9100-9102**, TLS).

### 2.4 Tolerancia a fallos

- Deteccion mediante heartbeats o timeouts, tanto de nodos caidos (crash) como de mensajes que se pierden (omision).
- Recuperacion efectiva: ante una caida, el sistema se reorganiza — vuelve a elegir coordinador, redistribuye la carga o reintegra al nodo — sin que el servicio se detenga por completo.

**Implementacion en este proyecto:**

- **Heartbeats** cada 2s por canal TLS de control: [`ServicioHeartbeats`](Codigo/DriveSimplificado/src/main/java/com/googledrive/fault/ServicioHeartbeats.java); timeout 5s registra `NODO_CAIDO` con marca Lamport.
- **Deteccion de omision**: `OMISION_MENSAJE` en replicacion y en Ricart si falla un envio.
- **Re-eleccion Bully** automatica si cae el coordinador; reintento en escrituras durante la re-eleccion (hasta 3s).
- **Reintegracion**: sync de archivos al recuperar un nodo via [`ServicioReplicacion`](Codigo/DriveSimplificado/src/main/java/com/googledrive/storage/server/ServicioReplicacion.java).
- **Demo de falla inducida**: [`GeneradorCargaDrive`](Codigo/DriveSimplificado/src/main/java/com/googledrive/client/load/GeneradorCargaDrive.java) derriba nodo3 a t=30s y mide tiempo de recuperacion.

## Requisitos

- Java 17+ (JDK 21 recomendado)
- Maven 3.x
- `keytool` (JDK)

Descarga JDK: https://www.oracle.com/latam/java/technologies/downloads/

## Configuracion

- [`nodos.txt`](nodos.txt): membresia del cluster (`id=host:puertoDatos:puertoControl:puertoMutex`)
- Keystore TLS (`Codigo/DriveSimplificado/keystore.jks`): contraseña `password`
- Se genera automaticamente al ejecutar los scripts de inicio si no existe

## Demo en vivo (Requisito 4.8)

| Paso | Linux / macOS | Windows (PowerShell o CMD) |
|------|---------------|----------------------------|
| Levantar 3 nodos | `./scripts/iniciar_nodos.sh` | `.\scripts\iniciar_nodos.bat` |
| Prueba de carga 60s | `./scripts/ejecutar_carga.sh` | `.\scripts\ejecutar_carga.bat` |
| Detener nodos | `./scripts/detener_nodos.sh` | `.\scripts\detener_nodos.bat` |

### Linux / macOS

```bash
chmod +x scripts/iniciar_nodos.sh scripts/ejecutar_carga.sh scripts/detener_nodos.sh

# 1. Levantar 3 nodos (guarda PIDs en Codigo/DriveSimplificado/.pids/)
./scripts/iniciar_nodos.sh

# 2. Prueba de carga: 50 hilos, 60s, falla inducida en t=30s (nodo3 = coordinador)
./scripts/ejecutar_carga.sh

# 3. Detener nodos
./scripts/detener_nodos.sh
```

### Windows

Requisitos: `java`, `javac` y `keytool` en el PATH. Ejecutar desde la raiz del repositorio.

```powershell
# Opcion A: wrappers .bat (recomendado, no requiere cambiar ExecutionPolicy)
.\scripts\iniciar_nodos.bat
.\scripts\ejecutar_carga.bat
.\scripts\detener_nodos.bat

# Opcion B: PowerShell directo
powershell -ExecutionPolicy Bypass -File .\scripts\iniciar_nodos.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\ejecutar_carga.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\detener_nodos.ps1
```

### Que sucede durante la demo

- Metricas parciales en consola cada 10 segundos (throughput, errores, latencia).
- A t=30s: mensaje `FALLA INDUCIDA` y `RECUPERACION: nuevo coordinador=nodo2`.
- Metricas finales: throughput, latencia p95, msgs coordinacion, tiempo recuperacion.
- Archivos generados en `Codigo/DriveSimplificado/logs/`:
  - `carga_<timestamp>.txt` — metricas completas
  - `eventos_corrida_<timestamp>.txt` — eventos Lamport consolidados
- Logs por nodo: `eventos_nodo1.log`, `eventos_nodo2.log`, `eventos_nodo3.log`
- Eventos Ricart: `RICART_ENTRA`, `RICART_SALE`, `RICART_DIFIERE` en logs consolidados

## Metricas recolectadas (Requisito 3.2)

| Metrica | Donde |
|---------|-------|
| Throughput (req/s) | Consola + `logs/carga_*.txt` |
| Latencia promedio y p95 | Consola + `logs/carga_*.txt` |
| Mensajes de coordinacion | Consola + `logs/carga_*.txt` |
| Tasa de error pre/post falla | Consola + `logs/carga_*.txt` |
| Tiempo de recuperacion | Consola + `logs/carga_*.txt` |

## Pruebas manuales

Con los 3 nodos levantados, desde `Codigo/DriveSimplificado`:

**Linux / macOS:**

```bash
find src/main/java -name "*.java" | sort | xargs javac -d target/classes -encoding UTF-8
find src/test/java -name "*.java" | sort | xargs javac -cp target/classes -d target/classes -encoding UTF-8
java -cp target/classes com.googledrive.client.test.ClientePruebaAlmacenamiento ../../nodos.txt
java -cp target/classes com.googledrive.client.sync.ClientePruebaSincronizacion ../../nodos.txt
```

**Windows (PowerShell):**

```powershell
Get-ChildItem -Recurse -Filter *.java src\main\java | ForEach-Object { $_.FullName } | Set-Content sources.txt
javac -d target/classes -encoding UTF-8 "@sources.txt"
Get-ChildItem -Recurse -Filter *.java src\test\java | ForEach-Object { $_.FullName } | Set-Content sources-test.txt
javac -cp target/classes -d target/classes -encoding UTF-8 "@sources-test.txt"
java -cp target/classes com.googledrive.client.test.ClientePruebaAlmacenamiento ..\..\nodos.txt
java -cp target/classes com.googledrive.client.sync.ClientePruebaSincronizacion ..\..\nodos.txt
```

## Ejecucion manual de un nodo

**Linux:**

```bash
cd Codigo/DriveSimplificado
find src/main/java -name "*.java" | xargs javac -d target/classes -encoding UTF-8
java -cp target/classes com.googledrive.storage.server.StorageServer nodo1 ../../nodos.txt
```

**Windows:**

```powershell
cd Codigo\DriveSimplificado
powershell -ExecutionPolicy Bypass -File ..\..\scripts\compilar.ps1
java -cp target/classes com.googledrive.storage.server.StorageServer nodo1 ..\..\nodos.txt
```
