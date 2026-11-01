#!/usr/bin/env bash
# Builds CleanYourCar (:app), enforces the APK size gate, and (unless BUILD_ONLY=1) creates an
# explicit, target-bound install PLAN. It never installs, grants permissions, changes settings,
# starts services, or touches the launcher; applying is a separate step (tools/car.py apply).
#   BUILD_ONLY=1 tools/build-and-install.sh                 # build + size gate only; no ADB, no plan
#   tools/build-and-install.sh --serial <transport> --user <id> --profile <profile.json> \
#       --output <private-dir/plan.json> [--with-bridge] [--records <dir>] [--adb <path>]
# The optional Navigation Bridge is built and planned only with --with-bridge.
set -euo pipefail

TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$TOOLS_DIR/.." && pwd)"
cd "$ROOT_DIR"

if [ -n "${CAR_HOST:-}" ]; then
    echo "Error: CAR_HOST is no longer supported. Select one device with --serial <transport>." >&2
    exit 2
fi

SERIAL=""
USER_ID=""
PROFILE=""
OUTPUT=""
WITH_BRIDGE=0
PASSTHROUGH=()
while [ $# -gt 0 ]; do
    case "$1" in
        --confirm|--confirm=*|--yes|-y|--apply|--force|--auto-confirm)
            echo "Error: this wrapper never confirms or applies; review the plan, then run car.py apply yourself ($1 refused)." >&2
            exit 2 ;;
        --with-bridge) WITH_BRIDGE=1; shift ;;
        --serial|--user|--profile|--output|--records|--adb|--app-version-code)
            if [ $# -lt 2 ]; then
                echo "Error: $1 needs a value." >&2
                exit 2
            fi
            case "$1" in
                --serial) SERIAL="$2" ;;
                --user) USER_ID="$2" ;;
                --profile) PROFILE="$2" ;;
                --output) OUTPUT="$2" ;;
                *) PASSTHROUGH+=("$1" "$2") ;;
            esac
            shift 2 ;;
        *)
            echo "Error: unknown argument '$1'." >&2
            exit 2 ;;
    esac
done

if [ "${BUILD_ONLY:-0}" != "1" ]; then
    if [ -z "$SERIAL" ] || ! [[ "$USER_ID" =~ ^[0-9]+$ ]] || [ -z "$PROFILE" ] || [ -z "$OUTPUT" ]; then
        echo "Usage: $0 --serial <transport> --user <id> --profile <profile.json> --output <plan.json> [--with-bridge]" >&2
        echo "       BUILD_ONLY=1 $0   (build and size gate only)" >&2
        exit 2
    fi
fi

APP_APK="app/build/outputs/apk/release/app-release.apk"
BRIDGE_APK="addons/nav-bridge/build/outputs/apk/release/navbridge-release.apk"
rm -f "$APP_APK" "$BRIDGE_APK"

TASKS=(":app:assembleRelease")
if [ "$WITH_BRIDGE" = "1" ]; then
    TASKS+=(":navbridge:assembleRelease")
fi
echo "Building Release APK(s): ${TASKS[*]}"
./gradlew "${TASKS[@]}"

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
    echo "BUILD_ONLY=1 set; build and size gate passed. No ADB was used."
    exit 0
fi

plan_install() {
    local apk="$1" plan="$2" metadata="${2%.json}-apk-metadata.json"
    python3 "$TOOLS_DIR/car.py" apk-metadata --apk "$apk" --output "$metadata"
    python3 "$TOOLS_DIR/car.py" plan-install --serial "$SERIAL" --user "$USER_ID" \
        --profile "$PROFILE" --apk-metadata "$metadata" --output "$plan" \
        ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}
}

plan_install "$APP_APK" "$OUTPUT"
if [ "$WITH_BRIDGE" = "1" ]; then
    if [ ! -f "$BRIDGE_APK" ]; then
        echo "Error: Navigation Bridge APK not found at $BRIDGE_APK" >&2
        exit 1
    fi
    plan_install "$BRIDGE_APK" "${OUTPUT%.json}-bridge.json"
fi

echo "Install plan(s) created. Nothing was installed. Review each preview, then apply separately with:"
echo "  python3 tools/car.py apply --serial <transport> --user <id> --plan <plan.json> --confirm <hash>"
