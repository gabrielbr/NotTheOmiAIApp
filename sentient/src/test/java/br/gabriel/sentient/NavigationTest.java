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

    @Test public void enterInSearchAsksWhatWasTyped() throws Exception {
        SentientActivity home = home();
        android.widget.EditText search = (android.widget.EditText) only(home, "Search everything synced",
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        search.setText("O que combinei com a Ana?");
        search.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        Intent next = Shadows.shadowOf(home).getNextStartedActivity();
        assertEquals(AskActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("O que combinei com a Ana?", next.getStringExtra(AskActivity.EXTRA_QUESTION));
        // The Ask button carries it too.
        only(home, "Ask a question", View.FIND_VIEWS_WITH_TEXT).performClick();
        assertEquals("O que combinei com a Ana?",
                Shadows.shadowOf(home).getNextStartedActivity().getStringExtra(AskActivity.EXTRA_QUESTION));
    }

    @Test public void askOpensWithTheCarriedQuestion() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), AskActivity.class).putExtra(AskActivity.EXTRA_QUESTION, " Ana? ");
        AskActivity ask = Robolectric.buildActivity(AskActivity.class, intent).setup().get();
        // Not set up in tests (no key or model), so the question waits in the field, ready to send.
        boolean found = false;
        for (View v : allViews(ask.getWindow().getDecorView()))
            if (v instanceof android.widget.EditText && "Ana?".equals(((android.widget.EditText) v).getText().toString())) found = true;
        assertEquals(true, found);
    }

    private static java.util.List<View> allViews(View root) {
        java.util.List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) root).getChildCount(); i++)
                out.addAll(allViews(((android.view.ViewGroup) root).getChildAt(i)));
        return out;
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

    @Test public void aboutLeadsToLicenses() throws Exception {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ((View) only(settings, "About", View.FIND_VIEWS_WITH_TEXT).getParent()).performClick();
        assertEquals(AboutActivity.class.getName(), Shadows.shadowOf(settings).getNextStartedActivity().getComponent().getClassName());

        AboutActivity about = Robolectric.buildActivity(AboutActivity.class).setup().get();
        ((View) only(about, "Open-source licenses", View.FIND_VIEWS_WITH_TEXT).getParent()).performClick();
        assertEquals(LicensesActivity.class.getName(), Shadows.shadowOf(about).getNextStartedActivity().getComponent().getClassName());

        LicensesActivity licenses = Robolectric.buildActivity(LicensesActivity.class).setup().get();
        for (String name : new String[]{"sqlcipher-android-BSD.txt", "androidx-sqlite-Apache-2.0.txt", "ubuntu-font-licence.txt"})
            only(licenses, name, View.FIND_VIEWS_WITH_TEXT);
    }

    @Test public void licensesReflowParagraphs() {
        assertEquals("Apache License Version 2.0\n\n1. Definitions. \"License\" means",
                LicensesActivity.reflow("   Apache License\r\n   Version 2.0\n\n  \n1. Definitions.\n   \"License\" means\n"));
    }

    @Test public void settingsListsAbout() {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ArrayList<View> found = new ArrayList<>();
        settings.getWindow().getDecorView().findViewsWithText(found, "About", View.FIND_VIEWS_WITH_TEXT);
        // "About you" also matches; exactly one row is About itself.
        found.removeIf(v -> !"About".contentEquals(((android.widget.TextView) v).getText()));
        assertEquals(1, found.size());
        for (String label : new String[]{"Connect sources", "Ask", "About you", "People", "To-dos", "Chats for to-dos", "Updates"}) {
            ArrayList<View> row = new ArrayList<>();
            settings.getWindow().getDecorView().findViewsWithText(row, label, View.FIND_VIEWS_WITH_TEXT);
            row.removeIf(v -> !label.contentEquals(((android.widget.TextView) v).getText()));
            assertEquals(label, 1, row.size());
        }
    }
}
