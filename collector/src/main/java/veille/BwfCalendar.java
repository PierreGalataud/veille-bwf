package veille;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Calendrier BWF (corporate.bwfbadminton.com, WordPress rendu serveur, Jsoup OK —
 * cf. carte des sources CLAUDE.md). Filtre les lignes sur leur CATÉGORIE, mappe
 * celle-ci vers un tier ({@code wtf|1000|750|500|300|monde|europe}), et reconstruit
 * les dates à partir du mois de la section et de la plage de jours (gère le
 * chevauchement sur deux mois).
 *
 * <p>Le périmètre couvre les 5 niveaux du World Tour PLUS les deux grands
 * championnats individuels seniors — Championnats du monde ({@code monde}) et
 * Championnats d'Europe ({@code europe}). Cf. {@link #tierOf} pour la carte des
 * catégories acceptées et, surtout, des voisines à rejeter.
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
                if (tier == null) continue; // catégorie hors périmètre (cf. tierOf)

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

                // « Continental Individual Championships » vaut pour TOUS les
                // continents : on ne garde que l'européen (cf. isEuropean).
                if ("europe".equals(tier) && !isEuropean(name, country)) continue;

                // La dotation est dans la ligne détail (repérée par data-target).
                // Elle manque souvent hors World Tour (Mondiaux, Euros) → « — ».
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
            String formatted = formatPrize(btn.text());
            if (formatted != null) return formatted;
        }
        return "—";
    }

    /**
     * Met en forme la dotation portée par un bouton de la ligne détail, ou
     * {@code null} si ce bouton n'en porte pas (l'appelant retombe alors sur « — »).
     *
     * <p>Hors World Tour — Championnats du monde, Championnats d'Europe — la BWF
     * n'affiche AUCUNE dotation : le bouton peut manquer, ou ne contenir qu'un
     * tiret. Ce n'est pas une anomalie et ça ne doit rien casser.
     */
    static String formatPrize(String buttonText) {
        if (buttonText == null) return null;
        if (!buttonText.toUpperCase(Locale.ROOT).contains("PRIZE")) return null;
        Matcher m = Pattern.compile("([\\d][\\d.,]*)").matcher(buttonText);
        if (!m.find()) return null;               // « PRIZE MONEY - » → aucun montant
        String digits = m.group(1).replaceAll("[.,]", "");
        // Regroupe les milliers par espace insécable fine : « 500 000 $ ».
        // ATTENTION : la chaîne de remplacement est un U+202F littéral
        // (invisible à l'œil) — ne pas le « corriger » en espace simple.
        String grouped = digits.replaceAll("\\B(?=(\\d{3})+(?!\\d))", " ");
        return grouped + " $";
    }

    /**
     * Catégories NON World Tour acceptées, appariées sur le libellé EXACT (normalisé).
     * <p>VÉRIFIÉ sur la liste officielle des catégories du calendrier : les éditions
     * juniors, vétérans (« Senior ») et par équipes ne diffèrent QUE d'un mot —
     * « Grade 1 – Individual Junior Tournaments », « Grade 1 – Individual Senior
     * Tournaments », « Grade 1 – Junior Team Tournaments », « Continental Junior
     * Individual Championships », « Continental Junior Team Championships »,
     * « Continental Team Championships ». Un {@code contains()} les laisserait toutes
     * passer (le Mondial junior arriverait en tête d'affiche) : ici, et seulement ici,
     * l'appariement est EXACT.
     */
    private static final Map<String, String> EXACT_TIER = Map.of(
            "grade 1 - individual tournaments", "monde",        // Championnats du monde
            "continental individual championships", "europe");  // continentaux → filtrés ensuite

    /**
     * Mappe la catégorie BWF vers un tier, ou {@code null} si hors périmètre.
     * <p>Deux régimes, volontairement différents :
     * <ol>
     *   <li>World Tour : préfixe « HSBC BWF World Tour » + niveau. Souple (un
     *       changement de sponsor ne doit pas vider le tableau de bord) et sans
     *       risque de confusion — aucune catégorie junior ne porte ce préfixe, et
     *       « BWF Tour Super 100 » ne le porte pas non plus, donc reste exclu ;</li>
     *   <li>championnats individuels seniors : libellé EXACT ({@link #EXACT_TIER}),
     *       parce que les catégories voisines sont à un mot près.</li>
     * </ol>
     * Tout le reste (International Challenge / Series, Future Series, Super 100,
     * Multi-Sport Games, Junior *, … et les épreuves par équipes) → {@code null}.
     */
    static String tierOf(String category) {
        if (category == null) return null;
        String c = normalizeCategory(category);
        if (c.startsWith("hsbc bwf world tour")) {
            if (c.contains("super 1000")) return "1000";
            if (c.contains("super 750")) return "750";
            if (c.contains("super 500")) return "500";
            if (c.contains("super 300")) return "300";
            if (c.contains("finals")) return "wtf";
            return null; // « HSBC BWF World Tour » sans niveau → ignoré
        }
        return EXACT_TIER.get(c);
    }

    /**
     * Libellé de catégorie normalisé : minuscules, accents retirés, tirets typographiques
     * ramenés au tiret simple (« Grade 1 <b>–</b> Individual Tournaments » est écrit avec
     * un U+2013, invisible à l'œil) et espaces compactés.
     */
    static String normalizeCategory(String category) {
        return TextUtil.stripAccents(category.toLowerCase(Locale.ROOT))
                .replaceAll("[\u2010-\u2015\u2212]", "-")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Codes pays des fédérations membres de Badminton Europe — repli quand le NOM
     * ne porte pas « European ». Un championnat continental se dispute toujours
     * dans son continent : l'hôte suffit donc à trancher.
     */
    private static final Set<String> EUROPEAN_COUNTRIES = Set.of(
            "ALB", "AND", "ARM", "AUT", "AZE", "BEL", "BIH", "BLR", "BUL", "CRO",
            "CYP", "CZE", "DEN", "ENG", "ESP", "EST", "FIN", "FRA", "GEO", "GER",
            "GIB", "GRE", "GUE", "HUN", "IRL", "ISL", "ISR", "ITA", "JER", "KOS",
            "LAT", "LIE", "LTU", "LUX", "MDA", "MKD", "MLT", "MNE", "MON", "NED",
            "NOR", "POL", "POR", "ROU", "RUS", "SCO", "SMR", "SRB", "SUI", "SVK",
            "SWE", "SLO", "TUR", "UKR", "WAL");

    /**
     * Ce championnat continental est-il l'EUROPÉEN ? La catégorie « Continental
     * Individual Championships » couvre TOUS les continents (All Africa, Badminton
     * Asia, Pan Am, Oceania…) : sans ce filtre, le tableau de bord afficherait les
     * championnats d'Afrique. Deux signaux, l'un ou l'autre suffit : le mot
     * « European » / « Europe » dans le nom, ou un pays hôte européen.
     */
    static boolean isEuropean(String name, String country) {
        String n = TextUtil.norm(name == null ? "" : name);
        if (TextUtil.hasWord(n, "european") || TextUtil.hasWord(n, "europe")) return true;
        return country != null
                && EUROPEAN_COUNTRIES.contains(country.trim().toUpperCase(Locale.ROOT));
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
