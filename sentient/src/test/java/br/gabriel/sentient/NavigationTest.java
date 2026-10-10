package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Intent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

/** Where each entry point leads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class NavigationTest {
    @Test public void headerGearOpensSettings() {
        SentientActivity home = Robolectric.buildActivity(SentientActivity.class).setup().get();
        ArrayList<View> found = new ArrayList<>();
        home.getWindow().getDecorView().findViewsWithText(found, "Settings", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertEquals(1, found.size());
        found.get(0).performClick();
        Intent next = Shadows.shadowOf(home).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(SettingsActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test public void settingsListsAbout() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ArrayList<View> found = new ArrayList<>();
        settings.getWindow().getDecorView().findViewsWithText(found, "About", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, found.size());
    }
}
