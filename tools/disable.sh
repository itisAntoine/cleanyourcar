#!/usr/bin/env bash
# Creates a private, target-bound debloat PLAN with the shared tool. It never applies anything.
#   disable.sh <list.txt> --serial <transport> --user <id> --profile <profile.json> \
#       --policy <policy.json> --output <private-dir/plan.json> [--records <dir>] [--adb <path>]
#   disable.sh <list.txt> --dry-run     # static list syntax check only; no device is contacted
# Applying is a separate, explicitly confirmed step: tools/car.py apply.
set -euo pipefail

TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -n "${CAR_HOST:-}" ]; then
    echo "Error: CAR_HOST is no longer supported. Select one device with --serial <transport>." >&2
    exit 2
fi
for arg in "$@"; do
    case "$arg" in
        --confirm|--confirm=*|--yes|-y|--apply|--force|--auto-confirm)
            echo "Error: this wrapper never confirms or applies; review the plan, then run car.py apply yourself ($arg refused)." >&2
            exit 2 ;;
    esac
done

if [ $# -lt 1 ] || [[ "$1" == -* ]]; then
    echo "Usage: $0 <list.txt> --serial <transport> --user <id> --profile <profile.json> --policy <policy.json> --output <plan.json>" >&2
    echo "       $0 <list.txt> --dry-run   (static check only)" >&2
    exit 2
fi
LIST_FILE="$1"
shift
if [ ! -f "$LIST_FILE" ]; then
    echo "Error: Debloat file not found: $LIST_FILE" >&2
    exit 1
fi

if [ $# -eq 1 ] && [ "$1" = "--dry-run" ]; then
    exec python3 "$TOOLS_DIR/car.py" validate-list "$LIST_FILE"
fi

exec python3 "$TOOLS_DIR/car.py" plan-debloat --candidates "$LIST_FILE" "$@"
