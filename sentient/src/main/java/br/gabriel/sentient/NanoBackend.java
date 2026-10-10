package br.gabriel.sentient;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Answers with Gemini Nano on phones whose Android offers it. Like Qwen it can't use tools, so
 * GMind finds the sources first ({@link LocalPrompt}). Its input must stay under about 4,000
 * tokens. When Nano can't answer (busy, over Android's quota, the app isn't on screen, or the
 * model went away), the same question goes to {@code fallback} (Qwen) and the answer says so.
 */
final class NanoBackend implements LlmBackend {
    /** About 6,000 characters of notes plus instructions and two turns stays under 4,000 tokens. */
    static final int SOURCE_BUDGET = 6000, MAX_ANSWER = 512;
    private final Nano.Model model;
    private final KnowledgeTools tools;
    private final String about;
    private final LlmBackend fallback;

    NanoBackend(Nano.Model model, KnowledgeTools tools, String about, LlmBackend fallback) {
        this.model = model; this.tools = tools; this.about = about; this.fallback = fallback;
    }

    @Override public String name() { return "Gemini Nano on this phone"; }

    @Override public Answer answer(List<Turn> history, String question, Listener listener, BooleanSupplier cancelled)
            throws Exception {
        listener.status("Finding related messages…");
        String sources = LocalPrompt.sources(tools, question, SOURCE_BUDGET);
        if (cancelled.getAsBoolean()) throw new ClaudeBackend.AskException("Stopped.");
        listener.status("Writing the answer with Gemini Nano…");
        try {
            return new Answer(generate(LocalPrompt.plain(about, sources, history, question), listener, cancelled), null);
        } catch (Nano.Failure tooLarge) {
            if (tooLarge.code == Nano.CANCELLED || cancelled.getAsBoolean()) throw new ClaudeBackend.AskException("Stopped.");
            if (tooLarge.code == Nano.REQUEST_TOO_LARGE) {
                // Long history or sources: once more with half the sources and no history.
                String fewer = sources.substring(0, Math.min(sources.length(), SOURCE_BUDGET / 2));
                try {
                    return new Answer(generate(LocalPrompt.plain(about, fewer, List.of(), question), listener, cancelled),
                            "Answered from fewer messages so it fit Gemini Nano.");
                } catch (Nano.Failure stillFailing) {
                    return fallBack(stillFailing.code, history, question, listener, cancelled);
                }
            }
            return fallBack(tooLarge.code, history, question, listener, cancelled);
        }
    }

    private String generate(String prompt, Listener listener, BooleanSupplier cancelled) throws Exception {
        StringBuilder text = new StringBuilder();
        String full = model.generate(prompt, MAX_ANSWER, piece -> {
            text.append(piece);
            listener.partial(text.toString());
        }, cancelled);
        return full == null || full.isEmpty() ? text.toString().trim() : full.trim();
    }

    private Answer fallBack(int code, List<Turn> history, String question, Listener listener, BooleanSupplier cancelled)
            throws Exception {
        if (fallback == null) throw new ClaudeBackend.AskException(reason(code) + " Download Qwen in Ask settings to answer anyway.");
        listener.partial("");
        Answer answer = fallback.answer(history, question, listener, cancelled);
        String why = reason(code) + " Answered with Qwen instead.";
        return new Answer(answer.text, answer.notice == null ? why : why + " " + answer.notice);
    }

    static String reason(int code) {
        switch (code) {
            case Nano.BUSY: return "Gemini Nano was busy.";
            case Nano.QUOTA: return "Android's daily limit for Gemini Nano was reached.";
            case Nano.BACKGROUND_BLOCKED: return "Android only lets Gemini Nano answer while GMind is on screen.";
            case Nano.NOT_AVAILABLE: case Nano.NOT_SUPPORTED: return "Gemini Nano isn't available right now.";
            default: return "Gemini Nano couldn't answer (error " + code + ").";
        }
    }
}
