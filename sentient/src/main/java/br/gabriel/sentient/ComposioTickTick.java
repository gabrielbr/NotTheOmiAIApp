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
 * TickTick: your open tasks across projects, re-read each sync so edits show up. TickTick's API only
 * lists open tasks, so a task you complete keeps its last open version here.
 */
final class ComposioTickTick extends ComposioToolkit {
    static final String PROJECTS = "TICKTICK_GET_USER_PROJECT", TASKS = "TICKTICK_LIST_ALL_TASKS";
    static final int MAX_TEXT = 4000;

    @Override String slug() { return "ticktick"; }
    @Override String displayName() { return "TickTick"; }
    @Override String reads() { return "Your open tasks. TickTick doesn't share completed ones."; }
    @Override Set<String> readTools() { return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PROJECTS, TASKS))); }

    @Override PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception {
        Map<String, String> projects = new LinkedHashMap<>();
        for (Object p : array(tools.run(PROJECTS, Collections.emptyMap()), "projects", "items")) {
            String id = Json.firstStr(p, "id"), name = Json.firstStr(p, "name");
            if (id != null && name != null) projects.put(id, name);
        }
        List<RawItem> items = new ArrayList<>();
        for (Object t : array(tools.run(TASKS, Json.object("limit", 500L)), "tasks", "items")) {
            RawItem item = item(t, projects, now, zone);
            if (item != null) items.add(item);
        }
        return new PullResult(items, Json.write(Json.object("at", now)), false);
    }

    static RawItem item(Object t, Map<String, String> projects, long now, ZoneId zone) {
        String id = Json.firstStr(t, "id"), title = Json.firstStr(t, "title");
        if (id == null || title == null) return null;
        boolean done = Json.num(Json.at(t, "status")) != null && Json.num(Json.at(t, "status")) == 2;
        long ts = time(Json.at(t, "completedTime"), zone);
        if (ts == 0) ts = time(Json.at(t, "modifiedTime"), zone);
        if (ts == 0) ts = time(Json.at(t, "createdTime"), zone);
        if (ts == 0) ts = time(Json.at(t, "startDate"), zone);
        if (ts == 0) ts = now;
        String project = Json.firstStr(t, "projectId");
        String projectName = project == null ? null : projects.get(project);
        if (projectName == null && project != null && project.startsWith("inbox")) projectName = "Inbox";
        StringBuilder text = new StringBuilder(done ? "Done: " + title : title);
        String due = Json.firstStr(t, "dueDate");
        if (due != null && !done) {
            long dueAt = time(due, zone);
            text.append("\nDue: ").append(dueAt == 0 ? due : java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate());
        }
        if (projectName != null) text.append("\nProject: ").append(projectName);
        String body = Json.firstStr(t, "content", "desc");
        if (body != null) text.append("\n\n").append(body.trim());
        for (Object check : Json.list(Json.at(t, "items"))) {
            String step = Json.firstStr(check, "title");
            if (step != null) text.append("\n- ").append(Json.num(Json.at(check, "status")) != null
                    && Json.num(Json.at(check, "status")) != 0 ? "[x] " : "[ ] ").append(step);
        }
        RawItem.Builder b = RawItem.builder("composio.ticktick", id)
                .kind(RawItem.TASK)
                .timestamp(ts)
                .text(cap(text.toString(), MAX_TEXT))
                .rawJson(Json.write(Json.object("id", id, "projectId", project, "status", Json.at(t, "status"),
                        "dueDate", due, "priority", Json.at(t, "priority"))))
                .author("me", null, true);
        if (project != null) b.conversation(project, projectName, "project");
        return b.build();
    }
}
