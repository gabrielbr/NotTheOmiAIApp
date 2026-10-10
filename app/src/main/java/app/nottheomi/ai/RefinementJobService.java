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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-only saved-audio refinement. Never starts microphone or Bluetooth.
 *
 * <p>The worker normally runs inside {@link RefinementService}, a foreground service: Android
 * then lets it use the big CPU cores and doesn't ration its time. A background job (or plain
 * process) is confined to the little cores and job quotas, which made Whisper many times slower.
 * When Android refuses the foreground start (app in the background without the battery
 * exemption), it falls back to the old paths: a plain thread while a capture's foreground service
 * keeps the process up, otherwise a job. A pass reaching its own time limit always finishes the
 * window it started. Progress is saved after every window.
 */
public final class RefinementJobService extends JobService {
    static final int JOB_ID = 41008;
    private static final long PASS_MS = 7 * 60 * 1000L;
    private static final Object LOCK = new Object();
    private static Work owner;
    /** The service, started while another worker held the model, waiting to take over. */
    private static RefinementService standby;
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
            if (owner != null) {
                owner.rescheduleRequested = true;
                // A job or plain thread runs on the little cores: move it to the service after its window.
                if (owner.host == null && standby == null) startService(context);
                return;
            }
            if (startService(context)) return;
            if (capturing()) { startInProcess(context.getApplicationContext()); return; }
            scheduleJob(context);
        }
    }

    /** The service started but couldn't go to the foreground: run the work the old way for a while. */
    static void scheduleFallback(Context context) {
        refusedAt = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (owner != null) return;
            if (capturing()) startInProcess(context.getApplicationContext());
            else scheduleJob(context);
        }
    }

    /** When the service last failed to reach the foreground; it isn't retried for a while after. */
    private static final long REFUSED_MS = 10 * 60 * 1000L;
    private static volatile long refusedAt = -REFUSED_MS;

    private static boolean startService(Context context) {
        if (SystemClock.elapsedRealtime() - refusedAt < REFUSED_MS) return false;
        return RefinementService.start(context);
    }

    /** The job fallback: Android starts it when it can; it first tries to move into the service. */
    private static void scheduleJob(Context context) { scheduleJob(context, JobInfo.NETWORK_TYPE_NONE); }

    /** {@code network}: the network a model download needs; NONE once the models are on the phone. */
    private static void scheduleJob(Context context, int network) {
        synchronized (LOCK) {
            try {
                JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                if (scheduler == null) return;
                int result = scheduler.schedule(new JobInfo.Builder(JOB_ID,
                        new ComponentName(context, RefinementJobService.class))
                        .setRequiredNetworkType(network)
                        .setRequiresStorageNotLow(true)
                        .setMinimumLatency(1000L)
                        .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build());
                if (result != JobScheduler.RESULT_SUCCESS)
                    setState("Whisper scheduling unavailable · reopen app to retry; drafts kept");
                else if (network == JobInfo.NETWORK_TYPE_NONE)
                    RefinementProgress.idle("Waiting for Android to start it (it needs some free storage)");
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

    /** Settings › "Download now": fetch the models even with nothing to transcribe yet. */
    static volatile boolean downloadRequested;

    static void downloadNow(Context context) {
        downloadRequested = true;
        schedule(context);
    }

    private static boolean metered(Context context) { return OmiSettingsActivity.mobileDownloads(context); }

    private static ModelInstaller.Progress downloading(String model) {
        return (done, total) -> RefinementProgress.download(total <= 0 || done >= total ? null
                : "Downloading Whisper " + model + " · " + done * 100 / total + "% · "
                  + done / 1_000_000 + " of " + total / 1_000_000 + " MB");
    }

    private static File small(Context context, Work work) throws Exception {
        return ModelInstaller.prepareSmall(context, work::cancelledNow, metered(context), downloading("small"));
    }

    private static File medium(Context context, Work work) throws Exception {
        return ModelInstaller.prepare(context, work::cancelledNow, metered(context), downloading("medium"));
    }
    private static boolean capturing() { return CaptureService.active || OmiCaptureService.active; }

    /** A capture started: refine saved recordings alongside it, in this process. Any thread. */
    public static void captureStarted(Context context) { schedule(context); }

    /**
     * Whisper threads for the next window, re-read every window: up to 4; while capturing, leave
     * room for live speech and the audio pipeline (2 on phones with fewer than 8 cores).
     */
    static int threads() { return threads(Runtime.getRuntime().availableProcessors(), capturing(), fastCores); }

    /** Fast cores the worker is pinned to (0: not pinned, any core). */
    static volatile int fastCores;

    static int threads(int processors, boolean capturing) { return threads(processors, capturing, 0); }

    /** One thread per fast core when pinned: more threads than those cores would just queue. */
    static int threads(int processors, boolean capturing, int fast) {
        int cores = Math.max(1, Math.min(4, fast > 0 ? Math.min(processors, fast) : processors));
        return capturing && processors < 8 ? Math.min(CAPTURE_THREADS, cores) : cores;
    }

    /**
     * Called by {@link RefinementService} once it's in the foreground: start the worker there, or
     * take over from another worker after its window. False only if it can't start.
     */
    static boolean host(RefinementService service) {
        synchronized (LOCK) {
            if (owner != null) {
                owner.rescheduleRequested = true;
                if (owner.host == null) {
                    // Wait in the foreground; the worker hands over after its window.
                    owner.promote = true;
                    standby = service;
                }
                return true;
            }
            Work work = new Work(null, null, service.getApplicationContext(), service);
            owner = work;
            work.thread = new Thread(() -> runWork(work), "saved-whisper-refinement");
            work.thread.start();
            return true;
        }
    }

    /** The service is going away (stopped by Android or the user): stop and leave the rest to a job. */
    static void hostGone(RefinementService service) {
        synchronized (LOCK) {
            if (standby == service) standby = null;
            if (owner != null && owner.host == service) { owner.hostLost = true; owner.cancel(); }
        }
    }

    private static void startInProcess(Context context) {
        Work work = new Work(null, null, context, null);
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
            // Android runs jobs on the little cores; the service gets the big ones.
            if (startService(getApplicationContext())) {
                MAIN.post(() -> jobFinished(parameters, false));
                return true;
            }
            Work work = new Work(parameters, this, getApplicationContext(), null);
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
        // Native worker threads inherit both the priority and the core affinity set below.
        try { Process.setThreadPriority(work.host != null ? Process.THREAD_PRIORITY_DEFAULT : WORKER_PRIORITY); }
        catch (RuntimeException unavailable) { /* Default priority still works. */ }
        try {
            if (work.shouldPause()) { retry = true; return; }
            Recordings store = Recordings.get(context);
            boolean better = OmiSettingsActivity.betterWhileCharging(context);
            List<Recordings.Refinement> quick = store.pendingRefinements();
            boolean accurate = better && charging(context) && !store.pendingFinals().isEmpty();
            boolean fetch = downloadRequested;
            if (quick.isEmpty() && !accurate && !fetch) {
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
                String small = small(context, work).getAbsolutePath();
                if (work.shouldPause()) { retry = true; return; }
                work.model = new WhisperModel(small, vad);
                loaded();
                if (work.cancelledNow()) { work.cancel(); retry = true; return; }
                retry |= pass(work, store, quick, false, language, vocabulary, done);
                closeModel(work);
            }
            // Accurate pass: Whisper medium, only while charging; it replaces the quick text when done.
            if (!work.shouldPause() && better && charging(context)) {
                List<Recordings.Refinement> finals = store.pendingFinals();
                if (!finals.isEmpty()) {
                    String medium = medium(context, work).getAbsolutePath();
                    if (work.shouldPause()) { retry = true; return; }
                    work.model = new WhisperModel(medium, vad);
                    loaded();
                    if (work.cancelledNow()) { work.cancel(); retry = true; return; }
                    retry |= pass(work, store, finals, true, language, vocabulary, done);
                }
            }
            // "Download now": the models only, so they're ready before the first recording.
            if (fetch && !work.shouldPause()) {
                small(context, work);
                if (better) medium(context, work);
                downloadRequested = false;
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
        } catch (ModelInstaller.WaitingForNetwork offline) {
            // Not an error: Android starts the job again once an allowed network is up.
            boolean anyNetwork = metered(context);
            RefinementProgress.download(null);
            RefinementProgress.idle(anyNetwork ? "Waiting for a network to download the Whisper model"
                    : "Waiting for Wi-Fi to download the Whisper model");
            setState("Waiting to download the Whisper model");
            work.network = anyNetwork ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED;
        } catch (Exception | LinkageError failure) {
            RefinementProgress.download(null);
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

    /** Whisper is loaded on this worker thread: note the build, and keep it on the fast cores. */
    private static void loaded() {
        RefinementProgress.build = WhisperNative.BUILD + " build";
        int cores = 0;
        try { cores = WhisperNative.pinFastCores(); }
        catch (LinkageError unavailable) { /* All cores, as before. */ }
        fastCores = cores;
        RefinementProgress.cores = cores;
    }

    private static void finished(Work work, boolean reschedule) {
        synchronized (LOCK) {
            if (owner != work) return;
            owner = null;
            if (work.network >= 0) {
                // A model download needs a network: hand over to a job that waits for one.
                RefinementJobService service = work.service;
                if (service != null && service.current == work) service.current = null;
                if (service != null && !work.platformStopped) service.jobFinished(work.parameters, false);
                if (work.host != null) work.host.done();
                RefinementService waiting = standby;
                standby = null;
                if (waiting != null) waiting.done();
                scheduleJob(work.context, work.network);
                return;
            }
            RefinementService host = work.host;
            if (host != null) {
                if (work.hostLost) { scheduleJob(work.context); return; }
                boolean more = (reschedule || work.rescheduleRequested) && !work.cancelled;
                if (!more || !host(host)) host.done();
                return;
            }
            RefinementJobService service = work.service;
            if (service != null && service.current == work) service.current = null;
            RefinementService waiting = standby;
            standby = null;
            if (waiting != null) {
                // The service is up and waiting for this worker's window: continue there.
                if (service != null && !work.platformStopped) service.jobFinished(work.parameters, false);
                if (!host(waiting)) waiting.done();
                return;
            }
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

    /**
     * One worker: in the foreground service (host set), a job (service and parameters set), or the
     * plain-thread fallback during capture.
     */
    private static final class Work {
        final JobParameters parameters;
        final RefinementJobService service;
        final RefinementService host;
        final Context context;
        final long deadline = SystemClock.elapsedRealtime() + PASS_MS;
        volatile boolean cancelled, platformStopped, rescheduleRequested, hostLost;
        /** The service started: stop after this window and continue there, on the fast cores. */
        volatile boolean promote;
        /** Set when a model download waits for this network type (a JobInfo NETWORK_TYPE_*). */
        volatile int network = -1;
        volatile WhisperModel model;
        Thread thread;
        Work(JobParameters parameters, RefinementJobService service, Context context, RefinementService host) {
            this.parameters = parameters; this.service = service; this.context = context; this.host = host;
        }
        boolean cancelledNow() { return cancelled; }
        /**
         * Stop before the next window: the service took over, a job's time is up, or the capture
         * keeping the plain thread alive ended. The service itself runs until the queue is done.
         */
        boolean softStop() {
            if (host != null) return false;
            if (promote) return true;
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
