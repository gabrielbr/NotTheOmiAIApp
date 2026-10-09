package br.gabriel.sentient.plugin;

/** What the host gives a plugin during one pull. */
public interface PluginContext {
    /** Plugin configuration value (server URL, toolkit name…), or null. */
    String config(String key);

    /** True once the sync was cancelled; long pulls should check it between pages. */
    boolean cancelled();

    /** A secret the user saved (API key, access token), decrypted on demand; null if none.
     * Never log it or put it in an exception message. */
    default String secret(String name) { return null; }

    /** Wall-clock time in milliseconds, so tests can pin "the last 30 days". */
    default long now() { return System.currentTimeMillis(); }

    /** HTTPS client for network sources. */
    default Http http() { throw new UnsupportedOperationException("No network for this source"); }
}
