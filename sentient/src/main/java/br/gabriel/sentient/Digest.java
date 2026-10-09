package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * One day in Markdown: what came in, who you talked with, where, what was on the calendar and the
 * to-dos found. Every line cites an item as [#id]. Built from the data alone (no AI), stored in
 * daily_digests. Plain Java (host-tested).
 */
public final class Digest {
    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH),
            TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    static final int TOP = 8;

    private Digest() {}

    /** Builds and stores the digest for {@code day}; returns its Markdown, or null for a day with nothing. */
    public static String write(Db db, LocalDate day, ZoneId zone, long now) throws Exception {
        String markdown = build(db, day, zone);
        if (markdown == null) db.exec("DELETE FROM daily_digests WHERE date = ?", day.toString());
        else db.exec("INSERT OR REPLACE INTO daily_digests(date, markdown, generated_at) VALUES(?, ?, ?)",
                day.toString(), markdown, now);
        return markdown;
    }

    public static String read(Db db, LocalDate day) throws Exception {
        List<Object[]> rows = db.query("SELECT markdown FROM daily_digests WHERE date = ?", day.toString());
        return rows.isEmpty() ? null : (String) rows.get(0)[0];
    }

    /** Dates with a digest, newest first. */
    public static List<Object[]> days(Db db, int limit) throws Exception {
        return db.query("SELECT date FROM daily_digests ORDER BY date DESC LIMIT ?", limit);
    }

    static String build(Db db, LocalDate day, ZoneId zone) throws Exception {
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Object[]> counts = db.query("SELECT source, kind, COUNT(*) FROM items WHERE ts >= ? AND ts < ?"
                + " AND kind != ? GROUP BY source, kind ORDER BY COUNT(*) DESC", from, to, RawItem.EVENT);
        List<Object[]> events = db.query("SELECT id, ts, substr(text, 1, instr(text || char(10), char(10)) - 1) FROM items"
                + " WHERE kind = ? AND ts >= ? AND ts < ? ORDER BY ts LIMIT 20", RawItem.EVENT, from, to);
        if (counts.isEmpty() && events.isEmpty()) return null;

        StringBuilder md = new StringBuilder("# ").append(TITLE.format(day)).append("\n\n");
        if (!counts.isEmpty()) {
            md.append("## What came in\n\n");
            for (Object[] c : counts)
                md.append("- ").append(People.sourceName((String) c[0])).append(": ")
                        .append(count(((Number) c[2]).longValue(), noun((String) c[1]))).append('\n');
            md.append('\n');
        }

        List<Object[]> people = db.query("SELECT people.display_name, COUNT(*), MAX(items.id),"
                + " group_concat(DISTINCT items.source) FROM items"
                + " JOIN identities ON identities.id = items.author_identity_id JOIN people ON people.id = identities.person_id"
                + " WHERE items.ts >= ? AND items.ts < ? AND people.is_me = 0 AND items.kind IN (?, ?)"
                + " GROUP BY people.id ORDER BY COUNT(*) DESC LIMIT ?", from, to, RawItem.MESSAGE, RawItem.EMAIL, TOP);
        if (!people.isEmpty()) {
            md.append("## People\n\n");
            for (Object[] p : people)
                md.append("- ").append(p[0]).append(" · ").append(count(((Number) p[1]).longValue(), "item"))
                        .append(" in ").append(sources((String) p[3])).append(" [#").append(p[2]).append("]\n");
            md.append('\n');
        }

        List<Object[]> chats = db.query("SELECT conversations.title, conversations.source, COUNT(*), MAX(items.id)"
                + " FROM items JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items.ts >= ? AND items.ts < ? AND items.kind = ? AND conversations.title IS NOT NULL"
                + " GROUP BY conversations.id ORDER BY COUNT(*) DESC LIMIT ?", from, to, RawItem.MESSAGE, TOP);
        if (!chats.isEmpty()) {
            md.append("## Conversations\n\n");
            for (Object[] c : chats)
                md.append("- ").append(c[0]).append(" (").append(People.sourceName((String) c[1])).append(") · ")
                        .append(count(((Number) c[2]).longValue(), "message")).append(" [#").append(c[3]).append("]\n");
            md.append('\n');
        }

        List<Object[]> recordings = db.query("SELECT items.id, items.ts, conversations.title FROM items"
                + " LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items.kind = ? AND items.ts >= ? AND items.ts < ? ORDER BY items.ts LIMIT ?",
                RawItem.TRANSCRIPT, from, to, TOP);
        if (!recordings.isEmpty()) {
            md.append("## Recordings\n\n");
            for (Object[] r : recordings)
                md.append("- ").append(time((Long) r[1], zone)).append(' ')
                        .append(r[2] == null ? "Recording" : r[2]).append(" [#").append(r[0]).append("]\n");
            md.append('\n');
        }

        if (!events.isEmpty()) {
            md.append("## Calendar\n\n");
            for (Object[] e : events)
                md.append("- ").append(time((Long) e[1], zone)).append(' ').append(e[2]).append(" [#").append(e[0]).append("]\n");
            md.append('\n');
        }

        List<Object[]> tasks = db.query("SELECT found_tasks.text, found_tasks.item_id FROM found_tasks"
                + " JOIN items ON items.id = found_tasks.item_id WHERE items.ts >= ? AND items.ts < ?"
                + " AND found_tasks.status != ? ORDER BY items.ts LIMIT ?", from, to, FoundTasks.DISMISSED, TOP * 2);
        if (!tasks.isEmpty()) {
            md.append("## To-dos mentioned\n\n");
            for (Object[] t : tasks) md.append("- ").append(t[0]).append(" [#").append(t[1]).append("]\n");
            md.append('\n');
        }
        return md.toString().trim() + "\n";
    }

    static String noun(String kind) {
        switch (kind) {
            case RawItem.TRANSCRIPT: return "recording";
            case RawItem.EMAIL: return "email";
            case RawItem.DOC: return "file";
            case RawItem.TASK: return "task";
            case RawItem.EVENT: return "event";
            default: return "message";
        }
    }

    static String count(long n, String noun) { return n + " " + noun + (n == 1 ? "" : "s"); }

    static String sources(String ids) {
        if (ids == null) return "GMind";
        List<String> names = new java.util.ArrayList<>();
        for (String s : ids.split(",")) names.add(People.sourceName(s));
        return String.join(", ", names);
    }

    static String time(long ts, ZoneId zone) { return TIME.format(Instant.ofEpochMilli(ts).atZone(zone)); }
}
