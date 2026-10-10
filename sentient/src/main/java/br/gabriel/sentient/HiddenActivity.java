package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** What GMind leaves out of memory, and why; Keep puts an item back. */
public final class HiddenActivity extends Activity {
    static final int LIMIT = 300;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        setContentView(page);
        page.addView(Ui.backBar(this, null));
        page.addView(Ui.divider(this));
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @Override protected void onResume() { super.onResume(); load(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    private void load() {
        io.execute(() -> {
            List<Items.Item> items;
            int count;
            try {
                Db db = KnowledgeStore.get(this);
                items = Items.hidden(db, LIMIT);
                count = Relevance.hiddenCount(db);
            } catch (Exception failure) {
                items = Collections.emptyList();
                count = 0;
            }
            final List<Items.Item> shown = items;
            final int total = count;
            main.post(() -> { if (!destroyed) show(shown, total); });
        });
    }

    void show(List<Items.Item> items, int total) {
        content.removeAllViews();
        content.addView(Ui.title(this, "Hidden from memory", "Hidden"));
        Ui.gap(content, 6);
        content.addView(Ui.text(this, "Marketing, newsletters and automated mail stay stored but are left out of the home "
                + "screen, Ask, your portrait and the vault. Search can still find them. Keep puts an item back.",
                14, Ui.MUTED, false));
        Switch on = new Switch(this);
        on.setText("Hide marketing and automated mail");
        on.setTypeface(Ui.font(this, false));
        on.setTextColor(Ui.INK);
        on.setChecked(Relevance.enabled);
        on.setOnCheckedChangeListener((b, checked) -> io.execute(() -> {
            try { Relevance.setEnabled(KnowledgeStore.get(this), checked); } catch (Exception ignored) { }
            main.post(this::load);
        }));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 14);
        content.addView(on, sp);
        if (items.isEmpty()) {
            TextView none = Ui.text(this, "Nothing is hidden.", 16, Ui.INK, true);
            none.setPadding(0, Ui.dp(this, 24), 0, 0);
            content.addView(none);
            return;
        }
        if (total > items.size()) {
            TextView more = Ui.text(this, "Showing " + items.size() + " of " + total + ".", 14, Ui.MUTED, false);
            more.setPadding(0, Ui.dp(this, 10), 0, 0);
            content.addView(more);
        }
        String reason = null;
        for (Items.Item item : items) {
            String why = item.noiseReason == null ? "Hidden" : item.noiseReason;
            if (!why.equals(reason)) {
                reason = why;
                TextView header = Ui.text(this, why, 18, Ui.INK, true);
                header.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 6));
                content.addView(header);
            }
            content.addView(Ui.divider(this));
            content.addView(row(item));
        }
        content.addView(Ui.divider(this));
    }

    private LinearLayout row(Items.Item item) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.text(this, SentientActivity.titleOf(this, item.conversation, item.source), 15, Ui.INK, true));
        text.addView(Ui.text(this, SentientActivity.metaOf(this, item.source, item.author, item.fromMe, item.ts), 13, Ui.MUTED, false));
        text.setOnClickListener(v -> startActivity(new Intent(this, ItemActivity.class).putExtra(ItemActivity.EXTRA_ID, item.id)));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Ui.button(this, "Keep", Ui.Style.QUIET, v -> io.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                db.transaction(() -> { Relevance.keep(db, item.id); return null; });
            } catch (Exception ignored) { }
            main.post(this::load);
        })));
        return row;
    }
}
