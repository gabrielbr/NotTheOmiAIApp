package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What's worth remembering. Marketing, newsletters and automated mail stay stored but are left
 * out of the feed, Ask, the portrait, people and the vault ({@link #visible()}). Judged on the
 * phone by rules (Gmail's own categories, bulk-mail headers, automated senders, Slack bots), and
 * by Claude when AI enrichment is on. Your own Hide and Keep always win.
 * Plain Java so host tests run it against real SQLite.
 */
public final class Relevance {
    /** items.noise values. */
    public static final int KEEP = 0, RULE = 1, CLAUDE = 2, HIDDEN_BY_YOU = 3, KEPT_BY_YOU = -1;
    static final String ENABLED = "noise.enabled", SENDERS = "noise.senders", BACKFILL = "noise.backfill";
    /** Off: rule and Claude verdicts are ignored (only your own hides apply). */
    static volatile boolean enabled = true;

    private static final Pattern AUTOMATED = Pattern.compile(
            "^(?:no[-_.]?reply|do[-_.]?not[-_.]?reply|notifications?|newsletters?|marketing|news|mailer-daemon|"
                    + "bounces?|promo(?:tions?)?)(?:[-_.+][^@]*)?@", Pattern.CASE_INSENSITIVE);

    private Relevance() {}

    /** SQL condition for items that count as memory. */
    public static String visible() { return enabled ? "items.noise <= 0" : "items.noise <> 3"; }

    /** Loads the switch from the store; call once when the store opens. */
    static void load(Db db) throws Exception { enabled = !"off".equals(Meta.get(db, ENABLED)); }

    static void setEnabled(Db db, boolean on) throws Exception {
        Meta.set(db, ENABLED, on ? null : "off");
        enabled = on;
    }

    /** A rule verdict: {noise, reason}; reason null when kept. */
    static final class Verdict {
        final int noise; final String reason;
        Verdict(int noise, String reason) { this.noise = noise; this.reason = reason; }
    }

    /**
     * The rule verdict for one item. {@code corresponds}: you've written in a thread with this
     * sender (or in this item's thread), so they're someone you talk to.
     */
    static Verdict rule(String source, String kind, boolean fromMe, String rawJson, String authorHandle, String text,
                        boolean corresponds, List<String> myNames) {
        if (fromMe || corresponds) return new Verdict(KEEP, null);
        if ("composio.gmail".equals(source)) {
            Object raw = rawJson == null ? null : Json.parse(rawJson);
            List<Object> labels = Json.list(Json.at(raw, "labelIds"));
            boolean bulk = Boolean.TRUE.equals(Json.at(raw, "bulk"));
            if (labels.contains("SPAM")) return new Verdict(RULE, "Gmail: Spam");
            if (labels.contains("CATEGORY_PROMOTIONS")) return new Verdict(RULE, "Gmail: Promotions");
            if (labels.contains("CATEGORY_SOCIAL")) return new Verdict(RULE, "Gmail: Social");
            if (labels.contains("CATEGORY_FORUMS")) return new Verdict(RULE, "Gmail: Forums");
            if (labels.contains("CATEGORY_UPDATES") && bulk) return new Verdict(RULE, "Gmail: Updates (mailing list)");
            if (bulk) return new Verdict(RULE, "Mailing list");
            String address = authorHandle != null && authorHandle.startsWith("email:") ? authorHandle.substring(6) : null;
            if (address != null && AUTOMATED.matcher(address).find()) return new Verdict(RULE, "Automated sender");
            return new Verdict(KEEP, null);
        }
        if (authorHandle != null && authorHandle.startsWith("slack-bot:")) {
            String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
            for (String name : myNames)
                if (!name.isEmpty() && lower.contains(name.toLowerCase(Locale.ROOT))) return new Verdict(KEEP, null);
            return new Verdict(RULE, "Slack bot");
        }
        return new Verdict(KEEP, null); // chats, recordings, calendar, files: always memory
    }

    /** Judges these items by the rules; never touches Claude's verdicts or yours. */
    static void judge(Db db, Collection<Long> ids) throws Exception {
        if (ids.isEmpty()) return;
        List<String> names = FoundTasks.names(db);
        Set<String> hiddenSenders = hiddenSenders(db);
        for (Long id : ids) {
            List<Object[]> rows = db.query("SELECT items.source, items.kind, items.from_me, items.raw_json, identities.handle,"
                    + " items.text, items.noise, items.conversation_id, items.author_identity_id FROM items"
                    + " LEFT JOIN identities ON identities.id = items.author_identity_id WHERE items.id = ?", id);
            if (rows.isEmpty()) continue;
            Object[] r = rows.get(0);
            int noise = ((Number) r[6]).intValue();
            boolean fromMe = ((Number) r[2]).intValue() != 0;
            Long conversation = r[7] == null ? null : ((Number) r[7]).longValue();
            if (fromMe && conversation != null) {
                // You wrote in this thread: everything in it is memory again (unless you hid it).
                db.exec("UPDATE items SET noise = 0, noise_reason = NULL WHERE conversation_id = ? AND noise IN (1, 2)",
                        conversation);
            }
            if (noise != KEEP && noise != RULE) continue;
            String handle = (String) r[4];
            if (handle != null && hiddenSenders.contains(handle)) {
                set(db, id, HIDDEN_BY_YOU, "Hidden by you (sender)");
                continue;
            }
            Verdict v = rule((String) r[0], (String) r[1], fromMe, (String) r[3], handle, (String) r[5],
                    corresponds(db, conversation, r[8] == null ? null : ((Number) r[8]).longValue()), names);
            if (v.noise != noise) set(db, id, v.noise, v.reason);
        }
    }

    /** You wrote in this thread, or in any thread this author also wrote in. */
    private static boolean corresponds(Db db, Long conversation, Long author) throws Exception {
        if (conversation != null && !db.query("SELECT 1 FROM items WHERE conversation_id = ? AND from_me = 1 LIMIT 1",
                conversation).isEmpty()) return true;
        return author != null && !db.query("SELECT 1 FROM items a JOIN items mine ON mine.conversation_id = a.conversation_id"
                + " WHERE a.author_identity_id = ? AND a.conversation_id IS NOT NULL AND mine.from_me = 1 LIMIT 1", author).isEmpty();
    }

    /**
     * Judges items stored before this existed, a batch at a time (resumes where it stopped).
     * Returns how many it judged; 0 when done.
     */
    static int backfill(Db db, int batch) throws Exception {
        String done = Meta.get(db, BACKFILL);
        if ("done".equals(done)) return 0;
        long after = done == null ? 0 : Long.parseLong(done);
        List<Long> ids = new ArrayList<>();
        for (Object[] r : db.query("SELECT id FROM items WHERE id > ? ORDER BY id LIMIT ?", after, batch))
            ids.add(((Number) r[0]).longValue());
        if (ids.isEmpty()) { Meta.set(db, BACKFILL, "done"); return 0; }
        db.transaction(() -> { judge(db, ids); return null; });
        Meta.set(db, BACKFILL, String.valueOf(ids.get(ids.size() - 1)));
        return ids.size();
    }

    /** Claude's verdict, for items nobody has judged as noise or kept. */
    static void claude(Db db, Collection<Long> ids) throws Exception {
        for (Long id : ids)
            db.exec("UPDATE items SET noise = 2, noise_reason = 'Claude: not worth remembering' WHERE id = ? AND noise = 0", id);
    }

    /** You hid this item. */
    static void hide(Db db, long id) throws Exception { set(db, id, HIDDEN_BY_YOU, "Hidden by you"); }

    /** You want this item remembered; rules and Claude won't hide it again. */
    static void keep(Db db, long id) throws Exception { set(db, id, KEPT_BY_YOU, null); }

    /** Hides everything from this sender (identity handle, e.g. "email:news@shop.com"), now and later. */
    static void hideSender(Db db, String handle) throws Exception {
        Set<String> senders = hiddenSenders(db);
        senders.add(handle);
        Meta.set(db, SENDERS, String.join("\n", senders));
        db.exec("UPDATE items SET noise = 3, noise_reason = 'Hidden by you (sender)' WHERE noise <> 3 AND author_identity_id IN"
                + " (SELECT id FROM identities WHERE handle = ?)", handle);
    }

    /** Remembers everything from this sender again. */
    static void keepSender(Db db, String handle) throws Exception {
        Set<String> senders = hiddenSenders(db);
        if (senders.remove(handle)) Meta.set(db, SENDERS, senders.isEmpty() ? null : String.join("\n", senders));
        db.exec("UPDATE items SET noise = -1, noise_reason = NULL WHERE noise > 0 AND author_identity_id IN"
                + " (SELECT id FROM identities WHERE handle = ?)", handle);
    }

    static Set<String> hiddenSenders(Db db) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        String value = Meta.get(db, SENDERS);
        if (value != null) for (String s : value.split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    /** Items hidden from memory (by rules, Claude or you), counted for the You screen. */
    static int hiddenCount(Db db) throws Exception {
        String condition = enabled ? "noise > 0" : "noise = 3";
        return ((Number) db.query("SELECT COUNT(*) FROM items WHERE " + condition).get(0)[0]).intValue();
    }

    private static void set(Db db, long id, int noise, String reason) throws Exception {
        db.exec("UPDATE items SET noise = ?, noise_reason = ? WHERE id = ?", noise, reason, id);
    }
}
