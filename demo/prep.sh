#!/bin/zsh
# Gets the phone ready for screen recording: clean status bar, visible taps, mirror window on the Mac.
#   ./demo/prep.sh       start (opens the mirror; close its window when done)
#   ./demo/prep.sh off   put the phone back to normal
set -e

if ! adb get-state >/dev/null 2>&1; then
  echo "No phone found. Plug it in by USB and allow USB debugging, then run this again."
  exit 1
fi

demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

if [[ "$1" == "off" ]]; then
  demo exit
  adb shell settings put system show_touches 0
  echo "Phone back to normal."
  exit 0
fi

# Clean status bar: 9:41, full battery and signal, no notification icons.
# (Stock Android / Pixel only. Samsung One UI ignores these broadcasts, so the real status bar stays.)
adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false

echo "Status bar cleaned. Opening the mirror (close its window to stop)."
# --no-audio keeps the phone's sound on the phone. --show-touches draws a dot on every tap.
scrcpy --window-title "Phone" --always-on-top --stay-awake --show-touches --no-audio \
       --max-fps 60 --video-bit-rate 16M \
       --window-x 24 --window-y 44 --window-height 750   # left side of the MacBook screen, clear of banners
