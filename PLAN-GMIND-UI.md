# Plan: GMind UI update — less text, real navigation

The backlog item *UI update: less text, real navigation* in `PLAN-SENTIENT.md`, planned out. It builds on the audit in `docs/SENTIENT-DESIGN.md` and keeps its design system: the GVoice tokens, Ubuntu Mono, hairline rows and `Ui.Style` buttons.

## Status

| Step | State |
|---|---|
| 1. Navigation shell and Settings | Done: `SettingsActivity` (About for now), `Ui.listRow`, gear in the header, `NavigationTest`, only-the-launcher-is-exported check in `verify_apk.py` |
| 2. Source screen | Done: `SourceActivity`, sources listed in Settings and (as short rows) on home, `SourceStatus` for the wording, `LiveSources` for live loading; `NavigationTest` and `SourceStatusTest` |
| 3. Search home: recent items, attention line | Not started |
| 4. About and licenses screens | Not started |
| 5. Text pass | Not started |
| 6. Bottom bar (when Ask or Tasks lands) | Waiting on Phase 2 / task monitoring |

## Where it is now

Everything is in `SentientActivity` (420 lines). With an empty search field, the page shows:

1. The header with the `i` icon, the title *Your knowledge* and the search field.
2. On first launch, *Nothing synced yet* plus a two-line explanation.
3. *Sources*: one row per source with its name, count, last sync, a *Needs attention* chip, the coral reason, the limits paragraph ("Saves the messages you receive from now on…", plus a Signal sentence) and **Allow notification access**.
4. The sync line ("Last synced 2 hours ago. Syncs again once a day.") and **Sync now**.

Typing replaces 2–4 with results. About and licenses are `AlertDialog`s. `ItemActivity` is the only other screen.

Problems:
- **Three jobs on one page.** Finding things, setting up sources and checking sync all share the scroll. Setup only matters once, but it stays on the page you open every day.
- **Too much text.** A fresh install with WhatsApp not yet allowed shows about 90 words of explanation before any content. The limits paragraph, the access reason and the sync line all repeat what a chip or a single line could say.
- **The empty search field shows settings, not content.** Once data exists, you open the app to find something, and you're shown the sources.
- **Nowhere to grow.** Ask, Tasks, Chats to monitor, Your name, Connections and Updates (all in the backlog) have no place except further down the same page.
- **Dialogs hide About.** The version, the privacy notes and the licenses live in pop-ups with no room for the update check.

## Target structure

```
Search (home)                          Settings
├─ search field                        ├─ Sources ─► Source screen (one per source)
├─ attention line (only when needed)   │            status · setup steps · limits · Sync now
├─ no query: recent items              ├─ Chats to monitor        (task-monitoring item)
└─ query: results ─► Item              ├─ Your name               (onboarding item)
                                       ├─ Connections             (Composio, Todoist)
                                       ├─ Updates                 (update-checker item)
                                       └─ About ─► Licenses
```

Later, **Ask** (Phase 2) and **Tasks** (task monitoring) become tabs. Then the app gets a bottom bar: **Search · Ask · Tasks · Settings**.

**Decision: no bottom bar until there's a third tab.** Today there would be only Search and Settings, and a two-tab bar with greyed "coming soon" tabs adds chrome without helping. Until then, Settings opens from a gear icon in the header, where the `i` icon is now. The bar arrives with the first of Ask or Tasks, and the gear goes away then. Steps 1–5 are built so that step 6 only moves the entry point.

## Implementation

All views stay plain Android, built in code like now. No new dependencies: `DEPENDENCIES.json` pins the runtime set, and the app has no AndroidX UI. `FLAG_SECURE` goes on every new activity.

### 1. Navigation shell and Settings

- `SettingsActivity` (new): a `Ui.backBar` page with one hairline row per entry. Each row has a label, a one-line status on the right (*All good*, *1 needs attention*, *Off*, *0.6.0*) and a chevron. Entries that don't exist yet aren't shown: no placeholders.
- `Ui.listRow(context, label, status, alert, click)` (new): the row used by Settings and the source list. Add `ic_settings` and `ic_chevron` line icons alongside the existing ones.
- Header: the `i` icon becomes a gear (`Settings`).
- Back from Settings returns to Search with the query kept (normal activity back stack).

### 2. Source screen

- `SourceActivity` (new, `EXTRA_PLUGIN_ID`) takes over everything `sourceRow` did beyond the name and chip:
  - A status block: the chip (*Working* / *Needs attention* / *Not set up*), count and last item or last sync.
  - When something's wrong: the reason, in coral, and its one action (**Allow notification access**, **Open Signal settings**, **Sync now**).
  - **What it saves**: the limits text, which is shown here permanently instead of only during setup.
  - **Sync now** with the sync line ("Synced 2 hours ago · daily").
- Settings › Sources lists the sources with `Ui.listRow`: name, chip and a short status ("318 messages", "Needs access").
- The live state stays live. `SourceActivity` polls `SyncJobService.revision` and `ChatPlugin.accessGranted` on resume and every second while visible, as the home page does now.
- Moving it out removes `accessButtonShown` / `limitsExplained` from `SentientActivity`. Each source screen is self-contained, so the "explain once across rows" bookkeeping goes away. A chat source screen still says one grant covers WhatsApp and Signal.

### 3. Search home

- Empty query, data exists: **Recent**, the latest 20 items across sources, using the same row as search results. This needs `Items.recent(Db, int limit)` (new query, `ORDER BY ts DESC`), which also gets a host check in `tests/sentient/run_host_checks.py`.
- Empty query, nothing synced yet: the empty state becomes one line, "Nothing here yet.", and one button, **Set up sources**, which opens Settings › Sources. The explanation moves to the source screens.
- **Attention line**: when any source needs attention, one coral line sits under the search field, for example "WhatsApp needs attention ›". Tapping it opens that source's screen; with two or more sources, it opens Settings › Sources. It shows nothing when everything's fine.
- The sync status leaves the home page completely; a sync still runs once a day and from the source screens.
- `showSources` becomes `showHome(List<Sources.State>, List<Items.Item>)`, and `showResults` stays. The screenshot test calls both, as now.

### 4. About and licenses

- `AboutActivity`: version, the four privacy points (as now, one line each), a **Licenses** row and, once it exists, the **Updates** row.
- `LicensesActivity`: the licence files in a scroll view, replacing the dialog.

### 5. Text pass

The rule: one short line of explanation per screen, at most. Status goes in chips, icons and counts. Longer help sits one tap away on the screen it belongs to.

| Now | After |
|---|---|
| "Nothing synced yet." + "GMind copies your GVoice transcripts once a day and saves WhatsApp and Signal messages as they arrive. Start the first sync now." | "Nothing here yet." + **Set up sources** |
| Limits paragraph on the home page, during setup | **What it saves** on the source screen, always |
| "Last synced 2 hours ago. Syncs again once a day." on the home page | "Synced 2 hours ago · daily" on the source screen |
| "Turns on with the same notification access." | Chip *Needs access* in the list; on the source screen, "Uses the same access as WhatsApp." |
| *Your knowledge* title above the search field | Removed: the header already says GMind, and the field's hint says what it searches |
| "Can't open your knowledge base." + two sentences | Unchanged: an error that loses data needs the explanation |

### 6. Bottom bar (later)

When Ask or Tasks lands:
- `Ui.bottomBar(activity, selectedTab)`: a hairline-topped row of icon-plus-label tabs, with mint on the selected one. Every top-level activity (`SentientActivity`, `AskActivity`, `TasksActivity`, `SettingsActivity`) adds it at the bottom of its page.
- Switching tabs uses `FLAG_ACTIVITY_REORDER_TO_FRONT` with no animation, so each tab keeps its state (the search query, a half-typed question). Back from a non-Search tab goes to Search; back from Search leaves the app.
- The header gear goes away, and `SettingsActivity` loses its back bar.

## Tests and verification

- **Screenshots** (`SentientScreenshotTest`): home (recent), home with the attention line, home empty, search, search with no matches, Settings, Settings › Sources, a source screen in each state (working, needs access, Signal hidden, unavailable), About, Licenses. Render before/after into `docs/ui/` and add a *Navigation* section to `docs/SENTIENT-DESIGN.md`.
- **Robolectric behaviour tests**: the attention line opens the right screen (one source → its screen, two → the list), and the access button on the source screen fires the notification-settings intent.
- **Host checks**: `Items.recent` ordering and its limit.
- **`tests/sentient/verify_apk.py`**: add the new activities to the expected class list and check that none is exported except the launcher.
- **Gradle**: `./gradlew :sentient:assembleDebug :sentient:assembleRelease :sentient:lintDebug :sentient:testDebugUnitTest`.
- **On device**: a fresh install (empty state → Set up sources → allow access → back home), a day of use (recent items, search, open a message), revoking notification access (the attention line appears), and the back stack from every screen.

## Out of scope

The Ask, Tasks, Chats to monitor, Your name, Connections and Updates screens themselves (each is its own backlog item; this plan only gives them a place), GVoice's UI, and a new visual style.

## Estimate

Steps 1–5: about 2–3 days, including screenshots. Step 6: about half a day, done with whichever of Ask or Tasks lands first.
