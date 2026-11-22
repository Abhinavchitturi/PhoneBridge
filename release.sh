#!/bin/zsh
# Builds both apps and puts the two download files side by side in release/:
#   release/PhoneBridge.dmg   (Mac: drag to Applications)
#   release/PhoneBridge.apk   (Android: install on the phone)
# (versioned copies, PhoneBridge-<version>.dmg/.apk, stay in release/ as a local archive)
# Publish just the two plain-named files; the README links and both apps' updaters look for these names,
# and GitHub shows each file's SHA-256 itself:
#   gh release create v<version> release/PhoneBridge.dmg release/PhoneBridge.apk --title "PhoneBridge <version>" --notes "..."
set -e
cd "$(dirname "$0")"
ROOT=$PWD

# Both apps update themselves from the latest GitHub release by comparing version numbers,
# so the Mac and Android versions must match the release tag (v<version>).
MAC_V=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" mac/Info.plist)
APK_V=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts)
[[ "$MAC_V" == "$APK_V" ]] || { echo "Version mismatch: mac/Info.plist is $MAC_V, android versionName is $APK_V"; exit 1; }

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
rm -f release/SHA256SUMS.txt
echo
echo "Ready in $ROOT/release:"
ls -lh release | sed 's/^/  /'
