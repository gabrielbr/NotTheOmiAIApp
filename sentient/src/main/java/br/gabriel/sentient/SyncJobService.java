package br.gabriel.sentient;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.List;

/**
 * Daily sync of every enabled source, plus "Sync now". One run at a time; a stopped job
 * keeps everything already committed and resumes from the stored cursors.
 */
public final class SyncJobService extends JobService {
    static final int DAILY_JOB = 52001, NOW_JOB = 52002;
    private static final long DAY_MS = 24L * 60 * 60 * 1000, FLEX_MS = 6L * 60 * 60 * 1000;
    private static final Object LOCK = new Object();
    private static boolean running;
    public static volatile String state = "Not synced yet";
    public static volatile long revision;
    private volatile boolean cancelled;

    static void scheduleDaily(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(DAILY_JOB) != null) return;
        scheduler.schedule(new JobInfo.Builder(DAILY_JOB, new ComponentName(context, SyncJobService.class))
                .setPeriodic(DAY_MS, FLEX_MS)
                .setPersisted(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build());
    }

    static void syncNow(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) { setState("Sync unavailable on this phone"); return; }
        int result = scheduler.schedule(new JobInfo.Builder(NOW_JOB, new ComponentName(context, SyncJobService.class))
                .setOverrideDeadline(0)
                .build());
        setState(result == JobScheduler.RESULT_SUCCESS ? "Sync starting…" : "Sync could not be scheduled");
    }

    static boolean running() { synchronized (LOCK) { return running; } }

    private static void setState(String value) { state = value; revision++; }

    @Override public boolean onStartJob(JobParameters params) {
        synchronized (LOCK) {
            if (running) return false; // the run in progress covers this request
            running = true;
        }
        setState("Syncing…");
        new Thread(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                List<SyncRunner.Outcome> outcomes = SyncRunner.run(db, PluginRegistry.plugins(this),
                        () -> cancelled, System::currentTimeMillis);
                int added = 0, updated = 0, failed = 0;
                for (SyncRunner.Outcome outcome : outcomes) {
                    added += outcome.added;
                    updated += outcome.updated;
                    if (outcome.failed) failed++;
                }
                setState(cancelled ? "Sync paused by Android · will resume"
                        : "Synced · " + added + " new, " + updated + " updated"
                        + (failed == 0 ? "" : " · " + failed + " source(s) need attention"));
            } catch (Exception failure) {
                setState("Sync failed · " + failure.getClass().getSimpleName());
            } finally {
                synchronized (LOCK) { running = false; }
                jobFinished(params, false);
            }
        }, "sentient-sync").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelled = true;
        return true;
    }
}
