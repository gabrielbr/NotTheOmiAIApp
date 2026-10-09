package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "About you": the portrait GMind builds from what it collected, the way to People and To-dos,
 * the daily digests, and the Markdown vault export. With EXTRA_DAY it shows one day's digest.
 */
public final class YouActivity extends Activity {
    static final String EXTRA_DAY = "day";
    private static final int PICK_FOLDER = 51;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private boolean destroyed, exporting;

    /** What the screen shows, read off the main thread. */
    static final class State {
        String portrait;
        int suggestions, openTasks;
        final List<String> days = new ArrayList<>();
    }

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
    }

    @Override protected void onResume() { super.onResume(); load(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    private void load() {
        String day = getIntent().getStringExtra(EXTRA_DAY);
        io.execute(() -> {
            State s = new State();
            try {
                Db db = KnowledgeStore.get(this);
                if (day != null) s.portrait = Digest.read(db, LocalDate.parse(day));
                else {
                    s.portrait = Portrait.read(db);
                    s.suggestions = People.suggestions(db, 50).size();
                    s.openTasks = FoundTasks.list(db, FoundTasks.OPEN, System.currentTimeMillis() - TasksActivity.WINDOW_MS, 500).size();
                    for (Object[] d : Digest.days(db, 14)) s.days.add((String) d[0]);
                }
            } catch (Exception failure) {
                s.portrait = null;
            }
            main.post(() -> { if (!destroyed) { if (day != null) showDay(day, s.portrait); else show(s); } });
        });
    }

    void showDay(String day, String digest) {
        content.removeAllViews();
        content.addView(Ui.title(this, digest == null ? day : digest.split("\n")[0].replaceFirst("^# ", ""), null));
        if (digest == null) content.addView(Ui.text(this, "Nothing came in that day.", 15, Ui.MUTED, false));
        else MarkdownView.render(this, content, digest);
    }

    void show(State s) {
        content.removeAllViews();
        content.addView(Ui.title(this, "About you", "you"));
        Ui.gap(content, 6);
        content.addView(Ui.text(this, "Built on this phone from what GMind collected, after every sync. Ask reads it "
                + "before answering. Tap › to see where a line comes from.", 14, Ui.MUTED, false));
        LinearLayout nav = Ui.row(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        nav.addView(Ui.button(this, s.suggestions > 0 ? "People · " + s.suggestions + " new" : "People", Ui.Style.DARK,
                v -> startActivity(new Intent(this, PeopleActivity.class))), half);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.leftMargin = dp(10);
        nav.addView(Ui.button(this, s.openTasks > 0 ? "To-dos · " + s.openTasks : "To-dos", Ui.Style.DARK,
                v -> startActivity(new Intent(this, TasksActivity.class))), right);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(16);
        content.addView(nav, np);

        if (s.portrait == null) {
            TextView empty = Ui.text(this, "Your portrait appears after the first sync.", 15, Ui.MUTED, false);
            empty.setPadding(0, dp(20), 0, 0);
            content.addView(empty);
        } else {
            MarkdownView.render(this, content, s.portrait);
        }

        if (!s.days.isEmpty()) {
            section("Days");
            for (String day : s.days) {
                content.addView(Ui.divider(this));
                TextView row = Ui.text(this, LocalDate.parse(day).format(java.time.format.DateTimeFormatter
                        .ofPattern("EEEE, d MMMM", java.util.Locale.ENGLISH)), 16, Ui.INK, false);
                row.setPadding(0, dp(12), 0, dp(12));
                row.setOnClickListener(v -> startActivity(new Intent(this, YouActivity.class).putExtra(EXTRA_DAY, day)));
                content.addView(row);
            }
            content.addView(Ui.divider(this));
        }
        enrichment();
        vault();
    }

    private void enrichment() {
        section("AI enrichment");
        content.addView(Ui.text(this, "After each sync, Claude Haiku 5.5 reads what came in and notes the people, projects, "
                + "places and facts in it, so the portrait, the vault and Ask know more. This sends your new messages, emails "
                + "and transcripts to Anthropic in batches, with your Claude key; usually a few US cents a day.", 14, Ui.MUTED, false));
        if (!AskSettings.hasKey(this)) {
            content.addView(Ui.button(this, "Add a Claude key first", Ui.Style.QUIET,
                    v -> startActivity(new Intent(this, AskSettingsActivity.class))), buttonParams());
            return;
        }
        CheckBox on = new CheckBox(this);
        on.setText("Enrich with Claude after each sync");
        on.setTypeface(Ui.font(this, false));
        on.setTextColor(Ui.INK);
        on.setButtonTintList(ColorStateList.valueOf(Ui.INK));
        on.setChecked(AskSettings.enrich(this));
        on.setOnCheckedChangeListener((b, checked) -> AskSettings.setEnrich(this, checked));
        content.addView(on, buttonParams());
        String status = AskSettings.enrichStatus(this);
        if (status != null) {
            TextView st = Ui.text(this, status, 13, status.startsWith("Last run") ? Ui.MUTED : Ui.CORAL_TEXT, false);
            st.setPadding(0, dp(4), 0, 0);
            content.addView(st);
        }
    }

    private void vault() {
        section("Markdown vault");
        Uri folder = VaultFolder.folder(this);
        content.addView(Ui.text(this, "Exports your portrait, a note per person, chat and day, and your to-dos as "
                + "Markdown with [[links]], for Obsidian or any notes app. The files are not encrypted: anything that "
                + "can read that folder can read them.", 14, Ui.MUTED, false));
        if (folder == null) {
            content.addView(Ui.button(this, "Choose an empty folder", Ui.Style.PRIMARY, v -> pick()), buttonParams());
            return;
        }
        TextView where = Ui.text(this, "Folder · " + VaultFolder.label(folder), 15, Ui.INK, true);
        where.setPadding(0, dp(12), 0, 0);
        content.addView(where);
        String status = VaultFolder.status(this);
        if (status != null) {
            TextView st = Ui.text(this, status, 13, status.startsWith("Exported") ? Ui.MUTED : Ui.CORAL_TEXT, false);
            st.setPadding(0, dp(4), 0, 0);
            content.addView(st);
        }
        content.addView(Ui.button(this, exporting ? "Exporting…" : "Export now", Ui.Style.PRIMARY, v -> exportNow()), buttonParams());
        CheckBox auto = new CheckBox(this);
        auto.setText("Refresh after each daily sync");
        auto.setTypeface(Ui.font(this, false));
        auto.setTextColor(Ui.INK);
        auto.setButtonTintList(ColorStateList.valueOf(Ui.INK));
        auto.setChecked(VaultFolder.auto(this));
        auto.setOnCheckedChangeListener((b, on) -> VaultFolder.setAuto(this, on));
        content.addView(auto, buttonParams());
        content.addView(Ui.button(this, "Choose another folder", Ui.Style.QUIET, v -> pick()), buttonParams());
        content.addView(Ui.button(this, "Stop exporting", Ui.Style.QUIET, v -> { VaultFolder.forget(this); load(); }), buttonParams());
    }

    private void pick() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
        } catch (android.content.ActivityNotFoundException none) {
            content.addView(Ui.text(this, "This phone has no folder picker.", 14, Ui.CORAL_TEXT, false));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            VaultFolder.choose(this, data.getData());
            exportNow();
        } catch (SecurityException refused) {
            content.addView(Ui.text(this, "Android didn't let GMind keep access to that folder. Try another.", 14, Ui.CORAL_TEXT, false));
        }
    }

    private void exportNow() {
        if (exporting) return;
        exporting = true;
        load();
        io.execute(() -> {
            try { VaultFolder.export(this, KnowledgeStore.get(this), System.currentTimeMillis()); }
            catch (Exception storeClosed) { /* status unchanged; shown on the next try */ }
            main.post(() -> { if (!destroyed) { exporting = false; load(); } });
        });
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        return p;
    }

    private void section(String name) {
        TextView t = Ui.text(this, name, 22, Ui.INK, true);
        t.setPadding(0, dp(28), 0, dp(8));
        content.addView(t);
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
