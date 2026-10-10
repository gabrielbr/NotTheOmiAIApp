package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ask questions of your knowledge; answers cite the messages and recordings they came from. The
 * chat lives in AskSession, so it's still here after leaving the screen, and an answer still being
 * written carries on; only Stop cancels it.
 */
public final class AskActivity extends Activity implements AskSession.Observer {
    private static final Pattern NUMBERED = Pattern.compile("\\[(\\d{1,3})\\]");

    private AskSession session;
    private LinearLayout thread, setup;
    private ScrollView scroll;
    private EditText question;
    private Button send;
    private TextView status, footer, newChat;
    /** The view showing the running answer's text, updated as it streams. */
    private TextView live;
    private int shownCount = -1;

    /** A question typed elsewhere (the home search box): asked as soon as the screen opens. */
    static final String EXTRA_QUESTION = "question";

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
        newChat = Ui.text(this, "New chat", 14, Ui.MUTED, true);
        newChat.setPadding(0, dp(10), 0, 0);
        newChat.setContentDescription("Start a new chat");
        newChat.setOnClickListener(v -> session.clear());
        newChat.setVisibility(View.GONE);
        content.addView(newChat, new LinearLayout.LayoutParams(-2, -2));
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
        send = Ui.button(this, "Ask", Ui.Style.PRIMARY, v -> { if (session.running()) session.stop(); else submit(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(52));
        sp.leftMargin = dp(8);
        row.addView(send, sp);
        bottom.addView(row);
        footer = Ui.text(this, "", 12, Ui.MUTED, false);
        footer.setPadding(0, dp(8), 0, 0);
        bottom.addView(footer);
        page.addView(bottom);
        session = AskSession.get(this);
        String carried = state == null ? getIntent().getStringExtra(EXTRA_QUESTION) : null; // not again on rotation
        if (carried != null && !carried.trim().isEmpty()) {
            question.setText(carried.trim());
            question.setSelection(question.length());
            submit(); // when Ask isn't set up yet, the question stays in the field
        }
    }

    @Override protected void onResume() {
        super.onResume();
        session.observe(this);
        shownCount = -1;
        changed();
        // Whether Gemini Nano can answer on this phone decides what setup "On this phone" needs.
        if (AskBackends.local(this)) Nano.refresh(() -> runOnUiThread(() -> { if (!isFinishing()) refreshSetup(); }));
    }

    @Override protected void onPause() {
        session.observe(null); // the answer keeps going; it's shown when you come back
        super.onPause();
    }

    /** Redraws the chat when exchanges are added or finish; streams text into the live answer. */
    @Override public void changed() {
        List<AskSession.Exchange> all = session.exchanges();
        AskSession.Exchange last = all.isEmpty() ? null : all.get(all.size() - 1);
        int finished = 0;
        for (AskSession.Exchange e : all) if (!e.running) finished++;
        int key = all.size() * 1000 + finished;
        if (key != shownCount) {
            shownCount = key;
            render(all);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        } else if (last != null && last.running && live != null) {
            live.setText(last.partial);
        }
        boolean running = session.running();
        send.setText(running ? "Stop" : "Ask");
        if (running && last.status != null) { status.setText(last.status); status.setVisibility(View.VISIBLE); }
        else status.setVisibility(View.GONE);
        newChat.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        refreshSetup();
    }

    private void render(List<AskSession.Exchange> all) {
        thread.removeAllViews();
        live = null;
        for (AskSession.Exchange e : all) {
            TextView asked = Ui.text(this, e.question, 17, Ui.INK, true);
            asked.setPadding(0, dp(24), 0, dp(8));
            thread.addView(asked);
            TextView answer = Ui.text(this, "", 16, Ui.INK, false);
            thread.addView(answer);
            if (e.running) { answer.setText(e.partial); live = answer; }
            else if (e.error != null) { answer.setText(e.error); answer.setTextColor(Ui.CORAL_TEXT); }
            else if (e.answer != null) showAnswer(answer, new LlmBackend.Answer(e.answer, e.notice), e.ids, e.sources, e.question);
        }
    }

    void refreshSetup() {
        setup.removeAllViews();
        String missing = AskBackends.missingSetup(this);
        footer.setText(missing == null ? AskBackends.footer(this) : "");
        boolean running = session.running();
        send.setEnabled(missing == null || running);
        if (missing != null) {
            Ui.gap(setup, 20);
            TextView why = Ui.text(this, missing, 15, Ui.MUTED, false);
            setup.addView(why);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(12);
            setup.addView(Ui.button(this, "Set up", Ui.Style.DARK,
                    v -> startActivity(new Intent(this, AskSettingsActivity.class))), bp);
        } else if (session.exchanges().isEmpty() && thread.getChildCount() == 0) {
            Ui.gap(setup, 12);
            setup.addView(Ui.text(this, "Try: \"What did Ana and I plan for Sunday?\" or \"What did I talk about on Tuesday?\"",
                    15, Ui.MUTED, false));
        }
    }

    private void submit() {
        String q = question.getText().toString().trim();
        if (q.isEmpty() || session.running() || AskBackends.missingSetup(this) != null) return;
        question.setText("");
        session.ask(q);
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

    private int dp(int v) { return Ui.dp(this, v); }
}
