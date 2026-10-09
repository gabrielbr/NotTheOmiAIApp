package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.util.ArrayList;
import java.util.List;

/**
 * To-dos found in what you said and wrote: recordings, your own messages, and what people ask of
 * you in direct chats. Each points at the item it came from. Suggestions only: you send one to
 * Todoist or dismiss it. Plain Java (host-tested).
 */
public final class FoundTasks {
    public static final String OPEN = "open", SHARED = "shared", DISMISSED = "dismissed";
    static final String CURSOR = "tasks.ingested_at";

    private FoundTasks() {}

    public static final class Task {
        public final long id, itemId, ts;
        public final String text, status, source, conversation, author;
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
        }
    }

    /**
     * Looks at items stored or changed since the last scan (a refined transcript is re-read).
     * Returns how many new to-dos were found.
     */
    public static int scan(Db db, long now) throws Exception {
        long since = Meta.getLong(db, CURSOR, 0);
        List<Object[]> rows = db.query("SELECT items.id, items.text, items.ingested_at FROM items"
                + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items.ingested_at > ? AND (items.kind = ? OR (items.kind = ? AND (items.from_me = 1"
                + " OR conversations.kind = 'dm')))", since, RawItem.TRANSCRIPT, RawItem.MESSAGE);
        int found = 0;
        long newest = since;
        for (Object[] r : rows) {
            newest = Math.max(newest, ((Number) r[2]).longValue());
            for (String task : TaskExtractor.extract((String) r[1]))
                if (db.insert("INSERT OR IGNORE INTO found_tasks(item_id, text, found_at) VALUES(?, ?, ?)",
                        r[0], task, now) > 0) found++;
        }
        if (newest > since) Meta.set(db, CURSOR, Long.toString(newest));
        return found;
    }

    /** Newest first; {@code status} null for every status. */
    public static List<Task> list(Db db, String status, long since, int limit) throws Exception {
        List<Task> tasks = new ArrayList<>();
        for (Object[] r : db.query("SELECT found_tasks.id, found_tasks.item_id, found_tasks.text, found_tasks.status,"
                + " items.ts, items.source, conversations.title, identities.display_name, items.from_me"
                + " FROM found_tasks JOIN items ON items.id = found_tasks.item_id"
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
