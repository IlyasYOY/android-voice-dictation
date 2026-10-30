# Pinned toolchain

Verified against primary documentation on 2026-10-30. This is a conservative compatible baseline, **not** a claim to use the newest releases.

| Component | Pin |
|---|---|
| Android Gradle Plugin | 8.11.1 |
| Gradle | 8.13 |
| Kotlin / Compose compiler plugin | 2.2.21 / 2.2.21 |
| JDK runtime | 17 or 21; Java/Kotlin bytecode target 17 |
| Android compile / target / minimum SDK | 36 / 36 / 26 |
| Android Build Tools | 35.0.0 |
| Compose BOM | 2025.08.00 |
| Activity Compose | 1.10.1 |
| Lifecycle | 2.9.4 |
| Core KTX | 1.16.0 |
| JUnit | 4.13.2 |
| AndroidX test JUnit / runner / Espresso | 1.3.0 / 1.7.0 / 3.7.0 |
| Linux command-line tools | 15859902 (22.0) |

## Why these work together

- [AGP 8.11 compatibility](https://developer.android.com/build/releases/agp-8-11-0-release-notes): SDK up to 36, Gradle 8.13, Build Tools 35.0.0, JDK 17 minimum.
- [Kotlin's compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html): Kotlin 2.2.21 supports AGP through 8.11.1 and Gradle through 8.14.
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html): Java 21 can run Gradle 8.5 and newer.
- [Compose August 2025 release](https://android-developers.googleblog.com/2025/08/whats-new-in-jetpack-compose-august-25-release.html) identifies BOM 2025.08.00. [Compose 1.9 requirements](https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.9.0) fit this toolchain.
- AndroidX releases: [Activity](https://developer.android.com/jetpack/androidx/releases/activity#1.10.1), [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.9.4), [Core](https://developer.android.com/jetpack/androidx/releases/core#1.16.0), [Test](https://developer.android.com/jetpack/androidx/releases/test).

Lint keeps the three version-update advisories (`AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable`) visible at informational severity because the compatible set is intentionally pinned. Other warnings remain errors; functional, privacy and API checks are not suppressed.

Versions live in `gradle/libs.versions.toml`, `app/build.gradle.kts`, the wrapper properties, and `scripts/android-env.sh`. Upgrade the compatible set deliberately and rerun all checks. No dynamic `+` or snapshot dependencies are used. This is version pinning, not a complete transitive-dependency lockfile.

## Download integrity

[Official Gradle checksums](https://gradle.org/release-checksums/):

- Gradle 8.13 binary ZIP: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`
- Gradle 8.13 wrapper JAR: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`

The generated standard wrapper JAR and both launchers are committed. Distribution SHA-256 is enforced in `gradle-wrapper.properties`; CI also verifies the wrapper JAR before executing it.

[Official Android downloads](https://developer.android.com/studio): command-line tools Linux 15859902 SHA-256 is `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`; setup verifies it before extraction.

The SDK platform and Build Tools IDs are pinned. `platform-tools` is selected by Google's stable channel and may update; it is used for device tooling, not source compilation. SDK package revisions and the Linux runner base image are not fully hermetic.
