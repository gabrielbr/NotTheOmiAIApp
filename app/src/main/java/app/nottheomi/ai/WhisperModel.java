package app.nottheomi.ai;

import java.io.IOException;

/** One native context. Serial inference/close; cancellation never waits for inference. */
public final class WhisperModel implements AutoCloseable {
    private volatile long handle;
    private volatile boolean cancelled;

    public WhisperModel(String absoluteFilePath) throws IOException {
        this(absoluteFilePath, null);
    }

    /** {@code vadPath}: the installed Silero model, or null to decode whole windows. */
    public WhisperModel(String absoluteFilePath, String vadPath) throws IOException {
        handle = WhisperNative.openFile(absoluteFilePath, vadPath);
        if (handle == 0) throw new IOException("Whisper model initialization failed");
    }

    String transcribe(short[] pcm) throws IOException {
        return transcribe(pcm, Runtime.getRuntime().availableProcessors(), "pt", null);
    }

    synchronized String transcribe(short[] pcm, int threads, String language, String prompt) throws IOException {
        if (handle == 0 || cancelled) throw new IOException("Whisper model unavailable");
        String text = WhisperNative.transcribe(handle, pcm, Math.max(1, Math.min(4, threads)), language, prompt);
        if (cancelled) throw new IOException("Whisper inference cancelled");
        if (text == null) throw new IOException("Whisper returned no result");
        return text.trim();
    }

    public void cancel() {
        cancelled = true;
        long current = handle;
        if (current != 0) WhisperNative.cancel(current);
    }

    @Override public void close() {
        cancel();
        synchronized (this) {
            long current = handle;
            handle = 0;
            if (current != 0) WhisperNative.close(current);
        }
    }
}
