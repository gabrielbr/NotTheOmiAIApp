package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;

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

    @Test public void sourceRowOpensItsScreen() throws Exception {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        SentientScreenshotTest.settle();
        settings.showRows(Arrays.asList(SentientScreenshotTest.state(ChatMessages.WHATSAPP, null, null, 0, null)));
        ((View) only(settings, "WhatsApp", View.FIND_VIEWS_WITH_TEXT).getParent()).performClick();
        Intent next = Shadows.shadowOf(settings).getNextStartedActivity();
        assertEquals(SourceActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(ChatMessages.WHATSAPP, next.getStringExtra(SourceActivity.EXTRA_PLUGIN_ID));
    }

    @Test public void attentionLineOpensTheSourceOrTheList() throws Exception {
        SentientActivity home = home();
        SentientScreenshotTest.grantWhatsApp(home, true);
        Sources.State fine = SentientScreenshotTest.state(OmiTranscripts.ID, 1L, "OK", 3, 1L);
        Sources.State broken = SentientScreenshotTest.state(OmiTranscripts.ID, 1L, "Unavailable · Install GVoice", 3, 1L);
        Sources.State signal = SentientScreenshotTest.noticed(
                SentientScreenshotTest.state(ChatMessages.SIGNAL, 1L, "OK · live", 0, null), MessagesListenerService.HIDDEN);

        home.showAttention(Arrays.asList(fine));
        assertEquals(0, find(home, "needs attention", View.FIND_VIEWS_WITH_TEXT, false).size());

        home.showAttention(Arrays.asList(broken));
        only(home, "GVoice recordings needs attention ›", View.FIND_VIEWS_WITH_TEXT).performClick();
        Intent next = Shadows.shadowOf(home).getNextStartedActivity();
        assertEquals(SourceActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(OmiTranscripts.ID, next.getStringExtra(SourceActivity.EXTRA_PLUGIN_ID));

        home.showAttention(Arrays.asList(broken, signal));
        only(home, "2 sources need attention ›", View.FIND_VIEWS_WITH_TEXT).performClick();
        assertEquals(SettingsActivity.class.getName(), Shadows.shadowOf(home).getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void emptyHomeOpensSetup() throws Exception {
        SentientActivity home = home();
        home.showHome(java.util.Collections.emptyList());
        only(home, "Set up sources", View.FIND_VIEWS_WITH_TEXT).performClick();
        assertEquals(SettingsActivity.class.getName(), Shadows.shadowOf(home).getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void recentItemOpensWithoutHighlight() throws Exception {
        SentientActivity home = home();
        home.showHome(SentientScreenshotTest.recent());
        only(home, "Open Reunião com o João", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION).performClick();
        Intent next = Shadows.shadowOf(home).getNextStartedActivity();
        assertEquals(ItemActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(1L, next.getLongExtra(ItemActivity.EXTRA_ID, -1));
        assertNull(next.getStringExtra(ItemActivity.EXTRA_QUERY));
    }

    private static SentientActivity home() throws Exception {
        SentientActivity home = Robolectric.buildActivity(SentientActivity.class).setup().get();
        SentientScreenshotTest.settle(); // let the store error from the real (absent) Keystore land first
        return home;
    }

    @Test public void accessButtonOpensNotificationAccess() throws Exception {
        SourceActivity source = SentientScreenshotTest.source(ChatMessages.WHATSAPP);
        SentientScreenshotTest.grantWhatsApp(source, false);
        source.show(SentientScreenshotTest.state(ChatMessages.WHATSAPP, null, null, 0, null));
        only(source, "Allow notification access", View.FIND_VIEWS_WITH_TEXT).performClick();
        Intent next = Shadows.shadowOf(source).getNextStartedActivity();
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, next.getAction());
    }

    @Test public void chatSourcesHaveNoSyncButton() throws Exception {
        SourceActivity source = SentientScreenshotTest.source(ChatMessages.WHATSAPP);
        SentientScreenshotTest.grantWhatsApp(source, true);
        source.show(SentientScreenshotTest.state(ChatMessages.WHATSAPP, 1L, "OK · live", 3, 1L));
        assertEquals(0, find(source, "Sync now", View.FIND_VIEWS_WITH_TEXT).size());
        SourceActivity omi = SentientScreenshotTest.source(OmiTranscripts.ID);
        omi.show(SentientScreenshotTest.state(OmiTranscripts.ID, 1L, "OK", 3, 1L));
        assertEquals(1, find(omi, "Sync now", View.FIND_VIEWS_WITH_TEXT).size());
    }

    private static ArrayList<View> find(Activity a, String text, int flags) { return find(a, text, flags, true); }

    /** Views whose text (or description) contains this, or is exactly this. */
    private static ArrayList<View> find(Activity a, String text, int flags, boolean exact) {
        ArrayList<View> found = new ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found, text, flags);
        found.removeIf(v -> v.getVisibility() != View.VISIBLE || exact && !text.contentEquals(
                flags == View.FIND_VIEWS_WITH_TEXT ? ((android.widget.TextView) v).getText() : v.getContentDescription()));
        return found;
    }

    private static View only(Activity a, String text, int flags) {
        ArrayList<View> found = find(a, text, flags);
        assertEquals(text, 1, found.size());
        return found.get(0);
    }

    @Test public void settingsListsAbout() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ArrayList<View> found = new ArrayList<>();
        settings.getWindow().getDecorView().findViewsWithText(found, "About", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, found.size());
    }
}
