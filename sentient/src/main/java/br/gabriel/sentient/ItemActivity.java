package br.gabriel.sentient;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One message or transcript in full, with the search words on the highlighter. */
public final class ItemActivity extends Activity {
    static final String EXTRA_ID = "item_id", EXTRA_QUERY = "query";

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
            try {
                Db db = KnowledgeStore.get(this);
                item = Items.get(db, id);
                if (item != null) marked = Items.highlighted(db, id, query);
            } catch (Exception failure) {
                item = null;
            }
            final Items.Item found = item;
            final String text = marked;
            main.post(() -> { if (!destroyed) show(found, text); });
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        super.onDestroy();
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
