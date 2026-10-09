package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.RawItem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI enrichment, model-independent: new items, in the order they were stored, go into batches of
 * about 12k characters (a header marks each conversation); a
 * model returns entities (people, organizations, projects, places, topics, events), relations and
 * facts in a fixed JSON schema, each citing item ids from its batch; this class checks the ids and
 * writes them to the graph (entities, entity_aliases, mentions, relations, facts). A person entity
 * links to the matching GMind person when exactly one has that name. Plain Java (host-tested).
 */
public final class Extraction {
    static final String CURSOR = "ai.ingested_at";
    static final int BATCH_CHARS = 12_000, ITEM_CHARS = 700;
    static final long FIRST_RUN_MS = 30L * 24 * 60 * 60 * 1000;
    static final List<String> TYPES = Arrays.asList("person", "org", "project", "place", "topic", "event");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private Extraction() {}

    /** What reads a batch and answers in {@link #SCHEMA} JSON (Claude in the app, a fake in tests). */
    public interface Extractor { String extract(String batch) throws Exception; }

    /** Items for one request: their ids (the only ones a reply may cite) and the text sent. */
    public static final class Batch {
        public final Set<Long> ids = new HashSet<>();
        public final StringBuilder text = new StringBuilder();
        long lastAt, lastId;
    }

    /** The structured output schema: strict, every field required, no extra properties. */
    static final Map<String, Object> SCHEMA = obj(
            "entities", arr(obj(
                    "key", str("Short id for this entity within the reply, e.g. ana_souza; use \"me\" for the user"),
                    "type", Json.object("type", "string", "enum", new ArrayList<Object>(TYPES)),
                    "name", str("Name as written, the fullest form seen"),
                    "aliases", arr(str("Other names for it in these items")),
                    "evidence", ids())),
            "relations", arr(obj(
                    "from", str("Entity key"),
                    "type", str("snake_case relation, e.g. works_at, family_of, part_of, met_at, lives_in"),
                    "to", str("Entity key"),
                    "evidence", ids())),
            "facts", arr(obj(
                    "about", str("Entity key"),
                    "key", str("snake_case attribute, e.g. role, birthday, phone_city, preference"),
                    "value", str("Short value, in the items' language"),
                    "evidence", ids())));

    public static final String SYSTEM = "You extract a personal knowledge graph from the user's own messages, emails, "
            + "calendar events, files, tasks and recording transcripts. Each item starts with its id like [#123]; items "
            + "marked \"me\" were written by the user.\n\n"
            + "Return the people, organizations, projects, places, topics and events these items clearly mention, how "
            + "they relate, and lasting facts about them (role, where someone works or lives, family ties, birthdays, "
            + "preferences, decisions). Use only what the items state; don't guess. Skip greetings, small talk, "
            + "newsletters and one-off logistics. Refer to the user with the key \"me\" and never create another entity "
            + "for them. Give every entity, relation and fact the ids of the items that support it, from this batch only. "
            + "Write names and values in the items' language. Empty lists are fine when there's nothing worth keeping.";

    // ---- batching -------------------------------------------------------------------------------

    /** Items stored or changed since the last run (the first run: the last 30 days), grouped by conversation. */
    public static List<Batch> batches(Db db, long now, ZoneId zone, int maxBatches) throws Exception {
        long[] cursor = cursor(db);
        long floor = cursor[0] == 0 ? now - FIRST_RUN_MS : 0;
        List<Object[]> rows = db.query("SELECT items.id, items.ts, items.source, items.kind, items.text, items.from_me,"
                + " conversations.title, identities.display_name, items.ingested_at, COALESCE(items.conversation_id, -items.id)"
                + " FROM items LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE (items.ingested_at > ? OR (items.ingested_at = ? AND items.id > ?)) AND items.ts >= ?"
                + " ORDER BY items.ingested_at, items.id", cursor[0], cursor[0], cursor[1], floor);
        List<Batch> batches = new ArrayList<>();
        Batch current = null;
        Object conversation = null;
        for (Object[] r : rows) {
            String line = line(r, zone);
            String title = (String) r[6];
            String header = "\n## " + People.sourceName((String) r[2]) + (title == null ? "" : " · " + title) + "\n";
            boolean newConversation = !r[9].equals(conversation);
            if (current == null || current.text.length() + (newConversation ? header.length() : 0) + line.length() > BATCH_CHARS) {
                if (batches.size() >= maxBatches) break;
                current = new Batch();
                batches.add(current);
                newConversation = true;
            }
            if (newConversation) current.text.append(header);
            conversation = r[9];
            current.text.append(line);
            current.ids.add(((Number) r[0]).longValue());
            current.lastAt = ((Number) r[8]).longValue();
            current.lastId = ((Number) r[0]).longValue();
        }
        return batches;
    }

    private static String line(Object[] r, ZoneId zone) {
        String text = ((String) r[4]).replace('\n', ' ').trim();
        if (text.length() > ITEM_CHARS) text = text.substring(0, ITEM_CHARS) + "…";
        boolean me = ((Number) r[5]).intValue() != 0;
        String who = me ? "me" : r[7] != null ? (String) r[7] : RawItem.TRANSCRIPT.equals(r[3]) ? "recording" : null;
        return "[#" + r[0] + "] " + WHEN.format(Instant.ofEpochMilli((Long) r[1]).atZone(zone)) + " · " + r[3]
                + (who == null ? "" : " · " + who) + ": " + text + "\n";
    }

    /** Moves the cursor past a batch that was processed (or that the model refused). Batches come in order. */
    public static void done(Db db, Batch batch) throws Exception {
        Meta.set(db, CURSOR, batch.lastAt + ":" + batch.lastId);
    }

    /** {ingested_at, item id} of the last item processed; {0, 0} before the first run. */
    static long[] cursor(Db db) throws Exception {
        String v = Meta.get(db, CURSOR);
        if (v == null) return new long[]{0, 0};
        try {
            String[] parts = v.split(":");
            return new long[]{Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : Long.MAX_VALUE};
        } catch (NumberFormatException corrupt) {
            return new long[]{0, 0};
        }
    }

    /** Result of one enrichment run. */
    public static final class Run { public int batches, refused; public final Counts counts = new Counts(); }

    /**
     * Sends up to {@code maxBatches} batches, applying each reply and moving the cursor in one
     * transaction, so a failure part-way keeps what was done. A null reply (the model declined) skips
     * the batch. Exceptions from the extractor (network, key) stop the run and propagate.
     */
    public static Run run(Db db, Extractor extractor, long now, ZoneId zone, int maxBatches,
                          java.util.function.BooleanSupplier cancelled) throws Exception {
        Run run = new Run();
        for (Batch batch : batches(db, now, zone, maxBatches)) {
            if (cancelled.getAsBoolean()) break;
            String reply = extractor.extract(batch.text.toString());
            db.transaction(() -> {
                if (reply == null) run.refused++;
                else {
                    Counts c;
                    try { c = apply(db, batch, reply, now); }
                    catch (IllegalArgumentException malformed) { c = new Counts(); c.dropped++; }
                    run.counts.entities += c.entities;
                    run.counts.relations += c.relations;
                    run.counts.facts += c.facts;
                    run.counts.dropped += c.dropped;
                }
                done(db, batch);
                return null;
            });
            run.batches++;
        }
        return run;
    }

    // ---- applying a reply -----------------------------------------------------------------------

    public static final class Counts { public int entities, relations, facts, dropped; }

    /** Writes one reply. Cited ids outside the batch are dropped; so is anything left without evidence. */
    public static Counts apply(Db db, Batch batch, String json, long now) throws Exception {
        Object reply = Json.parse(json);
        Counts counts = new Counts();
        long me = ((Number) db.query("SELECT id FROM people WHERE is_me = 1 ORDER BY id LIMIT 1").get(0)[0]).longValue();
        Map<String, Long> keys = new HashMap<>();
        keys.put("me", entity(db, "person", meName(db, me), "person:" + me));
        for (Object e : Json.list(Json.at(reply, "entities"))) {
            String key = Json.str(Json.at(e, "key")), type = Json.str(Json.at(e, "type")), name = Json.str(Json.at(e, "name"));
            List<Long> evidence = evidence(e, batch);
            if (key == null || name == null || !TYPES.contains(type) || evidence.isEmpty()) { counts.dropped++; continue; }
            if ("me".equals(key)) continue;
            String canonical = "person".equals(type) ? personKey(db, name, Json.list(Json.at(e, "aliases"))) : null;
            if (canonical == null) canonical = type + ":" + People.nameKey(name);
            if (canonical.endsWith(":")) { counts.dropped++; continue; }
            long id = entity(db, type, name, canonical);
            keys.put(key, id);
            for (Object alias : Json.list(Json.at(e, "aliases")))
                if (Json.str(alias) != null && !Json.str(alias).equals(name))
                    db.exec("INSERT OR IGNORE INTO entity_aliases(entity_id, alias) VALUES(?, ?)", id, Json.str(alias));
            for (long item : evidence) db.exec("INSERT OR IGNORE INTO mentions(item_id, entity_id, confidence) VALUES(?, ?, 1.0)", item, id);
            counts.entities++;
        }
        for (Object r : Json.list(Json.at(reply, "relations"))) {
            Long from = keys.get(Json.str(Json.at(r, "from"))), to = keys.get(Json.str(Json.at(r, "to")));
            String type = snake(Json.str(Json.at(r, "type")));
            List<Long> evidence = evidence(r, batch);
            if (from == null || to == null || from.equals(to) || type == null || evidence.isEmpty()) { counts.dropped++; continue; }
            long ts = latest(db, evidence);
            List<Object[]> existing = db.query("SELECT id FROM relations WHERE src_entity = ? AND dst_entity = ? AND type = ?", from, to, type);
            if (existing.isEmpty())
                db.insert("INSERT INTO relations(src_entity, dst_entity, type, evidence_item_id, confidence, first_seen, last_seen)"
                        + " VALUES(?, ?, ?, ?, 1.0, ?, ?)", from, to, type, evidence.get(0), ts, ts);
            else db.exec("UPDATE relations SET evidence_item_id = ?, last_seen = MAX(COALESCE(last_seen, 0), ?) WHERE id = ?",
                    evidence.get(0), ts, existing.get(0)[0]);
            counts.relations++;
        }
        for (Object f : Json.list(Json.at(reply, "facts"))) {
            Long about = keys.get(Json.str(Json.at(f, "about")));
            String key = snake(Json.str(Json.at(f, "key"))), value = Json.str(Json.at(f, "value"));
            List<Long> evidence = evidence(f, batch);
            if (about == null || key == null || value == null || evidence.isEmpty()) { counts.dropped++; continue; }
            if (value.length() > 300) value = value.substring(0, 300);
            List<Object[]> existing = db.query("SELECT id FROM facts WHERE entity_id = ? AND key = ?", about, key);
            if (existing.isEmpty())
                db.insert("INSERT INTO facts(entity_id, key, value, evidence_item_id, confidence, updated_at) VALUES(?, ?, ?, ?, 1.0, ?)",
                        about, key, value, evidence.get(0), now);
            else db.exec("UPDATE facts SET value = ?, evidence_item_id = ?, updated_at = ? WHERE id = ?", value, evidence.get(0), now, existing.get(0)[0]);
            counts.facts++;
        }
        return counts;
    }

    /** "person:<id>" when exactly one GMind person has this name (or an alias); else null. */
    private static String personKey(Db db, String name, List<Object> aliases) throws Exception {
        Set<Long> matches = new HashSet<>();
        List<String> names = new ArrayList<>();
        names.add(name);
        for (Object a : aliases) if (Json.str(a) != null) names.add(Json.str(a));
        Set<String> wanted = new HashSet<>();
        for (String n : names) if (People.nameKey(n).length() >= 3) wanted.add(People.nameKey(n));
        for (Object[] r : db.query("SELECT person_id, display_name FROM identities WHERE display_name IS NOT NULL"
                + " UNION SELECT id, display_name FROM people WHERE is_me = 0"))
            if (wanted.contains(People.nameKey((String) r[1]))) matches.add(((Number) r[0]).longValue());
        return matches.size() == 1 ? "person:" + matches.iterator().next() : null;
    }

    private static String meName(Db db, long me) throws Exception {
        return (String) db.query("SELECT display_name FROM people WHERE id = ?", me).get(0)[0];
    }

    private static long entity(Db db, String type, String name, String canonical) throws Exception {
        List<Object[]> found = db.query("SELECT id FROM entities WHERE canonical_key = ?", canonical);
        if (!found.isEmpty()) return ((Number) found.get(0)[0]).longValue();
        return db.insert("INSERT INTO entities(type, name, canonical_key) VALUES(?, ?, ?)", type, name, canonical);
    }

    private static List<Long> evidence(Object o, Batch batch) {
        List<Long> ids = new ArrayList<>();
        for (Object v : Json.list(Json.at(o, "evidence"))) {
            Long id = Json.num(v);
            if (id != null && batch.ids.contains(id) && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static long latest(Db db, List<Long> items) throws Exception {
        long ts = 0;
        for (long id : items) {
            List<Object[]> r = db.query("SELECT ts FROM items WHERE id = ?", id);
            if (!r.isEmpty()) ts = Math.max(ts, ((Number) r.get(0)[0]).longValue());
        }
        return ts;
    }

    static String snake(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        return t.isEmpty() || t.length() > 60 ? null : t;
    }

    // ---- schema helpers -------------------------------------------------------------------------

    private static Map<String, Object> obj(Object... keysAndSchemas) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (int i = 0; i + 1 < keysAndSchemas.length; i += 2) {
            props.put((String) keysAndSchemas[i], keysAndSchemas[i + 1]);
            required.add(keysAndSchemas[i]);
        }
        return Json.object("type", "object", "properties", props, "required", required, "additionalProperties", Boolean.FALSE);
    }

    private static Map<String, Object> arr(Object items) { return Json.object("type", "array", "items", items); }

    private static Map<String, Object> str(String description) { return Json.object("type", "string", "description", description); }

    private static Map<String, Object> ids() {
        return Json.object("type", "array", "items", Json.object("type", "integer"), "description", "Ids of the supporting items, like 123 for [#123]");
    }
}
