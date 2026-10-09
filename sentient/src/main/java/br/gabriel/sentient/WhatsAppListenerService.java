package br.gabriel.sentient;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import br.gabriel.sentient.plugin.RawItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves WhatsApp messages as their notifications arrive. Reads only WhatsApp's notifications,
 * never dismisses or answers them, and never logs message text.
 */
public final class WhatsAppListenerService extends NotificationListenerService {
    static final String[] PACKAGES = {"com.whatsapp", "com.whatsapp.w4b"};
    private static final ExecutorService STORE = Executors.newSingleThreadExecutor();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isWhatsApp(sbn.getPackageName())) return;
        WhatsAppMessages.Snapshot snapshot = snapshot(sbn);
        List<RawItem> items = WhatsAppMessages.parse(snapshot);
        if (items.isEmpty()) return;
        STORE.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                Sources.ensure(db, WhatsAppMessages.ID);
                Ingest.Stats stats = db.transaction(() -> Ingest.upsert(db, items, System.currentTimeMillis()));
                if (stats.added > 0 || stats.updated > 0)
                    Sources.finish(db, WhatsAppMessages.ID, System.currentTimeMillis(), "OK · live");
            } catch (Exception failure) {
                // Never surface message content; the next notification retries the whole history.
            }
        });
    }

    static boolean isWhatsApp(String packageName) {
        for (String p : PACKAGES) if (p.equals(packageName)) return true;
        return false;
    }

    /** Everything GMind needs from one notification, without keeping a reference to it. */
    static WhatsAppMessages.Snapshot snapshot(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (title == null) title = extras.getCharSequence(Notification.EXTRA_TITLE);
        boolean group = Build.VERSION.SDK_INT >= 28
                ? extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                : extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null;
        List<WhatsAppMessages.Message> messages = new ArrayList<>();
        Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (bundles != null) {
            for (Parcelable p : bundles) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                CharSequence text = b.getCharSequence("text");
                CharSequence sender = b.getCharSequence("sender");
                if (sender == null && Build.VERSION.SDK_INT >= 28) {
                    Person person = b.getParcelable("sender_person");
                    if (person != null) sender = person.getName();
                }
                messages.add(new WhatsAppMessages.Message(str(sender), str(text), b.getLong("time")));
            }
        } else {
            // Older style: one line of text; in a chat it comes from the chat's title.
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            messages.add(new WhatsAppMessages.Message(str(title), str(text), sbn.getPostTime()));
        }
        return new WhatsAppMessages.Snapshot(sbn.getPackageName(), sbn.getPostTime(), str(title),
                n.getShortcutId(), group, (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                str(selfName(extras)), n.category, messages);
    }

    @SuppressWarnings("deprecation")
    private static CharSequence selfName(Bundle extras) {
        if (Build.VERSION.SDK_INT >= 28) {
            Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
            if (user != null && user.getName() != null) return user.getName();
        }
        return extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME);
    }

    private static String str(CharSequence value) { return value == null ? null : value.toString(); }
}
