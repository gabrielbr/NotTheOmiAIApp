package br.gabriel.sentient;

import java.util.List;

/** Small key/value state kept in the encrypted store (enrichment cursors, the portrait). */
public final class Meta {
    private Meta() {}

    public static String get(Db db, String key) throws Exception {
        List<Object[]> rows = db.query("SELECT value FROM meta WHERE key = ?", key);
        return rows.isEmpty() ? null : (String) rows.get(0)[0];
    }

    public static void set(Db db, String key, String value) throws Exception {
        if (value == null) db.exec("DELETE FROM meta WHERE key = ?", key);
        else db.exec("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", key, value);
    }

    static long getLong(Db db, String key, long fallback) throws Exception {
        String v = get(db, key);
        try { return v == null ? fallback : Long.parseLong(v); } catch (NumberFormatException corrupt) { return fallback; }
    }
}
