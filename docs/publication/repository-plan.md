# Repository presentation and old branch plan

Snapshot: 2026-11-10, main `19614700da4671eaceaf1c2a6ee4445b24dc20a0`.
This PR proposes settings and cleanup decisions; it does not change visibility, settings, branches, tags, PR state, secrets or workflows.

## Description and topics

The checked [metadata proposal](repository-metadata.json) contains the short description and seven topics for the repository's About section. Current description is empty and topics are empty. Apply them through GitHub's About editor after reviewing/merging the preparation PRs; the JSON file itself does not apply settings. Do not use claims such as universal app compatibility, offline transcription or guaranteed confidentiality.

## Old branches

All 24 branch heads and 23 pre-existing PRs were inventoried. Of the 24 branch heads, 23 are ancestors of main (including main itself). No tag or Release was present. The table includes only the original branch inventory, not new publication-preparation branches.

| Branch | Head | Already in main | Branch-only commits |
| --- | --- | --- | --- |
| `ci/stable-apk-signing` | `1ec543a523c5` | no | 2 |
| `design/soft-current` | `073091634e1c` | yes | 0 |
| `feat/adaptive-history-pane` | `07c7bb6b6425` | yes | 0 |
| `feat/audio-reactive-current` | `cd0e5cadeadd` | yes | 0 |
| `feat/custom-vocabulary` | `044ca44e8097` | yes | 0 |
| `feat/diagnostic-log-export` | `99591e6e7e0c` | yes | 0 |
| `feat/durable-recording-storage` | `9ba5333bb5e7` | yes | 0 |
| `feat/first-run-setup` | `7c0fff59729d` | yes | 0 |
| `feat/history-count-limit` | `d17ab63c7010` | yes | 0 |
| `feat/history-waveform-seek` | `701bfb82ef9c` | yes | 0 |
| `feat/immediate-record-capture` | `f99bf58022f5` | yes | 0 |
| `feat/openai-billing` | `e1343389a656` | yes | 0 |
| `feat/record-only` | `d0fc4555e880` | yes | 0 |
| `feat/remember-floating-position` | `9f6d886c9c7f` | yes | 0 |
| `feat/shared-blob-controls` | `e194b51e53bf` | yes | 0 |
| `feat/swipe-dictation-history` | `0a70fbb11d91` | yes | 0 |
| `feat/text-postprocessing` | `fd5e6f49b05b` | yes | 0 |
| `feat/vivid-current` | `b2cb40c5e5b7` | yes | 0 |
| `feat/working-dictation-prototype` | `6db64528c42a` | yes | 0 |
| `fix/allow-dictation-screenshots` | `9c7cdc92ca06` | yes | 0 |
| `fix/editor-insertion-compatibility` | `0c5fc220e09d` | yes | 0 |
| `fix/floating-recording-screen-on` | `437ed7c449ca` | yes | 0 |
| `fix/fold-posture-layout` | `73b7a27cf1d2` | yes | 0 |
| `main` | `19614700da46` | yes | 0 |

After the six preparation PRs are accepted, the owner may delete fully merged feature branch refs as housekeeping if desired. That is optional and is not performed here. Removing a ref does not erase its commits, remove personal email from Git history, revoke a credential or remove PR discussions/artifacts. Keep main. Do not force-push or rewrite history as branch cleanup.

## Draft PR 17 and signing

[PR #17](https://github.com/IlyasYOY/android-voice-dictation/pull/17) remains the only original open PR, still draft, on `ci/stable-apk-signing`. Its head is not merged. It proposes a workflow_run listener, a protected `apk-signing` environment and signing secret references. Main has no active signing workflow and this preparation does not create its secrets/environment or enable it.

Recommended decision: leave #17 draft or close it unmerged when the owner is ready, and retain the branch until its useful design/tests have been reviewed. Do not merge it merely to obtain public release APKs. Public-repository threat modeling must cover untrusted/same-repository PRs, mutable artifacts, permissions, cache/source trust and environment protection before any future signing automation. Manual preparation with the existing certificate is a separate path and needs no signing secret in Actions.

## Owner launch sequence

1. Review/merge the six preparation PRs: audit #24, README #28, privacy #25, contributor/security #26, MIT/manual release #29 and this plan #27. The audit and this plan can merge independently; privacy precedes contributor guidance, and privacy/contributor/license-release documentation precedes the README's new cross-links. Each preparation PR targets main and has a separate scope; none is automatically merged.
2. Resolve the publication audit's unavailable artifact/log bytes, the personal-email decision and private links in old PR bodies. Repeat the audit over the final refs and newly generated Actions runs/artifacts. A passing source build does not resolve those exposure gates.
3. Plan a monitored private reporting route. If using GitHub private vulnerability reporting, enable it immediately after the owner changes visibility (the GitHub feature is for public repositories), verify the reporting control, and configure notifications. Until then SECURITY.md permits only a non-sensitive request to arrange private contact; do not announce an unavailable channel as operational.
4. Review manual signing evidence and the legacy certificate's distribution limitations. A release candidate is not a published release. Keep private signing material out of source, logs, caches and downloadable artifacts. No new key is generated by this plan.
5. Apply the About metadata if desired. Only the owner changes visibility after deciding the gates above are satisfied. GitHub makes Actions history/logs visible when converting to public; review that consequence before confirming.
6. Publish a reviewed signed release/tag only as a separate deliberate action with matching source, certificate and digest. Do not treat unsigned/debug CI artifacts as maintainer releases.

The public switch and release publication remain owner actions. These reports do not claim the repository is safe to publish while the audit's missing-byte coverage is unresolved.
