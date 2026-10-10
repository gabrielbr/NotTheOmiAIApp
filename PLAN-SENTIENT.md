# Plan: "Sentient" — a mobile personal-knowledge companion for Omi Tarefas (Android)

> **The app is named GMind.** The code, module and package keep the name *sentient* (`:sentient`, `br.gabriel.sentient`).

## Context

Omi Tarefas (`br.gabriel.omitarefas`) already records and transcribes all day, fully offline. We want a phone-based version of Sentient OS (the Mac app that turns your messages, notes and files into a queryable knowledge base), built for Android instead of the Mac:

- **Collect content daily** from WhatsApp and other sources through **plugins**, so adding a new source later is easy.
- **Use Composio as a data source**, so many services (Gmail, Slack, Calendar, and Matrix if its toolkit covers it) can be connected without writing a client for each one.
- **Store everything in a database with relationships and mappings** (people ↔ identities ↔ conversations ↔ entities), so an AI can answer questions over it.
- **No computer control.** This is read-only. We never perform actions on other services.

Decisions already made:
1. **Separate companion app** with INTERNET permission. Omi Tarefas stays offline, and its 3 "no INTERNET" checks stay in place.
2. **WhatsApp** is captured with a **NotificationListenerService** (incoming messages only, no root).
3. **AI backend is pluggable**: Claude API and an on-device LLM; the user picks one.

## Status

| Item | State |
|---|---|
| `:plugin-api` (plain Java): `SourcePlugin`, `RawItem`, `PullResult`, `PluginContext`, `SourceUnavailableException` | Done |
| `TranscriptProvider` in Omi Tarefas, signature permission `READ_TRANSCRIPTS`; still no INTERNET (`tests/hybrid/verify_apk.py` now also checks the provider guard) | Done; builds and lints |
| `:sentient` app: SQLCipher store (Keystore-wrapped raw key), schema v1 with FTS5, `Ingest`, `Sources`, `SyncRunner`, `Search`, Omi transcripts plugin, daily `SyncJobService` + "Sync now", home screen with sources and search, licenses | Done; `assembleDebug/Release`, `lintDebug` pass |
| Host tests `tests/sentient/run_host_checks.py`: real SQL through SQLite/JDBC, 46 checks (migrations, idempotent ingest, FTS accents, identities, page-by-page cursor commits, failure isolation, foreign-item rejection, Omi cursor rewind) | Pass |
| Device test `SqlCipherStoreTest` (real SQLCipher FTS5, no plaintext on disk, wrong key refused) | Compiles; **not run yet: no emulator here** |
| Signing both apps (`scripts/sign_release.py --modules`), `tests/sentient/verify_apk.py` (permission allow-list, no computer-control surface, pinned native libs, same signer), release workflow publishes both APKs | Done; verified locally with a throwaway key |
| UX/UI audit and the Omi Tarefas design system: search first, highlighted matches, item screen, sync states, About (`docs/SENTIENT-DESIGN.md`) | Done; screenshots rendered with Robolectric |
| **Phase 1, WhatsApp:** `WhatsAppListenerService` (notification listener, WhatsApp and WhatsApp Business only), `WhatsAppMessages` parser (DM/group, own replies from the notification, title suffixes, WhatsApp notices skipped, content-hash dedupe of re-posted history), live ingest, notification-access flow on the WhatsApp row, author in results, message shown in its conversation (`Items.around`) | Done; 20 new host checks + 4 Robolectric tests on real `MessagingStyle` notifications. **WhatsApp's real notification format still needs checking on your phone** |
| **Signal:** the capture is generalized to chat apps (`ChatMessages.App`: WhatsApp, WhatsApp Business, Signal `org.thoughtcrime.securesms`); Signal is its own source. One notification-access grant covers both. When Signal hides content ("New message"), the Signal row shows which Signal setting to change, as a standing notice the daily sync doesn't overwrite | Done; 10 new host checks, 2 more Robolectric tests |
| On-device: install both, record, "Sync now", search; allow notification access, receive and reply in WhatsApp and Signal, search | **Pending; needs your phone** |

Phase 1 decisions:
- Messages are written straight into the store when their notification arrives, so they're searchable at once. The Phase 0 `inbox_buffer` table stays unused; dedupe comes from content-hash ids instead.
- A person is keyed by WhatsApp display name (`name:<sender>`), because notifications don't carry phone numbers. The same name in a DM and in a group is the same person. Merging people across sources is Phase 4's job.
- A message with no sender is yours (a reply sent from the notification).

Phase 0 decisions that differ from the sketch below:
- The sync job uses `NETWORK_TYPE_NONE` for now, because the only source is local. Network-bound plugins will add their own constraint.
- Plugin config is a `source_config(plugin_id, key, value)` table instead of a JSON column. This keeps the store code plain Java (no `org.json`), so host tests run it.
- Both apps declare the same signature permission, so the grant works whichever app is installed first. Installing two builds with *different* signers then fails with a duplicate-permission error. That's intended: the provider couldn't be read in that case anyway.
- SQLCipher is pinned at 4.12.0, the newest release whose `androidx.sqlite` builds with compileSdk 34. This also sets `android.useAndroidX=true`; `:app` has no AndroidX dependencies.
- The provider uses `Recordings.list("")`, which decrypts every transcript's text (not audio) on each sync. That's fine once a day; a metadata-only `since` filter in `Recordings` can come later if libraries get large.

## Architecture

```
┌──────────── Omi Tarefas (:app, offline, unchanged promise) ────────────┐
│ Recordings (encrypted SQLite) ──► TranscriptProvider (read-only,        │
│                                    signature permission, completed only)│
└─────────────────────────────────────┬──────────────────────────────────┘
                                      │ ContentResolver
┌──────────── Sentient (:sentient, br.gabriel.sentient, INTERNET) ───────┐
│ Plugins (:plugin-api) ─► Ingest ─► KnowledgeStore (SQLCipher + FTS5)    │
│   OmiTranscripts | WhatsAppNotifications | Composio | Matrix           │
│ SyncJobService (daily JobScheduler + "Sync now")                       │
│ Enrichment (identity resolution, entity/relationship extraction)       │
│ Ask screen ─► LlmBackend { ClaudeBackend | LocalBackend } + read tools │
└────────────────────────────────────────────────────────────────────────┘
```

Both APKs are signed with the same key, so the `signature` permission on the provider works and nothing else can read the transcripts.

## Gradle modules

| Module | Type | Purpose |
|---|---|---|
| `:app` | existing | Gets just one new piece: `TranscriptProvider` |
| `:plugin-api` | plain Java library | Plugin contracts and `RawItem`. Host-testable with no Android dependency, like the other `tests/*/run_host_checks.py` suites |
| `:sentient` | Android app | Plugins, store, sync, enrichment, AI, UI |

`settings.gradle` adds `include ':sentient', ':plugin-api'`. Java 17, minSdk 26, targetSdk 34, the same conventions as `app/build.gradle` (no androidx; plain Activities like `TasksActivity`).

## 1. Omi Tarefas side (small, keeps the offline promise)

- New `app/src/main/java/app/nottheomi/ai/TranscriptProvider.java`. It's an exported `ContentProvider` guarded by a new `<permission android:protectionLevel="signature" android:name="br.gabriel.omitarefas.permission.READ_TRANSCRIPTS">`.
  - Query only. It returns `id, title, createdAt, durationMs, status, text` for `COMPLETE` sessions, with an `?since=<createdAt>` filter.
  - Implemented on top of the existing `Recordings.get(ctx).list("")` / `find(id)` (`Recordings.java`). No new storage code and no plaintext cache: it decrypts on demand, the same way export does.
- The manifest stays without INTERNET. `tests/hybrid/verify_apk.py` still passes, and we add an assertion that the provider requires the signature permission.

## 2. Plugin API (`:plugin-api`)

```java
public interface SourcePlugin {
    String id();                       // "whatsapp.notifications", "composio.gmail", "matrix"
    String displayName();
    Set<Mode> modes();                 // PULL (scheduled), PUSH (live buffer, e.g. notifications)
    ConfigSchema configSchema();       // fields rendered generically in Settings (token, server URL…)
    PullResult pull(PluginContext ctx, String cursor) throws Exception; // returns items + next cursor
}
public final class RawItem {           // normalized unit of ingest
    String source, externalId;         // unique together → idempotent re-sync
    String conversationExternalId, conversationTitle, conversationKind; // dm | group | meeting | thread | mailbox
    String authorHandle, authorDisplayName; boolean fromMe;
    long timestamp; String kind;       // message | email | transcript | event | doc
    String text; String rawJson;       // raw kept for re-processing later
}
```
- `PluginContext` gives a plugin its config, secrets (decrypted from the Keystore), an HTTP client (HttpURLConnection only, with no new dependencies), a logger that redacts message text, and a cancellation flag.
- `PluginRegistry` is a static list in `:sentient`. Adding a source means adding one class plus one line in the registry.
- Read-only by design: the plugin contract has no "send" or "act" methods.

## 3. Plugins for v1

| Plugin | How it works |
|---|---|
| **OmiTranscripts** | PULL through `ContentResolver` from `TranscriptProvider`, with the cursor set to the last `createdAt`. Kind is `transcript`, and the conversation is a "meeting" for each recording. |
| **WhatsAppNotifications** | `WhatsAppListenerService extends NotificationListenerService` filters `com.whatsapp` and `com.whatsapp.w4b`. It reads `Notification.MessagingStyle` messages (sender, text, timestamp) and the conversation title / `EXTRA_IS_GROUP_CONVERSATION`. It skips summary notifications and "N new messages" notifications. It writes straight into the store's encrypted `inbox_buffer`, deduplicated by `hash(chat, sender, ts, text)`, because WhatsApp re-posts its history in every notification. The daily PULL drains the buffer. Limits we'll document: own replies are not captured, muted chats are missed, and media shows up only as a placeholder. |
| **Composio** | One generic plugin, instantiated once per configured toolkit (`composio.<toolkit>`). It uses the Composio v3 REST API with the user's API key: it lists connected accounts, then executes **only allow-listed read tools** (for example `GMAIL_FETCH_EMAILS`, `SLACK_FETCH_CONVERSATION_HISTORY`, `GOOGLECALENDAR_EVENTS_LIST`). A per-toolkit `Mapper` turns the tool output into `RawItem`s. Write tools are rejected at the plugin layer. Account connection (OAuth) happens on Composio's hosted connect link, opened in the browser. |
| **Matrix** | A native client-server API plugin: `/login` (or a pasted access token), then `/sync?since=<next_batch>` with a filter for `m.room.message`. This is reliable and needs no third party. If Composio's Matrix toolkit turns out to fit well, it can be offered as a Composio mapper too (to be checked in Phase 3). A Matrix bridge (mautrix-whatsapp) can later bring in full WhatsApp history through this same plugin, with no new code. |

## 4. Knowledge store (`:sentient`, `KnowledgeStore.java`)

**Encryption:** SQLCipher for Android (`net.zetetic:sqlcipher-android`), with a random DB key wrapped by an Android Keystore AES-GCM key (the same Keystore pattern as `Recordings.java`). We use whole-DB encryption instead of the per-field AES-GCM that `Recordings` uses, because FTS and relational queries need searchable plaintext inside the encrypted file. Backups stay disabled and screen captures stay protected, as in `:app`.

**Schema (v1):**
```
sources(id, plugin_id, enabled, config_json, cursor, last_sync_at, last_status)
inbox_buffer(id, plugin_id, dedupe_hash UNIQUE, payload_json, received_at)

people(id, display_name, is_me, notes, created_at)
identities(id, person_id→people, source, handle, display_name, UNIQUE(source, handle))
   -- WhatsApp contact name, Matrix @user:server, email address, Slack user, Omi speaker label
conversations(id, source, external_id, title, kind, UNIQUE(source, external_id))
conversation_members(conversation_id, identity_id)

items(id, source, external_id, conversation_id, author_identity_id, ts, kind,
      text, raw_json, ingested_at, UNIQUE(source, external_id))
items_fts  -- FTS5 external-content on items.text (unicode61 remove_diacritics for PT)

entities(id, type, name, canonical_key)   -- person|org|project|place|topic|task|event
entity_aliases(entity_id, alias)
mentions(item_id, entity_id, confidence)
relations(id, src_entity, dst_entity, type, evidence_item_id, confidence, first_seen, last_seen)
   -- e.g. works_at, family_of, part_of_project, met_at, owes_task
facts(id, entity_id, key, value, evidence_item_id, confidence, updated_at)
daily_digests(date PRIMARY KEY, markdown, generated_at)
```
- Person entities link to `people` through `entities.canonical_key = 'person:<id>'`, so "people" (who I talk to) and "entities" (what gets talked about) are one graph.
- Everything derived (mentions, relations, facts) has an `evidence_item_id`, so the AI can always cite its source message.
- Migrations use a `PRAGMA user_version` ladder, with host tests for every step.

## 5. Daily sync (`SyncJobService.java`)

- Follows `RefinementJobService.java`: a single owner, cooperative cancel, and a user-readable `state`. It's a periodic `JobScheduler` job (24 h, `NETWORK_TYPE_ANY`, `requiresStorageNotLow`), plus a "Sync now" button.
- For each enabled source, it calls `pull(cursor)`, then `Ingest.upsert(items)` in a single transaction, then advances the cursor only after the commit, so re-running a sync is idempotent.
- After ingest it runs `Enrichment` on the new items, then writes `daily_digests` for yesterday.
- Failures are isolated per plugin and shown in a Sources screen (last sync time, item count, error).

## 6. Enrichment (relationships and mapping)

1. **Identity resolution** (deterministic, offline): normalize phone numbers, emails and Matrix IDs, and match exact display names across sources. Fuzzy matches become *suggestions* in a "Merge people?" screen. They're never merged automatically.
2. **Entity and relation extraction** (LLM, batched per conversation per day): it sends new items with a strict JSON schema prompt that returns `entities[] / relations[] / facts[] / tasks[]` with item ids as evidence. Results are upserted by `canonical_key`. It uses the selected `LlmBackend`. With the local backend it runs while the phone is charging.
3. **Tasks:** Todoist lives in Sentient only; Omi Tarefas no longer has it. `TaskExtractor` (pure Java, PT/EN cue phrases), its host test and the share-to-Todoist screen `TasksActivity` were removed from `:app`. Restore them from commit `d6165d4` (`git show d6165d4:app/src/main/java/app/nottheomi/ai/TaskExtractor.java`, `.../TasksActivity.java`, `tests/tasks/`), move the extractor to `:plugin-api` and run it on transcripts and WhatsApp/Matrix messages. The extracted tasks become `task` entities and can be shared to Todoist the same way.

4. **Portrait of you** (Sentient OS's README): after enrichment, regenerate an "About me" page from the graph: who you are, work, the people you talk to most, active projects, places and recent themes, each line linked to its evidence. It's shown in GMind, and the AI reads it before every answer.
5. **Markdown vault export** (Sentient OS's Obsidian vault): one note per person, project and place, with links between them and citations to messages, plus the portrait as `README.md`. You pick the folder with Android's file picker. The export is plaintext by your choice, and only refreshed when you ask or on a schedule you turn on.

## 7. AI query (`Ask` screen)

```java
interface LlmBackend { String id(); Reply chat(List<Msg> history, List<Tool> tools) throws Exception; }
```
- **Read-only tools** that the model can call, implemented in `KnowledgeTools.java`:
  `search(query, source?, person?, from?, to?)` (FTS5 + bm25), `get_person(name)` (identities, recent conversations, relations, facts), `get_conversation(id, around_ts)`, `timeline(from, to, person?)`, `related(entity, depth≤2)`, and `sql(select)` over curated read-only views, with a statement whitelist that allows only `SELECT` and a row cap.
- **ClaudeBackend:** Messages API with tool use over HttpURLConnection. Default model `claude-opus-5-5` for questions and `claude-sonnet-5-5` for nightly enrichment, both configurable. Only the tool results the model asks for leave the phone, never the whole DB. The API key is stored wrapped by the Keystore.
- **LocalBackend:** llama.cpp through JNI, reusing the existing NDK toolchain and `scripts/build_whisper.py` pattern (it's also ggml-based) in a new `scripts/build_llama.py`. The user picks a GGUF model in the app (Qwen/Gemma class, 1–4 B, Q4). The weights are downloaded by the companion app, not bundled. A small JSON tool-call grammar is used, since local models handle tool use poorly without one.
- Answers show citations (links to source items), and you can tap one to open the original message or transcript.

## 8. UI (`:sentient`)

`SentientActivity` (home: Ask box, last sync, today's digest), `SourcesActivity` (enable/configure plugins, notification-access prompt, Composio connect links), `PeopleActivity` (people, identities, merge suggestions), `SettingsActivity` (AI backend, models, keys, wipe all data). Plain Views in the same style as `TasksActivity`.

## 9. Build / release

- `.github/workflows/release.yml` builds and signs both APKs and attaches both to the release.
- `tests/hybrid/verify_apk.py` keeps asserting that `:app` has no INTERNET. A new `tests/sentient/verify_apk.py` asserts that `:sentient` has exactly this permission allow-list: `INTERNET`, `POST_NOTIFICATIONS`, the NotificationListener service binding, and the transcript read permission. It also asserts there's no Accessibility service, so no computer control.
- Docs: `PLAN-SENTIENT.md` (status table like `PLAN-PT-TODOIST.md`), a README section, and `THIRD_PARTY_NOTICES.md` entries for SQLCipher and llama.cpp.

## Phases

| Phase | Scope | Est. |
|---|---|---|
| 0 | Modules, `TranscriptProvider`, `:sentient` skeleton, KnowledgeStore + schema + FTS, OmiTranscripts plugin, SyncJobService, release workflow for two APKs | 3–4 d |
| 1 | WhatsApp notification plugin (live capture, dedupe), notification-access flow, message in context | 2–3 d |
| 2 | ClaudeBackend + KnowledgeTools + Ask screen with citations | 3 d |
| 3 | Composio plugin (generic + Gmail/Slack/Calendar mappers; check the Matrix toolkit) and native Matrix plugin | 3–4 d |
| 4 | Enrichment: identity resolution + merge UI, LLM entity/relation extraction, daily digest, **portrait of you** and **Markdown vault export** | 4–5 d |
| 5 | LocalBackend (llama.cpp JNI, model picker, grammar-constrained tools) | 4–5 d |
| 6 | Access from Claude anywhere: the vault reachable outside the phone (synced export folder or a small MCP server with `get_structure`/`get_files`, as in Sentient OS). First time data leaves the phone, so the design is decided then. | 3–5 d |

## Sentient OS parity

GMind is the Android take on Sentient OS: collect what's new in your life every day, privately on the device, and distill it into a knowledge base you can ask about.

| Sentient OS | GMind | Phase |
|---|---|---|
| Reads new messages, email, files and transcripts | Plugins: GVoice transcripts, WhatsApp, Signal, Composio (Gmail…), Matrix | 0, 1 (done), 3 |
| Everything stays on the device | Encrypted SQLCipher store, Keystore-wrapped key | 0 (done) |
| Distills into a knowledge base | People, identities, conversations, entities, relations, facts, all with evidence | 0 (schema), 4 |
| Notes per person, project, place | People screen and merge suggestions; entity pages | 4 |
| **README portrait of you** | "About me" page regenerated from the graph; the first thing the AI reads | 4 |
| **Obsidian-style vault** | Markdown export: one note per person, project and place, plus `README.md`, to a folder you pick | 4 |
| Ask an AI that knows you | Ask screen with read-only tools and citations (Claude or on-device) | 2, 5 |
| **Readable by Claude in any conversation** | Vault reachable outside the phone | 6 |
| Computer control (Mac) | **Excluded on purpose.** GMind is read-only and has no accessibility service; `verify_apk.py` enforces this | — |

## Backlog

Not scheduled into a phase yet.

### Task monitoring of chosen chats

The user picks, in GMind's UI, which groups or people in which apps (WhatsApp, Signal, later others) GMind keeps watching for tasks. Messages from those chats are checked as they arrive, and a task is added when a message asks the user to do something.

- Example: the company's shared WhatsApp group says Gabriel needs to fill in his hours sheet. GMind adds the task "Gabriel: fill in hours sheet".
- The text is interpreted for timelines and deadlines ("by Friday", "end of month", "before the 15th"), which become the task's due date. Relative dates resolve against the message's timestamp.
- The selection UI lists the conversations and people GMind has already seen, per app, each with an on/off toggle. Nothing is monitored until the user turns it on.
- Each task links back to the message it came from, shown in its conversation (`Items.around`).
- Tasks live in GMind: a task list stored in the encrypted store, which is the source of truth and works with no connection.
- Todoist is one of the plugin connections, and like the other plugins it goes through Composio (`composio.todoist`, account connected on Composio's hosted link). When it's enabled, GMind syncs its tasks with Todoist (as GVoice's task extraction does, `PLAN-PT-TODOIST.md`). When it's off, tasks stay only in GMind. This is the one write action GMind makes, it happens only after the user turns it on, and it touches only tasks GMind created. The Composio write-tool block stays in place everywhere else: only the Todoist task tools the sync needs (create, update, complete) are allow-listed, and only for `composio.todoist`. See *Out of scope*.

### Ask for the user's name during onboarding

Onboarding asks the user's name (and nicknames or other spellings, like "Gabriel" / "Gabi"). Task detection uses it to keep only tasks meant for the user. A request addressed to someone else in a group ("Ana, send the report") isn't added. A message with no name, sent in a direct chat, counts as addressed to the user.

### Update checker and in-app update for both APKs

Both apps (GVoice and GMind) check for a newer release and update themselves from inside the app, instead of the user installing the APK by hand or through Obtainium (`RELEASING.md`).

- Source: the repository's GitHub releases. A release is newer when its versionCode is higher than the installed app's.
- GMind does the network part for both apps, because GVoice has no INTERNET permission and `tests/hybrid/verify_apk.py` keeps it that way. GMind checks once a day (in the daily sync) and on "Check for updates". It downloads the APK, checks it against the release's `SHA256SUMS.txt` and checks that its signer matches the installed app, then installs it with `PackageInstaller`. Android still asks the user to confirm each install.
- GVoice gets an "Update" entry that opens GMind's update screen when GMind is installed. Without GMind, it links to the releases page in the browser. Either way GVoice itself stays offline.
- The update screen shows each app's installed and latest version, the release notes, and an **Update** button per app.
- `:sentient` gains `REQUEST_INSTALL_PACKAGES`, so the `tests/sentient/verify_apk.py` allow-list changes with it.
- An update never uninstalls or changes the signing key, so recordings and the GMind store are kept (same rule as in `RELEASING.md`).

### UI update: less text, real navigation

Planned in detail in [`PLAN-GMIND-UI.md`](PLAN-GMIND-UI.md).

GMind's screens (`SentientActivity`) put everything on one page: the title, search, every source row with its status and setup text (notification-access reasons, what WhatsApp capture can't see, Signal's hidden-content notice, the access button), the last-sync line and **Sync now**. About and licenses are dialogs. The backlog items above (Ask, tasks, chats to monitor, your name, Todoist through Composio, updates) would all end up on that same page. The work: audit the UI again (as in `docs/SENTIENT-DESIGN.md`), cut the text, and split the app into screens.

Audit findings so far:
- The home page mixes three jobs: finding things (search), setting things up (sources, access) and checking status (sync).
- Setup text stays on the home page once it's done its job. Source rows carry paragraphs that belong on a setup or detail screen.
- There's nowhere to put new features except further down the same scroll.
- About and licenses in dialogs hide the version and privacy notes.

Proposed navigation (bottom bar, 4 tabs):
- **Search** (home): the search field and results. With no query, recent items. A short line plus one button appear only when a source needs attention.
- **Ask**: the AI question screen (Phase 2).
- **Tasks**: tasks found in the monitored chats, with due dates and a link to the source message.
- **Settings**: *Sources* (one row each with a status chip; a tap opens the source's own screen with its setup steps, limits and **Sync now**), *Chats to monitor*, *Your name*, *Connections* (Composio, Todoist), *Updates*, *About and licenses* (a full screen, not a dialog).

Rules: one short line of explanation per screen at most, with longer help one tap away. Status is shown as chips and icons, not sentences. Keep the GVoice tokens, Ubuntu Mono and hairline rows from `docs/SENTIENT-DESIGN.md`. Update the Robolectric screenshots for every new screen.

## Verification

- **Host tests (no phone)**, in the same style as `tests/sentient/run_host_checks.py`: plugin contract and `RawItem` mapping per plugin using recorded JSON fixtures (Composio responses, a Matrix `/sync` page, MessagingStyle bundles), dedupe of re-posted WhatsApp history, cursor-advances-only-after-commit, the schema migration ladder, the SQL whitelist rejecting non-SELECT queries, and that Composio write tools are refused (except the allow-listed Todoist task tools, once the Todoist sync exists).
- **Instrumentation** (`:sentient` androidTest): KnowledgeStore with real SQLCipher + FTS5 (PT diacritics search), TranscriptProvider refusing a caller without the signature permission, and SyncJobService idempotency.
- **Gradle:** `./gradlew assembleDebug assembleRelease lintDebug` for both apps; `verify_apk.py` for both (`:app` has no INTERNET; `:sentient` matches the allow-list exactly).
- **On device:** install both APKs, grant notification access, receive WhatsApp messages, then "Sync now". Check that they appear under the right person and conversation. Record with Omi and confirm the transcript is ingested. Connect Gmail through Composio. Ask "what did Ana and I talk about this week?" and confirm the answer cites the right items. Switch to the local backend and repeat.

## Out of scope

Computer control or UI automation, Accessibility scraping, sending messages or any write action on Composio/Matrix, reading WhatsApp's encrypted backups, and cloud sync of the knowledge DB. The one planned exception is the opt-in Todoist task sync through Composio (see *Backlog*).
