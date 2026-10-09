package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One person: their names in each app (separate a wrong merge), where you talk, and recent items. */
public final class PersonActivity extends Activity {
    static final String EXTRA_ID = "person";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private boolean destroyed;
    private long id;

    static final class Detail {
        People.Person person;
        List<People.Identity> identities = new ArrayList<>();
        List<Object[]> chats = new ArrayList<>();
        List<Items.Item> recent = new ArrayList<>();
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        id = getIntent().getLongExtra(EXTRA_ID, -1);
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
            Detail d = new Detail();
            try {
                Db db = KnowledgeStore.get(this);
                d.person = People.get(db, id);
                if (d.person != null) {
                    d.identities = People.identities(db, id);
                    d.chats = People.conversations(db, id, 10);
                    for (long item : People.recentItems(db, id, 20)) {
                        Items.Item i = Items.get(db, item);
                        if (i != null) d.recent.add(i);
                    }
                }
            } catch (Exception failure) { /* shown as missing */ }
            main.post(() -> { if (!destroyed) show(d); });
        });
    }

    void show(Detail d) {
        content.removeAllViews();
        if (d.person == null) {
            content.addView(Ui.text(this, "This person was merged or removed.", 15, Ui.MUTED, false));
            return;
        }
        content.addView(Ui.title(this, d.person.name, null));
        TextView meta = Ui.text(this, Digest.count(d.person.items, "item") + (d.person.me ? " · this is you" : ""), 14, Ui.MUTED, false);
        meta.setPadding(0, dp(6), 0, 0);
        content.addView(meta);
        content.addView(Ui.button(this, "Rename", Ui.Style.QUIET, v -> rename(d.person.name)), buttonParams());

        section("In each app");
        for (People.Identity i : d.identities) {
            content.addView(Ui.divider(this));
            LinearLayout r = Ui.row(this);
            r.setPadding(0, dp(10), 0, dp(10));
            String handle = i.handle.startsWith("email:") ? i.handle.substring(6) : i.handle.startsWith("name:") || "me".equals(i.handle) ? null : i.handle;
            String label = People.sourceName(i.source) + " · " + (i.name != null ? i.name : handle != null ? handle : "you")
                    + (i.name != null && handle != null ? " (" + handle + ")" : "") + " · " + Digest.count(i.items, "item");
            r.addView(Ui.text(this, label, 14, Ui.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            if (d.identities.size() > 1)
                r.addView(Ui.button(this, "Separate", Ui.Style.QUIET, v -> change(db -> People.separate(db, i.id, System.currentTimeMillis()))));
            content.addView(r);
        }
        content.addView(Ui.divider(this));

        if (!d.chats.isEmpty()) {
            section("Where you talk");
            for (Object[] c : d.chats) {
                TextView t = Ui.text(this, "• " + c[1] + " (" + People.sourceName((String) c[2]) + ") · "
                        + Digest.count(((Number) c[3]).longValue(), "item"), 15, Ui.INK, false);
                t.setPadding(0, dp(4), 0, dp(4));
                content.addView(t);
            }
        }
        if (!d.recent.isEmpty()) {
            section("Recent");
            for (Items.Item item : d.recent) {
                content.addView(Ui.divider(this));
                LinearLayout r = Ui.column(this);
                r.setPadding(0, dp(10), 0, dp(10));
                r.addView(Ui.text(this, SentientActivity.metaOf(this, item.source, item.author, item.fromMe, item.ts)
                        + (item.conversation != null ? " · " + item.conversation : ""), 13, Ui.MUTED, false));
                TextView text = Ui.text(this, item.text, 15, Ui.INK, false);
                text.setMaxLines(2);
                text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                text.setPadding(0, dp(4), 0, 0);
                r.addView(text);
                r.setOnClickListener(v -> startActivity(new Intent(this, ItemActivity.class).putExtra(ItemActivity.EXTRA_ID, item.id)));
                content.addView(r);
            }
            content.addView(Ui.divider(this));
        }
    }

    private void rename(String current) {
        EditText field = new EditText(this);
        field.setText(current);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setTypeface(Ui.font(this, false));
        LinearLayout box = Ui.column(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field);
        new AlertDialog.Builder(this).setTitle("Rename").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dlg, w) -> {
                    String name = field.getText().toString();
                    if (!name.trim().isEmpty()) change(db -> People.rename(db, id, name));
                })
                .show();
    }

    private void change(PeopleActivity.Change change) {
        io.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                db.transaction(() -> { change.apply(db); return null; });
                Portrait.write(db, System.currentTimeMillis(), java.time.ZoneId.systemDefault());
            } catch (Exception failure) { /* reload shows the current state */ }
            main.post(() -> { if (!destroyed) load(); });
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
