package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        setContentView(page);
        page.addView(Ui.backBar(this, null));
        page.addView(Ui.divider(this));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

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
        rows.addView(Ui.listRow(this, "About", versionName(), false, v -> about()));
        rows.addView(Ui.divider(this));
    }

    /** A source in a list: name and a short status; opens its own screen. */
    static LinearLayout sourceRow(Activity a, Sources.State s, boolean access) {
        return Ui.listRow(a, PluginRegistry.displayName(a, s.pluginId), SourceStatus.summary(s, access),
                SourceStatus.problem(s, access),
                v -> a.startActivity(new Intent(a, SourceActivity.class).putExtra(SourceActivity.EXTRA_PLUGIN_ID, s.pluginId)));
    }

    // --- About ---------------------------------------------------------------------------------

    private void about() {
        new AlertDialog.Builder(this).setTitle("GMind " + versionName())
                .setMessage("Your knowledge base: everything GMind collects, searchable in one place.\n\n"
                        + "• Encrypted on this phone. Uninstalling or clearing the app's data deletes it.\n"
                        + "• Syncs once a day while the battery isn't low, or when you tap Sync now.\n"
                        + "• It reads your GVoice transcripts, and WhatsApp and Signal messages from their notifications once you allow access. In this version nothing leaves the phone.\n"
                        + "• Read-only: GMind never sends messages or acts for you.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Licenses", (d, w) -> licenses())
                .show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException missing) { return ""; }
    }

    private void licenses() {
        StringBuilder text = new StringBuilder();
        try {
            for (String name : getAssets().list("licenses")) {
                try (InputStream in = getAssets().open("licenses/" + name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                    text.append(name).append("\n\n").append(new String(out.toByteArray(), StandardCharsets.UTF_8))
                            .append("\n\n");
                }
            }
        } catch (java.io.IOException failure) {
            text.append("License files could not be opened.");
        }
        TextView t = Ui.text(this, text.toString(), 12, Ui.INK, false);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Close", null).show();
    }
}
