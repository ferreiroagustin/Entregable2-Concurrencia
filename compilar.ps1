# Compila todo el proyecto en la carpeta out/ (Java 17, sin Maven ni Gradle).
# Uso:  .\compilar.ps1
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

if (Test-Path out) { Remove-Item -Recurse -Force out }
$fuentes = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "Compilando $($fuentes.Count) archivos .java ..."

& javac -Xlint:all -encoding UTF-8 -d out $fuentes
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: la compilación falló." -ForegroundColor Red
    exit 1
}
Write-Host "Compilación OK -> out\" -ForegroundColor Green
