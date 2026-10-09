package br.gabriel.sentient.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer for plugins, with no dependencies so host tests run it.
 * Objects become {@code Map<String, Object>} (insertion order kept), arrays {@code List<Object>},
 * numbers {@code Long} or {@code Double}. The navigation helpers never throw on a missing or
 * differently-typed field: services change shapes, and a mapper should skip what it can't read.
 */
public final class Json {
    private static final int MAX_DEPTH = 200;

    private final String text;
    private int at;

    private Json(String text) { this.text = text; }

    /** Parses a whole document; throws IllegalArgumentException (without the input) when malformed. */
    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("No JSON");
        Json parser = new Json(text);
        parser.space();
        Object value = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw parser.error();
        return value;
    }

    // ---- navigation ----

    /** The value at a path of object keys (or list indexes as decimal strings); null if absent. */
    public static Object at(Object value, String... path) {
        Object current = value;
        for (String key : path) {
            if (current instanceof Map) current = ((Map<?, ?>) current).get(key);
            else if (current instanceof List) {
                List<?> list = (List<?>) current;
                try {
                    int index = Integer.parseInt(key);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException notIndex) { return null; }
            } else return null;
            if (current == null) return null;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    /** A string, or a number/boolean as text; null for anything else or an empty string. */
    public static String str(Object value) {
        if (value instanceof String) return ((String) value).isEmpty() ? null : (String) value;
        if (value instanceof Long || value instanceof Boolean) return value.toString();
        if (value instanceof Double) {
            double d = (Double) value;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : value.toString();
        }
        return null;
    }

    /** A number, or a string holding one; null otherwise. */
    public static Long num(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Double) return (long) (double) (Double) value;
        if (value instanceof String) {
            try { return Long.parseLong(((String) value).trim()); }
            catch (NumberFormatException notInteger) {
                try { return (long) Double.parseDouble(((String) value).trim()); }
                catch (NumberFormatException notNumber) { return null; }
            }
        }
        return null;
    }

    public static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    /** The first of {@code keys} present in {@code value} as a non-empty string. */
    public static String firstStr(Object value, String... keys) {
        for (String key : keys) {
            String s = str(obj(value).get(key));
            if (s != null) return s;
        }
        return null;
    }

    /** Builds an object: {@code Json.object("a", 1, "b", "x")}. Null values are left out. */
    public static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
            if (keysAndValues[i + 1] != null) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return map;
    }

    // ---- writing ----

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON too deep");
        if (value == null) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) out.append(value);
        else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("Not a JSON number");
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item, depth + 1);
            }
            out.append(']');
        } else throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getSimpleName());
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    // ---- reading ----

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error();
        if (at >= text.length()) throw error();
        char c = text.charAt(at);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) { at++; return map; }
        while (true) {
            space();
            if (!peek('"')) throw error();
            String key = string();
            space();
            expect(':');
            space();
            map.put(key, value(depth + 1));
            space();
            if (peek(',')) { at++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek(']')) { at++; return list; }
        while (true) {
            space();
            list.add(value(depth + 1));
            space();
            if (peek(',')) { at++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        at++; // opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error();
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw error();
            char e = text.charAt(at++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error();
                    try { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                    catch (NumberFormatException bad) { throw error(); }
                    at += 4;
                    break;
                default: throw error();
            }
        }
    }

    private Object number() {
        int start = at;
        if (peek('-')) at++;
        digits();
        boolean fraction = false;
        if (peek('.')) { fraction = true; at++; digits(); }
        if (peek('e') || peek('E')) {
            fraction = true;
            at++;
            if (peek('+') || peek('-')) at++;
            digits();
        }
        String n = text.substring(start, at);
        if (!fraction) {
            try { return Long.parseLong(n); } catch (NumberFormatException tooBig) { /* fall through */ }
        }
        try { return Double.parseDouble(n); } catch (NumberFormatException bad) { throw error(); }
    }

    private void digits() {
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at)) && text.charAt(at) < 128) at++;
        if (at == start) throw error();
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw error();
        at += word.length();
        return value;
    }

    private boolean peek(char c) { return at < text.length() && text.charAt(at) == c; }

    private void expect(char c) {
        if (!peek(c)) throw error();
        at++;
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') at++;
            else break;
        }
    }

    private IllegalArgumentException error() { return new IllegalArgumentException("Malformed JSON at " + at); }
}
