package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import br.gabriel.sentient.plugin.Http;
import java.io.IOException;
import java.util.Collections;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The real HttpURLConnection client inside the Android runtime, against a local fake server. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class UrlHttpTest {
    private MockWebServer server;

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { server.shutdown(); }

    @Test public void postsJsonAndReadsReplies() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"no\"}"));
        UrlHttp http = new UrlHttp(true);
        Http.Response r = http.post(server.url("/tools/execute/X").toString(),
                Collections.singletonMap("x-api-key", "k"), "{\"a\":\"ç\"}");
        assertTrue(r.ok());
        assertEquals("{\"ok\":true}", r.body);
        RecordedRequest sent = server.takeRequest();
        assertEquals("POST", sent.getMethod());
        assertEquals("k", sent.getHeader("x-api-key"));
        assertTrue(sent.getHeader("Content-Type").startsWith("application/json"));
        assertEquals("{\"a\":\"ç\"}", sent.getBody().readUtf8());
        Http.Response denied = http.get(server.url("/x").toString(), Collections.emptyMap());
        assertFalse(denied.ok());
        assertEquals(401, denied.status);
        assertEquals("{\"error\":\"no\"}", denied.body);
    }

    @Test public void refusesPlainHttp() throws Exception {
        try {
            new UrlHttp().get(server.url("/x").toString(), Collections.emptyMap());
            fail();
        } catch (IOException expected) {
            assertEquals("Only HTTPS is allowed", expected.getMessage());
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void refusesHugeBodies() throws Exception {
        server.enqueue(new MockResponse().setBody(new okio.Buffer().write(new byte[UrlHttp.MAX_BODY + 1])));
        try {
            new UrlHttp(true).get(server.url("/big").toString(), Collections.emptyMap());
            fail();
        } catch (IOException expected) {
            assertEquals("Response too large", expected.getMessage());
        }
    }
}
