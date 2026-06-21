@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0detener_nodos.ps1"
exit /b %ERRORLEVEL%
