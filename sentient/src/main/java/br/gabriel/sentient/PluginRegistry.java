package br.gabriel.sentient;

import android.content.Context;

import br.gabriel.sentient.plugin.SourcePlugin;

import java.util.ArrayList;
import java.util.List;

/** Every available source. Adding one is one class plus one line here. */
final class PluginRegistry {
    private PluginRegistry() {}

    static List<SourcePlugin> plugins(Context context) {
        List<SourcePlugin> plugins = new ArrayList<>();
        plugins.add(new OmiTranscriptsPlugin(context));
        for (ChatMessages.App app : ChatMessages.App.values()) plugins.add(new ChatPlugin(context, app));
        return plugins;
    }

    static String displayName(Context context, String pluginId) {
        for (SourcePlugin plugin : plugins(context)) if (plugin.id().equals(pluginId)) return plugin.displayName();
        return pluginId;
    }
}
