# Optional text post-processing

Processing is disabled on upgrade and first install. Settings explicitly saves the enabled switch, a dropdown text model and the editing prompt. New settings default to **GPT-6 Luna**, API ID `gpt-6-luna`; an existing saved choice is preserved. Opening Settings, choosing a model and saving settings send no requests. The UI explains the separate text request and possible additional API cost.

## Model list

The bundled choices use documented Responses-compatible IDs. The metadata-only **Refresh model list** action calls `GET /v1/models` with the existing saved OpenAI key. The endpoint returns basic model metadata, not endpoint capabilities, so results are intersected with the documented supported list; audio, image, embedding and unknown IDs are not offered as text processors. GPT-4.1 nano is shut down and is excluded from new choices; a saved legacy value remains visible without substitution.

Refresh never selects or saves another model. A missing selected model stays selected and is labelled as absent from the current list. A denied list permission, missing key, network or response failure keeps the previous list and selection. There is no automatic refresh on opening/recreation, no automatic retry or redirect. Tests use local HTTP and synthetic credentials only.

Checked sources: [GPT-6 Luna ID and Responses support](https://developers.openai.com/api/docs/models/gpt-6-luna), [model catalog](https://developers.openai.com/api/docs/models), [list models schema](https://developers.openai.com/api/reference/resources/models/methods/list), [GPT-4.1 nano shutdown](https://developers.openai.com/api/docs/models/gpt-4.1-nano). Project-level availability is checked by an explicit refresh on the owner's device; documentation alone does not establish access for their key.

## Data and ownership

The existing `transcript` remains the recognized original. Audio/text are durably completed before the optional text request. Additive format-1 metadata holds the latest successful `processedText`, separate processing status, attempt number and the attempted model/prompt. Older audio, legacy text, drafts, IDs and capacity remain intact. A failed or cancelled repeat keeps the previous successful result. Deletion removes both versions, with the existing confirmation; active claims prevent deletion until work ends.

The shared session remains busy through `TRANSCRIBING -> PROCESSING`, including the overlay's continuous screen-on lease. It publishes `RESULT` only after the chosen final text is ready. Recognition followed by a second-stage failure preserves the original, publishes `ERROR`, and never inserts an original fallback or retries audio. The visible processing error is only **Error / Ошибка**; existing Copy/history recovery remains available.

Cancellation after recognition cancels the text request while retaining completed audio and the original. Generation and durable attempt tokens reject late responses. Restart restores processing as interrupted without restarting requests. Background/manual lock follow the existing cancellation/recovery contract, not a promise to continue while locked.

## History actions

Recognized and processed versions remain separately copyable. Copy and Process again are aligned 48 dp icon buttons, with version-specific accessibility labels and the same retry glyph as recognition retry. Pressing **Process text again** immediately snapshots the **current saved global model and prompt**, even when automatic processing is disabled. It always starts from the immutable recognized original. It has no intermediate editor, audio upload, global-settings write or editor/insertion token. Change the model/prompt in Settings before repeating.

Ordinary recording cards have a direct Delete button instead of a one-item menu. Linked editable drafts retain the existing two meaningful actions, Clear current text and Delete. The obsolete one-off repeat form is dismissed when restoring old UI state; scroll positions, expansions, Words edits and durable recording data remain readable.

## Transport and diagnostics

`POST /v1/responses` uses the existing key and `store=false`, with no tools or conversation. The editing prompt is separate from a JSON transcript data object, and instructions prohibit executing commands or answering questions in dictation. Only completed, nonempty assistant text is accepted; refusals, truncation, malformed and oversized responses fail without inserting partial text. Prompt separation does not guarantee perfect model instruction following.

Both billable POST bodies are one-shot, also blocking OkHttp's independent `503 Retry-After: 0` follow-up. Multipart recognition bytes, model, keywords and explicit retry semantics remain covered by tests. Model-list GET errors are intercepted before status-directed follow-ups. Cancellation closes the HTTP call. Local diagnostics record closed stage/action/error codes and numeric HTTP status; they do not record keys, prompts, transcripts, model-list payloads or provider error bodies. Text processing and model discovery have distinct diagnostic areas.

## Phone acceptance

1. Update over the existing app. Confirm history/key persist and automatic processing remains at the saved enabled setting. A new install defaults to disabled processing with GPT-6 Luna.
2. Open the model dropdown, select/save a choice, and refresh. Missing/forbidden list access must keep the choice. No text/audio should be sent by refresh. Model availability and actual generation require the owner's API permissions.
3. Dictate from main and the keyboard blob. Stop once: recognition then amber text processing. Hold still reveals Cancel. Only the final text may be inserted once into the original supported field; changed/unsupported fields retain Copy fallback. API 26–32 remain Copy only.
4. In History copy both versions. Change the global model/prompt in Settings, then press Process again: it starts immediately, uses the original, and never inserts into the former field. A failure shows only Error and leaves the original/previous success available.
5. Check short error diagnostics using Save logs ZIP. Cancel processing, start another recording, and restart after interrupted processing: no late overwrite, duplicate insertion or automatic network repeat. Check direct Delete confirmation and that linked-draft Clear remains separate.
6. Check light/dark, narrow/wide/Fold, large font and TalkBack. The two action buttons must align and remain reachable. Confirm the overlay holds the screen through both stages and releases it after result/error/cancel/teardown. Emulator evidence remains distinct from physical TalkBack/OEM/API 33+ insertion and live model-quality checks.

Screenshots use labelled synthetic data. No video, real user dictation or paid provider requests are part of cloud verification.
