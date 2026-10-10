package br.gabriel.sentient;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The real JNI, built for the host, running the pinned tiny test model. Not an accuracy test. */
public final class LlamaNativeHostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        String model = args[1];
        int pinned = LlamaNative.pinFastCores();
        check(pinned >= 0 && pinned <= Runtime.getRuntime().availableProcessors(), "pinning to fast cores is safe: " + pinned);
        String g4 = "processor\t: 0\nFeatures\t: fp asimd aes asimdhp asimddp i8mm bf16\n\nprocessor\t: 7\nFeatures\t: fp asimd asimdhp asimddp i8mm\n";
        check(LlamaNative.fastCpu(g4), "Tensor G4 features pick the fast build");
        check(!LlamaNative.fastCpu(g4.replace(" i8mm bf16", " bf16")), "a core without i8mm keeps the compatible build");
        check(!LlamaNative.fastCpu("processor\t: 0\nFeatures\t: fp asimd\n") && !LlamaNative.fastCpu(null) && !LlamaNative.fastCpu(""),
                "older or unknown CPUs keep the compatible build");
        check(LlamaNative.load("/nonexistent/model.gguf", 256, 2) == 0, "missing model gives 0");
        check(LlamaNative.load(model, 16, 2) == 0, "context below the minimum refused");
        long h = LlamaNative.load(model, 256, 2);
        check(h != 0, "tiny model loads");

        List<byte[]> chunks = new ArrayList<>();
        int n = LlamaNative.generate(h, utf8("Once upon a time"), 24, 0f, 1, chunk -> { chunks.add(chunk); return true; });
        check(n > 0 && n <= 24, "generates up to max tokens: " + n);
        StringBuilder text = new StringBuilder();
        for (byte[] c : chunks) text.append(strictUtf8(c));
        check(text.toString().trim().length() > 0, "streams text: " + text);

        StringBuilder again = new StringBuilder();
        LlamaNative.generate(h, utf8("Once upon a time"), 24, 0f, 1, chunk -> { again.append(new String(chunk, StandardCharsets.UTF_8)); return true; });
        check(again.toString().equals(text.toString()), "greedy is repeatable, and each call starts fresh");

        int[] calls = {0};
        int stopped = LlamaNative.generate(h, utf8("Once upon a time"), 24, 0.7f, 7, chunk -> ++calls[0] < 2);
        check(calls[0] == 2 && stopped <= 3, "stops when the sink says so");

        StringBuilder longPrompt = new StringBuilder();
        for (int i = 0; i < 300; i++) longPrompt.append("story ");
        check(LlamaNative.generate(h, utf8(longPrompt.toString()), 24, 0f, 1, c -> true) == LlamaNative.TOO_LONG,
                "prompt plus answer over the context is refused");
        check(LlamaNative.generate(h, utf8("x"), 0, 0f, 1, c -> true) == LlamaNative.BAD_INPUT, "max tokens 0 refused");
        check(LlamaNative.generate(0, utf8("x"), 8, 0f, 1, c -> true) == LlamaNative.BAD_INPUT, "null handle refused");
        try {
            LlamaNative.generate(h, utf8("Once"), 8, 0f, 1, c -> { throw new IllegalStateException("boom"); });
            check(false, "sink exception propagates");
        } catch (IllegalStateException expected) {
            check(true, "sink exception propagates and stops generation");
        }
        check(LlamaNative.generate(h, utf8("Once upon a time"), 4, 0f, 1, c -> true) > 0, "usable after a sink exception");
        // Reading the prompt reports progress and can be stopped before any answer.
        StringBuilder longish = new StringBuilder();
        for (int i = 0; i < 18; i++) longish.append("Once upon a time there was a cat. "); // ~150 tokens: two read steps, under the 256 context
        int[] steps = {0}; int[] last = {0, 0};
        check(LlamaNative.generate(h, utf8(longish.toString()), 4, 0f, 1, new LlamaNative.TokenSink() {
            public boolean accept(byte[] c) { return true; }
            public boolean progress(int done, int total) { steps[0]++; last[0] = done; last[1] = total; return true; }
        }) > 0, "answers after reading the prompt");
        check(steps[0] >= 2 && last[0] == last[1], "progress reported per chunk, ending at the full prompt");
        byte[][] got = {null};
        check(LlamaNative.generate(h, utf8(longish.toString()), 8, 0f, 1, new LlamaNative.TokenSink() {
            public boolean accept(byte[] c) { got[0] = c; return true; }
            public boolean progress(int done, int total) { return false; }
        }) == 0 && got[0] == null, "stop while reading the prompt: no answer, 0 tokens");
        check(LlamaNative.generate(h, utf8("Once upon a time"), 4, 0f, 1, c -> true) > 0, "usable after stopping mid-prompt");
        LlamaNative.close(h);
        LlamaNative.close(0);
        check(true, "close is safe, including on 0");
        System.out.println("PASS_LLAMA_NATIVE_HOST_CHECKS " + checks);
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** Every chunk must be complete, valid UTF-8. */
    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
