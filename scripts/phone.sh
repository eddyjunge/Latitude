#!/usr/bin/env bash
# Build, install and inspect Latitude on a USB-connected phone.
#
#   scripts/phone.sh          build the optimized app, install it, launch it, show startup time
#   scripts/phone.sh log      live log of the camera pipeline (Ctrl+C to stop)
#   scripts/phone.sh pull     copy probe JSON and recorded clips into testclips/
#   scripts/phone.sh stop     stop the adb background service
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.eddyjunge.latitude
TAGS=(CameraController VideoConfig AndroidRuntime)

need_device() {
    if [ "$("$ADB" get-state 2>/dev/null || true)" = "device" ]; then return; fi
    "$ADB" devices -l
    cat <<'EOF'
No phone ready. Check:
  1. USB debugging is on (Settings > Additional settings > Developer options)
  2. The phone shows "Allow USB debugging?" -> tap Allow
  3. If the list above says "no permissions": run scripts/setup-usb.sh once
EOF
    exit 1
}

run() {
    need_device
    ./gradlew -q assembleRelease
    if ! "$ADB" install -r app/build/outputs/apk/release/app-release.apk; then
        echo 'Install blocked? On Xiaomi enable "Install via USB" in Developer options.'
        exit 1
    fi
    "$ADB" logcat -c
    "$ADB" shell am start -W -n "$PKG/.MainActivity" | grep -E 'Status|TotalTime|WaitTime' || true
    sleep 4
    "$ADB" logcat -d -s "${TAGS[@]}"
}

pull() {
    need_device
    mkdir -p testclips/probe testclips/clips
    "$ADB" pull /sdcard/Download/Latitude/. testclips/probe/ || true
    "$ADB" pull /sdcard/Movies/Latitude/. testclips/clips/ || true
    ls -la testclips/probe testclips/clips
}

case "${1:-run}" in
    run) run ;;
    log) need_device; "$ADB" logcat -s "${TAGS[@]}" ;;
    pull) pull ;;
    stop) "$ADB" kill-server ;;
    *) sed -n '2,8p' "$0"; exit 1 ;;
esac
