@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar_nodos.ps1"
exit /b %ERRORLEVEL%
