#!/bin/bash
# Builds and tests the Windows app, then publishes a self-contained PhoneBridge.exe.
# Works on macOS, Linux or Windows (Git Bash) with the .NET 10 SDK: https://dotnet.microsoft.com/download
#   ./build.sh            → out/win-x64/PhoneBridge.exe
#   ./build.sh win-arm64  → out/win-arm64/PhoneBridge.exe
set -euo pipefail
cd "$(dirname "$0")"
RID="${1:-win-x64}"
dotnet test tests/PhoneBridge.Core.Tests -c Release
dotnet publish src/PhoneBridge -c Release -r "$RID" -o "out/$RID"
echo "Built out/$RID/PhoneBridge.exe"
