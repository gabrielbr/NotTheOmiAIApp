package br.gabriel.sentient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the [#123] item citations in an answer, in order of first use. */
public final class Citations {
    static final Pattern CITE = Pattern.compile("\\[#(\\d{1,18})\\]");

    private Citations() {}

    public static List<Long> ids(String answer) {
        Set<Long> ids = new LinkedHashSet<>();
        if (answer != null) {
            Matcher m = CITE.matcher(answer);
            while (m.find()) ids.add(Long.parseLong(m.group(1)));
        }
        return new ArrayList<>(ids);
    }

    /** Only ids that exist in the store; a model can invent an id, and that must not become a link. */
    public static List<Long> existing(Db db, List<Long> ids) throws Exception {
        List<Long> real = new ArrayList<>();
        for (Long id : ids) if (!db.query("SELECT 1 FROM items WHERE id = ?", id).isEmpty()) real.add(id);
        return real;
    }

    /** Rewrites [#123] to [1], [2]… in citation order; unknown ids are dropped from the text. */
    public static String numbered(String answer, List<Long> real) {
        if (answer == null) return "";
        Matcher m = CITE.matcher(answer);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int n = real.indexOf(Long.parseLong(m.group(1))) + 1;
            m.appendReplacement(out, n > 0 ? "[" + n + "]" : "");
        }
        m.appendTail(out);
        return out.toString().replaceAll(" +([.,;:!?])", "$1").replaceAll("  +", " ").trim();
    }
}
