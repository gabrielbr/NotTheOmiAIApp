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

/** A chat app (WhatsApp, Signal), captured live by MessagesListenerService. The daily sync only
 * checks that notification access is still on. One access grant covers every chat app. */
final class ChatPlugin implements SourcePlugin {
    private final Context context;
    private final ChatMessages.App app;

    ChatPlugin(Context context, ChatMessages.App app) { this.context = context.getApplicationContext(); this.app = app; }

    static String needsAccess(ChatMessages.App app) {
        return "Allow notification access to save " + app.displayName + " messages";
    }

    @Override public String id() { return app.id; }
    @Override public String displayName() { return app.displayName; }
    @Override public Set<Mode> modes() { return EnumSet.of(Mode.PUSH); }

    @Override public PullResult pull(PluginContext ctx, String cursor) throws Exception {
        if (!accessGranted(context)) throw new SourceUnavailableException(needsAccess(app));
        return new PullResult(Collections.emptyList(), cursor, false);
    }

    static boolean accessGranted(Context context) {
        ComponentName listener = new ComponentName(context, MessagesListenerService.class);
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
