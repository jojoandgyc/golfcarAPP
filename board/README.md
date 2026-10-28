# App control bridges

The Android app uses the existing pinned SSH connection. There is no HTTP listener.
The configuration viewer calls `app_follow_bridge.py --check-config` with the
selected camera and follow options. The bridge forwards them to the continuous
runtime's configuration check. It does not open hardware.

## Deployment

- `app_joystick_bridge.py` belongs in `/home/topeet/lianzhan/scripts/`.
- `app_follow_bridge.py` and `app_follow_worker.py` belong together in
  `/home/topeet/Desktop/rk_car_runtime_module/`.
- Follow uses the existing runtime and its installed dependencies. The existing
  joystick bridge supplies read-only conflict/serial-ownership checks; it is not
  launched to start or stop follow.

The follow worker adapts the runtime documented in
`docs/app_follow_configuration_and_commands.md` on the board. Its bounded state
publisher observes runtime events directly, without tailing buffered JSONL logs.
If the runtime's `ContinuousRuntime`, hardware adapters, or telemetry schema
changes, recheck the adapter and its tests before using real execution.

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
