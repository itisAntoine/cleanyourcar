#!/usr/bin/env bash
# Re-enables packages from a debloat list or backup record and re-enables stock launcher3.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: [CAR_HOST=<ip>] $0 <path/to/debloat-or-record.txt>" >&2
    exit 1
fi

LIST_FILE="$1"
if [ ! -f "$LIST_FILE" ]; then
    echo "Error: File not found: $LIST_FILE" >&2
    exit 1
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

"$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm enable com.android.launcher3" </dev/null || true

while IFS= read -r raw_line || [ -n "$raw_line" ]; do
    line="$(printf '%s' "$raw_line" | tr -d '\r' | sed 's/#.*//;s/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [ -z "$line" ]; then
        continue
    fi
    if ! [[ "$line" =~ ^[A-Za-z0-9._]+$ ]]; then
        continue
    fi
    echo "Re-enabling $line ..."
    "$ADB_BIN" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell "pm enable $line" </dev/null || true
done < "$LIST_FILE"

echo "Restore complete."
