# Drive Simplificado

# Requisitos antes de ejecutar

Para ejecutar las pruebas correspondientes se pueden usar diferentes herramientas, como JDK o Maven. En este caso se recomienda utilizar JDK 21, ya que fue el programa utilizado para realizar las pruebas.

- https://www.oracle.com/latam/java/technologies/downloads/ (JDK 21, 25 o 26)

# Instrucciones de ejecucion y pruebas

1. Compilacion
   Antes de correr ejecutar alguna prueba, asegurate de compilar el codigo fuente. Abre una terminal en la carpeta Codigo/DriveSimplificado y ejecuta:
   javac -d target/classes -sourcepath src/main/java src/main/java/com/googledrive/storage/server/NodoAlmacenamiento.java

(Hacer lo mismo para el cliente o generador de carga si corresponde).

2. Ejecucion de los Nodos
   Para que el algoritmo de Ricart y Agrawala funcione y se coordinen entre si, es necesario levantar los 3 nodos en terminales independientes. Ejecuta cada uno de estos comandos en una consola distinta (aun mas...):

java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo1 9000
java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo2 9001
java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo3 9002

3. Ejecucion del Cliente normal
   Para probar subir o descargar un archivo manualmente:
   java -cp target/classes com.googledrive.client.drive.DriveClient

4. Prueba de Carga
   Para probar el rendimiento y la tolerancia a fallos, se debe correr el generador de carga (una vez que este implementado por el equipo). Se ejecuta en otra terminal y empezara a mandar peticiones a los puertos 9000, 9001 y 9002.
   Para simular la caida de un nodo, simplemente presiona Ctrl+C en la terminal de alguno de los nodos (ej. nodo2) mientras la prueba esta corriendo, y revisa los logs para ver como se recupera el sistema.

## Contraseña KeyStore

Esto es por el SSl socket por si acaso.
contraseña keystore: password

## Nota

Ocupe solo java, nada de maven porque que lata, pero funciona igual obvio.
https://www.oracle.com/latam/java/technologies/downloads/ descarguen el 21, por lo menos yo ocupe ese para correr todo.
