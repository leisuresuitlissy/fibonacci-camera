#!/usr/bin/env bash
# Pushes the fixtures and render list to the app, runs the batch, pulls the JPEGs back.
set -u
APK="$1"; LAB="$2"; OUT="$3"; mkdir -p "$OUT"
PKG=com.lissy.fibcam
adb wait-for-device
adb shell settings put global hide_error_dialogs 1 || true
adb install -r "$APK"
adb shell pm grant $PKG android.permission.CAMERA
adb shell am start -W -n $PKG/.MainActivity > /dev/null; sleep 5; adb shell am force-stop $PKG
D=/sdcard/Android/data/$PKG/files/lab
adb shell mkdir -p $D
for f in "$LAB"/*; do adb push "$f" $D/ > /dev/null; done
adb shell ls $D | head -40
adb shell am start -W -n $PKG/.MainActivity -e fibShot batch > /dev/null
total=$(grep -cv '^#' "$LAB/spec.txt")
for i in $(seq 1 600); do
  if adb shell ls $D/done > /dev/null 2>&1; then echo "done"; break; fi
  n=$(adb shell ls $D/out 2>/dev/null | wc -l); echo "rendered $n / $total"; sleep 6
done
adb pull "$D/out/." "$OUT/" > /dev/null
adb logcat -d -s AndroidRuntime:E > "$OUT/crash.txt" || true
ls "$OUT" | head
