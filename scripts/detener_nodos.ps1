$ErrorActionPreference = "SilentlyContinue"

$Root = Split-Path -Parent $PSScriptRoot
$Modulo = Join-Path $Root "Codigo\DriveSimplificado"
$PidDir = Join-Path $Modulo ".pids"

$detenidos = 0
if (Test-Path $PidDir) {
    Get-ChildItem (Join-Path $PidDir "*.pid") | ForEach-Object {
        $processId = (Get-Content $_.FullName -Raw).Trim()
        if ($processId -match '^\d+$') {
            Stop-Process -Id ([int]$processId) -Force -ErrorAction SilentlyContinue
            if ($?) { $detenidos++ }
        }
        Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
    }
}

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
    if ($_.CommandLine -like "*StorageServer*") {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        $detenidos++
    }
}

Write-Host "Nodos detenidos: $detenidos proceso(s) StorageServer."
