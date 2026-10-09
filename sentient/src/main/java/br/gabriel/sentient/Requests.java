package br.gabriel.sentient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requests other people make of you: "Gabriel, preenche a planilha de horas até sexta" in a watched
 * group, or "pode mandar o contrato amanhã?" in a direct chat. In a group a message must name you;
 * in a direct chat it's addressed to you unless it opens with someone else's name. It must ask for
 * something or set a deadline. Plain Java (host-tested).
 */
public final class Requests {
    static final int MAX_CHARS = 200;
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?;\\n])\\s+");
    private static final Pattern ASKS = Pattern.compile("\\b(?:precisa|precisamos|preciso|por favor|favor|pode|podes|poderia"
            + "|consegue|conseguiria|tem que|tens que|lembra de|nao esquece|please|pls|can you|could you|would you|need to|needs to"
            + "|have to|has to|must|make sure|remember to|don't forget|do not forget)\\b");
    /** Openings like "Ana," or "@Ana" that address someone. */
    private static final Pattern OPENING = Pattern.compile("^\\s*@?([\\p{L}][\\p{L}'-]{1,30})\\s*[,:]");

    private Requests() {}

    /**
     * To-dos for you in one message from someone else. {@code group}: true when the chat is a group
     * (your name must appear). {@code names}: your name and nicknames.
     */
    public static List<String> find(String text, List<String> names, boolean group) {
        List<String> found = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return found;
        Set<String> mine = folded(names);
        if (group && mine.isEmpty()) return found; // can't tell who a group message is for
        Set<String> seen = new LinkedHashSet<>();
        for (String sentence : SENTENCE.split(text.trim())) {
            String folded = TaskExtractor.fold(sentence);
            boolean named = mentions(folded, mine);
            Matcher opening = OPENING.matcher(folded);
            boolean someoneElse = opening.find() && !mine.contains(opening.group(1));
            if (group ? !named : (someoneElse && !named)) continue;
            boolean asks = ASKS.matcher(folded).find() || !TaskExtractor.extract(sentence).isEmpty();
            boolean deadline = DueDates.parse(sentence, 0, java.time.ZoneOffset.UTC) != null;
            if (!asks && !deadline) continue;
            String task = clean(sentence, mine);
            if (task.length() >= 4 && seen.add(TaskExtractor.fold(task))) found.add(task);
        }
        return found;
    }

    /** True when one of {@code names} appears as a whole word. */
    static boolean mentions(String folded, Set<String> names) {
        for (String n : names) if (Pattern.compile("(?<![\\p{L}])@?" + Pattern.quote(n) + "(?![\\p{L}])").matcher(folded).find()) return true;
        return false;
    }

    /** Drops the greeting and your name: "Gabriel, você precisa preencher X" → "Preencher X". */
    static String clean(String sentence, Set<String> mine) {
        String s = sentence.trim().replaceAll("\\s+", " ");
        String folded = TaskExtractor.fold(s);
        for (String n : mine) {
            Matcher m = Pattern.compile("^(?:(?:oi|ola|bom dia|boa tarde|boa noite|hey|hi|hello)[ ,!]+)?@?" + Pattern.quote(n)
                    + "\\b[ ,:!-]*(?:(?:voce|vc|tu|you)\\s+)?(?:(?:precisa|tem que|tens que|needs? to|has to|must|pode|can you)\\s+)?").matcher(folded);
            if (m.find() && m.end() > 0) { s = s.substring(m.end()); folded = folded.substring(m.end()); break; }
        }
        s = s.replaceAll("[\\s,:;.!-]+$", "").replaceAll("^(?:por favor|please)[ ,]+", "").trim();
        if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS).trim();
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    static Set<String> folded(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        if (names != null) for (String n : names) {
            String f = TaskExtractor.fold(n == null ? "" : n.trim());
            if (f.length() >= 2) out.add(f);
        }
        return out;
    }

    /** "Gabriel, Gabi" → ["Gabriel", "Gabi"]. */
    public static List<String> splitNames(String text) {
        List<String> names = new ArrayList<>();
        if (text == null) return names;
        for (String part : text.split("[,;/]")) {
            String n = part.trim();
            if (n.length() >= 2 && !names.contains(n)) names.add(n);
        }
        return names;
    }
}
