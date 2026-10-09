package br.gabriel.sentient;

import java.util.ArrayList;
import java.util.List;

/** Per-plugin sync state: enabled flag, cursor, last result. */
public final class Sources {
    private Sources() {}

    public static final class State {
        public final String pluginId, cursor, lastStatus, notice;
        public final boolean enabled;
        public final Long lastSyncAt, lastItemAt;
        public final long itemCount;
        State(Object[] row) {
            pluginId = (String) row[0];
            enabled = ((Number) row[1]).intValue() != 0;
            cursor = (String) row[2];
            lastSyncAt = row[3] == null ? null : ((Number) row[3]).longValue();
            lastStatus = (String) row[4];
            itemCount = ((Number) row[5]).longValue();
            lastItemAt = row.length > 6 && row[6] != null ? ((Number) row[6]).longValue() : null;
            notice = row.length > 7 ? (String) row[7] : null;
        }
    }

    /** A standing, user-facing problem a live source reports (e.g. Signal hiding message content).
     * Unlike last_status, the daily sync never overwrites it; the source clears it itself. */
    static final String NOTICE = "notice";

    private static final String COLUMNS = "plugin_id, enabled, cursor, last_sync_at, last_status, item_count,"
            + " (SELECT MAX(ts) FROM items WHERE items.source = sources.plugin_id),"
            + " (SELECT value FROM source_config WHERE source_config.plugin_id = sources.plugin_id AND key = '" + NOTICE + "')";


    public static void setNotice(Db db, String pluginId, String notice) throws Exception {
        if (notice == null) db.exec("DELETE FROM source_config WHERE plugin_id = ? AND key = ?", pluginId, NOTICE);
        else setConfig(db, pluginId, NOTICE, notice);
    }

    public static State ensure(Db db, String pluginId) throws Exception {
        db.exec("INSERT OR IGNORE INTO sources(plugin_id) VALUES(?)", pluginId);
        return new State(db.query("SELECT " + COLUMNS + " FROM sources WHERE plugin_id = ?", pluginId).get(0));
    }

    public static List<State> all(Db db) throws Exception {
        List<State> result = new ArrayList<>();
        for (Object[] row : db.query("SELECT " + COLUMNS + " FROM sources ORDER BY plugin_id")) result.add(new State(row));
        return result;
    }

    static void advance(Db db, String pluginId, String cursor) throws Exception {
        db.exec("UPDATE sources SET cursor = ? WHERE plugin_id = ?", cursor, pluginId);
    }

    static void finish(Db db, String pluginId, long now, String status) throws Exception {
        db.exec("UPDATE sources SET last_sync_at = ?, last_status = ?,"
                + " item_count = (SELECT COUNT(*) FROM items WHERE source = ?) WHERE plugin_id = ?",
                now, status, pluginId, pluginId);
    }

    /**
     * Forgets a source and everything it brought in: its items (search index included, through
     * the delete trigger), conversations, identities, and people left with no identity at all.
     * Call inside a transaction.
     */
    public static int forget(Db db, String pluginId) throws Exception {
        List<Object[]> count = db.query("SELECT COUNT(*) FROM items WHERE source = ?", pluginId);
        db.exec("DELETE FROM mentions WHERE item_id IN (SELECT id FROM items WHERE source = ?)", pluginId);
        db.exec("DELETE FROM found_tasks WHERE item_id IN (SELECT id FROM items WHERE source = ?)", pluginId);
        db.exec("UPDATE relations SET evidence_item_id = NULL"
                + " WHERE evidence_item_id IN (SELECT id FROM items WHERE source = ?)", pluginId);
        db.exec("UPDATE facts SET evidence_item_id = NULL"
                + " WHERE evidence_item_id IN (SELECT id FROM items WHERE source = ?)", pluginId);
        db.exec("DELETE FROM items WHERE source = ?", pluginId);
        db.exec("DELETE FROM conversation_members WHERE conversation_id IN"
                + " (SELECT id FROM conversations WHERE source = ?)", pluginId);
        db.exec("DELETE FROM watched_chats WHERE conversation_id IN (SELECT id FROM conversations WHERE source = ?)", pluginId);
        db.exec("DELETE FROM conversations WHERE source = ?", pluginId);
        db.exec("DELETE FROM conversation_members WHERE identity_id IN"
                + " (SELECT id FROM identities WHERE source = ?)", pluginId);
        db.exec("DELETE FROM identities WHERE source = ?", pluginId);
        db.exec("DELETE FROM people WHERE is_me = 0 AND id NOT IN (SELECT person_id FROM identities)");
        db.exec("DELETE FROM not_same_person WHERE a NOT IN (SELECT id FROM people) OR b NOT IN (SELECT id FROM people)");
        db.exec("DELETE FROM source_config WHERE plugin_id = ?", pluginId);
        db.exec("DELETE FROM sources WHERE plugin_id = ?", pluginId);
        return ((Number) count.get(0)[0]).intValue();
    }

    public static String config(Db db, String pluginId, String key) throws Exception {
        List<Object[]> rows = db.query("SELECT value FROM source_config WHERE plugin_id = ? AND key = ?", pluginId, key);
        return rows.isEmpty() ? null : (String) rows.get(0)[0];
    }

    public static void setConfig(Db db, String pluginId, String key, String value) throws Exception {
        db.exec("INSERT OR REPLACE INTO source_config(plugin_id, key, value) VALUES(?, ?, ?)", pluginId, key, value);
    }
}
