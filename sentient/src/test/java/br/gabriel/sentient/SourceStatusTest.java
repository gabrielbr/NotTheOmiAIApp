package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The words a source's state turns into, in lists and on its screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SourceStatusTest {
    @Test public void chatWithoutAccessNeedsAccess() throws Exception {
        Sources.State s = SentientScreenshotTest.state(ChatMessages.WHATSAPP, null, null, 0, null);
        assertTrue(SourceStatus.problem(s, false));
        assertEquals("Needs access", SourceStatus.summary(s, false));
        assertEquals("Allow notification access to save WhatsApp messages.", SourceStatus.reason(s, false));
        assertFalse(SourceStatus.problem(s, true));
        assertEquals("No messages yet", SourceStatus.summary(s, true));
        assertNull(SourceStatus.reason(s, true));
    }

    @Test public void standingNoticeNeedsAttention() throws Exception {
        Sources.State s = SentientScreenshotTest.noticed(
                SentientScreenshotTest.state(ChatMessages.SIGNAL, 1L, "OK · live", 2, 1L), MessagesListenerService.HIDDEN);
        assertEquals("Needs attention", SourceStatus.summary(s, true));
        assertEquals(MessagesListenerService.HIDDEN + ".", SourceStatus.reason(s, true));
    }

    @Test public void syncedSourceStatus() throws Exception {
        Sources.State never = SentientScreenshotTest.state(OmiTranscripts.ID, null, null, 0, null);
        assertEquals("Not synced yet", SourceStatus.summary(never, false));
        Sources.State ok = SentientScreenshotTest.state(OmiTranscripts.ID, 1L, "OK · 3 new", 1, 1L);
        assertEquals("1 recording", SourceStatus.summary(ok, false));
        Sources.State unavailable = SentientScreenshotTest.state(OmiTranscripts.ID, 1L, "Unavailable · Install GVoice", 1, 1L);
        assertEquals("Needs attention", SourceStatus.summary(unavailable, false));
        assertEquals("Install GVoice.", SourceStatus.reason(unavailable, false));
        Sources.State failed = SentientScreenshotTest.state(OmiTranscripts.ID, 1L, "Failed · IOException", 1, 1L);
        assertEquals("The last sync failed. Sync now to try again.", SourceStatus.reason(failed, false));
    }
}
