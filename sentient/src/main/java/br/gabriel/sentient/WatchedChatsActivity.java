package br.gabriel.sentient;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which chats GMind reads for to-dos. Direct chats are on and groups off until you choose; in a
 * group only messages that name you count. Switching a chat on reads its last 30 days again.
 */
public final class WatchedChatsActivity extends Activity {
    static final int WINDOW_DAYS = 90, RESCAN_DAYS = 30;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private boolean destroyed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        setContentView(page);
        page.addView(Ui.backBar(this, null));
        page.addView(Ui.divider(this));
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        content.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
    }

    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    private void load() {
        io.execute(() -> {
            List<Object[]> chats = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try {
                Db db = KnowledgeStore.get(this);
                chats = FoundTasks.chats(db, System.currentTimeMillis() - WINDOW_DAYS * 24L * 60 * 60 * 1000, 500);
                names = FoundTasks.names(db);
            } catch (Exception failure) { /* shown as empty */ }
            final List<Object[]> c = chats;
            final List<String> n = names;
            main.post(() -> { if (!destroyed) show(c, n); });
        });
    }

    void show(List<Object[]> chats, List<String> names) {
        content.removeAllViews();
        content.addView(Ui.title(this, "Chats for to-dos", "to-dos"));
        Ui.gap(content, 6);
        content.addView(Ui.text(this, "GMind looks for things people ask of you in the chats switched on here. Direct chats "
                + "are on and groups off until you choose. In a group, only messages that name you count"
                + (names.isEmpty() ? " (add your name in About you first)." : " (" + String.join(", ", names) + ")."),
                14, Ui.MUTED, false));
        if (chats.isEmpty()) {
            TextView none = Ui.text(this, "No chats from the last " + WINDOW_DAYS + " days yet.", 15, Ui.MUTED, false);
            none.setPadding(0, dp(16), 0, 0);
            content.addView(none);
            return;
        }
        String app = null;
        for (Object[] c : chats) {
            String source = (String) c[2];
            if (!source.equals(app)) {
                app = source;
                TextView h = Ui.text(this, People.sourceName(source), 18, Ui.INK, true);
                h.setPadding(0, dp(22), 0, dp(4));
                content.addView(h);
            }
            content.addView(Ui.divider(this));
            content.addView(row(c));
        }
        content.addView(Ui.divider(this));
    }

    private LinearLayout row(Object[] c) {
        long id = ((Number) c[0]).longValue();
        boolean group = !"dm".equals(c[3]);
        LinearLayout r = Ui.row(this);
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.text(this, (String) c[1], 16, Ui.INK, false));
        TextView meta = Ui.text(this, (group ? "Group" : "Direct chat") + " · last " + SentientActivity.ago(((Number) c[5]).longValue()),
                13, Ui.MUTED, false);
        meta.setPadding(0, dp(2), 0, 0);
        text.addView(meta);
        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(((Number) c[4]).intValue() != 0);
        toggle.setThumbTintList(ColorStateList.valueOf(Ui.INK));
        toggle.setContentDescription("Watch " + c[1] + " for to-dos");
        toggle.setOnCheckedChangeListener((b, on) -> io.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                db.transaction(() -> {
                    FoundTasks.setWatched(db, id, on);
                    if (on) FoundTasks.scanChat(db, id, System.currentTimeMillis(), ZoneId.systemDefault(), RESCAN_DAYS);
                    return null;
                });
            } catch (Exception failure) { /* the switch reverts on the next visit */ }
        }));
        r.addView(toggle);
        return r;
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
