#!/bin/bash
# Inicia los 3 nodos del cluster Drive Simplificado
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULO="$ROOT/Codigo/DriveSimplificado"
NODOS="$ROOT/nodos.txt"
PID_DIR="$MODULO/.pids"

cd "$MODULO"
mkdir -p "$PID_DIR" logs target/classes

if [ ! -f keystore.jks ]; then
  echo "Generando keystore.jks..."
  keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
    -storetype JKS -keystore keystore.jks -validity 365 \
    -storepass password -keypass password \
    -dname "CN=localhost, OU=Drive, O=PUCV, L=Valparaiso, ST=Valparaiso, C=CL"
fi

if command -v mvn >/dev/null 2>&1; then
  mvn -q compile -f pom.xml
else
  echo "Maven no encontrado, compilando con javac..."
  find src/main/java -name "*.java" | sort | xargs javac -d target/classes -encoding UTF-8
fi

run_nodo() {
  local ID=$1
  java -cp target/classes com.googledrive.storage.server.StorageServer "$ID" "$NODOS" &
  local PID=$!
  echo "$PID" > "$PID_DIR/$ID.pid"
  echo "Nodo $ID iniciado (PID $PID)"
}

run_nodo nodo1
sleep 2
run_nodo nodo2
sleep 2
run_nodo nodo3
sleep 2

echo ""
echo "Cluster iniciado. Puertos datos: 9000-9002 | control: 9100-9102"
echo "PIDs en $PID_DIR/"
echo "Detener: pkill -f StorageServer  o  kill \$(cat $PID_DIR/nodo1.pid) ..."
