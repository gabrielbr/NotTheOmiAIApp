package br.gabriel.sentient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * What runs after every sync, all on the phone and without AI: people with the same email address
 * merge, to-dos are found, the digests of days that got new items are rewritten, and the portrait is
 * rebuilt. Each step commits on its own. Plain Java (host-tested).
 */
public final class Enrichment {
    static final String LAST_RUN = "enrichment.at";
    static final int MAX_DAYS = 30;

    private Enrichment() {}

    public static final class Result {
        public final int merged, tasks, days;
        Result(int merged, int tasks, int days) { this.merged = merged; this.tasks = tasks; this.days = days; }
    }

    public static Result run(Db db, long now, ZoneId zone) throws Exception {
        long last = Meta.getLong(db, LAST_RUN, 0);
        // Once, after the update that added it: judge what's worth remembering in what's already stored.
        int judged = 0, step;
        for (int batches = 0; batches < 200 && (step = Relevance.backfill(db, 500)) > 0; batches++) judged += step;
        int merged = db.transaction(() -> People.mergeSameAddresses(db));
        int tasks = db.transaction(() -> FoundTasks.scan(db, now, zone));
        db.transaction(() -> TodoistSync.reconcile(db));
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        List<Object[]> oldest = db.query("SELECT MIN(ts) FROM items WHERE ingested_at > ?", last);
        LocalDate from = oldest.get(0)[0] == null ? today
                : Instant.ofEpochMilli(((Number) oldest.get(0)[0]).longValue()).atZone(zone).toLocalDate();
        if (judged > 0 || from.isBefore(today.minusDays(MAX_DAYS))) from = today.minusDays(MAX_DAYS); // redo recent days without noise
        if (from.isAfter(today)) from = today;
        int days = 0;
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            final LocalDate d = day;
            db.transaction(() -> Digest.write(db, d, zone, now));
            days++;
        }
        db.transaction(() -> {
            Portrait.write(db, now, zone);
            Meta.set(db, LAST_RUN, Long.toString(now));
            return null;
        });
        return new Result(merged, tasks, days);
    }
}
