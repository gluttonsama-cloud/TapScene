# TapScene viewer runtime

Browser-safe TypeScript with no Node, filesystem, network, DOM or media-decoder dependency.
`zod` is the only runtime dependency. Consumers use `@tapscene/runtime-ts`; all internal
imports use NodeNext-compatible `.js` specifiers. Source exports are intended for the
workspace's TypeScript/tsx/Vite build pipelines.

## Trust boundary

- `parseScene(raw: string): Scene` rejects duplicate keys, invalid Unicode, exponent or
  excessive-precision numbers, unknown fields and the Android parser/object budgets.
- `validateScene(value: unknown): Scene` applies the same data budgets plus strict
  schema 1/2/3 semantics, global object UUID uniqueness, asset role/path bindings,
  exact region relationships and graph reachability/ending checks.
- `parseJson(raw: string, maxBytes: number): unknown` exposes the bounded data parser.
- `canonicalJson(value: unknown): string` emits RFC 8785 ordering/escaping for this
  deliberately restricted numeric domain. Use its UTF-8 bytes to calculate the
  external content digest; the browser runtime deliberately does not import crypto.

These are viewing profiles matching `ViewerPackageCodec`, not the broader design
schema: only `tap` (one edge per hotspot) and explicitly authored `continue` are
supported. They do not accept `choice`, external assets, script fields or implicit
continue execution. Schema 1/2 have no regions; schema 3 crop assets cannot be shared
between regions. Every state is reachable and has a route to an ending.

Parsing is not media validation. `server/src/media.ts` performs actual PNG profile,
CRC, inflation and opaque pixel checks, encoded AVC SPS inspection, bounded local
ffprobe inspection and complete ffmpeg decode through EOS. The server must also
check every declared crop's pixels against its safe base before publication.

## Viewer state and events

`start(scene): ViewerState`; `reduce(scene, state, event): ViewerState`.

`ViewerState` is immutable and has `currentStateId`, actual `history` (including the
current visit), `ended`, `endLabel`, `endEdgeId`, session `visitedEdgeIds`,
`completedFromStart`, `pendingEdgeId`, `pendingTransitionAssetId`, `pendingToStateId`,
`pendingEndLabel`, `mediaRunId`, `transitionFailed`, and `closed`.

Events:

- `{type: "advance", edgeId}` explicitly selects an action
- `{type: "completeTransition" | "failTransition" | "skipTransition", mediaRunId}`
  uses the captured playback attempt ID
- `{type: "retryTransition" | "cancelTransition" | "back" | "restart" | "close"}`

An in-progress transition locks duplicate actions, retaining source history and
coverage until EOS or explicit skip. Failed media can be retried or skipped. Retry
allocates a new ID. IDs are runtime-instance unique, matching Java's in-process
counter; states are not resumable media sessions or trusted coverage claims.
Old EOS/error callbacks are no-ops after retry, cancel, back, restart or close.
Back cancels pending media first, then dismisses explicit ending labels, then
follows actual history. Restart clears history while retaining session coverage.
At 256 visits, state-target actions reject; explicit ending, back and restart remain
available. Invalid navigation throws. Closed sessions are immutable no-ops.

The reducer has no media IO. Its returned state is a proposal: a platform controller
must fully load/decode any new destination PNG before assigning it or recording
coverage. The first image needs the same check. Async controller work must also be
versioned so that cancellation, navigation or closure discards stale image loads.

## Checks

From the workspace root: `npm run check --workspace runtime-ts` and
`npm test --workspace runtime-ts`. Media tests require installed `ffmpeg`/`ffprobe`
with H.264 encoding and decoding support and the server workspace dependencies.
Synthetic media/tests do not establish browser/device compatibility or privacy review.
