#!/bin/bash
# Detiene los 3 nodos del cluster Drive Simplificado
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULO="$ROOT/Codigo/DriveSimplificado"
PID_DIR="$MODULO/.pids"

detenidos=0
if [ -d "$PID_DIR" ]; then
  for pidfile in "$PID_DIR"/*.pid; do
    [ -f "$pidfile" ] || continue
    pid=$(cat "$pidfile" 2>/dev/null | tr -d '[:space:]')
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null && detenidos=$((detenidos + 1))
    fi
    rm -f "$pidfile"
  done
fi

if pkill -9 -f "StorageServer nodo" 2>/dev/null; then
  detenidos=$((detenidos + 1))
fi

echo "Nodos detenidos: $detenidos proceso(s) StorageServer."
