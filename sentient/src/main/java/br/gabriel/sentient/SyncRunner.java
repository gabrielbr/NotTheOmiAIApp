package br.gabriel.sentient;

import br.gabriel.sentient.plugin.PluginContext;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;
import br.gabriel.sentient.plugin.SourcePlugin;
import br.gabriel.sentient.plugin.SourceUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Pulls every enabled plugin. Each page commits its items and the plugin's next cursor in
 * one transaction, so a crash or cancel never skips items: the next run resumes from the
 * last committed cursor. One failing plugin never stops the others.
 */
public final class SyncRunner {
    static final int MAX_PAGES = 50;

    private SyncRunner() {}

    public static final class Outcome {
        public final String pluginId, status;
        public final int added, updated;
        public final boolean failed;
        Outcome(String pluginId, String status, int added, int updated, boolean failed) {
            this.pluginId = pluginId; this.status = status; this.added = added; this.updated = updated;
            this.failed = failed;
        }
    }

    public interface Clock { long now(); }

    public static List<Outcome> run(Db db, List<SourcePlugin> plugins, BooleanSupplier cancelled, Clock clock)
            throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (SourcePlugin plugin : plugins) {
            if (cancelled.getAsBoolean()) break;
            Sources.State state = Sources.ensure(db, plugin.id());
            if (!state.enabled) continue;
            outcomes.add(pullAll(db, plugin, state.cursor, cancelled, clock));
        }
        return outcomes;
    }

    private static Outcome pullAll(Db db, SourcePlugin plugin, String cursor, BooleanSupplier cancelled,
                                   Clock clock) throws Exception {
        String id = plugin.id();
        PluginContext context = new PluginContext() {
            @Override public String config(String key) {
                try { return Sources.config(db, id, key); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            @Override public boolean cancelled() { return cancelled.getAsBoolean(); }
        };
        int added = 0, updated = 0;
        String status;
        boolean failed = false;
        try {
            for (int page = 0; ; page++) {
                PullResult result = plugin.pull(context, cursor);
                for (RawItem item : result.items)
                    if (!id.equals(item.source)) throw new IllegalStateException("Plugin returned another source's item");
                final String next = result.nextCursor;
                Ingest.Stats stats = db.transaction(() -> {
                    Ingest.Stats s = Ingest.upsert(db, result.items, clock.now());
                    Sources.advance(db, id, next);
                    return s;
                });
                cursor = next;
                added += stats.added;
                updated += stats.updated;
                if (!result.hasMore || cancelled.getAsBoolean() || page + 1 >= MAX_PAGES) break;
            }
            status = "OK · " + added + " new, " + updated + " updated";
        } catch (SourceUnavailableException unavailable) {
            status = "Unavailable · " + unavailable.getMessage();
            failed = true;
        } catch (Exception failure) {
            // Class name only: messages from sources can contain private content.
            status = "Failed · " + failure.getClass().getSimpleName();
            failed = true;
        }
        Sources.finish(db, id, clock.now(), status);
        return new Outcome(id, status, added, updated, failed);
    }
}
