package br.gabriel.sentient;

/**
 * The on-device model, through llama.cpp (sentient/src/main/cpp/llama_jni.cpp). One generation
 * at a time per handle: callers serialize (LocalBackend does).
 */
public final class LlamaNative {
    public static final int TOO_LONG = -1, DECODE_FAILED = -2, BAD_INPUT = -3;

    /** Receives the answer as it's written, in complete UTF-8 chunks. Return false to stop. */
    public interface TokenSink {
        boolean accept(byte[] utf8);
        /** Prompt tokens read so far, called between chunks before the answer starts. Return false to stop. */
        default boolean progress(int done, int total) { return true; }
    }

    private LlamaNative() {}

    /** Loads the library (separate so tests can load a host build by path). */
    public static void loadLibrary() { System.loadLibrary("gmind-llama"); }

    /** A handle, or 0 if the model can't be loaded. */
    public static native long load(String modelPath, int contextTokens, int threads);

    /** Tokens generated (0 when stopped while reading the prompt), or TOO_LONG / DECODE_FAILED / BAD_INPUT. */
    public static native int generate(long handle, byte[] promptUtf8, int maxTokens, float temperature, int seed,
                                      TokenSink sink);

    public static native void close(long handle);
}
