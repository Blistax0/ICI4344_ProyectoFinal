$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Modulo = Join-Path $Root "Codigo\DriveSimplificado"
$NodosTxt = (Resolve-Path (Join-Path $Root "nodos.txt")).Path
$PidDir = Join-Path $Modulo ".pids"

& (Join-Path $PSScriptRoot "compilar.ps1")

Set-Location $Modulo
Remove-Item (Join-Path $PidDir "*.pid") -ErrorAction SilentlyContinue

function Start-Nodo {
    param([string]$NodoId)
    $p = Start-Process java -ArgumentList @(
        "-cp", "target/classes",
        "com.googledrive.storage.server.StorageServer",
        $NodoId, $NodosTxt
    ) -PassThru -WindowStyle Normal
    $p.Id | Set-Content (Join-Path $PidDir "$NodoId.pid")
    Write-Host "Nodo $NodoId iniciado (PID $($p.Id))"
}

Start-Nodo "nodo1"
Start-Sleep -Seconds 2
Start-Nodo "nodo2"
Start-Sleep -Seconds 2
Start-Nodo "nodo3"
Start-Sleep -Seconds 2

Write-Host ""
Write-Host "Cluster iniciado. Puertos datos: 9000-9002 | control: 9100-9102 | mutex: 9200-9202"
Write-Host "PIDs en $PidDir"
Write-Host "Detener: .\scripts\detener_nodos.bat  o  .\scripts\detener_nodos.ps1"
