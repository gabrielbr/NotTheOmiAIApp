package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home: search everything synced; with no query, the newest items. A line under the search
 * field appears only when a source needs attention. Sources and sync live in Settings. */
public final class SentientActivity extends Activity implements LiveSources.Listener {
    static final int MAX_HITS = 30, RECENT = 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private TextView searchNote, attention;
    private LiveSources sources;
    private String query = "";
    private int searchGeneration;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        setContentView(page);
        page.addView(Ui.header(this, Ui.iconButton(this, R.drawable.ic_settings, "Settings", Ui.INK,
                v -> startActivity(new Intent(this, SettingsActivity.class)))));
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
        attention = Ui.text(this, "", 15, Ui.CORAL_TEXT, true);
        attention.setPadding(0, dp(14), 0, dp(4));
        attention.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3343F3B7), null, null));
        attention.setVisibility(View.GONE);
        content.addView(attention, new LinearLayout.LayoutParams(-1, -2));
        searchNote = Ui.text(this, "", 14, Ui.MUTED, false);
        searchNote.setPadding(0, dp(8), 0, 0);
        searchNote.setVisibility(View.GONE);
        content.addView(searchNote);
        body = Ui.column(this);
        content.addView(body);

        sources = new LiveSources(this, this);
        SyncJobService.scheduleDaily(this);
    }

    @Override protected void onResume() { super.onResume(); sources.resume(); }
    @Override protected void onPause() { sources.pause(); super.onPause(); }

    @Override protected void onDestroy() {
        destroyed = true;
        sources.destroy();
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
        if (query.isEmpty()) sources.reload();
        else search();
    }

    // --- Home --------------------------------------------------------------------------------

    @Override public void onSources(List<Sources.State> states) {
        showAttention(states);
        if (!query.isEmpty()) return;
        final int generation = searchGeneration;
        io.execute(() -> {
            List<Items.Item> recent;
            try { recent = Items.recent(KnowledgeStore.get(this), RECENT); }
            catch (Exception failure) {
                main.post(() -> { if (!destroyed && query.isEmpty()) showStoreError(failure); });
                return;
            }
            main.post(() -> { if (!destroyed && generation == searchGeneration && query.isEmpty()) showHome(recent); });
        });
    }

    @Override public void onStoreError(Exception failure) { if (query.isEmpty()) showStoreError(failure); }

    /** One coral line when any source needs attention; it opens that source, or the list for several. */
    void showAttention(List<Sources.State> states) {
        boolean access = ChatPlugin.accessGranted(this);
        List<Sources.State> problems = new java.util.ArrayList<>();
        for (Sources.State s : states) if (SourceStatus.problem(s, access)) problems.add(s);
        if (problems.isEmpty()) { attention.setVisibility(View.GONE); return; }
        Sources.State first = problems.get(0);
        String text = problems.size() == 1
                ? PluginRegistry.displayName(this, first.pluginId) + " needs attention ›"
                : problems.size() + " sources need attention ›";
        attention.setText(text);
        attention.setContentDescription(text.replace(" ›", ""));
        attention.setOnClickListener(v -> startActivity(problems.size() == 1
                ? new Intent(this, SourceActivity.class).putExtra(SourceActivity.EXTRA_PLUGIN_ID, first.pluginId)
                : new Intent(this, SettingsActivity.class)));
        attention.setVisibility(View.VISIBLE);
    }

    void showHome(List<Items.Item> recent) {
        body.removeAllViews();
        searchNote.setVisibility(View.GONE);
        Ui.gap(body, 28);
        if (recent.isEmpty()) {
            body.addView(Ui.text(this, "Nothing here yet.", 20, Ui.INK, true));
            Ui.gap(body, 20);
            body.addView(Ui.button(this, "Set up sources", Ui.Style.PRIMARY,
                    v -> startActivity(new Intent(this, SettingsActivity.class))), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        body.addView(Ui.text(this, "Recent", 14, Ui.MUTED, true));
        Ui.gap(body, 8);
        for (Items.Item item : recent) {
            body.addView(Ui.divider(this));
            body.addView(itemRow(item.id, null, titleOf(this, item.conversation, item.source),
                    metaOf(this, item.source, item.author, item.fromMe, item.ts), item.text));
        }
        body.addView(Ui.divider(this));
    }

    private void showStoreError(Exception failure) {
        body.removeAllViews();
        Ui.gap(body, 28);
        body.addView(Ui.text(this, "Can't open your knowledge base.", 20, Ui.INK, true));
        TextView hint = Ui.text(this, "It's encrypted with a key kept by Android. If the app's data was cleared, "
                + "the key is gone and GMind starts empty after a reinstall. (" + failure.getClass().getSimpleName() + ")",
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
            body.addView(itemRow(hit.itemId, term, titleOf(this, hit.conversation, hit.source),
                    metaOf(this, hit.source, hit.author, hit.fromMe, hit.ts), Ui.highlight(this, hit.snippet)));
        }
        body.addView(Ui.divider(this));
    }

    /** A search result or a recent item; opens the item, with the search words highlighted. */
    private View itemRow(long itemId, String term, String title, String meta, CharSequence text) {
        LinearLayout r = Ui.column(this);
        r.setPadding(0, dp(14), 0, dp(14));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3343F3B7), null, Ui.shape(this, Color.WHITE, 0)));
        r.addView(Ui.text(this, title, 17, Ui.INK, true));
        TextView metaView = Ui.text(this, meta, 13, Ui.MUTED, false);
        metaView.setPadding(0, dp(6), 0, 0);
        r.addView(metaView);
        TextView snippet = Ui.text(this, text, 15, Ui.INK, false);
        snippet.setMaxLines(2);
        snippet.setEllipsize(TextUtils.TruncateAt.END);
        snippet.setPadding(0, dp(8), 0, 0);
        r.addView(snippet);
        r.setContentDescription("Open " + title);
        r.setOnClickListener(v -> startActivity(new Intent(this, ItemActivity.class)
                .putExtra(ItemActivity.EXTRA_ID, itemId).putExtra(ItemActivity.EXTRA_QUERY, term)));
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

    /** "WhatsApp · Ana · 9 Oct, 14:02"; "Omi recording · 9 Oct, 14:02". */
    static String metaOf(Activity a, String source, String author, boolean fromMe, long ts) {
        String who = fromMe ? "You" : author;
        return kindOf(a, source) + (who == null ? "" : " · " + who) + " · " + date(a, ts);
    }

    static String kindOf(Activity a, String source) {
        return OmiTranscripts.ID.equals(source) ? "Omi recording" : PluginRegistry.displayName(a, source);
    }

    static String date(Activity a, long ts) {
        return DateUtils.formatDateTime(a, ts, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private int dp(int value) { return Ui.dp(this, value); }
}
