package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The enrichment request the SDK sends, inside the Android runtime, against a fake Messages API. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ClaudeExtractorTest {
    private MockWebServer server;

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { server.shutdown(); }

    private ClaudeExtractor extractor() {
        return new ClaudeExtractor("sk-test", server.url("/").toString().replaceAll("/$", ""));
    }

    private static String message(String text, String stop) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + br.gabriel.sentient.plugin.Json.write(text) + "}],"
                + "\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    @Test public void sendsStructuredLowEffortHaikuRequest() throws Exception {
        String json = "{\"entities\":[],\"relations\":[],\"facts\":[]}";
        server.enqueue(new MockResponse().addHeader("content-type", "application/json").setBody(message(json, "end_turn")));
        assertEquals(json, extractor().extract("[#1] 2026-10-08 10:00 · message · Ana: Oi"));
        RecordedRequest r = server.takeRequest();
        String body = r.getBody().readUtf8();
        assertEquals("sk-test", r.getHeader("x-api-key"));
        assertTrue(body.contains("\"model\":\"claude-haiku-5-5\""));
        assertTrue("structured output", body.contains("\"format\":{") && body.contains("\"json_schema\"") && body.contains("\"additionalProperties\":false"));
        assertTrue("low effort", body.contains("\"effort\":\"low\""));
        assertTrue("cached instructions", body.contains("\"cache_control\":{\"type\":\"ephemeral\""));
        assertTrue("the batch", body.contains("[#1] 2026-10-08 10:00"));
    }

    @Test public void refusalSkipsTheBatch() throws Exception {
        server.enqueue(new MockResponse().addHeader("content-type", "application/json").setBody(message("", "refusal")));
        assertNull(extractor().extract("x"));
    }

    @Test public void badKeyStopsWithAMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("content-type", "application/json")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        try { extractor().extract("x"); fail(); }
        catch (ClaudeBackend.AskException expected) { assertTrue(expected.getMessage().contains("API key")); }
    }
}
