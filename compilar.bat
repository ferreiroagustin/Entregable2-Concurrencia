@echo off
rem Compila todo el proyecto en la carpeta out\ (Java 17, sin Maven ni Gradle).
rem Se usan rutas relativas por paquete para no tener problemas con espacios en la ruta.
setlocal
cd /d "%~dp0"
if exist out rmdir /s /q out
javac -Xlint:all -encoding UTF-8 -d out ^
  src\restaurante\*.java ^
  src\restaurante\config\*.java ^
  src\restaurante\modelo\*.java ^
  src\restaurante\estado\*.java ^
  src\restaurante\recursos\*.java ^
  src\restaurante\actores\*.java ^
  src\restaurante\simulacion\*.java ^
  src\restaurante\display\*.java ^
  src\restaurante\web\*.java
if errorlevel 1 (
  echo ERROR: la compilacion fallo.
  exit /b 1
)
echo Compilacion OK -^> out\
endlocal
