package app.nottheomi.ai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * Renders real screens with native graphics into PNGs for design review (not a correctness test).
 * Run: ./gradlew testDebugUnitTest --tests '*UiScreenshotTest*' -Pandroid.useAndroidX=true
 * Output: app/build/ui-screenshots/*.png
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public final class UiScreenshotTest {
    static final File OUT = new File("build/ui-screenshots");

    @After public void reset() { CaptureService.active = false; }

    @Test public void home() throws Exception {
        // "Ideias no carro" (191 s) is being refined: a third saved, half of the current window done.
        long[] now = {1_000_000L};
        RefinementProgress.clock = () -> now[0];
        RefinementProgress.reset();
        int[] window = {50};
        long total = 191 * 32_000L, saved = total / 3 / 2 * 2;
        RefinementProgress.begin("s2", 0, total, () -> window[0]);
        RefinementProgress.window(0, saved); now[0] += 90_000; RefinementProgress.saved(saved);
        RefinementProgress.window(saved, 30 * 32_000L); RefinementProgress.get(); now[0] += 20_000;
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        settle();
        set(a, "ready", true);
        LinearLayout rows = (LinearLayout) get(a, "historyRows");
        TextView hint = (TextView) get(a, "historyHint");
        hint.setVisibility(View.GONE);
        Method row = MainActivity.class.getDeclaredMethod("sessionRow", Recordings.Session.class, int.class);
        row.setAccessible(true);
        for (Recordings.Session s : samples()) {
            rows.addView(Ui.divider(a));
            rows.addView((View) row.invoke(a, s, 2));
        }
        call(a, "refreshCapture");
        settle(); shot(a, "home");

        CaptureService.active = true;
        CaptureService.state = "Recording";
        CaptureService.startedAt = SystemClock.elapsedRealtime() - 754_000;
        CaptureService.level = 0.7f;
        OmiSettingsActivity.preferences(a).edit().putString("source", "phone").apply();
        call(a, "draw"); set(a, "ready", true); call(a, "refreshCapture");
        settle(); shot(a, "home-recording");

        CaptureService.active = false;
        set(a, "library", true); call(a, "draw");
        LinearLayout lib = (LinearLayout) get(a, "libraryRows");
        lib.removeAllViews();
        ((TextView) get(a, "storageLabel")).setText("41.3 MB of 2 GB used");
        for (Recordings.Session s : samples()) { lib.addView(Ui.divider(a)); lib.addView((View) row.invoke(a, s, 2)); }
        settle(); shot(a, "library");

        set(a, "library", false); call(a, "draw");
        set(a, "selectedId", "s1");
        Method detail = MainActivity.class.getDeclaredMethod("showDetail", Recordings.Session.class);
        detail.setAccessible(true);
        detail.invoke(a, samples()[0]);
        settle(); shot(a, "detail");

        set(a, "selectedId", "s2");
        detail.invoke(a, samples()[1]);
        call(a, "refreshProgress");
        settle(); shot(a, "detail-refining");

        now[0] += 7 * 60_000L; // nothing moves for 7 minutes
        call(a, "refreshProgress");
        settle(); shot(a, "detail-stalled");

        // A quick transcript waiting for the charger, then being improved.
        RefinementProgress.idle("Quick transcripts done · the accurate ones are made while the phone charges");
        set(a, "selectedId", "s4");
        detail.invoke(a, samples()[3]);
        settle(); shot(a, "detail-quick");
        long total4 = 407 * 32_000L;
        RefinementProgress.begin("s4", 0, total4, () -> window[0], true);
        RefinementProgress.window(0, 30 * 32_000L); now[0] += 40_000; RefinementProgress.saved(30 * 32_000L);
        RefinementProgress.window(30 * 32_000L, 30 * 32_000L); RefinementProgress.get(); now[0] += 10_000;
        call(a, "refreshProgress");
        settle(); shot(a, "detail-improving");
    }

    @Test public void omi() throws Exception {
        ActivityController<OmiSettingsActivity> c = Robolectric.buildActivity(OmiSettingsActivity.class).setup();
        settle(); shot(c.get(), "omi");
    }

    static Recordings.Session[] samples() throws Exception {
        return new Recordings.Session[]{
            session("s1", "Reunião com o João", "saved", "complete", 754, 2,
                "Bom dia pessoal. Preciso ligar para o João sobre o contrato amanhã. Ficou combinado de enviar a proposta dia 15. Também não esquecer de pagar o boleto da luz até sexta. Then we moved to the roadmap: I need to email Sarah the slides tomorrow, and we should follow up with the vendor about the delivery dates."),
            session("s2", "Ideias no carro", "saved", "pending", 191, 20,
                "Tenho que revisar o orçamento da viagem e ver hotéis perto do centro."),
            session("s3", "Recording 9 Oct", "interrupted", "failed", 48, 30, ""),
            session("s4", "Almoço com a Ana", "saved", "quick", 407, 26,
                "Ela disse que chega às oito e que traz o bolo. Combinamos de ver o apartamento no sábado."),
        };
    }

    static Recordings.Session session(String id, String title, String status, String state, long seconds,
                                      long hoursAgo, String text) throws Exception {
        Class<?> metaClass = Class.forName("app.nottheomi.ai.Recordings$Metadata");
        Constructor<?> mc = metaClass.getDeclaredConstructor();
        mc.setAccessible(true);
        Object m = mc.newInstance();
        setField(m, "id", id); setField(m, "title", title); setField(m, "status", status);
        setField(m, "createdAt", System.currentTimeMillis() - hoursAgo * 3_600_000L);
        setField(m, "bytes", seconds * 32_000L);
        Constructor<Recordings.Session> sc = Recordings.Session.class.getDeclaredConstructor(
                metaClass, String.class, String.class, String.class, boolean.class, long.class, String.class, long.class);
        sc.setAccessible(true);
        // A pending recording is shown a third of the way through; "quick" is a small-model transcript.
        boolean quick = "quick".equals(state);
        return sc.newInstance(m, text, text, quick ? "complete" : state, false,
                "pending".equals(state) ? seconds * 32_000L / 3 / 2 * 2 : quick ? seconds * 32_000L : 0L,
                quick ? Recordings.SMALL : "complete".equals(state) ? Recordings.MEDIUM : null, quick ? 0L : -1L);
    }

    static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
    static void set(Object o, String name, Object value) throws Exception { setField(o, name, value); }
    static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static void call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(o);
    }

    static void settle() throws InterruptedException {
        for (int i = 0; i < 5; i++) { Thread.sleep(120); ShadowLooper.idleMainLooper(); }
    }

    static View find(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = find(((ViewGroup) v).getChildAt(i), text); if (f != null) return f; }
        return null;
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
