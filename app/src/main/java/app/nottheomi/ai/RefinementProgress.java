package app.nottheomi.ai;

import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * What Whisper is doing right now, for the screens: which recording, how far (saved checkpoint plus
 * the current window's own progress), how fast, and when anything last moved, so a slow refinement
 * can be told apart from one that's stuck. Written by the refinement worker, read by the UI.
 */
final class RefinementProgress {
    /** No movement for this long is shown as a warning. */
    static final long STALL_MS = 5 * 60 * 1000L;
    static final long BYTES_PER_MS = 32; // 16 kHz mono PCM16

    static LongSupplier clock = System::currentTimeMillis;
    /** Which native Whisper build runs ("fast build" / "compatible build"); null until loaded. */
    static volatile String build;
    /** Fast cores Whisper is pinned to (0: not pinned). */
    static volatile int cores;
    /** Battery usage is unrestricted: the service can start from the background. Set by the UI. */
    static volatile boolean background;
    /** A model download in progress ("Downloading Whisper small · 45% · …"), else null. */
    private static volatile String download;

    private static String id;
    private static long saved, total, windowStart, windowBytes, windowStartedAt, lastChangeAt;
    private static int lastPercent = -1;
    /** Audio milliseconds refined per wall millisecond, from finished windows; 0 until known. */
    private static double speed;
    private static IntSupplier windowPercent = () -> 0;
    private static String waiting;
    /** The current pass is the accurate (medium) one, made while charging. */
    private static boolean accurate;

    private RefinementProgress() {}

    /** A snapshot for display. */
    static final class Snapshot {
        final String id, waiting;
        final boolean accurate;
        final long saved, total, done, lastChangeAt;
        final double speed;
        Snapshot(String id, String waiting, long saved, long total, long done, long lastChangeAt, double speed,
                 boolean accurate) {
            this.id = id; this.waiting = waiting; this.accurate = accurate; this.saved = saved; this.total = total; this.done = done;
            this.lastChangeAt = lastChangeAt; this.speed = speed;
        }
        int percent() { return total <= 0 ? 0 : (int) Math.min(100, done * 100 / total); }
    }

    static synchronized void begin(String recording, long savedBytes, long totalBytes, IntSupplier percent) {
        begin(recording, savedBytes, totalBytes, percent, false);
    }

    static synchronized void begin(String recording, long savedBytes, long totalBytes, IntSupplier percent,
                                   boolean accuratePass) {
        id = recording; accurate = accuratePass;
        if (accuratePass) speed = 0; // a different model: its own speed
        saved = savedBytes; total = totalBytes; windowBytes = 0;
        windowPercent = percent == null ? () -> 0 : percent;
        waiting = null; lastPercent = -1; lastChangeAt = clock.getAsLong();
        RefinementJobService.revision++;
    }

    static synchronized void window(long startBytes, long lengthBytes) {
        windowStart = startBytes; windowBytes = lengthBytes; windowStartedAt = clock.getAsLong();
        lastPercent = -1;
    }

    static synchronized void saved(long afterBytes) {
        long now = clock.getAsLong();
        long elapsed = Math.max(1, now - windowStartedAt);
        if (windowBytes > 0 && afterBytes > windowStart) {
            double rate = (afterBytes - windowStart) / (double) BYTES_PER_MS / elapsed;
            speed = speed == 0 ? rate : speed * 0.6 + rate * 0.4;
        }
        saved = afterBytes; windowBytes = 0; lastChangeAt = now; lastPercent = -1;
    }

    /** A model download's progress line, or null when none runs. Any thread; cheap to call often. */
    static void download(String line) { download = line; }

    /** Nothing is being refined: why (null when the queue is simply empty). */
    static synchronized void idle(String reason) {
        id = null; windowBytes = 0; windowPercent = () -> 0; waiting = reason;
        lastChangeAt = clock.getAsLong();
        RefinementJobService.revision++;
    }

    static synchronized Snapshot get() {
        long now = clock.getAsLong();
        int percent = id == null || windowBytes == 0 ? 0 : Math.max(0, Math.min(100, windowPercent.getAsInt()));
        if (percent != lastPercent) {
            if (lastPercent >= 0 && percent > lastPercent) lastChangeAt = now;
            lastPercent = percent;
        }
        long done = saved + windowBytes * percent / 100;
        String note = download;
        return new Snapshot(id, note != null ? note : waiting, saved, total, done, lastChangeAt, speed, accurate);
    }

    /** "Refining · 34% · 12:30 of 41:00 · about 25 min left · updated 20 s ago". */
    static String describe(Snapshot s, long now) {
        StringBuilder line = new StringBuilder(s.accurate ? "Improving · " : "Refining · ").append(s.percent()).append("% · ")
                .append(clock(s.done / BYTES_PER_MS)).append(" of ").append(clock(s.total / BYTES_PER_MS));
        if (s.speed > 0) {
            long leftMs = (long) ((s.total - s.done) / (double) BYTES_PER_MS / s.speed);
            line.append(" · about ").append(span(leftMs)).append(" left");
        }
        line.append(" · updated ").append(span(Math.max(0, now - s.lastChangeAt))).append(" ago");
        return line.toString();
    }

    /** "fast build, 4 fast cores, background allowed, 3.1× real time" for the home line; null when nothing is known. */
    static String engine(Snapshot s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (build != null) parts.add(build);
        if (cores > 0) parts.add(cores + " fast cores");
        if (background) parts.add("background allowed");
        if (s.id != null && s.speed > 0) parts.add(String.format(Locale.ROOT, "%.1f", s.speed) + "× real time");
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** True when nothing has moved for STALL_MS. */
    static boolean stalled(Snapshot s, long now) { return now - s.lastChangeAt >= STALL_MS; }

    static String clock(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String span(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + " s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** Tests only. */
    static synchronized void reset() {
        id = null; saved = total = windowStart = windowBytes = windowStartedAt = lastChangeAt = 0;
        lastPercent = -1; speed = 0; windowPercent = () -> 0; waiting = null; accurate = false;
        build = null; cores = 0; background = false; download = null;
    }
}
