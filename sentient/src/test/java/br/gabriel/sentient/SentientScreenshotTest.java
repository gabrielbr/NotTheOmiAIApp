package br.gabriel.sentient;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * Renders the real screens with native graphics into PNGs for design review (not a correctness
 * test). Sample rows are fed straight to the views, so no Keystore or database is needed.
 * Run: ./gradlew :sentient:testDebugUnitTest --tests '*SentientScreenshotTest*'
 * Output: sentient/build/ui-screenshots/*.png
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public final class SentientScreenshotTest {
    static final File OUT = new File("build/ui-screenshots");
    static final long HOUR = 3_600_000L;

    @Test public void home() throws Exception {
        SentientActivity a = Robolectric.buildActivity(SentientActivity.class).setup().get();
        settle();
        long now = System.currentTimeMillis();
        grantWhatsApp(a, false);
        a.showAttention(Arrays.asList(state(OmiTranscripts.ID, null, null, 0, null),
                state(ChatMessages.WHATSAPP, null, null, 0, null), state(ChatMessages.SIGNAL, null, null, 0, null)));
        a.showHome(Collections.emptyList());
        settle(); shot(a, "home-empty");

        grantWhatsApp(a, true);
        List<Sources.State> fine = Arrays.asList(
                state(OmiTranscripts.ID, now - 2 * HOUR, "OK · 3 new, 1 updated", 142, now - 3 * HOUR),
                state(ChatMessages.WHATSAPP, now - 2 * HOUR, "OK · live", 318, now - 5 * 60_000L),
                state(ChatMessages.SIGNAL, now - 2 * HOUR, "OK · live", 41, now - 40 * 60_000L));
        a.showAttention(fine);
        a.showHome(recent());
        settle(); shot(a, "home");

        a.showAttention(Arrays.asList(fine.get(0), fine.get(1),
                noticed(state(ChatMessages.SIGNAL, now - 2 * HOUR, "OK · live", 0, null), MessagesListenerService.HIDDEN)));
        settle(); shot(a, "home-attention");

        List<Search.Hit> hits = new ArrayList<>();
        hits.add(hit(4, ChatMessages.WHATSAPP, "message", "Família", "Mãe", false, 1, "Jantar no domingo? Faço aquele \u0002contrato\u0003 de sobremesa que vocês gostam"));
        hits.add(hit(1, "Reunião com o João", 2, "…preciso ligar para o João sobre o \u0002contrato\u0003 amanhã. Ficou combinado de enviar a proposta dia 15…"));
        hits.add(hit(5, ChatMessages.WHATSAPP, "message", "Rui", null, true, 30, "Mandei o \u0002contrato\u0003 assinado por email"));
        a.showResults("contrat", hits);
        settle(); shot(a, "search");

        a.showResults("zzz", Collections.emptyList());
        settle(); shot(a, "search-empty");
    }

    @Test public void settings() throws Exception {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        settle();
        long now = System.currentTimeMillis();
        grantWhatsApp(a, false);
        a.showRows(Arrays.asList(
                state(OmiTranscripts.ID, now - 2 * HOUR, "OK · 3 new, 1 updated", 142, now - 3 * HOUR),
                state(ChatMessages.WHATSAPP, now - 2 * HOUR, "OK · live", 318, now - 5 * 60_000L),
                state(ChatMessages.SIGNAL, now - 2 * HOUR, "OK · live", 41, now - 40 * 60_000L)));
        settle(); shot(a, "settings");
    }

    @Test public void about() throws Exception {
        shot(Robolectric.buildActivity(AboutActivity.class).setup().get(), "about");
        shot(Robolectric.buildActivity(LicensesActivity.class).setup().get(), "licenses");
    }

    @Test public void sources() throws Exception {
        long now = System.currentTimeMillis();
        SourceActivity omi = source(OmiTranscripts.ID);
        omi.show(state(OmiTranscripts.ID, now - 2 * HOUR, "OK · 3 new, 1 updated", 142, now - 3 * HOUR));
        settle(); shot(omi, "source-gvoice");
        omi.show(state(OmiTranscripts.ID, now - 26 * HOUR, "Unavailable · Install GVoice to sync recordings", 142, now - 30 * HOUR));
        settle(); shot(omi, "source-gvoice-unavailable");

        SourceActivity whatsApp = source(ChatMessages.WHATSAPP);
        grantWhatsApp(whatsApp, false);
        whatsApp.show(state(ChatMessages.WHATSAPP, null, null, 0, null));
        settle(); shot(whatsApp, "source-whatsapp-access");
        grantWhatsApp(whatsApp, true);
        whatsApp.show(state(ChatMessages.WHATSAPP, now - 2 * HOUR, "OK · live", 318, now - 5 * 60_000L));
        settle(); shot(whatsApp, "source-whatsapp");

        SourceActivity signal = source(ChatMessages.SIGNAL);
        grantWhatsApp(signal, true);
        signal.show(noticed(state(ChatMessages.SIGNAL, now - 2 * HOUR, "OK · live", 0, null), MessagesListenerService.HIDDEN));
        settle(); shot(signal, "source-signal-hidden");
    }

    static SourceActivity source(String pluginId) throws Exception {
        SourceActivity a = Robolectric.buildActivity(SourceActivity.class,
                new Intent().putExtra(SourceActivity.EXTRA_PLUGIN_ID, pluginId)).setup().get();
        settle();
        return a;
    }

    @Test public void item() throws Exception {
        ItemActivity a = Robolectric.buildActivity(ItemActivity.class,
                new Intent().putExtra(ItemActivity.EXTRA_ID, 1L).putExtra(ItemActivity.EXTRA_QUERY, "contrato")).setup().get();
        settle();
        Constructor<Items.Item> c = Items.Item.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        String text = "Bom dia pessoal. Preciso ligar para o João sobre o contrato amanhã. Ficou combinado de enviar "
                + "a proposta dia 15. Também não esquecer de pagar o boleto da luz até sexta. Then we moved to the "
                + "roadmap: I need to email Sarah the slides tomorrow, and we should follow up with the vendor about "
                + "the delivery dates. O contrato novo fica para a semana que vem.";
        Items.Item item = c.newInstance((Object) new Object[]{1L, OmiTranscripts.ID, "transcript",
                System.currentTimeMillis() - 2 * HOUR, text, "Reunião com o João", null, null, 0L});
        a.show(item, text.replace("contrato", "\u0002contrato\u0003"));
        settle(); shot(a, "item");

        ItemActivity t = Robolectric.buildActivity(ItemActivity.class,
                new Intent().putExtra(ItemActivity.EXTRA_ID, 4L).putExtra(ItemActivity.EXTRA_QUERY, "sobremesa")).setup().get();
        settle();
        Items.Item hit = message(4, "Mãe", false, 60, "Jantar no domingo? Faço aquele pudim de sobremesa que vocês gostam");
        t.showThread(hit, "Jantar no domingo? Faço aquele pudim de \u0002sobremesa\u0003 que vocês gostam", Arrays.asList(
                message(2, "Pai", false, 70, "Alguém sabe se a padaria abre domingo?"),
                message(3, null, true, 65, "Abre sim, até o meio-dia"),
                hit,
                message(5, "Pai", false, 58, "Pudim! Levo o vinho"),
                message(6, null, true, 55, "Fechado, chego às 19h")));
        settle(); shot(t, "thread");
    }

    static List<Items.Item> recent() throws Exception {
        Constructor<Items.Item> c = Items.Item.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        long now = System.currentTimeMillis();
        return Arrays.asList(
                message(4, "Mãe", false, 5, "Jantar no domingo? Faço aquele pudim de sobremesa que vocês gostam"),
                message(3, null, true, 40, "Abre sim, até o meio-dia"),
                c.newInstance((Object) new Object[]{1L, OmiTranscripts.ID, "transcript", now - 3 * HOUR,
                        "Bom dia pessoal. Preciso ligar para o João sobre o contrato amanhã. Ficou combinado de enviar a proposta dia 15.",
                        "Reunião com o João", null, null, 0L}),
                c.newInstance((Object) new Object[]{8L, ChatMessages.SIGNAL, "message", now - 5 * HOUR,
                        "Te mando o endereço amanhã cedo", "Rui", 9L, "Rui", 0L}));
    }

    /** Same state plus a standing notice (row column 7). */
    static Sources.State noticed(Sources.State s, String notice) throws Exception {
        Constructor<Sources.State> c = Sources.State.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        return c.newInstance((Object) new Object[]{s.pluginId, 1L, null, s.lastSyncAt, s.lastStatus, s.itemCount, s.lastItemAt, notice});
    }

    static void grantWhatsApp(Activity a, boolean granted) {
        org.robolectric.Shadows.shadowOf(a.getSystemService(android.app.NotificationManager.class))
                .setNotificationListenerAccessGranted(new android.content.ComponentName(a, MessagesListenerService.class), granted);
    }


    static Search.Hit hit(long id, String conversation, long hoursAgo, String snippet) throws Exception {
        return hit(id, OmiTranscripts.ID, "transcript", conversation, null, false, hoursAgo, snippet);
    }

    static Search.Hit hit(long id, String source, String kind, String conversation, String author, boolean me,
                          long hoursAgo, String snippet) throws Exception {
        Constructor<Search.Hit> c = Search.Hit.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        return c.newInstance((Object) new Object[]{id, source, kind,
                System.currentTimeMillis() - hoursAgo * HOUR, conversation, snippet, author, me ? 1L : 0L});
    }

    static Items.Item message(long id, String author, boolean me, long minutesAgo, String text) throws Exception {
        Constructor<Items.Item> c = Items.Item.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        return c.newInstance((Object) new Object[]{id, ChatMessages.WHATSAPP, "message",
                System.currentTimeMillis() - minutesAgo * 60_000L, text, "Família", 7L, author, me ? 1L : 0L});
    }

    static Sources.State state(String id, Long lastSync, String status, long count, Long lastItem) throws Exception {
        Constructor<Sources.State> c = Sources.State.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        return c.newInstance((Object) new Object[]{id, 1L, null, lastSync, status, count, lastItem});
    }

    static void settle() throws InterruptedException {
        for (int i = 0; i < 5; i++) { Thread.sleep(120); ShadowLooper.idleMainLooper(); }
    }

    static void shot(Activity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        int w = 1080, h = 2340;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
