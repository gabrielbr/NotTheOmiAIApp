package br.gabriel.sentient;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deadlines in Portuguese and English messages ("até sexta", "amanhã", "dia 15", "15/10",
 * "fim do mês", "by Friday", "before the 15th", "next week"), resolved against the message's own
 * time, not today. Returns null when there's no deadline. Plain Java (host-tested).
 */
public final class DueDates {
    private DueDates() {}

    private static final String[][] WEEKDAYS = {
            {"segunda", "monday", "mon"}, {"terca", "tuesday", "tue"}, {"quarta", "wednesday", "wed"},
            {"quinta", "thursday", "thu"}, {"sexta", "friday", "fri"}, {"sabado", "saturday", "sat"},
            {"domingo", "sunday", "sun"}};
    private static final Pattern DAY_MONTH = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b"),
            DAY_ONLY = Pattern.compile("\\b(?:dia|the|on the|before the|by the|until the|ate o dia)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b"),
            ORDINAL = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)\\b");

    public static LocalDate parse(String text, long ts, ZoneId zone) {
        if (text == null || text.isEmpty()) return null;
        String t = " " + TaskExtractor.fold(text) + " ";
        LocalDate day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
        if (t.contains("depois de amanha") || t.contains("day after tomorrow")) return day.plusDays(2);
        if (t.matches("(?s).*\\b(amanha|tomorrow)\\b.*")) return day.plusDays(1);
        if (t.matches("(?s).*\\b(hoje|today|tonight|ainda hoje|end of (the )?day)\\b.*")) return day;
        if (t.matches("(?s).*\\b(fim|final) do mes\\b.*") || t.matches("(?s).*\\bend of (the )?month\\b.*"))
            return day.with(TemporalAdjusters.lastDayOfMonth());
        if (t.matches("(?s).*\\b(semana que vem|proxima semana|next week)\\b.*"))
            return day.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        if (t.matches("(?s).*\\b(fim de semana|final de semana|this weekend|the weekend)\\b.*"))
            return day.getDayOfWeek() == DayOfWeek.SATURDAY ? day : day.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));

        Matcher dm = DAY_MONTH.matcher(t);
        if (dm.find()) {
            int a = Integer.parseInt(dm.group(1)), b = Integer.parseInt(dm.group(2));
            int d = a, m = b;
            if (b > 12 && a <= 12) { d = b; m = a; } // US month/day
            if (m >= 1 && m <= 12 && d >= 1 && d <= 31) {
                int year = dm.group(3) != null ? year(dm.group(3)) : day.getYear();
                LocalDate date = safe(year, m, d);
                if (date != null && dm.group(3) == null && date.isBefore(day.minusDays(7))) date = safe(year + 1, m, d);
                if (date != null) return date;
            }
        }
        Matcher only = DAY_ONLY.matcher(t);
        Matcher ordinal = ORDINAL.matcher(t);
        Integer dom = only.find() ? Integer.valueOf(only.group(1)) : ordinal.find() ? Integer.valueOf(ordinal.group(1)) : null;
        if (dom != null && dom >= 1 && dom <= 31) {
            LocalDate date = safe(day.getYear(), day.getMonthValue(), dom);
            if (date == null || date.isBefore(day)) {
                LocalDate next = day.plusMonths(1);
                date = safe(next.getYear(), next.getMonthValue(), dom);
            }
            if (date != null) return date;
        }
        for (int i = 0; i < WEEKDAYS.length; i++) {
            for (String w : WEEKDAYS[i]) {
                if (!t.matches("(?s).*\\b" + w + "(-feira| feira)?\\b.*")) continue;
                if (w.length() == 3 && !t.matches("(?s).*\\b(on|by|until|before|next) " + w + "\\b.*")) continue;
                DayOfWeek target = DayOfWeek.of(i + 1);
                return day.with(TemporalAdjusters.next(target)); // "sexta" said on a Friday means next Friday
            }
        }
        return null;
    }

    private static int year(String y) {
        int n = Integer.parseInt(y);
        return n < 100 ? 2000 + n : n;
    }

    private static LocalDate safe(int y, int m, int d) {
        try { return LocalDate.of(y, m, d); } catch (java.time.DateTimeException invalid) { return null; }
    }
}
