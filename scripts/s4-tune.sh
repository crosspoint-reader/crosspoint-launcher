#!/bin/sh
# Debloat + e-ink text tweaks for the Xteink S4, over plain adb (no root, no reboot).
# Everything is reversible:  ./s4-tune.sh --undo
# Requires: USB debugging enabled, `adb devices` shows the S4 as "device".
set -e

# Vendor apps removed for user 0 (APKs stay in /system; --undo restores them).
PKGS="
com.wetao.elauncher
com.wetao.emreader
com.wetao.storage
com.simple.appstore
com.cghs.stresstest
com.DeviceTest
"
# Kept on purpose: com.wetao.transfer (Wi-Fi book transfer), com.wetao.wallpaper
# (the only app that can set the sleep screen), org.koreader.launcher.

CROSSPOINT=com.crosspoint.launcher/.MainActivity
GAMMA=1.5   # debug.sf.gamma.gamma, 0..2.5; darkens thin strokes. Resets on reboot.

model=$(adb shell getprop ro.product.model | tr -d '\r')
[ "$model" = "S4" ] || { echo "Expected an Xteink S4, found '$model'. Aborting."; exit 1; }

if [ "$1" = "--undo" ]; then
  for p in $PKGS; do adb shell pm install-existing --user 0 "$p" >/dev/null && echo "restored $p"; done
  adb shell settings put secure high_text_contrast_enabled 0   # turned on by earlier versions
  adb shell setprop debug.sf.gamma.gamma "''"
  echo "Done. Stock state restored."
  exit 0
fi

# Never remove the stock launcher unless CrossPoint is installed and set as home.
if adb shell pm list packages com.crosspoint.launcher | grep -q crosspoint; then
  adb shell cmd package set-home-activity "$CROSSPOINT" >/dev/null
else
  echo "CrossPoint not installed; keeping the stock launcher."
  PKGS=$(echo "$PKGS" | grep -v com.wetao.elauncher)
fi

for p in $PKGS; do
  if adb shell pm list packages "$p" | grep -qx "package:$p"; then
    adb shell pm uninstall --user 0 "$p" >/dev/null && echo "removed  $p"
  fi
done

adb shell setprop debug.sf.gamma.gamma "$GAMMA"
echo "Done. Re-run after a reboot to reapply gamma. Undo: $0 --undo"
