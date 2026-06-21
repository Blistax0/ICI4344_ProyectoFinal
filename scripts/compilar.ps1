$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Modulo = Join-Path $Root "Codigo\DriveSimplificado"

Set-Location $Modulo
New-Item -ItemType Directory -Force -Path "target\classes", "logs", ".pids" | Out-Null

if (-not (Test-Path "keystore.jks")) {
    Write-Host "Generando keystore.jks..."
    keytool -genkeypair -alias server -keyalg RSA -keysize 2048 `
        -storetype JKS -keystore keystore.jks -validity 365 `
        -storepass password -keypass password `
        -dname "CN=localhost, OU=Drive, O=PUCV, L=Valparaiso, ST=Valparaiso, C=CL"
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    mvn -q compile -f pom.xml
} else {
    Write-Host "Maven no encontrado, compilando con javac..."
    $sourcesFile = Join-Path $Modulo "sources.txt"
    Get-ChildItem -Recurse -Filter "*.java" "src\main\java" |
        ForEach-Object { $_.FullName } |
        Set-Content -Encoding UTF8 $sourcesFile
    javac -d target\classes -encoding UTF-8 "@$sourcesFile"
    Remove-Item $sourcesFile -ErrorAction SilentlyContinue
}

Write-Host "Compilacion completada."
