package br.gabriel.sentient;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Who answers questions: Claude (key and model) or the model on this phone (download). */
public final class AskSettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout content, local;
    private boolean visible, destroyed;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible || !AskBackends.local(AskSettingsActivity.this)) return;
            io.execute(() -> {
                LocalModel.Status status = LocalModel.poll(AskSettingsActivity.this);
                main.post(() -> { if (!destroyed && visible) showLocal(status); });
            });
        }
    };

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

    @Override protected void onResume() { super.onResume(); visible = true; main.post(poll); }
    @Override protected void onPause() { visible = false; main.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    void draw() {
        content.removeAllViews();
        content.addView(Ui.title(this, "Ask settings", "Ask"));

        section("Answer with");
        RadioGroup who = new RadioGroup(this);
        boolean onPhone = AskBackends.local(this);
        who.addView(radio("Claude · best answers; your question and the messages it looks up go to Anthropic", !onPhone,
                () -> { AskSettings.setBackend(this, AskSettings.CLAUDE); draw(); }));
        who.addView(radio("On this phone · private and offline; shorter, simpler answers", onPhone,
                () -> { AskSettings.setBackend(this, AskSettings.LOCAL); draw(); main.post(poll); }));
        content.addView(who);
        if (onPhone) { drawLocal(); return; }

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
        for (String[] option : options)
            models.addView(radio(option[1], option[0].equals(current), () -> AskSettings.setModel(this, option[0])));
        content.addView(models);
        TextView price = Ui.text(this, "Costs are rough estimates for a typical question with a few lookups.", 12, Ui.MUTED, false);
        price.setPadding(0, dp(6), 0, 0);
        content.addView(price);
    }

    private RadioButton radio(String label, boolean checked, Runnable chosen) {
        RadioButton b = new RadioButton(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Ui.font(this, false));
        b.setTextColor(Ui.INK);
        b.setButtonTintList(ColorStateList.valueOf(Ui.INK));
        b.setPadding(dp(8), dp(10), 0, dp(10));
        b.setId(android.view.View.generateViewId());
        b.setChecked(checked);
        b.setOnClickListener(v -> chosen.run());
        return b;
    }

    private void drawLocal() {
        section("On this phone");
        content.addView(Ui.text(this, LocalModel.NAME + " runs on the phone's processor: nothing you ask leaves it, and it "
                + "works offline. It needs a one-time " + LocalModel.gb(LocalModel.BYTES) + " download and answers more "
                + "slowly and simply than Claude.", 14, Ui.MUTED, false));
        String unsupported = LocalModel.unsupported(this);
        if (unsupported != null) {
            TextView warn = Ui.text(this, unsupported, 15, Ui.CORAL_TEXT, false);
            warn.setPadding(0, dp(12), 0, 0);
            content.addView(warn);
            return;
        }
        local = Ui.column(this);
        content.addView(local);
        showLocal(new LocalModel.Status(LocalModel.ready(this) ? LocalModel.State.READY : LocalModel.State.NONE, 0,
                LocalModel.BYTES, null));
    }

    /** The model's state: download button, progress, ready (with delete), or a failure. */
    void showLocal(LocalModel.Status status) {
        if (local == null) return;
        local.removeAllViews();
        Ui.gap(local, 14);
        switch (status.state) {
            case READY:
                local.addView(Ui.text(this, "Model ready · " + LocalModel.gb(LocalModel.BYTES), 15, Ui.INK, true));
                local.addView(Ui.button(this, "Delete model", Ui.Style.DANGER, v -> { LocalModel.delete(this); draw(); }),
                        new LinearLayout.LayoutParams(-1, -2));
                return;
            case DOWNLOADING:
            case VERIFYING:
                String line = status.message != null ? status.message
                        : "Downloading · " + LocalModel.gb(status.done) + " of " + LocalModel.gb(status.total);
                local.addView(Ui.text(this, line, 15, Ui.INK, true));
                ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(1000);
                bar.setProgress((int) (1000L * status.done / Math.max(1, status.total)));
                bar.setProgressTintList(ColorStateList.valueOf(Ui.MINT));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(12));
                bp.topMargin = dp(10);
                local.addView(bar, bp);
                local.addView(Ui.button(this, "Cancel download", Ui.Style.QUIET, v -> { LocalModel.cancel(this); draw(); }),
                        new LinearLayout.LayoutParams(-1, -2));
                main.postDelayed(poll, 1000);
                return;
            default:
                if (status.state == LocalModel.State.FAILED && status.message != null) {
                    TextView failed = Ui.text(this, status.message, 15, Ui.CORAL_TEXT, false);
                    failed.setPadding(0, 0, 0, dp(10));
                    local.addView(failed);
                }
                TextView error = Ui.text(this, "", 13, Ui.CORAL_TEXT, false);
                local.addView(Ui.button(this, "Download model · " + LocalModel.gb(LocalModel.BYTES), Ui.Style.PRIMARY, v -> {
                    try { LocalModel.download(this); main.post(poll); }
                    catch (Exception cant) { error.setText(cant.getMessage()); }
                }), new LinearLayout.LayoutParams(-1, -2));
                error.setPadding(0, dp(6), 0, 0);
                local.addView(error);
                CheckBox wifi = new CheckBox(this);
                wifi.setText("Download only on Wi-Fi");
                wifi.setTypeface(Ui.font(this, false));
                wifi.setTextColor(Ui.INK);
                wifi.setButtonTintList(ColorStateList.valueOf(Ui.INK));
                wifi.setChecked(LocalModel.wifiOnly(this));
                wifi.setOnCheckedChangeListener((b, on) -> LocalModel.setWifiOnly(this, on));
                local.addView(wifi);
        }
    }

    private void section(String name) {
        TextView t = Ui.text(this, name, 22, Ui.INK, true);
        t.setPadding(0, dp(28), 0, dp(8));
        content.addView(t);
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
