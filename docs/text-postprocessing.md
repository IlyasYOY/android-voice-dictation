# Optional text post-processing

This feature is disabled on upgrade and first install. Settings has an explicit Save for the enabled switch, text model and editing prompt. The suggested default is `gpt-4.1-mini`; the model ID remains editable. Saving settings sends no request. A failed or unreadable setting never silently enables processing. The UI explains that recognition and text processing are separate OpenAI requests and each explicit processing attempt may cost extra.

## Data and ownership

The existing `transcript` field remains the recognized original. The recorder/transcriber completes its durable audio-and-text commit before any optional text request. Additive format-1 metadata fields hold `processedText`, a separate processing status, attempt number, model and prompt. Older rows default to no processing; audio, legacy text, drafts, IDs and capacity remain intact. A previous successful result survives a failed or cancelled repeat. Deleting a row deletes both versions; active processing owns a claim and prevents deletion until cancelled or completed.

The shared session remains busy through `TRANSCRIBING -> PROCESSING`, so the existing overlay screen-on lease continues across both requests. It publishes `RESULT` only after the chosen final text is ready. Recognition success followed by processing failure publishes `ERROR` with the recognized text, no audio retry and no insertion trigger. Main offers Copy recognized text and Retry in History; the overlay offers its existing Copy/history recovery with a specific processing failure message. It never silently inserts an original fallback.

Cancellation after recognition cancels the text request and preserves the completed audio/original. Generation and repository attempt tokens reject non-cooperative late replies. Process restart restores `PROCESSING` as `INTERRUPTED`, without restarting a request. Closing/backgrounding the live session retains the original. Manual lock remains cancellation/recovery, not a promise to process while locked.

History exposes Recognized/Processed versions and a text-only repeat form. A repeat always reads the immutable recognized transcript, even if a prior edited result exists. Its model/prompt are scoped to that attempt and saved for the row, never written to global settings. The form survives configuration recreation. History has no editor token or insertion callback. It never re-uploads audio or inserts into a former field. Explicit audio transcription retry from history also applies the currently saved optional processing setting after saving recognition; it still has no editor token.

## Transport

`OpenAiTextProcessor` makes one HTTPS `POST /v1/responses` with the existing credential. The editing prompt is separate from a JSON user-data object containing the transcript. Instructions explicitly prohibit treating dictation as commands/questions to execute. No tools, conversation, background execution, redirects or connection retries are enabled; `store=false`. Cancellation cancels the OkHttp call. The response must be completed, non-empty assistant output text; refusals, truncation, malformed and oversized replies are errors. The app never logs prompts, text or keys. There is no claim that prompt separation eliminates every possible model instruction-following error.

References checked during implementation: [Responses create](https://developers.openai.com/api/reference/resources/responses/methods/create), [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini). Availability/billing depends on the owner's OpenAI project; no paid endpoint or real dictation is exercised by development tests.

## Acceptance on a phone

1. Update the existing app. Existing audio/history and key remain. Settings starts with post-processing disabled. Dictate once; behavior matches the previous version.
2. Enable processing, choose the model and edit the prompt, then Save. Start from main and from the keyboard blob. Stop once: recognition then amber Text processing; hold still reveals Cancel. There must be no intermediate insertion. After success the overlay attempts insertion once on the original supported field; changed/unsupported fields use the existing Copy fallback. API 26–32 still use Copy.
3. In History expand the row. Select Recognized/Processed and copy each. Reprocess with a different prompt/model; check that the original remains and Settings is unchanged. No audio upload or insertion into the previous field should occur.
4. Cause a second-stage error (for example choose an unavailable text model). Original remains copyable, with an explicit processing error and no silent insertion. Retry from History with a supported model. If a previous successful version existed, it remains labelled as the previous result during/after a failed repeat.
5. Cancel while text processing, then start a new recording. The original stays in History; a late response cannot replace the next recording or insert. Cancel a history repeat with its Cancel action. Restart after interrupted processing: data remains and no request resumes automatically.
6. Try narrow phone, landscape, Fold flat/book/tabletop, large font, TalkBack and light/dark. Model/prompt, version selectors, copy and repeat must stay reachable; processing status is announced, and long-click/cancel remain labelled. Confirm screen timeout remains held during overlay text processing, then returns normally on success, error, cancel and service teardown. Physical device/OEM/accessibility checks remain distinct from emulator evidence.

The recorded verification and deliverables belong to the PR and private delivery page. Screenshots use explicitly labelled synthetic data. No videos or paid API requests are required.
