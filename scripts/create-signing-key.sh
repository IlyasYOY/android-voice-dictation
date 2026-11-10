#!/bin/bash
# macOS system Bash 3.2 compatible. Run locally, never source this file.
set +x
set -euo pipefail
umask 077
unset VD_NEW_KEY_PASSWORD VD_NEW_KEY_CONFIRM
trap 'unset VD_NEW_KEY_PASSWORD VD_NEW_KEY_CONFIRM' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ -t 0 && -t 1 ]] || fail 'Run interactively in a private Terminal; redirected input/output is refused.'
command -v keytool >/dev/null 2>&1 || fail 'Install an official JDK 17 or 21, then put its bin directory on PATH (see docs/signing-key.md).'
command -v shasum >/dev/null 2>&1 || fail 'shasum is required.'
[[ $# -eq 0 ]] || fail 'No arguments accepted. The script asks for values privately.'

printf '%s\n' 'Creates a NEW Android signing identity. It cannot update the old installation.' \
  'Before uninstalling the old app, save important history/audio/text/settings. Uninstall may delete them.' \
  'Certificate names are self-declared; Android does not guarantee showing a developer name.' \
  'No upload, GitHub secret change, password file, clipboard operation or shell command with a literal password is performed.'
read -r -p 'Continue creating a new identity? Type CREATE: ' answer
[[ "$answer" == CREATE ]] || fail 'Cancelled; nothing created.'
read -r -p 'GitHub developer nickname [IlyasYOY]: ' developer
developer=${developer:-IlyasYOY}
[[ "$developer" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,38}$ ]] || fail 'Use a GitHub nickname: letters, digits and hyphens, at most 39 characters.'
read -r -p 'Certificate organization (optional; letters, numbers, spaces, dots, hyphens): ' organization
[[ "$organization" =~ ^[A-Za-z0-9.\ -]*$ ]] || fail 'Unsupported organization characters.'
read -r -p 'Country code (optional, two uppercase letters): ' country
[[ -z "$country" || "$country" =~ ^[A-Z]{2}$ ]] || fail 'Country must be empty or two uppercase letters.'
read -r -p "NEW private directory (absolute, outside Git/cloud sync) [$HOME/VoiceDictationSigning]: " destination
destination=${destination:-$HOME/VoiceDictationSigning}
[[ "$destination" == /* && "$destination" != */ && ! -e "$destination" && ! -L "$destination" ]] || fail 'Choose a new absolute directory without a trailing slash; existing paths are never overwritten.'
# Refuse an existing Git worktree; choosing a local non-synced disk is the owner's responsibility.
parent=$(dirname -- "$destination")
[[ -d "$parent" && ! -L "$parent" ]] || fail 'The parent must be an existing real directory.'
if command -v git >/dev/null 2>&1 && git -C "$parent" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  fail 'Choose a directory outside a Git worktree.'
fi
read -r -s -p 'New password (at least 16 characters; save in your password manager): ' VD_NEW_KEY_PASSWORD
printf '\n'
read -r -s -p 'Repeat password: ' VD_NEW_KEY_CONFIRM
printf '\n'
[[ ${#VD_NEW_KEY_PASSWORD} -ge 16 && "$VD_NEW_KEY_PASSWORD" == "$VD_NEW_KEY_CONFIRM" ]] || fail 'Passwords differ or are too short; nothing created.'
unset VD_NEW_KEY_CONFIRM
export VD_NEW_KEY_PASSWORD
mkdir -m 700 -- "$destination" || fail 'Cannot create new directory; nothing overwritten.'
identity="CN=$developer"
[[ -z "$organization" ]] || identity="$identity, O=$organization"
[[ -z "$country" ]] || identity="$identity, C=$country"
# PKCS12 uses the same password for store and key. Values are environment references,
# never command-line password arguments. Suppress keytool diagnostics, not into a log.
if ! keytool -genkeypair -keystore "$destination/release.p12" -storetype PKCS12 \
  -alias voice-dictation -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
  -dname "$identity" -storepass:env VD_NEW_KEY_PASSWORD -keypass:env VD_NEW_KEY_PASSWORD \
  -noprompt >/dev/null 2>&1; then
  fail 'keytool failed. A partial private directory may remain; inspect locally. No automatic deletion or retry.'
fi
chmod 600 "$destination/release.p12"
keytool -exportcert -keystore "$destination/release.p12" -storetype PKCS12 \
  -alias voice-dictation -storepass:env VD_NEW_KEY_PASSWORD \
  -file "$destination/certificate.der" >/dev/null 2>&1 || fail 'Public certificate export failed; keep the keystore.'
unset VD_NEW_KEY_PASSWORD
fingerprint=$(shasum -a 256 "$destination/certificate.der")
fingerprint=${fingerprint%% *}
printf 'Developer: %s\nAlias: voice-dictation\nCertificate SHA-256: %s\n' "$developer" "$fingerprint" > "$destination/public-info.txt"
printf '\nCreated private keystore: %s/release.p12\n' "$destination"
printf 'Alias: voice-dictation\nPublic certificate SHA-256: %s\n' "$fingerprint"
printf '%s\n' 'Keep the keystore and password in separate encrypted backups. Test restoring a backup before relying on it.' \
  'Follow docs/signing-key.md to enter secrets yourself in GitHub Environments.' \
  'Never upload the keystore/password to a Gist, chat, issue, artifact or repository. Base64 is not encryption.'
