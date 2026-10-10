package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.util.List;
import java.util.Objects;

/**
 * Writes plugin items into the store. Idempotent: (source, externalId) identifies an item,
 * so a re-pulled item is updated in place (e.g. a transcript after Whisper refinement).
 * Call inside a transaction. Plain Java so host tests run it against real SQLite.
 */
public final class Ingest {
    private Ingest() {}

    public static final class Stats {
        public int added, updated, unchanged;
        @Override public String toString() { return added + " new, " + updated + " updated"; }
    }

    public static Stats upsert(Db db, List<RawItem> items, long now) throws Exception {
        Stats stats = new Stats();
        List<Long> changed = new java.util.ArrayList<>();
        for (RawItem item : items) {
            Long conversation = conversation(db, item);
            Long author = identity(db, item);
            if (conversation != null && author != null)
                db.exec("INSERT OR IGNORE INTO conversation_members(conversation_id, identity_id) VALUES(?, ?)",
                        conversation, author);
            List<Object[]> existing = db.query("SELECT id, text, ts, conversation_id, author_identity_id, raw_json"
                    + " FROM items WHERE source = ? AND external_id = ?", item.source, item.externalId);
            if (existing.isEmpty()) {
                changed.add(db.insert("INSERT INTO items(source, external_id, conversation_id, author_identity_id, from_me,"
                        + " ts, kind, text, raw_json, ingested_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        item.source, item.externalId, conversation, author, item.fromMe ? 1 : 0,
                        item.timestamp, item.kind, item.text, item.rawJson, now));
                stats.added++;
                continue;
            }
            Object[] row = existing.get(0);
            if (Objects.equals(row[1], item.text) && ((Number) row[2]).longValue() == item.timestamp
                    && Objects.equals(asLong(row[3]), conversation) && Objects.equals(asLong(row[4]), author)
                    && Objects.equals(row[5], item.rawJson)) {
                stats.unchanged++;
                continue;
            }
            db.update("UPDATE items SET text = ?, ts = ?, conversation_id = ?, author_identity_id = ?,"
                    + " from_me = ?, kind = ?, raw_json = ?, ingested_at = ? WHERE id = ?",
                    item.text, item.timestamp, conversation, author, item.fromMe ? 1 : 0, item.kind,
                    item.rawJson, now, row[0]);
            changed.add(((Number) row[0]).longValue());
            stats.updated++;
        }
        Relevance.judge(db, changed); // what's worth remembering
        return stats;
    }

    private static Long conversation(Db db, RawItem item) throws Exception {
        if (item.conversationExternalId == null) return null;
        db.exec("INSERT OR IGNORE INTO conversations(source, external_id, title, kind) VALUES(?, ?, ?, ?)",
                item.source, item.conversationExternalId, item.conversationTitle, item.conversationKind);
        if (item.conversationTitle != null)
            db.exec("UPDATE conversations SET title = ? WHERE source = ? AND external_id = ? AND title IS NOT ?",
                    item.conversationTitle, item.source, item.conversationExternalId, item.conversationTitle);
        return (Long) db.query("SELECT id FROM conversations WHERE source = ? AND external_id = ?",
                item.source, item.conversationExternalId).get(0)[0];
    }

    /** New handles get their own person; "me" handles join the built-in Me person. Merging
     * people across sources is identity resolution's job (a later phase), never automatic here. */
    private static Long identity(Db db, RawItem item) throws Exception {
        if (item.authorHandle == null) return null;
        List<Object[]> found = db.query("SELECT id FROM identities WHERE source = ? AND handle = ?",
                item.source, item.authorHandle);
        if (!found.isEmpty()) {
            Long id = (Long) found.get(0)[0];
            if (item.authorDisplayName != null)
                db.exec("UPDATE identities SET display_name = ? WHERE id = ? AND display_name IS NOT ?",
                        item.authorDisplayName, id, item.authorDisplayName);
            return id;
        }
        long person;
        if (item.fromMe) {
            person = (Long) db.query("SELECT id FROM people WHERE is_me = 1 ORDER BY id LIMIT 1").get(0)[0];
        } else {
            String name = item.authorDisplayName != null ? item.authorDisplayName : item.authorHandle;
            person = db.insert("INSERT INTO people(display_name, created_at) VALUES(?, ?)", name, item.timestamp);
        }
        return db.insert("INSERT INTO identities(person_id, source, handle, display_name) VALUES(?, ?, ?, ?)",
                person, item.source, item.authorHandle, item.authorDisplayName);
    }

    private static Long asLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
}
