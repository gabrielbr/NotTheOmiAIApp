package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import br.gabriel.sentient.plugin.SourcePlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home: search everything synced; with no query, the sources and "Sync now". */
public final class SentientActivity extends Activity {
    static final int MAX_HITS = 30;
    private static final String OK = "OK";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private TextView searchNote;
    private String query = "";
    private int searchGeneration;
    private long shownRevision = -1;
    private boolean visible, destroyed;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (SyncJobService.revision != shownRevision && query.isEmpty()) loadSources();
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        setContentView(page);
        page.addView(Ui.header(this, Ui.iconButton(this, R.drawable.ic_info, "About and privacy", Ui.INK, v -> about())));
        page.addView(Ui.divider(this));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(Ui.title(this, "Your knowledge", "knowledge"));
        Ui.gap(content, 16);
        content.addView(searchField(), new LinearLayout.LayoutParams(-1, dp(52)));
        searchNote = Ui.text(this, "", 14, Ui.MUTED, false);
        searchNote.setPadding(0, dp(8), 0, 0);
        searchNote.setVisibility(View.GONE);
        content.addView(searchNote);
        body = Ui.column(this);
        content.addView(body);

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
        destroyed = true;
        io.shutdownNow();
        super.onDestroy();
    }

    private EditText searchField() {
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search messages and recordings");
        search.setTextSize(16);
        search.setTypeface(Ui.font(this, false));
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable field = Ui.shape(this, Ui.SURFACE, 4);
        field.setStroke(Math.max(1, dp(1)), Ui.LINE);
        search.setBackground(field);
        search.setPadding(dp(14), 0, dp(14), 0);
        Drawable lens = getDrawable(R.drawable.ic_search);
        if (lens != null) {
            lens = lens.mutate();
            lens.setTint(Ui.MUTED);
            lens.setBounds(0, 0, dp(20), dp(20));
            search.setCompoundDrawablesRelative(lens, null, null, null);
            search.setCompoundDrawablePadding(dp(10));
        }
        search.setContentDescription("Search everything synced");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim();
                final int generation = ++searchGeneration;
                main.postDelayed(() -> { if (generation == searchGeneration) refresh(); }, 250);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        return search;
    }

    private void refresh() {
        if (query.isEmpty()) { shownRevision = -1; loadSources(); }
        else search();
    }

    // --- Sources -------------------------------------------------------------------------------

    private void loadSources() {
        shownRevision = SyncJobService.revision;
        final int generation = searchGeneration;
        io.execute(() -> {
            List<Sources.State> states;
            try {
                Db db = KnowledgeStore.get(this);
                for (SourcePlugin plugin : PluginRegistry.plugins(this)) Sources.ensure(db, plugin.id());
                states = Sources.all(db);
            } catch (Exception failure) {
                main.post(() -> { if (!destroyed && query.isEmpty()) showStoreError(failure); });
                return;
            }
            main.post(() -> { if (!destroyed && generation == searchGeneration && query.isEmpty()) showSources(states); });
        });
    }

    void showSources(List<Sources.State> states) {
        body.removeAllViews();
        searchNote.setVisibility(View.GONE);
        long lastSync = 0;
        for (Sources.State s : states) if (s.lastSyncAt != null) lastSync = Math.max(lastSync, s.lastSyncAt);
        boolean busy = SyncJobService.busy();

        if (lastSync == 0 && !busy) {
            Ui.gap(body, 28);
            body.addView(Ui.text(this, "Nothing synced yet.", 20, Ui.INK, true));
            TextView hint = Ui.text(this, "Sentient copies your Omi Tarefas transcripts once a day. Start the first sync now.",
                    15, Ui.MUTED, false);
            hint.setPadding(0, dp(8), 0, dp(16));
            body.addView(hint);
            body.addView(syncButton(false), new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        Ui.gap(body, 28);
        body.addView(Ui.text(this, "Sources", 22, Ui.INK, true));
        Ui.gap(body, 6);
        for (Sources.State s : states) {
            body.addView(Ui.divider(this));
            body.addView(sourceRow(s));
        }
        body.addView(Ui.divider(this));
        Ui.gap(body, 20);
        String line = busy ? "Getting new messages and recordings…"
                : "Last synced " + ago(lastSync) + ". Syncs again once a day.";
        body.addView(Ui.text(this, line, 14, Ui.MUTED, false));
        Ui.gap(body, 12);
        body.addView(syncButton(busy), new LinearLayout.LayoutParams(-1, -2));
    }

    private View sourceRow(Sources.State s) {
        LinearLayout r = Ui.column(this);
        r.setPadding(0, dp(14), 0, dp(14));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, PluginRegistry.displayName(this, s.pluginId), 17, Ui.INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        boolean problem = s.lastStatus != null && !s.lastStatus.startsWith(OK);
        if (problem) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = dp(10);
            top.addView(Ui.chip(this, "Needs attention", true), cp);
        }
        r.addView(top);
        String when = s.lastSyncAt == null ? "Not synced yet"
                : "Synced " + ago(s.lastSyncAt);
        TextView meta = Ui.text(this, items(s.itemCount) + " · " + when, 13, Ui.MUTED, false);
        meta.setPadding(0, dp(6), 0, 0);
        r.addView(meta);
        if (problem) {
            TextView reason = Ui.text(this, problemText(s.lastStatus), 15, Ui.CORAL_TEXT, false);
            reason.setPadding(0, dp(8), 0, 0);
            r.addView(reason);
        }
        return r;
    }

    /** "Unavailable · <reason>" carries a user-facing reason; other failures only a class name. */
    static String problemText(String status) {
        String unavailable = "Unavailable · ";
        if (status.startsWith(unavailable)) return status.substring(unavailable.length()) + ".";
        return "The last sync failed. Sync now to try again.";
    }

    /** "2 hours ago"; passing now explicitly keeps it relative instead of a calendar date. */
    private static String ago(long time) {
        String value = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        // Android capitalises "Yesterday"; it reads mid-sentence here ("Synced yesterday").
        boolean english = "en".equals(java.util.Locale.getDefault().getLanguage());
        return english && !value.isEmpty() ? Character.toLowerCase(value.charAt(0)) + value.substring(1) : value;
    }

    private static String items(long count) { return count == 1 ? "1 item" : count + " items"; }

    private Button syncButton(boolean busy) {
        Button b = Ui.button(this, busy ? "Syncing…" : "Sync now", Ui.Style.PRIMARY, v -> {
            SyncJobService.syncNow(this);
            shownRevision = -1;
        });
        b.setEnabled(!busy);
        return b;
    }

    private void showStoreError(Exception failure) {
        body.removeAllViews();
        Ui.gap(body, 28);
        body.addView(Ui.text(this, "Can't open your knowledge base.", 20, Ui.INK, true));
        TextView hint = Ui.text(this, "It's encrypted with a key kept by Android. If the app's data was cleared, "
                + "the key is gone and Sentient starts empty after a reinstall. (" + failure.getClass().getSimpleName() + ")",
                15, Ui.MUTED, false);
        hint.setPadding(0, dp(8), 0, 0);
        body.addView(hint);
    }

    // --- Search --------------------------------------------------------------------------------

    private void search() {
        final String term = query;
        final int generation = searchGeneration;
        io.execute(() -> {
            List<Search.Hit> hits;
            try { hits = Search.find(KnowledgeStore.get(this), term, MAX_HITS); }
            catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || generation != searchGeneration) return;
                    body.removeAllViews();
                    note("Search isn't available right now (" + failure.getClass().getSimpleName() + ").", true);
                });
                return;
            }
            main.post(() -> { if (!destroyed && generation == searchGeneration) showResults(term, hits); });
        });
    }

    void showResults(String term, List<Search.Hit> hits) {
        body.removeAllViews();
        searchNote.setVisibility(View.GONE);
        if (hits.isEmpty()) {
            Ui.gap(body, 28);
            body.addView(Ui.text(this, "No matches.", 20, Ui.INK, true));
            TextView hint = Ui.text(this, "Try another word, or fewer words.", 15, Ui.MUTED, false);
            hint.setPadding(0, dp(8), 0, 0);
            body.addView(hint);
            return;
        }
        if (hits.size() >= MAX_HITS) note("Showing the " + MAX_HITS + " best matches.", false);
        Ui.gap(body, 12);
        for (Search.Hit hit : hits) {
            body.addView(Ui.divider(this));
            body.addView(resultRow(term, hit));
        }
        body.addView(Ui.divider(this));
    }

    private View resultRow(String term, Search.Hit hit) {
        LinearLayout r = Ui.column(this);
        r.setPadding(0, dp(14), 0, dp(14));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3343F3B7), null, Ui.shape(this, Color.WHITE, 0)));
        String title = titleOf(this, hit.conversation, hit.source);
        r.addView(Ui.text(this, title, 17, Ui.INK, true));
        TextView meta = Ui.text(this, kindOf(this, hit.source) + " · " + date(this, hit.ts), 13, Ui.MUTED, false);
        meta.setPadding(0, dp(6), 0, 0);
        r.addView(meta);
        TextView snippet = Ui.text(this, Ui.highlight(this, hit.snippet), 15, Ui.INK, false);
        snippet.setMaxLines(2);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        snippet.setPadding(0, dp(8), 0, 0);
        r.addView(snippet);
        r.setContentDescription("Open " + title);
        r.setOnClickListener(v -> startActivity(new Intent(this, ItemActivity.class)
                .putExtra(ItemActivity.EXTRA_ID, hit.itemId).putExtra(ItemActivity.EXTRA_QUERY, term)));
        return r;
    }

    private void note(String value, boolean error) {
        searchNote.setText(value);
        searchNote.setTextColor(error ? Ui.CORAL_TEXT : Ui.MUTED);
        searchNote.setVisibility(View.VISIBLE);
    }

    static String titleOf(Activity a, String conversation, String source) {
        return conversation == null || conversation.isEmpty() ? PluginRegistry.displayName(a, source) : conversation;
    }

    static String kindOf(Activity a, String source) {
        return OmiTranscripts.ID.equals(source) ? "Omi recording" : PluginRegistry.displayName(a, source);
    }

    static String date(Activity a, long ts) {
        return DateUtils.formatDateTime(a, ts, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_ABBREV_MONTH);
    }

    // --- About ---------------------------------------------------------------------------------

    private void about() {
        new AlertDialog.Builder(this).setTitle("Sentient " + versionName())
                .setMessage("Your knowledge base: everything Sentient collects, searchable in one place.\n\n"
                        + "• Encrypted on this phone. Uninstalling or clearing the app's data deletes it.\n"
                        + "• Syncs once a day while the battery isn't low, or when you tap Sync now.\n"
                        + "• Today it reads your Omi Tarefas transcripts. In this version nothing leaves the phone.\n"
                        + "• Read-only: Sentient never sends messages or acts for you.")
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
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Close", null).show();
    }

    private int dp(int value) { return Ui.dp(this, value); }
}
