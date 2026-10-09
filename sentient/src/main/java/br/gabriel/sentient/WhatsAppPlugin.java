package br.gabriel.sentient;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import br.gabriel.sentient.plugin.Mode;
import br.gabriel.sentient.plugin.PluginContext;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.SourcePlugin;
import br.gabriel.sentient.plugin.SourceUnavailableException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** WhatsApp, captured live by WhatsAppListenerService. The daily sync only checks access. */
final class WhatsAppPlugin implements SourcePlugin {
    static final String NEEDS_ACCESS = "Allow notification access to save WhatsApp messages";
    private final Context context;

    WhatsAppPlugin(Context context) { this.context = context.getApplicationContext(); }

    @Override public String id() { return WhatsAppMessages.ID; }
    @Override public String displayName() { return "WhatsApp"; }
    @Override public Set<Mode> modes() { return EnumSet.of(Mode.PUSH); }

    @Override public PullResult pull(PluginContext ctx, String cursor) throws Exception {
        if (!accessGranted(context)) throw new SourceUnavailableException(NEEDS_ACCESS);
        return new PullResult(Collections.emptyList(), cursor, false);
    }

    static boolean accessGranted(Context context) {
        ComponentName listener = new ComponentName(context, WhatsAppListenerService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(listener);
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(listener.flattenToString());
    }

    static Intent accessSettings() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
