package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;

/**
 * Shows GMind's own Markdown (portrait, digests): headings, list lines and italic notes, with each
 * [#id] citation as a small tappable mint marker that opens the item. Not a general Markdown renderer.
 */
final class MarkdownView {
    private MarkdownView() {}

    static void render(Activity a, LinearLayout target, String markdown) {
        if (markdown == null) return;
        for (String line : markdown.split("\n")) {
            if (line.trim().isEmpty()) continue;
            TextView t;
            if (line.startsWith("# ")) {
                continue; // the screen has its own title
            } else if (line.startsWith("## ")) {
                t = Ui.text(a, line.substring(3), 18, Ui.INK, true);
                t.setPadding(0, Ui.dp(a, 22), 0, Ui.dp(a, 6));
            } else if (line.startsWith("_") && line.endsWith("_")) {
                t = Ui.text(a, line.substring(1, line.length() - 1), 13, Ui.MUTED, false);
                t.setPadding(0, Ui.dp(a, 4), 0, 0);
            } else {
                t = Ui.text(a, cited(a, line.startsWith("- ") ? "• " + line.substring(2) : line), 15, Ui.INK, false);
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setLineSpacing(0, 1.2f);
                t.setPadding(0, Ui.dp(a, 5), 0, Ui.dp(a, 5));
            }
            target.addView(t);
        }
    }

    /** "Ana · 3 items [#41]" with [#41] as a tappable "›" that opens item 41. */
    static CharSequence cited(Activity a, String line) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        Matcher m = Citations.CITE.matcher(line);
        int last = 0;
        while (m.find()) {
            out.append(line.substring(last, m.start()).replaceAll("\\s+$", ""));
            long id = Long.parseLong(m.group(1));
            int start = out.length();
            out.append(" › ");
            out.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    a.startActivity(new Intent(a, ItemActivity.class).putExtra(ItemActivity.EXTRA_ID, id));
                }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(Ui.MINT_INK);
                    paint.bgColor = Ui.MINT;
                    paint.setUnderlineText(false);
                }
            }, start + 1, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        out.append(line.substring(last));
        return out;
    }
}
