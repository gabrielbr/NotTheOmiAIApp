package br.gabriel.sentient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Gemini Nano answers through a fake model: streaming, prompt size, retries and the Qwen fallback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class NanoBackendTest {
    private final List<String> prompts = new ArrayList<>();
    private final List<String> partials = new ArrayList<>();
    private final List<Integer> failures = new ArrayList<>();
    private int downloads, statusChecks;
    private int status = Nano.AVAILABLE;

    private final Db oneHit = new Db() {
        @Override public void exec(String sql, Object... args) { throw new AssertionError("must not write"); }
        @Override public long insert(String sql, Object... args) { throw new AssertionError("must not write"); }
        @Override public int update(String sql, Object... args) { throw new AssertionError("must not write"); }
        @Override public List<Object[]> query(String sql, Object... args) {
            // The keyword search finds one message; nothing else (its conversation) is looked up here.
            if (!sql.contains("items_fts")) return Collections.emptyList();
            return Collections.singletonList(new Object[]{41L, 1_791_540_000_000L, "whatsapp", "Ana", "Ana", 0L,
                    "Vamos almoçar domingo?"});
        }
        @Override public <T> T transaction(Work<T> work) { throw new AssertionError(); }
    };

    /** Fails with the queued codes first, then streams "Domingo, " + "com a Ana [#41]". */
    private final Nano.Model fake = new Nano.Model() {
        @Override public int status() { statusChecks++; return status; }
        @Override public void download() { downloads++; }
        @Override public String generate(String prompt, int maxTokens, Consumer<String> piece, BooleanSupplier cancelled)
                throws Exception {
            prompts.add(prompt);
            if (!failures.isEmpty()) throw new Nano.Failure(failures.remove(0), null);
            if (cancelled.getAsBoolean()) throw new Nano.Failure(Nano.CANCELLED, null);
            piece.accept("Domingo, ");
            piece.accept("com a Ana [#41]");
            return "Domingo, com a Ana [#41]";
        }
    };

    private final LlmBackend qwen = new LlmBackend() {
        @Override public String name() { return "Qwen"; }
        @Override public Answer answer(List<Turn> history, String question, Listener listener, BooleanSupplier cancelled) {
            return new Answer("Qwen says domingo [#41]", null);
        }
    };

    private final LlmBackend.Listener listener = new LlmBackend.Listener() {
        @Override public void status(String s) { }
        @Override public void partial(String s) { partials.add(s); }
    };

    @After public void reset() { Nano.use(null); }

    private NanoBackend backend(LlmBackend fallback) {
        return new NanoBackend(fake, new KnowledgeTools(oneHit, ZoneId.of("UTC")), "Gabriel, em Brasília", fallback);
    }

    @Test public void streamsTheAnswerFromSourcesFoundFirst() throws Exception {
        LlmBackend.Answer a = backend(qwen).answer(List.of(new LlmBackend.Turn("Oi", "Olá")), "Quando almoçamos com a Ana?",
                listener, () -> false);
        assertEquals("Domingo, com a Ana [#41]", a.text);
        assertNull(a.notice);
        assertEquals(List.of("Domingo, ", "Domingo, com a Ana [#41]"), partials);
        String prompt = prompts.get(0);
        assertTrue(prompt, prompt.contains("[#41]") && prompt.contains("Vamos almoçar domingo?"));
        assertTrue(prompt, prompt.contains("Gabriel, em Brasília") && prompt.contains("User: Oi\nGMind: Olá"));
        assertTrue(prompt, prompt.endsWith("User: Quando almoçamos com a Ana?\nGMind:"));
        assertTrue(prompt, !prompt.contains("<|im_start|>"));
        // Instructions + 6,000 characters of notes + two turns stay well under Nano's ~4,000 tokens.
        assertTrue(String.valueOf(prompt.length()), prompt.length() < AskPrompts.LOCAL_SYSTEM.length() + NanoBackend.SOURCE_BUDGET + 2000);
    }

    @Test public void tooLargeRetriesWithFewerNotesAndNoHistory() throws Exception {
        failures.add(Nano.REQUEST_TOO_LARGE);
        LlmBackend.Answer a = backend(qwen).answer(List.of(new LlmBackend.Turn("Oi", "Olá")), "Ana?", listener, () -> false);
        assertEquals("Domingo, com a Ana [#41]", a.text);
        assertTrue(a.notice, a.notice.contains("fewer messages"));
        assertEquals(2, prompts.size());
        assertTrue(!prompts.get(1).contains("User: Oi"));
    }

    @Test public void busyQuotaOrBackgroundFallBackToQwenAndSaySo() throws Exception {
        for (int code : new int[]{Nano.BUSY, Nano.QUOTA, Nano.BACKGROUND_BLOCKED, Nano.NOT_AVAILABLE, 999}) {
            failures.add(code);
            LlmBackend.Answer a = backend(qwen).answer(List.of(), "Ana?", listener, () -> false);
            assertEquals("Qwen says domingo [#41]", a.text);
            assertTrue(a.notice, a.notice.startsWith(NanoBackend.reason(code)) && a.notice.contains("Qwen"));
        }
    }

    @Test public void withoutQwenTheFailureIsExplained() throws Exception {
        failures.add(Nano.BUSY);
        try {
            backend(null).answer(List.of(), "Ana?", listener, () -> false);
            fail();
        } catch (ClaudeBackend.AskException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("busy") && e.getMessage().contains("Download Qwen"));
        }
    }

    @Test public void stopIsNotAFallback() throws Exception {
        try {
            backend(qwen).answer(List.of(), "Ana?", listener, () -> true);
            fail();
        } catch (ClaudeBackend.AskException e) {
            assertEquals("Stopped.", e.getMessage());
        }
    }

    @Test public void statusIsCheckedOnceAMinuteAndStartsAndroidsDownload() {
        Nano.use(fake);
        assertEquals(Nano.UNKNOWN, Nano.cached());
        status = Nano.DOWNLOADABLE;
        assertEquals(Nano.DOWNLOADING, Nano.status());
        assertEquals(1, downloads);
        status = Nano.AVAILABLE;
        assertEquals(Nano.DOWNLOADING, Nano.status()); // cached for a minute
        assertEquals(1, statusChecks);
        Nano.use(fake);
        assertEquals(Nano.AVAILABLE, Nano.status());
        assertTrue(Nano.describe(Nano.AVAILABLE).contains("Gemini Nano"));
        assertTrue(Nano.describe(Nano.UNAVAILABLE).contains("Qwen"));
    }

    @Test public void aPhoneWithoutAicoreReadsAsUnavailable() {
        Nano.use(new Nano.Model() {
            @Override public int status() { throw new IllegalStateException("no AICore"); }
            @Override public void download() { }
            @Override public String generate(String p, int m, Consumer<String> c, BooleanSupplier s) { throw new AssertionError(); }
        });
        assertEquals(Nano.UNAVAILABLE, Nano.status());
    }
}
