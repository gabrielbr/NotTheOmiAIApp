package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home: sync status per source, "Sync now" and full-text search over everything synced. */
public final class SentientActivity extends Activity {
    private static final int PAPER = 0xfff5f2eb, INK = 0xff191c18, MUTED = 0xff646a61, ACCENT = 0xffbe432b;
    private static final int MAX_HITS = 30;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout page, sources, results;
    private TextView status;
    private EditText query;
    private long shownRevision = -1;
    private boolean visible;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (SyncJobService.revision != shownRevision) refreshSources();
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(24));
        page.setBackgroundColor(PAPER);
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("Sentient", 28, INK, true));
        page.addView(text("Your daily knowledge base, kept encrypted on this phone.", 13, MUTED, false));
        status = text("", 14, INK, false);
        status.setPadding(0, dp(14), 0, dp(6));
        page.addView(status);
        page.addView(button("Sync now", true, v -> SyncJobService.syncNow(this)));

        page.addView(heading("Sources"));
        sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        page.addView(sources);

        page.addView(heading("Search"));
        query = new EditText(this);
        query.setHint("Words from any message or recording");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((TextView v, int action, KeyEvent event) -> { search(); return true; });
        page.addView(query);
        page.addView(button("Search", false, v -> search()));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);

        page.addView(text("Synced once a day while the battery isn't low. Nothing leaves this phone in this "
                + "version: Sentient only reads Omi Tarefas transcripts.", 12, MUTED, false));
        page.addView(button("Licenses", false, v -> showLicenses()));

        SyncJobService.scheduleDaily(this);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        shownRevision = -1;
        main.post(tick);
    }

    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void refreshSources() {
        long revision = SyncJobService.revision;
        shownRevision = revision;
        status.setText(SyncJobService.state);
        io.execute(() -> {
            List<Sources.State> states;
            try {
                Db db = KnowledgeStore.get(this);
                for (br.gabriel.sentient.plugin.SourcePlugin plugin : PluginRegistry.plugins(this))
                    Sources.ensure(db, plugin.id());
                states = Sources.all(db);
            } catch (Exception failure) {
                main.post(() -> status.setText("Store unavailable · " + failure.getClass().getSimpleName()));
                return;
            }
            main.post(() -> {
                if (isDestroyed()) return;
                sources.removeAllViews();
                for (Sources.State s : states) {
                    String when = s.lastSyncAt == null ? "never synced"
                            : "synced " + DateUtils.getRelativeTimeSpanString(s.lastSyncAt);
                    sources.addView(card(PluginRegistry.displayName(this, s.pluginId),
                            s.itemCount + " items · " + when + (s.lastStatus == null ? "" : "\n" + s.lastStatus)));
                }
            });
        });
    }

    private void search() {
        String words = query.getText().toString();
        io.execute(() -> {
            List<Search.Hit> hits;
            try { hits = Search.find(KnowledgeStore.get(this), words, MAX_HITS); }
            catch (Exception failure) {
                main.post(() -> status.setText("Search failed · " + failure.getClass().getSimpleName()));
                return;
            }
            main.post(() -> {
                if (isDestroyed()) return;
                results.removeAllViews();
                if (hits.isEmpty()) results.addView(text("No matches.", 13, MUTED, false));
                for (Search.Hit hit : hits) {
                    String title = (hit.conversation == null ? PluginRegistry.displayName(this, hit.source) : hit.conversation)
                            + " · " + DateUtils.formatDateTime(this, hit.ts,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
                    results.addView(card(title, hit.snippet));
                }
            });
        });
    }

    private void showLicenses() {
        StringBuilder text = new StringBuilder();
        try {
            for (String name : getAssets().list("licenses")) {
                try (InputStream in = getAssets().open("licenses/" + name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                    text.append("— ").append(name).append(" —\n\n")
                            .append(new String(out.toByteArray(), StandardCharsets.UTF_8)).append("\n\n");
                }
            }
        } catch (java.io.IOException failure) {
            text.append("Licenses unavailable.");
        }
        ScrollView scroll = new ScrollView(this);
        TextView body = text(text.toString(), 11, INK, false);
        body.setPadding(dp(18), dp(12), dp(18), dp(12));
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Licenses").setView(scroll).setPositiveButton("Close", null).show();
    }

    private View card(String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(12));
        box.setBackground(background);
        box.addView(text(title, 15, INK, true));
        box.addView(text(body, 13, MUTED, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        box.setLayoutParams(params);
        return box;
    }

    private TextView heading(String value) {
        TextView view = text(value, 18, INK, true);
        view.setPadding(0, dp(22), 0, dp(4));
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : INK);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? ACCENT : Color.WHITE);
        background.setCornerRadius(dp(24));
        button.setBackground(background);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
