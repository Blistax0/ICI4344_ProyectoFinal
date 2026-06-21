# Sistema de Almacenamiento Distribuido (Drive Simplificado)

Proyecto Final - ICI4344

Este proyecto implementa un sistema de almacenamiento distribuido descentralizado y tolerante a fallos, diseñado bajo arquitecturas con replicación de datos transparente, exclusión mutua mediante el algoritmo de Ricart y Agrawala, y ordenamiento asíncrono con Relojes Lógicos de Lamport.

## 1. Requisitos Previos

- **Java Development Kit (JDK):** Versión 21 o superior. Puede ser descargado desde [aqui](https://www.oracle.com/la/java/technologies/downloads/).

## 2. Instrucciones de Compilación

Para preparar el entorno de ejecución, es necesario compilar tanto el código fuente del servidor como el de los clientes de prueba. Abra una terminal apuntando al directorio raíz del código fuente `Codigo/DriveSimplificado` y ejecute:

```bash
# Compilación del Servidor y Componentes Centrales
javac -d target/classes -sourcepath src/main/java src/main/java/com/googledrive/storage/server/NodoAlmacenamiento.java

# Compilación de los Clientes de Prueba y Carga
javac -d target/classes -sourcepath "src/main/java;src/test/java" src/test/java/com/googledrive/client/storage/ClientePruebaAlmacenamiento.java
javac -d target/classes -sourcepath "src/main/java;src/test/java" src/test/java/com/googledrive/client/load/GeneradorCargaDrive.java
```

## 3. Despliegue de la Topología Distribuida

Para que los algoritmos de coordinación y exclusión mutua operen correctamente, se requiere inicializar una red de nodos interconectados. Abra tres consolas independientes en el directorio `Codigo/DriveSimplificado` y ejecute un nodo en cada una:

```bash
# Consola 1
java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo1 9000

# Consola 2
java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo2 9001

# Consola 3
java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo3 9002
```

## 4. Verificación Funcional Básica

Para realizar una validación inicial del sistema, comprobando la correcta coordinación de los relojes lógicos y la subida de un archivo individual hacia el ecosistema, ejecute el cliente de prueba de almacenamiento:

```bash
java -cp target/classes com.googledrive.client.storage.ClientePruebaAlmacenamiento
```

## 5. Pruebas de Rendimiento y Tolerancia a Fallos

El sistema incorpora una herramienta integral de generación de carga para evaluar el rendimiento bajo estrés y certificar la resiliencia de la red ante caídas abruptas. Esta herramienta despliega 50 hilos concurrentes durante 60 segundos.

El esquema de tolerancia a fallos está compuesto por:

1.  **Detección:** Intercambio continuo de _Heartbeats_ para identificar desconexiones.
2.  **Reconfiguración de Liderazgo:** Ejecución automática del Algoritmo Bully para nombrar un nuevo coordinador.
3.  **Recuperación y Sincronización:** Reintegración y sincronización de datos transparente para los nodos que se reincorporen tras una caída.

Para ejecutar la simulación de carga, visualizar las métricas resultantes (Throughput y Latencia P95) y probar la recuperación del sistema:

```bash
java -cp target/classes com.googledrive.client.load.GeneradorCargaDrive nodo2
```

_(Nota: El argumento opcional `nodo2` especifica la instancia que el generador de carga finalizará abruptamente a los 30 segundos para evaluar los mecanismos de reestructuración de la red)._

## 6. Configuración de Seguridad Perimetral

Todas las transmisiones entre clientes y nodos utilizan conexiones seguras (`SSL/TLS`). Para fines académicos y de prueba local, se incluye un almacén de claves pre-configurado (`keystore.jks`).

- **Contraseña del KeyStore:** `password`
