package br.gabriel.sentient;

import java.util.List;

/**
 * Knowledge store schema, as an append-only migration ladder keyed by PRAGMA user_version.
 * Never edit a released step; add a new one. Plain Java so host tests run the same SQL.
 */
public final class Schema {
    private Schema() {}

    static final String[][] MIGRATIONS = {
        { // 1: sources, people/identities, conversations, items + full-text search, graph
            "CREATE TABLE sources(plugin_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1,"
                + " cursor TEXT, last_sync_at INTEGER, last_status TEXT, item_count INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE source_config(plugin_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT,"
                + " PRIMARY KEY(plugin_id, key))",
            "CREATE TABLE inbox_buffer(id INTEGER PRIMARY KEY, plugin_id TEXT NOT NULL,"
                + " dedupe_hash TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, received_at INTEGER NOT NULL)",

            "CREATE TABLE people(id INTEGER PRIMARY KEY, display_name TEXT NOT NULL,"
                + " is_me INTEGER NOT NULL DEFAULT 0, notes TEXT, created_at INTEGER NOT NULL)",
            "INSERT INTO people(display_name, is_me, created_at) VALUES('Me', 1, 0)",
            "CREATE TABLE identities(id INTEGER PRIMARY KEY, person_id INTEGER NOT NULL REFERENCES people(id),"
                + " source TEXT NOT NULL, handle TEXT NOT NULL, display_name TEXT, UNIQUE(source, handle))",
            "CREATE INDEX identities_person ON identities(person_id)",

            "CREATE TABLE conversations(id INTEGER PRIMARY KEY, source TEXT NOT NULL, external_id TEXT NOT NULL,"
                + " title TEXT, kind TEXT, UNIQUE(source, external_id))",
            "CREATE TABLE conversation_members(conversation_id INTEGER NOT NULL REFERENCES conversations(id),"
                + " identity_id INTEGER NOT NULL REFERENCES identities(id), PRIMARY KEY(conversation_id, identity_id))",

            "CREATE TABLE items(id INTEGER PRIMARY KEY, source TEXT NOT NULL, external_id TEXT NOT NULL,"
                + " conversation_id INTEGER REFERENCES conversations(id), author_identity_id INTEGER REFERENCES identities(id),"
                + " from_me INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL,"
                + " raw_json TEXT, ingested_at INTEGER NOT NULL, UNIQUE(source, external_id))",
            "CREATE INDEX items_ts ON items(ts)",
            "CREATE INDEX items_conversation ON items(conversation_id, ts)",
            // External-content FTS kept in sync by triggers; accents folded for Portuguese.
            "CREATE VIRTUAL TABLE items_fts USING fts5(text, content='items', content_rowid='id',"
                + " tokenize='unicode61 remove_diacritics 2')",
            "CREATE TRIGGER items_ai AFTER INSERT ON items BEGIN"
                + " INSERT INTO items_fts(rowid, text) VALUES (new.id, new.text); END",
            "CREATE TRIGGER items_ad AFTER DELETE ON items BEGIN"
                + " INSERT INTO items_fts(items_fts, rowid, text) VALUES('delete', old.id, old.text); END",
            "CREATE TRIGGER items_au AFTER UPDATE OF text ON items BEGIN"
                + " INSERT INTO items_fts(items_fts, rowid, text) VALUES('delete', old.id, old.text);"
                + " INSERT INTO items_fts(rowid, text) VALUES (new.id, new.text); END",

            // Derived graph (filled by enrichment). Every row points at its evidence item.
            "CREATE TABLE entities(id INTEGER PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL,"
                + " canonical_key TEXT NOT NULL UNIQUE)",
            "CREATE TABLE entity_aliases(entity_id INTEGER NOT NULL REFERENCES entities(id), alias TEXT NOT NULL,"
                + " PRIMARY KEY(entity_id, alias))",
            "CREATE TABLE mentions(item_id INTEGER NOT NULL REFERENCES items(id),"
                + " entity_id INTEGER NOT NULL REFERENCES entities(id), confidence REAL, PRIMARY KEY(item_id, entity_id))",
            "CREATE TABLE relations(id INTEGER PRIMARY KEY, src_entity INTEGER NOT NULL REFERENCES entities(id),"
                + " dst_entity INTEGER NOT NULL REFERENCES entities(id), type TEXT NOT NULL,"
                + " evidence_item_id INTEGER REFERENCES items(id), confidence REAL, first_seen INTEGER, last_seen INTEGER)",
            "CREATE TABLE facts(id INTEGER PRIMARY KEY, entity_id INTEGER NOT NULL REFERENCES entities(id),"
                + " key TEXT NOT NULL, value TEXT NOT NULL, evidence_item_id INTEGER REFERENCES items(id),"
                + " confidence REAL, updated_at INTEGER)",
            "CREATE TABLE daily_digests(date TEXT PRIMARY KEY, markdown TEXT NOT NULL, generated_at INTEGER NOT NULL)",
        },
        { // 2: enrichment: to-dos found in what was said, people kept apart, small key/value state
            "CREATE TABLE found_tasks(id INTEGER PRIMARY KEY, item_id INTEGER NOT NULL REFERENCES items(id),"
                + " text TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'open', found_at INTEGER NOT NULL,"
                + " UNIQUE(item_id, text))",
            "CREATE INDEX found_tasks_status ON found_tasks(status, found_at)",
            "CREATE TABLE not_same_person(a INTEGER NOT NULL, b INTEGER NOT NULL, PRIMARY KEY(a, b))",
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
        },
        { // 3: chats watched for to-dos (overrides: direct chats on, groups off by default), due dates, Todoist ids
            "CREATE TABLE watched_chats(conversation_id INTEGER PRIMARY KEY REFERENCES conversations(id), watched INTEGER NOT NULL)",
            "ALTER TABLE found_tasks ADD COLUMN due TEXT",
            "ALTER TABLE found_tasks ADD COLUMN todoist_id TEXT",
        },
        { // 4: what's worth remembering. noise: 0 keep, 1 rule, 2 an AI (Claude or Qwen), 3 hidden by you, -1 kept by you
            "ALTER TABLE items ADD COLUMN noise INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE items ADD COLUMN noise_reason TEXT",
            "CREATE INDEX items_noise ON items(noise, ts)",
        },
    };

    public static int latest() { return MIGRATIONS.length; }

    /** Applies every pending step, each in its own transaction with its version bump. */
    public static void migrate(Db db) throws Exception {
        int version = version(db);
        if (version > latest()) throw new IllegalStateException("Knowledge store is newer than this app");
        for (int step = version; step < latest(); step++) {
            final String[] statements = MIGRATIONS[step];
            final int next = step + 1;
            db.transaction(() -> {
                for (String sql : statements) db.exec(sql);
                db.exec("PRAGMA user_version = " + next);
                return null;
            });
        }
    }

    public static int version(Db db) throws Exception {
        List<Object[]> rows = db.query("PRAGMA user_version");
        return ((Number) rows.get(0)[0]).intValue();
    }
}
