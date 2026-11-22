#!/bin/zsh
# Builds both apps and puts the two download files side by side in release/:
#   release/PhoneBridge-<version>.dmg   (Mac: drag to Applications)
#   release/PhoneBridge-<version>.apk   (Android: install on the phone)
#   plus the same files under the plain names PhoneBridge.dmg / PhoneBridge.apk, and SHA256SUMS.txt
# Publish them with:  gh release create v<version> release/PhoneBridge-<version>.dmg release/PhoneBridge-<version>.apk \
#                       release/PhoneBridge.dmg release/PhoneBridge.apk release/SHA256SUMS.txt --title "PhoneBridge <version>" --generate-notes
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

(cd release && shasum -a 256 "PhoneBridge-$VERSION.dmg" "PhoneBridge-$VERSION.apk" > SHA256SUMS.txt)
echo
echo "Ready in $ROOT/release:"
ls -lh release | sed 's/^/  /'
