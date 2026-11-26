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
    echo "Run this test as an unprivileged host user; root bypasses the mode-555 fixture." >&2
    exit 2
fi

# Keep the backing directory outside ROOTFS (so fake-root permission
# emulation does not bypass host permissions), but on the same filesystem
# as ROOTFS/tmp (so the extension's renameat() does not fail with EXDEV).
ROOTFS=$(cd "$ROOTFS" && pwd -P)
[[ -d "$ROOTFS/tmp" ]] || {
    echo "ROOTFS/tmp is missing; cannot create the guest-side source file." >&2
    exit 2
}
ROOTFS_PARENT=$(dirname "$ROOTFS")
TMP_DIR=$(mktemp -d "$ROOTFS_PARENT/.droidrunner-link2symlink.XXXXXX") || {
    echo "Cannot create a temporary directory beside ROOTFS: $ROOTFS_PARENT" >&2
    exit 2
}
ROOTFS_TMP_DEVICE=$(stat -c '%d' "$ROOTFS/tmp")
L2S_DEVICE=$(stat -c '%d' "$TMP_DIR")
if [[ "$ROOTFS_TMP_DEVICE" != "$L2S_DEVICE" ]]; then
    echo "Cannot run regression test: ROOTFS/tmp and PROOT_L2S_DIR are on different filesystems (device $ROOTFS_TMP_DEVICE vs $L2S_DEVICE)." >&2
    rmdir "$TMP_DIR"
    exit 2
fi

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
# The directory must open successfully; the later renameat() must fail with
# EACCES. Mode 000 only tests open_l2s_directory(), not patched errno handling.
chmod 555 "$TMP_DIR/l2s-eacces"

run_case "fake-root non-writable PROOT_L2S_DIR" "$TMP_DIR/l2s-eacces" \
    "$EXPECTED_ERROR_EACCES"

run_case "fake-root missing PROOT_L2S_DIR" "$TMP_DIR/l2s-missing" \
    "$EXPECTED_ERROR_ENOENT"

echo "All link2symlink errno regression cases passed."
