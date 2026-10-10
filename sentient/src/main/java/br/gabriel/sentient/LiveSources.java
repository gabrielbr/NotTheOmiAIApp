package br.gabriel.sentient;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import br.gabriel.sentient.plugin.SourcePlugin;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads every source's state off the main thread, again on resume and whenever a sync changes
 * something, while the screen is visible. Call resume/pause/destroy from the activity's own.
 */
final class LiveSources {
    interface Listener {
        void onSources(List<Sources.State> states);
        void onStoreError(Exception failure);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long shownRevision = -1;
    private boolean visible, destroyed;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (SyncJobService.revision != shownRevision) reload();
            main.postDelayed(this, 1000);
        }
    };

    LiveSources(Context context, Listener listener) { this.context = context; this.listener = listener; }

    void resume() {
        visible = true;
        shownRevision = -1; // notification access may have changed while away
        main.post(tick);
    }

    void pause() {
        visible = false;
        main.removeCallbacks(tick);
    }

    void destroy() {
        destroyed = true;
        main.removeCallbacks(tick);
        io.shutdownNow();
    }

    void reload() {
        shownRevision = SyncJobService.revision;
        io.execute(() -> {
            List<Sources.State> states;
            try {
                Db db = KnowledgeStore.get(context);
                for (SourcePlugin plugin : PluginRegistry.plugins(context)) Sources.ensure(db, plugin.id());
                states = Sources.all(db);
            } catch (Exception failure) {
                main.post(() -> { if (!destroyed) listener.onStoreError(failure); });
                return;
            }
            main.post(() -> { if (!destroyed) listener.onSources(states); });
        });
    }
}
