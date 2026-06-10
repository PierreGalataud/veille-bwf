package veille;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
 * Le {@code frenchStatus} de chaque tournoi de {@code current} est enrichi via
 * equipe-france.fr (cf. {@link #buildFrenchStatus}) : on retrouve la page du
 * tournoi par réconciliation nom + dates, puis on lit la phrase de participation.
 *
 * Échec gracieux : si le fetch ou le parsing du calendrier BWF (source primaire)
 * échoue, ou si rien n'est extrait, on NE réécrit PAS data.json et on sort en
 * erreur, pour ne pas vider le site. equipe-france est une source secondaire
 * d'enrichissement : si elle est indisponible, on retombe sur un frenchStatus
 * neutre « à vérifier » sans bloquer l'écriture (on ne blanchit rien).
 *
 * Hors périmètre de ce milestone (ÉTAPE 2) : {@code players[]} et les têtes de
 * série ({@code seeds}) ; la dotation et le fuseau restent neutres.
 *
 * Argument optionnel : chemin de sortie (défaut "public/data.json").
 */
public class Collector {

    private static final String CAL_URL =
            "https://corporate.bwfbadminton.com/events/calendar/";
    private static final String EF_BASE = "https://www.equipe-france.fr";
    private static final String EF_CAL_URL = EF_BASE + "/badminton/calendrier";
    private static final String USER_AGENT =
            "veille-bwf/1.0 (projet personnel; +https://github.com)";
    private static final int TIMEOUT_MS = 20000;
    /** Pause entre deux requêtes equipe-france (politesse, cf. garde-fous). */
    private static final long EF_THROTTLE_MS = 800;

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

    /** Mois français (avec accents, tels qu'écrits par equipe-france) → numéro. */
    private static final Map<String, Integer> FR_MONTH_NUM = new HashMap<>();
    static {
        for (int i = 1; i <= 12; i++) FR_MONTH_NUM.put(FR_MONTHS[i], i);
    }

    /**
     * Mots à ignorer pour la réconciliation des noms de tournois : articles,
     * mots génériques et marques de sponsors. On garde les jetons géographiques
     * et distinctifs (australian, canada, china, finals…).
     */
    private static final Set<String> NAME_STOPWORDS = new HashSet<>(List.of(
            "open", "de", "du", "des", "d", "le", "la", "les", "l", "et",
            "badminton", "tournament", "championship", "championships",
            "super", "1000", "750", "500", "300", "100", "2026", "2027",
            "ltd", "co", "group", "powered", "by",
            // sponsors fréquents
            "hsbc", "yonex", "victor", "li", "ning", "lining", "daihatsu",
            "sathio", "sands", "petronas", "toyota", "perodua", "sandschina"));

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

    /** Statut français d'un tournoi (alimente {@code frenchStatus}). */
    private record FrenchStatus(boolean present, String title, String note, boolean confirm) {
        /** Valeur par défaut quand equipe-france est indisponible. */
        static final FrenchStatus UNKNOWN = new FrenchStatus(false,
                "Présence française à vérifier",
                "Suivi equipe-france momentanément indisponible — à confirmer.", true);
    }

    /** Une entrée du calendrier equipe-france. {@code start}/{@code end} = {mois, jour}. */
    private record EfEntry(String name, String url, int[] start, int[] end) {}

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

        // Enrichissement du statut français via equipe-france (best-effort).
        Map<String, FrenchStatus> frByName = buildFrenchStatus(current);

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
            FrenchStatus fs = frByName.getOrDefault(t.name(), FrenchStatus.UNKNOWN);
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("present", fs.present());
            fr.put("title", fs.title());
            fr.put("note", fs.note());
            fr.put("confirm", fs.confirm());
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

    // ============================================================
    //  ÉTAPE 1 — statut français des tournoi en cours
    // ============================================================

    /**
     * Pour chaque tournoi en cours, retrouve sa page equipe-france (réconciliation
     * nom + dates) et en lit la phrase de participation. Source secondaire :
     * tout échec retombe silencieusement sur {@link FrenchStatus#UNKNOWN} sans
     * empêcher l'écriture du data.json (on ne blanchit jamais le site).
     *
     * @return statut indexé par {@code Tournament.name()} (clé identique au JSON).
     */
    private static Map<String, FrenchStatus> buildFrenchStatus(List<Tournament> current) {
        Map<String, FrenchStatus> result = new HashMap<>();
        if (current.isEmpty()) return result;

        List<EfEntry> entries;
        try {
            entries = parseEfCalendar(fetch(EF_CAL_URL));
        } catch (Exception e) {
            System.err.println("equipe-france (calendrier) indisponible — frenchStatus à vérifier : " + e);
            return result; // tous les tournois retombent sur UNKNOWN
        }

        for (Tournament t : current) {
            try {
                EfEntry match = matchEntry(t, entries);
                if (match == null) {
                    result.put(t.name(), new FrenchStatus(false,
                            "Aucun Français attendu",
                            "Aucune page de suivi sur equipe-france.fr pour ce tournoi — "
                                    + "aucun Français annoncé (à confirmer).", true));
                    continue;
                }
                Thread.sleep(EF_THROTTLE_MS); // requêtes espacées (garde-fous)
                String url = match.url().startsWith("http") ? match.url() : EF_BASE + match.url();
                Document page = fetch(url);
                if (!confirmsMatch(t, page)) {
                    // La page candidate ne correspond pas vraiment au tournoi BWF.
                    result.put(t.name(), new FrenchStatus(false,
                            "Aucun Français attendu",
                            "Pas de correspondance fiable sur equipe-france.fr — à confirmer.", true));
                    continue;
                }
                result.put(t.name(), parseParticipation(page));
            } catch (Exception e) {
                System.err.println("equipe-france KO pour « " + t.name() + " » : " + e);
                // tournoi laissé sur UNKNOWN
            }
        }
        return result;
    }

    private static Document fetch(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0)
                .get();
    }

    /** Extrait les lignes du calendrier equipe-france (tables groupées par mois). */
    private static List<EfEntry> parseEfCalendar(Document cal) {
        List<EfEntry> out = new ArrayList<>();
        for (Element row : cal.select("div.classement table tbody tr")) {
            Element a = row.selectFirst("th[scope=row] a");
            if (a == null) continue;
            String name = a.text().trim();
            String url = a.attr("href").trim();
            if (name.isEmpty() || !url.contains("/badminton/")) continue;

            // La cellule de dates est celle qui contient un nom de mois français.
            String dateText = "";
            for (Element td : row.select("td")) {
                String txt = td.text().toLowerCase(Locale.ROOT);
                if (FR_MONTH_NUM.keySet().stream().anyMatch(txt::contains)) {
                    dateText = td.text();
                    break;
                }
            }
            int[][] range = parseEfDates(dateText);
            out.add(new EfEntry(name, url, range[0], range[1]));
        }
        return out;
    }

    /**
     * Analyse une cellule de dates equipe-france (« 30 juin – 5 juillet », ou
     * « 16 – 21 juin », parfois avec un libellé responsive dupliqué).
     *
     * @return {@code {{startMonth,startDay},{endMonth,endDay}}}, valeurs à 0 si absent.
     */
    private static int[][] parseEfDates(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        // mois (valeur + position)
        List<int[]> months = new ArrayList<>(); // {monthNum, position}
        Matcher mm = Pattern.compile(
                "janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre")
                .matcher(low);
        while (mm.find()) months.add(new int[]{FR_MONTH_NUM.get(mm.group()), mm.start()});
        // jours (valeur + position)
        List<int[]> days = new ArrayList<>(); // {day, position}
        Matcher dm = Pattern.compile("\\b(\\d{1,2})\\b").matcher(low);
        while (dm.find()) days.add(new int[]{Integer.parseInt(dm.group(1)), dm.start()});

        if (months.isEmpty() || days.isEmpty()) return new int[][]{{0, 0}, {0, 0}};

        int startMonth = months.get(0)[0];
        int endMonth = months.get(months.size() - 1)[0];
        int startDay = days.get(0)[0];
        // jour de fin = dernier nombre situé avant le dernier mois cité
        int lastMonthPos = months.get(months.size() - 1)[1];
        int endDay = startDay;
        for (int[] d : days) if (d[1] < lastMonthPos) endDay = d[0];

        return new int[][]{{startMonth, startDay}, {endMonth, endDay}};
    }

    /** Choisit l'entrée equipe-france correspondant au tournoi BWF, ou null. */
    private static EfEntry matchEntry(Tournament t, List<EfEntry> entries) {
        Set<String> bwfTokens = nameTokens(t.name());
        EfEntry best = null;
        int bestScore = 0;
        for (EfEntry e : entries) {
            boolean dateOverlap = datesOverlap(t, e);
            int nameShare = sharedTokens(bwfTokens, nameTokens(e.name()));
            // Le nom equipe-france est en français (« Open du Canada ») et le nom
            // BWF en anglais : le recouvrement direct est faible, donc le
            // chevauchement de dates est le signal principal, le nom un bonus.
            int score = 0;
            if (dateOverlap) score += 2;
            score += nameShare;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        // On exige au minimum un chevauchement de dates OU 2 jetons communs.
        return bestScore >= 2 ? best : null;
    }

    /**
     * Confirme la correspondance en comparant le nom BWF à « l'identité » de la
     * page equipe-france (h1 + 1er paragraphe d'intro, qui contient l'alias
     * anglais du type « YONEX Canada Open » / « Macau Open »).
     */
    private static boolean confirmsMatch(Tournament t, Document page) {
        StringBuilder bag = new StringBuilder();
        Element h1 = page.selectFirst("h1");
        if (h1 != null) bag.append(' ').append(h1.text());
        Element intro = page.selectFirst("p.intro");
        if (intro != null) bag.append(' ').append(intro.text());
        Element meta = page.selectFirst("meta[name=description]");
        if (meta != null) bag.append(' ').append(meta.attr("content"));

        Set<String> bwfTokens = nameTokens(t.name());
        Set<String> pageTokens = nameTokens(bag.toString());
        return sharedTokens(bwfTokens, pageTokens) >= 1;
    }

    /**
     * Lit la phrase de participation des paragraphes {@code p.intro} et en déduit
     * present / title / note. Table de décision déterministe (pas de LLM).
     */
    private static FrenchStatus parseParticipation(Document page) {
        String sentence = "";
        for (Element p : page.select("p.intro")) {
            String txt = p.text().trim();
            String low = stripAccents(txt.toLowerCase(Locale.ROOT));
            if (low.contains("badiste") || low.contains("francais") || low.contains("aucun")
                    || low.contains("selectionn") || low.contains("engag")
                    || low.contains("participe") || low.contains("disponible")) {
                sentence = txt;
                break;
            }
        }
        if (sentence.isEmpty()) {
            Elements intros = page.select("p.intro");
            if (intros.size() >= 2) sentence = intros.get(1).text().trim();
        }

        String low = stripAccents(sentence.toLowerCase(Locale.ROOT));
        if (low.contains("aucun") && low.contains("franc")) {
            return new FrenchStatus(false, "Aucun Français engagé", sentence, false);
        }
        if (low.contains("pas disponible") || low.contains("non disponible")) {
            return new FrenchStatus(false, "Sélection française non publiée", sentence, true);
        }
        if (low.contains("engag") || low.contains("selectionn")
                || low.contains("participe") || low.contains("badiste")) {
            return new FrenchStatus(true, "Français engagés", sentence, false);
        }
        if (!sentence.isEmpty()) {
            return new FrenchStatus(false, "Statut français à confirmer", sentence, true);
        }
        return new FrenchStatus(false, "Statut français à confirmer",
                "Information de participation non trouvée sur equipe-france.fr.", true);
    }

    /** Chevauchement de plages (mois, jour), insensible à l'année (saison 2026). */
    private static boolean datesOverlap(Tournament t, EfEntry e) {
        if (e.start()[0] == 0 || e.end()[0] == 0) return false; // dates ef absentes
        int bs = ord(t.start().getMonthValue(), t.start().getDayOfMonth());
        int be = ord(t.end().getMonthValue(), t.end().getDayOfMonth());
        int es = ord(e.start()[0], e.start()[1]);
        int ee = ord(e.end()[0], e.end()[1]);
        return bs <= ee && es <= be;
    }

    private static int ord(int month, int day) {
        return month * 100 + day;
    }

    /** Jetons normalisés et significatifs d'un nom (accents et stopwords retirés). */
    private static Set<String> nameTokens(String name) {
        Set<String> tokens = new HashSet<>();
        for (String raw : stripAccents(name.toLowerCase(Locale.ROOT)).split("[^a-z0-9]+")) {
            if (raw.length() >= 2 && !NAME_STOPWORDS.contains(raw)) tokens.add(raw);
        }
        return tokens;
    }

    /** Nombre de jetons communs, en tolérant les variantes FR/EN par préfixe. */
    private static int sharedTokens(Set<String> a, Set<String> b) {
        int shared = 0;
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y) || (x.length() >= 5 && y.length() >= 5
                        && (x.startsWith(y.substring(0, 5)) || y.startsWith(x.substring(0, 5))))) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    private static String stripAccents(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
