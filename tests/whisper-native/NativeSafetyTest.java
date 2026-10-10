import app.nottheomi.ai.WhisperNative;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeSafetyTest {
    private static native void setMode(int mode);
    private static native boolean entered();
    private static native int live();
    private static native void setMultilingual(boolean value);
    private static native String language();
    private static native boolean vad();
    private static native String prompt();
    private static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("check " + checks);
    }
    static void io(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); }
        catch (IOException expected) {
            check(!expected.getMessage().contains("test-model"));
        }
    }
    static long open() throws IOException { return WhisperNative.openFile("test-model-😀.bin", null); }
    static short[] audio(int count) { short[] out = new short[count]; Arrays.fill(out, (short) 8192); return out; }
    static void waitEntered() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!entered() && System.nanoTime() < deadline) Thread.sleep(1);
        check(entered());
    }
    public static void main(String[] args) throws Exception {
        // Library choice: the fast build only when every core has dotprod and fp16 SIMD.
        check(WhisperNative.fastCpu("processor : 0\nFeatures : fp asimd evtstrm aes asimdhp cpuid asimdrdm asimddp\n"
                + "processor : 1\nFeatures : fp asimd asimdhp asimddp lrcpc\n"));
        check(!WhisperNative.fastCpu("Features : fp asimd asimddp\n"));          // no fp16
        check(!WhisperNative.fastCpu("Features : fp asimd asimdhp asimddp\nFeatures : fp asimd\n")); // one core lacks it
        check(!WhisperNative.fastCpu("flags : fpu sse avx2\n") && !WhisperNative.fastCpu(null)); // x86, unreadable
        check(WhisperNative.BUILD.equals("compatible")); // this x86 host loads the portable build
        io(() -> WhisperNative.openFile(null, null));
        io(() -> WhisperNative.openFile("", null));
        io(() -> WhisperNative.openFile("x".repeat(4097), null));
        io(() -> WhisperNative.openFile("bad\u0000path", null));
        io(() -> WhisperNative.openFile("bad\ud800path", null));
        io(() -> WhisperNative.openFile("bad\udc00path", null));
        io(() -> WhisperNative.openFile("bad-model", null));
        io(() -> WhisperNative.openFile("oom", null));
        check(live() == 0);
        long handle = open();
        check(handle > 0 && live() == 1);
        io(() -> WhisperNative.transcribe(0, audio(16000), 4, "pt", null));
        io(() -> WhisperNative.transcribe(Long.MAX_VALUE, audio(16000), 4, "pt", null));
        io(() -> WhisperNative.transcribe(handle, null, 4, "pt", null));
        io(() -> WhisperNative.transcribe(handle, audio(480001), 4, "pt", null));
        check(WhisperNative.transcribe(handle, new short[0], 4, "pt", null).isEmpty());
        check(WhisperNative.transcribe(handle, audio(1599), 4, "pt", null).isEmpty());
        check(WhisperNative.transcribe(handle, new short[480000], 4, "pt", null).isEmpty());
        check(!entered());
        short[] pcm = audio(1600);
        short[] copy = pcm.clone();
        check(WhisperNative.transcribe(handle, pcm, Integer.MIN_VALUE, "pt", null).equals(" café 😀"));
        check(Arrays.equals(copy, pcm));
        check(WhisperNative.transcribe(handle, audio(480000), Integer.MAX_VALUE, "pt", null).equals(" café 😀"));
        for (int mode : new int[]{2, 3, 4}) {
            setMode(mode);
            io(() -> WhisperNative.transcribe(handle, pcm, 4, "pt", null));
        }
        setMode(5);
        check(WhisperNative.transcribe(handle, pcm, 4, "pt", null).equals("bad \ufffd\ufffd"));
        for (int mode : new int[]{6, 7}) {
            setMode(mode);
            // High or NaN no-speech probability alone no longer drops text.
            check(!WhisperNative.transcribe(handle, pcm, 4, "pt", null).isEmpty());
        }
        setMode(0);
        WhisperNative.cancel(handle);
        setMode(0);
        check(WhisperNative.transcribe(handle, pcm, 4, "pt", null).isEmpty());
        check(!entered());
        check(WhisperNative.progress(handle) == 0); // a cancelled call never starts a window
        WhisperNative.close(handle);
        check(WhisperNative.progress(handle) == 0 && WhisperNative.progress(0) == 0);
        WhisperNative.close(handle);
        WhisperNative.cancel(handle);
        WhisperNative.close(0);
        WhisperNative.cancel(Long.MAX_VALUE);
        check(live() == 0);
        io(() -> WhisperNative.transcribe(handle, pcm, 4, "pt", null));
        for (boolean close : new boolean[]{false, true}) {
            long current = open();
            check(current != handle);
            setMode(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    if (!WhisperNative.transcribe(current, audio(16000), 4, "pt", null).isEmpty())
                        failure.set(new AssertionError("Cancelled transcript published"));
                } catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            waitEntered();
            check(WhisperNative.progress(current) == 40); // mid-window progress readable from another thread
            if (close) WhisperNative.close(current); else WhisperNative.cancel(current);
            worker.join(5000);
            check(!worker.isAlive());
            check(failure.get() == null);
            WhisperNative.close(current);
            check(live() == 0);
        }
        // Language: English-only weights stay "en"; multilingual weights take the caller's choice.
        setMode(0);
        long plain = open();
        check(WhisperNative.progress(plain) == 0);
        check(!WhisperNative.transcribe(plain, audio(16000), 4, "pt", null).isEmpty() && language().equals("en") && !vad());
        check(WhisperNative.progress(plain) == 100); // a finished window reads 100
        setMultilingual(true);
        for (String code : new String[]{"pt", "en", "auto"}) {
            check(!WhisperNative.transcribe(plain, audio(16000), 4, code, null).isEmpty() && language().equals(code));
        }
        for (String bad : new String[]{null, "", "de", "pt-BR", "autox", "p\u0000"}) {
            io(() -> WhisperNative.transcribe(plain, audio(16000), 4, bad, null));
        }
        // Vocabulary: passed as Whisper's prompt (UTF-8), empty/null means none, bounded and validated.
        check(!WhisperNative.transcribe(plain, audio(16000), 4, "pt", "Gabriel, Tatá").isEmpty() && prompt().equals("Gabriel, Tatá"));
        check(!WhisperNative.transcribe(plain, audio(16000), 4, "pt", "").isEmpty() && prompt().isEmpty());
        check(!WhisperNative.transcribe(plain, audio(16000), 4, "pt", null).isEmpty() && prompt().isEmpty());
        check(!WhisperNative.transcribe(plain, audio(16000), 4, "pt", "x".repeat(400)).isEmpty() && prompt().length() == 400);
        io(() -> WhisperNative.transcribe(plain, audio(16000), 4, "pt", "x".repeat(401)));
        io(() -> WhisperNative.transcribe(plain, audio(16000), 4, "pt", "bad\ud800word"));
        io(() -> WhisperNative.transcribe(plain, audio(16000), 4, "pt", "nul\u0000word"));
        WhisperNative.close(plain);
        io(() -> WhisperNative.openFile("test-model-😀.bin", ""));
        io(() -> WhisperNative.openFile("test-model-😀.bin", "bad\ud800vad"));
        check(live() == 0);
        long withVad = WhisperNative.openFile("test-model-😀.bin", "vad-😀.bin");
        check(!WhisperNative.transcribe(withVad, audio(16000), 4, "pt", null).isEmpty() && vad() && language().equals("pt"));
        WhisperNative.close(withVad);
        setMultilingual(false);
        check(live() == 0);
        System.out.println("JNI mock-backend safety checks passed: " + checks);
    }
}
