package veille;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Calendrier BWF World Tour (corporate.bwfbadminton.com, WordPress rendu serveur,
 * Jsoup OK — cf. carte des sources CLAUDE.md). Ne garde que les tournois dont la
 * catégorie commence par « HSBC BWF World Tour », mappe la catégorie vers un tier
 * ({@code wtf|1000|750|500|300}), et reconstruit les dates à partir du mois de la
 * section et de la plage de jours (gère le chevauchement sur deux mois).
 */
final class BwfCalendar {

    private BwfCalendar() {}

    private static final String CAL_URL =
            "https://corporate.bwfbadminton.com/events/calendar/";

    /** Mois anglais (titres de section) → numéro. */
    private static final Map<String, Integer> MONTH_NUM = new HashMap<>();
    static {
        String[] en = {
                "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
                "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
        };
        for (int i = 0; i < en.length; i++) MONTH_NUM.put(en[i], i + 1);
    }

    /** Télécharge et parse le calendrier (source PRIMAIRE : tout échec remonte). */
    static List<Tournament> fetchTournaments() throws Exception {
        Document doc = Http.fetch(CAL_URL);
        return parseTournaments(doc, extractYear(doc));
    }

    private static int extractYear(Document doc) {
        Matcher m = Pattern.compile("currentYear\\s*=\\s*'(\\d{4})'").matcher(doc.outerHtml());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return Window.today().getYear();   // année de référence = Europe/Paris (cf. Window)
    }

    private static List<Tournament> parseTournaments(Document doc, int year) {
        List<Tournament> out = new ArrayList<>();

        // Chaque mois = une section .item-results avec un titre <h2> et une table.
        for (Element section : doc.select("div.item-results")) {
            Element title = section.selectFirst("h2.bwf-title_under--red");
            if (title == null) continue;
            String monthName = title.text().trim().toUpperCase();
            Integer month = MONTH_NUM.get(monthName);
            if (month == null) continue;
            int startMonth = month;

            for (Element row : section.select("table tr")) {
                if (row.hasClass("tr-tournament-detail")) continue;

                Element catEl = row.selectFirst("td[width=14%] .category .name");
                if (catEl == null) continue;
                String category = catEl.text().trim();
                String tier = tierOf(category);
                if (tier == null) continue; // pas un tournoi World Tour suivi

                Element nameEl = row.selectFirst("td[width=34%] .name a");
                if (nameEl == null) nameEl = row.selectFirst("td[width=34%] .name");
                if (nameEl == null) continue;
                String name = nameEl.text().trim();

                Element dateEl = row.selectFirst("td[width=10%]");
                if (dateEl == null) continue;
                int[] days = parseDayRange(dateEl.text());
                if (days == null) continue;
                int startDay = days[0];
                int endDay = days[1];

                // Reconstruction des dates : le mois vient de la section ; si le
                // jour de fin est < jour de début, le tournoi est à cheval sur
                // le mois suivant (ex. « 30 JUNE - 05 JULY »).
                int endMonth = startMonth;
                int endYear = year;
                if (endDay < startDay) {
                    endMonth = startMonth + 1;
                    if (endMonth > 12) {
                        endMonth = 1;
                        endYear = year + 1;
                    }
                }

                LocalDate start, end;
                try {
                    start = LocalDate.of(year, startMonth, startDay);
                    end = LocalDate.of(endYear, endMonth, endDay);
                } catch (Exception ex) {
                    continue; // jour/mois improbable → on saute la ligne
                }

                Element countryEl = row.selectFirst(".country_code");
                Element cityEl = row.selectFirst("td[width=12%] .category");
                String country = countryEl != null ? countryEl.text().trim() : "";
                String city = cityEl != null ? cityEl.text().trim() : "";
                String location = buildLocation(city, country);

                // La dotation est dans la ligne détail (repérée par data-target).
                // NB : les têtes de série et le fuseau n'y figurent pas (ils
                // dépendent des tableaux / pages de résultats) → laissés neutres.
                String prize = parsePrize(row, section);

                out.add(new Tournament(name, tier, location, prize, start, end));
            }
        }
        return out;
    }

    /** Lit la dotation dans la ligne détail liée par {@code data-target="#id"}. */
    private static String parsePrize(Element row, Element section) {
        Element expander = row.selectFirst("a.bwf-calendar_expander[data-target]");
        if (expander == null) return "—";
        String id = expander.attr("data-target").replace("#", "").trim();
        if (id.isEmpty()) return "—";
        Element detail = section.getElementById(id);
        if (detail == null) return "—";
        for (Element btn : detail.select(".bwf-button")) {
            String txt = btn.text();
            if (txt.toUpperCase(Locale.ROOT).contains("PRIZE")) {
                Matcher m = Pattern.compile("([\\d][\\d.,]*)").matcher(txt);
                if (m.find()) {
                    String digits = m.group(1).replaceAll("[.,]", "");
                    // Regroupe les milliers par espace insécable fine : « 500 000 $ ».
                    // ATTENTION : la chaîne de remplacement est un U+202F littéral
                    // (invisible à l'œil) — ne pas le « corriger » en espace simple.
                    String grouped = digits.replaceAll("\\B(?=(\\d{3})+(?!\\d))", " ");
                    return grouped + " $";
                }
            }
        }
        return "—";
    }

    /** Mappe la catégorie BWF vers un tier, ou null si hors World Tour suivi. */
    private static String tierOf(String category) {
        if (category == null) return null;
        String c = category.toLowerCase();
        if (!c.startsWith("hsbc bwf world tour")) return null;
        if (c.contains("super 1000")) return "1000";
        if (c.contains("super 750")) return "750";
        if (c.contains("super 500")) return "500";
        if (c.contains("super 300")) return "300";
        if (c.contains("finals")) return "wtf";
        return null; // « HSBC BWF World Tour » sans niveau → ignoré
    }

    /** Extrait les deux premiers entiers d'un libellé de dates (« 09 -14 »). */
    static int[] parseDayRange(String text) {
        Matcher m = Pattern.compile("(\\d{1,2})").matcher(text);
        List<Integer> nums = new ArrayList<>();
        while (m.find() && nums.size() < 2) {
            nums.add(Integer.parseInt(m.group(1)));
        }
        if (nums.isEmpty()) return null;
        int start = nums.get(0);
        int end = nums.size() > 1 ? nums.get(1) : start;
        return new int[]{start, end};
    }

    private static String buildLocation(String city, String country) {
        if (!city.isEmpty() && !country.isEmpty()) return city + ", " + country;
        if (!city.isEmpty()) return city;
        return country;
    }
}
