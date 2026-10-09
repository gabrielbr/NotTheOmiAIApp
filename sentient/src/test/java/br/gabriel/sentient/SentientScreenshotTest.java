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
        a.showSources(Arrays.asList(state(OmiTranscripts.ID, null, null, 0, null),
                state(ChatMessages.WHATSAPP, null, null, 0, null), state(ChatMessages.SIGNAL, null, null, 0, null)));
        settle(); shot(a, "home-empty");

        grantWhatsApp(a, true);
        a.showSources(Arrays.asList(
                state(OmiTranscripts.ID, now - 2 * HOUR, "OK · 3 new, 1 updated", 142, now - 3 * HOUR),
                state(ChatMessages.WHATSAPP, now - 2 * HOUR, "OK · live", 318, now - 5 * 60_000L),
                state(ChatMessages.SIGNAL, now - 2 * HOUR, "OK · live", 41, now - 40 * 60_000L)));
        settle(); shot(a, "home");

        a.showSources(Arrays.asList(
                state(OmiTranscripts.ID, now - 2 * HOUR, "OK · 3 new, 1 updated", 142, now - 3 * HOUR),
                state(ChatMessages.WHATSAPP, now - 2 * HOUR, "OK · live", 318, now - 5 * 60_000L),
                noticed(state(ChatMessages.SIGNAL, now - 2 * HOUR, "OK · live", 0, null), MessagesListenerService.HIDDEN)));
        settle(); shot(a, "home-signal-hidden");

        grantWhatsApp(a, false);
        a.showSources(Arrays.asList(
                state(OmiTranscripts.ID, now - 26 * HOUR, "Unavailable · Install GVoice to sync recordings", 142, now - 30 * HOUR),
                state(ChatMessages.WHATSAPP, now - 26 * HOUR, "OK · live", 318, now - 26 * HOUR)));
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

    @Test public void ask() throws Exception {
        AskActivity a = Robolectric.buildActivity(AskActivity.class).setup().get();
        settle(); shot(a, "ask-setup");

        AskSettingsActivity settings = Robolectric.buildActivity(AskSettingsActivity.class).setup().get();
        settle(); shot(settings, "ask-settings");

        // An answered question, as the worker would leave it (no key or network in tests).
        android.widget.LinearLayout thread = (android.widget.LinearLayout) field(a, "thread");
        ((android.widget.LinearLayout) field(a, "setup")).removeAllViews();
        android.widget.TextView q = Ui.text(a, "O que a Ana e eu combinamos para domingo?", 17, Ui.INK, true);
        q.setPadding(0, Ui.dp(a, 24), 0, Ui.dp(a, 8));
        thread.addView(q);
        android.widget.TextView answer = Ui.text(a, "", 16, Ui.INK, false);
        thread.addView(answer);
        long now = System.currentTimeMillis();
        Items.Item m1 = message(41, "Ana", false, 26 * 60, "Vamos almoçar domingo no Lisboa?");
        Items.Item m2 = message(42, null, true, 25 * 60, "Bora, meio-dia");
        Constructor<Items.Item> c = Items.Item.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        Items.Item rec = c.newInstance((Object) new Object[]{77L, OmiTranscripts.ID, "transcript", now - 20 * HOUR,
                "…", "Reunião com o João", null, null, 0L});
        a.showAnswer(answer, new LlmBackend.Answer("Vocês combinaram almoçar no domingo ao meio-dia, no Lisboa [#41][#42]. "
                + "Você também comentou isso na reunião com o João [#77].", null),
                Arrays.asList(41L, 42L, 77L), Arrays.asList(m1, m2, rec), "domingo");
        ((android.widget.TextView) field(a, "footer")).setText("Answered by Claude Haiku 5.5. Your question and the messages it looks up are sent to Anthropic.");
        settle(); shot(a, "ask-answer");
    }

    static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
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
