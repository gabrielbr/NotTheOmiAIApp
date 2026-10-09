package br.gabriel.sentient;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import br.gabriel.sentient.plugin.RawItem;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A transcript in full, or a message in its conversation, with the search words highlighted. */
public final class ItemActivity extends Activity {
    static final String EXTRA_ID = "item_id", EXTRA_QUERY = "query";
    static final int CONTEXT = 10;

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

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        String query = getIntent().getStringExtra(EXTRA_QUERY);
        io.execute(() -> {
            Items.Item item;
            String marked = null;
            List<Items.Item> thread = Collections.emptyList();
            try {
                Db db = KnowledgeStore.get(this);
                item = Items.get(db, id);
                if (item != null) {
                    marked = Items.highlighted(db, id, query);
                    if (RawItem.MESSAGE.equals(item.kind)) thread = Items.around(db, item, CONTEXT);
                }
            } catch (Exception failure) {
                item = null;
            }
            final Items.Item found = item;
            final String text = marked;
            final List<Items.Item> context = thread;
            main.post(() -> { if (!destroyed) { if (context.size() > 1) showThread(found, text, context); else show(found, text); } });
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        super.onDestroy();
    }

    /** A message among its neighbours, as a simple chat log; the hit is marked and highlighted. */
    void showThread(Items.Item hit, String marked, List<Items.Item> thread) {
        content.removeAllViews();
        content.addView(Ui.text(this, SentientActivity.titleOf(this, hit.conversation, hit.source), 26, Ui.INK, true));
        TextView meta = Ui.text(this, SentientActivity.kindOf(this, hit.source) + " · "
                + SentientActivity.date(this, hit.ts), 14, Ui.MUTED, false);
        meta.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        content.addView(meta);
        for (Items.Item m : thread) {
            boolean isHit = m.id == hit.id;
            LinearLayout row = Ui.column(this);
            int pad = Ui.dp(this, 12);
            row.setPadding(pad, pad, pad, pad);
            if (isHit) row.setBackground(Ui.shape(this, Ui.SURFACE, 4));
            String who = m.fromMe ? "You" : (m.author == null ? "" : m.author);
            String time = android.text.format.DateUtils.formatDateTime(this, m.ts, android.text.format.DateUtils.FORMAT_SHOW_TIME);
            row.addView(Ui.text(this, who.isEmpty() ? time : who + " · " + time, 13, m.fromMe ? Ui.MINT_INK : Ui.MUTED, true));
            TextView text = Ui.text(this, isHit && marked != null ? Ui.highlight(this, marked) : m.text, 16, Ui.INK, false);
            text.setPadding(0, Ui.dp(this, 4), 0, 0);
            text.setTextIsSelectable(true);
            row.addView(text);
            if (isHit) row.setContentDescription("Search result: " + who + ", " + m.text);
            content.addView(row);
        }
    }

    /** {@code marked} is the full text with match markers, or null to show it plain. */
    void show(Items.Item item, String marked) {
        content.removeAllViews();
        if (item == null) {
            content.addView(Ui.text(this, "This item is gone.", 20, Ui.INK, true));
            TextView hint = Ui.text(this, "It may have been removed at its source.", 15, Ui.MUTED, false);
            hint.setPadding(0, Ui.dp(this, 8), 0, 0);
            content.addView(hint);
            return;
        }
        content.addView(Ui.text(this, SentientActivity.titleOf(this, item.conversation, item.source), 26, Ui.INK, true));
        TextView meta = Ui.text(this, SentientActivity.kindOf(this, item.source) + " · "
                + SentientActivity.date(this, item.ts), 14, Ui.MUTED, false);
        meta.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 20));
        content.addView(meta);
        TextView body = Ui.text(this, marked != null ? Ui.highlight(this, marked) : item.text, 16, Ui.INK, false);
        body.setLineSpacing(0, 1.3f);
        body.setTextIsSelectable(true);
        content.addView(body);
    }
}
