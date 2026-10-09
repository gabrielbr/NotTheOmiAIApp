package br.gabriel.sentient;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Which Composio toolkits are connected, by Composio connected-account id. Not secret (the key is
 * in SecretStore), and cheap to read, so the plugin registry can call it often.
 */
final class Connections {
    static final String PENDING = "pending", CONNECTED = "connected";
    private static final String PREFS = "connections", USER = "composio.user";

    private Connections() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** This install's Composio user id: random, so it says nothing about the person. */
    static synchronized String composioUser(Context c) {
        String id = prefs(c).getString(USER, null);
        if (id == null) {
            id = "gmind-" + UUID.randomUUID();
            prefs(c).edit().putString(USER, id).apply();
        }
        return id;
    }

    static String account(Context c, ComposioToolkit toolkit) {
        return prefs(c).getString(toolkit.sourceId() + ".account", null);
    }

    /** PENDING while the person is on Composio's connect page, CONNECTED once active, else null. */
    static String state(Context c, ComposioToolkit toolkit) {
        return account(c, toolkit) == null ? null : prefs(c).getString(toolkit.sourceId() + ".state", null);
    }

    static void pending(Context c, ComposioToolkit toolkit, String accountId) {
        prefs(c).edit().putString(toolkit.sourceId() + ".account", accountId)
                .putString(toolkit.sourceId() + ".state", PENDING).apply();
    }

    static void connected(Context c, ComposioToolkit toolkit) {
        prefs(c).edit().putString(toolkit.sourceId() + ".state", CONNECTED).apply();
    }

    /** Create approved to-dos in Todoist through Composio (off until turned on; needs Todoist connected). */
    static boolean todoistSync(Context c) { return prefs(c).getBoolean("todoist.sync", false); }
    static void setTodoistSync(Context c, boolean on) { prefs(c).edit().putBoolean("todoist.sync", on).apply(); }

    /** The connected Todoist toolkit when direct sync can run, else null. */
    static ComposioToolkit todoistReady(Context c) {
        ComposioToolkit todoist = ComposioToolkit.forSlug("todoist");
        return todoist != null && CONNECTED.equals(state(c, todoist)) && SecretStore.COMPOSIO.has(c) ? todoist : null;
    }

    static void remove(Context c, ComposioToolkit toolkit) {
        prefs(c).edit().remove(toolkit.sourceId() + ".account").remove(toolkit.sourceId() + ".state").apply();
    }
}
