package br.gabriel.sentient;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates for GMind and GVoice from the project's GitHub releases. GVoice stays offline: GMind
 * downloads its update, checks it, and Android asks you to confirm the install.
 */
public final class UpdatesActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private boolean visible, destroyed, checking;
    private String checkError;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            draw();
            UpdateInstaller.State s = UpdateInstaller.state;
            if (s == UpdateInstaller.State.DOWNLOADING || s == UpdateInstaller.State.VERIFYING) main.postDelayed(this, 500);
        }
    };

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

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (UpdateInstaller.latest(this) == null) check(); else main.post(tick);
    }

    @Override protected void onPause() { visible = false; main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    private void check() {
        if (checking) return;
        checking = true;
        checkError = null;
        draw();
        io.execute(() -> {
            String error = null;
            try { UpdateInstaller.remember(this, Updates.latest(new UrlHttp()), System.currentTimeMillis()); }
            catch (Exception failed) { error = failed instanceof java.io.IOException ? failed.getMessage() : "Couldn't check for updates."; }
            final String e = error;
            main.post(() -> { if (!destroyed) { checking = false; checkError = e; draw(); } });
        });
    }

    void draw() {
        content.removeAllViews();
        content.addView(Ui.title(this, "Updates", "Updates"));
        Ui.gap(content, 6);
        content.addView(Ui.text(this, "From the project's GitHub releases. Each download is checked against the release's "
                + "SHA-256 and this app's signing key, and Android asks you to confirm before installing. Your recordings "
                + "and GMind's data stay.", 14, Ui.MUTED, false));
        if (checkError != null) error(checkError);
        String latest = UpdateInstaller.latest(this);
        app("GMind", Updates.GMIND, latest);
        app("GVoice", Updates.GVOICE, latest);

        UpdateInstaller.State s = UpdateInstaller.state;
        if (s == UpdateInstaller.State.DOWNLOADING || s == UpdateInstaller.State.VERIFYING) progress();
        else if (UpdateInstaller.message != null) {
            TextView m = Ui.text(this, UpdateInstaller.message, 15, s == UpdateInstaller.State.FAILED ? Ui.CORAL_TEXT : Ui.INK, true);
            m.setPadding(0, dp(16), 0, 0);
            content.addView(m);
        }
        if (!UpdateInstaller.mayInstall(this)) {
            TextView why = Ui.text(this, "To install updates, allow GMind to install apps (Android asks once).", 14, Ui.MUTED, false);
            why.setPadding(0, dp(16), 0, 0);
            content.addView(why);
            content.addView(Ui.button(this, "Allow installing updates", Ui.Style.DARK,
                    v -> startActivity(UpdateInstaller.allowInstallsSettings(this))), params());
        }
        content.addView(Ui.button(this, checking ? "Checking…" : "Check for updates", Ui.Style.QUIET, v -> check()), params());
        long at = UpdateInstaller.checkedAt(this);
        if (at > 0) {
            TextView when = Ui.text(this, "Checked " + SentientActivity.ago(at) + ". GMind also checks once a day.", 13, Ui.MUTED, false);
            when.setPadding(0, dp(6), 0, 0);
            content.addView(when);
        }
        String notes = UpdateInstaller.notes(this);
        if (latest != null && notes != null && !notes.isEmpty()) {
            TextView h = Ui.text(this, "What's in " + latest, 18, Ui.INK, true);
            h.setPadding(0, dp(24), 0, dp(6));
            content.addView(h);
            content.addView(Ui.text(this, notes.length() > 2000 ? notes.substring(0, 2000) + "…" : notes, 13, Ui.MUTED, false));
        }
    }

    private void app(String name, String pkg, String latest) {
        content.addView(Ui.divider(this));
        LinearLayout r = Ui.column(this);
        r.setPadding(0, dp(14), 0, dp(14));
        String installed = UpdateInstaller.installed(this, pkg);
        Updates.Asset asset = UpdateInstaller.asset(this, pkg);
        boolean newer = latest != null && asset != null && Updates.newer(latest, installed);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, name, 17, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (newer) top.addView(Ui.chip(this, "Update", false));
        r.addView(top);
        String meta = (installed == null ? "Not installed" : "Installed " + installed)
                + (latest == null ? "" : " · latest " + latest);
        TextView m = Ui.text(this, meta, 13, Ui.MUTED, false);
        m.setPadding(0, dp(4), 0, 0);
        r.addView(m);
        if (newer && installed != null) {
            boolean busy = UpdateInstaller.state == UpdateInstaller.State.DOWNLOADING || UpdateInstaller.state == UpdateInstaller.State.VERIFYING;
            r.addView(Ui.button(this, "Update " + name + " · " + Updates.size(asset.bytes), Ui.Style.PRIMARY, v -> {
                if (!UpdateInstaller.mayInstall(this)) { startActivity(UpdateInstaller.allowInstallsSettings(this)); return; }
                if (UpdateInstaller.start(this, pkg)) main.post(tick);
            }), params());
            if (busy) r.getChildAt(r.getChildCount() - 1).setEnabled(false);
            if (Updates.GVOICE.equals(pkg) && asset.bytes > 100_000_000L) {
                TextView wifi = Ui.text(this, "Large download: use Wi-Fi.", 13, Ui.MUTED, false);
                wifi.setPadding(0, dp(6), 0, 0);
                r.addView(wifi);
            }
        }
        content.addView(r);
    }

    private void progress() {
        String name = Updates.GVOICE.equals(UpdateInstaller.working) ? "GVoice" : "GMind";
        String line = UpdateInstaller.state == UpdateInstaller.State.VERIFYING ? "Checking " + name + "…"
                : "Downloading " + name + " · " + Updates.size(UpdateInstaller.done) + " of " + Updates.size(UpdateInstaller.total);
        TextView t = Ui.text(this, line, 15, Ui.INK, true);
        t.setPadding(0, dp(16), 0, 0);
        content.addView(t);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) (1000L * UpdateInstaller.done / Math.max(1, UpdateInstaller.total)));
        bar.setProgressTintList(ColorStateList.valueOf(Ui.MINT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(12));
        bp.topMargin = dp(10);
        content.addView(bar, bp);
        content.addView(Ui.button(this, "Cancel", Ui.Style.QUIET, v -> UpdateInstaller.cancel()), params());
    }

    private void error(String message) {
        TextView t = Ui.text(this, message, 14, Ui.CORAL_TEXT, false);
        t.setPadding(0, dp(10), 0, 0);
        content.addView(t);
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
