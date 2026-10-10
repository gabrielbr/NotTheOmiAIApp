package br.gabriel.sentient;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The license files shipped in assets/licenses, one after another. */
public final class LicensesActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = Ui.secondaryPage(this);
        content.addView(Ui.title(this, "Licenses", "Licenses"));
        Ui.gap(content, 20);
        try {
            for (String name : getAssets().list("licenses")) {
                content.addView(Ui.divider(this));
                TextView title = Ui.text(this, name, 15, Ui.INK, true);
                title.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
                content.addView(title);
                TextView body = Ui.text(this, reflow(read("licenses/" + name)), 13, Ui.MUTED, false);
                body.setPadding(0, 0, 0, Ui.dp(this, 14));
                content.addView(body);
            }
        } catch (IOException failure) {
            content.addView(Ui.text(this, "License files could not be opened.", 15, Ui.CORAL_TEXT, false));
        }
    }

    /** License files are hard-wrapped at about 80 columns, which wraps raggedly on a phone. Join the
     * lines of each paragraph; the words are unchanged. */
    static String reflow(String text) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.replace("\r\n", "\n").split("\n[ \t]*\n")) {
            String joined = paragraph.trim().replaceAll("[ \t]*\n[ \t]*", " ");
            if (joined.isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(joined);
        }
        return out.toString();
    }

    private String read(String asset) throws IOException {
        try (InputStream in = getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
