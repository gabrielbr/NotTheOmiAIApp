package br.gabriel.sentient;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Answers on the phone with Qwen2.5 1.5B through llama.cpp. GMind finds the sources first
 * (LocalPrompt), then the model writes the answer, streamed to the screen. Nothing leaves the phone.
 */
final class LocalBackend implements LlmBackend {
    static final int CONTEXT = 4096, MAX_ANSWER = 384, SOURCE_BUDGET = 7000;
    private static final Object LOCK = new Object();
    private static long handle;
    private static String loadedPath;
    private final Context context;
    private final KnowledgeTools tools;

    LocalBackend(Context context, Db db) {
        this.context = context.getApplicationContext();
        this.tools = new KnowledgeTools(db, ZoneId.systemDefault());
    }

    @Override public String name() { return LocalModel.NAME + " on this phone"; }

    @Override public Answer answer(List<Turn> history, String question, Listener listener, BooleanSupplier cancelled)
            throws Exception {
        listener.status("Finding related messages…");
        String sources = LocalPrompt.sources(tools, question, SOURCE_BUDGET);
        if (cancelled.getAsBoolean()) throw new ClaudeBackend.AskException("Stopped.");
        synchronized (LOCK) {
            long h = load(listener);
            listener.status("Writing the answer on this phone…");
            StringBuilder text = new StringBuilder();
            int result = generate(h, LocalPrompt.chat(sources, history, question), text, listener, cancelled);
            if (result == LlamaNative.TOO_LONG) {
                // Long history or sources: retry once with half the sources and no history.
                text.setLength(0);
                String fewer = sources.substring(0, Math.min(sources.length(), SOURCE_BUDGET / 2));
                fewer = fewer.substring(0, Math.max(0, fewer.lastIndexOf('\n') + 1));
                result = generate(h, LocalPrompt.chat(fewer, java.util.Collections.emptyList(), question), text, listener, cancelled);
            }
            if (result < 0) throw new ClaudeBackend.AskException("The on-device model couldn't answer (" + result + ").");
            if (cancelled.getAsBoolean()) return new Answer(text.toString().trim(), "Stopped.");
            return new Answer(text.toString().trim(), result >= MAX_ANSWER ? "The answer was cut off." : null);
        }
    }

    private int generate(long h, String prompt, StringBuilder text, Listener listener, BooleanSupplier cancelled) {
        ByteArrayOutputStream utf8 = new ByteArrayOutputStream();
        return LlamaNative.generate(h, prompt.getBytes(StandardCharsets.UTF_8), MAX_ANSWER, 0.3f, 42, chunk -> {
            utf8.write(chunk, 0, chunk.length);
            text.setLength(0);
            text.append(new String(utf8.toByteArray(), StandardCharsets.UTF_8));
            listener.partial(text.toString());
            return !cancelled.getAsBoolean();
        });
    }

    /** Loads the model once and keeps it while the app runs; reloading takes a few seconds. */
    private long load(Listener listener) throws ClaudeBackend.AskException {
        String path = LocalModel.file(context).getPath();
        if (handle != 0 && path.equals(loadedPath)) return handle;
        if (!LocalModel.ready(context)) throw new ClaudeBackend.AskException("Download the on-device model first.");
        listener.status("Loading the model…");
        LlamaNative.loadLibrary();
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        handle = LlamaNative.load(path, CONTEXT, threads);
        if (handle == 0) throw new ClaudeBackend.AskException("The on-device model couldn't be loaded. Try deleting and downloading it again.");
        loadedPath = path;
        return handle;
    }

    /** Frees the model's memory (e.g. before deleting it). */
    static void release() {
        synchronized (LOCK) {
            if (handle != 0) LlamaNative.close(handle);
            handle = 0;
            loadedPath = null;
        }
    }
}
