package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * To-dos found in what you said and wrote: recordings, your own messages, and what people ask of
 * you in the chats GMind watches (direct chats by default, groups you choose), with due dates. Each points at the item it came from. Suggestions only: you send one to
 * Todoist or dismiss it. Plain Java (host-tested).
 */
public final class FoundTasks {
    public static final String OPEN = "open", SHARED = "shared", DISMISSED = "dismissed", DONE = "done";
    static final String CURSOR = "tasks.ingested_at";

    private FoundTasks() {}

    public static final class Task {
        public final long id, itemId, ts;
        public final String text, status, source, conversation, author, due, todoistId;
        public final boolean fromMe;
        Task(Object[] r) {
            id = ((Number) r[0]).longValue();
            itemId = ((Number) r[1]).longValue();
            text = (String) r[2];
            status = (String) r[3];
            ts = ((Number) r[4]).longValue();
            source = (String) r[5];
            conversation = (String) r[6];
            author = (String) r[7];
            fromMe = ((Number) r[8]).intValue() != 0;
            due = r.length > 9 ? (String) r[9] : null;
            todoistId = r.length > 10 ? (String) r[10] : null;
        }
    }

    static final String NAMES = "me.names";

    /** Your name and nicknames, e.g. ["Gabriel", "Gabi"]; empty until you say. */
    public static List<String> names(Db db) throws Exception { return Requests.splitNames(Meta.get(db, NAMES)); }

    /** Saves your names; the first also becomes the name of the Me person. */
    public static void setNames(Db db, List<String> names) throws Exception {
        if (names.isEmpty()) { Meta.set(db, NAMES, null); return; }
        Meta.set(db, NAMES, String.join(", ", names));
        db.exec("UPDATE people SET display_name = ? WHERE is_me = 1", names.get(0));
    }

    /** True when GMind looks for to-dos in this chat: your override, else on for direct chats and off for groups. */
    public static boolean watched(Db db, long conversation) throws Exception {
        List<Object[]> o = db.query("SELECT watched FROM watched_chats WHERE conversation_id = ?", conversation);
        if (!o.isEmpty()) return ((Number) o.get(0)[0]).intValue() != 0;
        List<Object[]> k = db.query("SELECT kind FROM conversations WHERE id = ?", conversation);
        return !k.isEmpty() && "dm".equals(k.get(0)[0]);
    }

    public static void setWatched(Db db, long conversation, boolean on) throws Exception {
        db.exec("INSERT OR REPLACE INTO watched_chats(conversation_id, watched) VALUES(?, ?)", conversation, on ? 1 : 0);
    }

    /** Chats GMind could watch (direct chats and groups with messages), busiest recent first:
     * {conversation id, title, source, kind, watched (0/1), last ts}. */
    public static List<Object[]> chats(Db db, long since, int limit) throws Exception {
        return db.query("SELECT conversations.id, conversations.title, conversations.source, conversations.kind,"
                + " COALESCE((SELECT watched FROM watched_chats WHERE conversation_id = conversations.id),"
                + "   CASE WHEN conversations.kind = 'dm' THEN 1 ELSE 0 END), MAX(items.ts)"
                + " FROM conversations JOIN items ON items.conversation_id = conversations.id"
                + " WHERE conversations.kind IN ('dm', 'group') AND items.kind = ? AND conversations.title IS NOT NULL"
                + " GROUP BY conversations.id HAVING MAX(items.ts) >= ? ORDER BY conversations.source, MAX(items.ts) DESC LIMIT ?",
                RawItem.MESSAGE, since, limit);
    }

    /**
     * Looks at items stored or changed since the last scan (a refined transcript is re-read).
     * Recordings and your own messages: things you said you'd do. Watched chats (direct chats by
     * default, groups you switch on): what others ask of you, named in groups. Returns how many were found.
     */
    public static int scan(Db db, long now, ZoneId zone) throws Exception {
        long since = Meta.getLong(db, CURSOR, 0);
        List<Object[]> rows = db.query(ROWS + " WHERE items.ingested_at > ? AND items.kind IN (?, ?)",
                since, RawItem.TRANSCRIPT, RawItem.MESSAGE);
        long newest = since;
        for (Object[] r : rows) newest = Math.max(newest, ((Number) r[2]).longValue());
        int found = scanRows(db, rows, now, zone);
        if (newest > since) Meta.set(db, CURSOR, Long.toString(newest));
        return found;
    }

    /** After you switch a chat on: reads its last {@code days} days again. */
    public static int scanChat(Db db, long conversation, long now, ZoneId zone, int days) throws Exception {
        return scanRows(db, db.query(ROWS + " WHERE items.conversation_id = ? AND items.kind = ? AND items.ts >= ?",
                conversation, RawItem.MESSAGE, now - days * 24L * 60 * 60 * 1000), now, zone);
    }

    private static final String ROWS = "SELECT items.id, items.text, items.ingested_at, items.kind, items.from_me,"
            + " items.conversation_id, conversations.kind, items.ts FROM items"
            + " LEFT JOIN conversations ON conversations.id = items.conversation_id";

    private static int scanRows(Db db, List<Object[]> rows, long now, ZoneId zone) throws Exception {
        List<String> names = names(db);
        int found = 0;
        java.util.Map<Long, Boolean> watching = new java.util.HashMap<>();
        for (Object[] r : rows) {
            String text = (String) r[1];
            long ts = ((Number) r[7]).longValue();
            List<String> tasks = new ArrayList<>();
            boolean mine = RawItem.TRANSCRIPT.equals(r[3]) || ((Number) r[4]).intValue() != 0;
            if (mine) tasks.addAll(TaskExtractor.extract(text));
            else if (r[5] != null) {
                long conversation = ((Number) r[5]).longValue();
                Boolean on = watching.get(conversation);
                if (on == null) { on = watched(db, conversation); watching.put(conversation, on); }
                if (on) {
                    boolean group = !"dm".equals(r[6]);
                    if (!group) tasks.addAll(TaskExtractor.extract(text));
                    for (String t : Requests.find(text, names, group))
                        if (!containsFolded(tasks, t)) tasks.add(t);
                }
            }
            for (String task : tasks) {
                java.time.LocalDate due = DueDates.parse(text, ts, zone);
                if (db.insert("INSERT OR IGNORE INTO found_tasks(item_id, text, found_at, due) VALUES(?, ?, ?, ?)",
                        r[0], task, now, due == null ? null : due.toString()) > 0) found++;
            }
        }
        return found;
    }

    private static boolean containsFolded(List<String> tasks, String task) {
        String f = TaskExtractor.fold(task);
        for (String t : tasks) if (TaskExtractor.fold(t).equals(f) || TaskExtractor.fold(t).contains(f) || f.contains(TaskExtractor.fold(t))) return true;
        return false;
    }

    /** Newest first; {@code status} null for every status. */
    public static List<Task> list(Db db, String status, long since, int limit) throws Exception {
        List<Task> tasks = new ArrayList<>();
        for (Object[] r : db.query("SELECT found_tasks.id, found_tasks.item_id, found_tasks.text, found_tasks.status,"
                + " items.ts, items.source, conversations.title, identities.display_name, items.from_me,"
                + " found_tasks.due, found_tasks.todoist_id FROM found_tasks JOIN items ON items.id = found_tasks.item_id"
                + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE (? IS NULL OR found_tasks.status = ?) AND items.ts >= ?"
                + " ORDER BY items.ts DESC, found_tasks.id LIMIT ?", status, status, since, limit))
            tasks.add(new Task(r));
        return tasks;
    }

    public static void setStatus(Db db, long id, String status) throws Exception {
        db.exec("UPDATE found_tasks SET status = ? WHERE id = ?", status, id);
    }
}
