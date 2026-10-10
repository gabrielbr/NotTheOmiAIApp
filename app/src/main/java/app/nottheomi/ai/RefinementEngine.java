package app.nottheomi.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Bounded, restartable PCM16 refinement. Neither plaintext files nor whole-session buffers.
 * Windows end at the quietest moment between 18 and 30 seconds, so a sentence is rarely cut in
 * two; the rest of the window starts the next one. Checkpoints sit on those cuts, and the cut
 * depends only on the audio, so a resumed pass windows exactly like an uninterrupted one.
 */
final class RefinementEngine {
    static final int WINDOW_SAMPLES = 30 * 16000;
    static final int MIN_WINDOW_SAMPLES = 18 * 16000;
    /** 100 ms frames, searched every 50 ms. */
    static final int FRAME = 1600, STEP = 800;
    interface Consumer { void accept(byte[] pcm) throws Exception; }
    interface Source { void stream(Consumer consumer) throws Exception; }
    interface Decoder { String transcribe(short[] samples) throws Exception; }
    interface Sink {
        void commit(long expectedOffset, long nextOffset, String text) throws Exception;
        void complete() throws Exception;
    }

    static final class Paused extends InterruptedIOException {
        Paused() { super("Saved transcript refinement paused"); }
    }

    private RefinementEngine() { }

    static void run(long totalBytes, long offsetBytes, Source source, Decoder decoder,
                    Sink sink, BooleanSupplier cancelled) throws Exception {
        if (totalBytes <= 0 || offsetBytes < 0 || offsetBytes > totalBytes
                || ((totalBytes | offsetBytes) & 1) != 0) {
            throw new IOException("Invalid saved PCM checkpoint");
        }
        new Pass(totalBytes, offsetBytes, decoder, sink, cancelled).run(source);
    }

    private static final class Pass {
        final long total, resume;
        final Decoder decoder;
        final Sink sink;
        final BooleanSupplier cancelled;
        final short[] window = new short[WINDOW_SAMPLES];
        long seen, committed;
        int count;

        Pass(long total, long resume, Decoder decoder, Sink sink, BooleanSupplier cancelled) {
            this.total = total; this.resume = resume; this.committed = resume;
            this.decoder = decoder; this.sink = sink; this.cancelled = cancelled;
        }

        void check() throws Paused {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Paused();
        }

        void run(Source source) throws Exception {
            try {
                check();
                source.stream(this::accept);
                check();
                if (seen != total) throw new IOException("Saved audio length differs from manifest");
                if (count > 0) flush();
                check();
                if (committed != total) throw new IOException("Refinement checkpoint incomplete");
                sink.complete();
            } finally { Arrays.fill(window, (short) 0); }
        }

        /** End of the window: the middle of its quietest 100 ms after 18 s. */
        static int cut(short[] window, int count) {
            if (count < WINDOW_SAMPLES) return count;
            long best = Long.MAX_VALUE;
            int at = count;
            for (int start = MIN_WINDOW_SAMPLES; start + FRAME <= count; start += STEP) {
                long energy = 0;
                for (int i = start; i < start + FRAME; i++) energy += (long) window[i] * window[i];
                if (energy < best) { best = energy; at = start + FRAME / 2; }
            }
            return at;
        }

        void accept(byte[] pcm) throws Exception {
            if (pcm == null) throw new IOException("Missing saved PCM chunk");
            try {
                check();
                if (pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length > total - seen)
                    throw new IOException("Invalid saved PCM chunk");
                int start = (int) Math.min(pcm.length, Math.max(0L, resume - seen));
                seen += pcm.length;
                for (int i = start; i < pcm.length; i += 2) {
                    window[count++] = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                    if (count == window.length) flush();
                }
            } finally { Arrays.fill(pcm, (byte) 0); }
        }

        void flush() throws Exception {
            check();
            int used = cut(window, count);
            short[] samples = Arrays.copyOf(window, used);
            String text;
            try { text = decoder.transcribe(samples); }
            finally {
                Arrays.fill(samples, (short) 0);
                // The audio after the cut opens the next window.
                System.arraycopy(window, used, window, 0, count - used);
                Arrays.fill(window, count - used, window.length, (short) 0);
                count -= used;
            }
            check(); // Never commit a result from preempted native work.
            if (text == null) throw new IOException("Refinement returned no result");
            long next = committed + used * 2L;
            sink.commit(committed, next, text.trim());
            committed = next;
        }
    }
}
