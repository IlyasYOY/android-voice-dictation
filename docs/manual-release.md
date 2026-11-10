# Manual release preparation

No signing workflow, signing secret/environment, tag or published Release is added by this change. Draft PR #17 is not a release dependency. Prepare a candidate privately, then rebuild/verify the final accepted merged revision before deliberately publishing it. A successful build does not resolve the separate publication audit.

## Existing update identity

The accepted sideloaded APK uses certificate SHA-256:

```text
66088e33b7734f65bc26a0268c63f47875babe65c1a81558bc6f806e23b85a68
```

This is public certificate metadata, not a private key. Its subject is the legacy Android Debug identity. Keep the existing private keystore outside Git and access-controlled; do not upload it as an artifact or copy it into Actions. The verifier deliberately defaults to this certificate so a silently generated debug/new key cannot pass as an update. Independent builds signed with another key cannot update existing installs.

The legacy certificate preserves current sideload compatibility, but it is not a Play Store production-signing plan. Android's [signing guide](https://developer.android.com/studio/publish/app-signing) distinguishes debug and release signing. Decide a production/store identity and a user-data/update migration separately if those distribution channels are wanted. Do not rotate the current key as incidental publication cleanup.

## Build in a trusted clean checkout

Use the wrapper and existing approved SDK/JDK; no license acceptance or package installation is performed by these commands. Python 3 is required for the verifier and its offline unit tests.

```sh
bash scripts/check.sh
./gradlew --no-daemon :app:assembleRelease
./gradlew --no-daemon :app:dependencies --configuration releaseRuntimeClasspath
```

Record the dependency report outside Git and inspect license/NOTICE changes against THIRD_PARTY_NOTICES.md. The APK includes MIT, Apache-2.0, MPL-2.0 and attribution text under `assets/licenses/`. Upstream terms still apply; the project's MIT license does not replace them.

Use only the unsigned APK produced from this trusted checkout. Revision metadata is useful for mismatch detection but is not proof that an arbitrary downloaded APK was built from the claimed source. Do not feed PR-supplied/downloaded binaries to a signing session with the maintainer key.

## Sign without exposing the key/password

Use `zipalign` and `apksigner` from the installed pinned Build Tools (35.0.0). Keep all candidates and reports in a private directory outside the checkout. Set `SIGNING_KEYSTORE` and `SIGNING_ALIAS` to the existing identity. Enter passwords privately into the environment variables `VD_SIGN_STORE_PASS` and `VD_SIGN_KEY_PASS`; never put literal passwords in CLI arguments, shell history, PR text or trace logs. Disable shell tracing. These examples assume those variables are already set privately and exported.

```sh
set +x
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$PRIVATE_OUTPUT/aligned.apk"
apksigner sign --ks "$SIGNING_KEYSTORE" --ks-key-alias "$SIGNING_ALIAS" \
  --ks-pass env:VD_SIGN_STORE_PASS --key-pass env:VD_SIGN_KEY_PASS \
  --out "$PRIVATE_OUTPUT/voice-dictation.apk" "$PRIVATE_OUTPUT/aligned.apk"
unset VD_SIGN_STORE_PASS VD_SIGN_KEY_PASS
```

No new key is generated. Verify the signer against the trusted fingerprint, package/version, v2/v3 signatures, alignment, required license entries and full source-revision metadata:

```sh
python3 scripts/verify-release.py "$PRIVATE_OUTPUT/voice-dictation.apk" \
  --revision "$(git rev-parse HEAD)" --version-code 42 --version-name 0.19.2 \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --report "$PRIVATE_OUTPUT/verification.json"
```

Adjust version arguments deliberately when releasing a newer version. The report destination must not already exist; the verifier never signs, uploads or publishes. Inspect its result and record the APK SHA-256, certificate, version and source commit. The report verifies bytes/declared metadata, not reproducible source provenance.

## Acceptance and publication gates

Install the previous accepted build, then install the candidate with `adb install -r` without uninstalling between them. Verify version, preserved app UID, startup and representative retained data; UID alone does not establish that every record survived. Check playback/history, explicit transcription/post-processing with a separately consented synthetic provider scenario, and floating behavior on a supported Android 13+ phone. State unrun scenarios explicitly. Do not send personal dictation or paid API requests from automated tests.

Before a public Release, accept all publication PRs, rebuild/verify their final merged source, resolve the audit's unavailable artifacts/logs and owner decisions, review vulnerability reporting and the legacy signing limitation. Publish only the reviewed APK plus verification/checksum and license/notice information, with honest device/provider limitations. Do not include private test data, raw logs, keys, temporary aligned APKs or development fixture APKs. Release/tag creation and public visibility are separate owner-authorized actions, not effects of this document or verifier.
