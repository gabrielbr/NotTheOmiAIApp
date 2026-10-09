package br.gabriel.sentient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The read-only view an AI gets of the knowledge store. Every line it returns starts with the
 * item id as [#123], which answers cite. Inputs are validated and bounded; there is no SQL
 * passthrough and nothing here writes. Plain Java so host tests run it against real SQLite.
 */
public final class KnowledgeTools {
    public static final String SEARCH = "search", CONVERSATION = "conversation", PEOPLE = "people",
            TIMELINE = "timeline", ABOUT = "about";
    static final int MAX_LIMIT = 20, MAX_AROUND = 15, MAX_TIMELINE = 50, MAX_TEXT = 400;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Db db;
    private final ZoneId zone;

    public KnowledgeTools(Db db, ZoneId zone) { this.db = db; this.zone = zone; }

    /** Runs one tool call. Bad input throws IllegalArgumentException with a message for the model. */
    public String run(String tool, Map<String, Object> input) throws Exception {
        switch (tool) {
            case SEARCH:
                return search(str(input, "query"), str(input, "source"), str(input, "person"),
                        day(input, "from", false), day(input, "to", true), num(input, "limit", 10, 1, MAX_LIMIT));
            case CONVERSATION:
                Long id = longValue(input.get("item_id"));
                if (id == null) throw new IllegalArgumentException("item_id is required");
                return conversation(id, num(input, "around", 8, 1, MAX_AROUND));
            case PEOPLE:
                String name = str(input, "name");
                if (name == null) throw new IllegalArgumentException("name is required");
                return people(name);
            case TIMELINE:
                Long from = day(input, "from", false), to = day(input, "to", true);
                if (from == null || to == null) throw new IllegalArgumentException("from and to are required (YYYY-MM-DD)");
                return timeline(from, to, str(input, "source"), num(input, "limit", 30, 1, MAX_TIMELINE));
            case ABOUT:
                String what = str(input, "name");
                if (what == null) throw new IllegalArgumentException("name is required");
                return about(what);
            default:
                throw new IllegalArgumentException("Unknown tool " + tool);
        }
    }

    /** Full-text search; filters narrow it. A person filter alone lists their latest items. */
    public String search(String query, String source, String person, Long from, Long to, int limit) throws Exception {
        String match = Search.matchExpression(query);
        if (match.isEmpty() && person == null) throw new IllegalArgumentException("query or person is required");
        StringBuilder sql = new StringBuilder("SELECT items.id, items.ts, items.source, conversations.title,"
                + " identities.display_name, items.from_me, ");
        List<Object> args = new ArrayList<>();
        if (!match.isEmpty()) {
            sql.append("snippet(items_fts, 0, '', '', '…', 24) FROM items_fts JOIN items ON items.id = items_fts.rowid");
        } else {
            sql.append("substr(items.text, 1, ").append(MAX_TEXT).append(") FROM items");
        }
        sql.append(" LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " LEFT JOIN people ON people.id = identities.person_id WHERE 1 = 1");
        if (!match.isEmpty()) { sql.append(" AND items_fts MATCH ?"); args.add(match); }
        if (source != null) { sql.append(" AND items.source = ?"); args.add(source); }
        if (person != null) {
            sql.append(" AND (people.display_name LIKE ? OR identities.display_name LIKE ? OR conversations.title LIKE ?)");
            String like = "%" + person + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (from != null) { sql.append(" AND items.ts >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND items.ts < ?"); args.add(to); }
        sql.append(match.isEmpty() ? " ORDER BY items.ts DESC" : " ORDER BY bm25(items_fts)");
        sql.append(" LIMIT ?");
        args.add(limit);
        List<Object[]> rows = db.query(sql.toString(), args.toArray());
        if (rows.isEmpty()) return "No matches.";
        StringBuilder out = new StringBuilder();
        for (Object[] r : rows)
            line(out, (Long) r[0], (Long) r[1], (String) r[2], (String) r[3], (String) r[4], flag(r[5]), (String) r[6]);
        return out.toString();
    }

    /**
     * Search where any of the words may match (best matches first): for a small on-device model,
     * which can't refine a search itself, recall matters more than precision.
     */
    public String searchAny(List<String> words, int limit) throws Exception {
        StringBuilder any = new StringBuilder();
        for (String w : words) {
            String term = Search.matchExpression(w);
            if (term.isEmpty()) continue;
            if (any.length() > 0) any.append(" OR ");
            any.append(term);
        }
        if (any.length() == 0) return "No matches.";
        List<Object[]> rows = db.query("SELECT items.id, items.ts, items.source, conversations.title,"
                + " identities.display_name, items.from_me, snippet(items_fts, 0, '', '', '…', 24) FROM items_fts"
                + " JOIN items ON items.id = items_fts.rowid"
                + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE items_fts MATCH ? ORDER BY bm25(items_fts) LIMIT ?", any.toString(), Math.min(limit, MAX_LIMIT));
        if (rows.isEmpty()) return "No matches.";
        StringBuilder out = new StringBuilder();
        for (Object[] r : rows)
            line(out, (Long) r[0], (Long) r[1], (String) r[2], (String) r[3], (String) r[4], flag(r[5]), (String) r[6]);
        return out.toString();
    }

    /** The item plus its neighbours in the same conversation, oldest first. */
    public String conversation(long itemId, int around) throws Exception {
        Items.Item item = Items.get(db, itemId);
        if (item == null) return "No item #" + itemId + ".";
        StringBuilder out = new StringBuilder();
        for (Items.Item m : Items.around(db, item, around))
            line(out, m.id, m.ts, m.source, m.conversation, m.author, m.fromMe, m.text);
        return out.toString();
    }

    /** People whose name matches, with where they appear and how much. */
    public String people(String name) throws Exception {
        List<Object[]> rows = db.query("SELECT people.id, people.display_name, people.is_me,"
                + " group_concat(DISTINCT identities.source), COUNT(items.id), MAX(items.ts)"
                + " FROM people JOIN identities ON identities.person_id = people.id"
                + " LEFT JOIN items ON items.author_identity_id = identities.id"
                + " WHERE people.display_name LIKE ? OR identities.display_name LIKE ?"
                + " GROUP BY people.id ORDER BY COUNT(items.id) DESC LIMIT 10", "%" + name + "%", "%" + name + "%");
        if (rows.isEmpty()) return "No one named like that.";
        StringBuilder out = new StringBuilder();
        for (Object[] r : rows) {
            out.append(r[1]).append(flag(r[2]) ? " (you)" : "").append(" · in ").append(r[3])
                    .append(" · ").append(r[4]).append(" items");
            if (r[5] != null) out.append(" · last ").append(when((Long) r[5]));
            List<Object[]> chats = db.query("SELECT DISTINCT conversations.title FROM items"
                    + " JOIN identities ON identities.id = items.author_identity_id"
                    + " JOIN conversations ON conversations.id = items.conversation_id"
                    + " WHERE identities.person_id = ? LIMIT 5", r[0]);
            if (!chats.isEmpty()) {
                out.append(" · chats: ");
                for (int i = 0; i < chats.size(); i++) out.append(i == 0 ? "" : ", ").append(chats.get(i)[0]);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** What enrichment learned about a person, project, place or topic: facts, links and mentions. */
    public String about(String name) throws Exception {
        String like = "%" + name + "%";
        List<Object[]> found = db.query("SELECT DISTINCT entities.id, entities.name, entities.type FROM entities"
                + " LEFT JOIN entity_aliases ON entity_aliases.entity_id = entities.id"
                + " WHERE entities.name LIKE ? OR entity_aliases.alias LIKE ?"
                + " ORDER BY (SELECT COUNT(*) FROM mentions WHERE mentions.entity_id = entities.id) DESC LIMIT 3", like, like);
        if (found.isEmpty()) return "Nothing known about that yet (enrichment may be off, or it hasn't come up).";
        StringBuilder out = new StringBuilder();
        for (Object[] e : found) {
            out.append(e[1]).append(" (").append(e[2]).append(")\n");
            for (Object[] f : db.query("SELECT key, value, evidence_item_id FROM facts WHERE entity_id = ? ORDER BY key", e[0]))
                out.append("  ").append(f[0]).append(": ").append(f[1]).append(f[2] == null ? "" : " [#" + f[2] + "]").append('\n');
            for (Object[] r : db.query("SELECT relations.type, src.name, dst.name, relations.evidence_item_id FROM relations"
                    + " JOIN entities src ON src.id = relations.src_entity JOIN entities dst ON dst.id = relations.dst_entity"
                    + " WHERE relations.src_entity = ? OR relations.dst_entity = ? ORDER BY relations.last_seen DESC LIMIT 15", e[0], e[0]))
                out.append("  ").append(r[1]).append(" ").append(r[0]).append(" ").append(r[2])
                        .append(r[3] == null ? "" : " [#" + r[3] + "]").append('\n');
            for (Object[] m : db.query("SELECT items.id, items.ts, items.source, conversations.title, identities.display_name,"
                    + " items.from_me, substr(items.text, 1, " + MAX_TEXT + ") FROM mentions JOIN items ON items.id = mentions.item_id"
                    + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                    + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                    + " WHERE mentions.entity_id = ? ORDER BY items.ts DESC LIMIT 5", e[0]))
                line(out, (Long) m[0], (Long) m[1], (String) m[2], (String) m[3], (String) m[4], flag(m[5]), (String) m[6]);
        }
        return out.toString();
    }

    /** Everything in a time range, oldest first, capped. */
    public String timeline(long from, long to, String source, int limit) throws Exception {
        if (to <= from) throw new IllegalArgumentException("to must be after from");
        List<Object> args = new ArrayList<>();
        args.add(from); args.add(to);
        String filter = "";
        if (source != null) { filter = " AND items.source = ?"; args.add(source); }
        args.add(limit);
        List<Object[]> rows = db.query("SELECT items.id, items.ts, items.source, conversations.title,"
                + " identities.display_name, items.from_me, substr(items.text, 1, " + MAX_TEXT + ") FROM items"
                + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE items.ts >= ? AND items.ts < ?" + filter + " ORDER BY items.ts LIMIT ?", args.toArray());
        if (rows.isEmpty()) return "Nothing in that range.";
        StringBuilder out = new StringBuilder();
        for (Object[] r : rows)
            line(out, (Long) r[0], (Long) r[1], (String) r[2], (String) r[3], (String) r[4], flag(r[5]), (String) r[6]);
        return out.toString();
    }

    /** "[#12] 2026-10-09 14:02 · whatsapp · Família · Mãe: text" */
    private void line(StringBuilder out, long id, long ts, String source, String chat, String author, boolean me, String text) {
        out.append("[#").append(id).append("] ").append(when(ts)).append(" · ").append(source);
        if (chat != null) out.append(" · ").append(chat);
        String who = me ? "me" : author;
        out.append(who == null ? "" : " · " + who).append(": ");
        String t = text == null ? "" : text.replace('\n', ' ');
        out.append(t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "…" : t).append('\n');
    }

    String when(long ts) { return WHEN.format(Instant.ofEpochMilli(ts).atZone(zone)); }

    private static boolean flag(Object v) { return v != null && ((Number) v).intValue() != 0; }

    private static String str(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long longValue(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong(((String) v).replace("#", "").trim()); } catch (NumberFormatException e) { return null; }
        return null;
    }

    private static int num(Map<String, Object> input, String key, int fallback, int min, int max) {
        Long v = longValue(input.get(key));
        return v == null ? fallback : (int) Math.max(min, Math.min(max, v));
    }

    /** YYYY-MM-DD as the start of that day; {@code end} gives the start of the next day. */
    private Long day(Map<String, Object> input, String key, boolean end) {
        String v = str(input, key);
        if (v == null) return null;
        try {
            LocalDate d = LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
            return (end ? d.plusDays(1) : d).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(key + " must be YYYY-MM-DD");
        }
    }
}
