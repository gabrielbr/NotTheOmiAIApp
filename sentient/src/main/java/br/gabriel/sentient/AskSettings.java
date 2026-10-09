package br.gabriel.sentient;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Who answers questions, and the Claude API key. The key is kept only encrypted, by a
 * non-exportable Android Keystore key, in no-backup storage; it is never logged or shown again.
 */
final class AskSettings {
    static final String CLAUDE = "claude", LOCAL = "local";
    static final String HAIKU = "claude-haiku-5-5", SONNET = "claude-sonnet-5-5", OPUS = "claude-opus-5-5";
    static final String[] MODELS = {HAIKU, SONNET, OPUS};
    private static final String PREFS = "ask", BACKEND = "backend", MODEL = "model";

    private AskSettings() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String backend(Context c) { return prefs(c).getString(BACKEND, CLAUDE); }
    static void setBackend(Context c, String backend) { prefs(c).edit().putString(BACKEND, backend).apply(); }

    static String model(Context c) {
        String m = prefs(c).getString(MODEL, HAIKU);
        for (String known : MODELS) if (known.equals(m)) return m;
        return HAIKU;
    }
    static void setModel(Context c, String model) { prefs(c).edit().putString(MODEL, model).apply(); }

    static String modelName(String model) {
        switch (model) {
            case OPUS: return "Claude Opus 5.5";
            case SONNET: return "Claude Sonnet 5.5";
            default: return "Claude Haiku 5.5";
        }
    }

    static boolean hasKey(Context c) { return SecretStore.CLAUDE.has(c); }

    /** The saved key's last four characters, for "Key saved · …abcd"; null if none. */
    static String keyHint(Context c) { return SecretStore.CLAUDE.hint(c); }

    static void saveKey(Context c, String key) throws Exception { SecretStore.CLAUDE.save(c, key.trim()); }

    static void deleteKey(Context c) { SecretStore.CLAUDE.delete(c); }

    /** Null when no key is saved or it can no longer be decrypted (e.g. after a Keystore reset). */
    static String apiKey(Context c) { return SecretStore.CLAUDE.read(c); }
}
