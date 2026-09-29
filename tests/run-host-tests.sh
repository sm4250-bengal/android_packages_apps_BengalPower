#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 1 || $1 != /* ]]; then
    echo 'Usage: bash tests/run-host-tests.sh /absolute/owned/scratch/output' >&2
    exit 64
fi
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p -- "$1"
test_output=$(mktemp -d "$1/host-tests.XXXXXX")
mapfile -t fd_doubles < <(rg --files "$source_dir/tests/fd-doubles" -g '*.java' | sort)
javac --release 11 -Xlint:all -Werror -d "$test_output" \
    "${fd_doubles[@]}" \
    "$source_dir/src/com/bengal/power/AndroidAccPowerChannel.java" \
    "$source_dir/src/com/bengal/power/AccPowerCoordinator.java" \
    "$source_dir/src/com/bengal/power/UnavailablePowerReadiness.java" \
    "$source_dir/src/com/bengal/power/AndroidOwnedPreparation.java" \
    "$source_dir/src/com/bengal/power/InstalledDeepCapability.java" \
    "$source_dir/src/com/bengal/power/AndroidPowerHost.java" \
    "$source_dir/src/com/bengal/power/AndroidPreparationTimer.java" \
    "$source_dir/src/com/bengal/power/BacpPacket.java" \
    "$source_dir/src/com/bengal/power/BacpPeerAuth.java" \
    "$source_dir/src/com/bengal/power/BacpRecordReader.java" \
    "$source_dir/src/com/bengal/power/AccPowerSession.java" \
    "$source_dir/src/com/bengal/power/BacpChannel.java" \
    "$source_dir/tests/android/os/SystemClock.java" \
    "$source_dir/tests/android/os/PowerManager.java" \
    "$source_dir/tests/com/bengal/power/AndroidAdaptersTest.java" \
    "$source_dir/tests/com/bengal/power/AccPowerCoordinatorTest.java" \
    "$source_dir/tests/com/bengal/power/BacpSessionTest.java" \
    "$source_dir/tests/com/bengal/power/BacpChannelTest.java" \
    "$source_dir/tests/com/bengal/power/AndroidFdLifetimeTest.java" \
    "$source_dir/tests/com/bengal/power/UnavailablePowerReadinessTest.java" \
    "$source_dir/tests/com/bengal/power/AndroidOwnedPreparationTest.java" \
    "$source_dir/tests/com/bengal/power/InstalledDeepCapabilityTest.java"
java -ea -cp "$test_output" com.bengal.power.InstalledDeepCapabilityTest "$test_output"
java -ea -cp "$test_output" com.bengal.power.AndroidOwnedPreparationTest
java -ea -cp "$test_output" com.bengal.power.UnavailablePowerReadinessTest
java -ea -cp "$test_output" com.bengal.power.AccPowerCoordinatorTest
java -ea -cp "$test_output" com.bengal.power.AndroidAdaptersTest
golden="$source_dir/../../../hardware/xyauto/hud/tests/acc-power-golden.txt"
read -r actual _ < <(sha256sum -- "$golden")
[[ $actual == 776f62f9adcb5b221eaf552366dd84dd7b9af005434658494e97f8de73a014ed ]] || {
    echo 'Immutable BACP golden pin mismatch' >&2; exit 1;
}
java -ea -cp "$test_output" com.bengal.power.BacpSessionTest "$golden"
java -ea -cp "$test_output" com.bengal.power.BacpChannelTest
java --add-opens java.base/java.io=ALL-UNNAMED -ea -cp "$test_output" com.bengal.power.AndroidFdLifetimeTest
echo "Classes: $test_output"
