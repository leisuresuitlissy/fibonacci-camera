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
# Follow guide in Compose: every guide with it on, and the chosen anchor of multi-anchor guides
for g in THIRDS PHI_GRID DIAGONALS DYNAMIC CENTRE MARKS OFF; do shoot "follow-$g" "compose:$g:-:follow:1"; done
shoot follow-SPIRAL-3 "compose:SPIRAL:3:follow"
shoot follow-TRIANGLES-1 "compose:TRIANGLES:1:follow:1"
# Looks following the guide: Holga depth at the spiral eye; Tilt Shift along a triangle diagonal and through a thirds point
shoot look-HOLGA-follow-spiral1 "look:HOLGA:5:1:follow:SPIRAL:1"
shoot look-TILT-follow-triangle "look:TILT_SHIFT:1:-:follow:TRIANGLES:0:1"
shoot look-TILT-follow-thirds "look:TILT_SHIFT:3:-:follow:THIRDS:0:3"
shoot look-TILT-nofollow "look:TILT_SHIFT:1"
# Follow guide off: a tap moves the sharp region to the tapped point (Holga Depth)
adb shell am force-stop com.lissy.fibcam
adb shell am start -W -n com.lissy.fibcam/.MainActivity -e fibShot "look:HOLGA:5:1:-:SPIRAL:0" > /dev/null
sleep 6; adb shell input tap 820 1250; sleep 1.5
adb exec-out screencap -p > "$OUT/look-HOLGA-tap.png"; echo "shot look-HOLGA-tap"
shoot look-HOLGA-reset "look:HOLGA:0"
# Tilt Shift, Follow guide off: a tap low in the frame moves the band there
adb shell am force-stop com.lissy.fibcam
adb shell am start -W -n com.lissy.fibcam/.MainActivity -e fibShot "look:TILT_SHIFT:1:-:-:SPIRAL:0" > /dev/null
sleep 6; adb shell input tap 540 1350; sleep 1.5
adb exec-out screencap -p > "$OUT/look-TILT-tap.png"; echo "shot look-TILT-tap"
# The simulated effect on a detailed test image (no camera involved), guide drawn on top for comparison
for q in 0 1 2 3; do shoot "render-HOLGA-spiral$q" "render:HOLGA:SPIRAL:$q:0:follow"; done
shoot render-KINO-thirds0 "render:KINO:THIRDS:0:0:follow"
shoot render-KINO-thirds3 "render:KINO:THIRDS:0:3:follow"
shoot render-PINHOLE-centre "render:PINHOLE:CENTRE:0:0:follow"
shoot render-TILT-default "render:TILT_SHIFT:OFF:0:0:-"
shoot render-TILT-spiral "render:TILT_SHIFT:SPIRAL:2:0:follow"
shoot render-TILT-triangleB "render:TILT_SHIFT:TRIANGLES:1:1:follow"
shoot render-TILT-thirds3 "render:TILT_SHIFT:THIRDS:0:3:follow"
shoot render-TILT-focus20 "render:TILT_SHIFT:OFF:0:0:-:1=20"
shoot render-TILT-width0 "render:TILT_SHIFT:OFF:0:0:-:2=0"
shoot render-TILT-width100 "render:TILT_SHIFT:OFF:0:0:-:2=100"
shoot render-TILT-angle100 "render:TILT_SHIFT:OFF:0:0:-:3=100"
shoot render-TILT-feather0 "render:TILT_SHIFT:OFF:0:0:-:4=0"
shoot render-TILT-feather100 "render:TILT_SHIFT:OFF:0:0:-:4=100"
shoot render-TILT-depth0 "render:TILT_SHIFT:OFF:0:0:-:7=0"
shoot render-TILT-depth100 "render:TILT_SHIFT:OFF:0:0:-:7=100"
shoot render-TILT-intensity0 "render:TILT_SHIFT:OFF:0:0:-:0=0"
shoot render-HOLGA-vignette100 "render:HOLGA:OFF:0:0:-:8=100"
shoot render-HOLGA-softness100 "render:HOLGA:OFF:0:0:-:2=100"
shoot render-GRAINY-sat0 "render:GRAINY_FILM:OFF:0:0:-"
shoot render-INSTANT-default "render:INSTANT:OFF:0:0:-"
shoot render-INSTANT-max "render:INSTANT:OFF:0:0:-:0=100,1=100,2=100,6=100"
shoot render-INSTANT-cool "render:INSTANT:OFF:0:0:-:0=100,1=0"
shoot render-GRAINY-sat50 "render:GRAINY_FILM:OFF:0:0:-:3=50"
shoot render-GRAINY-sat100 "render:GRAINY_FILM:OFF:0:0:-:3=100"
shoot render-HOLGA-full "render:HOLGA:OFF:0:0:-:0=100"
shoot render-HOLGA-sat0 "render:HOLGA:OFF:0:0:-:0=100,6=0"
shoot render-HOLGA-plain0 "render:HOLGA:OFF:0:0:-:8=0,2=0"
shoot render-KINO-vig0 "render:KINO:OFF:0:0:-:8=0"
shoot render-KINO-vig100 "render:KINO:OFF:0:0:-:8=100"

shoot exposure "exposure"
shoot settings "settings"
shoot header-active "double"
adb logcat -d -s AndroidRuntime:E > "$OUT/crash-log.txt" || true
