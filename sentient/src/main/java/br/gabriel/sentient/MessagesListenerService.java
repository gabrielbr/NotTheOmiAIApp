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
 * Saves chat messages (WhatsApp, Signal) as their notifications arrive. Reads only those apps'
 * notifications, never dismisses or answers them, and never logs message text.
 */
public final class MessagesListenerService extends NotificationListenerService {
    static final String HIDDEN = "Signal is hiding message content. In Signal, open Settings › Notifications › Show, and choose Name and message";
    private static final ExecutorService STORE = Executors.newSingleThreadExecutor();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        ChatMessages.App app = ChatMessages.App.forPackage(sbn.getPackageName());
        if (app == null) return;
        ChatMessages.Snapshot snapshot = snapshot(sbn);
        List<RawItem> items = ChatMessages.parse(snapshot);
        boolean hidden = items.isEmpty() && ChatMessages.contentHidden(snapshot);
        if (items.isEmpty() && !hidden) return;
        STORE.execute(() -> {
            try {
                Db db = KnowledgeStore.get(this);
                Sources.ensure(db, app.id);
                long now = System.currentTimeMillis();
                if (hidden) { Sources.setNotice(db, app.id, HIDDEN); return; }
                Ingest.Stats stats = db.transaction(() -> {
                    Sources.setNotice(db, app.id, null);
                    return Ingest.upsert(db, items, now);
                });
                if (stats.added > 0 || stats.updated > 0) Sources.finish(db, app.id, now, "OK · live");
            } catch (Exception failure) {
                // Never surface message content; the next notification retries the whole history.
            }
        });
    }

    /** Everything GMind needs from one notification, without keeping a reference to it. */
    static ChatMessages.Snapshot snapshot(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (title == null) title = extras.getCharSequence(Notification.EXTRA_TITLE);
        boolean group = Build.VERSION.SDK_INT >= 28
                ? extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                : extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null;
        List<ChatMessages.Message> messages = new ArrayList<>();
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
                messages.add(new ChatMessages.Message(str(sender), str(text), b.getLong("time")));
            }
        } else {
            // Older style: one line of text; in a chat it comes from the chat's title.
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            messages.add(new ChatMessages.Message(str(title), str(text), sbn.getPostTime()));
        }
        return new ChatMessages.Snapshot(sbn.getPackageName(), sbn.getPostTime(), str(title),
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
