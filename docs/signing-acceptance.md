# Owner-controlled signing smoke test

Status: **not started**. Execute only after separate owner authorization, following
the [release runbook](signed-releases.md). This documentation PR provides an open
PR targeting main for the worker's `pr` operation; it changes no application,
dependency, workflow or signing code.

1. Record the reviewed PR head and trusted main/controller SHA. Keep the same PR
   head for both builds; do not push result updates between them.
2. After authorized ledger initialization, enable only `APK_PR_BUILDS_ENABLED`.
   Keep `APK_RELEASES_ENABLED` unset/false. The owner dispatches the main workflow
   with operation `pr` and this PR's number, then approves `apk-pr-signing` after
   inspecting the pinned source and allocation.
3. For the final verified APK artifact, record the run URL, certificate SHA-256,
   APK SHA-256 and versionCode. Check the configured certificate, package
   `io.github.ilyasyoy.voicedictation` and expected source metadata. Artifacts in
   this public repository are available to signed-in readers, not just the owner.
4. After accepting the [legacy reinstall/data-loss boundary](signing-key.md),
   install the new-key APK. Create a synthetic local recording and change a
   setting without configuring an API key or making provider requests.
5. Dispatch a new build of the same PR head. Verify the same certificate and a
   greater versionCode, install it as an update without uninstalling, and check
   retained recording playback and settings. Failed attempts burn versionCodes.
6. Report actual results only after execution, using public verification metadata
   and synthetic observations. Include no keystore, passwords, user content or
   private artifact links. Keep this PR unmerged until acceptance.

This test covers signed PR artifacts and same-key updates. Stable publication,
queue catch-up and recovery require their separately authorized checks. No
Release or legacy-data migration is performed by this checklist.
