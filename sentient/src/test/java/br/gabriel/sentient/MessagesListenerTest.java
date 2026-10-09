package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import br.gabriel.sentient.plugin.RawItem;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real MessagingStyle notifications, as WhatsApp posts them, through the listener's extraction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class MessagesListenerTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final Person me = new Person.Builder().setName("Gabriel").build();

    @SuppressWarnings("deprecation")
    private StatusBarNotification posted(String pkg, Notification n) {
        return new StatusBarNotification(pkg, pkg, 1, null, 0, 0, 0, n, Process.myUserHandle(), 99_000L);
    }

    private Notification.Builder builder() {
        return new Notification.Builder(context, "chats").setSmallIcon(android.R.drawable.sym_def_app_icon);
    }

    @Test public void directMessageWithMyReply() {
        Person ana = new Person.Builder().setName("Ana").build();
        Notification n = builder().setShortcutId("5511999@s.whatsapp.net").setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(new Notification.MessagingStyle(me)
                        .addMessage("Vamos almoçar amanhã?", 1000, ana)
                        .addMessage("Bora, 12h?", 2000, (Person) null))
                .build();
        ChatMessages.Snapshot s = MessagesListenerService.snapshot(posted("com.whatsapp", n));
        assertEquals("5511999@s.whatsapp.net", s.shortcutId);
        assertEquals("Gabriel", s.selfName);
        assertFalse(s.group);
        assertEquals(2, s.messages.size());
        assertEquals("Ana", s.messages.get(0).sender);
        assertNull(s.messages.get(1).sender);
        List<RawItem> items = ChatMessages.parse(s);
        assertEquals(2, items.size());
        assertTrue(items.get(1).fromMe);
        assertEquals(1000, items.get(0).timestamp);
    }

    @Test public void groupConversation() {
        Person mae = new Person.Builder().setName("Mãe").build();
        Notification n = builder().setStyle(new Notification.MessagingStyle(me)
                        .setConversationTitle("Família (2 mensagens)").setGroupConversation(true)
                        .addMessage("Jantar domingo?", 1000, mae)
                        .addMessage("📷 Foto", 1100, mae))
                .build();
        List<RawItem> items = ChatMessages.parse(MessagesListenerService.snapshot(posted("com.whatsapp", n)));
        assertEquals(2, items.size());
        assertEquals("Família", items.get(0).conversationTitle);
        assertEquals("group", items.get(0).conversationKind);
        assertEquals("name:Mãe", items.get(0).authorHandle);
    }

    @Test public void summaryAndOtherAppsIgnored() {
        Notification summary = builder().setGroup("chats").setGroupSummary(true)
                .setContentTitle("WhatsApp").setContentText("5 messages from 3 chats").build();
        assertTrue(ChatMessages.parse(MessagesListenerService.snapshot(posted("com.whatsapp", summary))).isEmpty());
        assertEquals(ChatMessages.App.WHATSAPP_APP, ChatMessages.App.forPackage("com.whatsapp.w4b"));
        assertNull(ChatMessages.App.forPackage("com.facebook.orca"));
    }

    @Test public void signalConversation() {
        Person rui = new Person.Builder().setName("Rui").build();
        Notification n = builder().setShortcutId("recipient-7")
                .setStyle(new Notification.MessagingStyle(me).addMessage("Chego às 9", 1000, rui))
                .build();
        List<RawItem> items = ChatMessages.parse(MessagesListenerService.snapshot(posted("org.thoughtcrime.securesms", n)));
        assertEquals(1, items.size());
        assertEquals(ChatMessages.SIGNAL, items.get(0).source);
        assertEquals("name:Rui", items.get(0).authorHandle);
    }

    @Test public void signalHidingContent() {
        Notification n = builder().setContentTitle("Signal").setContentText("New message").build();
        ChatMessages.Snapshot s = MessagesListenerService.snapshot(posted("org.thoughtcrime.securesms", n));
        assertTrue(ChatMessages.parse(s).isEmpty());
        assertTrue(ChatMessages.contentHidden(s));
    }

    @Test public void plainTextFallback() {
        Notification n = builder().setContentTitle("Rui").setContentText("Chego às 9").build();
        List<RawItem> items = ChatMessages.parse(MessagesListenerService.snapshot(posted("com.whatsapp", n)));
        assertEquals(1, items.size());
        assertEquals("name:Rui", items.get(0).authorHandle);
        assertEquals(99_000L, items.get(0).timestamp);
    }
}
