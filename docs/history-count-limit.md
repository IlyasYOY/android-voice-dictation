# History count limit

Default capacity is 100 retained ledger rows, configurable with positive validated input. Active Record occupies a CAPTURING row before microphone start. Legacy text-only rows, pending/failed/successful recordings and unavailable retained directories count alike. Main and overlay share synchronized allocation; they cannot consume the last place twice.

Full history blocks new captures and offers explicit deletion/history or limit increase. Existing playback, copy, retry and deletion remain available. Lowering below existing count or below an already admitted capture never evicts data or prevents that capture from being preserved. Safety recovery may exceed the limit to avoid dropping existing bytes; later new starts stay blocked.

The old in-memory empty Live admissions are removed and cannot survive process restart. Legacy checkpointed text and nonempty staged directories remain counted. See [Record-only migration](record-only.md). No age/count cleanup or automatic upload is introduced.
