package veille;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dates en français : libellés de mois, plages formatées, dates du fil equipe-france. */
final class FrDates {

    private FrDates() {}

    private static final String[] FR_MONTHS = {
            "", "janvier", "février", "mars", "avril", "mai", "juin",
            "juillet", "août", "septembre", "octobre", "novembre", "décembre"
    };

    /** Mois français (avec accents, tels qu'écrits par equipe-france) → numéro. */
    static final Map<String, Integer> FR_MONTH_NUM = new HashMap<>();
    static {
        for (int i = 1; i <= 12; i++) FR_MONTH_NUM.put(FR_MONTHS[i], i);
    }

    /** Libellé français du mois (1..12). */
    static String monthName(int month) {
        return FR_MONTHS[month];
    }

    /** « 9 – 14 juin 2026 » ; à cheval : « 30 juin – 5 juillet 2026 ». */
    static String dateRange(LocalDate start, LocalDate end, boolean withYear) {
        String sMonth = FR_MONTHS[start.getMonthValue()];
        String eMonth = FR_MONTHS[end.getMonthValue()];
        StringBuilder sb = new StringBuilder();
        if (start.getMonthValue() == end.getMonthValue()
                && start.getYear() == end.getYear()) {
            sb.append(start.getDayOfMonth()).append(" – ")
              .append(end.getDayOfMonth()).append(' ').append(sMonth);
        } else {
            sb.append(start.getDayOfMonth()).append(' ').append(sMonth)
              .append(" – ")
              .append(end.getDayOfMonth()).append(' ').append(eMonth);
        }
        if (withYear) sb.append(' ').append(end.getYear());
        return sb.toString();
    }

    /** Parse une date du fil (« 5 juin ») en {@link LocalDate}, année déduite (date
     *  récente passée). {@code null} si illisible. */
    static LocalDate parseFeedDate(String text, LocalDate today) {
        if (text == null) return null;
        String low = TextUtil.stripAccents(text.toLowerCase(Locale.ROOT));
        Matcher dm = Pattern.compile("(\\d{1,2})").matcher(low);
        if (!dm.find()) return null;
        int day = Integer.parseInt(dm.group(1));
        int month = 0;
        for (Map.Entry<String, Integer> e : FR_MONTH_NUM.entrySet()) {
            if (low.contains(TextUtil.stripAccents(e.getKey()))) { month = e.getValue(); break; }
        }
        if (month == 0) return null;
        try {
            LocalDate d = LocalDate.of(today.getYear(), month, day);
            if (d.isAfter(today)) d = d.minusYears(1); // l'actu date du passé récent
            return d;
        } catch (Exception e) {
            return null;
        }
    }
}
