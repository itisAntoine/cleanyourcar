#!/usr/bin/env bash
# Builds CleanYourCar (:app) and Navigation Bridge (:navbridge), checks the <512 KB APK size gate,
# installs them onto the head unit over ADB, grants overlay/settings permissions, and verifies Home launch.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "Building Release APKs (:app and :navbridge)..."
./gradlew :app:assembleRelease :navbridge:assembleRelease

APP_APK="app/build/outputs/apk/release/app-release.apk"
BRIDGE_APK="addons/nav-bridge/build/outputs/apk/release/navbridge-release.apk"

if [ ! -f "$APP_APK" ]; then
    echo "Error: Release APK not found at $APP_APK" >&2
    exit 1
fi

if [[ "$(uname)" == "Darwin" ]]; then
    APK_BYTES=$(stat -f %z "$APP_APK")
else
    APK_BYTES=$(stat -c %s "$APP_APK")
fi

MAX_BYTES=524288
echo "CleanYourCar APK size: ${APK_BYTES} bytes (limit: ${MAX_BYTES} bytes)"
if [ "$APK_BYTES" -ge "$MAX_BYTES" ]; then
    echo "Error: APK exceeds 512 KB size gate (${APK_BYTES} >= ${MAX_BYTES})" >&2
    exit 1
fi

if [ "${BUILD_ONLY:-0}" = "1" ]; then
    echo "BUILD_ONLY=1 set; skipping ADB installation."
    exit 0
fi

ADB_BIN="${ADB:-}"
if [ -z "$ADB_BIN" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB_BIN="adb"
    elif [ -x "$HOME/Downloads/platform-tools/adb" ]; then
        ADB_BIN="$HOME/Downloads/platform-tools/adb"
    elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
    else
        echo "Error: adb not found. Set ADB=/path/to/adb." >&2
        exit 1
    fi
fi

ADB_ARGS=()
if [ -n "${CAR_HOST:-}" ]; then
    "$ADB_BIN" connect "${CAR_HOST}:5555"
    ADB_ARGS=("-s" "${CAR_HOST}:5555")
fi

echo "Installing CleanYourCar ($APP_APK)..."
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} install -r "$APP_APK"

if [ -f "$BRIDGE_APK" ]; then
    echo "Installing Navigation Bridge Add-on ($BRIDGE_APK)..."
    "$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} install -r "$BRIDGE_APK"
fi

echo "Granting overlay & runtime permissions (Android 6.0 / API 23 compatible)..."
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "appops set com.cleanyourcar.launcher SYSTEM_ALERT_WINDOW allow" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "appops set com.cleanyourcar.launcher WRITE_SETTINGS allow" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm grant com.cleanyourcar.launcher android.permission.ACCESS_FINE_LOCATION" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm grant com.cleanyourcar.launcher android.permission.ACCESS_COARSE_LOCATION" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm grant com.cleanyourcar.launcher android.permission.READ_EXTERNAL_STORAGE" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm grant com.cleanyourcar.navbridge android.permission.ACCESS_FINE_LOCATION" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm grant com.cleanyourcar.navbridge android.permission.ACCESS_COARSE_LOCATION" || true

# Enable BackActionService so the [↩ Back] button in Live Google Maps Mode dispatches system Back
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "settings put secure enabled_accessibility_services com.cleanyourcar.launcher/com.cleanyourcar.launcher.BackActionService" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "settings put secure accessibility_enabled 1" || true

echo "Starting NavBridgeService and CleanYourCar MainActivity..."
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "am startservice -n com.cleanyourcar.navbridge/.NavBridgeService" || true
"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "am start -n com.cleanyourcar.launcher/.MainActivity"
sleep 2

FOCUSED=$("$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedApp|topResumedActivity'" || true)
echo "Focused activity: $FOCUSED"

if [[ "$FOCUSED" == *"com.cleanyourcar.launcher"* ]]; then
    echo "Verified CleanYourCar is running in foreground."
    if [ "${DISABLE_STOCK_LAUNCHER:-0}" = "1" ]; then
        echo "DISABLE_STOCK_LAUNCHER=1: Disabling stock com.android.launcher3..."
        "$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm disable-user com.android.launcher3"
    else
        echo "Tip: Press Home button on head unit and choose CleanYourCar -> Always (or re-run with DISABLE_STOCK_LAUNCHER=1)."
    fi
else
    echo "Warning: CleanYourCar was not detected in foreground; leaving stock launcher untouched." >&2
    exit 1
fi
