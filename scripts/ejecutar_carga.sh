#!/bin/bash
# Ejecuta la prueba de carga de 60 segundos con falla inducida al coordinador (nodo3)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULO="$ROOT/Codigo/DriveSimplificado"
NODOS="$ROOT/nodos.txt"

cd "$MODULO"
mkdir -p logs .pids target/classes

if command -v mvn >/dev/null 2>&1; then
  mvn -q compile -f pom.xml
else
  find src/main/java -name "*.java" | sort | xargs javac -d target/classes -encoding UTF-8
fi

java -cp target/classes com.googledrive.client.load.GeneradorCargaDrive "$NODOS" nodo3
