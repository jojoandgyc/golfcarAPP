# App control bridges

The Android app uses the existing pinned SSH connection. There is no HTTP listener.
The configuration viewer calls `app_follow_bridge.py --check-config` with the
selected camera and follow options. The bridge forwards them to the continuous
runtime's configuration check. It does not open hardware.

## Deployment

For the current 2026-10-29 follow lifecycle contract, deploy the complete revision
from the board runtime repository, including `app_follow_options.py`,
`app_follow_lifecycle.py`, and the matching `continuous_follow/runtime.py`.
The follow bridge/worker copies in this directory predate that update and must
not overwrite the current board installation. See the [App adaptation record](../docs/follow-handoff-adaptation-2026-10-30.md).

- `app_joystick_bridge.py` belongs in `/home/topeet/lianzhan/scripts/`.
- Manual diagnostics also require `app_diagnostic_log.py` beside that joystick script.
  Manual logs are stored under `/home/topeet/lianzhan/app_drive_diagnostic_logs/`.
- `app_follow_bridge.py`, `app_follow_worker.py`, and `app_diagnostic_log.py` belong together in
  `/home/topeet/Desktop/rk_car_runtime_module/`.
- Follow uses the existing runtime and its installed dependencies. The existing
  joystick bridge supplies read-only conflict/serial-ownership checks; it is not
  launched to start or stop follow.

The follow worker adapts the runtime documented in
`docs/app_follow_configuration_and_commands.md` on the board. Its bounded state
publisher observes runtime events directly, without tailing buffered JSONL logs.
If the runtime's `ContinuousRuntime`, hardware adapters, or telemetry schema
changes, recheck the adapter and its tests before using real execution.

## Manual safety protocol v2

Android 1.8.8 starts the joystick bridge with `--require-safety-version 2`.
Hardware mode rejects an absent/wrong version before opening a serial device;
older bridges reject the new argument before their old initialization runs.
`--dry-run --require-safety-version 2` uses simulated feedback and never opens hardware.

READY and ACK separate accepted `left/right` targets (`targetStopped`) from
measured `stopped`. Feedback contains signed `feedbackLeftRpm/feedbackRightRpm`,
both error codes, sample age/count and a hardware/simulated source. A single FC03
read of 13 registers starting at 0x0009 observes both speeds and fault codes.
Standstill requires two zero/error-free observations at least 100 ms apart,
with both observations no older than 250 ms. Zero targets alone are not evidence.
The App also budgets board sample age + request RTT + local elapsed time within
500 ms before authorizing a parking change. Delayed ACKs can keep the session
alive without authorizing configuration from stale feedback.

The `parking` request contains only the usual seq/token/cmd/left/right and
`parkingEnabled`; the old `parkingCurrent` request is rejected. READY/ACK report
`parkingLeftCurrentAmps`, `parkingRightCurrentAmps`, `parkingMaxCurrentAmps`, and
`parkingCurrentReadOnly=true`. Startup reads the driver's existing configuration.
ON requires both saved currents above zero and within the configured limit;
OFF and session cleanup request emergency stop mode. None of these paths write
configuration registers, clear saved currents or restore guessed defaults.
Any explicit current edit belongs to the separate guarded motor configuration UI.

Safety polling samples idle feedback at 100 ms intervals and refreshes hardware
zero/stop at 200 ms intervals, including while waiting for input. Before a queued
neutral refresh can erase unexpected-motion evidence, feedback is checked again.
Once standstill was confirmed, any observed nonzero speed immediately latches a
fault and requests emergency stop on both sides; read failures and motor errors
also fail closed. Network/IR emergency hold is not silently changed to normal
parking by periodic polling. Moving-to-stopped transitions allow up to 1.5 s
for measured standstill; parking pre/post checks share a 2 s transaction budget.
Serial I/O is still bounded at 150 ms per transaction; these polling intervals
are scheduling targets, not hard real-time guarantees on failed serial I/O.
Emergency writes are attempted on both sides even if one fails or a deadline
expires. A feedback-confirmed zero speed is not proof of a mechanical brake.

The existing 600 ms soft-stop/5 s network grace and explicit-release recovery
remain. Hardware feedback faults terminate the session and cannot be cleared by
ordinary heartbeat traffic. Logs now include read-only current configuration,
measured RPM/error/sample metadata, parking mode changes and the initiating
hardware fault, without raw frames or control tokens. Only deploy while all
control owners are idle, with an exact previous-file backup; do not start a
hardware session as part of deployment verification.

## Follow sessions

The bridge sends a `ready` JSON line before starting any worker. Requests contain
exactly `seq`, `token`, `cmd`, and `runId`. Every successful response rotates the
token. Supported commands are `start`, `heartbeat`, `stop`, and `close`.
`ready.options` echoes the normalized `targetDistanceM`, `gesturesEnabled`,
`resumeGesture`, `avoidance`, `reverseEnabled`, and `reverseDistanceM` values so the
App can verify that the board accepted its selections before sending `start`.

The bridge accepts `--target-distance`, `--gestures` / `--no-gestures`,
`--gesture-resume Closed_Fist|Thumb_Up`, `--avoidance off|shadow|on`,
`--reverse-follow` / `--no-reverse-follow`, and `--reverse-distance`. Both distances
must be finite and within 0.5–5.0 m. `--avoidance on` requires an explicit
`--camera astra-pro`. The bridge defaults to no ordinary-distance override,
gestures off, `Closed_Fist` as the selected resume gesture, avoidance off,
reverse off, and no reverse-distance override.

The App initially selects 1.5 m for ordinary following, `Thumb_Up` as the resume
gesture, and 1.4 m for reverse distance; gesture recognition, avoidance, reverse
following, and real motor execution initially remain off. These App selections
are sent as explicit launch options and may differ from the bridge defaults.
`--target-distance` copies the complete `continuous_follow/baseline.ini` into a
private temporary settings file, changes only `target_distance_m` for this run,
and deletes the copy after the check or session exits. It does not edit the
board's baseline configuration.

When reverse following is enabled, the current controller uses
`reverse_target_distance_m` as its effective holding distance in both forward
and reverse motion. For example, selecting 1.5 m ordinary distance and 1.4 m
reverse distance yields an effective holding distance of 1.4 m while reverse
following is on. Reverse following has no rear-obstacle protection.

The App sends heartbeats every 500 ms. The supervisor and its worker independently
expire the lease after 2 seconds. Closing the channel, losing the parent, or
leaving the App requests shutdown. A stopped lease cannot resume movement. The
worker checks the lease before opening devices and before non-stop motor writes.

Only the owned child receives a stop request; the bridge never sends raw motor
commands or kills unrelated processes. Manual and follow sessions share
`/home/topeet/lianzhan/.app_joystick.lock`; the worker inherits that lock until
exit. The runtime's original follow lock is also respected. A stop that exceeds
8 seconds becomes a fault, with ownership retained while the process is alive.

ACKs contain `status` and `terminal`. `terminal=true` means the process has exited,
not that the wheels have physically stopped. STOP communication acknowledgement
comes only from the runtime's motor owner. `physicalStopped` remains `null`.
Missing or stale target, range, and wheel feedback are unknown, never zero.

`python3 app_follow_bridge.py --inspect` checks both ownership locks and known
conflicting processes without opening devices. The App uses it to recover from
an uncertain session after reconnecting; reconnecting never starts follow.

## Session diagnostics

The bridge writes private JSONL files under
`/home/topeet/Desktop/rk_car_runtime_module/app_diagnostic_logs/`. Their filenames
contain the same `runId` returned in `ready` and recorded by the Android App.
Records include UTC wall time, board monotonic milliseconds, bridge/worker PID,
accepted request sequence, response/terminal delivery, first stop source, first
failure, worker runtime log directory, cleanup and exit codes. The two devices'
monotonic clocks are independent; correlate by `runId` and request sequence, not
by comparing raw monotonic timestamps.

`firstStop` and `firstFailure` are retained in the final session record. An App
`stop` request, input EOF, heartbeat watchdog expiry, protocol validation failure,
worker status failure and a supervisor signal have distinct diagnostic sources.
Later cleanup does not overwrite the first trigger. This supplements the
runtime's generic `app_stop_requested`: the worker still receives the unchanged
`S` lease message, and request/response JSON is unchanged.

A bounded background writer rotates files at 2 MiB, keeps at most 20 MiB total,
and removes files older than 7 days on subsequent writes. The directory is mode
700 and files are mode 600 on Linux. Critical stop/error records take priority
over routine heartbeats when the queue fills; a subsequent record reports the
cumulative `droppedEvents` count. Slow or failed disk writes never block the
control thread. Failure is reported with `FOLLOW_DIAGNOSTIC_UNAVAILABLE` on
stderr, never on the protocol stdout stream. Warnings use a separate nonblocking
Linux pipe/tty descriptor and may be dropped under backpressure or for unsupported
stderr sinks; they never use Python's buffered stderr lock. Constructor failures
also report asynchronously. Tokens, credential-shaped values
and raw protocol messages are not written. Logging remains best effort if the
process is killed or storage is unavailable.

Fault capture only copies bounded primitive arguments and up to 24 traceback
file/line/function entries. It retains no exception frames or locals, reads no
source files, and never invokes exception `__str__`; formatting/redaction runs
on the writer. Earliest causes are selected by capture monotonic time and an
ordering counter under a short memory-only lock, so later-queued earlier faults
and equal-resolution timestamps cannot replace the original trigger with cleanup.

For a reported App session, inspect matching files via SSH, for example:

```bash
ls -lt /home/topeet/Desktop/rk_car_runtime_module/app_diagnostic_logs/
rg '"event":"session_failed"|"event":"session_finished"' \
  /home/topeet/Desktop/rk_car_runtime_module/app_diagnostic_logs/*_<runId>_*.jsonl
```

Deploy the diagnostics module before replacing the bridge, with control idle,
and retain a backup of the previous bridge/module. The worker is unchanged by
the diagnostic enhancement. Offline `--dry-run` tests may set
`APP_FOLLOW_DIAGNOSTIC_TEST_DIR` to an isolated temporary folder; this override
is ignored for real/shadow runs.

## Modes and verification

Check the selected launch configuration before starting a session:

```text
python3 app_follow_bridge.py --check-config --camera astra-pro \
  --target-distance 1.5 --gestures --gesture-resume Thumb_Up \
  --avoidance on --reverse-follow --reverse-distance 1.4
```

This performs only the runtime's static configuration validation and reports
its effective settings; it does not open a camera or motor. `--check-config`
cannot be combined with `--execute` or `--dry-run`.

- Default App mode is **shadow follow**: opens camera/models, not motors.
- The explicit real-execution switch adds `--execute` and permits motor motion.
- Developer-only `--dry-run` imports no camera, model, or motor runtime and runs a
  fake worker. It cannot be combined with `--execute`.

Offline board tests:

```text
python -m unittest discover -s board -p "test_app_*.py" -v
```

Run these on Linux as well as Windows to cover inherited `flock` ownership and
parent-death handling. Their subprocesses are explicitly dry-run; fake adapters
cover runtime-open, lease, and STOP acknowledgement behavior. Android
`FollowModelsTest` and `FollowControlsTest` use captured configuration output and
fake UI callbacks. `BoardFollowIntegrationTest` is opt-in with `boardPassword`
and uses only static configuration checks and `--dry-run`.

Real camera startup, tracking, serial acknowledgements, and mechanical stopping
still require supervised hardware validation. Configuration and fake-session
success do not establish those hardware results.
