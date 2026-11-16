#!/usr/bin/env bash
# Renders the camera screen in the states the Figma design specifies, on an emulator sized like the
# reference phone (384 × 857 dp). Uses the app's fibShot test hook; it only sets screen state.
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
adb wait-for-device
adb shell wm size 1080x2410
adb shell wm density 450
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global hide_error_dialogs 1 || true
adb install -r "$APK"
adb shell pm grant com.lissy.fibcam android.permission.CAMERA
sleep 3
shoot() {
  local name="$1"; local extra="$2"
  adb shell am force-stop com.lissy.fibcam
  if [ -n "$extra" ]; then adb shell am start -W -n com.lissy.fibcam/.MainActivity -e fibShot "$extra" > /dev/null
  else adb shell am start -W -n com.lissy.fibcam/.MainActivity > /dev/null; fi
  sleep 6
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "shot $name"
}
shoot 00-launch ""
for g in OFF THIRDS PHI_GRID DIAGONALS DYNAMIC CENTRE MARKS; do shoot "compose-$g" "compose:$g"; done
for q in 0 1 2 3; do shoot "compose-SPIRAL-$q" "compose:SPIRAL:$q"; done
shoot compose-TRIANGLES-0 "compose:TRIANGLES:0"
shoot compose-TRIANGLES-1 "compose:TRIANGLES:1"
for m in OFF HOLGA PINHOLE TILT_SHIFT GRAINY_FILM CROSS_PROCESS CYANOTYPE DREAM_LENS INFRARED KINO EXPIRED REDSCALE SOLARISED INSTANT BLEACH_BYPASS; do
  shoot "look-$m" "look:$m:0"
done
shoot look-HOLGA-leaks-off "look:HOLGA:3:0"
shoot look-HOLGA-leaks-warm "look:HOLGA:3:1"
shoot look-HOLGA-depth-follow "look:HOLGA:5:1:follow"
shoot look-TILT_SHIFT-angle "look:TILT_SHIFT:3"
shoot look-EXPIRED-colour "look:EXPIRED:2:1"
shoot look-SOLARISED-contour "look:SOLARISED:3"
shoot exposure "exposure"
shoot settings "settings"
shoot header-active "double"
adb logcat -d -s AndroidRuntime:E > "$OUT/crash-log.txt" || true
