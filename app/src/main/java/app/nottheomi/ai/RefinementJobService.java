package app.nottheomi.ai;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Local-only saved-audio refinement. Never starts microphone or Bluetooth.
 *
 * <p>While a capture is active the worker runs on a plain thread in this process, which the
 * capture's foreground service keeps alive: no job time limit, so long windows on a slow phone
 * still finish. Otherwise Android runs it as a job. A pass reaching its own time limit always
 * finishes the window it started; only Android stopping the job (or the app being stopped)
 * discards a window in progress. Progress is saved after every window.
 */
public final class RefinementJobService extends JobService {
    static final int JOB_ID = 41008;
    private static final long PASS_MS = 7 * 60 * 1000L;
    private static final Object LOCK = new Object();
    private static Work owner;
    /** Threads while a capture is active on phones with fewer than 8 cores. */
    static final int CAPTURE_THREADS = 2;
    /** Slightly below normal, but not THREAD_PRIORITY_BACKGROUND, which moves threads to the little cores. */
    static final int WORKER_PRIORITY = 4;
    public static volatile String state = "Whisper refines each recording after it's saved";
    public static volatile long revision;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private Work current;

    /** Starts the accurate (medium) pass when the phone is next plugged in. */
    static final int JOB_ID_CHARGING = 41009;

    /** Queue is in encrypted SQLite; requests may be safely repeated. */
    public static void schedule(Context context) {
        scheduleCharging(context);
        synchronized (LOCK) {
            if (owner != null) { owner.rescheduleRequested = true; return; }
            if (capturing()) { startInProcess(context.getApplicationContext()); return; }
            try {
                JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                if (scheduler == null) return;
                int result = scheduler.schedule(new JobInfo.Builder(JOB_ID,
                        new ComponentName(context, RefinementJobService.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                        .setRequiresStorageNotLow(true)
                        .setMinimumLatency(1000L)
                        .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build());
                if (result != JobScheduler.RESULT_SUCCESS)
                    setState("Whisper scheduling unavailable · reopen app to retry; drafts kept");
                else RefinementProgress.idle("Waiting for Android to start it (it needs some free storage)");
            } catch (RuntimeException unavailable) {
                setState("Whisper scheduling unavailable · reopen app to retry; drafts kept");
            }
        }
    }

    private static void scheduleCharging(Context context) {
        if (!OmiSettingsActivity.betterWhileCharging(context)) return;
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.schedule(new JobInfo.Builder(JOB_ID_CHARGING,
                    new ComponentName(context, RefinementJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setRequiresCharging(true)
                    .setRequiresStorageNotLow(true)
                    .build());
        } catch (RuntimeException unavailable) { /* The quick transcripts stand. */ }
    }

    private static void setState(String value) { state = value; revision++; }
    private static boolean capturing() { return CaptureService.active || OmiCaptureService.active; }

    /** A capture started: refine saved recordings alongside it, in this process. Any thread. */
    public static void captureStarted(Context context) { schedule(context); }

    /**
     * Whisper threads for the next window, re-read every window: up to 4; while capturing, leave
     * room for live speech and the audio pipeline (2 on phones with fewer than 8 cores).
     */
    static int threads() { return threads(Runtime.getRuntime().availableProcessors(), capturing()); }

    static int threads(int processors, boolean capturing) {
        int cores = Math.max(1, Math.min(4, processors));
        return capturing && processors < 8 ? Math.min(CAPTURE_THREADS, cores) : cores;
    }

    private static void startInProcess(Context context) {
        Work work = new Work(null, null, context);
        owner = work;
        work.thread = new Thread(() -> runWork(work), "saved-whisper-refinement");
        work.thread.start();
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        synchronized (LOCK) {
            if (owner != null) {
                // The in-process worker already has it (and hands off to a job itself); a
                // cancelled older job must exit before another model can start.
                boolean retryLater = owner.service != null;
                owner.rescheduleRequested = true;
                MAIN.post(() -> jobFinished(parameters, retryLater));
                return true;
            }
            Work work = new Work(parameters, this, getApplicationContext());
            owner = current = work;
            work.thread = new Thread(() -> runWork(work), "saved-whisper-refinement");
            work.thread.start();
            return true;
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        synchronized (LOCK) {
            // Binder unmarshals a new JobParameters for stop; object identity is not stable.
            if (current != null && current.parameters.getJobId() == parameters.getJobId()) {
                current.platformStopped = true;
                current.cancel();
            }
        }
        // Android will reschedule. Checkpoints/drafts survive even abrupt process death.
        return true;
    }

    @Override public void onDestroy() {
        synchronized (LOCK) {
            if (current != null) { current.platformStopped = true; current.cancel(); }
        }
        super.onDestroy();
    }

    private static void runWork(Work work) {
        boolean retry = false;
        Context context = work.context;
        try { Process.setThreadPriority(WORKER_PRIORITY); } // native worker threads inherit it
        catch (RuntimeException unavailable) { /* Default priority still works. */ }
        try {
            if (work.shouldPause()) { retry = true; return; }
            Recordings store = Recordings.get(context);
            boolean better = OmiSettingsActivity.betterWhileCharging(context);
            List<Recordings.Refinement> quick = store.pendingRefinements();
            boolean accurate = better && charging(context) && !store.pendingFinals().isEmpty();
            if (quick.isEmpty() && !accurate) {
                setState("Saved transcripts up to date");
                RefinementProgress.idle(better && !store.pendingFinals().isEmpty()
                        ? "Quick transcripts done · the accurate ones are made while the phone charges" : null);
                return;
            }
            setState("Preparing Whisper for saved recordings");
            String vad = ModelInstaller.prepareVad(context, work::cancelledNow).getAbsolutePath();
            String language = OmiSettingsActivity.language(context);
            String vocabulary = OmiSettingsActivity.vocabulary(context);
            List<String> done = new ArrayList<>();
            // Quick pass: Whisper small, while recording too.
            if (!quick.isEmpty()) {
                String small = ModelInstaller.prepareSmall(context, work::cancelledNow).getAbsolutePath();
                if (work.shouldPause()) { retry = true; return; }
                work.model = new WhisperModel(small, vad);
                RefinementProgress.build = WhisperNative.BUILD + " build";
                if (work.cancelledNow()) { work.cancel(); retry = true; return; }
                retry |= pass(work, store, quick, false, language, vocabulary, done);
                closeModel(work);
            }
            // Accurate pass: Whisper medium, only while charging; it replaces the quick text when done.
            if (!work.shouldPause() && better && charging(context)) {
                List<Recordings.Refinement> finals = store.pendingFinals();
                if (!finals.isEmpty()) {
                    String medium = ModelInstaller.prepare(context, work::cancelledNow).getAbsolutePath();
                    if (work.shouldPause()) { retry = true; return; }
                    work.model = new WhisperModel(medium, vad);
                    RefinementProgress.build = WhisperNative.BUILD + " build";
                    if (work.cancelledNow()) { work.cancel(); retry = true; return; }
                    retry |= pass(work, store, finals, true, language, vocabulary, done);
                }
            }
            try { ReadyNotifier.refined(context, done); }
            catch (RuntimeException notificationUnavailable) { /* Transcripts are saved either way. */ }
            if (!work.cancelledNow() && !store.pendingRefinements().isEmpty()) retry = true;
            if (!work.cancelledNow() && better && charging(context) && !store.pendingFinals().isEmpty()) retry = true;
            if (!retry) {
                setState("Whisper pass finished · check each recording's transcript status");
                RefinementProgress.idle(better && !store.pendingFinals().isEmpty()
                        ? "Quick transcripts done · the accurate ones are made while the phone charges" : null);
            }
        } catch (Exception | LinkageError failure) {
            if (work.shouldPause()) retry = true;
            else {
                setState("Whisper unavailable · drafts and audio kept; reopen app to retry");
                RefinementProgress.idle("Whisper couldn't start on this phone (" + failure.getClass().getSimpleName() + ")");
            }
        } finally {
            // Native context belongs exclusively to this worker. Cancellation never frees it.
            if (work.model != null) {
                try { work.model.close(); } catch (RuntimeException | LinkageError ignored) { }
                work.model = null;
            }
            final boolean reschedule = retry;
            MAIN.post(() -> finished(work, reschedule));
        }
    }

    private static void finished(Work work, boolean reschedule) {
        synchronized (LOCK) {
            if (owner != work) return;
            owner = null;
            RefinementJobService service = work.service;
            if (service != null && service.current == work) service.current = null;
            boolean again = (reschedule || work.rescheduleRequested) && !work.cancelled;
            if (service == null) {
                // In-process: carry on here while capturing, or hand the rest to a job.
                if (again) schedule(work.context);
                return;
            }
            if (work.platformStopped) return; // Android reschedules stopped jobs itself.
            if (again) {
                // A pass ending at its time limit continues right away, not after a growing backoff.
                service.jobFinished(work.parameters, false);
                schedule(work.context);
            } else service.jobFinished(work.parameters, reschedule || work.rescheduleRequested);
        }
    }

    /** One lane over its recordings with the loaded model. Returns true when more is left to do. */
    private static boolean pass(Work work, Recordings store, List<Recordings.Refinement> entries, boolean accurate,
                                String language, String vocabulary, List<String> done) {
        boolean retry = false;
        for (Recordings.Refinement entry : entries) {
            if (work.shouldPause() || (accurate && !charging(work.context))) { retry = true; break; }
            try {
                Recordings.Refinement fresh = accurate ? store.finalRefinement(entry.id) : store.refinement(entry.id);
                if (fresh == null || !"pending".equals(fresh.state)) continue;
                setState(accurate ? "Improving a quick transcript while charging" : "Refining saved transcript · live draft and audio available");
                WhisperModel model = work.model;
                RefinementProgress.begin(fresh.id, fresh.offsetBytes, fresh.totalBytes, model::progress, accurate);
                refine(store, fresh, model, language, vocabulary, work::cancelledNow,
                        accurate ? () -> work.softStop() || !charging(work.context) : work::softStop);
                if (!accurate) done.add(fresh.id);
                revision++;
            } catch (Recordings.StaleCheckpointException restarted) {
                // Set to refine again meanwhile: it's pending from the start; pick it up next.
                retry = true;
                revision++;
            } catch (Exception | LinkageError failure) {
                if (work.shouldPause() || failure instanceof RefinementEngine.Paused) {
                    retry = true;
                    break;
                }
                // A failed item is not retried forever. The user can explicitly retry.
                try { if (accurate) store.failFinal(entry.id); else store.failRefinement(entry.id); }
                catch (Exception missingOrDamaged) { /* Preserve original archive; no destructive repair. */ }
                setState(accurate ? "The accurate pass failed for a recording · its quick transcript stays"
                        : "Whisper refinement failed · live draft and audio kept; retry in Library");
            }
        }
        return retry;
    }

    private static void closeModel(Work work) {
        WhisperModel model = work.model;
        work.model = null;
        if (model != null) {
            try { model.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    /** Plugged in (or full on the charger). Any thread. */
    static boolean charging(Context context) {
        try {
            android.os.BatteryManager battery = context.getSystemService(android.os.BatteryManager.class);
            return battery != null && battery.isCharging();
        } catch (RuntimeException unavailable) { return false; }
    }

    static void refine(Recordings store, Recordings.Refinement entry, WhisperModel model, String language, String vocabulary,
                       java.util.function.BooleanSupplier cancelled) throws Exception {
        refine(store, entry, model, language, vocabulary, cancelled, () -> false, Recordings.MEDIUM);
    }

    static void refine(Recordings store, Recordings.Refinement entry, WhisperModel model, String language, String vocabulary,
                       java.util.function.BooleanSupplier cancelled, java.util.function.BooleanSupplier stopBeforeWindow)
            throws Exception {
        refine(store, entry, model, language, vocabulary, cancelled, stopBeforeWindow, Recordings.SMALL);
    }

    /** {@code quickModel}: what made a main (non-final) pass, recorded on completion. */
    static void refine(Recordings store, Recordings.Refinement entry, WhisperModel model, String language, String vocabulary,
                       java.util.function.BooleanSupplier cancelled, java.util.function.BooleanSupplier stopBeforeWindow,
                       String quickModel) throws Exception {
        RefinementEngine.run(entry.totalBytes, entry.offsetBytes,
                consumer -> store.forEachPcm(entry.id, consumer::accept), samples -> model.transcribe(samples, threads(), language, vocabulary),
                new RefinementEngine.Sink() {
                    public void commit(long before, long after, String text) throws Exception {
                        if (entry.finalPass) store.commitFinalBatch(entry.id, before, after, text);
                        else store.commitRefinementBatch(entry.id, before, after, text);
                        RefinementProgress.saved(after);
                        revision++;
                    }
                    public void complete() throws Exception {
                        if (entry.finalPass) store.completeFinal(entry.id);
                        else store.completeRefinement(entry.id, quickModel);
                    }
                }, cancelled, stopBeforeWindow, RefinementProgress::window);
    }

    /** One worker: a job (service and parameters set) or the in-process runner during capture. */
    private static final class Work {
        final JobParameters parameters;
        final RefinementJobService service;
        final Context context;
        final long deadline = SystemClock.elapsedRealtime() + PASS_MS;
        volatile boolean cancelled, platformStopped, rescheduleRequested;
        volatile WhisperModel model;
        Thread thread;
        Work(JobParameters parameters, RefinementJobService service, Context context) {
            this.parameters = parameters; this.service = service; this.context = context;
        }
        boolean cancelledNow() { return cancelled; }
        /** Stop before the next window: a job's time is up, or the capture keeping us alive ended. */
        boolean softStop() {
            return service == null ? !capturing() : SystemClock.elapsedRealtime() >= deadline;
        }
        boolean shouldPause() { return cancelled || softStop(); }
        void cancel() {
            cancelled = true;
            WhisperModel nativeOwner = model;
            if (nativeOwner != null) nativeOwner.cancel();
        }
    }
}
