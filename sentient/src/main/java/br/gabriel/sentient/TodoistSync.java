package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in sync of approved to-dos to Todoist through Composio: GMind creates each task (with its due
 * date and where it came from) and later marks it done when Todoist's completed tasks come back
 * through the composio.todoist source. GMind never edits, closes or deletes anything in Todoist.
 * Plain Java (host-tested).
 */
public final class TodoistSync {
    static final String CREATE = "TODOIST_CREATE_TASK";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private TodoistSync() {}

    /** Creates one task; returns Todoist's id for it. */
    public static String create(ComposioClient client, String userId, String accountId, FoundTasks.Task t, ZoneId zone)
            throws IOException, ComposioClient.ComposioException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("content", t.text);
        args.put("description", "From " + People.sourceName(t.source) + (t.conversation == null ? "" : " · " + t.conversation)
                + (t.fromMe || t.author == null || t.author.equals(t.conversation) ? "" : " · " + t.author)
                + " · " + DAY.format(Instant.ofEpochMilli(t.ts).atZone(zone)) + ", via GMind");
        if (t.due != null) args.put("due_date", t.due);
        Object data = client.write(CREATE, userId, accountId, args);
        String id = ComposioToolkit.field(data, "id", "task_id");
        if (id == null) id = Json.str(Json.at(data, "task", "id"));
        if (id == null) throw new ComposioClient.ComposioException(0, "Todoist didn't return the new task");
        return id;
    }

    /** Sends the picked open tasks; returns how many were created. Stops at the first failure (already-sent ones stay sent). */
    public static int send(Db db, ComposioClient client, String userId, String accountId, List<Long> ids, ZoneId zone)
            throws Exception {
        int sent = 0;
        for (FoundTasks.Task t : FoundTasks.list(db, FoundTasks.OPEN, 0, 5000)) {
            if (!ids.contains(t.id) || t.todoistId != null) continue;
            String todoistId = create(client, userId, accountId, t, zone);
            db.exec("UPDATE found_tasks SET status = ?, todoist_id = ? WHERE id = ?", FoundTasks.SHARED, todoistId, t.id);
            sent++;
        }
        return sent;
    }

    /** Tasks GMind sent that Todoist now lists as completed become done in GMind. Returns how many. */
    public static int reconcile(Db db) throws Exception {
        return db.update("UPDATE found_tasks SET status = ? WHERE todoist_id IS NOT NULL AND status = ?"
                + " AND EXISTS (SELECT 1 FROM items WHERE items.source = 'composio.todoist'"
                + " AND items.external_id = found_tasks.todoist_id AND items.text LIKE 'Done:%')",
                FoundTasks.DONE, FoundTasks.SHARED);
    }
}
