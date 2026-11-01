#!/usr/bin/env bash
# Creates a private, target-bound restore PLAN from a recorded operation. It never applies anything.
#   restore.sh <operation-id> --serial <transport> --user <id> --output <private-dir/plan.json> \
#       [--records <dir>] [--adb <path>]
# Debloat lists and record files are not accepted: restore only returns recorded state and never
# blindly enables a list. Applying is a separate, explicitly confirmed step: tools/car.py apply.
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

if [ $# -lt 1 ]; then
    echo "Usage: $0 <operation-id> --serial <transport> --user <id> --output <plan.json>" >&2
    exit 2
fi
OPERATION_ID="$1"
shift
if [ -e "$OPERATION_ID" ] || ! [[ "$OPERATION_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$ ]]; then
    echo "Error: '$OPERATION_ID' is not a recorded operation ID. Restoring from a debloat list or file is no longer supported." >&2
    exit 2
fi

exec python3 "$TOOLS_DIR/car.py" plan-restore --operation-id "$OPERATION_ID" "$@"
