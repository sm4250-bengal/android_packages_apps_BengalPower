#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 5 || $1 != /* || $2 != /* || $3 != /* || $4 != /* || $5 != /* ]]; then
    echo 'Usage: bash tests/check-platform-api.sh OWN_OUTPUT FRAMEWORK_JAR CORE_LIBART_JAR GENERATED_V1_JAVA BLUETOOTH_JAR (absolute paths)' >&2
    exit 64
fi
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
expected=7632eb34c08132faa4018d027cce86b367bba42a6108083af18186e521f9fdd3
read -r actual _ < <(sha256sum -- "$2")
[[ $actual == "$expected" ]] || { echo 'Exact24 framework jar pin mismatch' >&2; exit 1; }
libcore_expected=afba5762c39026cd85315b6f2007b856ced2d01776a1700ace32b09c9d6175e8
aidl_expected=3da6a2ee9f097f1c037b9d603f1e9543ebdf1c7c874636db3133bc62a4f2613b
bluetooth_expected=1af70e2d8427ddfbf70d58fef3f8d1ffb7309e2e99383aa87a24a2bc19fa210c
read -r bluetooth_actual _ < <(sha256sum -- "$5")
[[ $bluetooth_actual == "$bluetooth_expected" ]] || { echo 'Exact24 Bluetooth jar pin mismatch' >&2; exit 1; }
read -r libcore_actual _ < <(sha256sum -- "$3")
read -r aidl_actual _ < <(sha256sum -- "$4")
[[ $libcore_actual == "$libcore_expected" && $aidl_actual == "$aidl_expected" ]] || {
    echo 'Exact24 libcore/generated V1 Java pin mismatch' >&2; exit 1;
}
mkdir -p -- "$1"
api_output=$(mktemp -d "$1/platform-api.XXXXXX")
# No test doubles, Android build, input generation or framework code execution.
mapfile -t production < <(rg --files "$source_dir/src" -g '*.java' | sort)
javac --release 11 -proc:none -Xlint:all,-classfile -Werror -cp "$2:$3:$5" -d "$api_output" \
    "${production[@]}" "$4"
read -r after _ < <(sha256sum -- "$2")
[[ $after == "$expected" ]] || { echo 'Framework jar changed during compilation' >&2; exit 1; }
read -r libcore_after _ < <(sha256sum -- "$3")
read -r aidl_after _ < <(sha256sum -- "$4")
read -r bluetooth_after _ < <(sha256sum -- "$5")
[[ $bluetooth_after == "$bluetooth_expected" ]] || { echo 'Bluetooth jar changed during compilation' >&2; exit 1; }
[[ $libcore_after == "$libcore_expected" && $aidl_after == "$aidl_expected" ]] || {
    echo 'API input changed during compilation' >&2; exit 1;
}
echo "PASS exact24 platform API compile: SHA256=$actual"
echo "Libcore SHA256=$libcore_actual; generated V1 Java SHA256=$aidl_actual"
echo "Bluetooth SHA256=$bluetooth_actual"
echo "Compiled all ${#production[@]} production files plus genuine generated AIDL"
echo "Classes: $api_output"
