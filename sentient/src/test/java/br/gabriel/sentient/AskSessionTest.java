package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The Ask chat outlives the screen and survives being saved and read back. */
@RunWith(RobolectricTestRunner.class)
public final class AskSessionTest {
    @Before public void fresh() { AskSession.resetForTest(); }

    static AskSession.Exchange answered(String q, String a, Long... ids) {
        AskSession.Exchange e = new AskSession.Exchange(q);
        e.answer = a;
        e.ids = new ArrayList<>(Arrays.asList(ids));
        return e;
    }

    @Test public void savedChatReadsBack() {
        AskSession.Exchange failed = new AskSession.Exchange("E o João?");
        failed.error = "Couldn't reach Claude.";
        AskSession.Exchange running = new AskSession.Exchange("Ainda escrevendo");
        running.running = true;
        String json = AskSession.encode(Arrays.asList(answered("O que combinamos?", "Almoço domingo [#41].", 41L), failed, running));
        List<AskSession.Exchange> back = AskSession.decode(json);
        assertEquals("a running exchange isn't saved", 2, back.size());
        assertEquals("O que combinamos?", back.get(0).question);
        assertEquals("Almoço domingo [#41].", back.get(0).answer);
        assertEquals(Arrays.asList(41L), back.get(0).ids);
        assertNull(back.get(1).answer);
        assertEquals("Couldn't reach Claude.", back.get(1).error);
        assertTrue("corrupt JSON gives an empty chat", AskSession.decode("{not json").isEmpty());
        assertTrue(AskSession.decode(null).isEmpty());
    }

    @Test public void savesOnlyTheLatest() {
        List<AskSession.Exchange> many = new ArrayList<>();
        for (int i = 0; i < AskSession.MAX_SAVED + 5; i++) many.add(answered("q" + i, "a" + i));
        List<AskSession.Exchange> back = AskSession.decode(AskSession.encode(many));
        assertEquals(AskSession.MAX_SAVED, back.size());
        assertEquals("q5", back.get(0).question);
    }

    @Test public void chatSurvivesLeavingTheScreen() {
        ActivityController<AskActivity> first = Robolectric.buildActivity(AskActivity.class).setup();
        AskSession session = AskSession.get(first.get());
        session.restore(Arrays.asList(answered("O que a Ana disse?", "Que chega às oito.")));
        assertTrue(shows(first.get(), "O que a Ana disse?"));
        first.pause().stop().destroy();

        AskActivity again = Robolectric.buildActivity(AskActivity.class).setup().get();
        assertTrue("question still there", shows(again, "O que a Ana disse?"));
        assertTrue("answer still there", shows(again, "Que chega às oito."));

        session.clear();
        assertTrue("New chat empties it", !shows(again, "O que a Ana disse?"));
    }

    static boolean shows(AskActivity a, String text) {
        ArrayList<View> found = new ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : found) if (v instanceof TextView && v.isShown()) return true;
        return false;
    }
}
