# Third party notices

The project's MIT license does not relicense third-party components. Keep their notices and terms when redistributing source or APKs. This inventory describes the 0.19.2 release runtime; test/build tools have their own licenses and are not all shipped in the app.

| Runtime family | License / upstream |
| --- | --- |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0; [AndroidX source](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library, kotlinx.coroutines and runtime annotation dependencies | Apache-2.0; [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines), [annotations](https://github.com/JetBrains/java-annotations) |
| OkHttp 4.12.0 and Okio | Apache-2.0; [OkHttp](https://github.com/square/okhttp/tree/parent-4.12.0), [Okio](https://github.com/square/okio) |
| OkHttp bundled Public Suffix List data | MPL-2.0, as stated by the upstream `okhttp3/internal/publicsuffix/NOTICE`; [list source](https://publicsuffix.org/list/), [license](https://mozilla.org/MPL/2.0/) |

Unmodified license texts are in `LICENSES/`. The APK also carries the project license, Apache-2.0, MPL-2.0 and attribution information in `assets/licenses/`; the upstream OkHttp public-suffix NOTICE remains in the dependency resources. Corresponding upstream source is available at the links above. No project modification to the bundled Public Suffix List is introduced by this change.

Before each release, inspect the resolved `releaseRuntimeClasspath` and relevant upstream POM/source notices for added/changed dependencies. Record the resolved dependency report with release verification evidence. This file is not an exhaustive license certification of arbitrary future dependency graphs, development tools or modified builds.
