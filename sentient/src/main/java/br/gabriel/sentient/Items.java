package br.gabriel.sentient;

import java.util.List;

/** One stored item, for the detail screen. Plain Java so host tests run it. */
public final class Items {
    private Items() {}

    public static final class Item {
        public final long id, ts;
        public final String source, kind, text, conversation, author;
        public final Long conversationId;
        public final boolean fromMe;
        /** Relevance.noise value, why it's hidden (null when it isn't), and the author's handle. */
        public final int noise;
        public final String noiseReason, authorHandle;
        Item(Object[] row) {
            id = ((Number) row[0]).longValue();
            source = (String) row[1];
            kind = (String) row[2];
            ts = ((Number) row[3]).longValue();
            text = (String) row[4];
            conversation = (String) row[5];
            conversationId = row[6] == null ? null : ((Number) row[6]).longValue();
            author = (String) row[7];
            fromMe = ((Number) row[8]).intValue() != 0;
            noise = row.length > 9 ? ((Number) row[9]).intValue() : Relevance.KEEP;
            noiseReason = row.length > 10 ? (String) row[10] : null;
            authorHandle = row.length > 11 ? (String) row[11] : null;
        }
        /** Left out of memory (feed, Ask, portrait…) under the current setting. */
        public boolean hidden() { return noise == Relevance.HIDDEN_BY_YOU || (Relevance.enabled && noise > 0); }
    }

    private static final String SELECT = "SELECT items.id, items.source, items.kind, items.ts, items.text,"
            + " conversations.title, items.conversation_id, identities.display_name, items.from_me,"
            + " items.noise, items.noise_reason, identities.handle FROM items"
            + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
            + " LEFT JOIN identities ON identities.id = items.author_identity_id";

    /** Null when the item no longer exists. */
    public static Item get(Db db, long id) throws Exception {
        List<Object[]> rows = db.query(SELECT + " WHERE items.id = ?", id);
        return rows.isEmpty() ? null : new Item(rows.get(0));
    }

    /** Characters of text kept for a list row; transcripts can be long. */
    static final int PREVIEW_CHARS = 280;

    /** The newest {@code limit} items across sources, newest first, with text cut to a preview. */
    public static List<Item> recent(Db db, int limit) throws Exception {
        List<Item> result = new java.util.ArrayList<>();
        for (Object[] row : db.query(SELECT.replace("items.text,", "substr(items.text, 1, " + PREVIEW_CHARS + "),")
                + " WHERE " + Relevance.visible() + " ORDER BY items.ts DESC, items.id DESC LIMIT ?", limit))
            result.add(new Item(row));
        return result;
    }

    /** Items hidden from memory, grouped by why (then newest first), text cut to a preview. */
    public static List<Item> hidden(Db db, int limit) throws Exception {
        List<Item> result = new java.util.ArrayList<>();
        for (Object[] row : db.query(SELECT.replace("items.text,", "substr(items.text, 1, " + PREVIEW_CHARS + "),")
                + " WHERE NOT (" + Relevance.visible() + ") ORDER BY items.noise_reason, items.ts DESC LIMIT ?", limit))
            result.add(new Item(row));
        return result;
    }

    /** Up to {@code n} messages before and after {@code item} in its conversation, oldest first,
     * including the item itself. Just the item when it has no conversation. */
    public static List<Item> around(Db db, Item item, int n) throws Exception {
        List<Item> result = new java.util.ArrayList<>();
        if (item.conversationId == null) { result.add(item); return result; }
        List<Object[]> before = db.query(SELECT + " WHERE items.conversation_id = ? AND (items.ts < ? OR"
                + " (items.ts = ? AND items.id < ?)) ORDER BY items.ts DESC, items.id DESC LIMIT ?",
                item.conversationId, item.ts, item.ts, item.id, n);
        for (int i = before.size() - 1; i >= 0; i--) result.add(new Item(before.get(i)));
        result.add(item);
        for (Object[] row : db.query(SELECT + " WHERE items.conversation_id = ? AND (items.ts > ? OR"
                + " (items.ts = ? AND items.id > ?)) ORDER BY items.ts, items.id LIMIT ?",
                item.conversationId, item.ts, item.ts, item.id, n)) result.add(new Item(row));
        return result;
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
