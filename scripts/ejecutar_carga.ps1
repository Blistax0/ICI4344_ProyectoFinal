$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Modulo = Join-Path $Root "Codigo\DriveSimplificado"
$NodosTxt = (Resolve-Path (Join-Path $Root "nodos.txt")).Path

& (Join-Path $PSScriptRoot "compilar.ps1")

Set-Location $Modulo
java -cp target/classes com.googledrive.client.load.GeneradorCargaDrive $NodosTxt nodo3
