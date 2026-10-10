package app.nottheomi.ai;

import java.io.IOException;

/** CPU-only, offline JNI. The owner serializes open/transcribe/close off the UI thread. */
public final class WhisperNative {
    /** "fast" (ARMv8.2 dot-product + fp16 build) or "compatible" (portable armv8-a build). */
    public static final String BUILD;

    static {
        String chosen = "compatible";
        if (fastCpu(cpuinfo())) {
            try { System.loadLibrary("nottheomi-whisper-dotprod"); chosen = "fast"; }
            catch (UnsatisfiedLinkError unavailable) { System.loadLibrary("nottheomi-whisper"); }
        } else System.loadLibrary("nottheomi-whisper");
        BUILD = chosen;
    }

    /** True when every core lists both the dot-product and half-precision SIMD features. */
    public static boolean fastCpu(String cpuinfo) {
        if (cpuinfo == null) return false;
        boolean any = false;
        for (String line : cpuinfo.split("\n")) {
            if (!line.startsWith("Features")) continue;
            any = true;
            java.util.Set<String> features = new java.util.HashSet<>(
                    java.util.Arrays.asList(line.substring(line.indexOf(':') + 1).trim().split("\\s+")));
            if (!features.contains("asimddp") || !features.contains("asimdhp")) return false;
        }
        return any;
    }

    private static String cpuinfo() {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/cpuinfo")),
                java.nio.charset.StandardCharsets.US_ASCII); }
        catch (Exception | LinkageError unreadable) { return null; }
    }

    private WhisperNative() { }

    /**
     * Opens a previously hash-verified private model file, with an optional hash-verified Silero
     * VAD model (null disables VAD). Returns an opaque nonzero handle.
     */
    public static native long openFile(String path, String vadPath) throws IOException;

    /**
     * Transcribes 16kHz mono signed PCM16, at most 480000 samples (30 seconds).
     * Zero-length, sub-100ms, near-silent or cancelled input returns an empty string.
     * Threads are clamped to 1..4. Language is "pt", "en" or "auto" (detected per call);
     * English-only weights always use "en". Prompt: optional words to expect (names, jargon),
     * at most 400 characters; null or empty for none. This method does not modify the caller's array;
     * the caller must wipe it after return and must not mutate it during this call.
     * Native temporary raw PCM copies are wiped on success, error and cancellation.
     * No transcript, audio, or model path is logged by this adapter.
     */
    public static native String transcribe(long handle, short[] pcm, int threads, String language, String prompt) throws IOException;

    /** The current window's progress, 0..100 (0 before it starts or for an unknown handle). Any thread. */
    public static native int progress(long handle);

    /**
     * Keeps the calling thread, and the Whisper threads it starts, on the cores above the slowest
     * frequency tier (the big cores). Returns how many; 0 when all cores are alike or it can't.
     */
    public static native int pinFastCores();

    /**
     * Cross-thread safe, non-blocking with respect to inference. Cancellation is
     * sticky: discard/close this handle and open a new one to resume. Unknown or
     * already-closed handles are harmless. Model loading itself is synchronous.
     */
    public static native void cancel(long handle);

    /** Cancels, waits for an active call, then releases ownership. Idempotent, including zero. */
    public static native void close(long handle);
}
