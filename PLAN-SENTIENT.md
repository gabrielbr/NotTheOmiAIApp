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
| On-device: install both, record, "Sync now", search | **Pending; needs your phone** |

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
| `:plugin-api` | plain Java library | Plugin contracts and `RawItem`. Host-testable with no Android dependency, like `tests/tasks` |
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
3. **Tasks:** the existing `TaskExtractor.extract()` (`app/.../TaskExtractor.java`) is already pure Java, so we move it to `:plugin-api` (or copy it) and run it on WhatsApp/Matrix messages too. The extracted tasks become `task` entities and can be shared to Todoist the same way `TasksActivity` does.

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
| 1 | WhatsApp notification plugin + inbox buffer/dedupe + Sources screen | 2–3 d |
| 2 | ClaudeBackend + KnowledgeTools + Ask screen with citations | 3 d |
| 3 | Composio plugin (generic + Gmail/Slack/Calendar mappers; check the Matrix toolkit) and native Matrix plugin | 3–4 d |
| 4 | Enrichment: identity resolution + merge UI, LLM entity/relation extraction, daily digest | 3–4 d |
| 5 | LocalBackend (llama.cpp JNI, model picker, grammar-constrained tools) | 4–5 d |

## Verification

- **Host tests (no phone)**, in the same style as `tests/tasks/run_host_checks.py`: plugin contract and `RawItem` mapping per plugin using recorded JSON fixtures (Composio responses, a Matrix `/sync` page, MessagingStyle bundles), dedupe of re-posted WhatsApp history, cursor-advances-only-after-commit, the schema migration ladder, the SQL whitelist rejecting non-SELECT queries, and that Composio write tools are refused.
- **Instrumentation** (`:sentient` androidTest): KnowledgeStore with real SQLCipher + FTS5 (PT diacritics search), TranscriptProvider refusing a caller without the signature permission, and SyncJobService idempotency.
- **Gradle:** `./gradlew assembleDebug assembleRelease lintDebug` for both apps; `verify_apk.py` for both (`:app` has no INTERNET; `:sentient` matches the allow-list exactly).
- **On device:** install both APKs, grant notification access, receive WhatsApp messages, then "Sync now". Check that they appear under the right person and conversation. Record with Omi and confirm the transcript is ingested. Connect Gmail through Composio. Ask "what did Ana and I talk about this week?" and confirm the answer cites the right items. Switch to the local backend and repeat.

## Out of scope

Computer control or UI automation, Accessibility scraping, sending messages or any write action on Composio/Matrix, reading WhatsApp's encrypted backups, and cloud sync of the knowledge DB.
