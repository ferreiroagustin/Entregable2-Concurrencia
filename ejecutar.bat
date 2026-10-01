@echo off
rem Ejecuta el simulador (compila si hace falta). Acepta los mismos flags que Main:
rem   ejecutar.bat                       modo web
rem   ejecutar.bat --consola --t 15000   solo consola con T = 15 s
rem   ejecutar.bat --repeticiones 20     estres
setlocal
cd /d "%~dp0"
if not exist out\restaurante\Main.class call compilar.bat || exit /b 1
java -cp out restaurante.Main %*
endlocal
