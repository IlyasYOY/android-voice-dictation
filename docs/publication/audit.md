# Publication audit

Snapshot: 2026-11-10, main `19614700da4671eaceaf1c2a6ee4445b24dc20a0` (0.19.2).
This is a bounded audit report, not a guarantee that no secret exists. Keep the repository private until the unresolved items below have been reviewed. Nothing was deleted, rewritten, revoked or published by this audit.

## Scope and results

| Surface | Coverage | Result |
| --- | --- | --- |
| Git | Full, non-shallow checkout; 24 remote branch heads, 23 PR heads, no tags; 175 unique commits | Gitleaks 8.30.1, default rules, 100% redaction: no findings |
| Available Git objects | All 1,192 blob objects, including available objects beyond a path-based diff | Separate Gitleaks byte scan: no findings |
| GitHub text/metadata | All pages: 23 PRs (including draft #17), 116 conversation comments, 33 inline comments, 29 reviews, repository/branch/tag/release/run/artifact metadata | Separate Gitleaks scan: no findings; no personal email in PR/comment/review free text |
| Historical binary evidence | 32 PNG versions and 2 verification-report ZIP versions in Git history | All 32 images visually inspected; 32 OCR passes completed; extracted ZIP members and OCR text separately scanned with Gitleaks: no findings. Images show synthetic test data, including a masked test-key field |
| Releases | REST API returned no releases or release assets | Nothing available to scan on this surface; no release created |
| Actions artifacts | 61 listed, none marked expired, 341,453,917 compressed bytes | **Bytes not scanned**: redirected downloads denied by the cloud network boundary |
| Actions run logs | 161 run-log downloads attempted | **Bytes not scanned**: 148 redirect/download denials, 9 HTTP 404 responses, 4 HTTP 403 responses |

Gitleaks cannot establish whether a credential is live, inspect arbitrary encrypted data, or replace human review. OCR can miss pixels. Scanning metadata is not scanning artifact contents.

## Personal and private metadata

One personal Gmail identity, masked here as `i***@gmail.com`, appears in author or committer metadata of **148 unique commits** (296 author/committer occurrences). The other identities are GitHub noreply identities. Changing the latest Git configuration or adding a mailmap does not remove the address from old Git objects. On 2026-11-10 the owner explicitly accepted publication of this existing email metadata. No history migration is requested; the address remains in the original commits. New preparation commits use a generic Codex noreply identity.

At the original audit snapshot, PR bodies [#18](https://github.com/IlyasYOY/android-voice-dictation/pull/18), [#20](https://github.com/IlyasYOY/android-voice-dictation/pull/20) and [#21](https://github.com/IlyasYOY/android-voice-dictation/pull/21) contained three private ChatGPT artifact/Page links. Their targets were not opened or copied into this report. On 2026-11-10, with the owner's authorization, only those three URLs were removed from the current descriptions. Read-back verified that no private ChatGPT URL remains there and all non-URL body text was preserved. This updates the current descriptions; external Pages and other stored copies were not changed. No private URL is reproduced here.

The APK signer certificate is public metadata; the private keystore is not. No keystore, API credential or real dictation was added to this report or these PRs. No credential validation or paid provider request was performed.

## Unresolved download boundary

The first inspected artifact redirect targeted `productionresultssa30.blob.core.windows.net` and returned `Forbidden`. Requests stopped at that boundary; there was no attempt to bypass it. Further redirected hosts may differ. This is not evidence that an artifact is clean or expired. Repeat the byte audit in an authorized environment with access to the actual GitHub Actions storage destinations, then replace these incomplete results with verified counts and findings.

GitHub documents that [Actions history and logs become visible when a private repository becomes public](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility). Do not dismiss inaccessible logs merely because they are old. The report does not authorize deleting runs/artifacts as a shortcut.

## Reproduction without printing matches

Fetch all heads, PR heads and tags into a private checkout (not just the default remote fetch refspec). Verify `git rev-parse --is-shallow-repository` is false. Use a trusted Gitleaks binary; the audited Linux x64 v8.30.1 archive digest was `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`.

```sh
gitleaks git --redact=100 --log-opts='--all --full-history' \
  --report-format=json --report-path=/private/location/git-report.json .
gitleaks dir --redact=100 --report-format=json \
  --report-path=/private/location/metadata-report.json /private/location/metadata
```

Keep inventories, raw metadata, OCR output, downloaded bytes and scanner reports outside Git with restricted permissions. Enumerate/paginate repository branches, PRs, comments, reviews, releases, workflow runs and artifacts; record download failures individually. Unpack archives with path/size/depth limits before scanning members; visually inspect images for personal content. Scan all available blobs as well as history diffs. Never print a matched value, upload it for validation, or put a suspected credential into a PR comment.

Repeat against the final merged publication tree and any new refs, runs or artifacts immediately before the owner changes visibility. Unreachable server objects, deleted/expired content, LFS objects not fetched, inaccessible attachments and external linked Pages are outside this report's byte coverage.
