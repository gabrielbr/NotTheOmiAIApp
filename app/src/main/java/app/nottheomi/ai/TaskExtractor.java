package app.nottheomi.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, rule-based to-do finder for mixed Portuguese/English transcripts.
 * Pure Java (no Android types) so it runs in host tests. Output is a suggestion list
 * for the user to review; nothing is sent anywhere from here. Date words ("amanhã",
 * "sexta", "tomorrow") stay in the task text so Todoist Quick Add can parse them.
 */
public final class TaskExtractor {
    static final int MAX_TASK_CHARS = 200;
    private static final int MIN_TASK_CHARS = 3;

    /**
     * Cue phrases, matched against accent-folded lowercase text. The task is the text
     * after the cue. Longer/more specific cues come first in each alternation.
     */
    private static final Pattern CUE = Pattern.compile("\\b(?:"
            // Portuguese
            + "(?:eu )?nao (?:posso |pode |podemos )?(?:me )?esquecer (?:de |que )?"
            + "|(?:me )?lembr(?:a|e|ar)(?:-me)? (?:de |que )?"
            + "|lembrete:? "
            + "|(?:eu )?(?:preciso|precisamos|precisa|vou precisar) (?:de )?"
            + "|(?:eu )?(?:tenho|temos|tem|tinha|vou ter|vamos ter) (?:que|de) "
            + "|(?:eu )?(?:devo|devemos) "
            + "|(?:ficou|fica|ficamos|fiquei) (?:combinado )?(?:de|que) "
            + "|combinamos (?:de|que) "
            + "|tarefa:? "
            + "|a fazer:? "
            // English
            + "|(?:i |we )?(?:don't|do not) forget (?:to )?"
            + "|remind me (?:to )?"
            + "|(?:i |we )?(?:need|have|has|got|must|should) to "
            + "|(?:i|we) (?:must|should|gotta) "
            + "|remember to "
            + "|make sure (?:to |that )?"
            + "|(?=follow up (?:with|on) )" // keep "Follow up" in the task
            + "|to ?do:? "
            + ")");

    /** Sentence breaks; Whisper output is punctuated, Vosk drafts are not. */
    private static final Pattern SENTENCE = Pattern.compile("[.!?;\\n]+\\s*|\\s+(?:e|and) (?:depois|then)\\s+");

    private TaskExtractor() { }

    public static List<String> extract(String transcript) {
        List<String> tasks = new ArrayList<>();
        if (transcript == null || transcript.isEmpty()) return tasks;
        Set<String> seen = new LinkedHashSet<>();
        for (String sentence : SENTENCE.split(transcript)) {
            String folded = fold(sentence);
            Matcher cue = CUE.matcher(folded);
            if (!cue.find()) continue;
            String task = clean(sentence.substring(cue.end()));
            if (task.length() < MIN_TASK_CHARS) continue;
            if (seen.add(fold(task))) tasks.add(task);
        }
        return tasks;
    }

    /** Lowercase and strip accents while keeping indices aligned with the original. */
    static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) { out.append(Character.toLowerCase(c)); continue; }
            String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            char first = base.isEmpty() ? c : base.charAt(0);
            String lower = String.valueOf(first).toLowerCase(Locale.ROOT);
            out.append(lower.length() == 1 ? lower.charAt(0) : first);
        }
        return out.toString();
    }

    private static String clean(String raw) {
        String task = raw.trim().replaceAll("\\s+", " ").replaceAll("[\\s,:;\\-–—]+$", "");
        if (task.length() > MAX_TASK_CHARS) task = task.substring(0, MAX_TASK_CHARS).trim();
        if (task.isEmpty()) return task;
        return task.substring(0, 1).toUpperCase(Locale.ROOT) + task.substring(1);
    }
}
