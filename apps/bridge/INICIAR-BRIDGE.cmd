@echo off
setlocal
title Codex Watch Bridge
cd /d "%~dp0"

netstat -ano | findstr /R /C:":8787 .*LISTENING" >nul
if not errorlevel 1 (
    echo El bridge ya esta ejecutandose en el puerto 8787.
    echo.
    echo Cierra su otra ventana o detenlo antes de arrancar una segunda copia.
    pause
    exit /b 0
)

echo Iniciando Codex Watch Bridge...
echo Esta ventana debe permanecer abierta.
echo Para detener el bridge, pulsa Ctrl+C o cierra la ventana.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-network.ps1"

set "BRIDGE_EXIT=%ERRORLEVEL%"
echo.
if not "%BRIDGE_EXIT%"=="0" (
    echo El bridge se ha detenido con el codigo %BRIDGE_EXIT%.
) else (
    echo El bridge se ha detenido.
)
pause
exit /b %BRIDGE_EXIT%
