package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Everything that isn't finding things: one row per entry, each opening its own screen.
 * Entries are added as their features land (Sources, Chats to monitor, Your name, Connections,
 * Updates); none is shown before it works. See PLAN-GMIND-UI.md.
 */
public final class SettingsActivity extends Activity {
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
        showRows();
    }

    void showRows() {
        rows.removeAllViews();
        rows.addView(Ui.divider(this));
        rows.addView(Ui.listRow(this, "About", versionName(), false, v -> about()));
        rows.addView(Ui.divider(this));
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
