package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "About me": a portrait of the user built from what GMind holds (who they are in each app, who
 * they talk to most, where, what's coming up, open to-dos), every line citing an item as [#id].
 * Regenerated after each sync and stored in meta; Ask reads it before answering, and the vault
 * exports it as README.md. Plain Java (host-tested).
 */
public final class Portrait {
    static final String KEY = "portrait", AT = "portrait.at";
    static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000, AHEAD_MS = 7L * 24 * 60 * 60 * 1000;
    static final int TOP = 10, BRIEF_CHARS = 1200;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH),
            DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private Portrait() {}

    public static String read(Db db) throws Exception { return Meta.get(db, KEY); }

    /** Rebuilds, stores and returns the portrait. */
    public static String write(Db db, long now, ZoneId zone) throws Exception {
        String md = build(db, now, zone);
        Meta.set(db, KEY, md);
        Meta.set(db, AT, Long.toString(now));
        return md;
    }

    /** The first lines, for prompts with little room (the on-phone model). */
    public static String brief(String portrait) {
        if (portrait == null) return null;
        StringBuilder out = new StringBuilder();
        for (String line : portrait.split("\n")) {
            if (line.startsWith("_") || line.trim().isEmpty()) continue;
            String clean = line.replaceAll("\\s*\\[#\\d+\\]", "");
            if (out.length() + clean.length() + 1 > BRIEF_CHARS) break;
            out.append(clean).append('\n');
        }
        return out.toString().trim();
    }

    static String build(Db db, long now, ZoneId zone) throws Exception {
        StringBuilder md = new StringBuilder("# About me\n\n_Built by GMind on ")
                .append(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
                .append(" from what it collected. Each line cites its evidence._\n\n");

        List<Object[]> names = db.query("SELECT identities.source, identities.display_name, identities.handle,"
                + " MAX(items.id) FROM identities JOIN people ON people.id = identities.person_id"
                + " LEFT JOIN items ON items.author_identity_id = identities.id"
                + " WHERE people.is_me = 1 GROUP BY identities.id ORDER BY identities.source");
        md.append("## Who I am\n\n");
        List<String> who = new ArrayList<>();
        for (Object[] n : names) {
            String name = (String) n[1], handle = (String) n[2];
            if ("me".equals(handle)) continue; // chat apps don't say your name
            String address = handle.startsWith("email:") ? handle.substring(6) : handle.startsWith("@") ? handle : null;
            String label = name != null && address != null ? name + " · " + address : name != null ? name : address != null ? address : handle;
            String line = label + " (" + People.sourceName((String) n[0]) + ")";
            boolean seen = false;
            for (String w : who) seen |= w.startsWith(line);
            if (!seen) who.add(line + (n[3] == null ? "" : " [#" + n[3] + "]"));
        }
        md.append(who.isEmpty() ? "- Not known yet: connect Gmail or Matrix, or rename yourself in People.\n"
                : "- Known as " + String.join(", ", who) + "\n");
        md.append('\n');

        long since = now - WINDOW_MS;
        List<Object[]> people = db.query("SELECT people.display_name, COUNT(*), MAX(items.id), group_concat(DISTINCT items.source),"
                + " MAX(items.ts) FROM items JOIN identities ON identities.id = items.author_identity_id"
                + " JOIN people ON people.id = identities.person_id WHERE items.ts >= ? AND people.is_me = 0"
                + " AND items.kind IN (?, ?, ?) GROUP BY people.id ORDER BY COUNT(*) DESC LIMIT ?",
                since, RawItem.MESSAGE, RawItem.EMAIL, RawItem.EVENT, TOP);
        if (!people.isEmpty()) {
            md.append("## People I talk to most (last 30 days)\n\n");
            int i = 1;
            for (Object[] p : people)
                md.append(i++).append(". ").append(p[0]).append(" · ").append(Digest.count(((Number) p[1]).longValue(), "item"))
                        .append(" in ").append(Digest.sources((String) p[3])).append(", last ")
                        .append(DAY.format(Instant.ofEpochMilli((Long) p[4]).atZone(zone))).append(" [#").append(p[2]).append("]\n");
            md.append('\n');
        }

        List<Object[]> chats = db.query("SELECT conversations.title, conversations.source, COUNT(*), MAX(items.id),"
                + " SUM(items.from_me) FROM items JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items.ts >= ? AND items.kind = ? AND conversations.kind = 'group' AND conversations.title IS NOT NULL"
                + " GROUP BY conversations.id ORDER BY COUNT(*) DESC LIMIT ?", since, RawItem.MESSAGE, TOP);
        if (!chats.isEmpty()) {
            md.append("## Groups I'm active in\n\n");
            for (Object[] c : chats)
                md.append("- ").append(c[0]).append(" (").append(People.sourceName((String) c[1])).append(") · ")
                        .append(Digest.count(((Number) c[2]).longValue(), "message"))
                        .append(((Number) c[4]).longValue() > 0 ? ", " + c[4] + " mine" : "")
                        .append(" [#").append(c[3]).append("]\n");
            md.append('\n');
        }

        List<Object[]> upcoming = db.query("SELECT id, ts, substr(text, 1, instr(text || char(10), char(10)) - 1) FROM items"
                + " WHERE kind = ? AND ts >= ? AND ts < ? AND text NOT LIKE 'Cancelled:%' ORDER BY ts LIMIT ?",
                RawItem.EVENT, now, now + AHEAD_MS, TOP);
        if (!upcoming.isEmpty()) {
            md.append("## Coming up (next 7 days)\n\n");
            for (Object[] e : upcoming)
                md.append("- ").append(WHEN.format(Instant.ofEpochMilli((Long) e[1]).atZone(zone))).append(" · ")
                        .append(e[2]).append(" [#").append(e[0]).append("]\n");
            md.append('\n');
        }

        List<Object[]> tasks = db.query("SELECT id, source, substr(text, 1, instr(text || char(10), char(10)) - 1) FROM items"
                + " WHERE kind = ? AND text NOT LIKE 'Done:%' ORDER BY ts DESC LIMIT ?", RawItem.TASK, TOP);
        List<FoundTasks.Task> found = FoundTasks.list(db, FoundTasks.OPEN, now - 14L * 24 * 60 * 60 * 1000, TOP);
        if (!tasks.isEmpty() || !found.isEmpty()) {
            md.append("## Open to-dos\n\n");
            for (Object[] t : tasks)
                md.append("- ").append(t[2]).append(" (").append(People.sourceName((String) t[1])).append(") [#").append(t[0]).append("]\n");
            for (FoundTasks.Task t : found)
                md.append("- ").append(t.text).append(" (said in ").append(People.sourceName(t.source)).append(", ")
                        .append(DAY.format(Instant.ofEpochMilli(t.ts).atZone(zone))).append(") [#").append(t.itemId).append("]\n");
            md.append('\n');
        }

        List<Object[]> sources = db.query("SELECT source, COUNT(*), MIN(ts), MAX(id) FROM items GROUP BY source ORDER BY COUNT(*) DESC");
        if (!sources.isEmpty()) {
            md.append("## What GMind knows from\n\n");
            for (Object[] s : sources)
                md.append("- ").append(People.sourceName((String) s[0])).append(": ")
                        .append(Digest.count(((Number) s[1]).longValue(), "item")).append(" since ")
                        .append(DAY.format(Instant.ofEpochMilli((Long) s[2]).atZone(zone))).append(" [#").append(s[3]).append("]\n");
        }
        return md.toString().trim() + "\n";
    }
}
