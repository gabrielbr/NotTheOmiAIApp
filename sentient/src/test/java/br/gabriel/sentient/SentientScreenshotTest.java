package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;

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

    @Test public void memory() throws Exception {
        Constructor<Items.Item> c = Items.Item.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        long now = System.currentTimeMillis();
        Items.Item promo = c.newInstance((Object) new Object[]{9L, "composio.gmail", "email", now - 3 * HOUR,
                "50% off\n\nSó hoje, sapatos com metade do preço.", "50% off", 12L, "Loja", 0L,
                Relevance.RULE, "Gmail: Promotions", "email:ofertas@loja.com"});
        Items.Item personal = c.newInstance((Object) new Object[]{10L, "composio.gmail", "email", now - 2 * HOUR,
                "Jantar sábado?\n\nVamos?", "Jantar sábado?", 13L, "Ana", 0L, Relevance.KEEP, null, "email:ana@example.com"});

        ItemActivity hidden = Robolectric.buildActivity(ItemActivity.class, new Intent().putExtra(ItemActivity.EXTRA_ID, 9L)).setup().get();
        settle();
        hidden.show(promo, null);
        settle(); shot(hidden, "item-hidden");
        assertEquals(1, views(hidden, "Keep in memory").size());
        assertEquals(0, views(hidden, "Hide from memory").size());

        ItemActivity kept = Robolectric.buildActivity(ItemActivity.class, new Intent().putExtra(ItemActivity.EXTRA_ID, 10L)).setup().get();
        settle();
        kept.show(personal, null);
        settle();
        assertEquals(1, views(kept, "Hide from memory").size());
        assertEquals(1, views(kept, "Hide everything from Ana").size());

        HiddenActivity list = Robolectric.buildActivity(HiddenActivity.class).setup().get();
        settle();
        Items.Item social = c.newInstance((Object) new Object[]{11L, "composio.gmail", "email", now - 5 * HOUR,
                "Ana curtiu sua foto", "Ana curtiu sua foto", 14L, "Rede social", 0L, Relevance.RULE, "Gmail: Social", "email:notify@social.com"});
        Items.Item digest = c.newInstance((Object) new Object[]{12L, "composio.gmail", "email", now - 6 * HOUR,
                "Weekly usage report", "Weekly usage report", 15L, "SaaS", 0L, Relevance.CLAUDE, "Claude: not worth remembering", "email:team@saas.com"});
        list.show(Arrays.asList(digest, promo, social), 3);
        settle(); shot(list, "hidden");
        assertEquals(3, views(list, "Keep").size());
        assertEquals(1, views(list, "Gmail: Promotions").size());
    }

    private static java.util.ArrayList<android.view.View> views(android.app.Activity a, String text) {
        java.util.ArrayList<android.view.View> found = new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found, text, android.view.View.FIND_VIEWS_WITH_TEXT);
        found.removeIf(v -> !(v instanceof android.widget.TextView) || !text.contentEquals(((android.widget.TextView) v).getText()));
        return found;
    }

    @Test public void connect() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        ConnectActivity a = Robolectric.buildActivity(ConnectActivity.class).setup().get();
        settle(); shot(a, "connect");

        // A saved key (the Keystore isn't available here, so only its file), Gmail connected, Calendar pending.
        java.io.File key = new java.io.File(app.getNoBackupFilesDir(), "composio.key");
        key.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(key)) { out.write(new byte[40]); }
        Connections.pending(app, new ComposioGmail(), "ca_1");
        Connections.connected(app, new ComposioGmail());
        Connections.pending(app, new ComposioCalendar(), "ca_2");
        a.draw();
        settle(); shot(a, "connect-composio");
        key.delete();
        Connections.remove(app, new ComposioGmail());
        Connections.remove(app, new ComposioCalendar());

        SentientActivity home = Robolectric.buildActivity(SentientActivity.class).setup().get();
        settle();
        home.askName = true;
        home.showHome(recent());
        settle(); shot(home, "home-name");

        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        settle();
        grantWhatsApp(settings, true);
        long now = System.currentTimeMillis();
        settings.showRows(Arrays.asList(
                state(OmiTranscripts.ID, now - 2 * HOUR, "OK · 3 new, 1 updated", 142, now - 3 * HOUR),
                state(ChatMessages.WHATSAPP, now - 2 * HOUR, "OK · live", 318, now - 5 * 60_000L),
                state(ChatMessages.TELEGRAM, now - 2 * HOUR, "OK · live", 12, now - 50 * 60_000L),
                state("composio.gmail", now - 2 * HOUR, "OK · 25 new, 0 updated", 214, now - 4 * HOUR),
                noticed(state(MatrixPlugin.ID, now - 2 * HOUR, "OK · 9 new, 0 updated", 87, now - 6 * HOUR),
                        "2 rooms are end-to-end encrypted. GMind can't read those yet; unencrypted rooms are saved.")));
        settle(); shot(settings, "settings-connected");
    }

    @Test public void you() throws Exception {
        YouActivity a = Robolectric.buildActivity(YouActivity.class).setup().get();
        settle();
        YouActivity.State s = new YouActivity.State();
        s.suggestions = 2;
        s.openTasks = 5;
        s.portrait = "# About me\n\n_Built by GMind on 2026-10-09 from what it collected. Each line cites its evidence._\n\n"
                + "## Who I am\n\n- Known as Gabriel · me@example.com (Gmail) [#9], @gabriel:matrix.org (Matrix) [#21]\n\n"
                + "## People I talk to most (last 30 days)\n\n1. Ana · 120 items in WhatsApp, Gmail, last 8 Oct [#41]\n"
                + "2. Mãe · 64 items in WhatsApp, last 9 Oct [#77]\n3. Rui · 31 items in Signal, last 7 Oct [#90]\n\n"
                + "## Coming up (next 7 days)\n\n- Sat 10 Oct, 10:00 · Dentista [#12]\n\n"
                + "## Open to-dos\n\n- Pagar luz (Todoist) [#13]\n- Ligar pro banco (said in WhatsApp, 8 Oct) [#2]\n";
        s.days.addAll(Arrays.asList("2026-10-09", "2026-10-08"));
        a.show(s);
        settle(); shot(a, "you");

        PeopleActivity p = Robolectric.buildActivity(PeopleActivity.class).setup().get();
        settle();
        Constructor<People.Person> pc = People.Person.class.getDeclaredConstructor(Object[].class);
        pc.setAccessible(true);
        long now = System.currentTimeMillis();
        People.Person me = pc.newInstance((Object) new Object[]{1L, "Gabriel", 1L, "whatsapp,composio.gmail", 210L, now - HOUR});
        People.Person ana = pc.newInstance((Object) new Object[]{2L, "Ana", 0L, "whatsapp", 120L, now - 2 * HOUR});
        People.Person anaMx = pc.newInstance((Object) new Object[]{3L, "Ana", 0L, "matrix", 8L, now - 30 * HOUR});
        People.Person mae = pc.newInstance((Object) new Object[]{4L, "Mãe", 0L, "whatsapp", 64L, now - 3 * HOUR});
        Constructor<People.Suggestion> sc = People.Suggestion.class.getDeclaredConstructor(People.Person.class, People.Person.class, String.class);
        sc.setAccessible(true);
        p.show(Arrays.asList(sc.newInstance(ana, anaMx, "Same name in WhatsApp and Matrix")), Arrays.asList(me, ana, mae, anaMx));
        settle(); shot(p, "people");

        TasksActivity t = Robolectric.buildActivity(TasksActivity.class).setup().get();
        settle();
        Constructor<FoundTasks.Task> tc = FoundTasks.Task.class.getDeclaredConstructor(Object[].class);
        tc.setAccessible(true);
        t.show(Arrays.asList(
                tc.newInstance((Object) new Object[]{1L, 41L, "Comprar pão amanhã", "open", now - 3 * HOUR, "whatsapp", "Ana", "Ana", 0L, "2026-10-10", null}),
                tc.newInstance((Object) new Object[]{5L, 45L, "Preencher a planilha de horas até sexta", "open", now - 2 * HOUR, "whatsapp", "Empresa", "Chefe", 0L, "2026-10-16", null}),
                tc.newInstance((Object) new Object[]{2L, 42L, "Mandar o relatório na segunda", "open", now - 5 * HOUR, "omi.transcripts", "Reunião com o João", null, 0L}),
                tc.newInstance((Object) new Object[]{3L, 43L, "Ligar pro banco", "open", now - 26 * HOUR, "whatsapp", "Ana", null, 1L})),
                Arrays.asList(tc.newInstance((Object) new Object[]{4L, 44L, "Renovar o passaporte", "shared", now - 50 * HOUR, "omi.transcripts", null, null, 0L})));
        settle(); shot(t, "tasks");

        WatchedChatsActivity w = Robolectric.buildActivity(WatchedChatsActivity.class).setup().get();
        settle();
        w.show(Arrays.asList(
                new Object[]{1L, "Ana", "whatsapp", "dm", 1L, now - HOUR},
                new Object[]{2L, "Empresa", "whatsapp", "group", 1L, now - 2 * HOUR},
                new Object[]{3L, "Família", "whatsapp", "group", 0L, now - 3 * HOUR},
                new Object[]{4L, "#geral", "composio.slack", "group", 0L, now - 30 * HOUR}), Arrays.asList("Gabriel", "Gabi"));
        settle(); shot(w, "watched-chats");
    }

    @Test public void updates() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        Object json = br.gabriel.sentient.plugin.Json.parse(UpdatesTestData.RELEASE);
        java.util.Map<String, String> sums = new java.util.HashMap<>();
        sums.put("GMind-0.9.0.apk", "e42c7e1745e262028e7623da7158677d7068a2443cbfffdadb9b56782a9032c4");
        sums.put("GVoice-0.9.0-arm64-v8a.apk", "45caa672aa9b99ced288f62aff9b31ba848b94a590806cabfcf8ce0b2dcd6964");
        UpdateInstaller.remember(app, Updates.parse(json, sums), System.currentTimeMillis() - HOUR);
        android.content.pm.PackageInfo gvoice = new android.content.pm.PackageInfo();
        gvoice.packageName = Updates.GVOICE;
        gvoice.versionName = "0.5.24";
        org.robolectric.Shadows.shadowOf(app.getPackageManager()).installPackage(gvoice);
        UpdatesActivity a = Robolectric.buildActivity(UpdatesActivity.class).setup().get();
        settle(); a.draw(); settle(); shot(a, "updates");
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

    @Test public void askOnPhone() throws Exception {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        AskSettings.setBackend(app, AskSettings.LOCAL);
        AskSettingsActivity settings = Robolectric.buildActivity(AskSettingsActivity.class).setup().get();
        settle();
        settings.showLocal(new LocalModel.Status(LocalModel.State.NONE, 0, LocalModel.BYTES, null));
        settle(); shot(settings, "ask-local-download");
        settings.showLocal(new LocalModel.Status(LocalModel.State.DOWNLOADING, 412_000_000L, LocalModel.BYTES, null));
        settle(); shot(settings, "ask-local-progress");
        settings.showLocal(new LocalModel.Status(LocalModel.State.READY, LocalModel.BYTES, LocalModel.BYTES, null));
        settle(); shot(settings, "ask-local-ready");
        AskSettings.setBackend(app, AskSettings.CLAUDE);
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
