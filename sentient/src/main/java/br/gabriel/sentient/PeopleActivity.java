package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * People across apps. "Same person?" cards come first: the same name in two apps is only merged
 * when you say so. The same email address in Gmail, Calendar and Drive merges on its own.
 */
public final class PeopleActivity extends Activity {
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
    }

    @Override protected void onResume() { super.onResume(); load(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    private void load() {
        io.execute(() -> {
            List<People.Suggestion> suggestions = new ArrayList<>();
            List<People.Person> people = new ArrayList<>();
            try {
                Db db = KnowledgeStore.get(this);
                suggestions = People.suggestions(db, 20);
                people = People.list(db, 300);
            } catch (Exception failure) { /* shown as empty */ }
            final List<People.Suggestion> s = suggestions;
            final List<People.Person> p = people;
            main.post(() -> { if (!destroyed) show(s, p); });
        });
    }

    void show(List<People.Suggestion> suggestions, List<People.Person> people) {
        content.removeAllViews();
        content.addView(Ui.title(this, "People", "People"));
        if (!suggestions.isEmpty()) {
            section("Same person?");
            content.addView(Ui.text(this, "Merging keeps both apps' messages under one name. You can separate them again.",
                    14, Ui.MUTED, false));
            for (People.Suggestion s : suggestions) {
                content.addView(Ui.divider(this));
                LinearLayout card = Ui.column(this);
                card.setPadding(0, dp(14), 0, dp(14));
                card.addView(Ui.text(this, s.a.name + (s.a.me ? " (you)" : "") + "  ·  " + s.b.name, 17, Ui.INK, true));
                TextView why = Ui.text(this, s.reason, 13, Ui.MUTED, false);
                why.setPadding(0, dp(4), 0, 0);
                card.addView(why);
                LinearLayout actions = Ui.row(this);
                LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
                actions.addView(Ui.button(this, "Merge", Ui.Style.PRIMARY, v -> change(db -> People.merge(db, s.a.id, s.b.id))), left);
                LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
                right.leftMargin = dp(10);
                actions.addView(Ui.button(this, "Not the same", Ui.Style.QUIET, v -> change(db -> People.keepApart(db, s.a.id, s.b.id))), right);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
                ap.topMargin = dp(10);
                card.addView(actions, ap);
                content.addView(card);
            }
            content.addView(Ui.divider(this));
        }
        section("Everyone");
        if (people.isEmpty()) content.addView(Ui.text(this, "No one yet. People appear as messages come in.", 15, Ui.MUTED, false));
        for (People.Person p : people) {
            content.addView(Ui.divider(this));
            content.addView(row(p));
        }
        if (!people.isEmpty()) content.addView(Ui.divider(this));
    }

    private LinearLayout row(People.Person p) {
        LinearLayout r = Ui.column(this);
        r.setPadding(0, dp(12), 0, dp(12));
        r.addView(Ui.text(this, p.name + (p.me ? " (you)" : ""), 17, Ui.INK, true));
        StringBuilder meta = new StringBuilder(Digest.count(p.items, "item"));
        if (p.sources != null) meta.append(" · ").append(Digest.sources(p.sources));
        if (p.lastTs != null) meta.append(" · last ").append(SentientActivity.ago(p.lastTs));
        TextView m = Ui.text(this, meta.toString(), 13, Ui.MUTED, false);
        m.setPadding(0, dp(4), 0, 0);
        r.addView(m);
        r.setOnClickListener(v -> startActivity(new Intent(this, PersonActivity.class).putExtra(PersonActivity.EXTRA_ID, p.id)));
        r.setContentDescription("Open " + p.name);
        return r;
    }

    interface Change { void apply(Db db) throws Exception; }

    private void change(Change change) {
        io.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                db.transaction(() -> { change.apply(db); return null; });
                Portrait.write(db, System.currentTimeMillis(), java.time.ZoneId.systemDefault());
            } catch (Exception failure) { /* the list reloads as it is */ }
            main.post(() -> { if (!destroyed) load(); });
        });
    }

    private void section(String name) {
        TextView t = Ui.text(this, name, 22, Ui.INK, true);
        t.setPadding(0, dp(28), 0, dp(8));
        content.addView(t);
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
