package br.gabriel.sentient;

import java.util.ArrayList;
import java.util.List;

/** Full-text search over items (FTS5, accent-insensitive), best matches first. */
public final class Search {
    /** Wrap each match in snippets and highlighted text; never present in real text. */
    public static final char MATCH_START = '\u0002', MATCH_END = '\u0003';
    static final String START = String.valueOf(MATCH_START), END = String.valueOf(MATCH_END);

    private Search() {}

    public static final class Hit {
        public final long itemId, ts;
        public final String source, kind, conversation, snippet;
        Hit(Object[] row) {
            itemId = ((Number) row[0]).longValue();
            source = (String) row[1];
            kind = (String) row[2];
            ts = ((Number) row[3]).longValue();
            conversation = (String) row[4];
            snippet = (String) row[5];
        }
    }

    public static List<Hit> find(Db db, String query, int limit) throws Exception {
        List<Hit> hits = new ArrayList<>();
        String match = matchExpression(query);
        if (match.isEmpty()) return hits;
        for (Object[] row : db.query("SELECT items.id, items.source, items.kind, items.ts, conversations.title,"
                + " snippet(items_fts, 0, ?, ?, '…', 16) FROM items_fts"
                + " JOIN items ON items.id = items_fts.rowid"
                + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items_fts MATCH ? ORDER BY bm25(items_fts) LIMIT ?", START, END, match, limit))
            hits.add(new Hit(row));
        return hits;
    }

    /** Free text → FTS5 query: every word must match, as a prefix. Never a syntax error. */
    static String matchExpression(String query) {
        StringBuilder out = new StringBuilder();
        if (query == null) return "";
        for (String word : query.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append('"').append(word).append("\"*");
        }
        return out.toString();
    }
}
