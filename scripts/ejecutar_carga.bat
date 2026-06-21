@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ejecutar_carga.ps1"
exit /b %ERRORLEVEL%
