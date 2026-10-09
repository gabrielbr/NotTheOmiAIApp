package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The official SDK running inside the Android runtime (Robolectric) against a local fake of the
 * Messages API: the tool loop, what leaves the phone, and how failures read to the user.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ClaudeBackendTest {
    private MockWebServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> statusLines = new ArrayList<>();

    /** One fake search hit, whatever the query: enough to drive the loop. */
    private final Db oneHit = new Db() {
        @Override public void exec(String sql, Object... args) { throw new AssertionError("tools must not write"); }
        @Override public long insert(String sql, Object... args) { throw new AssertionError("tools must not write"); }
        @Override public int update(String sql, Object... args) { throw new AssertionError("tools must not write"); }
        @Override public List<Object[]> query(String sql, Object... args) {
            return Collections.singletonList(new Object[]{41L, 1_791_540_000_000L, "whatsapp", "Ana", "Ana", 0L,
                    "Vamos almoçar domingo?"});
        }
        @Override public <T> T transaction(Work<T> work) { throw new AssertionError(); }
    };

    @Before public void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void stop() throws Exception { server.shutdown(); }

    private void reply(int status, String body) {
        server.enqueue(new MockResponse().setResponseCode(status).addHeader("content-type", "application/json").setBody(body));
    }

    private void reply(String body) { reply(200, body); }

    private ClaudeBackend backend() {
        return new ClaudeBackend("sk-test-key", AskSettings.HAIKU, new KnowledgeTools(oneHit, ZoneId.of("UTC")),
                ZoneId.of("UTC"), server.url("/").toString().replaceAll("/$", ""));
    }

    /** Collects what reached the fake server, in order. */
    private void collect() throws Exception {
        RecordedRequest r;
        while ((r = server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS)) != null)
            requests.add(r.getPath() + " " + r.getHeader("x-api-key") + " " + r.getBody().readUtf8());
    }

    private static String message(String content, String stop) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[" + content + "],\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    private LlmBackend.Answer ask(String question) throws Exception {
        return backend().answer(Collections.singletonList(new LlmBackend.Turn("Oi?", "Olá!")), question,
                new LlmBackend.Listener() {
                    @Override public void status(String s) { statusLines.add(s); }
                    @Override public void partial(String s) {}
                }, () -> false);
    }

    @Test public void toolLoopThenAnswer() throws Exception {
        reply(message("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search\",\"input\":{\"query\":\"almoço\"}}", "tool_use"));
        reply(message("{\"type\":\"text\",\"text\":\"A Ana chamou para almoçar no domingo [#41].\"}", "end_turn"));
        LlmBackend.Answer answer = ask("O que a Ana me chamou para fazer?");
        collect();

        assertEquals("A Ana chamou para almoçar no domingo [#41].", answer.text);
        assertNull(answer.notice);
        assertEquals(2, requests.size());
        String first = requests.get(0), second = requests.get(1);
        assertTrue(first.startsWith("/v1/messages sk-test-key "));
        assertTrue(first.contains("\"model\":\"claude-haiku-5-5\""));
        assertTrue("fixed prompt and tools are cached", first.contains("\"cache_control\":{\"type\":\"ephemeral\""));
        assertTrue("five read-only tools", first.contains("\"name\":\"search\"") && first.contains("\"name\":\"timeline\"")
                && first.contains("\"name\":\"about\""));
        assertTrue("history and the date go along", first.contains("Olá!") && first.contains("\"role\":\"system\""));
        assertTrue("the tool result goes back with its id", second.contains("\"tool_use_id\":\"toolu_1\"")
                && second.contains("[#41] 2026-10-09"));
        assertTrue(statusLines.contains("Searching messages and recordings…"));
    }

    @Test public void badToolInputGoesBackAsAnError() throws Exception {
        reply(message("{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"timeline\",\"input\":{\"from\":\"ontem\"}}", "tool_use"));
        reply(message("{\"type\":\"text\",\"text\":\"Não encontrei.\"}", "end_turn"));
        ask("O que aconteceu ontem?");
        collect();
        assertTrue(requests.get(1).contains("\"is_error\":true"));
    }

    @Test public void refusalAndCutOffAreExplained() throws Exception {
        reply(message("{\"type\":\"text\",\"text\":\"\"}", "refusal"));
        assertEquals("Claude declined to answer this one.", ask("?").notice);
        reply(message("{\"type\":\"text\",\"text\":\"Começo…\"}", "max_tokens"));
        LlmBackend.Answer cut = ask("?");
        assertEquals("Começo…", cut.text);
        assertTrue(cut.notice.contains("cut off"));
    }

    @Test public void roundsAreCapped() throws Exception {
        for (int i = 0; i < ClaudeBackend.MAX_ROUNDS; i++)
            reply(message("{\"type\":\"tool_use\",\"id\":\"t" + i + "\",\"name\":\"search\",\"input\":{\"query\":\"x\"}}", "tool_use"));
        LlmBackend.Answer answer = ask("?");
        collect();
        assertEquals(ClaudeBackend.MAX_ROUNDS, requests.size());
        assertTrue(answer.notice.startsWith("Stopped after"));
    }

    @Test public void badKeyReadsClearly() throws Exception {
        reply(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        try { ask("?"); fail(); }
        catch (ClaudeBackend.AskException expected) { assertTrue(expected.getMessage().contains("API key")); }
    }

    @Test public void cancelledBeforeAsking() throws Exception {
        try {
            backend().answer(Collections.emptyList(), "?", new LlmBackend.Listener() {
                @Override public void status(String s) {}
                @Override public void partial(String s) {}
            }, () -> true);
            fail();
        } catch (ClaudeBackend.AskException expected) {
            collect();
            assertEquals(0, requests.size());
        }
    }
}
