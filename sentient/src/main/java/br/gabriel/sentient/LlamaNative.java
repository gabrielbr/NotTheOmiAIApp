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

    /** Which native build runs: "fast" (dotprod + fp16 + i8mm) or "compatible"; null until loaded. */
    public static volatile String build;

    /**
     * Loads the library (separate so tests can load a host build by path): the fast build when
     * every core has the dot-product, half-precision and int8 matrix instructions, else the
     * portable one.
     */
    public static synchronized void loadLibrary() {
        if (build != null) return;
        String chosen = "compatible";
        if (fastCpu(cpuinfo())) {
            try { System.loadLibrary("gmind-llama-fast"); chosen = "fast"; }
            catch (UnsatisfiedLinkError unavailable) { System.loadLibrary("gmind-llama"); }
        } else System.loadLibrary("gmind-llama");
        build = chosen;
    }

    /** True when every core lists the dot-product, half-precision and int8 matrix-multiply features. */
    public static boolean fastCpu(String cpuinfo) {
        if (cpuinfo == null) return false;
        boolean any = false;
        for (String line : cpuinfo.split("\n")) {
            if (!line.startsWith("Features")) continue;
            any = true;
            java.util.Set<String> features = new java.util.HashSet<>(
                    java.util.Arrays.asList(line.substring(line.indexOf(':') + 1).trim().split("\\s+")));
            if (!features.contains("asimddp") || !features.contains("asimdhp") || !features.contains("i8mm")) return false;
        }
        return any;
    }

    private static String cpuinfo() {
        try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/cpuinfo")),
                java.nio.charset.StandardCharsets.US_ASCII); }
        catch (Exception | LinkageError unreadable) { return null; }
    }

    /**
     * Keeps the calling thread, and the threads llama.cpp starts from it, on the cores above the
     * slowest frequency tier (the big cores). Returns how many; 0 when all cores are alike or it can't.
     */
    public static native int pinFastCores();

    /** A handle, or 0 if the model can't be loaded. */
    public static native long load(String modelPath, int contextTokens, int threads);

    /** Tokens generated (0 when stopped while reading the prompt), or TOO_LONG / DECODE_FAILED / BAD_INPUT. */
    public static native int generate(long handle, byte[] promptUtf8, int maxTokens, float temperature, int seed,
                                      TokenSink sink);

    public static native void close(long handle);
}
