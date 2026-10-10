package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The overnight review request the SDK sends, inside the Android runtime, against a fake Messages API. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ClaudeReviewerTest {
    private MockWebServer server;

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { server.shutdown(); }

    private ClaudeReviewer reviewer() {
        return new ClaudeReviewer("sk-test", server.url("/").toString().replaceAll("/$", ""));
    }

    private static String message(String text, String stop) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + br.gabriel.sentient.plugin.Json.write(text) + "}],"
                + "\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    @Test public void asksForTheIdsNotWorthRemembering() throws Exception {
        server.enqueue(new MockResponse().addHeader("content-type", "application/json").setBody(message("{\"noise\":[2]}", "end_turn")));
        String reply = reviewer().review("[#1] 2026-10-08 10:00 · WhatsApp · Ana · message: Jantar?\n[#2] … · Gmail · Loja · email: 50% off\n");
        assertEquals(List.of(2L), Review.ids(reply, Set.of(1L, 2L)));
        RecordedRequest r = server.takeRequest();
        String body = r.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""));
        assertTrue("structured {noise:[...]}", body.contains("\"json_schema\"") && body.contains("\"noise\"") && body.contains("\"additionalProperties\":false"));
        assertTrue("low effort, cached instructions", body.contains("\"effort\":\"low\"") && body.contains("\"cache_control\":{\"type\":\"ephemeral\""));
        assertTrue("the review instructions", body.contains("NOT worth remembering"));
        assertTrue("the items", body.contains("[#2]"));
    }

    @Test public void refusalSkipsTheBatch() throws Exception {
        server.enqueue(new MockResponse().addHeader("content-type", "application/json").setBody(message("", "refusal")));
        assertNull(reviewer().review("x"));
    }

    @Test public void badKeyStopsWithAMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("content-type", "application/json")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        try { reviewer().review("x"); fail(); }
        catch (ClaudeBackend.AskException expected) { assertTrue(expected.getMessage().contains("API key")); }
    }
}
