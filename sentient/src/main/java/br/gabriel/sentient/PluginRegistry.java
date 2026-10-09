package br.gabriel.sentient;

import android.content.Context;

import br.gabriel.sentient.plugin.SourcePlugin;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Every available source. Adding one is one class plus one line here. Cheap to call: no
 * Keystore, database or network work happens until a plugin pulls. */
final class PluginRegistry {
    private PluginRegistry() {}

    static List<SourcePlugin> plugins(Context context) {
        List<SourcePlugin> plugins = new ArrayList<>();
        plugins.add(new OmiTranscriptsPlugin(context));
        for (ChatMessages.App app : ChatMessages.App.values()) plugins.add(new ChatPlugin(context, app));
        for (ComposioToolkit toolkit : ComposioToolkit.ALL)
            if (Connections.CONNECTED.equals(Connections.state(context, toolkit)))
                plugins.add(new ComposioPlugin(toolkit, Connections.composioUser(context),
                        Connections.account(context, toolkit), ComposioClient.BASE, ZoneId.systemDefault()));
        if (SecretStore.MATRIX.has(context)) plugins.add(new MatrixPlugin());
        return plugins;
    }

    static String displayName(Context context, String pluginId) {
        ComposioToolkit toolkit = ComposioToolkit.forSource(pluginId);
        if (toolkit != null) return toolkit.displayName();
        if (MatrixPlugin.ID.equals(pluginId)) return "Matrix";
        for (SourcePlugin plugin : plugins(context)) if (plugin.id().equals(pluginId)) return plugin.displayName();
        return pluginId;
    }

    /** Secrets plugins may ask for, by name. The Claude key is never among them. */
    static String secret(Context context, String name) {
        SecretStore store = SecretStore.named(name);
        return store == null ? null : store.read(context);
    }
}
