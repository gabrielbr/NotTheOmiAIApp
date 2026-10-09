package br.gabriel.sentient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connect the sources that live on servers: Composio toolkits (Gmail, Calendar, Drive) with the
 * person's Composio key, and a Matrix account. Read-only; tokens and keys are kept encrypted.
 */
public final class ConnectActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private boolean destroyed, working;
    private String composioError, matrixError;
    private boolean tokenMode;

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

    @Override protected void onResume() {
        super.onResume();
        checkPending();
    }

    @Override protected void onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy(); }

    void draw() {
        content.removeAllViews();
        content.addView(Ui.title(this, "Connect sources", "Connect"));
        Ui.gap(content, 6);
        content.addView(Ui.text(this, "GMind only reads. It never sends, edits or deletes anything in these accounts.",
                14, Ui.MUTED, false));
        drawComposio();
        drawMatrix();
        drawChats();
    }

    // --- Composio ------------------------------------------------------------------------------

    private void drawComposio() {
        section("Gmail, Calendar and Drive");
        content.addView(Ui.text(this, "Through Composio (composio.dev), with your own Composio API key. Composio's "
                + "servers fetch this data from Google and hand it to GMind, so Composio sees it. GMind keeps the key "
                + "encrypted on this phone.", 14, Ui.MUTED, false));
        Ui.gap(content, 14);
        boolean hasKey = SecretStore.COMPOSIO.has(this);
        if (!hasKey) {
            EditText key = field("Composio API key", true);
            content.addView(key, new LinearLayout.LayoutParams(-1, dp(52)));
            error(composioError);
            content.addView(Ui.button(this, "Save key", Ui.Style.PRIMARY, v -> {
                String value = key.getText().toString().trim();
                if (value.length() < 16 || value.contains(" ")) { composioError = "That doesn't look like a Composio API key."; draw(); return; }
                try { SecretStore.COMPOSIO.save(this, value); composioError = null; }
                catch (Exception failed) { composioError = "The key couldn't be saved on this phone."; }
                draw();
            }), buttonParams());
            return;
        }
        String hint = SecretStore.COMPOSIO.hint(this);
        content.addView(Ui.text(this, "Key saved" + (hint == null ? "" : " · ends in …" + hint), 15, Ui.INK, true));
        error(composioError);
        Ui.gap(content, 14);
        for (ComposioToolkit toolkit : ComposioToolkit.ALL) toolkitRow(toolkit);
        Ui.gap(content, 10);
        content.addView(Ui.button(this, "Remove Composio key", Ui.Style.QUIET, v -> {
            SecretStore.COMPOSIO.delete(this);
            draw();
        }), buttonParams());
    }

    private void toolkitRow(ComposioToolkit toolkit) {
        content.addView(Ui.divider(this));
        LinearLayout r = Ui.column(this);
        r.setPadding(0, dp(14), 0, dp(14));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.text(this, toolkit.displayName(), 17, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        String state = Connections.state(this, toolkit);
        if (Connections.CONNECTED.equals(state)) top.addView(Ui.chip(this, "Connected", false));
        r.addView(top);
        TextView reads = Ui.text(this, toolkit.reads(), 13, Ui.MUTED, false);
        reads.setPadding(0, dp(6), 0, 0);
        r.addView(reads);
        if (Connections.CONNECTED.equals(state)) {
            r.addView(Ui.button(this, "Disconnect", Ui.Style.QUIET, v -> disconnect(toolkit, false)), buttonParams());
            r.addView(Ui.button(this, "Disconnect and delete its items", Ui.Style.DANGER,
                    v -> confirmDelete(toolkit.displayName(), () -> disconnect(toolkit, true))), buttonParams());
        } else if (Connections.PENDING.equals(state)) {
            TextView waiting = Ui.text(this, "Waiting for you to finish connecting on Composio's page…", 15, Ui.INK, false);
            waiting.setPadding(0, dp(10), 0, 0);
            r.addView(waiting);
            r.addView(Ui.button(this, "Check again", Ui.Style.DARK, v -> checkPending()), buttonParams());
            r.addView(Ui.button(this, "Cancel", Ui.Style.QUIET, v -> { Connections.remove(this, toolkit); draw(); }),
                    buttonParams());
        } else {
            r.addView(Ui.button(this, working ? "Opening…" : "Connect " + toolkit.displayName(), Ui.Style.PRIMARY,
                    v -> connect(toolkit)), buttonParams());
        }
        content.addView(r);
    }

    private void connect(ComposioToolkit toolkit) {
        if (working) return;
        working = true;
        composioError = null;
        draw();
        String user = Connections.composioUser(this);
        io.execute(() -> {
            String failure = null;
            String url = null;
            try {
                ComposioClient client = composio();
                ComposioClient.Link link = client.link(client.authConfig(toolkit.slug()), user);
                Connections.pending(this, toolkit, link.connectedAccountId);
                url = link.redirectUrl;
            } catch (ComposioClient.ComposioException refused) {
                failure = refused.getMessage();
            } catch (IOException offline) {
                failure = "Couldn't reach Composio. Check your internet connection.";
            }
            final String error = failure, open = url;
            main.post(() -> {
                if (destroyed) return;
                working = false;
                composioError = error;
                draw();
                if (open != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(open)));
            });
        });
    }

    /** After the person returns from Composio's page: mark accounts that became active. */
    private void checkPending() {
        if (!SecretStore.COMPOSIO.has(this)) return;
        io.execute(() -> {
            boolean any = false;
            String failure = null;
            for (ComposioToolkit toolkit : ComposioToolkit.ALL) {
                if (!Connections.PENDING.equals(Connections.state(this, toolkit))) continue;
                try {
                    String status = composio().status(Connections.account(this, toolkit));
                    if ("ACTIVE".equalsIgnoreCase(status)) { Connections.connected(this, toolkit); any = true; }
                    else if (status == null || "FAILED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status))
                        Connections.remove(this, toolkit);
                } catch (ComposioClient.ComposioException refused) {
                    failure = refused.getMessage();
                } catch (IOException offline) {
                    failure = "Couldn't reach Composio to check the connection.";
                }
            }
            final boolean synced = any;
            final String error = failure;
            main.post(() -> {
                if (destroyed) return;
                if (synced) SyncJobService.syncNow(this);
                composioError = error;
                draw();
            });
        });
    }

    private void disconnect(ComposioToolkit toolkit, boolean deleteItems) {
        String account = Connections.account(this, toolkit);
        Connections.remove(this, toolkit);
        draw();
        io.execute(() -> {
            String note = null;
            try { if (account != null) composio().disconnect(account); }
            catch (Exception unreachable) { note = "Removed from GMind. Composio couldn't be reached to remove the connection there; you can remove it on composio.dev."; }
            if (deleteItems) note = forget(toolkit.sourceId(), note);
            final String message = note;
            main.post(() -> { if (!destroyed) { composioError = message; draw(); } });
        });
    }

    private ComposioClient composio() throws ComposioClient.ComposioException {
        String key = SecretStore.COMPOSIO.read(this);
        if (key == null) throw new ComposioClient.ComposioException(0, "Save your Composio key again.");
        return new ComposioClient(new UrlHttp(), key, ComposioClient.BASE);
    }

    // --- Matrix --------------------------------------------------------------------------------

    private void drawMatrix() {
        section("Matrix");
        MatrixClient.Session session = SecretStore.MATRIX.has(this) ? MatrixClient.Session.fromJson(SecretStore.MATRIX.read(this)) : null;
        if (session != null) {
            content.addView(Ui.text(this, "Signed in as " + session.userId, 15, Ui.INK, true));
            TextView note = Ui.text(this, "GMind reads your rooms directly from " + session.homeserver.replaceFirst("^https://", "")
                    + ". End-to-end encrypted rooms can't be read yet.", 13, Ui.MUTED, false);
            note.setPadding(0, dp(6), 0, 0);
            content.addView(note);
            error(matrixError);
            content.addView(Ui.button(this, "Sign out", Ui.Style.QUIET, v -> signOut(session, false)), buttonParams());
            content.addView(Ui.button(this, "Sign out and delete its items", Ui.Style.DANGER,
                    v -> confirmDelete("Matrix", () -> signOut(session, true))), buttonParams());
            return;
        }
        content.addView(Ui.text(this, "Reads the unencrypted rooms you joined, straight from your homeserver, from 30 days "
                + "ago on. GMind signs in as its own device, \"" + MatrixClient.DEVICE_NAME + "\"; your password is used "
                + "once and not kept.", 14, Ui.MUTED, false));
        Ui.gap(content, 14);
        EditText user = field(tokenMode ? "Homeserver, e.g. matrix.org" : "User, e.g. @you:matrix.org", false);
        content.addView(user, new LinearLayout.LayoutParams(-1, dp(52)));
        Ui.gap(content, 10);
        EditText secret = field(tokenMode ? "Access token" : "Password", true);
        content.addView(secret, new LinearLayout.LayoutParams(-1, dp(52)));
        error(matrixError);
        content.addView(Ui.button(this, working ? "Signing in…" : "Sign in", Ui.Style.PRIMARY,
                v -> signIn(user.getText().toString(), secret.getText().toString())), buttonParams());
        content.addView(Ui.button(this, tokenMode ? "Use a password instead" : "Use an access token instead", Ui.Style.QUIET,
                v -> { tokenMode = !tokenMode; matrixError = null; draw(); }), buttonParams());
    }

    private void signIn(String who, String secret) {
        if (working) return;
        String name = who.trim();
        if (name.isEmpty() || secret.isEmpty()) {
            matrixError = tokenMode ? "Enter the homeserver and the token." : "Enter your user and password.";
            draw();
            return;
        }
        working = true;
        matrixError = null;
        draw();
        final boolean withToken = tokenMode;
        io.execute(() -> {
            String failure = null;
            try {
                MatrixClient client = new MatrixClient(new UrlHttp());
                String base = client.discover(MatrixClient.serverName(name));
                MatrixClient.Session session = withToken ? client.withToken(base, secret) : client.login(base, name, secret);
                SecretStore.MATRIX.save(this, session.toJson());
            } catch (MatrixClient.MatrixException refused) {
                failure = refused.getMessage();
            } catch (IllegalArgumentException bad) {
                failure = bad.getMessage();
            } catch (IOException offline) {
                failure = "Couldn't reach that homeserver. Check the address and your internet connection.";
            } catch (Exception cantSave) {
                failure = "Signed in, but the session couldn't be saved on this phone.";
            }
            final String error = failure;
            main.post(() -> {
                if (destroyed) return;
                working = false;
                matrixError = error;
                if (error == null) SyncJobService.syncNow(this);
                draw();
            });
        });
    }

    private void signOut(MatrixClient.Session session, boolean deleteItems) {
        SecretStore.MATRIX.delete(this);
        draw();
        io.execute(() -> {
            String note = null;
            try { new MatrixClient(new UrlHttp()).logout(session); }
            catch (IOException unreachable) { note = "Signed out on this phone. The homeserver couldn't be reached; remove the \"" + MatrixClient.DEVICE_NAME + "\" session in another Matrix app."; }
            if (deleteItems) note = forget(MatrixPlugin.ID, note);
            final String message = note;
            main.post(() -> { if (!destroyed) { matrixError = message; draw(); } });
        });
    }

    // --- Chat apps -----------------------------------------------------------------------------

    private void drawChats() {
        section("WhatsApp, Signal and Telegram");
        content.addView(Ui.text(this, "Saved from their notifications as messages arrive, with no account to connect. "
                + "They need GMind's notification access.", 14, Ui.MUTED, false));
        if (!ChatPlugin.accessGranted(this))
            content.addView(Ui.button(this, "Allow notification access", Ui.Style.DARK,
                    v -> startActivity(ChatPlugin.accessSettings())), buttonParams());
        else {
            TextView on = Ui.text(this, "Notification access is on.", 15, Ui.INK, true);
            on.setPadding(0, dp(10), 0, 0);
            content.addView(on);
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Deletes a source's items on the io thread; returns the note to show. */
    private String forget(String sourceId, String note) {
        try {
            Db db = KnowledgeStore.get(this);
            int deleted = db.transaction(() -> Sources.forget(db, sourceId));
            String done = deleted + (deleted == 1 ? " item" : " items") + " deleted.";
            return note == null ? done : note + " " + done;
        } catch (Exception failed) {
            return "The items couldn't be deleted (" + failed.getClass().getSimpleName() + ").";
        }
    }

    private void confirmDelete(String name, Runnable delete) {
        new AlertDialog.Builder(this).setTitle("Delete everything from " + name + "?")
                .setMessage("GMind stops reading " + name + " and deletes what it saved from it on this phone. "
                        + "Nothing changes in the account itself.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (d, w) -> delete.run())
                .show();
    }

    private EditText field(String hint, boolean secret) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setSingleLine(true);
        f.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_URI));
        f.setTypeface(Ui.font(this, false));
        f.setTextColor(Ui.INK);
        f.setHintTextColor(Ui.MUTED);
        GradientDrawable shape = Ui.shape(this, Ui.SURFACE, 4);
        shape.setStroke(Math.max(1, dp(1)), Ui.LINE);
        f.setBackground(shape);
        f.setPadding(dp(14), 0, dp(14), 0);
        f.setContentDescription(hint);
        return f;
    }

    private void error(String message) {
        if (message == null) return;
        TextView t = Ui.text(this, message, 14, Ui.CORAL_TEXT, false);
        t.setPadding(0, dp(10), 0, 0);
        content.addView(t);
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
