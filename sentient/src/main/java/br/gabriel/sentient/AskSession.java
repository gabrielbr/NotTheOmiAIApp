package br.gabriel.sentient;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import br.gabriel.sentient.plugin.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Ask conversation, owned by the app rather than the screen: leaving Ask and coming back keeps
 * the chat, and a question still being answered keeps going (only Stop cancels it). Finished
 * exchanges are saved in the encrypted store, so the chat also survives the app restarting.
 */
final class AskSession {
    static final String META_KEY = "ask.chat";
    static final int MAX_SAVED = 40, HISTORY_SENT = 10;

    /** One question and what came back. Fields change only on the main thread. */
    static final class Exchange {
        final String question;
        String answer, notice, error, status, partial = "";
        List<Long> ids = new ArrayList<>();
        List<Items.Item> sources = new ArrayList<>();
        boolean running;
        Exchange(String question) { this.question = question; }
        boolean answered() { return answer != null; }
    }

    interface Observer { void changed(); }

    private static AskSession instance;

    static synchronized AskSession get(Context context) {
        if (instance == null) instance = new AskSession(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Exchange> exchanges = new ArrayList<>();
    private Observer observer;
    private boolean loaded;
    private volatile boolean cancelled;

    private AskSession(Context context) { this.context = context; }

    List<Exchange> exchanges() { return Collections.unmodifiableList(exchanges); }

    boolean running() { return !exchanges.isEmpty() && exchanges.get(exchanges.size() - 1).running; }

    /** The screen showing the chat, or null when none is. */
    void observe(Observer o) {
        observer = o;
        if (o != null && !loaded) load();
    }

    private void notifyChanged() { if (observer != null) observer.changed(); }

    /** Reads the saved chat once per process, off the main thread. */
    private void load() {
        loaded = true;
        worker.execute(() -> {
            List<Exchange> saved = new ArrayList<>();
            try {
                Db db = KnowledgeStore.get(context);
                saved = decode(Meta.get(db, META_KEY));
                for (Exchange e : saved) {
                    for (Long id : e.ids) { Items.Item item = Items.get(db, id); if (item != null) e.sources.add(item); }
                }
            } catch (Exception unreadable) { /* start with an empty chat */ }
            final List<Exchange> found = saved;
            main.post(() -> restore(found));
        });
    }

    /** Puts saved exchanges before anything asked since the app started. Main thread. */
    void restore(List<Exchange> saved) {
        loaded = true;
        exchanges.addAll(0, saved);
        notifyChanged();
    }

    /** Tests only: a fresh session. */
    static synchronized void resetForTest() { instance = null; }

    void ask(String question) {
        if (running()) return;
        Exchange e = new Exchange(question);
        e.running = true;
        e.status = "Thinking…";
        exchanges.add(e);
        cancelled = false;
        List<LlmBackend.Turn> past = new ArrayList<>();
        for (Exchange done : exchanges) if (done != e && done.answered()) past.add(new LlmBackend.Turn(done.question, done.answer));
        // A saved chat can be long; the model only needs the recent turns.
        if (past.size() > HISTORY_SENT) past = new ArrayList<>(past.subList(past.size() - HISTORY_SENT, past.size()));
        final List<LlmBackend.Turn> recent = past;
        notifyChanged();
        worker.execute(() -> answer(e, recent));
    }

    void stop() { cancelled = true; }

    /** Starts over: forgets the chat here and in the store. A running answer is stopped. */
    void clear() {
        cancelled = true;
        exchanges.clear();
        worker.execute(() -> {
            try { Meta.set(KnowledgeStore.get(context), META_KEY, null); } catch (Exception ignored) { /* nothing saved */ }
        });
        notifyChanged();
    }

    private void answer(Exchange e, List<LlmBackend.Turn> past) {
        LlmBackend.Answer answer = null;
        String error = null;
        List<Long> cited = new ArrayList<>();
        List<Items.Item> sources = new ArrayList<>();
        try {
            LlmBackend backend = AskBackends.create(context);
            answer = backend.answer(past, e.question, new LlmBackend.Listener() {
                @Override public void status(String s) { main.post(() -> { e.status = s; notifyChanged(); }); }
                @Override public void partial(String s) { main.post(() -> { e.partial = s; notifyChanged(); }); }
            }, () -> cancelled);
            Db db = KnowledgeStore.get(context);
            cited = Citations.existing(db, Citations.ids(answer.text));
            for (Long id : cited) { Items.Item item = Items.get(db, id); if (item != null) sources.add(item); }
        } catch (ClaudeBackend.AskException failed) {
            error = failed.getMessage();
        } catch (Exception failed) {
            error = "Something went wrong (" + failed.getClass().getSimpleName() + ").";
        }
        final LlmBackend.Answer done = answer;
        final String problem = error;
        final List<Long> ids = cited;
        final List<Items.Item> found = sources;
        main.post(() -> {
            e.running = false;
            e.status = null;
            if (!exchanges.contains(e)) return; // the chat was cleared meanwhile
            if (done == null) e.error = problem;
            else { e.answer = done.text; e.notice = done.notice; e.ids = ids; e.sources = found; }
            notifyChanged();
            String saved = encode(exchanges);
            worker.execute(() -> {
                try { Meta.set(KnowledgeStore.get(context), META_KEY, saved); } catch (Exception ignored) { /* kept in memory */ }
            });
        });
    }

    /** The last MAX_SAVED finished exchanges as JSON. */
    static String encode(List<Exchange> all) {
        List<Object> out = new ArrayList<>();
        for (Exchange e : all) {
            if (e.running) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("q", e.question);
            if (e.answer != null) m.put("a", e.answer);
            if (e.notice != null) m.put("notice", e.notice);
            if (e.error != null) m.put("error", e.error);
            m.put("ids", new ArrayList<Object>(e.ids));
            out.add(m);
        }
        if (out.size() > MAX_SAVED) out = out.subList(out.size() - MAX_SAVED, out.size());
        return Json.write(out);
    }

    static List<Exchange> decode(String json) {
        List<Exchange> out = new ArrayList<>();
        if (json == null) return out;
        try {
            for (Object o : Json.list(Json.parse(json))) {
                String q = Json.str(Json.at(o, "q"));
                if (q == null) continue;
                Exchange e = new Exchange(q);
                e.answer = Json.str(Json.at(o, "a"));
                e.notice = Json.str(Json.at(o, "notice"));
                e.error = Json.str(Json.at(o, "error"));
                for (Object id : Json.list(Json.at(o, "ids"))) { Long n = Json.num(id); if (n != null) e.ids.add(n); }
                out.add(e);
            }
        } catch (IllegalArgumentException corrupt) { out.clear(); }
        return out;
    }
}
