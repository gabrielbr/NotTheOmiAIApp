package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.RawItem;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown vault (Obsidian-style, like Sentient OS): README.md is the portrait, then one note
 * per person and per chat, a note per day and the open to-dos, linked with [[wiki links]].
 * Citations become "(WhatsApp · 8 Oct)" since item ids mean nothing outside GMind. The files are
 * plaintext in a folder the user picked. GMind only ever replaces or removes files it wrote itself,
 * listed in its manifest. Plain Java (host-tested); Android writes through SafVaultWriter.
 */
public final class Vault {
    static final String MANIFEST = ".gmind-vault.json";
    static final int MAX_PEOPLE = 300, MAX_CHATS = 200, MAX_ENTITIES = 300, CHAT_MESSAGES = 50, PERSON_RECENT = 15, DAYS = 30, MAX_LINE = 400;
    static final long CHAT_WINDOW_MS = 90L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
            DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final Pattern CITE = Pattern.compile("\\s*\\[#(\\d+)\\]"), UNSAFE = Pattern.compile("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]+");

    /** Where the files go: a folder the user picked (Android) or a map (tests). Paths use "/". */
    public interface Writer {
        void write(String path, String content) throws Exception;
        void delete(String path) throws Exception;
        /** The file's text, or null when it doesn't exist. */
        String read(String path) throws Exception;
    }

    /** First lines of README.md, for Claude (or anyone) opening the vault cold, as in Sentient OS. */
    static final String GUIDE = "> **GMind vault** · a personal knowledge base exported by GMind from the owner's messages, emails,\n"
            + "> calendar, files, tasks and recordings. Start here: the portrait below says who they are. Folders: People/ (one note\n"
            + "> per person), Chats/, Days/ (a digest per day), Projects/, Places/, Topics/, and Tasks.md. Notes link with [[Name]];\n"
            + "> \"(WhatsApp · 8 Oct)\" marks where a line comes from. Read-only copy: changes here don't go back to GMind.\n\n";

    private final Db db;
    private final ZoneId zone;
    private final Map<Long, String> citeCache = new HashMap<>();
    /** Person id → note name, and conversation id → note name, so links match file names. */
    private final Map<Long, String> personNotes = new LinkedHashMap<>(), chatNotes = new LinkedHashMap<>(),
            entityNotes = new LinkedHashMap<>(), entityFolders = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();

    private Vault(Db db, ZoneId zone) { this.db = db; this.zone = zone; }

    /** Writes the whole vault and removes notes GMind wrote before that no longer exist. Returns the note count. */
    public static int export(Db db, Writer writer, long now, ZoneId zone) throws Exception {
        Map<String, String> files = build(db, now, zone);
        Set<String> previous = new HashSet<>();
        String manifest = writer.read(MANIFEST);
        if (manifest == null && (writer.read("README.md") != null || writer.read("Tasks.md") != null))
            throw new IllegalStateException("This folder already has notes. Pick an empty folder for GMind's vault.");
        if (manifest != null) {
            try { for (Object p : Json.list(Json.at(Json.parse(manifest), "files"))) if (p instanceof String) previous.add((String) p); }
            catch (IllegalArgumentException corrupt) { /* start a fresh list; nothing is deleted */ }
        }
        for (Map.Entry<String, String> f : files.entrySet()) writer.write(f.getKey(), f.getValue());
        for (String old : previous) if (!files.containsKey(old) && safe(old)) writer.delete(old);
        writer.write(MANIFEST, Json.write(Json.object("written_by", "GMind", "exported_at", Instant.ofEpochMilli(now).toString(),
                "files", new ArrayList<>(files.keySet()))));
        return files.size();
    }

    private static final Pattern OWN = Pattern.compile("(?:README|Tasks)\\.md|(?:People|Chats|Days|Projects|Places|Topics)/[^/\\\\]+\\.md");

    /** Only the kinds of notes GMind writes, never anything that climbs out of the folder. */
    static boolean safe(String path) {
        return OWN.matcher(path).matches() && !path.contains("..");
    }

    static Map<String, String> build(Db db, long now, ZoneId zone) throws Exception {
        return new Vault(db, zone).notes(now);
    }

    private Map<String, String> notes(long now) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        usedNames.add("readme");
        usedNames.add("tasks");
        // Names first, so every note can link to every other.
        for (Object[] p : db.query("SELECT people.id, people.display_name FROM people"
                + " JOIN identities ON identities.person_id = people.id JOIN items ON items.author_identity_id = identities.id"
                + " WHERE " + Relevance.visible() + " GROUP BY people.id HAVING SUM(items.kind = ?) > 0 OR COUNT(*) >= 2 OR MAX(people.is_me) = 1"
                + " OR EXISTS (SELECT 1 FROM entities WHERE entities.canonical_key = 'person:' || people.id)"
                + " ORDER BY MAX(people.is_me) DESC, COUNT(*) DESC LIMIT ?", RawItem.MESSAGE, MAX_PEOPLE))
            personNotes.put(((Number) p[0]).longValue(), unique((String) p[1]));
        for (Object[] c : db.query("SELECT conversations.id, conversations.title, conversations.source FROM conversations"
                + " JOIN items ON items.conversation_id = conversations.id WHERE conversations.kind IN ('dm', 'group')"
                + " AND conversations.title IS NOT NULL GROUP BY conversations.id HAVING MAX(items.ts) >= ?"
                + " ORDER BY MAX(items.ts) DESC LIMIT ?", now - CHAT_WINDOW_MS, MAX_CHATS))
            chatNotes.put(((Number) c[0]).longValue(), unique(c[1] + " (" + People.sourceName((String) c[2]) + ")"));

        for (Object[] e : db.query("SELECT entities.id, entities.name, entities.type FROM entities"
                + " JOIN mentions ON mentions.entity_id = entities.id WHERE entities.canonical_key NOT LIKE 'person:%'"
                + " GROUP BY entities.id HAVING COUNT(*) >= 2 ORDER BY COUNT(*) DESC LIMIT ?", MAX_ENTITIES)) {
            long id = ((Number) e[0]).longValue();
            entityNotes.put(id, unique((String) e[1]));
            entityFolders.put(id, folder((String) e[2]));
        }

        String portrait = Portrait.read(db);
        if (portrait == null) portrait = Portrait.build(db, now, zone);
        files.put("README.md", GUIDE + linkPeople(cite(portrait)));
        for (Map.Entry<Long, String> p : personNotes.entrySet()) files.put("People/" + p.getValue() + ".md", person(p.getKey(), p.getValue()));
        for (Map.Entry<Long, String> c : chatNotes.entrySet()) files.put("Chats/" + c.getValue() + ".md", chat(c.getKey(), c.getValue()));
        for (Map.Entry<Long, String> e : entityNotes.entrySet())
            files.put(entityFolders.get(e.getKey()) + "/" + e.getValue() + ".md", entity(e.getKey(), e.getValue()));
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (Object[] d : db.query("SELECT date, markdown FROM daily_digests WHERE date >= ? ORDER BY date DESC",
                today.minusDays(DAYS).toString()))
            files.put("Days/" + d[0] + ".md", linkPeople(cite((String) d[1])));
        files.put("Tasks.md", tasks(now));
        return files;
    }

    private String person(long id, String note) throws Exception {
        People.Person p = People.get(db, id);
        StringBuilder md = new StringBuilder("---\n");
        List<String> aliases = new ArrayList<>();
        Set<String> apps = new java.util.LinkedHashSet<>();
        for (People.Identity i : People.identities(db, id)) {
            String alias = i.name != null ? i.name : i.handle.startsWith("email:") ? i.handle.substring(6) : null;
            if (alias != null && !alias.equals(note) && !aliases.contains(alias) && !"me".equals(alias)) aliases.add(alias);
            apps.add(People.sourceName(i.source));
        }
        md.append("aliases: ").append(yamlList(aliases)).append('\n');
        md.append("apps: ").append(yamlList(new ArrayList<>(apps))).append('\n');
        if (p != null && p.me) md.append("me: true\n");
        md.append("---\n\n# ").append(note).append("\n\n");
        if (p != null) {
            md.append("- ").append(Digest.count(p.items, "item"));
            if (p.lastTs != null) md.append(", last ").append(STAMP.format(Instant.ofEpochMilli(p.lastTs).atZone(zone)));
            md.append("\n\n");
        }
        List<Object[]> personEntity = db.query("SELECT id FROM entities WHERE canonical_key = ?", "person:" + id);
        if (!personEntity.isEmpty()) graph(md, ((Number) personEntity.get(0)[0]).longValue());
        List<Object[]> chats = People.conversations(db, id, 12);
        if (!chats.isEmpty()) {
            md.append("## Where we talk\n\n");
            for (Object[] c : chats) {
                String chat = chatNotes.get(((Number) c[0]).longValue());
                String title = chat != null ? "[[" + chat + "]]" : c[1] + " (" + People.sourceName((String) c[2]) + ")";
                md.append("- ").append(title).append(" · ").append(Digest.count(((Number) c[3]).longValue(), "item")).append('\n');
            }
            md.append('\n');
        }
        List<Long> recent = People.recentItems(db, id, PERSON_RECENT);
        if (!recent.isEmpty()) {
            md.append("## Recent\n\n");
            for (long itemId : recent) {
                Items.Item item = Items.get(db, itemId);
                if (item == null) continue;
                md.append("- ").append(STAMP.format(Instant.ofEpochMilli(item.ts).atZone(zone))).append(" · ")
                        .append(People.sourceName(item.source));
                if (item.conversation != null) md.append(" · ").append(item.conversation);
                md.append(": ").append(oneLine(item.text)).append('\n');
            }
        }
        return md.toString().trim() + "\n";
    }

    private String entity(long id, String note) throws Exception {
        Object[] e = db.query("SELECT name, type FROM entities WHERE id = ?", id).get(0);
        List<String> aliases = new ArrayList<>();
        for (Object[] a : db.query("SELECT alias FROM entity_aliases WHERE entity_id = ? ORDER BY alias", id))
            if (!note.equals(a[0])) aliases.add((String) a[0]);
        StringBuilder md = new StringBuilder("---\naliases: ").append(yamlList(aliases)).append("\ntype: ").append(e[1])
                .append("\n---\n\n# ").append(note).append("\n\n");
        graph(md, id);
        List<Object[]> mentions = db.query("SELECT items.id FROM mentions JOIN items ON items.id = mentions.item_id"
                + " WHERE mentions.entity_id = ? ORDER BY items.ts DESC LIMIT ?", id, PERSON_RECENT);
        if (!mentions.isEmpty()) {
            md.append("## Mentioned in\n\n");
            for (Object[] m : mentions) {
                Items.Item item = Items.get(db, ((Number) m[0]).longValue());
                if (item == null) continue;
                md.append("- ").append(STAMP.format(Instant.ofEpochMilli(item.ts).atZone(zone))).append(" · ")
                        .append(People.sourceName(item.source));
                if (item.conversation != null) md.append(" · ").append(item.conversation);
                md.append(": ").append(oneLine(item.text)).append('\n');
            }
        }
        return md.toString().trim() + "\n";
    }

    /** Facts and relations of an entity, linking to the notes of related people and things. */
    private void graph(StringBuilder md, long entity) throws Exception {
        List<Object[]> facts = db.query("SELECT key, value FROM facts WHERE entity_id = ? ORDER BY key", entity);
        if (!facts.isEmpty()) {
            md.append("## Facts\n\n");
            for (Object[] f : facts) md.append("- ").append(((String) f[0]).replace('_', ' ')).append(": ").append(oneLine((String) f[1])).append('\n');
            md.append('\n');
        }
        List<Object[]> related = db.query("SELECT relations.type, other.id, other.name, other.canonical_key, relations.src_entity = ?"
                + " FROM relations JOIN entities other ON other.id = CASE WHEN relations.src_entity = ? THEN relations.dst_entity"
                + " ELSE relations.src_entity END WHERE relations.src_entity = ? OR relations.dst_entity = ?"
                + " ORDER BY relations.last_seen DESC LIMIT 30", entity, entity, entity, entity);
        if (!related.isEmpty()) {
            md.append("## Related\n\n");
            for (Object[] r : related) {
                String type = ((String) r[0]).replace('_', ' ');
                boolean outgoing = ((Number) r[4]).intValue() != 0;
                md.append("- ").append(outgoing ? type + " " : "").append(link(((Number) r[1]).longValue(), (String) r[2], (String) r[3]))
                        .append(outgoing ? "" : " (" + type + ")").append('\n');
            }
            md.append('\n');
        }
    }

    private String link(long entity, String name, String canonical) {
        String note = entityNotes.get(entity);
        if (note == null && canonical != null && canonical.startsWith("person:")) {
            try { note = personNotes.get(Long.parseLong(canonical.substring(7))); } catch (NumberFormatException ignored) { }
        }
        return note != null ? "[[" + note + "]]" : name;
    }

    private static String folder(String type) {
        switch (type) {
            case "place": return "Places";
            case "project": case "org": return "Projects";
            default: return "Topics";
        }
    }

    private String chat(long conversation, String note) throws Exception {
        StringBuilder md = new StringBuilder("# ").append(note).append("\n\n");
        List<Object[]> rows = db.query("SELECT items.ts, items.text, items.from_me, identities.person_id, identities.display_name"
                + " FROM items LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE items.conversation_id = ? ORDER BY items.ts DESC, items.id DESC LIMIT ?", conversation, CHAT_MESSAGES);
        md.append("_The last ").append(rows.size()).append(" messages._\n\n");
        for (int i = rows.size() - 1; i >= 0; i--) {
            Object[] r = rows.get(i);
            String who;
            if (((Number) r[2]).intValue() != 0) who = "Me";
            else if (r[3] != null && personNotes.containsKey(((Number) r[3]).longValue())) who = "[[" + personNotes.get(((Number) r[3]).longValue()) + "]]";
            else who = r[4] != null ? (String) r[4] : "Someone";
            md.append("- ").append(STAMP.format(Instant.ofEpochMilli((Long) r[0]).atZone(zone))).append(" **").append(who)
                    .append("**: ").append(oneLine((String) r[1])).append('\n');
        }
        return md.toString();
    }

    private String tasks(long now) throws Exception {
        StringBuilder md = new StringBuilder("# Tasks\n\n");
        List<Object[]> imported = db.query("SELECT id, source, text FROM items WHERE kind = ? AND text NOT LIKE 'Done:%'"
                + " ORDER BY ts DESC LIMIT 200", RawItem.TASK);
        if (!imported.isEmpty()) {
            md.append("## Open in Todoist and TickTick\n\n");
            for (Object[] t : imported)
                md.append("- [ ] ").append(oneLine(firstLine((String) t[2]))).append(" (").append(People.sourceName((String) t[1])).append(")\n");
            md.append('\n');
        }
        List<FoundTasks.Task> found = FoundTasks.list(db, FoundTasks.OPEN, now - 30L * 24 * 60 * 60 * 1000, 200);
        if (!found.isEmpty()) {
            md.append("## Mentioned in recordings and chats\n\n");
            for (FoundTasks.Task t : found)
                md.append("- [ ] ").append(oneLine(t.text)).append(" (").append(People.sourceName(t.source)).append(" · ")
                        .append(DAY.format(Instant.ofEpochMilli(t.ts).atZone(zone))).append(")\n");
        }
        if (imported.isEmpty() && found.isEmpty()) md.append("Nothing open.\n");
        return md.toString().trim() + "\n";
    }

    /** "[#41]" → " (WhatsApp · 8 Oct)". */
    String cite(String markdown) throws Exception {
        Matcher m = CITE.matcher(markdown);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            long id = Long.parseLong(m.group(1));
            String tag = citeCache.get(id);
            if (tag == null) {
                Items.Item item = Items.get(db, id);
                tag = item == null ? "" : " (" + People.sourceName(item.source) + " · " + DAY.format(Instant.ofEpochMilli(item.ts).atZone(zone)) + ")";
                citeCache.put(id, tag);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(tag));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Links people named at the start of list lines ("1. Ana · …", "- Ana · …") to their notes. */
    String linkPeople(String markdown) {
        Map<String, String> byName = new HashMap<>();
        for (String note : personNotes.values()) byName.put(note, note);
        StringBuilder out = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            Matcher m = Pattern.compile("^((?:\\d+\\.|-) )(.+?)( · .*)$").matcher(line);
            if (m.matches() && byName.containsKey(m.group(2))) line = m.group(1) + "[[" + m.group(2) + "]]" + m.group(3);
            out.append(line).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    /** A file-safe, unique note name. */
    private String unique(String name) {
        String base = UNSAFE.matcher(name == null ? "" : name).replaceAll(" ").replaceAll("\\s+", " ").trim();
        while (base.startsWith(".")) base = base.substring(1).trim();
        if (base.length() > 80) base = base.substring(0, 80).trim();
        if (base.isEmpty()) base = "Untitled";
        String candidate = base;
        for (int n = 2; !usedNames.add(candidate.toLowerCase(Locale.ROOT)); n++) candidate = base + " (" + n + ")";
        return candidate;
    }

    private static String yamlList(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) quoted.add("\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        return "[" + String.join(", ", quoted) + "]";
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String oneLine(String text) {
        String s = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > MAX_LINE ? s.substring(0, MAX_LINE) + "…" : s;
    }
}
