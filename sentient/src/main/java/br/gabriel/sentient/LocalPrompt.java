package br.gabriel.sentient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the on-device model's prompt: GMind finds the sources first (a 1.5B model can't use
 * tools reliably), then hands them over numbered by item id in Qwen's ChatML format.
 * Plain Java so host tests run it against real SQLite.
 */
public final class LocalPrompt {
    static final int MAX_KEYWORDS = 8, TOP_THREADS = 3, AROUND = 3, HISTORY_TURNS = 2;
    private static final Pattern LINE_ID = Pattern.compile("^\\[#(\\d+)\\]");
    /** Words that say nothing about what to look for, in Portuguese and English. */
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "what", "when", "where", "who", "why", "how", "did", "does", "was", "were",
            "about", "that", "this", "have", "has", "from", "you", "your", "are", "can", "tell", "say", "said", "any",
            "que", "qual", "quais", "quando", "onde", "quem", "como", "por", "para", "com", "sobre", "foi", "era",
            "tem", "ele", "ela", "eles", "elas", "uma", "umas", "uns", "dos", "das", "nos", "nas", "meu", "minha",
            "seu", "sua", "isso", "esse", "essa", "este", "esta", "mais", "muito", "ter", "ser", "fez", "disse",
            "falou", "falamos", "falei", "conversa", "conversamos", "mim", "você", "voce", "vocês"));

    private LocalPrompt() {}

    /** Words worth searching for, in order. */
    static List<String> keywords(String question) {
        List<String> words = new ArrayList<>();
        for (String w : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 3 || STOP.contains(w) || words.contains(w)) continue;
            words.add(w);
            if (words.size() == MAX_KEYWORDS) break;
        }
        return words;
    }

    /**
     * Source lines for the question, best first, within {@code budgetChars}: the top hits with a
     * little of their conversation, then the remaining hits.
     */
    static String sources(KnowledgeTools tools, String question, int budgetChars) throws Exception {
        String hits = tools.searchAny(keywords(question), 12);
        if (hits.startsWith("No matches")) return "";
        Map<Long, String> lines = new LinkedHashMap<>();
        List<Long> order = ids(hits, null);
        for (int i = 0; i < Math.min(TOP_THREADS, order.size()); i++)
            ids(tools.conversation(order.get(i), AROUND), lines);
        ids(hits, lines);
        StringBuilder out = new StringBuilder();
        for (String line : lines.values()) {
            if (out.length() + line.length() + 1 > budgetChars) break;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Ids of "[#id] …" lines, in order; also collects the lines when {@code into} is given. */
    private static List<Long> ids(String text, Map<Long, String> into) {
        List<Long> ids = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = LINE_ID.matcher(line);
            if (!m.find()) continue;
            long id = Long.parseLong(m.group(1));
            ids.add(id);
            if (into != null && !into.containsKey(id)) into.put(id, clean(line));
        }
        return ids;
    }

    /** The full ChatML prompt for Qwen2.5 Instruct. */
    static String chat(String sources, List<LlmBackend.Turn> history, String question) {
        return chat(null, sources, history, question);
    }

    /** {@code about}: a short portrait of the user (Portrait.brief), or null. */
    static String chat(String about, String sources, List<LlmBackend.Turn> history, String question) {
        StringBuilder p = new StringBuilder("<|im_start|>system\n").append(AskPrompts.LOCAL_SYSTEM);
        if (about != null && !about.isEmpty()) p.append("\n\nAbout the user:\n").append(clean(about));
        p
                .append("\n\nSources:\n").append(sources.isEmpty() ? "(nothing related was found)\n" : sources)
                .append("<|im_end|>\n");
        for (int i = Math.max(0, history.size() - HISTORY_TURNS); i < history.size(); i++) {
            p.append("<|im_start|>user\n").append(clean(history.get(i).question)).append("<|im_end|>\n")
                    .append("<|im_start|>assistant\n").append(clean(history.get(i).answer)).append("<|im_end|>\n");
        }
        return p.append("<|im_start|>user\n").append(clean(question)).append("<|im_end|>\n<|im_start|>assistant\n").toString();
    }

    /**
     * The same instructions, portrait, sources and recent turns as {@link #chat}, as plain text
     * for Gemini Nano (no chat template).
     */
    static String plain(String about, String sources, List<LlmBackend.Turn> history, String question) {
        StringBuilder p = new StringBuilder(AskPrompts.LOCAL_SYSTEM);
        if (about != null && !about.isEmpty()) p.append("\n\nAbout the user:\n").append(about);
        p.append("\n\nSources:\n").append(sources.isEmpty() ? "(nothing related was found)\n" : sources);
        if (!history.isEmpty()) {
            p.append("\nEarlier in this conversation:\n");
            for (int i = Math.max(0, history.size() - HISTORY_TURNS); i < history.size(); i++)
                p.append("User: ").append(history.get(i).question).append("\nGMind: ").append(history.get(i).answer).append('\n');
        }
        return p.append("\nUser: ").append(question).append("\nGMind:").toString();
    }

    /** Text from people or messages must not be able to open or close a ChatML turn. */
    static String clean(String text) {
        return text == null ? "" : text.replace("<|im_start|>", "").replace("<|im_end|>", "");
    }
}
