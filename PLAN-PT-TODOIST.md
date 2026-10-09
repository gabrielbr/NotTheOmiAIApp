# Plan: Portuguese transcription + task extraction to Todoist Inbox

## Decisions (2026-10-09)

- **Todoist: Option A**, share each task to the Todoist app. The app stays without INTERNET permission.
- **Language: mixed Portuguese/English.** Whisper auto-detects the language per 30-second window. Vosk can only load one language, so the live draft uses the Portuguese model, and the final Whisper transcript handles both languages.

## Status

| Item | State |
|---|---|
| Whisper `language="auto"` when the loaded model is multilingual, otherwise `"en"` (`whisper_jni.cpp`) | Done. Native JNI safety suite passes (ASan+UBSan); cross-compiled for arm64-v8a and x86_64 |
| Whisper model swapped to multilingual `ggml-small-q5_1.bin` (SHA-256 `ae85e4a9…11bb`, cross-checked against Hugging Face's LFS hash) | Done |
| Vosk live-draft model swapped to `vosk-model-small-pt-0.3` (archive SHA-256 `6e1ce909…93b7`, per-file manifest regenerated) | Done. Installer host tests pass against the real archive |
| `TaskExtractor`: offline PT+EN cue-phrase finder, plus `tests/tasks/run_host_checks.py` | Done; host checks pass |
| `TasksActivity`: review/edit/select, then share each task to `com.todoist`, plus a "Find tasks for Todoist" button on each saved recording | Done; builds. Not run on a device yet |
| All existing host suites (capture, hybrid, job, whisper-java, omi, omi-capture, omi-composition) | Pass |
| `./gradlew assembleDebug assembleRelease assembleDebugAndroidTest lintDebug` | Pass. Lint shows only translation warnings, the same kind the existing screens have |
| `tests/hybrid/verify_apk.py` on a signed arm64 release APK | Pass: both models pinned, native libs present, `internet_permission: false` |
| On-device check: PT/EN accuracy with your Omi, and the Todoist share flow | **Pending; needs your phone** |
| New applicationId / app name (Phase 0) | Pending |
| Notification after refinement ("N tarefas encontradas") | Deferred. The per-recording button covers v1 |
| Portuguese UI strings | Deferred |

## Feasibility: yes

| Need | Today (0.4.4) | What it takes |
|---|---|---|
| Android app | Native Java 17, minSdk 26, BLE Omi + phone mic | Keep as is |
| Portuguese speech | English only: Vosk `small-en-us` (live draft) + Whisper `small.en` (refine), `language="en"` hard-coded in `app/src/main/cpp/whisper_jni.cpp:104` | Swap both models for multilingual/PT ones, pass language through JNI |
| Find tasks in transcript | Nothing ("No AI summaries yet") | New `TaskExtractor` running on the refined transcript |
| Send to Todoist Inbox | App has **no INTERNET permission** by design, and 3 checks assert it (`tests/hybrid/verify_apk.py`, `tests/whisper-device/verify_apk.py`, `scripts/sign_release.py`) | Option A: hand off to the Todoist app via Android share (stays offline). Option B: add INTERNET and call the Todoist API |

The hardest part is not technical. It's the decision to drop the app's "no internet" promise, or keep it by using the Todoist app as the bridge.

## Phase 0: Fork hygiene (½ day)

1. Change `applicationId`/`namespace` from `app.nottheomi.ai` to your own (e.g. `br.gabriel.omitarefas`), so it installs next to the original and never collides with its signer.
2. Change the app name, the About text (`MainActivity.java:302`) and the version. Keep `LICENSE` (MIT) and `THIRD_PARTY_NOTICES.md`.
3. Set up the build on Linux/WSL: JDK 17, SDK 34, build-tools 34.0.0, NDK 27.2.12479018, CMake. Run `scripts/prepare_model.py`, `scripts/build_whisper.py` and `./gradlew assembleDebug` to get a known-good baseline before changing anything.

## Phase 1: Portuguese (2–3 days)

**Live draft (Vosk)**
- Replace `vosk-model-small-en-us-0.15` with `vosk-model-small-pt-0.3` (~31 MB, Apache-2.0).
- Files: `scripts/prepare_preview.py` (URL + SHA-256), `PreviewModelInstaller.java:28` (`ROOT`), `DEPENDENCIES.json` (`preview_model`).

**Final transcript (Whisper)**
- Replace `ggml-small.en-q5_1.bin` with the multilingual `ggml-small-q5_1.bin` (~190 MB, same size and speed class). `.en` models cannot do Portuguese.
- Files: `scripts/prepare_whisper.py`, `ModelInstaller.MODEL_FILE`, `DEPENDENCIES.json` (`model`).
- `whisper_jni.cpp`: replace the hard-coded `p.language = "en"` with a `language` string passed from Java (`WhisperNative`/`WhisperRecognizer`). Default `"pt"`. Optionally offer `"auto"` for mixed PT/EN days. Rebuild the native library.
- Optional quality upgrade later: `ggml-medium-q5_0` (~540 MB). It is much more accurate in PT but roughly 3× slower on a phone CPU, and refinement already runs in the background.

**UI**
- Add `res/values-pt/strings.xml`. Many strings are currently inline in Java and need to move to resources first.

**Tests**
- Update the tests and verifiers that pin the English model names/hashes (`tests/hybrid`, `tests/whisper-*`, `HybridSpeechIntegrationTest`, `SpeechIntegrationTest`). Add a short public-domain PT WAV fixture with an expected-substring check.

## Phase 2: Task extraction (3–5 days for v1)

**Where it hooks in.** `RefinementJobService` calls `store.completeRefinement(id)` once Whisper finishes a session. At that point:
1. Run `TaskExtractor.extract(refinedText)` and get `List<TaskCandidate{content, dueString?, sourceSnippet}>`.
2. Store the candidates **encrypted** next to the session, the same way `Recordings` handles AES-GCM storage. Never in plaintext, logs or notification text.
3. Post a notification that only gives a count: "3 tarefas encontradas".
4. A new **Review screen** lists the candidates with checkboxes, lets you edit the text and due date, and has a **Send to Todoist** button. Nothing is sent without review, because ASR plus extraction will make mistakes.
5. Add a manual "Extrair tarefas" action on any saved session (works on old recordings too).

**Extractor: a pluggable interface, two implementations**

- **v1, rule-based, offline (recommended first).** Portuguese cue phrases: "preciso", "tenho que", "temos que", "não esquecer de", "lembrar de", "vou ligar/mandar/comprar…", "fica de", "ficou combinado", "até sexta", and imperatives addressed to yourself. Date phrases ("amanhã", "segunda", "semana que vem", "dia 15", "às 14h") are captured as a `dueString` to pass to Todoist as-is. The rules are cheap, predictable and unit-testable on the host. Expect decent recall and moderate precision; the review screen absorbs the rest.
- **v2, on-device LLM (optional).** The project already compiles ggml for whisper.cpp, so adding **llama.cpp** with a small multilingual instruct model (e.g. Qwen2.5-1.5B or Gemma-3-1B, Q4, ~1 GB) is the natural next step. Prompt it to return JSON tasks in Portuguese. This stays fully offline, but it makes the APK/assets much larger and takes tens of seconds per session on CPU.
- **Cloud LLM (e.g. the Claude API).** This gives the best extraction quality, but the transcript leaves the phone and it needs INTERNET plus an API key. Only consider it if you go with Option B below anyway.

## Phase 3: Sending to the Todoist Inbox (1–3 days)

**Option A: share to the Todoist app (keeps the app offline). Recommended start.**
- `Intent.ACTION_SEND`, `text/plain`, `setPackage("com.todoist")`, text = `"Ligar para o João amanhã"`.
- Todoist's share target opens Quick Add, which parses natural-language dates in your Todoist language and defaults to the Inbox.
- No token, no INTERNET permission, and the existing "no-INTERNET" tests stay green.
- Downside: one confirmation tap per task. To check during implementation: whether sharing multi-line text offers "add as separate tasks".

**Option B: direct Todoist API (one tap for all tasks, needs INTERNET).**
- Add `android.permission.INTERNET`. Update the three no-INTERNET assertions to allow it, and keep `usesCleartextTraffic=false`.
- Settings screen to paste a personal API token (Todoist → Settings → Integrations → Developer). Store the token encrypted with a Keystore key, never in logs.
- `POST https://api.todoist.com/api/v1/tasks` with `{"content": "...", "due_string": "amanhã", "due_lang": "pt"}`. Leaving out `project_id` puts the task in the **Inbox**. Send an `X-Request-Id` per candidate so retries don't create duplicates. Confirm the exact endpoint and fields against the current Todoist API v1 docs when implementing.
- Run a send queue in a `JobService` with a network-connected constraint and backoff, matching the existing `RefinementJobService` pattern. Mark candidates sent/failed.
- Keep all networking in **one class** (`TodoistClient`) that sends only task text. Never audio or full transcripts.

Recommendation: ship Option A first. Add Option B behind a setting once the extraction quality has earned it.

## Suggested milestones

1. **M0**: fork builds and runs unchanged on your phone with your Omi.
2. **M1**: Portuguese transcripts (Vosk PT + Whisper multilingual `pt`).
3. **M2**: rule-based PT extractor, review screen, share to Todoist (Option A).
4. **M3** (optional): Todoist API direct send (Option B).
5. **M4** (optional): on-device LLM extractor.

## Risks / open questions

- **Accuracy.** Whisper `small` in Portuguese with wearable audio is usable but imperfect. Names and jargon will be wrong at times. `medium` helps, at the cost of CPU time.
- **Mixed languages.** If you speak PT and EN in the same day, `"auto"` per 30 s window may help. Decide once you see real data.
- **Upstream sync.** The upstream repo publishes source snapshots, not granular history, so expect manual merges if you want future upstream fixes.
- **Not certified upstream.** Long sessions and screen-off endurance are explicitly marked "not certified" in `verification/PUBLIC-SOURCE-0.4.4.md`.
