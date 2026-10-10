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
            "Reads GVoice transcripts, and WhatsApp and Signal notifications once you allow it. Nothing leaves the phone.",
            "Read-only: never sends messages or acts for you.",
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
        content.addView(Ui.listRow(this, "Open-source licenses", null, false,
                v -> startActivity(new Intent(this, LicensesActivity.class))));
        content.addView(Ui.divider(this));
    }

    static String versionName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException missing) { return ""; }
    }
}
