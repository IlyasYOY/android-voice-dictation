# Local diagnostics and ZIP export

Settings → **Save logs ZIP** opens Android's system file picker (`CreateDocument`,
`application/zip`). Choose a location, save, then attach the ZIP manually to a bug
report with the approximate time and steps. No automatic upload, network request,
sharing intent or storage permission is added. Diagnostics are available without an
API key, including empty logs. Interface follows Soft Current surfaces, scrolls in a
short/folded window and uses the existing light/dark theme and adaptive layout.

## Contents and privacy

`events.jsonl` contains UTC epoch milliseconds, fixed `area`, `action`, `code`
enums and an optional numeric HTTP status (100–599). `manifest.json` contains schema
1, numeric app version code and Android API, retention limits, whether events are
empty and process-local counts of dropped events/write failures. `README.txt`
explains the archive. No device identifiers or third-party package/editor data.

The logging API cannot accept arbitrary strings. It never reads exception messages,
causes, stack traces or `Throwable.toString()`. It classifies known exception types;
unknown types become `UNKNOWN`. It does not collect API keys, request headers,
URLs, raw responses/JSON, audio, dictated/edited text, vocabulary, clipboard or
other apps' contents. Persisted rows are revalidated against the allowlist on export;
partial/corrupt/unrecognized rows are omitted. Do not add free-text attributes or an
HTTP body/interceptor logger later.

Covered boundaries: application startup and best-effort uncaught failures; main
foreground/background/configuration; session record/process/retry/cancel/safety stop
and domain failures; PCM capture/cleanup; network start/result/cancellation and
sanitized errors; `RecordingRecovery` storage/manual-retry failures; floating
service connect/interrupt/unbind/configuration/screen off/destroy, render/foreground
service failures, cancellation, copy failure and insertion result/fallback. Overlay
visibility polling does not log accessibility events, field contents or app names.

## Storage and bounds

- Private `Context.noBackupFilesDir/diagnostics`, outside recording/history/key data.
  The app already disables backup; no diagnostic FileProvider or exported endpoint.
- Up to **7 days**, **1 MiB total**, **16 segments** of at most **64 KiB**, each record
  at most **256 bytes**. Oldest segments go first. Age uses the earliest segment
  timestamp, so some younger rows can rotate early. Clock rollback discards future
  segments. A process restart may start a new segment; the same count/size cap holds.
- Prune on startup, each accepted event, each export and hourly while the process is
  alive. An inactive/stopped app does no background work; expired data is removed
  when it next runs, before it is exported. Persisted row timestamps are checked too.
- One IO writer, one running task and a queue of **256** tasks. Overload drops new
  events and counts them; storage failures are contained and counted, never recursive.
  No audio/transcription operation waits on diagnostic disk writes.
- Append/prune/snapshot are serialized. Export waits at most ten seconds for the
  accepted-event barrier; a full queue fails export explicitly. ZIP creation/provider
  IO use a detached snapshot of at most 1 MiB and do not lock or block the writer.
- No temporary ZIPs or export cache files. A single activity-owned ViewModel prevents
  duplicate saves and retains an in-flight write across rotation/Fold changes.
  The destination is closed before success is shown. Canceling the picker is neutral.
  Open/write/close/snapshot failures show a short retry message and best-effort delete
  only that newly selected partial document. Some providers may refuse deletion.
  Process death can leave a partial destination; restored UI reports failure, allowing
  another explicit export. Previously saved copies are never deleted by the app.
- Abrupt process kill, ANR, power loss, out-of-memory or an unwritable disk can lose
  recent queued events. Uncaught failure flushing is best effort, capped at 500 ms,
  then delegates to Android's original crash handler. This is not system logcat capture.

## Manual acceptance (no video)

1. Install 0.14.0 (29), open Settings; **Diagnostics** and **Save logs ZIP** should be
   visible without entering an API key. Check light/dark and narrow/wide Fold windows.
2. Save to Downloads. Open ZIP: exactly `manifest.json`, `events.jsonl`, `README.txt`.
   Verify `app_version_code:29`, Android API and plausible timestamps/codes.
3. Cancel the picker, retry, rotate/fold while the picker or save is active. Settings
   stays usable; one save per selection; only completed writes say “Logs saved.”
4. Reproduce an offline transcription error and explicit History retry. Save ZIP and
   check NETWORK/RECOVERY codes. Test floating capture/copy/insertion on a supported
   real device. No dictation, key, vocabulary, clipboard or other-app text appears.
5. Select an unavailable/read-only provider or run out of destination space. Expect
   the retry message, no success and best-effort removal of the new partial file.
6. Keep the app running across the retention window or relaunch after it. Logs expire;
   saved ZIPs in Downloads remain under your control. Automated tests use a fake clock
   and fill the store to exercise exact time/byte boundaries without waiting a week.

## Automated evidence

`DiagnosticStoreTest` covers exact age, restart, rotation/size/count, corruption,
clock rollback, concurrent writers/snapshots, exception privacy sentinels, empty ZIP,
provider isolation, open/write/close/snapshot failure and destination closure.
`DiagnosticQueueTest` checks overload/order and timeout cancellation.
`DiagnosticsRuntimeTest` exercises the Android picker cancellation/recreation,
real system document creation/ZIP readback, no-backup storage, recovery privacy and
actual settings renders. Runtime results and limitations are recorded in the PR;
compilation alone is not an Android test pass.
