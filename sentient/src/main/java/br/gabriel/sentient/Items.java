package br.gabriel.sentient;

import java.util.List;

/** One stored item, for the detail screen. Plain Java so host tests run it. */
public final class Items {
    private Items() {}

    public static final class Item {
        public final long id, ts;
        public final String source, kind, text, conversation;
        Item(Object[] row) {
            id = ((Number) row[0]).longValue();
            source = (String) row[1];
            kind = (String) row[2];
            ts = ((Number) row[3]).longValue();
            text = (String) row[4];
            conversation = (String) row[5];
        }
    }

    /** Null when the item no longer exists. */
    public static Item get(Db db, long id) throws Exception {
        List<Object[]> rows = db.query("SELECT items.id, items.source, items.kind, items.ts, items.text,"
                + " conversations.title FROM items LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items.id = ?", id);
        return rows.isEmpty() ? null : new Item(rows.get(0));
    }

    /** The item's full text with every match of {@code query} wrapped in Search markers, or null
     * when the query is blank or doesn't match this item. */
    public static String highlighted(Db db, long id, String query) throws Exception {
        String match = Search.matchExpression(query);
        if (match.isEmpty()) return null;
        List<Object[]> rows = db.query("SELECT highlight(items_fts, 0, ?, ?) FROM items_fts"
                + " WHERE items_fts MATCH ? AND rowid = ?", Search.START, Search.END, match, id);
        return rows.isEmpty() ? null : (String) rows.get(0)[0];
    }
}
