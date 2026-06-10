package veille;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
            String name, String tier, String location, String prize,
            LocalDate start, LocalDate end) {}

    /**
     * Statut français d'un tournoi (alimente {@code frenchStatus}).
     *
     * {@code present} est un {@link Boolean} À TROIS ÉTATS, distincts par contrat
     * avec le front (cf. App.jsx) :
     * <ul>
     *   <li>{@code TRUE}  — page equipe-france appariée, des Français engagés ;</li>
     *   <li>{@code FALSE} — page appariée, mention explicite « aucun Français » ;</li>
     *   <li>{@code null}  — aucune page appariée (ou source indisponible) :
     *       statut INCONNU, à ne jamais confondre avec un « aucun » confirmé.</li>
     * </ul>
     */
    private record FrenchStatus(Boolean present, String title, String note, boolean confirm) {
        /** Aucune page appariée / source indisponible → statut inconnu (null). */
        static FrenchStatus unknown(String note) {
            return new FrenchStatus(null, "Statut français inconnu", note, true);
        }
        /** equipe-france totalement indisponible : statut inconnu par défaut. */
        static final FrenchStatus SOURCE_DOWN = unknown(
                "Suivi equipe-france momentanément indisponible — statut inconnu.");
    }

    /**
     * Une page tournoi equipe-france candidate. {@code start}/{@code end} =
     * {mois, jour} ({@code {0,0}} si dates inconnues, ex. lien glané sur l'accueil).
     */
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
            o.put("prize", t.prize());
            o.put("timezone", "—"); // absent du calendrier BWF (cf. parseTournaments)
            o.put("dayLabel", dayLabel(t.start(), t.end(), today));
            o.put("seeds", new ArrayList<>());
            FrenchStatus fs = frByName.getOrDefault(t.name(), FrenchStatus.SOURCE_DOWN);
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

    /** Pages equipe-france à ignorer (joueurs, classements, pages utilitaires). */
    private static final Set<String> SKIP_SLUGS = new HashSet<>(List.of(
            "calendrier", "amp", "classement-des-joueurs-feminin",
            "classement-des-joueurs-masculin", "feminin", "masculin",
            "jeux-olympiques-d-ete"));
    /** Borne de politesse : nb max de pages tournoi sondées par tournoi BWF
     *  (couvre le saut page pérenne → édition datée). */
    private static final int MAX_EF_TRIES = 5;

    /**
     * Pour chaque tournoi en cours, retrouve sa page equipe-france par
     * réconciliation (dates d'abord, nom/lieu en appoint) et en lit la phrase de
     * participation. Trois issues distinctes (cf. {@link FrenchStatus}) :
     * present TRUE / FALSE / null. « Pas trouvé » (null) ≠ « trouvé, aucun »
     * (false). Source secondaire : tout échec retombe sur un statut null
     * (inconnu) sans empêcher l'écriture du data.json — on ne blanchit rien.
     *
     * @return statut indexé par {@code Tournament.name()} (clé identique au JSON).
     */
    private static Map<String, FrenchStatus> buildFrenchStatus(List<Tournament> current) {
        Map<String, FrenchStatus> result = new HashMap<>();
        if (current.isEmpty()) return result;

        // Le calendrier equipe-france ne liste QUE les tournois à venir ; un
        // tournoi déjà en cours (ex. l'Open d'Australie) n'y est pas mais sa page
        // existe et est liée depuis l'accueil. On agrège donc les deux index.
        List<EfEntry> candidates = harvestCandidates();
        if (candidates.isEmpty()) {
            System.err.println("equipe-france indisponible — statut français inconnu.");
            return result; // tous les tournois retombent sur SOURCE_DOWN (null)
        }

        Map<String, Document> cache = new HashMap<>();
        for (Tournament t : current) {
            try {
                result.put(t.name(), resolveFrenchStatus(t, candidates, cache));
            } catch (Exception e) {
                System.err.println("equipe-france KO pour « " + t.name() + " » : " + e);
                result.put(t.name(), FrenchStatus.unknown(
                        "Statut inconnu : erreur de lecture equipe-france."));
            }
        }
        return result;
    }

    /**
     * Apparie un tournoi BWF à une page equipe-france et en déduit le statut.
     * Classe les candidats (dates = signal fort, nom/lieu = appoint), sonde les
     * meilleurs, et NE retient qu'une page CONFIRMÉE par ses propres dates +
     * l'alias anglais. Aucune confirmation → present:null (jamais false).
     *
     * Deux sauts : l'accueil ne lie souvent que la page « pérenne » sans année
     * (ex. /open-d-australie, sans dates) ; on suit alors son lien vers l'édition
     * datée de l'année visée (/open-d-australie-2026) pour confirmer.
     */
    private static FrenchStatus resolveFrenchStatus(
            Tournament t, List<EfEntry> candidates, Map<String, Document> cache) throws Exception {
        Set<String> bwfTokens = nameTokens(t.name() + " " + t.location());
        int year = t.start().getYear();

        List<EfEntry> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Integer.compare(score(t, bwfTokens, b), score(t, bwfTokens, a)));

        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EfEntry e : ranked) {
            if (score(t, bwfTokens, e) <= 0) break; // plus aucun signal de nom/date
            String u = efUrl(e.url());
            if (seen.add(u)) queue.add(u);
        }

        int tried = 0;
        while (!queue.isEmpty() && tried < MAX_EF_TRIES) {
            Document page = getPage(queue.poll(), cache);
            tried++;
            if (page == null) continue;

            // Confirmation : les DATES de la page font foi (chevauchement avec le
            // tournoi BWF) ET l'alias anglais (h1 + intro + meta) partage un jeton.
            boolean dateOk = datesOverlapRange(t, parsePageDates(page));
            int aliasShare = sharedTokens(bwfTokens, pageIdentityTokens(page));
            if (dateOk && aliasShare >= 1) {
                return parseParticipation(page);
            }
            // Page pérenne sans dates : suivre son édition datée de l'année visée.
            for (String edition : editionLinks(page, year, bwfTokens)) {
                if (seen.add(edition)) queue.addFirst(edition); // prioritaire
            }
        }
        return FrenchStatus.unknown(
                "Statut inconnu : aucune page equipe-france appariée (réconciliation à affiner).");
    }

    /** Liens d'édition datée (/badminton/<stem>-YYYY) liés depuis une page pérenne. */
    private static List<String> editionLinks(Document page, int year, Set<String> bwfTokens) {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("^/badminton/([a-z0-9-]+)-" + year + "$");
        for (Element a : page.select("a[href]")) {
            String href = a.attr("href").replaceAll("[?#].*$", "");
            Matcher m = p.matcher(href);
            if (!m.matches()) continue;
            // On ne suit que les éditions dont le nom recoupe le tournoi BWF.
            if (sharedTokens(bwfTokens, nameTokens(m.group(1))) >= 1) {
                out.add(EF_BASE + href);
            }
        }
        return out;
    }

    /** Score d'appariement : dates dominantes, similarité nom/lieu en appoint. */
    private static int score(Tournament t, Set<String> bwfTokens, EfEntry e) {
        int s = 10 * sharedTokens(bwfTokens, nameTokens(e.name()));
        if (datesOverlap(t, e)) s += 100; // chevauchement de dates = signal fort
        return s;
    }

    /** Agrège les pages tournoi candidates : calendrier (daté) + accueil. */
    private static List<EfEntry> harvestCandidates() {
        Map<String, EfEntry> byPath = new LinkedHashMap<>();
        try { // calendrier : entrées datées (prioritaires en cas de doublon)
            for (EfEntry e : parseEfCalendar(fetch(EF_CAL_URL))) {
                byPath.putIfAbsent(pathOf(e.url()), e);
            }
        } catch (Exception ex) {
            System.err.println("equipe-france (calendrier) KO : " + ex);
        }
        try { // accueil : liens tournoi non datés (complète les tournois en cours)
            for (EfEntry e : parseHomeLinks(fetch(EF_BASE + "/badminton"))) {
                byPath.putIfAbsent(pathOf(e.url()), e);
            }
        } catch (Exception ex) {
            System.err.println("equipe-france (accueil) KO : " + ex);
        }
        return new ArrayList<>(byPath.values());
    }

    private static Document fetch(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0)
                .get();
    }

    /** Récupère une page (avec cache et pause de politesse). null si échec. */
    private static Document getPage(String url, Map<String, Document> cache) throws InterruptedException {
        if (cache.containsKey(url)) return cache.get(url);
        Thread.sleep(EF_THROTTLE_MS);
        Document d = null;
        try {
            d = fetch(url);
        } catch (Exception e) {
            System.err.println("equipe-france page KO (" + url + ") : " + e);
        }
        cache.put(url, d);
        return d;
    }

    private static String efUrl(String url) {
        return url.startsWith("http") ? url : EF_BASE + url;
    }

    private static String pathOf(String url) {
        String u = url.replaceFirst("^https?://[^/]+", "");
        return u.replaceAll("[?#].*$", "").replaceAll("/+$", "");
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

    /** Glane les liens vers des pages tournoi sur l'accueil badminton (non datés). */
    private static List<EfEntry> parseHomeLinks(Document home) {
        Map<String, EfEntry> byPath = new LinkedHashMap<>();
        for (Element a : home.select("a[href]")) {
            String href = a.attr("href").trim();
            if (!href.startsWith("/badminton/")) continue;
            String slug = href.substring("/badminton/".length()).replaceAll("[/?#].*$", "");
            if (slug.isEmpty() || SKIP_SLUGS.contains(slug)) continue;
            // Le nom dérive du slug ; les dates restent inconnues ({0,0}).
            String name = slug.replace('-', ' ');
            byPath.putIfAbsent("/badminton/" + slug,
                    new EfEntry(name, "/badminton/" + slug, new int[]{0, 0}, new int[]{0, 0}));
        }
        return new ArrayList<>(byPath.values());
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

    /** Dates de la page tournoi, lues dans le 1er paragraphe d'intro (« du … au … »). */
    private static int[][] parsePageDates(Document page) {
        Element intro = page.selectFirst("p.intro");
        return intro == null ? new int[][]{{0, 0}, {0, 0}} : parseEfDates(intro.text());
    }

    /**
     * « Identité » de la page tournoi : h1 + 1er intro + meta description, qui
     * contiennent l'alias anglais (« Australian Badminton Open », « Macau Open »,
     * « YONEX Canada Open »…) — fiable pour confirmer face au nom BWF anglais.
     */
    private static Set<String> pageIdentityTokens(Document page) {
        StringBuilder bag = new StringBuilder();
        Element h1 = page.selectFirst("h1");
        if (h1 != null) bag.append(' ').append(h1.text());
        Element intro = page.selectFirst("p.intro");
        if (intro != null) bag.append(' ').append(intro.text());
        Element meta = page.selectFirst("meta[name=description]");
        if (meta != null) bag.append(' ').append(meta.attr("content"));
        return nameTokens(bag.toString());
    }

    /**
     * Lit la phrase de participation des paragraphes {@code p.intro} et en déduit
     * present / title / note. Table de décision déterministe (pas de LLM).
     *
     * La page étant appariée, on ne renvoie que TRUE (engagés) ou FALSE (mention
     * explicite « aucun Français »). Tout cas ambigu (sélection non publiée,
     * phrase non reconnue) → null : on a la page mais on ne PEUT PAS affirmer
     * l'absence — à ne jamais transformer en « aucun » confirmé.
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
        // « aucun » d'abord : « Aucun français ne participe » contient « participe ».
        if (low.contains("aucun") && low.contains("franc")) {
            return new FrenchStatus(false, "Aucun Français engagé",
                    "equipe-france : " + sentence, false);
        }
        // Sélection non publiée : page trouvée mais présence indéterminée → null.
        if (low.contains("pas disponible") || low.contains("non disponible")) {
            return FrenchStatus.unknown("equipe-france : " + sentence);
        }
        if (low.contains("engag") || low.contains("selectionn") || low.contains("badiste")) {
            return new FrenchStatus(true, "Français engagés",
                    "equipe-france : " + sentence, false);
        }
        // Page trouvée mais phrase non reconnue → inconnu (jamais false).
        return FrenchStatus.unknown(sentence.isEmpty()
                ? "Page equipe-france trouvée mais participation non lisible."
                : "equipe-france : " + sentence);
    }

    /** Chevauchement de la plage BWF avec une plage equipe-france {{m,d},{m,d}}. */
    private static boolean datesOverlap(Tournament t, EfEntry e) {
        return datesOverlapRange(t, new int[][]{e.start(), e.end()});
    }

    private static boolean datesOverlapRange(Tournament t, int[][] range) {
        if (range[0][0] == 0 || range[1][0] == 0) return false; // dates absentes
        int bs = ord(t.start().getMonthValue(), t.start().getDayOfMonth());
        int be = ord(t.end().getMonthValue(), t.end().getDayOfMonth());
        int es = ord(range[0][0], range[0][1]);
        int ee = ord(range[1][0], range[1][1]);
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
