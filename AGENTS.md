# Repository instructions

- Scope: Android/Kotlin/Compose scaffold. Do not turn the preview into real recording or paid AI integration without an explicit task.
- Preserve private visibility. Do not add secrets, user audio, signing keys or `local.properties` to Git.
- Read `README.md`, `docs/architecture.md` and `docs/cloud-environment.md` before changing setup.
- Use JDK 17 or 21 and the committed Gradle Wrapper. Keep Kotlin and Compose compiler plugin versions identical.
- Run `./scripts/check.sh` before reporting success. Run `./gradlew :app:connectedDebugAndroidTest` only with an available emulator/device; distinguish compile-only from executed UI tests.
- No implicit SDK license acceptance. Installation requires the user's agreement and `ANDROID_ACCEPT_LICENSES=yes`.
- Do not create a cloud environment, use a user computer, merge/deploy, change repository visibility, or set up billing unless the current user request authorizes that action.
- Keep domain logic testable without Android; keep Compose screens stateless. Avoid speculative frameworks/modules.
- After edits, report exact passed/failed/unrun checks and the verified remote commit. Do not fabricate screenshots.
