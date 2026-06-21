$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Modulo = Join-Path $Root "Codigo\DriveSimplificado"

Write-Host "================================================="
Write-Host "   DRIVE SIMPLIFICADO - SIMULACION AUTOMATICA    "
Write-Host "================================================="

Write-Host "`n[1/5] Limpiando procesos de Java previos..."
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
    if ($_.CommandLine -match "NodoAlmacenamiento" -or $_.CommandLine -match "GeneradorCargaDrive") {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

Set-Location $Modulo

Write-Host "`n[2/5] Compilando el Servidor y Generador de Carga..."
if (-not (Test-Path "target\classes")) { New-Item -ItemType Directory -Force -Path "target\classes" | Out-Null }

javac -d target/classes -sourcepath src/main/java src/main/java/com/googledrive/storage/server/NodoAlmacenamiento.java
javac -d target/classes -sourcepath "src/main/java;src/test/java" src/test/java/com/googledrive/client/load/GeneradorCargaDrive.java

Write-Host "`n[3/5] Levantando los 3 Nodos en ventanas separadas..."
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$Modulo'; java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo1 9000`""
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$Modulo'; java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo2 9001`""
Start-Process powershell -ArgumentList "-NoExit -Command `"cd '$Modulo'; java -cp target/classes com.googledrive.storage.server.NodoAlmacenamiento nodo3 9002`""

Write-Host "`n[4/5] Esperando 12 segundos para que Bully elija al Coordinador..."
Start-Sleep -Seconds 12

Write-Host "`n[5/5] Iniciando el Generador de Carga..."
Write-Host "      (El generador derribara automaticamente al nodo2 a los 30s)"
Write-Host "-------------------------------------------------`n"

java -cp target/classes com.googledrive.client.load.GeneradorCargaDrive nodo2

Write-Host "`n================================================="
Write-Host " Prueba finalizada con exito."
Write-Host " Puedes cerrar las ventanas de los nodos manualmente."
