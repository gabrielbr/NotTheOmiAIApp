package br.gabriel.sentient;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.BatteryManager;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The AI work, done while the phone charges (usually overnight), in rounds of a few minutes that
 * pick up where the last one stopped: Claude's enrichment (when on), then the review of what came
 * in from every source. The reviewer follows Ask's choice: Claude, or Qwen on the phone (Gemini
 * Nano can't run in the background; Android only lets the app on screen use it).
 */
public final class NightJobService extends JobService {
    static final int JOB = 52003;
    /** A round stays under Android's job time limit; the next round starts a minute later. */
    static final long ROUND_MS = 8L * 60 * 1000, NEXT_ROUND_MS = 60_000L, NEXT_NIGHT_MS = 6L * 60 * 60 * 1000;
    static final int CLAUDE_BATCHES = 40, QWEN_BATCHES = 400;
    private volatile boolean cancelled;

    /** Schedules the next round (or night); harmless to repeat. */
    static void schedule(Context context, long delayMs) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, NightJobService.class))
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setMinimumLatency(delayMs)
                .setPersisted(true)
                .build());
    }

    /** Makes sure a night is scheduled (e.g. after a sync brought new items). */
    static void ensure(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null && scheduler.getPendingJob(JOB) == null) schedule(context, 0);
    }

    static boolean charging(Context context) {
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        return battery == null || battery.isCharging();
    }

    @Override public boolean onStartJob(JobParameters params) {
        Context context = getApplicationContext();
        long deadline = System.currentTimeMillis() + ROUND_MS;
        java.util.function.BooleanSupplier stop = () -> cancelled || System.currentTimeMillis() > deadline || !charging(context);
        new Thread(() -> {
            boolean more = false;
            try {
                more = round(context, stop);
            } catch (Exception failure) {
                status(context, "The overnight review stopped (" + failure.getClass().getSimpleName() + "); it resumes next time.");
            } finally {
                jobFinished(params, false);
                schedule(context, more && !cancelled ? NEXT_ROUND_MS : NEXT_NIGHT_MS);
            }
        }, "gmind-night").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelled = true;
        return false; // the round reschedules itself
    }

    /** One round. True when there's more to do tonight. */
    static boolean round(Context context, java.util.function.BooleanSupplier stop) throws Exception {
        Db db = KnowledgeStore.get(context);
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        while (!stop.getAsBoolean() && Relevance.backfill(db, 500) > 0) { /* the free rules, for items stored before them */ }
        boolean local = AskBackends.local(context);
        String key = AskSettings.apiKey(context);
        Review.Reviewer reviewer;
        if (!local && key != null) {
            if (AskSettings.enrich(context) && !stop.getAsBoolean()) enrich(context, db, key, now, zone, stop);
            reviewer = new ClaudeReviewer(key, null);
        } else if (local && LocalModel.ready(context)) {
            reviewer = new QwenReviewer(new LocalBackend(context, db), stop);
        } else {
            status(context, local ? "Download the on-phone model in Ask settings to review what comes in overnight."
                    : "Add your Claude key in Ask settings to review what comes in overnight.");
            return false;
        }
        Review.Result r;
        try {
            r = Review.run(db, reviewer, now, zone, "Claude".equals(reviewer.name()) ? CLAUDE_BATCHES : QWEN_BATCHES, stop);
        } catch (ClaudeBackend.AskException refused) {
            status(context, refused.getMessage());
            return false;
        }
        if (r.hidden > 0) refresh(db, now, zone);
        int left = Review.pending(db, now);
        status(context, reviewer.name() + " reviewed " + r.reviewed + " item" + (r.reviewed == 1 ? "" : "s") + " and hid " + r.hidden
                + (left > 0 ? " · " + left + " still to review" : " · all caught up") + " · "
                + android.text.format.DateUtils.formatDateTime(context, now,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME));
        return left > 0 && r.batches > 0;
    }

    /** Claude's enrichment (people, projects, facts): moved here from the daily sync. */
    private static void enrich(Context context, Db db, String key, long now, ZoneId zone, java.util.function.BooleanSupplier stop) {
        try {
            Extraction.Run run = Extraction.run(db, new ClaudeExtractor(key, null), now, zone, SyncJobService.AI_BATCHES, stop);
            Portrait.write(db, now, zone);
            AskSettings.setEnrichStatus(context, "Last run: " + run.batches + " batches · " + run.counts.entities
                    + " things, " + run.counts.relations + " links, " + run.counts.facts + " facts"
                    + (run.refused > 0 ? " · " + run.refused + " declined" : "") + " · "
                    + android.text.format.DateUtils.formatDateTime(context, now,
                            android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME));
        } catch (ClaudeBackend.AskException refused) {
            AskSettings.setEnrichStatus(context, refused.getMessage());
        } catch (Exception failure) {
            AskSettings.setEnrichStatus(context, "Enrichment failed (" + failure.getClass().getSimpleName() + "); it resumes next time the phone charges.");
        }
    }

    /** The portrait and the last week's days, without what was just hidden. */
    private static void refresh(Db db, long now, ZoneId zone) throws Exception {
        LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (int i = 0; i < 7; i++) {
            final LocalDate day = today.minusDays(i);
            db.transaction(() -> Digest.write(db, day, zone, now));
        }
        Portrait.write(db, now, zone);
    }

    private static void status(Context context, String line) {
        try { Meta.set(KnowledgeStore.get(context), Review.STATUS, line); } catch (Exception ignored) { }
    }
}
