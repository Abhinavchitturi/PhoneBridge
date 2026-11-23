#!/bin/zsh
# Builds the apps and puts the download files side by side in release/:
#   release/PhoneBridge.dmg           (Mac: drag to Applications)
#   release/PhoneBridge.apk           (Android: install on the phone)
#   release/PhoneBridge-Windows.exe   (Windows: run it; built when the .NET 10 SDK is installed)
# (versioned copies, PhoneBridge-<version>.dmg/.apk and PhoneBridge-Windows-<version>.exe, stay in release/ as a local archive)
# Publish just the plain-named files; the README links and the apps' updaters look for these names,
# and GitHub shows each file's SHA-256 itself (the Windows updater checks it):
#   gh release create v<version> release/PhoneBridge.dmg release/PhoneBridge.apk release/PhoneBridge-Windows.exe --title "PhoneBridge <version>" --notes "..."
set -e
cd "$(dirname "$0")"
ROOT=$PWD

# Both apps update themselves from the latest GitHub release by comparing version numbers,
# so the Mac and Android versions must match the release tag (v<version>).
MAC_V=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" mac/Info.plist)
APK_V=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts)
[[ "$MAC_V" == "$APK_V" ]] || { echo "Version mismatch: mac/Info.plist is $MAC_V, android versionName is $APK_V"; exit 1; }
WIN_V=$(sed -n 's/.*<Version>\(.*\)<\/Version>.*/\1/p' windows/src/PhoneBridge/PhoneBridge.csproj)
[[ "$MAC_V" == "$WIN_V" ]] || { echo "Version mismatch: mac/Info.plist is $MAC_V, windows PhoneBridge.csproj is $WIN_V"; exit 1; }

echo "▸ Mac app and disk image"
./mac/make-dmg.sh

echo "▸ Android app"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
(cd android && ./gradlew assembleRelease -q)
APK=android/app/build/outputs/apk/release/app-release.apk
[[ -f "$APK" ]] || { echo "APK not built"; exit 1; }
VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" mac/Info.plist)
cp "$APK" "release/PhoneBridge-$VERSION.apk"
cp "$APK" release/PhoneBridge.apk
cp "release/PhoneBridge-$VERSION.apk" PhoneBridge.apk   # the copy kept in the project root

if command -v dotnet >/dev/null; then
  echo "▸ Windows app"
  ./windows/build.sh win-x64 >/dev/null
  cp windows/out/win-x64/PhoneBridge.exe "release/PhoneBridge-Windows-$VERSION.exe"
  cp windows/out/win-x64/PhoneBridge.exe release/PhoneBridge-Windows.exe
else
  echo "▸ Windows app skipped: install the .NET 10 SDK to build it (brew install --cask dotnet-sdk)"
  rm -f release/PhoneBridge-Windows.exe
fi
rm -f release/SHA256SUMS.txt
echo
echo "Ready in $ROOT/release:"
ls -lh release | sed 's/^/  /'
