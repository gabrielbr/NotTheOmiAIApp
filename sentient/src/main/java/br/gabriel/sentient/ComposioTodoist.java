package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Todoist: every open task, re-read each sync so edits show up, plus tasks completed since the last
 * sync (the first sync: the last 30 days). A completed task updates the same item.
 */
final class ComposioTodoist extends ComposioToolkit {
    static final String PROJECTS = "TODOIST_GET_ALL_PROJECTS", TASKS = "TODOIST_GET_ALL_TASKS",
            COMPLETED = "TODOIST_GET_COMPLETED_TASKS_BY_COMPLETION_DATE";
    static final int PAGES = 10, MAX_TEXT = 4000;
    private static final long OVERLAP_MS = 60 * 60 * 1000;

    @Override String slug() { return "todoist"; }
    @Override String displayName() { return "Todoist"; }
    @Override String reads() { return "Your open tasks, and tasks you complete from the last 30 days on."; }
    @Override Set<String> readTools() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PROJECTS, TASKS, COMPLETED)));
    }

    @Override PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception {
        Map<String, Object> state = state(cursor);
        Long last = state == null ? null : Json.num(state.get("completedSince"));
        long since = last != null ? last - OVERLAP_MS : now - FIRST_SYNC_MS;

        Map<String, String> projects = new LinkedHashMap<>();
        for (Object p : pages(tools, PROJECTS, Json.object("limit", 200L), "results", "projects")) {
            String id = Json.firstStr(p, "id"), name = Json.firstStr(p, "name");
            if (id != null && name != null) projects.put(id, name);
        }
        List<RawItem> items = new ArrayList<>();
        for (Object t : pages(tools, TASKS, Json.object("limit", 200L), "results", "tasks", "items")) {
            RawItem item = item(t, projects, false, now, zone);
            if (item != null) items.add(item);
        }
        // Todoist answers at most ~3 months per request; the window is 30 days plus the time between syncs.
        long from = Math.max(since, now - 90L * 24 * 60 * 60 * 1000);
        for (Object t : pages(tools, COMPLETED, Json.object("since", iso(from), "until", iso(now), "limit", 200L),
                "items", "results", "tasks")) {
            RawItem item = item(t, projects, true, now, zone);
            if (item != null) items.add(item);
        }
        return new PullResult(items, Json.write(Json.object("completedSince", now)), false);
    }

    private static List<Object> pages(Tools tools, String slug, Map<String, Object> first, String... keys) throws Exception {
        List<Object> all = new ArrayList<>();
        Map<String, Object> args = new LinkedHashMap<>(first);
        for (int p = 0; p < PAGES; p++) {
            Object data = tools.run(slug, args);
            all.addAll(array(data, keys));
            String next = nextCursor(data);
            if (next == null) break;
            args.put("cursor", next);
        }
        return all;
    }

    static RawItem item(Object t, Map<String, String> projects, boolean completed, long now, ZoneId zone) {
        String id = Json.firstStr(t, "task_id", "id");
        String content = Json.firstStr(t, "content");
        if (id == null || content == null) return null;
        boolean done = completed || Json.bool(Json.at(t, "checked")) || Json.bool(Json.at(t, "is_completed"));
        long ts = time(Json.at(t, "completed_at"), zone);
        if (ts == 0) ts = time(Json.at(t, "updated_at"), zone);
        if (ts == 0) ts = time(Json.at(t, "added_at"), zone);
        if (ts == 0) ts = time(Json.at(t, "created_at"), zone);
        if (ts == 0) ts = now;
        String project = Json.firstStr(t, "project_id");
        String projectName = project == null ? null : projects.get(project);
        StringBuilder text = new StringBuilder(done ? "Done: " + content : content);
        String due = Json.str(Json.at(t, "due", "string"));
        if (due == null) due = Json.str(Json.at(t, "due", "date"));
        if (due != null && !done) text.append("\nDue: ").append(due);
        if (projectName != null) text.append("\nProject: ").append(projectName);
        List<String> labels = new ArrayList<>();
        for (Object l : Json.list(Json.at(t, "labels"))) if (Json.str(l) != null) labels.add(Json.str(l));
        if (!labels.isEmpty()) text.append("\nLabels: ").append(String.join(", ", labels));
        String description = Json.firstStr(t, "description");
        if (description != null) text.append("\n\n").append(description.trim());
        RawItem.Builder b = RawItem.builder("composio.todoist", id)
                .kind(RawItem.TASK)
                .timestamp(ts)
                .text(cap(text.toString(), MAX_TEXT))
                .rawJson(Json.write(Json.object("id", id, "project_id", project, "done", done,
                        "due", Json.str(Json.at(t, "due", "date")), "priority", Json.at(t, "priority"))))
                .author("me", null, true);
        if (project != null) b.conversation(project, projectName, "project");
        return b.build();
    }
}
