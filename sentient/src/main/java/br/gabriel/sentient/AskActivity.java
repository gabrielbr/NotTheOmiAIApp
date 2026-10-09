package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ask questions of your knowledge; answers cite the messages and recordings they came from. */
public final class AskActivity extends Activity {
    private static final Pattern NUMBERED = Pattern.compile("\\[(\\d{1,3})\\]");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<LlmBackend.Turn> history = new ArrayList<>();
    private LinearLayout thread, setup;
    private ScrollView scroll;
    private EditText question;
    private Button send;
    private TextView status, footer;
    private volatile boolean running, cancelled;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        setContentView(page);
        page.addView(Ui.backBar(this, Ui.iconButton(this, R.drawable.ic_settings, "Ask settings", Ui.INK,
                v -> startActivity(new Intent(this, AskSettingsActivity.class)))));
        page.addView(Ui.divider(this));
        scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(Ui.title(this, "Ask your knowledge", "Ask"));
        setup = Ui.column(this);
        content.addView(setup);
        thread = Ui.column(this);
        content.addView(thread);

        page.addView(Ui.divider(this));
        LinearLayout bottom = Ui.column(this);
        bottom.setBackgroundColor(Ui.SURFACE);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(12));
        status = Ui.text(this, "", 13, Ui.MUTED, false);
        status.setVisibility(View.GONE);
        bottom.addView(status);
        LinearLayout row = Ui.row(this);
        question = new EditText(this);
        question.setHint("Ask anything");
        question.setTextSize(16);
        question.setTypeface(Ui.font(this, false));
        question.setTextColor(Ui.INK);
        question.setHintTextColor(Ui.MUTED);
        question.setMaxLines(4);
        question.setImeOptions(EditorInfo.IME_ACTION_SEND);
        question.setOnEditorActionListener((v, action, event) -> { submit(); return true; });
        GradientDrawable field = Ui.shape(this, Ui.SURFACE, 4);
        field.setStroke(Math.max(1, dp(1)), Ui.LINE);
        question.setBackground(field);
        question.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.addView(question, new LinearLayout.LayoutParams(0, -2, 1));
        send = Ui.button(this, "Ask", Ui.Style.PRIMARY, v -> { if (running) cancelled = true; else submit(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(52));
        sp.leftMargin = dp(8);
        row.addView(send, sp);
        bottom.addView(row);
        footer = Ui.text(this, "", 12, Ui.MUTED, false);
        footer.setPadding(0, dp(8), 0, 0);
        bottom.addView(footer);
        page.addView(bottom);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSetup();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelled = true;
        io.shutdownNow();
        super.onDestroy();
    }

    void refreshSetup() {
        setup.removeAllViews();
        String missing = AskBackends.missingSetup(this);
        footer.setText(missing == null ? AskBackends.footer(this) : "");
        send.setEnabled(missing == null || running);
        if (missing != null) {
            Ui.gap(setup, 20);
            TextView why = Ui.text(this, missing, 15, Ui.MUTED, false);
            setup.addView(why);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(12);
            setup.addView(Ui.button(this, "Set up", Ui.Style.DARK,
                    v -> startActivity(new Intent(this, AskSettingsActivity.class))), bp);
        } else if (history.isEmpty() && thread.getChildCount() == 0) {
            Ui.gap(setup, 12);
            setup.addView(Ui.text(this, "Try: \"What did Ana and I plan for Sunday?\" or \"What did I talk about on Tuesday?\"",
                    15, Ui.MUTED, false));
        }
    }

    private void submit() {
        String q = question.getText().toString().trim();
        if (q.isEmpty() || running || AskBackends.missingSetup(this) != null) return;
        question.setText("");
        setup.removeAllViews();
        TextView asked = Ui.text(this, q, 17, Ui.INK, true);
        asked.setPadding(0, dp(24), 0, dp(8));
        thread.addView(asked);
        TextView pending = Ui.text(this, "", 16, Ui.INK, false);
        thread.addView(pending);
        running = true;
        cancelled = false;
        send.setText("Stop");
        showStatus("Thinking…");
        final List<LlmBackend.Turn> past = new ArrayList<>(history);
        io.execute(() -> {
            LlmBackend.Answer answer = null;
            String error = null;
            List<Long> cited = new ArrayList<>();
            List<Items.Item> sources = new ArrayList<>();
            try {
                LlmBackend backend = AskBackends.create(this);
                answer = backend.answer(past, q, new LlmBackend.Listener() {
                    @Override public void status(String s) { main.post(() -> showStatus(s)); }
                    @Override public void partial(String s) { main.post(() -> { if (!destroyed) pending.setText(s); }); }
                }, () -> cancelled);
                Db db = KnowledgeStore.get(this);
                cited = Citations.existing(db, Citations.ids(answer.text));
                for (Long id : cited) { Items.Item item = Items.get(db, id); if (item != null) sources.add(item); }
            } catch (ClaudeBackend.AskException failed) {
                error = failed.getMessage();
            } catch (Exception failed) {
                error = "Something went wrong (" + failed.getClass().getSimpleName() + ").";
            }
            final LlmBackend.Answer done = answer;
            final String problem = error;
            final List<Long> ids = cited;
            final List<Items.Item> found = sources;
            main.post(() -> {
                if (destroyed) return;
                running = false;
                send.setText("Ask");
                status.setVisibility(View.GONE);
                if (done == null) {
                    pending.setText(problem);
                    pending.setTextColor(Ui.CORAL_TEXT);
                } else {
                    history.add(new LlmBackend.Turn(q, done.text));
                    showAnswer(pending, done, ids, found, q);
                }
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    /** The answer with [n] citation links, a notice if any, and the cited sources. */
    void showAnswer(TextView view, LlmBackend.Answer answer, List<Long> ids, List<Items.Item> sources, String query) {
        String text = Citations.numbered(answer.text, ids);
        if (text.isEmpty()) text = "No answer.";
        SpannableString spans = new SpannableString(text);
        Matcher m = NUMBERED.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > ids.size()) continue;
            long id = ids.get(n - 1);
            spans.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { open(id, query); }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(Ui.MINT_INK);
                    paint.bgColor = Ui.MINT;
                    paint.setUnderlineText(false);
                }
            }, m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(spans);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLineSpacing(0, 1.3f);
        LinearLayout parent = (LinearLayout) view.getParent();
        int at = parent.indexOfChild(view) + 1;
        if (answer.notice != null) {
            TextView notice = Ui.text(this, answer.notice, 13, Ui.CORAL_TEXT, false);
            notice.setPadding(0, dp(6), 0, 0);
            parent.addView(notice, at++);
        }
        for (int i = 0; i < sources.size(); i++) {
            Items.Item item = sources.get(i);
            TextView source = Ui.text(this, "[" + (ids.indexOf(item.id) + 1) + "] "
                    + SentientActivity.titleOf(this, item.conversation, item.source) + " · "
                    + SentientActivity.metaOf(this, item.source, item.author, item.fromMe, item.ts), 13, Ui.MUTED, false);
            source.setPadding(0, dp(i == 0 ? 12 : 6), 0, 0);
            source.setOnClickListener(v -> open(item.id, query));
            source.setContentDescription("Open source " + (i + 1));
            parent.addView(source, at++);
        }
    }

    private void open(long id, String query) {
        startActivity(new Intent(this, ItemActivity.class).putExtra(ItemActivity.EXTRA_ID, id)
                .putExtra(ItemActivity.EXTRA_QUERY, query));
    }

    private void showStatus(String s) {
        if (destroyed || !running) return;
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
