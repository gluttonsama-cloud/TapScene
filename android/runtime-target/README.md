# Synthetic runtime target (debug fixture only)

This native Java APK is an input receiver for TapScene's disposable CI AVD. It is
not a production feature, app dependency, account service, or phone deliverable.
It requests no permissions and has no third-party dependencies. Build inclusion
requires `-PtapsceneRuntimeSmoke=true`; the module disables every non-debug variant.
The manifest also sets `testOnly=true`, so a disposable AVD install must use `-t`.

The build toolchain is the existing AGP 8.13.2, compile/target SDK 36, build tools
35.0.0, and Java 17 source/bytecode level. The fixture requires API 33+, matching
the production click-chain runtime's display-identity requirement.

## Launcher and round identity

- Package: `com.tapscene.runtime.target`
- Component: `com.tapscene.runtime.target/.TargetActivity`
- Optional explicit launch extras: `sessionId` (1–128 ASCII letters, digits,
  `.`, `_`, `:`, or `-`) and `static` (boolean, default false)
- `singleTask` preserves the prepared round when production opens the ordinary
  package launcher intent. An intent without `sessionId` never resets that round.
- A new explicit `sessionId` launch starts an empty round. Saved-instance Activity
  recreation in the same process preserves the in-memory round. Process loss
  loses the live round and is not silently recovered as successful evidence.

## IPC contract, schema 1

Authority: `com.tapscene.runtime.target.control`. Use
`ContentResolver.call(Uri.parse("content://com.tapscene.runtime.target.control"),
method, sessionId, extras)`.

Every call compares `Binder.getCallingUid()` with the target UID using
`PackageManager.checkSignatures`; only the same debug-signed build can call it.
The fixture and instrumented app must therefore be built with the same Gradle
debug certificate. No production manifest permission is needed.

- `snapshot`: the only supported call; read-only, `extras=null`; rejects a stale
  session identity. Round initialization uses only the explicit Activity launch
  extras above. There is no reset, click, counter, or input-injection RPC.
- Query, insert, update, delete, and file-open mutations are unsupported.

The snapshot method returns a Bundle with `schema`, `sessionId`, `ready`, `static`,
`focused`, `width`, `height`, `button1X`, `button1Y`, `button2X`, `button2Y`,
`clickCount`, `state` (the logical click count), `renderedCounter`,
`centerColorArgb`, `ioError`, `events` (JSON array string), `geometry` (JSON object
string), and `json` (the complete diagnostic snapshot string).

`ready` means a live attached view has known geometry and has executed `onDraw`.
It does not certify compositor presentation, MediaProjection delivery, an encoded
frame, or successful production recording. `ioError` must be empty for valid
target-file evidence. Do not treat a gesture completion callback as a target hit;
match each dispatch to actual target events and the incremented counter.

## Actual input and clocks

Only a matching, single-pointer `MotionEvent.ACTION_DOWN` followed by
`ACTION_UP` in the same button increments the logical count. An unmatched UP,
CANCEL, or multi-touch does not. There are no click listeners, broadcast input
endpoints, accessibility node actions, or test-generated MotionEvents. The view's
`performClick()` returns false and does not alter state.

Each received DOWN, UP, CANCEL, POINTER_DOWN, or POINTER_UP is written as one JSON
line and logged under tag `TapSceneRuntimeTarget`. Events include:

- `schema`, `sessionId`, `sequence`, numeric `action`, and `actionName`
- `rawX`, `rawY`, and `button` (1, 2, or 0 for outside)
- `eventTimeMs`, `downTimeMs`: MotionEvent's uptime time base
- `receivedUptimeMs`: `SystemClock.uptimeMillis()` at touch-handler entry
- `receivedElapsedRealtimeNs`: `SystemClock.elapsedRealtimeNanos()` at entry;
  `receivedElapsedRealtimeNanos` is an equal-valued compatibility alias
- `counter`, `clickCount`, `button1Count`, `button2Count`, `stateChanged`,
  `static`, and `renderedCounter`

These clocks are diagnostics, never video PTS. `stateChanged` means an accepted
real UP changed the logical count; static mode still records accepted clicks.
The snapshot's `drawnUptimeMs` / `drawnElapsedRealtimeNs` refer only to `onDraw`,
not a display presentation fence.

## Pixels and geometry

Both large click regions are horizontal neighbors in the upper part of the safe
screen, above the usual production positioning panel. Their centers have a fixed
24dp-radius off-white disk (`0xfff7f7f7`, signed ARGB `-526345`) on a normal AVD.
The radius is capped only for a window too small to contain that disk. There is no
ripple, pressed highlight, focus animation, text, clock, or numeric point marker.
No target color uses the production overlay green `#1e7d67`.

After each accepted UP in dynamic mode, one 150ms-delayed task redraws the state
region. This deliberately allows a distinct source observation after the system
gesture callback; it does not fabricate evidence or guarantee a production After
anchor. Old-round callbacks are ignored. The region cycles through fixed colors
`0xff263c68`, `0xff923d30`, `0xff72409a`, `0xffd09c2a`, `0xff315786` and displays
eight binary check cells. The button centers and lower checkerboard never change.

In static mode an initial/reset draw is allowed, but receiving input schedules no
redraw and never changes rendered pixels. Unrelated OS redraws remain possible.

`geometry` uses `screen_raw_px` coordinates and includes full display width and
height, rotation/display ID, view origin and size, full-screen window bounds,
system-bars/display-cutout insets, button rectangles and centers, `stateRegion`,
`staticRegion`, `centerRadiusPx`, `centerStableRoiHalfSizePx`, fixed ARGB colors,
and the configured draw delay. Rectangles use inclusive left/top and exclusive
right/bottom. The stable center comparison ROI is a square with half-size
`centerStableRoiHalfSizePx` around either returned button center, safely inside
the fixed disk. Static-region comparisons must use the same geometry.

## Target-owned evidence

The APK writes only its own private `files/` directory:

- `target-events.jsonl`: exact received-event records, synchronously appended
- `geometry.json`: current round ID and geometry
- `snapshot.json`: atomically replaced complete current state and events

Each explicit new round replaces these fixture files; collect them before reset.
Backup and device transfer are disabled. No production data is read or changed.
The event/state shape is documented by `contract.schema.json`.

Build/lint commands, when explicitly run in the authorized CI environment:

```sh
gradle -p android -PtapsceneRuntimeSmoke=true \
  :runtime-target:assembleDebug :runtime-target:lintDebug
```

Preparing or statically parsing this source is not an Android runtime pass.
