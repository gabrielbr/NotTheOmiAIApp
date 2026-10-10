package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import java.util.Collections;
import java.util.List;

/**
 * Everything that isn't finding things: one row per entry, each opening its own screen.
 * Entries are added as their features land (Chats to monitor, Your name, Connections, Updates);
 * none is shown before it works. See PLAN-GMIND-UI.md.
 */
public final class SettingsActivity extends Activity implements LiveSources.Listener {
    private LiveSources sources;
    private LinearLayout rows;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = Ui.secondaryPage(this);

        content.addView(Ui.title(this, "Settings", "Settings"));
        Ui.gap(content, 20);
        rows = Ui.column(this);
        content.addView(rows);
        showRows(Collections.emptyList());
        sources = new LiveSources(this, this);
    }

    @Override protected void onResume() { super.onResume(); sources.resume(); }
    @Override protected void onPause() { sources.pause(); super.onPause(); }
    @Override protected void onDestroy() { sources.destroy(); super.onDestroy(); }

    @Override public void onSources(List<Sources.State> states) { showRows(states); }
    @Override public void onStoreError(Exception failure) { showRows(Collections.emptyList()); }

    void showRows(List<Sources.State> states) {
        rows.removeAllViews();
        if (!states.isEmpty()) {
            rows.addView(Ui.text(this, "Sources", 14, Ui.MUTED, true));
            Ui.gap(rows, 8);
            boolean access = ChatPlugin.accessGranted(this);
            for (Sources.State s : states) {
                rows.addView(Ui.divider(this));
                rows.addView(sourceRow(this, s, access));
            }
            rows.addView(Ui.divider(this));
            Ui.gap(rows, 28);
        }
        rows.addView(Ui.divider(this));
        rows.addView(Ui.listRow(this, "About", AboutActivity.versionName(this), false,
                v -> startActivity(new Intent(this, AboutActivity.class))));
        rows.addView(Ui.divider(this));
    }

    /** A source in a list: name and a short status; opens its own screen. */
    static LinearLayout sourceRow(Activity a, Sources.State s, boolean access) {
        return Ui.listRow(a, PluginRegistry.displayName(a, s.pluginId), SourceStatus.summary(s, access),
                SourceStatus.problem(s, access),
                v -> a.startActivity(new Intent(a, SourceActivity.class).putExtra(SourceActivity.EXTRA_PLUGIN_ID, s.pluginId)));
    }
}
