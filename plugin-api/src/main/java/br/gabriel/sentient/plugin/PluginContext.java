package br.gabriel.sentient.plugin;

/** What the host gives a plugin during one pull. */
public interface PluginContext {
    /** Plugin configuration value (server URL, toolkit name…), or null. */
    String config(String key);

    /** True once the sync was cancelled; long pulls should check it between pages. */
    boolean cancelled();
}
