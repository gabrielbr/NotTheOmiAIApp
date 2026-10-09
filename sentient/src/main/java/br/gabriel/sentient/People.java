package br.gabriel.sentient;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One person across apps. The same email address in Gmail, Calendar and Drive is the same person,
 * so those merge on their own; everything else (the same name in WhatsApp and Matrix) is only a
 * suggestion you confirm or dismiss. Merges can be undone by separating an identity again.
 * Plain Java (host-tested).
 */
public final class People {
    private People() {}

    public static final class Person {
        public final long id, items;
        public final String name, sources;
        public final boolean me;
        public final Long lastTs;
        Person(Object[] r) {
            id = ((Number) r[0]).longValue();
            name = (String) r[1];
            me = ((Number) r[2]).intValue() != 0;
            sources = (String) r[3];
            items = r[4] == null ? 0 : ((Number) r[4]).longValue();
            lastTs = r[5] == null ? null : ((Number) r[5]).longValue();
        }
    }

    public static final class Identity {
        public final long id;
        public final String source, handle, name;
        public final long items;
        Identity(Object[] r) {
            id = ((Number) r[0]).longValue();
            source = (String) r[1];
            handle = (String) r[2];
            name = (String) r[3];
            items = ((Number) r[4]).longValue();
        }
    }

    /** Two people who look like one, and why. */
    public static final class Suggestion {
        public final Person a, b;
        public final String reason;
        Suggestion(Person a, Person b, String reason) { this.a = a; this.b = b; this.reason = reason; }
    }

    private static final String PERSON = "SELECT people.id, people.display_name, people.is_me,"
            + " (SELECT group_concat(DISTINCT identities.source) FROM identities WHERE identities.person_id = people.id),"
            + " (SELECT COUNT(*) FROM items JOIN identities ON identities.id = items.author_identity_id"
            + "   WHERE identities.person_id = people.id),"
            + " (SELECT MAX(items.ts) FROM items JOIN identities ON identities.id = items.author_identity_id"
            + "   WHERE identities.person_id = people.id)"
            + " FROM people";

    /** Everyone with at least one item, most active first; you first of all. */
    public static List<Person> list(Db db, int limit) throws Exception {
        List<Person> people = new ArrayList<>();
        for (Object[] r : db.query("SELECT * FROM (" + PERSON + ") ORDER BY 3 DESC, 5 DESC, 2 LIMIT ?", limit)) {
            Person p = new Person(r);
            if (p.items > 0 || p.me) people.add(p);
        }
        return people;
    }

    public static Person get(Db db, long id) throws Exception {
        List<Object[]> rows = db.query(PERSON + " WHERE people.id = ?", id);
        return rows.isEmpty() ? null : new Person(rows.get(0));
    }

    public static List<Identity> identities(Db db, long person) throws Exception {
        List<Identity> result = new ArrayList<>();
        for (Object[] r : db.query("SELECT identities.id, identities.source, identities.handle, identities.display_name,"
                + " (SELECT COUNT(*) FROM items WHERE items.author_identity_id = identities.id)"
                + " FROM identities WHERE person_id = ? ORDER BY identities.source, identities.id", person))
            result.add(new Identity(r));
        return result;
    }

    /** Merges people who share an email address across Google sources. Returns how many merged. */
    public static int mergeSameAddresses(Db db) throws Exception {
        int merged = 0;
        for (int pass = 0; pass < 20; pass++) {
            int before = merged;
            merged += mergePass(db);
            if (merged == before) break;
        }
        return merged;
    }

    private static int mergePass(Db db) throws Exception {
        int merged = 0;
        for (Object[] r : db.query("SELECT a.person_id, b.person_id FROM identities a JOIN identities b"
                + " ON a.handle = b.handle AND a.source < b.source AND a.person_id != b.person_id"
                + " WHERE a.handle LIKE 'email:%'")) {
            long a = ((Number) r[0]).longValue(), b = ((Number) r[1]).longValue();
            if (get(db, a) == null || get(db, b) == null) continue; // already merged in this pass
            if (kept(db, a, b)) continue;
            merge(db, a, b);
            merged++;
        }
        return merged;
    }

    /** True when the person kept these two apart. */
    private static boolean kept(Db db, long a, long b) throws Exception {
        return !db.query("SELECT 1 FROM not_same_person WHERE a = ? AND b = ?", Math.min(a, b), Math.max(a, b)).isEmpty();
    }

    /** People with the same name in different apps, never ones you said are different. */
    public static List<Suggestion> suggestions(Db db, int limit) throws Exception {
        Map<String, List<Person>> byName = new LinkedHashMap<>();
        Map<Long, Person> people = new HashMap<>();
        for (Person p : list(db, 5000)) people.put(p.id, p);
        for (Object[] r : db.query("SELECT DISTINCT person_id, display_name FROM identities WHERE display_name IS NOT NULL"
                + " UNION SELECT id, display_name FROM people")) {
            Person p = people.get(((Number) r[0]).longValue());
            String key = nameKey((String) r[1]);
            if (p == null || key.length() < 3) continue;
            List<Person> same = byName.computeIfAbsent(key, k -> new ArrayList<>());
            boolean already = false;
            for (Person q : same) already |= q.id == p.id;
            if (!already) same.add(p);
        }
        List<Suggestion> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, List<Person>> e : byName.entrySet()) {
            List<Person> same = e.getValue();
            for (int i = 0; i < same.size(); i++) for (int j = i + 1; j < same.size(); j++) {
                Person a = same.get(i), b = same.get(j);
                if (a.me && b.me) continue;
                if (sameSourcesOnly(a, b) || kept(db, a.id, b.id) || !seen.add(Math.min(a.id, b.id) + ":" + Math.max(a.id, b.id)))
                    continue;
                Person first = a.me || (!b.me && a.items >= b.items) ? a : b, second = first == a ? b : a;
                result.add(new Suggestion(first, second, "Same name in " + label(first.sources) + " and " + label(second.sources)));
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    /** Two names in the same single app are usually two people (e.g. two "Ana"s in WhatsApp). */
    private static boolean sameSourcesOnly(Person a, Person b) {
        return a.sources != null && a.sources.equals(b.sources) && !a.sources.contains(",");
    }

    private static String label(String sources) {
        if (sources == null) return "GMind";
        List<String> names = new ArrayList<>();
        for (String s : sources.split(",")) names.add(sourceName(s));
        return String.join(", ", names);
    }

    /** Short app name for a source id, without Android (PluginRegistry needs a Context). */
    static String sourceName(String source) {
        switch (source) {
            case "omi.transcripts": return "GVoice";
            case "whatsapp": return "WhatsApp";
            case "signal": return "Signal";
            case "telegram": return "Telegram";
            case "matrix": return "Matrix";
            case "composio.gmail": return "Gmail";
            case "composio.googlecalendar": return "Calendar";
            case "composio.googledrive": return "Drive";
            case "composio.slack": return "Slack";
            case "composio.todoist": return "Todoist";
            case "composio.ticktick": return "TickTick";
            default: return source;
        }
    }

    private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}\\p{N} ]+"), SPACES = Pattern.compile("\\s+");

    /** "  Ána  SOUZA 🙂" → "ana souza". */
    static String nameKey(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = NOT_LETTER.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** Folds {@code drop} into {@code keep} (you always stay you). */
    public static void merge(Db db, long keep, long drop) throws Exception {
        if (keep == drop) return;
        Person k = get(db, keep), d = get(db, drop);
        if (k == null || d == null) return;
        if (d.me && !k.me) { long t = keep; keep = drop; drop = t; }
        db.exec("UPDATE identities SET person_id = ? WHERE person_id = ?", keep, drop);
        db.exec("UPDATE entities SET canonical_key = 'person:' || ? WHERE canonical_key = 'person:' || ?"
                + " AND NOT EXISTS (SELECT 1 FROM entities e WHERE e.canonical_key = 'person:' || ?)", keep, drop, keep);
        db.exec("DELETE FROM not_same_person WHERE a = ? OR b = ?", drop, drop);
        db.exec("DELETE FROM people WHERE id = ?", drop);
    }

    /** Remembers that these two are different people, so they're never suggested again. */
    public static void keepApart(Db db, long a, long b) throws Exception {
        db.exec("INSERT OR IGNORE INTO not_same_person(a, b) VALUES(?, ?)", Math.min(a, b), Math.max(a, b));
    }

    /** Moves one identity out into its own person (undoing a wrong merge). Returns the new person. */
    public static long separate(Db db, long identity, long now) throws Exception {
        List<Object[]> rows = db.query("SELECT person_id, COALESCE(display_name, handle) FROM identities WHERE id = ?", identity);
        if (rows.isEmpty()) throw new IllegalArgumentException("No such identity");
        long from = ((Number) rows.get(0)[0]).longValue();
        if (((Number) db.query("SELECT COUNT(*) FROM identities WHERE person_id = ?", from).get(0)[0]).intValue() < 2)
            throw new IllegalArgumentException("That's this person's only identity");
        long person = db.insert("INSERT INTO people(display_name, created_at) VALUES(?, ?)", rows.get(0)[1], now);
        db.exec("UPDATE identities SET person_id = ? WHERE id = ?", person, identity);
        keepApart(db, from, person);
        return person;
    }

    public static void rename(Db db, long person, String name) throws Exception {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("A name is needed");
        db.exec("UPDATE people SET display_name = ? WHERE id = ?", clean, person);
    }

    /** Conversations this person writes in, busiest first: {conversation id, title, source, count, last ts}. */
    public static List<Object[]> conversations(Db db, long person, int limit) throws Exception {
        return db.query("SELECT conversations.id, conversations.title, conversations.source, COUNT(*), MAX(items.ts)"
                + " FROM items JOIN identities ON identities.id = items.author_identity_id"
                + " JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE identities.person_id = ? GROUP BY conversations.id ORDER BY COUNT(*) DESC LIMIT ?", person, limit);
    }

    /** Item ids of this person's latest items, newest first. */
    public static List<Long> recentItems(Db db, long person, int limit) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (Object[] r : db.query("SELECT items.id FROM items JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE identities.person_id = ? ORDER BY items.ts DESC LIMIT ?", person, limit))
            ids.add(((Number) r[0]).longValue());
        return ids;
    }
}
