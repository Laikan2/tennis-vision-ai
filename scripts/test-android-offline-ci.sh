#!/usr/bin/env bash
set -euo pipefail
# CI uses a separate audit application without INTERNET permission or personal data.
proof=app/build/reports/android-device
mkdir -p "$proof"
collect() {
  adb logcat -d > "$proof/logcat.txt" 2>&1 || true
  adb pull /sdcard/Android/data/ai.tennisvision.camera.audit/files/offline-proof "$proof/" || true
}
trap collect EXIT
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent 224
adb shell wm dismiss-keyguard
adb shell getprop ro.build.version.release > "$proof/android-version.txt"
adb shell getprop ro.product.model > "$proof/model.txt"
adb logcat -c
set +e
bash gradlew -PauditDevice=true connectedAuditAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.tennisvision.camera.LocalTrainingTest \
  --console=plain 2>&1 | tee "$proof/device-tests.log"
result=${PIPESTATUS[0]}
set -e
for method in allowingCameraActuallyProducesFrames deniedCameraNeverPretendsReadyAndCanOpenSettings; do
  adb shell am force-stop ai.tennisvision.camera.audit
  adb shell pm revoke ai.tennisvision.camera.audit android.permission.CAMERA
  adb shell pm clear-permission-flags ai.tennisvision.camera.audit android.permission.CAMERA user-set user-fixed
  adb shell am instrument -w -r -e class "ai.tennisvision.camera.LocalPermissionTest#$method" \
    ai.tennisvision.camera.audit.test/androidx.test.runner.AndroidJUnitRunner | tee "$proof/$method.log"
  if ! grep -Eq '^OK \(1 test\)' "$proof/$method.log"; then result=1; fi
done
exit "$result"
