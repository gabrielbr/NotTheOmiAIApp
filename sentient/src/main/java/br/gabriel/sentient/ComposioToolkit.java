package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One Composio toolkit GMind reads (Gmail, Calendar…): the read tools it may run and how their
 * output becomes items. Composio documents tool output only as "data", so mappers read the
 * service's own field names tolerantly and skip what they can't read. Plain Java (host-tested).
 */
abstract class ComposioToolkit {
    /** How far back the first sync of a newly connected source goes. */
    static final long FIRST_SYNC_MS = 30L * 24 * 60 * 60 * 1000;

    /** Runs read tools for one connected account. */
    interface Tools {
        Object run(String slug, Map<String, Object> arguments) throws Exception;
        /** Fetches a file a tool handed back by HTTPS URL (e.g. an exported doc); text only. */
        String download(String httpsUrl) throws Exception;
    }

    static final List<ComposioToolkit> ALL = Collections.unmodifiableList(Arrays.asList(
            new ComposioGmail(), new ComposioCalendar(), new ComposioDrive()));

    static ComposioToolkit forSlug(String slug) {
        for (ComposioToolkit toolkit : ALL) if (toolkit.slug().equals(slug)) return toolkit;
        return null;
    }

    static ComposioToolkit forSource(String sourceId) {
        for (ComposioToolkit toolkit : ALL) if (toolkit.sourceId().equals(sourceId)) return toolkit;
        return null;
    }

    /** Composio's toolkit slug, e.g. "gmail". */
    abstract String slug();

    abstract String displayName();

    /** What the person sees before connecting: what GMind will read. */
    abstract String reads();

    abstract Set<String> readTools();

    abstract PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception;

    final String sourceId() { return "composio." + slug(); }

    // ---- helpers for mappers ----

    /** The first array found under any of {@code keys}, at the top level or one wrapper down. */
    static List<Object> array(Object data, String... keys) {
        for (Object scope : new Object[]{data, Json.at(data, "response_data"), Json.at(data, "data")}) {
            for (String key : keys) {
                Object found = Json.at(scope, key);
                if (found instanceof List) return Json.list(found);
            }
        }
        return Collections.emptyList();
    }

    /** Like {@link #array} for a single string field, e.g. a next-page token. */
    static String field(Object data, String... keys) {
        for (Object scope : new Object[]{data, Json.at(data, "response_data"), Json.at(data, "data")}) {
            String s = Json.firstStr(scope, keys);
            if (s != null) return s;
        }
        return null;
    }

    static String iso(long millis) { return Instant.ofEpochMilli(millis).toString(); }

    /** RFC 3339 / ISO-8601 instant, a plain date (start of that day in {@code zone}), or epoch millis. */
    static long time(Object value, ZoneId zone) {
        if (value instanceof Long || value instanceof Double) return millis(Json.num(value));
        String s = Json.str(value);
        if (s == null) return 0;
        s = s.trim();
        if (s.matches("\\d{9,}")) return millis(Long.parseLong(s));
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) { }
        try { return Instant.parse(s).toEpochMilli(); } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) { }
        return 0;
    }

    /** Seconds or milliseconds since the epoch, as milliseconds. */
    private static long millis(long n) { return n < 100_000_000_000L ? n * 1000 : n; }

    private static final Pattern TAG = Pattern.compile("<[^>]{0,2000}>"), BREAK = Pattern.compile("(?i)<br\\s*/?>|</p>|</div>|</li>");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f]+"), LINES = Pattern.compile("\\n{3,}");

    /** Rough HTML to text, enough for search and for a model to read. */
    static String plain(String html) {
        if (html == null) return "";
        String s = BREAK.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("\r\n", "\n");
        s = SPACES.matcher(s).replaceAll(" ");
        return LINES.matcher(s).replaceAll("\n\n").trim();
    }

    static String cap(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** "Ana Souza <ana@x.com>" → {"Ana Souza", "ana@x.com"}; a bare address has no name. */
    static String[] nameAndAddress(String from) {
        if (from == null) return new String[]{null, null};
        String s = from.trim();
        int lt = s.lastIndexOf('<'), gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            String name = s.substring(0, lt).trim().replaceAll("^\"|\"$", "").trim();
            String address = s.substring(lt + 1, gt).trim().toLowerCase(java.util.Locale.ROOT);
            return new String[]{name.isEmpty() ? null : name, address.isEmpty() ? null : address};
        }
        return new String[]{null, s.contains("@") ? s.toLowerCase(java.util.Locale.ROOT) : null};
    }

    /** Cursor state is a small JSON object; a corrupt cursor starts over from the window. */
    static Map<String, Object> state(String cursor) {
        if (cursor == null) return null;
        try {
            Object parsed = Json.parse(cursor);
            return parsed instanceof Map ? Json.obj(parsed) : null;
        } catch (IllegalArgumentException corrupt) {
            return null;
        }
    }
}
