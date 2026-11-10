# Owner signing identity on macOS

The maintainer/developer is **[IlyasYOY](https://github.com/IlyasYOY)**, as already stated in the MIT copyright and the application ID. A certificate's `CN` is self-declared metadata, not a verified developer account. Android installers/settings do not promise to display it as an author. Store publisher profiles are configured separately. Git contribution authorship stays accurate; do not rewrite old commits or third-party copyright notices.

The owner deliberately chose a **new local release key**, replacing the old Android Debug identity for future distribution. The new key cannot update the old installation through this pipeline; no signing lineage/key rotation is configured. **Uninstalling deletes local recordings, history, vocabulary, settings and the stored API key.** The current app has no audio export or vocabulary/settings export/import. Original and processed text can be copied separately from individual history items into another app before uninstalling; this does not preserve the recordings or restore history/settings. The diagnostic ZIP is not a data backup. If you need to retain these local data, do not uninstall. Do not assume Android backup will restore them. This change adds no migration/export functionality. Future APKs must keep the same new key and increase `versionCode`.

## Create locally

Use a trusted private Mac session, not a shared terminal, screen recording, cloud-synced directory or CI runner. Install a full JDK 17/21 from an official JDK distributor if needed; the script installs nothing. Verify `java -version` and `keytool -help` yourself. Download/review [the script](../scripts/create-signing-key.sh) from the reviewed commit, or use the reviewed checkout, then run it with Bash (macOS system Bash 3.2 is supported):

```bash
bash scripts/create-signing-key.sh
```

It asks for confirmation, GitHub nickname (default `IlyasYOY`), optional organization/country, a **new absolute local directory outside Git**, and a password twice. Choose a unique random password of at least 16 characters in your password manager. Input is hidden. Passwords are not printed, placed in command arguments or written to password/config/log files. JDK processes receive a short-lived environment reference; a compromised Mac/root can still inspect process memory/environment. The script cannot make an infected/shared machine safe or detect every cloud-synced folder. Do not source it or run it under a recording/debugger.

The result is a private `0700` directory with:

| File | Meaning |
| --- | --- |
| `release.p12` (`0600`) | Encrypted PKCS12 private keystore; never share publicly |
| `certificate.der` | Public certificate only |
| `public-info.txt` | Public nickname, alias and lowercase SHA-256 fingerprint; no password |

Alias: `voice-dictation`. RSA 4096, SHA256withRSA, 10,000-day certificate. PKCS12 uses **the same password for store and key**. Existing paths are refused; errors may leave a private partial directory, never an automatic overwrite/delete. The official [JDK keytool manual](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html) documents `-storepass:env` and `-keypass:env`.

Store the keystore in at least two encrypted, access-controlled backups; store the password separately in a password manager. Test restoring a backup and checking its public fingerprint. Losing either key or password can lose future update capability. Do not create a secret Gist: unlisted URLs are not access control. Never send keys/passwords through chat, issues, Actions artifacts, Library files, shell transcripts or a repository. Base64 is reversible, not encryption.

## Enter GitHub secrets yourself, after pipeline review

First configure the two protected Environments and public variables exactly as [the release runbook](signed-releases.md) describes. Do not add these secrets at repository/organization scope. No script here creates an Environment, changes its protections, creates a GitHub secret, enables releases or uploads a key automatically.

Use GitHub's Environment secret UI on your own trusted machine, or authenticated official GitHub CLI. The following optional commands send values directly over stdin to `gh secret set`, without a base64 file or clipboard. Review the script/workflow and Environment protection rules first. Set `KEY_DIRECTORY` to the **local path**, never a key/password literal. Confirm `gh auth status` shows your own account and the correct repository.

```bash
set +x
set -o pipefail
KEY_DIRECTORY="$HOME/VoiceDictationSigning" # adjust to your chosen private directory
for SIGNING_ENVIRONMENT in apk-stable-signing apk-pr-signing; do
  base64 < "$KEY_DIRECTORY/release.p12" | tr -d '\r\n' | \
    gh secret set APK_SIGNING_KEYSTORE_BASE64 --repo IlyasYOY/android-voice-dictation --env "$SIGNING_ENVIRONMENT"
done
read -r -s -p 'Existing local keystore password: ' VD_GH_SIGNING_PASSWORD
printf '\n'
for SIGNING_ENVIRONMENT in apk-stable-signing apk-pr-signing; do
  printf '%s' "$VD_GH_SIGNING_PASSWORD" | gh secret set APK_SIGNING_STORE_PASSWORD --repo IlyasYOY/android-voice-dictation --env "$SIGNING_ENVIRONMENT"
  printf '%s' "$VD_GH_SIGNING_PASSWORD" | gh secret set APK_SIGNING_KEY_PASSWORD --repo IlyasYOY/android-voice-dictation --env "$SIGNING_ENVIRONMENT"
done
unset VD_GH_SIGNING_PASSWORD KEY_DIRECTORY SIGNING_ENVIRONMENT
```

Stop if any command fails; do not enable anything until all entries/protections are correct. GitHub cannot show a saved secret back to you, so keep your own backups. Set each Environment's nonsecret `APK_SIGNING_KEY_ALIAS` to `voice-dictation`, and the repository's public `APK_SIGNING_CERT_SHA256` to the fingerprint from `public-info.txt`. The ledger pins this identity at initialization and the signer checks the actual keystore certificate before signing. There is no fallback to the old debug key.
