package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * To-dos GMind noticed in your recordings and chats (restored from Omi Tarefas). Pick some and send
 * them to the Todoist app through Android's share: Todoist opens once per task so you confirm each
 * one there. GMind itself never writes to Todoist.
 */
public final class TasksActivity extends Activity {
    static final String TODOIST_PACKAGE = "com.todoist";
    static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int SHARE_REQUEST = 40;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final List<FoundTasks.Task> queue = new ArrayList<>();
    private LinearLayout content;
    private Button send, dismiss;
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
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = Ui.column(this);
        bar.setPadding(dp(20), dp(10), dp(20), dp(16));
        page.addView(Ui.divider(this));
        send = Ui.button(this, "Send to Todoist", Ui.Style.PRIMARY, v -> sendSelected());
        bar.addView(send, new LinearLayout.LayoutParams(-1, -2));
        dismiss = Ui.button(this, "Dismiss", Ui.Style.QUIET, v -> dismissSelected());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = dp(6);
        bar.addView(dismiss, dp);
        page.addView(bar);
        load();
    }

    @Override protected void onRestart() { super.onRestart(); load(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    private void load() {
        io.execute(() -> {
            List<FoundTasks.Task> open = new ArrayList<>(), sent = new ArrayList<>();
            try {
                Db db = KnowledgeStore.get(this);
                long since = System.currentTimeMillis() - WINDOW_MS;
                open = FoundTasks.list(db, FoundTasks.OPEN, since, 300);
                sent = FoundTasks.list(db, FoundTasks.SHARED, since, 20);
            } catch (Exception failure) { /* shown as empty */ }
            final List<FoundTasks.Task> o = open, s = sent;
            main.post(() -> { if (!destroyed) show(o, s); });
        });
    }

    void show(List<FoundTasks.Task> open, List<FoundTasks.Task> sent) {
        content.removeAllViews();
        selected.retainAll(ids(open));
        content.addView(Ui.title(this, "To-dos", "To-dos"));
        Ui.gap(content, 6);
        content.addView(Ui.text(this, "Things you said you'd do, or were asked to in the chats GMind watches, from the last "
                + "30 days. Found on this phone by phrases like \"preciso\", \"não esquece de\", \"I need to\".", 14, Ui.MUTED, false));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.topMargin = dp(8);
        content.addView(Ui.button(this, "Choose chats to watch", Ui.Style.QUIET,
                v -> startActivity(new Intent(this, WatchedChatsActivity.class))), wp);
        Ui.gap(content, 4);
        if (open.isEmpty()) {
            TextView none = Ui.text(this, "Nothing open.", 17, Ui.INK, true);
            none.setPadding(0, dp(14), 0, 0);
            content.addView(none);
        }
        for (FoundTasks.Task t : open) {
            content.addView(Ui.divider(this));
            content.addView(row(t));
        }
        if (!open.isEmpty()) content.addView(Ui.divider(this));
        if (!sent.isEmpty()) {
            TextView h = Ui.text(this, "Sent to Todoist", 18, Ui.INK, true);
            h.setPadding(0, dp(24), 0, dp(6));
            content.addView(h);
            for (FoundTasks.Task t : sent) {
                TextView line = Ui.text(this, "✓ " + t.text, 14, Ui.MUTED, false);
                line.setPadding(0, dp(4), 0, dp(4));
                content.addView(line);
            }
        }
        updateBar();
    }

    private LinearLayout row(FoundTasks.Task t) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, dp(8), 0, dp(8));
        CheckBox box = new CheckBox(this);
        box.setButtonTintList(ColorStateList.valueOf(Ui.INK));
        box.setChecked(selected.contains(t.id));
        box.setContentDescription("Select " + t.text);
        box.setOnCheckedChangeListener((b, on) -> { if (on) selected.add(t.id); else selected.remove(t.id); updateBar(); });
        r.addView(box);
        LinearLayout text = Ui.column(this);
        text.addView(Ui.text(this, t.text, 16, Ui.INK, false));
        String where = (t.due == null ? "" : "Due " + dueLabel(t.due) + " · ") + "From " + People.sourceName(t.source) + (t.conversation != null ? " · " + t.conversation : "")
                + (t.fromMe || t.author == null || t.author.equals(t.conversation) ? "" : " · " + t.author) + " · " + SentientActivity.date(this, t.ts);
        TextView meta = Ui.text(this, where, 13, Ui.MUTED, false);
        meta.setPadding(0, dp(3), 0, 0);
        text.addView(meta);
        text.setOnClickListener(v -> startActivity(new Intent(this, ItemActivity.class).putExtra(ItemActivity.EXTRA_ID, t.itemId)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(6);
        r.addView(text, tp);
        return r;
    }

    /** "Fri 16 Oct". */
    static String dueLabel(String due) {
        try {
            return java.time.LocalDate.parse(due).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH));
        } catch (java.time.format.DateTimeParseException bad) {
            return due;
        }
    }

    private void updateBar() {
        int n = selected.size();
        send.setText(n > 0 ? "Send " + n + " to Todoist" : "Send to Todoist");
        send.setEnabled(n > 0);
        dismiss.setText(n > 0 ? "Dismiss " + n : "Dismiss");
        dismiss.setEnabled(n > 0);
    }

    private void sendSelected() {
        if (selected.isEmpty()) return;
        io.execute(() -> {
            List<FoundTasks.Task> picked = new ArrayList<>();
            try {
                for (FoundTasks.Task t : FoundTasks.list(KnowledgeStore.get(this), FoundTasks.OPEN, 0, 1000))
                    if (selected.contains(t.id)) picked.add(t);
            } catch (Exception failure) { /* nothing to send */ }
            main.post(() -> { if (!destroyed) { queue.clear(); queue.addAll(picked); shareNext(); } });
        });
    }

    /** One share per task, so Todoist's Quick Add parses each task and its date on its own. */
    private void shareNext() {
        if (queue.isEmpty()) { selected.clear(); load(); return; }
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, queue.get(0).text).setPackage(TODOIST_PACKAGE);
        try {
            startActivityForResult(share, SHARE_REQUEST);
        } catch (ActivityNotFoundException missing) {
            queue.clear();
            new AlertDialog.Builder(this).setTitle("Todoist app not found")
                    .setMessage("Install and sign in to the Todoist app on this phone, then try again.")
                    .setPositiveButton("OK", null).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != SHARE_REQUEST || queue.isEmpty()) return;
        // Todoist doesn't say whether you saved or cancelled; mark it sent and move on.
        FoundTasks.Task done = queue.remove(0);
        io.execute(() -> {
            try { FoundTasks.setStatus(KnowledgeStore.get(this), done.id, FoundTasks.SHARED); }
            catch (Exception failure) { /* stays open */ }
            main.post(() -> { if (!destroyed) shareNext(); });
        });
    }

    private void dismissSelected() {
        List<Long> ids = new ArrayList<>(selected);
        selected.clear();
        io.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                db.transaction(() -> { for (long id : ids) FoundTasks.setStatus(db, id, FoundTasks.DISMISSED); return null; });
            } catch (Exception failure) { /* stays open */ }
            main.post(() -> { if (!destroyed) load(); });
        });
    }

    private static Set<Long> ids(List<FoundTasks.Task> tasks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (FoundTasks.Task t : tasks) ids.add(t.id);
        return ids;
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
