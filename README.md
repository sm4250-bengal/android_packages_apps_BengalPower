# BengalPower policy and BACP channel — NOT WIRED

## Prior-art census

The starting source map is `kernel/out/.tmp/acc-android24-path-map/report.md`,
SHA256 `4487835521533a994d2bf3edce4484b0e3b6b169382da8f282c0f88f0af2ea14`.
The four relocated reference trees were re-searched for ACC, standby,
`goToSleep` and the fixed wake answer. Relevant opened oracles:

- `archive/reference/device12/docs/xyauto-mcu/hardware/STOCK_QUICKBOOT_HANDSHAKE.md:7`:
  daemon-side OFF/final-stage/500 ms/final-write ordering. This core consumes
  that boundary; it does not implement another UART parser or timer.
- `archive/reference/device12/handoff/STATUS.md:3`: device-1 retained
  0.024 A result and the ordinary Android SystemSuspend path. Family prior
  art does not establish device-3 power or wake performance.
- `archive/reference/device12/hardware/firmware/newdevice-3403782-stock-2026-08-26/meta/sleep-state-after-cycle.txt:1`:
  stock device-2 suspend-counter/retained-session evidence; separate from a
  framework sleep request.
- `archive/reference/device12/kernel/xyauto-sm4250/scripts/package-complete-audio-0376.sh:6`:
  SystemSuspend owns entry. Imported ACK patches `kernel/patches/bengal/0033`
  through `0035` are kernel prior art, not implemented or selected here.
- `archive/reference/device12/apps/mcu-toolbox/bridge/main.go:159,456,548`:
  reuse cycle cancellation and final-stage ordering. Replace its lock-held
  command sequence with explicit asynchronous boundaries and compensation;
  do not reuse shell commands, hardware writers or blanket radio restoration.

Current HUD's `platform/hardware/xyauto/hud/src/binder.rs` drops SleepRequest;
its public ACC state does not prove host TX success. The source map traces
the existing no-op preparation hooks and daemon-relative timestamps. Before
this addition, the BengalPower directory did not exist. D24's reopened
provider-only work, D27 and PLAN Phase 6 require independently demonstrated
deep capability plus measured retained wake; this core claims neither.

## Scope and behavior

This contains a pure Java coordinator, unselected Android Host/Timer and BACP
socketpair adapters, and executable host tests. There is no
manifest, service, boot receiver, permission, AIDL, Android build module or
product selection. Nothing instantiates it in production. Passing `false`
to the constructor is inert, including lease acquisition; enabling requires
an explicit caller choice. Java source acceptance is not Android, suspend,
physical preparation or standby acceptance.

The future narrow system_ext power owner wires the dependencies. Policy
state uses short internal locks. No injected call, including lease release,
participant notification, time sample or power RPC, runs under those locks.
Preparation dispatch and a separate FIFO serial power lane allow cancellation
to invalidate state while a power RPC is blocked. The serial lane wraps even
a multi-thread executor. Production executors must actually dispatch off
the calling thread and remain alive until pending cleanup/compensation ends.
The coordinator does not own or shut down caller executors.
Sleep/wake reservation and internal FIFO append share the state-lock critical
section. Append only returns a dispatch token; the injected backend runs after
all locks are released. The lock order is state then FIFO. The worker releases
the FIFO lock before calling a task or notifying a failure.

`connect(identity, snapshotGeneration)` creates an opaque registration epoch.
The snapshot is only a floor: no preparation or sleep replay, and no redundant
wake on ordinary cold start. `beginOff(session, generation)` consumes a live
final-stage preparation request after confirmed OFF, with strictly increasing
nonnegative daemon cycle numbers within that registration. It does not start
at the beginning of the roughly 25-second grace interval. Duplicate/older
requests and callbacks for previous registrations are ignored. Generation
must never wrap within a session; rotate session before exhaustion.

Only a matching `finalHostWrite(..., true)`, successful participant completion
within the preparation deadline, and a current deep-capability check permit
`Host.sleep`. The first final result is authoritative and immutable; it must
represent completed host write success, not an attempted TX, queued message,
MCU receipt or a SUSPENDING state. A false result invalidates that cycle.
The core cannot authenticate or independently verify any supplied evidence.

Each admitted cycle gets a bounded preparation timer and at most one owned
lease. On success both are released before the power call, followed by another
monotonic-deadline and current-cycle check. A delayed timer callback cannot
extend the preparation window. Cancellation, timeout, failures, replacement, death and
close invalidate first and release resources. A lease acquired after
cancellation is immediately released. Late preparation results cannot revive
the cycle. Lease implementations must provide a hard platform expiry as well
as explicit release: a Java exception or a dead process cannot itself
prove an external wakelock was released. A release failure before sleep vetoes
sleep; participant rollback failures likewise cannot authorize it.
The actual Android WakeLock release uses synchronous Binder. Its hard acquire
timeout bounds the hold under a functioning framework, not Binder/Handler
liveness. A stuck release or power RPC gives no bounded wake-latency guarantee;
state invalidation and lock-free external calls do not change that limitation.

`Host.uptimeMillis()` must sample Android `SystemClock.uptimeMillis()` at each
power call. No daemon timestamps or wall clock are accepted. Ordering is by
registration/cycle identity, never raw timestamp comparisons. `Host.sleep`
maps to the typed PowerManager sleep API with the accepted no-doze policy;
`Host.wake` maps to its typed wake API. PowerManager and SystemSuspend retain
normal blocker handling. No direct sysfs sleep, shell or forceSuspend path
exists here. `SLEEP_REQUESTED` means only the RPC returned successfully.

If ON/death/cancellation arrives after the final pre-call check, sleep may
already execute. The same serial job then calls compensating wake **after**
sleep returns, including an exceptional/uncertain sleep result. This job
finishes before any newer queued sleep. Queued normal wakes are deduplicated
and bound to the sleep they undo, so an old wake cannot undo a newer sleep.
A fresh OFF replacing a previous sleep first schedules the outstanding wake.
The core cannot retract an already accepted RPC or wake while that RPC remains
indefinitely stuck; it retains no preparation lease over the RPC.

A wake exception or rejected power executor is a terminal fault: state FAILED,
current session invalidated, leases released, no automatic retry or reconnect
on that instance. Rejection latches the lane permanently faulted atomically
with clearing its queue, before coordinator notification; concurrent appends
cannot restart it while notification is pending. The production owner must surface the uncertain outcome and
reconcile it before creating a replacement coordinator. Ordinary connection
loss permits a fresh session with a snapshot floor. `close` still permits
needed queued/in-flight compensating wake; it never permits new sleep.

## Implemented Android adapters — unselected

`AndroidPowerHost` accepts the future owner's typed PowerManager. It samples
SystemClock.uptimeMillis again inside each actual sleep/wake call, ignoring
the coordinator's earlier sample. Sleep uses GO_TO_SLEEP_REASON_APPLICATION
and GO_TO_SLEEP_FLAG_NO_DOZE; wake uses WAKE_REASON_APPLICATION. Each preparation
lease owns a distinct PARTIAL_WAKE_LOCK, setReferenceCounted(false), acquired
with a hard 1..30000 ms timeout. Explicit release is atomic and idempotent,
including after or during framework auto-expiry. Acquisition failure attempts
cleanup and propagates; power/release exceptions are never reported as success.

`AndroidPreparationTimer` owns one ScheduledThreadPoolExecutor worker with at
most eight pending/running tasks and 1..30000 ms delays. Cancelled tasks are
removed; running callbacks are not interrupted. Completion, cancellation and
scheduler rejection each release the capacity slot exactly once. The core's
deadline samples use SystemClock.elapsedRealtime, including time in suspend.
ScheduledExecutorService is not a wake alarm: callback delivery can lag across
suspend or a blocked worker. The core's final elapsed deadline check prevents
that lag from extending permission to sleep. The preparation wakelock and
normal framework scheduling remain required for timely callbacks.

The timer owns no power or preparation executor and never shuts either down.
Lifecycle order for the future owner is: close/invalidate the coordinator,
drain its power lane including any pending post-RPC wake, then close the timer
and other owned executors. Timer.close refuses active deadlines/callbacks;
that guard alone does not prove the separate power lane has drained. There
is no service lifecycle or production factory in this slice.

## BACPv1 socketpair adapter — unselected

`BacpPacket` implements exactly the immutable 64-byte v1 record contract and
consumes root's twelve independent native golden vectors. Unsigned fields
outside Java's signed nonnegative range, unknown kinds/phases, reserved bits,
bad lengths and invalid nonce/field combinations are denied. Nonces fence a
registration; they do not authenticate a process.

`AccPowerSession` initializes the coordinator snapshot floor before START,
requires exact next live event sequence and both nonces, and never replays a
historical OFF. Only actual participant completion plus current deep readiness
produces READY. Complete FINAL_RESULT alone never grants core permission:
one matching AUTHORIZE, READY, successful final, current generation and fresh
elapsed deadline are all required. Late readiness is cancelled, not upgraded.
Preparation lease/timer bounds are shortened to the producer's remaining
absolute deadline; actual host entry rechecks that deadline. Observation mode
does not prepare, send READY or call sleep. A single coordinator survives
reconnects so its power FIFO orders old compensation before new generation-one
sleep, including when the supplied executor has multiple threads.

`AndroidAccPowerChannel` creates an unnamed, connected, nonblocking/CLOEXEC
AF_UNIX SOCK_SEQPACKET pair. It resolves only the canonical
`vendor.xyauto.accpower.IAccPowerChannel/default` through ServiceManager,
checks frozen V1/version/hash, links Binder death, and offers a duplicated
ParcelFileDescriptor through the root-owned stable interface. Exclusive HUD
service-add policy is the identity root, not the descriptor/hash alone. There
is no arbitrary production Binder injection, pathname connect or init socket.
Both socketpair endpoints retain the creator app's peer UID/SID: requiring HUD
peer credentials here would be wrong. The app verifies its own user0 UID
10000..19999 and exact `u:r:bengal_power_app:s0` plus the four levelFrom=all
categories derived from appId. Missing/malformed identity denies. HUD must
independently bind the incoming transaction UID to the socket creator UID and
validate the exact app SID/MLS and descriptor type.

The current bringup product is SELinux permissive. These checks therefore do
NOT establish authenticated runtime isolation on that product. Observation
must remain incapable of READY/sleep. A future trustworthy DeepCapability
must establish enforcing policy and non-permissive participating owner domains,
not merely test a global enforcement bit; that provider is unavailable here.

The original server endpoint closes immediately after duplication. Before
submission the bootstrap lane atomically takes exclusive ownership of the PFD,
then closes it in offer's finally. Timeout may close a still-pending PFD, never
a PFD borrowed by Binder serialization. A permanently stuck submission may
retain exactly that one PFD and the already-occupied bootstrap lane; local
shutdown makes its eventual transfer unusable. The local endpoint has one
explicit owner: LocalSocket's FD constructor is non-owning. Stop revokes local
session authority, closes any still-pending PFD, shuts down the local socket and
signals its wake pipe before potentially blocking framework cleanup. Only
the I/O worker finally closes its polled descriptors, after close cleanup has
returned. A short local FD lock excludes final close while signal/shutdown
borrows a raw descriptor; it never covers Binder or participant calls. Thus
even an already-running signal cannot use a closed/reused pipe FD. One reusable
I/O lane and one bootstrap lane have
fixed one-job queues; callback ring capacity is eight. No retry/thread is
spawned automatically. Explicit reopen is denied while old cleanup or a
blocked bootstrap remains outstanding. Do not create replacement owners to
circumvent a stuck bootstrap. Idle waiting uses a local wake pipe and indefinite
poll, not a periodic heartbeat.

The handshake has a 2000ms local elapsed deadline. Oneway Binder submission
is neither admission nor a wall-clock liveness guarantee. A late return can
never reactivate an expired attempt; closing a Future is not claimed to
interrupt Binder. The owned I/O/bootstrap executors may stop accepting work
on owner close; supplied power/preparation executors and timer remain alive
for compensation, and their final shutdown belongs to the caller after drain.
Stuck Binder, release, scheduler or kernel calls have no bounded physical wake
guarantee. The app is still NOT wired or runtime-proven.

Receive uses one LocalSocket read into MAX_PACKET+1, never readFully. Every
read outcome drains the ancillary array; any attached FD rejects the packet
and every close is attempted even if another close fails. Exact24 JNI uses
ReceiveFileDescriptorVector with a maximum of 64 RAII-owned SCM_RIGHTS descriptors;
libbase rejects unexpected controls and MSG_TRUNC/MSG_CTRUNC before transferring
RIGHTS ownership to Java. It does not adopt arbitrary control FDs: SCM_PIDFD
would not be closed there. That path is unreachable for this privately created
receiver, which never enables SO_PASSPIDFD or exposes its endpoint to another
owner; the other socket endpoint cannot configure this receive option. This
source evidence covers reachable RIGHTS error/truncation cleanup, not generic
ancillary-FD RAII. Host doubles do not prove Android recvmsg or live descriptor behavior.
Sources: frameworks/base/core/jni/android_net_LocalSocketImpl.cpp,
system/libbase/cmsg.cpp, and LocalSocket/LocalSocketImpl at the reused exact24
pin. `Os.recvmsg` with null controls is deliberately not used.
Send uses the same NONBLOCK LocalSocket JNI and its MSG_NOSIGNAL flag:
exact24 OsConstants does not expose MSG_DONTWAIT/MSG_NOSIGNAL. The selected
SEQPACKET operation returns the complete record or an error, so JNI's generic
short-write stream fallback is not entered. No outbound FD is attached; the
helper's empty SCM_RIGHTS header is elided by Linux scm_fp_copy's zero-count
path. Kernel source confirms these semantics, not Android runtime operation.

This implements the wire part of frozen contract `9dcb1afe` with bootstrap
correction `99478649` (the correction supersedes pathname/peer-HUD auth).
The source map `44878355` is reused, not re-censused. Future build dependency
is root's `vendor.xyauto.accpower-V1-java`; no AIDL or policy is defined here.

The optional final `Consumer<BacpPacket>` constructor argument observes packets
only after successful session validation on the I/O lane (SNAPSHOT follows
successful START output). Existing constructors retain a no-op observer.
Keep callbacks finite/nonblocking; a throwing observer closes the connection.
Protocol acceptance is not enforcing peer identity or physical acceptance and
the callback supplies no power authority.

## Integration prerequisites — still unwired

- Activate the dedicated BACP registration alongside existing UI listeners,
  only after the root-owned frozen API, exclusive service registration and
  narrowly compiled SELinux policy are selected and verified on device.
  Existing single-subscriber ACC callbacks remain insufficient.
- Daemon final-write result and cancellation delivery that remain independent
  of blocked preparation/power calls. A confirmed ON cancels the exact OFF
  cycle; its token is not a new arbitrary generation. ON at daemon 499 ms is
  represented here as cancellation before the final result, not a second
  approximation of the daemon deadline.
- Wire the implemented Host/Timer adapters into the future owner with its
  lifecycle and failure handling. Keep timer callbacks independent of the
  preparation and power workers. A callback must not perform blocking
  participant work; Android release/RPC liveness has the limitation above.
- Real audio, storage and USB owners behind `Participants`: return true only
  after actual bounded preparation completes. Preserve all-source transient
  mute, user intent and owned rollback across cancellation. `cancel` must be
  a nonblocking local notification into that ownership machinery. Participants
  must check `Cycle.isCancelled()` before actions and compensate actions that
  race cancellation. This core has no physical mute/preparation flag and
  never restores volume, plays media, writes MCU bytes or drives USB GPIOs.
- A trustworthy `DeepCapability` provider using installed/selected kernel
  evidence and wake prerequisites, not product intent. It is checked before
  preparation and immediately before sleep. Capability revocation must also
  cancel the current cycle; a cached boolean cannot eliminate a change racing
  the power call. Missing capability returns TARGET_UNAVAILABLE.
- Platform signing, narrowly scoped DEVICE_POWER/WAKE_LOCK permissions and policy,
  build/product default-off integration, Android API/SELinux checks, bounded
  PMIC wake integration and physical flight acceptance. FM/buttons must not
  gain power privileges; existing MCU/audio/USB owners remain authoritative.

## Host verification

From this worktree, with an explicitly owned absolute scratch directory:

```sh
bash platform/packages/apps/BengalPower/tests/run-host-tests.sh \
  /home/meinte/android/bengal/kernel/out/.tmp/acc-power-socket-android
```

Uses offline `javac --release 11 -Xlint:all -Werror` and `java -ea`, retaining
each run's classes in a fresh output subdirectory. The 24 focused cases execute
the actual production class. Manual executors/timers cover queue boundaries;
latches block a real power call on a pool backend while ON/death and a new OFF
arrive. Five-second latch watchdogs diagnose a hang; no sleeping or probabilistic
timing is used. Tests cover gates, duplicates, stale sessions/generations,
snapshot replay, failures/timeouts, cancellation and all acquired leases.
Eleven additional cases execute the real Android adapters against minimal
PowerManager/SystemClock doubles plus a manual scheduled executor. They check
fresh uptime, typed flags, lease isolation/expiry/concurrent release, failures,
timer capacity/cancellation/rejection and callback-before-future-attachment.
A real owned scheduler control checks one worker, removal from its actual queue,
and termination after cancellation. No test double is used for API compilation.
Sixteen BACP session/codec groups consume all twelve immutable golden vectors
and cover observation, strict packet checks, readiness/final/authorization,
deadlines, cancellation, actual in-flight compensation and reconnect ordering.
Fifteen channel groups exercise the actual owner/record reader against controlled
syscall/Binder boundaries, including a blocked offer, EOF/death, callback ring
overflow, reopen, one-record sizes, all-FD cleanup, close ordering and observer
acceptance/rejection. Five more groups execute the actual AndroidEndpoint with
synthetic FD/PFD/Binder doubles, pausing serialization, signal and shutdown.
All three baseline Android borrower paths fail these regressions, and the
baseline channel fails cleanup-before-disposal. The FD tests use add-opens only
to assign synthetic Java FD numbers; no host descriptors are adopted or closed.
Android socketpair, ServiceManager and Binder runtime are not executed by host tests.

Exact API compile (JDK 21 is required to read the existing class-file version 65):

```sh
env PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  bash platform/packages/apps/BengalPower/tests/check-platform-api.sh \
  /home/meinte/android/bengal/kernel/out/.tmp/acc-power-socket-android \
  /home/meinte/android/bengal/kernel/out/lineage24-cp2a/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework.jar \
  /home/meinte/android/bengal/kernel/out/lineage24-cp2a/soong/.intermediates/libcore/core-libart/android_common_apex31/combined/core-libart.jar \
  /home/meinte/android/bengal/kernel/out/.tmp/acc-power-api-lead/frozen-java/vendor/xyauto/accpower/IAccPowerChannel.java \
  /home/meinte/android/bengal/kernel/out/lineage24-cp2a/soong/.intermediates/packages/modules/Bluetooth/framework/framework-bluetooth.impl/android_common/turbine-combined/framework-bluetooth.jar
```

The existing exact24 jar is 45481016 bytes, SHA256
`7632eb34c08132faa4018d027cce86b367bba42a6108083af18186e521f9fdd3`, checked before
and after javac. Source pin: selected24 frameworks/base
`38d8e89230c1c80d49530bd11bbea93e45dd7ffc`; PowerManager.java:1681/1759 are the
typed calls, :4347/4401 the timed acquire/non-reference-counted release.
This verifies production API names/signatures without Soong or framework
execution. Missing dependency annotations are excluded with `-Xlint:-classfile`;
all source diagnostics remain fatal. It does not prove permission or live Binder behavior.
All nine production files are compiled, including the actual Android transport.
The existing core-libart jar is 599708 bytes, SHA256
`afba5762c39026cd85315b6f2007b856ced2d01776a1700ace32b09c9d6175e8`.
Root's genuine SDK37-AIDL-generated V1 source is 6535 bytes, SHA256
`3da6a2ee9f097f1c037b9d603f1e9543ebdf1c7c874636db3133bc62a4f2613b`;
its interface hash is `433d5bf62db24a55b9dac824f638ff2e21f11150`.
It was generated from root's actually frozen V1 at `db337d39` with version1,
minSDK37, structured and VINTF stability options, byte-identical to the earlier
provisional source; it is not a host stub. Consumer module build/policy proofs
remain separate. The app uses the generated HASH constant, not a guessed hash.
The checker also pins the actual Bluetooth framework header jar, SHA256
`1af70e2d8427ddfbf70d58fef3f8d1ffb7309e2e99383aa87a24a2bc19fa210c`, for
the ACC participant's internal-state and temporary-disable APIs. All four
read-only inputs are pinned before and after compilation.
Adapter controls: replacing the fresh sleep sample with the earlier argument
compiles but fails `sleep adapter`; renaming the typed method to
`goToSleepInvalid` fails against the actual jar with a missing-method error.

Negative control: removing the inline compensating wake still compiled and
failed at `failed RPC compensation`; restoring it passes the suite. This is
a source policy check, not authentication, Android framework or kernel proof.
New scratch-only mutation controls grant core permission directly on Complete
and stop ancillary cleanup after the first close failure; both compile and
fail the focused tests (`Complete authorized sleep`, `remaining ancillary leak`).

The follow-up ordering regressions both fail against original core `98587685`
while its original 22 cases still pass. The actual serial lane is instantiated
reflectively to latch its failure notification after a reject-once backend;
a concurrent submission must neither dispatch nor execute. A test-only FIFO
subclass checks that every actual append holds the coordinator state lock,
and a blocked wake dispatch permits a newer OFF to queue before verifying
`sleep, wake, sleep`. This distinguishes atomic reservation/append from merely
serializing jobs already submitted. No production test hook was added.
The FIFO invariant probe reflectively replaces a private final field before
any work begins; use JDK 17 or 21 for the host suite. JDK 26 currently runs it
but warns about that test-only field replacement.

Desk cannot answer: real deep entry/current, UART delivery, physical mute,
storage detach, rail changes, PMIC retained wake, repaint or audio/USB recovery.

For memory: BengalPower now has an unselected BACP socketpair/session adapter
alongside its core and Android Host/Timer adapters. READY plus exact successful
final/authorization, current readiness and deadline are required; one core
preserves compensation ordering across reconnect. Platform API compilation
and host controls are not enforcing runtime authentication or standby proof.
Root-owned packaging/policy and real participant/deep evidence remain required.
