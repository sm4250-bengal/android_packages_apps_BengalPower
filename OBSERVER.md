# Product-selected ACC owner and default observation mode

This integration reuses the four-tree ACC census and corrected BACP bootstrap
recorded in README and docs/research/2026-09-09-acc-power-bootstrap.md. It adds
an Android owner, not a second MCU/USB-GPIO/audio-hardware writer.

`BENGAL_ACC_POWER_CHANNEL=1` selects the dedicated app, native compile feature,
vendor property, VINTF declaration/matrix and system_ext/vendor policy together.
The default is off. New code is compiled from source against the actual frozen
V1 API; the property is not an authentication credential.

Android's system persistent-app startup owns the process. It uses its own user0
application UID and dedicated `bengal_power_app` domain; no shared UID, exported
component, boot receiver or activity is added. The selected APK now requests
DEVICE_POWER, WAKE_LOCK, MOUNT_UNMOUNT_FILESYSTEMS, BLUETOOTH_CONNECT and
BLUETOOTH_PRIVILEGED, including when running observation mode. It remains
platform-signed and own-UID; root must verify actual permission grants and the
app-domain power-service policy before opting in. No FM/button UID gains power.
Exact24 DEVICE_POWER is signature|role (or signature|role|module with its flag),
not a privileged-only permission; platform signing is the intended grant path,
not adding a speculative privapp allowlist entry. WAKE_LOCK is normal permission.
MOUNT_UNMOUNT_FILESYSTEMS is signature|privileged and MUST be allowlisted in
system_ext/etc/permissions/privapp-permissions-com.bengal.power.xml. Platform
signing does not exempt this system privileged package from the boot check.
The APK requires the matching prebuilt_etc module so it cannot be omitted from
future product installs. D3-0137 exposed this as a fatal system_server loop.
BLUETOOTH_PRIVILEGED is also in that same-partition allowlist; BLUETOOTH_CONNECT
has a system_ext default-runtime-permission exception for this persistent owner.
Neither permission starts or changes Bluetooth in observation mode.
The process owns one channel and one bounded deadline scheduler. Three finite
startup open opportunities accommodate service ordering; an occupied or stuck
owner is never replaced. No periodic retry or wake alarm runs while idle.
After later daemon death, reconnect is deliberately not automatic in this slice.

The observer supplies `physicalPolicy=false`, an unavailable deep capability,
false participants and throwing power/preparation adapters. It never constructs
AndroidPowerHost. Thus observation mode performs no READY, wakelock or sleep call.
An accepted packet is logged only after protocol/session validation; this is
not proof of enforced sender identity on the permissive bring-up product.
Log tag `BengalPower` records kind/phase/sequence/generation/deadline. Event kinds
2/4/5/7/9 mean SNAPSHOT/GRACE/PREPARE/FINAL_RESULT/RESUME. FINAL_RESULT phase1
is successful host write, not MCU receipt or permission to sleep.

Persistent process lifetime deliberately avoids replacing a coordinator during
uncertain cleanup or a stuck Binder submission. Android process death reclaims
local descriptors and threads; it does not prove external volume rollback.

## Default-off boot policy wiring

`persist.sys.bengal.acc_power_policy=true` is sampled once at process startup.
Absent/false retains the observer above, including forbidden power adapters.
True constructs the accepted AndroidPowerHost and Timer with one bounded
preparation lane, one bounded participant lane and one bounded power lane. These live with the process; no
shutdown can discard queued compensating wake. The existing coordinator and
transport still own cycle/session/deadline validation, cancellation and wake
ordering. There is no new service, protocol, raw sysfs write or suspend bypass.

Opt-in now installs actual `AndroidOwnedPreparation` and `InstalledDeepCapability`.
Only authenticated live GRACE stages Android preparation; snapshots remain a
floor, never history replay. Optional Participants.grace/cancelGrace hooks carry
the existing opaque Session and generation. The exact matching PREPARE adopts
staged completion; no new wire message or fabricated Cycle is introduced.
Early RESUME, disconnect/death, malformed input and a 30-second grace deadline
cancel staging even when PREPARE never arrives. FINAL_RESULT remains host-write
completion only; READY + fresh matching native AUTHORIZE remain mandatory.

Android preparation requests AUDIOFOCUS_GAIN_TRANSIENT (no delayed acceptance)
and holds only its own focus request. Grant proves framework focus ownership
and loss-transient dispatch, **not per-player pause acknowledgment or physical
mute**. Native AUTHORIZE must additionally require its actual generation-owned
all-output mute and owned sink completion. Android never resets user volume,
globally mutes streams, drives DSP/FM rails, or explicitly plays media on wake.
Abandoning the owned focus request lets the framework restore its focus stack;
each player retains its own user-intent/compliance policy. Focus denial/loss
revokes preparation. Exact24 AudioService:12795 and HardeningEnforcer:405+
also enforce TAKE_AUDIO_FOCUS/CONTROL_AUDIO AppOps; a denied request fails closed,
not a request for broad routing/phone-state permission.

During the same live GRACE, Bluetooth is temporarily disabled through
`BluetoothAdapter.disable(false)`. Its persisted user choice is unchanged.
The owner observes internal `getLeState()` through classic and BLE broadcasts:
transitional states never pass READY, even when public `getState()` reports OFF.
Actual OFF and the existing Bengal policy's stable retained BLE_ON are accepted;
BLE_ON means idle classic profiles, NOT physical controller shutdown or proof of
zero native wakelocks. This preserves the known healthy WCN transport policy.
An ON preference also causes disable during an OFF gap in a failing restart
loop, cancelling the manager's pending retry. On resume/cancellation, restore
once only if previously enabled and the current persisted choice still says ON.
Accepted enable hands ownership back to BluetoothManager; it does not claim
successful HCI initialization or profile reconnection. No airplane-mode toggle,
forced process kill, scan-client reset or Bluetooth factory reset is used.

Storage selection is strictly TYPE_PUBLIC with disk.isUsb(), initially mounted
or mounted-read-only. Private/emulated/internal userdata, SD disks, and already
unmounted volumes are untouched. A successful unmount Binder return is **not**
completion: StorageManagerService:2508–2533 can catch vold/session errors.
Readiness waits for actual STATE_UNMOUNTED via registered volume notifications
and fresh getVolumes snapshots. New mounted USB or loss/change of a selected
identity revokes readiness; the power-side eligibility check re-reads prepared
volume states before entry. The adapter never claims a synthetic sync completed.

Cancellation/remount uses original volume id, disk id, fsUuid and a local removal
incarnation. Only volumes for which this cycle issued unmount may be remounted,
only while still same identity and unmounted, at most once. Removal/replacement
drops ownership; no automatic mount of newly inserted or previously unmounted
volumes. Remount completion is observed before focus abandonment. A 10-second
rollback deadline releases focus when the lane can run and permanently blocks
new preparation if cleanup remains uncertain; late callbacks can still finish
owned cleanup. No polling/retry thread replaces a stuck lane. Android exposes no
compare-and-mount request token, so physical hotplug or another actor racing the
last checked snapshot and Binder call is not an atomic exclusivity guarantee.
The lead owns real-device acceptance of that platform boundary.

`InstalledDeepCapability` freshly reads at most 256 ASCII bytes per protected
file, without cache or writes. Exact required facts are:

- /proc/sys/kernel/osrelease = 6.18.32-4k-gd84cfa4479d9
- /sys/module/bengal_deep_suspend/parameters/guard_registered = Y
- /sys/module/bengal_deep_suspend/parameters/guard_coverage = 255
- /sys/module/bengal_deep_suspend/parameters/guard_failed = 0
- /sys/module/bengal_deep_suspend/parameters/ops_registered = Y
- /sys/module/bengal_deep_suspend/parameters/require_hooks = Y
- /sys/module/bengal_deep_suspend/parameters/force_register = N
- /sys/module/bengal_deep_suspend/parameters/production_idle_guard = Y
- /sys/module/bengal_deep_suspend/parameters/state_name = system-pc
- /sys/devices/system/cpu/online = 0-7
- /sys/power/mem_sleep = [s2idle] deep OR s2idle [deep]

One trailing newline is accepted; missing/error/oversize/non-ASCII/noncanonical
facts deny. production_idle_guard must be the root-selected computed read-only
backend status, not a writable flag; a missing old diagnostic status denies.
Both selection states are eligible because native owns arm after READY and
must gate AUTHORIZE on matching actual arm completion. The same
provider is called before preparation and before sleep: it proves installed
backend eligibility, not selected-deep arm, physical ACC wake, module hash or
DT provenance. Root's privileged startup owns exact provider/DT/backend-hash
acceptance and scoped read policy. Permissive bringup is permitted for functional
tests; there is no global-enforcing gate. Enforcing authentication acceptance
remains distinct from functional testing. Bounds are bytes/queues/deadlines,
not guaranteed liveness of a stuck Binder or kernel read.

The property is not an authentication credential or a readiness assertion.
Changing it while running does not change mode or grant an old cycle authority;
normal reboot supplies the process-start boundary. The app owns its required
privileged-permission allowlist; root owns product properties, property_contexts
and SELinux integration. Source/API compilation cannot prove runtime
permissions or framework/kernel entry. The accepted callback logs
policy_requested/physical_policy mode separately from actual acceptance; a true
mode is not sleep-entry or wake proof. Finite generation-tagged preparation logs
distinguish focus grant/denial, requested/observed unmount, Android-ready,
requested/observed remount, cleanup completion and poisoned rollback timeout.

Remaining acceptance: actual APK/vendor linking, selected policy and VINTF,
on-device own UID/context/canonical service and event sequence; then separately
enforcing identity, real participant rollback, deep backend/wake prerequisites,
bounded physical retained-current and same-session wake tests. Installing this
observer alone does not enable ACC standby. This opt-in path requires the paired
native preparation/arm implementation and root policy/kernel deployment; only
the user performs ACC-off/on tests, and root collects the resulting logs.
