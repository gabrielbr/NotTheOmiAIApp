package br.gabriel.sentient;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Gemini Nano, the model Android itself runs on some phones (AICore, through ML Kit's Prompt API).
 * Nothing to download into GMind: Android manages the model. Only phones Google lists support it
 * (not the Pixel 9a today), and Android only lets the app on screen use it; GMind falls back to
 * Qwen otherwise. The ML Kit classes are touched only inside {@link MlKit}.
 */
final class Nano {
    /** ML Kit FeatureStatus values. */
    static final int UNAVAILABLE = 0, DOWNLOADABLE = 1, DOWNLOADING = 2, AVAILABLE = 3, UNKNOWN = -1;
    /** ML Kit GenAiException error codes GMind reacts to. */
    static final int BUSY = 9, REQUEST_TOO_LARGE = 12, QUOTA = 27, BACKGROUND_BLOCKED = 30, NOT_AVAILABLE = 8,
            NOT_SUPPORTED = 16, CANCELLED = 7;

    /** What GMind needs from the model; a seam so tests run without Android's AICore. */
    interface Model {
        int status() throws Exception;
        /** Asks Android to fetch the model; returns at once. */
        void download();
        /** The answer, streaming each new piece to {@code piece}. Throws {@link Failure} with ML Kit's code. */
        String generate(String prompt, int maxTokens, Consumer<String> piece, BooleanSupplier cancelled) throws Exception;
    }

    /** An ML Kit error, by its code. */
    static final class Failure extends Exception {
        final int code;
        Failure(int code, Throwable cause) { super("Gemini Nano error " + code, cause); this.code = code; }
    }

    private static volatile Model model;
    private static volatile int status = UNKNOWN;
    private static volatile long checkedAt;
    private static final long FRESH_MS = 60_000;

    private Nano() {}

    /** For tests: a fake model (null restores the real one). */
    static void use(Model fake) { model = fake; status = UNKNOWN; checkedAt = 0; }

    static Model model() {
        Model m = model;
        if (m == null) synchronized (Nano.class) { if (model == null) model = new MlKit(); m = model; }
        return m;
    }

    /** The last known status, without waiting (UNKNOWN before the first check). Any thread. */
    static int cached() { return status; }

    /**
     * Checks Android's status for Gemini Nano (at most once a minute), starting Android's model
     * download when it's offered. Blocks briefly; not on the main thread.
     */
    static int status() {
        long now = System.currentTimeMillis();
        if (status != UNKNOWN && now - checkedAt < FRESH_MS) return status;
        int s;
        try { s = model().status(); }
        catch (Exception | LinkageError unsupported) { s = UNAVAILABLE; } // no AICore, unlocked bootloader, …
        if (s == DOWNLOADABLE) {
            try { model().download(); s = DOWNLOADING; } catch (Exception | LinkageError ignored) { }
        }
        status = s;
        checkedAt = now;
        return s;
    }

    /** Status from a background thread, for screens. */
    static void refresh(Runnable then) {
        new Thread(() -> { checkedAt = 0; status(); if (then != null) then.run(); }, "gmind-nano-status").start();
    }

    static String describe(int s) {
        switch (s) {
            case AVAILABLE: return "Gemini Nano (built into this phone)";
            case DOWNLOADABLE: case DOWNLOADING: return "Gemini Nano is being set up by Android; Qwen answers until it's ready";
            case UNKNOWN: return "Checking for Gemini Nano…";
            default: return "Gemini Nano isn't available on this phone, so Qwen answers";
        }
    }

    /** The real model, through ML Kit's Prompt API (Java futures). */
    private static final class MlKit implements Model {
        private final com.google.mlkit.genai.prompt.java.GenerativeModelFutures futures =
                com.google.mlkit.genai.prompt.java.GenerativeModelFutures.from(
                        com.google.mlkit.genai.prompt.Generation.INSTANCE.getClient());

        @Override public int status() throws Exception {
            return futures.checkStatus().get(10, TimeUnit.SECONDS);
        }

        @Override public void download() {
            futures.download(new com.google.mlkit.genai.common.DownloadCallback() { });
        }

        @Override public String generate(String prompt, int maxTokens, Consumer<String> piece, BooleanSupplier cancelled)
                throws Exception {
            com.google.mlkit.genai.prompt.GenerateContentRequest.Builder builder =
                    new com.google.mlkit.genai.prompt.GenerateContentRequest.Builder(
                            new com.google.mlkit.genai.prompt.TextPart(prompt));
            builder.setMaxOutputTokens(maxTokens);
            builder.setTemperature(0.3f);
            Future<com.google.mlkit.genai.prompt.GenerateContentResponse> pending =
                    futures.generateContent(builder.build(), piece::accept);
            while (true) {
                if (cancelled.getAsBoolean()) { pending.cancel(true); throw new Failure(CANCELLED, null); }
                try {
                    com.google.mlkit.genai.prompt.GenerateContentResponse response = pending.get(200, TimeUnit.MILLISECONDS);
                    StringBuilder text = new StringBuilder();
                    for (com.google.mlkit.genai.prompt.Candidate c : response.getCandidates())
                        if (c.getText() != null) { text.append(c.getText()); break; }
                    return text.toString();
                } catch (TimeoutException stillWriting) {
                    // poll for Stop
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof com.google.mlkit.genai.common.GenAiException)
                        throw new Failure(((com.google.mlkit.genai.common.GenAiException) cause).getErrorCode(), cause);
                    throw failed;
                }
            }
        }
    }

}
