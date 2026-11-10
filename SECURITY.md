# Security policy

Security work is best effort. Review fixes against the latest main branch; older prototypes and debug fixtures are not supported as production deployments. There is no guaranteed response time or security certification.

## Reporting a vulnerability

If this repository's **Security → Report a vulnerability** control is available, use GitHub's private vulnerability reporting. Availability is a repository setting; this document does not claim that it is enabled. If it is unavailable, open a **non-sensitive** issue asking the maintainer to arrange a private reporting channel. Do not publish the vulnerability, exploit details, personal data, API keys, keystores or a full diagnostic archive while requesting that channel. No private reporting email is designated here.

The owner should plan a monitored private reporting route before launch. GitHub documents private vulnerability reporting for public repositories; if that route is chosen, enable it immediately after the owner changes visibility and verify the reporting control before inviting reports. Until then the non-sensitive contact-request fallback above applies. See [GitHub configuration guidance](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository). Do not treat an ordinary issue or PR as private, even while preparing a public repository.

In a private report, include affected version/source commit, Android/API and keyboard/editor versions if relevant, minimal synthetic reproduction, expected/observed impact, and the smallest sanitized evidence needed. If a credential was exposed, revoke/rotate it with its provider; deleting it from the latest commit does not invalidate it or remove old history/caches. Coordinate any history rewrite, artifact removal or release replacement with the owner.

## Boundaries to preserve

- The key is user-provided and encrypted locally with Android Keystore. Never embed an API/signing key in source, APKs, tests, issues or logs.
- Recording is local until an explicit Stop/retry; optional text editing is a separate provider request. See the privacy statement for data and provider-retention limits.
- Accessibility grants broad OS access. Maintain original-editor/session/caret/visibility checks, sensitive-field restrictions and one commit attempt. Never introduce automatic retargeting, Send or full-field replacement as a compatibility fix.
- History and audio remain recoverable across provider failures. Deletion is explicit. Do not confuse clearing a key with clearing records, or ordinary deletion with secure erasure.
- Diagnostics use a closed enum vocabulary; do not add arbitrary messages, request/response bodies, text, headers, clipboard, URLs, other-app data or stack traces.
- Signing material must never be exposed to untrusted PR code, build scripts, restored caches or downloaded artifacts. Keep signing manual until separately designed/reviewed protected automation is approved. Draft PR #17 must not be treated as deployed infrastructure.

A successful scanner/build/review does not prove the absence of vulnerabilities. The publication audit also has explicit limits; resolve its unavailable artifacts/logs and personal-metadata decisions before changing visibility.
