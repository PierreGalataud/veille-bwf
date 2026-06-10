package veille;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Collecteur v2 — collecte réelle du calendrier BWF World Tour.
 *
 * Lit https://corporate.bwfbadminton.com/events/calendar/, ne garde que les
 * tournois dont la catégorie commence par « HSBC BWF World Tour », mappe la
 * catégorie vers un tier ({@code wtf|1000|750|500|300}), reconstruit les dates
 * à partir du mois de la section et de la plage de jours (gère le chevauchement
 * sur deux mois), puis répartit les tournois entre {@code current} (chevauchant
 * la date du jour) et {@code upcoming} (à venir).
 *
 * Échec gracieux : si le fetch ou le parsing échoue, ou si rien n'est extrait,
 * on NE réécrit PAS data.json et on sort en erreur, pour ne pas vider le site.
 *
 * Hors périmètre de ce milestone (tableaux/draws plus tard) : les têtes de série
 * ({@code seeds}), le suivi des Français ({@code frenchStatus}, {@code players}),
 * la dotation et le fuseau restent des valeurs neutres « à confirmer ».
 *
 * Argument optionnel : chemin de sortie (défaut "public/data.json").
 */
public class Collector {

    private static final String CAL_URL =
            "https://corporate.bwfbadminton.com/events/calendar/";
    private static final String USER_AGENT =
            "veille-bwf/1.0 (projet personnel; +https://github.com)";
    private static final int TIMEOUT_MS = 20000;

    /** Mois anglais → (numéro, libellé français). */
    private static final Map<String, int[]> MONTH_NUM = new LinkedHashMap<>();
    private static final String[] FR_MONTHS = {
            "", "janvier", "février", "mars", "avril", "mai", "juin",
            "juillet", "août", "septembre", "octobre", "novembre", "décembre"
    };
    static {
        String[] en = {
                "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
                "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
        };
        for (int i = 0; i < en.length; i++) {
            MONTH_NUM.put(en[i], new int[]{i + 1});
        }
    }

    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "public/data.json");
        try {
            String json = buildData();

            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, json, StandardCharsets.UTF_8);
            System.out.println("data.json écrit : " + out.toAbsolutePath());
        } catch (Exception e) {
            // Échec gracieux : on laisse l'ancien data.json intact (pas de commit
            // côté CI puisque le fichier n'a pas changé) et on signale l'échec.
            System.err.println("Collecte échouée — data.json laissé intact : " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Un tournoi World Tour extrait du calendrier. */
    private record Tournament(
            String name, String tier, String location,
            LocalDate start, LocalDate end) {}

    private static String buildData() throws Exception {
        Document doc = Jsoup.connect(CAL_URL)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0) // la page fait ~1,4 Mo : ne pas tronquer (défaut = 1 Mo)
                .get();

        int year = extractYear(doc);
        List<Tournament> all = parseTournaments(doc, year);

        if (all.isEmpty()) {
            // Soit la page a changé de structure, soit le fetch a renvoyé une
            // page vide/erreur : on refuse d'écrire un data.json vide.
            throw new IllegalStateException(
                    "aucun tournoi World Tour extrait — structure de page changée ou réponse invalide");
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Tournament> current = new ArrayList<>();
        List<Tournament> upcoming = new ArrayList<>();
        for (Tournament t : all) {
            if (!t.start().isAfter(today) && !t.end().isBefore(today)) {
                current.add(t);              // start <= today <= end
            } else if (t.start().isAfter(today)) {
                upcoming.add(t);             // à venir
            }
            // sinon : tournoi passé → ignoré
        }
        current.sort((a, b) -> a.start().compareTo(b.start()));
        upcoming.sort((a, b) -> a.start().compareTo(b.start()));

        // ---- Construction du JSON conforme au contrat (cf. CLAUDE.md) ----
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("weekLabel", weekLabel(today));

        List<Object> currentJson = new ArrayList<>();
        for (Tournament t : current) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", t.name());
            o.put("tier", t.tier());
            o.put("location", t.location());
            o.put("dates", dateRange(t.start(), t.end(), true));
            o.put("prize", "—");
            o.put("timezone", "—");
            o.put("dayLabel", dayLabel(t.start(), t.end(), today));
            o.put("seeds", new ArrayList<>());
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("present", false);
            fr.put("title", "Présence française à vérifier");
            fr.put("note", "Le collecteur ne lit pas encore les tableaux (draws) : "
                    + "présence des Français à confirmer sur le tableau officiel.");
            fr.put("confirm", true);
            o.put("frenchStatus", fr);
            currentJson.add(o);
        }
        root.put("current", currentJson);

        // players : alimenté plus tard par la lecture des draws/résultats.
        root.put("players", new ArrayList<>());

        List<Object> upcomingJson = new ArrayList<>();
        for (Tournament t : upcoming) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("dates", dateRange(t.start(), t.end(), false));
            o.put("name", t.name());
            o.put("tier", t.tier());
            o.put("french", "FR : à confirmer");
            upcomingJson.add(o);
        }
        root.put("upcoming", upcomingJson);

        ObjectMapper mapper = new ObjectMapper();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static int extractYear(Document doc) {
        Matcher m = Pattern.compile("currentYear\\s*=\\s*'(\\d{4})'").matcher(doc.outerHtml());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return LocalDate.now(ZoneOffset.UTC).getYear();
    }

    private static List<Tournament> parseTournaments(Document doc, int year) {
        List<Tournament> out = new ArrayList<>();

        // Chaque mois = une section .item-results avec un titre <h2> et une table.
        for (Element section : doc.select("div.item-results")) {
            Element title = section.selectFirst("h2.bwf-title_under--red");
            if (title == null) continue;
            String monthName = title.text().trim().toUpperCase();
            int[] month = MONTH_NUM.get(monthName);
            if (month == null) continue;
            int startMonth = month[0];

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

                out.add(new Tournament(name, tier, location, start, end));
            }
        }
        return out;
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
    private static int[] parseDayRange(String text) {
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

    /** « 9 – 14 juin 2026 » ; à cheval : « 30 juin – 5 juillet 2026 ». */
    private static String dateRange(LocalDate start, LocalDate end, boolean withYear) {
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

    private static String weekLabel(LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate sunday = monday.plusDays(6);
        return "Semaine du " + dateRange(monday, sunday, true);
    }

    /** « Jour 2 / 6 · 10 juin » pour un tournoi en cours. */
    private static String dayLabel(LocalDate start, LocalDate end, LocalDate today) {
        long total = end.toEpochDay() - start.toEpochDay() + 1;
        long day = today.toEpochDay() - start.toEpochDay() + 1;
        if (day < 1) day = 1;
        if (day > total) day = total;
        return "Jour " + day + " / " + total + " · "
                + today.getDayOfMonth() + " " + FR_MONTHS[today.getMonthValue()];
    }
}
