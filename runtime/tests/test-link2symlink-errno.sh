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

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/droidrunner-link2symlink.XXXXXX")
cleanup() {
    chmod 700 "$TMP_DIR/l2s-eacces" 2>/dev/null || true
    rm -rf -- "$TMP_DIR"
}
trap cleanup EXIT

PYTHON_TEST='import os, sys, tempfile
expected = int(sys.argv[1])
with tempfile.TemporaryDirectory(prefix="droidrunner-link2symlink-") as directory:
    source = os.path.join(directory, "source")
    destination = os.path.join(directory, "destination")
    with open(source, "w", encoding="utf-8") as stream:
        stream.write("fixture")
    try:
        os.link(source, destination)
    except OSError as error:
        print("link_errno=%d" % error.errno)
        sys.exit(0 if error.errno == expected else 1)
    print("link unexpectedly succeeded")
    sys.exit(2)'

run_case() {
    local name=$1
    local l2s_dir=$2
    local expected_errno=$3
    local output

    echo "=== $name (expected errno $expected_errno) ==="
    if output=$(PROOT_L2S_DIR="$l2s_dir" "$PROOT" -0 -l -r "$ROOTFS" -w /tmp \
        /usr/bin/python3 -c "$PYTHON_TEST" "$expected_errno" 2>&1); then
        printf '%s\n' "$output"
        echo "PASS: $name"
    else
        local status=$?
        printf '%s\n' "$output" >&2
        echo "FAIL: $name (exit $status)" >&2
        return "$status"
    fi
}

mkdir "$TMP_DIR/l2s-eacces"
chmod 000 "$TMP_DIR/l2s-eacces"
run_case "fake-root inaccessible PROOT_L2S_DIR" "$TMP_DIR/l2s-eacces" 13

run_case "fake-root missing PROOT_L2S_DIR" "$TMP_DIR/l2s-missing" 2

echo "All link2symlink errno propagation cases passed."
