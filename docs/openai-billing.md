# OpenAI Billing shortcut

Settings → OpenAI connection includes a full-width **View billing / Посмотреть баланс** button.
It opens `https://platform.openai.com/settings/organization/billing/overview` with an external
`ACTION_VIEW`/`BROWSABLE` intent. The app does not fetch or display an account balance and does
not attach API keys, headers, extras, query parameters, recordings or text to this navigation.
The browser may require a separate OpenAI login and organization selection.

If no handler exists or Android blocks the launch, settings remain available and announce a
localized error. The user can enable a browser and try again. No additional app permission or
package-visibility query is required: launch directly and handle failure.

## Phone acceptance

1. Update over the current installation without uninstalling; verify the saved key, history and
   processing settings remain. Open Settings and scroll to OpenAI connection.
2. Tap View billing. Confirm the external browser opens the official billing page. Sign in there
   if necessary, then return to the app. No balance is displayed by Voice Dictation.
3. Check without a saved API key: navigation should still work and must not save/paste a key.
4. On a test device without an enabled browser, the app should stay in Settings and show the
   localized browser error. Enable a browser and retry; the error should clear after launch.
5. Check Russian/English, light/dark themes, large font and TalkBack focus/activation. The button
   remains a normal labelled button and the error uses a polite accessibility live region.

Automated Android coverage intercepts the real MainActivity browser launch before dispatch, checks
the complete destination and absence of payload, exercises missing/restricted browser recovery,
and renders Russian dark/200% font settings. Those tests make no browser/provider network request;
they do not verify a signed-in billing page or a physical phone's browser.
