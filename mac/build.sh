#!/bin/zsh
# Builds PhoneBridge.app (menu bar app) without Xcode.
set -e
cd "$(dirname "$0")"
APP=build/PhoneBridge.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp Info.plist "$APP/Contents/Info.plist"
# A fresh build number every time, so macOS (e.g. Notification Center's icon cache) notices updates.
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $(date +%Y%m%d%H%M%S)" "$APP/Contents/Info.plist"
mkdir -p "$APP/Contents/Resources" && cp AppIcon.icns "$APP/Contents/Resources/"
# Now-playing watcher, run inside /usr/bin/perl (see Helpers/NowPlaying.m).
clang -O2 -dynamiclib -fobjc-arc -framework Foundation -framework ImageIO -framework CoreGraphics \
    Helpers/NowPlaying.m -o "$APP/Contents/Resources/NowPlaying.dylib"
cp Helpers/pb "$APP/Contents/Resources/pb" && chmod +x "$APP/Contents/Resources/pb"
swiftc -O -parse-as-library -swift-version 5 Sources/*.swift -o "$APP/Contents/MacOS/PhoneBridge"
# Same signing identity every build keeps macOS permissions (see make-cert.sh); ad-hoc otherwise.
IDENTITY=$(security find-certificate -c "PhoneBridge Local Signing" -Z 2>/dev/null | awk '/SHA-1/ {print $3}')
codesign --force --sign "${IDENTITY:--}" "$APP"
echo "Built $APP"
