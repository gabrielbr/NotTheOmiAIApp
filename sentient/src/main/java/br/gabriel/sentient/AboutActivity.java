package br.gabriel.sentient;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Version, what GMind does with your data, and the open-source licenses. */
public final class AboutActivity extends Activity {
    static final String[] POINTS = {
            "Encrypted on this phone. Uninstalling or clearing its data deletes it.",
            "Syncs once a day while the battery isn't low, or when you tap Sync now.",
            "Reads GVoice transcripts, and WhatsApp, Signal and Telegram notifications once you allow it.",
            "Sources you connect are read from their servers: Matrix from your homeserver; Gmail, Calendar, Drive, Slack, Todoist and TickTick through Composio.",
            "Nothing you collected leaves the phone, except what you ask Claude (or AI enrichment, if on), the vault if you export it to Google Drive, and to-dos you send to Todoist.",
            "Never sends messages or acts for you.",
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = Ui.secondaryPage(this);
        content.addView(Ui.title(this, "GMind", "GMind"));
        TextView version = Ui.text(this, "Version " + versionName(this), 14, Ui.MUTED, false);
        version.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(version);
        Ui.gap(content, 24);
        for (String point : POINTS) {
            content.addView(Ui.divider(this));
            TextView t = Ui.text(this, point, 15, Ui.INK, false);
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            content.addView(t);
        }
        content.addView(Ui.divider(this));
        Ui.gap(content, 28);
        content.addView(Ui.divider(this));
        content.addView(Ui.listRow(this, "Updates", null, false,
                v -> startActivity(new Intent(this, UpdatesActivity.class))));
        content.addView(Ui.divider(this));
        content.addView(Ui.listRow(this, "Open-source licenses", null, false,
                v -> startActivity(new Intent(this, LicensesActivity.class))));
        content.addView(Ui.divider(this));
    }

    static String versionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException missing) { return ""; }
    }
}
