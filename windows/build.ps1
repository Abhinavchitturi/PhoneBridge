# Builds and tests the Windows app, then publishes a self-contained PhoneBridge.exe (needs the .NET 10 SDK).
#   .\build.ps1                  -> out\win-x64\PhoneBridge.exe
#   .\build.ps1 -Rid win-arm64   -> out\win-arm64\PhoneBridge.exe
param([string]$Rid = "win-x64")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
dotnet test tests/PhoneBridge.Core.Tests -c Release
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
dotnet publish src/PhoneBridge -c Release -r $Rid -o "out/$Rid"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Built out\$Rid\PhoneBridge.exe"
