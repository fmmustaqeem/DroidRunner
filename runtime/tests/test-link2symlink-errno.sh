#!/usr/bin/env bash
set -euo pipefail

: "${PROOT:?Set PROOT to the built Android PRoot executable}"
: "${ROOTFS:?Set ROOTFS to the Ubuntu rootfs directory}"

if [[ ! -x "$PROOT" ]]; then
    echo "PROOT is not executable: $PROOT" >&2
    exit 2
fi
if [[ ! -d "$ROOTFS" ]]; then
    echo "ROOTFS is not a directory: $ROOTFS" >&2
    exit 2
fi
if [[ "$EUID" -eq 0 ]]; then
    echo "Run this test as an unprivileged host user; root bypasses the mode-000 fixture." >&2
    exit 2
fi

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/droidrunner-link2symlink.XXXXXX")
GUEST_SOURCE="/tmp/droidrunner-link2symlink-source-$BASHPID"
GUEST_DEST="/tmp/droidrunner-link2symlink-destination-$BASHPID"
cleanup() {
    chmod 700 "$TMP_DIR/l2s-eacces" 2>/dev/null || true
    PROOT_L2S_DIR="$TMP_DIR/l2s-missing" "$PROOT" -0 -l -r "$ROOTFS" -w /tmp \
        /usr/bin/rm -f "$GUEST_SOURCE" "$GUEST_DEST" >/dev/null 2>&1 || true
    rm -rf -- "$TMP_DIR"
}
trap cleanup EXIT

EXPECTED_ERROR_EACCES="Permission denied"
EXPECTED_ERROR_ENOENT="No such file or directory"

run_case() {
    local name=$1
    local l2s_dir=$2
    local expected_error=$3
    local output
    local status=0

    echo "=== $name (expected: $expected_error) ==="
    output=$(PROOT_L2S_DIR="$l2s_dir" LC_ALL=C "$PROOT" -0 -l -r "$ROOTFS" -w /tmp \
        /usr/bin/ln "$GUEST_SOURCE" "$GUEST_DEST" 2>&1) || status=$?
    printf '%s\n' "$output"
    if [[ "$status" -ne 0 && "$output" == *"$expected_error"* ]]; then
        echo "PASS: $name"
    else
        echo "FAIL: $name (exit $status)" >&2
        return 1
    fi
}

# Prepare the guest source file without relying on Python or a compiler.
"$PROOT" -0 -l -r "$ROOTFS" -w /tmp /usr/bin/touch "$GUEST_SOURCE"

mkdir "$TMP_DIR/l2s-eacces"
chmod 000 "$TMP_DIR/l2s-eacces"

run_case "fake-root inaccessible PROOT_L2S_DIR" "$TMP_DIR/l2s-eacces" \
    "$EXPECTED_ERROR_EACCES"

run_case "fake-root missing PROOT_L2S_DIR" "$TMP_DIR/l2s-missing" \
    "$EXPECTED_ERROR_ENOENT"

echo "All link2symlink errno regression cases passed."
