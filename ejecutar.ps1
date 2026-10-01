# Ejecuta el simulador. Si no está compilado, compila primero.
# Ejemplos:
#   .\ejecutar.ps1                      modo web (abre http://localhost:8080)
#   .\ejecutar.ps1 --consola            solo consola, termina solo
#   .\ejecutar.ps1 --consola --t 15000  consola con T = 15 s
#   .\ejecutar.ps1 --repeticiones 20    20 simulaciones cortas de estrés
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

if (-not (Test-Path out\restaurante\Main.class)) {
    & "$PSScriptRoot\compilar.ps1"
    if ($LASTEXITCODE -ne 0) { exit 1 }
}
& java -cp out restaurante.Main @args
