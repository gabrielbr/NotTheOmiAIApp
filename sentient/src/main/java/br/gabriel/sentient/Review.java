package br.gabriel.sentient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The overnight review: an AI (Claude, or Qwen on the phone, following Ask's choice) reads what
 * came in from every source and hides what isn't worth remembering. It runs while the phone
 * charges ({@link NightJobService}), after the free rules ({@link Relevance}) already ran.
 * Never reviewed: your own items, anything in a conversation you wrote in, and anything someone
 * (you, a rule or Claude) already judged. Plain Java so host tests run it against real SQLite.
 */
public final class Review {
    static final String CURSOR = "review.cursor", STATUS = "review.status";
    /** Only items from the last 30 days are reviewed; older ones are left as they are. */
    static final long WINDOW_MS = 30L * 24 * 3600 * 1000;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final Pattern ID = Pattern.compile("\\d+");

    /** What the reviewer is told, for Claude and Qwen alike. */
    static final String SYSTEM = "You keep a personal memory: an assistant that remembers the user's life from their "
            + "messages, emails, chats, calendar, files, tasks and recording transcripts. Each item starts with its id "
            + "like [#123].\n\nList the items that are NOT worth remembering: marketing and promotions, newsletters, "
            + "automated notifications and alerts with nothing personal, mass mail, spam, chain messages and forwards, "
            + "bot output, and recordings of TV, radio or background noise. Keep anything about the user's people, "
            + "plans, work, money, health, home, travel, purchases, appointments and decisions, and anything someone "
            + "wrote to the user personally. When unsure, keep it.";

    private Review() {}

    /** One model behind the review. */
    interface Reviewer {
        /** Shown to the user, e.g. "Claude" or "Qwen". */
        String name();
        /** Characters of item text per request (Qwen's context is small). */
        int batchChars();
        int itemChars();
        /** The reply naming the items not worth remembering (any text with their ids), or null if it declined. */
        String review(String items) throws Exception;
    }

    static final class Batch {
        final Set<Long> ids = new LinkedHashSet<>();
        final StringBuilder text = new StringBuilder();
        long lastId;
    }

    static final class Result { int batches, reviewed, hidden, declined; }

    /** Items waiting for review, oldest first, cut into batches. */
    static List<Batch> batches(Db db, long now, ZoneId zone, int maxBatches, int batchChars, int itemChars) throws Exception {
        long cursor = Meta.getLong(db, CURSOR, 0);
        long floor = now - WINDOW_MS;
        List<Object[]> rows = db.query("SELECT items.id, items.ts, items.source, items.kind, items.text, conversations.title,"
                + " identities.display_name FROM items LEFT JOIN conversations ON conversations.id = items.conversation_id"
                + " LEFT JOIN identities ON identities.id = items.author_identity_id"
                + " WHERE items.id > ? AND items.ts >= ? AND items.from_me = 0 AND items.noise = 0"
                + " AND NOT EXISTS (SELECT 1 FROM items mine WHERE items.conversation_id IS NOT NULL"
                + "   AND mine.conversation_id = items.conversation_id AND mine.from_me = 1)"
                + " ORDER BY items.id LIMIT ?", cursor, floor, maxBatches * 60);
        List<Batch> batches = new ArrayList<>();
        Batch current = null;
        for (Object[] r : rows) {
            String line = line(r, zone, itemChars);
            if (current == null || current.text.length() + line.length() > batchChars) {
                if (batches.size() >= maxBatches) break;
                current = new Batch();
                batches.add(current);
            }
            current.text.append(line);
            long id = ((Number) r[0]).longValue();
            current.ids.add(id);
            current.lastId = id;
        }
        return batches;
    }

    /** How many items still wait for review (for the status line). */
    static int pending(Db db, long now) throws Exception {
        long cursor = Meta.getLong(db, CURSOR, 0);
        long floor = now - WINDOW_MS;
        return ((Number) db.query("SELECT COUNT(*) FROM items WHERE items.id > ? AND items.ts >= ? AND items.from_me = 0"
                + " AND items.noise = 0 AND NOT EXISTS (SELECT 1 FROM items mine WHERE items.conversation_id IS NOT NULL"
                + "   AND mine.conversation_id = items.conversation_id AND mine.from_me = 1)", cursor, floor).get(0)[0]).intValue();
    }

    private static String line(Object[] r, ZoneId zone, int itemChars) {
        String text = ((String) r[4]).replace('\n', ' ').trim();
        if (text.length() > itemChars) text = text.substring(0, itemChars) + "…";
        String where = People.sourceName((String) r[2]) + (r[5] == null ? "" : " · " + r[5]);
        String who = r[6] == null ? "" : " · " + r[6];
        return "[#" + r[0] + "] " + WHEN.format(Instant.ofEpochMilli((Long) r[1]).atZone(zone)) + " · " + where + who
                + " · " + r[3] + ": " + text + "\n";
    }

    /** The ChatML prompt for Qwen: the instructions, the items, and a reply format a small model can keep. */
    static String qwenPrompt(String items) {
        return "<|im_start|>system\n" + SYSTEM + "\n\nAnswer with only the ids of the items not worth remembering, "
                + "separated by commas, like: 12, 15. If every item is worth remembering, answer: none<|im_end|>\n"
                + "<|im_start|>user\nItems:\n" + LocalPrompt.clean(items) + "<|im_end|>\n<|im_start|>assistant\n";
    }

    /** Ids in the reply that belong to the batch; "none" or anything else yields nothing. */
    static List<Long> ids(String reply, Set<Long> allowed) {
        List<Long> out = new ArrayList<>();
        if (reply == null) return out;
        Matcher m = ID.matcher(reply);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group());
                if (allowed.contains(id) && !out.contains(id)) out.add(id);
            } catch (NumberFormatException tooLong) { /* not an id */ }
        }
        return out;
    }

    /**
     * Reviews up to {@code maxBatches}, oldest first, until {@code stop} says so (time's up, or
     * the phone was unplugged). Each batch is applied and the cursor moved before the next.
     */
    static Result run(Db db, Reviewer reviewer, long now, ZoneId zone, int maxBatches, BooleanSupplier stop) throws Exception {
        Result result = new Result();
        String reason = reviewer.name() + ": not worth remembering";
        for (Batch batch : batches(db, now, zone, maxBatches, reviewer.batchChars(), reviewer.itemChars())) {
            if (stop.getAsBoolean()) break;
            String reply = reviewer.review(batch.text.toString());
            if (stop.getAsBoolean()) break; // the reply may be cut short; review this batch again next time
            final List<Long> hide = ids(reply, batch.ids);
            db.transaction(() -> {
                for (Long id : hide)
                    db.exec("UPDATE items SET noise = 2, noise_reason = ? WHERE id = ? AND noise = 0", reason, id);
                Meta.set(db, CURSOR, String.valueOf(batch.lastId));
                return null;
            });
            result.batches++;
            result.reviewed += batch.ids.size();
            result.hidden += hide.size();
            if (reply == null) result.declined++;
        }
        return result;
    }
}
