#!/usr/bin/env bash
set -euo pipefail
# CI uses a separate audit application without INTERNET permission or personal data.
proof=app/build/reports/android-device
mkdir -p "$proof"
collect() {
  adb logcat -d > "$proof/logcat.txt" 2>&1 || true
  adb shell dumpsys connectivity > "$proof/connectivity-after.txt" 2>&1 || true
  adb pull /sdcard/Android/data/ai.tennisvision.camera.audit/files/offline-proof "$proof/" || true
}
trap collect EXIT
# Google APIs images also expose virtual Ethernet. Wi-Fi/data alone leave that
# network connected, so disable its links on this disposable emulator as well.
serial=$(adb get-serialno)
if [[ "$serial" != emulator-* ]]; then
  echo "This offline CI harness requires an emulator, not a personal phone." >&2
  exit 1
fi
adb root
adb wait-for-device
adb shell dumpsys connectivity > "$proof/connectivity-before.txt"
adb shell svc wifi disable
adb shell svc data disable
adb shell 'for interface in /sys/class/net/eth*; do if [ -e "$interface" ]; then ip link set "${interface##*/}" down; fi; done'
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent 224
adb shell wm dismiss-keyguard
adb shell getprop ro.build.version.release > "$proof/android-version.txt"
adb shell getprop ro.product.model > "$proof/model.txt"
adb logcat -c
# UTP's connectedAndroidTest task uninstalls its APKs after the suite, deleting
# screenshots and preventing the separate permission tests from launching.
# Install the already-built isolated APKs and retain them until evidence is pulled.
adb install -r app/build/outputs/apk/audit/app-audit.apk
adb install -r app/build/outputs/apk/androidTest/audit/app-audit-androidTest.apk
set +e
adb shell am instrument -w -r -e class ai.tennisvision.camera.LocalTrainingTest \
  ai.tennisvision.camera.audit.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$proof/device-tests.log"
result=${PIPESTATUS[0]}
set -e
if ! grep -Eq '^OK \(8 tests\)' "$proof/device-tests.log"; then result=1; fi
for method in allowingCameraActuallyProducesFrames deniedCameraNeverPretendsReadyAndCanOpenSettings; do
  adb shell am force-stop ai.tennisvision.camera.audit
  adb shell pm revoke ai.tennisvision.camera.audit android.permission.CAMERA
  adb shell pm clear-permission-flags ai.tennisvision.camera.audit android.permission.CAMERA user-set user-fixed
  set +e
  adb shell am instrument -w -r -e class "ai.tennisvision.camera.LocalPermissionTest#$method" \
    ai.tennisvision.camera.audit.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$proof/$method.log"
  permission_result=${PIPESTATUS[0]}
  set -e
  if [[ "$permission_result" -ne 0 ]]; then result=1; fi
  if ! grep -Eq '^OK \(1 test\)' "$proof/$method.log"; then result=1; fi
done
exit "$result"
