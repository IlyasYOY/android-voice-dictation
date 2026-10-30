# Architecture candidate 0.3.1 — runtime evidence

These captures are from the refactored debug candidate, not the earlier 0.3 prototype. All text and server responses are synthetic; no OpenAI calls or private audio. Final functional source: `44ca6c9e14430fd77a3fba111aa90b878a2ce7a5` (subsequent changes are evidence/documentation and removal of one comment).

- 70 JVM tests passed; strict app/fixture lint and all debug/test APK builds passed.
- API 28 in-app instrumentation: 11/11 passed after the final functional changes.
- Startup orphan cleanup: both process stages passed without opening a recorder.
- API 28 floating instrumentation: 2/2 passed. Bubble/live/Stop/Copy, unchanged original field, microphone stopped; Cancel and explicit restart passed.
- API 28 uses explicit Copy. **Native API 33+ insertion is unverified on this candidate:** Android 16 APK installation failed with package-service Broken pipe (32).
- The separate Library MP4 is a real, silent emulator recording of the API 28 Copy flow. Its UI is not an insertion demo.

The retained logs below are concise actual test outputs. Screenshots show the live partial, Copy fallback, unobscured unchanged final field, and in-app final transcript. Earlier failed attempts are described in [architecture review](../../architecture-review.md); they are not relabelled as successful runs.

Debug APK SHA-256: `4de34bd65ce5eb8071ab0ca2b59855d886a00b9c815341c44f3eb7ffc255c25d`.
