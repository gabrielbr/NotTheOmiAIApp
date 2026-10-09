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
        a.showSources(Collections.singletonList(state(OmiTranscripts.ID, null, null, 0)));
        settle(); shot(a, "home-empty");

        a.showSources(Collections.singletonList(
                state(OmiTranscripts.ID, System.currentTimeMillis() - 2 * HOUR, "OK · 3 new, 1 updated", 142)));
        settle(); shot(a, "home");

        a.showSources(Collections.singletonList(state(OmiTranscripts.ID, System.currentTimeMillis() - 26 * HOUR,
                "Unavailable · Install Omi Tarefas to sync recordings", 142)));
        settle(); shot(a, "home-attention");

        List<Search.Hit> hits = new ArrayList<>();
        hits.add(hit(1, "Reunião com o João", 2, "…preciso ligar para o João sobre o \u0002contrato\u0003 amanhã. Ficou combinado de enviar a proposta dia 15…"));
        hits.add(hit(2, "Ideias no carro", 20, "…revisar o \u0002contrato\u0003 do aluguel e ver hotéis perto do centro…"));
        hits.add(hit(3, null, 50, "…the vendor \u0002contract\u0003 needs a follow-up about the delivery dates…"));
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
                System.currentTimeMillis() - 2 * HOUR, text, "Reunião com o João"});
        a.show(item, text.replace("contrato", "\u0002contrato\u0003"));
        settle(); shot(a, "item");
    }

    static Sources.State state(String id, Long lastSync, String status, long count) throws Exception {
        Constructor<Sources.State> c = Sources.State.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        return c.newInstance((Object) new Object[]{id, 1L, null, lastSync, status, count});
    }

    static Search.Hit hit(long id, String conversation, long hoursAgo, String snippet) throws Exception {
        Constructor<Search.Hit> c = Search.Hit.class.getDeclaredConstructor(Object[].class);
        c.setAccessible(true);
        return c.newInstance((Object) new Object[]{id, OmiTranscripts.ID, "transcript",
                System.currentTimeMillis() - hoursAgo * HOUR, conversation, snippet});
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
