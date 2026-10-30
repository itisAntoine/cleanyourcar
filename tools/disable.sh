#!/usr/bin/env bash
# Safely disables bloatware packages on an Android head unit using `pm disable-user`.
# Refuses to run if any protected system or vehicle hardware package appears in the list.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: [CAR_HOST=<ip>] $0 <path/to/debloat.txt> [--dry-run]" >&2
    exit 1
fi

LIST_FILE="$1"
DRY_RUN="${2:-}"

if [ ! -f "$LIST_FILE" ]; then
    echo "Error: Debloat file not found: $LIST_FILE" >&2
    exit 1
fi

PROTECTED_PACKAGES=(
    "android"
    "com.android.systemui"
    "com.android.settings"
    "com.android.phone"
    "com.android.server.telecom"
    "com.android.packageinstaller"
    "com.android.defcontainer"
    "com.android.inputmethod.latin"
    "com.android.shell"
    "com.android.providers.settings"
    "com.android.providers.media"
    "com.android.externalstorage"
    "com.android.location.fused"
    "com.autochips.android.backcar"
    "com.autochips.bluetooth"
    "com.android.bluetooth"
    "com.xygala.canbus"
    "com.xygala.pvcanset"
    "com.xy.spl"
    "com.xy.brakecheck"
    "com.xyauto.Settings"
    "com.fvsm.camera"
    "com.acloud.stub.localmusic"
    "com.acloud.stub.localradio"
    "com.mediatek.usbchecker"
    "com.google.android.apps.maps"
    "com.google.android.gms"
    "com.cleanyourcar.launcher"
    "com.cleanyourcar.navbridge"
)

is_protected() {
    local candidate="$1"
    for p in "${PROTECTED_PACKAGES[@]}"; do
        if [ "$candidate" = "$p" ]; then
            return 0
        fi
    done
    return 1
}

PACKAGES=()
while IFS= read -r raw_line || [ -n "$raw_line" ]; do
    line="$(printf '%s' "$raw_line" | tr -d '\r' | sed 's/#.*//;s/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [ -z "$line" ]; then
        continue
    fi
    if ! [[ "$line" =~ ^[A-Za-z0-9._]+$ ]]; then
        echo "Error: Invalid package name token '$line' in $LIST_FILE." >&2
        exit 2
    fi
    if is_protected "$line"; then
        echo "CRITICAL SAFETY REFUSAL: Protected package '$line' found in $LIST_FILE. Aborting without changes." >&2
        exit 2
    fi
    PACKAGES+=("$line")
done < "$LIST_FILE"

if [ "${#PACKAGES[@]}" -eq 0 ]; then
    echo "No packages to disable in $LIST_FILE."
    exit 0
fi

if [ "$DRY_RUN" = "--dry-run" ]; then
    echo "Dry-run passed: ${#PACKAGES[@]} packages validated against safety guard."
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
    "$ADB_BIN" connect "${CAR_HOST}:5555" >/dev/null
    ADB_ARGS=("-s" "${CAR_HOST}:5555")
fi

RECORD_DIR="$HOME/.cleanyourcar"
mkdir -p "$RECORD_DIR"
RECORD_FILE="$RECORD_DIR/disabled-$(date +%Y%m%d-%H%M%S).txt"

echo "Recording disabled packages to $RECORD_FILE"
for pkg in "${PACKAGES[@]}"; do
    echo "Disabling $pkg ..."
    if "$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm disable-user $pkg" </dev/null; then
        echo "$pkg" >> "$RECORD_FILE"
    fi
done

echo "Done. Disabled ${#PACKAGES[@]} packages. Undo anytime with: ./tools/restore.sh $RECORD_FILE"
