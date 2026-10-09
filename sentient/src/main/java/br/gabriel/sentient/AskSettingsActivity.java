package br.gabriel.sentient;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Who answers questions: the Claude API key and model. */
public final class AskSettingsActivity extends Activity {
    private LinearLayout content;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        draw();
    }

    void draw() {
        content.removeAllViews();
        content.addView(Ui.title(this, "Ask settings", "Ask"));

        section("Claude");
        content.addView(Ui.text(this, "GMind sends Claude your question and only the messages and recordings it looks up "
                + "to answer. Nothing else leaves the phone. You need your own API key from console.anthropic.com; "
                + "usage is billed to your Anthropic account.", 14, Ui.MUTED, false));
        Ui.gap(content, 14);
        String hint = AskSettings.keyHint(this);
        if (hint != null) {
            content.addView(Ui.text(this, "Key saved · ends in …" + hint, 15, Ui.INK, true));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            content.addView(Ui.button(this, "Remove key", Ui.Style.DANGER, v -> { AskSettings.deleteKey(this); draw(); }), rp);
        } else {
            EditText key = new EditText(this);
            key.setHint("sk-ant-…");
            key.setSingleLine(true);
            key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            key.setTypeface(Ui.font(this, false));
            key.setTextColor(Ui.INK);
            GradientDrawable field = Ui.shape(this, Ui.SURFACE, 4);
            field.setStroke(Math.max(1, dp(1)), Ui.LINE);
            key.setBackground(field);
            key.setPadding(dp(14), 0, dp(14), 0);
            key.setContentDescription("Claude API key");
            content.addView(key, new LinearLayout.LayoutParams(-1, dp(52)));
            TextView error = Ui.text(this, "", 13, Ui.CORAL_TEXT, false);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(10);
            content.addView(Ui.button(this, "Save key", Ui.Style.PRIMARY, v -> {
                String value = key.getText().toString().trim();
                if (!value.startsWith("sk-ant-") || value.length() < 20) { error.setText("That doesn't look like a Claude API key (sk-ant-…)."); return; }
                try { AskSettings.saveKey(this, value); draw(); }
                catch (Exception failed) { error.setText("The key couldn't be saved on this phone."); }
            }), bp);
            error.setPadding(0, dp(6), 0, 0);
            content.addView(error);
        }

        section("Model");
        RadioGroup models = new RadioGroup(this);
        String current = AskSettings.model(this);
        String[][] options = {
                {AskSettings.HAIKU, "Haiku 5.5 · fastest and cheapest, about $0.002 a question"},
                {AskSettings.SONNET, "Sonnet 5.5 · better at connecting the dots, about $0.03"},
                {AskSettings.OPUS, "Opus 5.5 · the most capable, about $0.07"}};
        for (String[] option : options) {
            RadioButton b = new RadioButton(this);
            b.setText(option[1]);
            b.setTextSize(15);
            b.setTypeface(Ui.font(this, false));
            b.setTextColor(Ui.INK);
            b.setButtonTintList(ColorStateList.valueOf(Ui.INK));
            b.setPadding(dp(8), dp(10), 0, dp(10));
            b.setId(android.view.View.generateViewId());
            b.setChecked(option[0].equals(current));
            b.setOnClickListener(v -> AskSettings.setModel(this, option[0]));
            models.addView(b);
        }
        content.addView(models);
        TextView price = Ui.text(this, "Costs are rough estimates for a typical question with a few lookups.", 12, Ui.MUTED, false);
        price.setPadding(0, dp(6), 0, 0);
        content.addView(price);
    }

    private void section(String name) {
        TextView t = Ui.text(this, name, 22, Ui.INK, true);
        t.setPadding(0, dp(28), 0, dp(8));
        content.addView(t);
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
