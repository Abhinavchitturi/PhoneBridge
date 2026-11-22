#!/bin/zsh
# Builds PhoneBridge.app and packs it into a drag-to-Applications disk image: release/PhoneBridge-<version>.dmg
# Usage: ./make-dmg.sh            (builds, then packs)
#        ./make-dmg.sh --no-build (packs the existing build/PhoneBridge.app)
set -e
cd "$(dirname "$0")"
[[ "$1" == "--no-build" ]] || ./build.sh
APP=build/PhoneBridge.app
[[ -d "$APP" ]] || { echo "No $APP — run ./build.sh first"; exit 1; }
VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" Info.plist)
OUT=../release
NAME="PhoneBridge-$VERSION"
VOL="PhoneBridge"
mkdir -p "$OUT"
STAGE=$(mktemp -d)
RW="$STAGE/rw.dmg"
trap 'hdiutil detach "/Volumes/$VOL" -quiet 2>/dev/null || true; rm -rf "$STAGE"' EXIT

# 1. Stage: the app, a shortcut to /Applications and the window background
mkdir -p "$STAGE/src/.background"
cp -R "$APP" "$STAGE/src/"
ln -s /Applications "$STAGE/src/Applications"
cp dmg/background.png "$STAGE/src/.background/background.png"
# A double-clickable shortcut to System Settings → Privacy & Security, where "Open Anyway" lives (step 3 in the window).
ln -s /System/Library/PreferencePanes/Security.prefPane "$STAGE/src/Privacy & Security"

# 2. A writable image, so the window can be laid out
hdiutil detach "/Volumes/$VOL" -quiet 2>/dev/null || true
hdiutil create -quiet -srcfolder "$STAGE/src" -volname "$VOL" -fs HFS+ -format UDRW -ov "$RW"
hdiutil attach -quiet -readwrite -noverify -noautoopen "$RW"

# 3. Icon positions and window look (Finder scripting; skipped quietly if macOS doesn't allow it)
osascript <<EOF || echo "Note: couldn't style the installer window (Finder automation not allowed); the DMG still works."
tell application "Finder"
  tell disk "$VOL"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    try
      set pathbar visible of container window to false
    end try
    set the bounds of container window to {200, 120, 860, 658}
    set opts to the icon view options of container window
    set arrangement of opts to not arranged
    set icon size of opts to 96
    set background picture of opts to file ".background:background.png"
    set position of item "PhoneBridge.app" of container window to {170, 120}
    set position of item "Applications" of container window to {490, 120}
    set position of item "Privacy & Security" of container window to {545, 355}
    close
    open
    update without registering applications
    delay 2
    close
  end tell
end tell
EOF
sync
hdiutil detach "/Volumes/$VOL" -quiet

# 4. The compressed, read-only image people download
rm -f "$OUT/$NAME.dmg"
hdiutil convert "$RW" -quiet -format UDZO -imagekey zlib-level=9 -o "$OUT/$NAME.dmg"
cp "$OUT/$NAME.dmg" "$OUT/PhoneBridge.dmg"
echo "Built $OUT/$NAME.dmg"
