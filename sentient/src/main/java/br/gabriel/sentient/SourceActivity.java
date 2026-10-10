package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** One source: its status, the one action that fixes a problem, what it saves and, for synced
 * sources, Sync now. The setup text lives here so the home page doesn't carry it. */
public final class SourceActivity extends Activity implements LiveSources.Listener {
    static final String EXTRA_PLUGIN_ID = "plugin_id";

    private LiveSources sources;
    private LinearLayout content;
    private String pluginId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pluginId = getIntent().getStringExtra(EXTRA_PLUGIN_ID);
        content = Ui.secondaryPage(this);
        sources = new LiveSources(this, this);
    }

    @Override protected void onResume() { super.onResume(); sources.resume(); }
    @Override protected void onPause() { sources.pause(); super.onPause(); }
    @Override protected void onDestroy() { sources.destroy(); super.onDestroy(); }

    @Override public void onSources(List<Sources.State> states) {
        for (Sources.State s : states) if (s.pluginId.equals(pluginId)) { show(s); return; }
        finish(); // the source no longer exists
    }

    @Override public void onStoreError(Exception failure) {
        content.removeAllViews();
        content.addView(Ui.text(this, "Can't open your knowledge base.", 20, Ui.INK, true));
    }

    void show(Sources.State s) {
        content.removeAllViews();
        boolean access = ChatPlugin.accessGranted(this);
        boolean problem = SourceStatus.problem(s, access);
        String name = PluginRegistry.displayName(this, s.pluginId);
        content.addView(Ui.title(this, name, name));
        Ui.gap(content, 16);

        TextView chip = problem ? Ui.chip(this, "Needs attention", true)
                : s.itemCount > 0 || s.lastSyncAt != null ? Ui.chip(this, "Working", false) : null;
        if (chip != null) content.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        TextView meta = Ui.text(this, SourceStatus.meta(s), 14, Ui.MUTED, false);
        meta.setPadding(0, chip != null ? Ui.dp(this, 10) : 0, 0, 0);
        content.addView(meta);

        if (problem) {
            TextView reason = Ui.text(this, SourceStatus.reason(s, access), 15, Ui.CORAL_TEXT, false);
            reason.setPadding(0, Ui.dp(this, 14), 0, 0);
            content.addView(reason);
            Button fix = fixButton(s, access);
            if (fix != null) {
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
                bp.topMargin = Ui.dp(this, 14);
                content.addView(fix, bp);
            }
            if (SourceStatus.needsAccess(s, access)) {
                TextView shared = Ui.text(this, "One access covers WhatsApp and Signal.", 13, Ui.MUTED, false);
                shared.setPadding(0, Ui.dp(this, 8), 0, 0);
                content.addView(shared);
            }
        }

        Ui.gap(content, 28);
        content.addView(Ui.text(this, "What it saves", 17, Ui.INK, true));
        TextView saves = Ui.text(this, SourceStatus.saves(s.pluginId), 15, Ui.MUTED, false);
        saves.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(saves);

        if (!SourceStatus.live(s)) {
            // Chat apps save messages as they arrive; only synced sources have a sync to run.
            Ui.gap(content, 28);
            content.addView(Ui.divider(this));
            Ui.gap(content, 20);
            boolean busy = SyncJobService.busy();
            String line = busy ? "Syncing…" : s.lastSyncAt == null ? "Not synced yet · syncs daily"
                    : "Synced " + SourceStatus.ago(s.lastSyncAt) + " · daily";
            content.addView(Ui.text(this, line, 14, Ui.MUTED, false));
            Ui.gap(content, 12);
            Button sync = Ui.button(this, busy ? "Syncing…" : "Sync now", Ui.Style.PRIMARY, v -> {
                SyncJobService.syncNow(this);
                sources.reload();
            });
            sync.setEnabled(!busy);
            content.addView(sync, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** The one action that fixes the problem, when there is one. */
    private Button fixButton(Sources.State s, boolean access) {
        if (SourceStatus.needsAccess(s, access))
            return Ui.button(this, "Allow notification access", Ui.Style.DARK, v -> startActivity(ChatPlugin.accessSettings()));
        ChatMessages.App app = ChatMessages.App.forId(s.pluginId);
        if (app != null && s.notice != null) {
            Intent open = null;
            for (String pkg : app.packages) if ((open = getPackageManager().getLaunchIntentForPackage(pkg)) != null) break;
            if (open == null) return null;
            Intent launch = open;
            return Ui.button(this, "Open " + app.displayName, Ui.Style.DARK, v -> startActivity(launch));
        }
        return null; // a failed sync: Sync now, further down, is the fix
    }
}
