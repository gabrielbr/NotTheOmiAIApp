package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Google Calendar through GOOGLECALENDAR_EVENTS_LIST on the primary calendar. The first sync takes
 * events from 30 days ago on (upcoming ones included); later syncs take what changed since the last
 * one, so edits and cancellations update the same item.
 */
final class ComposioCalendar extends ComposioToolkit {
    static final String LIST = "GOOGLECALENDAR_EVENTS_LIST";
    static final int PAGE = 100, MAX_TEXT = 6000;
    /** Changes made while a sync runs are caught by the next one. */
    private static final long OVERLAP_MS = 10 * 60 * 1000;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    @Override String slug() { return "googlecalendar"; }
    @Override String displayName() { return "Google Calendar"; }
    @Override String reads() { return "Events on your main calendar, from 30 days ago on, upcoming ones included."; }
    @Override Set<String> readTools() { return Collections.singleton(LIST); }

    @Override PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception {
        Map<String, Object> state = state(cursor);
        Long updated = state == null ? null : Json.num(state.get("updated"));
        String page = state == null ? null : Json.str(state.get("page"));
        Long startedAt = state == null ? null : Json.num(state.get("started"));
        long started = startedAt != null && page != null ? startedAt : now;

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("calendarId", "primary");
        args.put("singleEvents", Boolean.TRUE);
        args.put("maxResults", (long) PAGE);
        if (updated == null) args.put("timeMin", iso(now - FIRST_SYNC_MS));
        else {
            args.put("updatedMin", iso(updated - OVERLAP_MS));
            args.put("showDeleted", Boolean.TRUE);
        }
        if (page != null) args.put("pageToken", page);
        Object data = tools.run(LIST, args);

        List<RawItem> items = new ArrayList<>();
        for (Object event : array(data, "items", "events")) {
            RawItem item = item(event, zone);
            if (item != null) items.add(item);
        }
        String next = field(data, "nextPageToken", "next_page_token");
        if (next != null)
            return new PullResult(items, Json.write(Json.object("updated", updated, "page", next, "started", started)), true);
        return new PullResult(items, Json.write(Json.object("updated", started)), false);
    }

    static RawItem item(Object e, ZoneId zone) {
        String id = Json.firstStr(e, "id");
        if (id == null) return null;
        long start = time(firstNonNull(Json.at(e, "start", "dateTime"), Json.at(e, "start", "date")), zone);
        if (start == 0) return null; // a bare cancellation stub: nothing to show
        long end = time(firstNonNull(Json.at(e, "end", "dateTime"), Json.at(e, "end", "date")), zone);
        boolean allDay = Json.at(e, "start", "dateTime") == null;
        String title = Json.firstStr(e, "summary");
        if (title == null) title = "(no title)";
        boolean cancelled = "cancelled".equals(Json.firstStr(e, "status"));

        StringBuilder text = new StringBuilder(cancelled ? "Cancelled: " + title : title);
        text.append("\nWhen: ").append(allDay ? Instant.ofEpochMilli(start).atZone(zone).toLocalDate() + " (all day)"
                : WHEN.format(Instant.ofEpochMilli(start).atZone(zone))
                        + (end > start ? " – " + WHEN.format(Instant.ofEpochMilli(end).atZone(zone)).substring(11) : ""));
        String location = Json.firstStr(e, "location");
        if (location != null) text.append("\nWhere: ").append(location);
        List<String> with = new ArrayList<>();
        for (Object a : Json.list(Json.at(e, "attendees"))) {
            if (Json.bool(Json.at(a, "self")) || Json.bool(Json.at(a, "resource"))) continue;
            String name = Json.firstStr(a, "displayName", "email");
            if (name != null) with.add(name);
        }
        if (!with.isEmpty()) text.append("\nWith: ").append(String.join(", ", with));
        String description = plain(Json.firstStr(e, "description"));
        if (!description.isEmpty()) text.append("\n\n").append(description);

        Object organizer = Json.at(e, "organizer");
        String email = Json.firstStr(organizer, "email");
        Map<String, Object> raw = new LinkedHashMap<>(Json.obj(e));
        raw.remove("description");
        RawItem.Builder b = RawItem.builder("composio.googlecalendar", id)
                .kind(RawItem.EVENT)
                .timestamp(start)
                .text(cap(text.toString(), MAX_TEXT))
                .rawJson(Json.write(raw))
                .conversation("primary", "Calendar", "calendar");
        if (email != null)
            b.author("email:" + email.toLowerCase(Locale.ROOT), Json.firstStr(organizer, "displayName"),
                    Json.bool(Json.at(organizer, "self")));
        return b.build();
    }

    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }
}
