# History count limit

History is newest-first, with a configurable default of 100 items. Main and the floating control share the same recording ledger. Reaching the count does not block either microphone control: when a new recording is durably kept, the oldest saved items are removed, including their audio, text and linked drafts. Dates and ordering belong to the original recording, not to its latest retry.

An active or cancelled capture does not evict anything by itself. Lowering the setting alone also leaves all existing recordings intact; the new limit takes effect the next time a recording is kept. Legacy text-only entries and detached failed/pending recordings participate in the rolling limit. Unavailable metadata and unfinalized safety recovery are preserved rather than automatically erased at the point of recovery.

Temporary overflow is possible while an older recording is actively being uploaded or processed, or when storage prevents cleanup. The saved cleanup limit allows the repository to resume trimming when the blocking work ends or on restart, without applying a later decrease before another recording is kept. Playback, copy, manual retry and explicit delete use the same history as before. No age-based cleanup or automatic upload is introduced.
