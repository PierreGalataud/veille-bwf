package veille;

import java.time.LocalDate;
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

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Source equipe-france.fr (rendu serveur, FR-centré, Jsoup OK — cf. carte des
 * sources CLAUDE.md) : statut français des tournois ({@code frenchStatus}), fil
 * d'actualités des Bleus et classement mondial.
 *
 * Source SECONDAIRE d'enrichissement : toute indisponibilité dégrade en statut
 * « inconnu » / liste vide SANS bloquer l'écriture du data.json (échec gracieux,
 * on ne blanchit rien).
 */
final class EquipeFrance {

    private EquipeFrance() {}

    static final String EF_BASE = "https://www.equipe-france.fr";
    private static final String EF_CAL_URL = EF_BASE + "/badminton/calendrier";
    /** Page de classement (simple messieurs) : source des {@code rank}. */
    private static final String EF_RANK_M =
            EF_BASE + "/badminton/classement-des-joueurs-masculin";
    /** Pause entre deux requêtes equipe-france (politesse, cf. garde-fous). */
    private static final long EF_THROTTLE_MS = 800;
    /** Pages equipe-france à ignorer (joueurs, classements, pages utilitaires). */
    private static final Set<String> SKIP_SLUGS = new HashSet<>(List.of(
            "calendrier", "amp", "classement-des-joueurs-feminin",
            "classement-des-joueurs-masculin", "feminin", "masculin",
            "jeux-olympiques-d-ete"));
    /** Borne de politesse : nb max de pages tournoi sondées par tournoi BWF
     *  (couvre le saut page pérenne → édition datée). */
    private static final int MAX_EF_TRIES = 5;

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
    record FrenchStatus(Boolean present, String title, String note, boolean confirm) {
        /** Aucune page appariée / source indisponible → statut inconnu (null). */
        static FrenchStatus unknown(String note) {
            return new FrenchStatus(null, "Statut français inconnu", note, true);
        }
        /** equipe-france totalement indisponible : statut inconnu par défaut. */
        static final FrenchStatus SOURCE_DOWN = unknown(
                "Suivi equipe-france momentanément indisponible — statut inconnu.");
    }

    /**
     * Une page tournoi equipe-france candidate. {@code dates} =
     * {@link EfDateRange#UNKNOWN} si inconnues (ex. lien glané sur l'accueil).
     */
    private record EfEntry(String name, String url, EfDateRange dates) {}

    // ------------------------------------------------------------
    //  Accès réseau : throttle commun à TOUTES les requêtes equipe-france
    // ------------------------------------------------------------

    /** Horodatage de la dernière requête equipe-france (throttle commun). */
    private static long lastEfFetchMs = 0;

    /**
     * Point de passage UNIQUE des requêtes equipe-france : applique la pause de
     * politesse entre deux requêtes au même site (cf. garde-fous), où que l'appel
     * soit fait dans le pipeline. Pas d'attente avant la toute première requête.
     */
    private static Document fetchEf(String url) throws Exception {
        long wait = EF_THROTTLE_MS - (System.currentTimeMillis() - lastEfFetchMs);
        if (wait > 0) Thread.sleep(wait);
        try {
            return Http.fetch(url);
        } finally {
            lastEfFetchMs = System.currentTimeMillis();
        }
    }

    /** Récupère une page equipe-france (avec cache). null si échec. */
    private static Document getPage(String url, Map<String, Document> cache) {
        if (cache.containsKey(url)) return cache.get(url);
        Document d = null;
        try {
            d = fetchEf(url);
        } catch (Exception e) {
            System.err.println("equipe-france page KO (" + url + ") : " + e);
        }
        cache.put(url, d);
        return d;
    }

    // ------------------------------------------------------------
    //  Statut français des tournois en cours (frenchStatus)
    // ------------------------------------------------------------

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
    static Map<String, FrenchStatus> buildFrenchStatus(List<Tournament> current) {
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
        Set<String> bwfTokens = TextUtil.nameTokens(t.name() + " " + t.location());
        int year = t.start().getYear();

        // Scores précalculés (les recalculer à chaque comparaison du tri serait
        // quadratique pour rien).
        Map<EfEntry, Integer> scores = new HashMap<>();
        for (EfEntry e : candidates) scores.put(e, score(t, bwfTokens, e));

        List<EfEntry> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Integer.compare(scores.get(b), scores.get(a)));

        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EfEntry e : ranked) {
            if (scores.get(e) <= 0) break; // plus aucun signal de nom/date
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
            int aliasShare = TextUtil.sharedTokens(bwfTokens, pageIdentityTokens(page));
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
            if (TextUtil.sharedTokens(bwfTokens, TextUtil.nameTokens(m.group(1))) >= 1) {
                out.add(EF_BASE + href);
            }
        }
        return out;
    }

    /** Score d'appariement : dates dominantes, similarité nom/lieu en appoint. */
    private static int score(Tournament t, Set<String> bwfTokens, EfEntry e) {
        int s = 10 * TextUtil.sharedTokens(bwfTokens, TextUtil.nameTokens(e.name()));
        if (datesOverlapRange(t, e.dates())) s += 100; // chevauchement de dates = signal fort
        return s;
    }

    /** Agrège les pages tournoi candidates : calendrier (daté) + accueil. */
    private static List<EfEntry> harvestCandidates() {
        Map<String, EfEntry> byPath = new LinkedHashMap<>();
        try { // calendrier : entrées datées (prioritaires en cas de doublon)
            for (EfEntry e : parseEfCalendar(fetchEf(EF_CAL_URL))) {
                byPath.putIfAbsent(pathOf(e.url()), e);
            }
        } catch (Exception ex) {
            System.err.println("equipe-france (calendrier) KO : " + ex);
        }
        try { // accueil : liens tournoi non datés (complète les tournois en cours)
            for (EfEntry e : parseHomeLinks(fetchEf(EF_BASE + "/badminton"))) {
                byPath.putIfAbsent(pathOf(e.url()), e);
            }
        } catch (Exception ex) {
            System.err.println("equipe-france (accueil) KO : " + ex);
        }
        return new ArrayList<>(byPath.values());
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
                if (FrDates.FR_MONTH_NUM.keySet().stream().anyMatch(txt::contains)) {
                    dateText = td.text();
                    break;
                }
            }
            out.add(new EfEntry(name, url, parseEfDates(dateText)));
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
            // Le nom dérive du slug ; les dates restent inconnues.
            String name = slug.replace('-', ' ');
            byPath.putIfAbsent("/badminton/" + slug,
                    new EfEntry(name, "/badminton/" + slug, EfDateRange.UNKNOWN));
        }
        return new ArrayList<>(byPath.values());
    }

    /**
     * Analyse une cellule de dates equipe-france (« 30 juin – 5 juillet », ou
     * « 16 – 21 juin », parfois avec un libellé responsive dupliqué).
     */
    static EfDateRange parseEfDates(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        // mois (valeur + position)
        List<int[]> months = new ArrayList<>(); // {monthNum, position}
        Matcher mm = Pattern.compile(
                "janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre")
                .matcher(low);
        while (mm.find()) months.add(new int[]{FrDates.FR_MONTH_NUM.get(mm.group()), mm.start()});
        // jours (valeur + position)
        List<int[]> days = new ArrayList<>(); // {day, position}
        Matcher dm = Pattern.compile("\\b(\\d{1,2})\\b").matcher(low);
        while (dm.find()) days.add(new int[]{Integer.parseInt(dm.group(1)), dm.start()});

        if (months.isEmpty() || days.isEmpty()) return EfDateRange.UNKNOWN;

        int startMonth = months.get(0)[0];
        int endMonth = months.get(months.size() - 1)[0];
        int startDay = days.get(0)[0];
        // jour de fin = dernier nombre situé avant le dernier mois cité
        int lastMonthPos = months.get(months.size() - 1)[1];
        int endDay = startDay;
        for (int[] d : days) if (d[1] < lastMonthPos) endDay = d[0];

        return new EfDateRange(startMonth, startDay, endMonth, endDay);
    }

    /** Dates de la page tournoi, lues dans le 1er paragraphe d'intro (« du … au … »). */
    private static EfDateRange parsePageDates(Document page) {
        Element intro = page.selectFirst("p.intro");
        return intro == null ? EfDateRange.UNKNOWN : parseEfDates(intro.text());
    }

    /**
     * La plage equipe-france est sans année : on l'ancre sur l'année du tournoi
     * BWF en choisissant l'interprétation la plus proche (à ± 6 mois), puis on
     * déroule l'enroulement déc. → janv. (mois de fin < mois de début). Comparer
     * de vraies dates évite le bug du passage d'année (« 30 déc. – 4 janv. »
     * ne chevauchait jamais rien avec l'ancienne arithmétique mois*100+jour).
     */
    static boolean datesOverlapRange(Tournament t, EfDateRange r) {
        if (!r.known()) return false; // dates absentes
        LocalDate es, ee;
        try {
            es = LocalDate.of(t.start().getYear(), r.startMonth(), r.startDay());
            if (es.isBefore(t.start().minusMonths(6))) es = es.plusYears(1);
            else if (es.isAfter(t.start().plusMonths(6))) es = es.minusYears(1);
            int endYear = es.getYear() + (r.endMonth() < r.startMonth() ? 1 : 0);
            ee = LocalDate.of(endYear, r.endMonth(), r.endDay());
        } catch (Exception ex) {
            return false; // jour/mois invalide dans la source
        }
        return !es.isAfter(t.end()) && !t.start().isAfter(ee);
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
        return TextUtil.nameTokens(bag.toString());
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
            String low = TextUtil.stripAccents(txt.toLowerCase(Locale.ROOT));
            if (low.contains("badiste") || low.contains("francais") || low.contains("aucun")
                    || low.contains("selectionn") || low.contains("engag")
                    || low.contains("participe") || low.contains("disponible")) {
                sentence = txt;
                break;
            }
        }
        // Pas de repli sur un paragraphe arbitraire : sans phrase de participation
        // reconnue, on tombera sur « inconnu » plus bas. Classer un paragraphe pris
        // au hasard risquerait un present:true erroné pour un mot croisé par hasard.

        String low = TextUtil.stripAccents(sentence.toLowerCase(Locale.ROOT));
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

    // ------------------------------------------------------------
    //  Fil d'actualités et classement (alimentent players[])
    // ------------------------------------------------------------

    /** Fil d'actualités de l'accueil badminton (lève si source indisponible). */
    static List<FeedItem> fetchFeed() throws Exception {
        return parseFeed(fetchEf(EF_BASE + "/badminton"));
    }

    /** Découpe le fil d'actualités (liste de {@code li[id^=article_]}). */
    private static List<FeedItem> parseFeed(Document home) {
        List<FeedItem> out = new ArrayList<>();
        for (Element li : home.select("li[id^=article_]")) {
            Element titleEl = li.selectFirst("div.title");
            if (titleEl == null) continue;
            String title = titleEl.text().trim();
            if (title.isEmpty()) continue;
            Element catEl = li.selectFirst("div.cat .catspan");
            Element dateEl = li.selectFirst("div.date");
            Element a = li.selectFirst("a[href]");
            String tournoi = catEl != null ? catEl.text().trim() : "";
            String date = dateEl != null ? dateEl.text().trim() : "";
            String href = a != null ? a.attr("href").trim() : "";
            out.add(new FeedItem(date, tournoi, title, href));
        }
        return out;
    }

    /**
     * Lit la page de classement simple messieurs d'equipe-france et renvoie
     * {@code slug -> "#N mondial"}. Échec gracieux : indisponibilité → map vide
     * (les rank concernés restent {@code null}, on ne devine pas).
     */
    static Map<String, String> buildRanks() {
        Map<String, String> ranks = new HashMap<>();
        try {
            Document doc = fetchEf(EF_RANK_M);
            for (Element tr : doc.select("table tbody tr")) {
                Element a = tr.selectFirst("th[scope=row] a[href]");
                Element num = tr.selectFirst("td strong");
                if (a == null || num == null) continue;
                String slug = pathOf(a.attr("href")).replaceAll("^.*/", "");
                String n = num.text().replaceAll("\\D", "");
                if (slug.isEmpty() || n.isEmpty()) continue;
                ranks.putIfAbsent(slug, "#" + n + " mondial");
            }
        } catch (Exception e) {
            System.err.println("equipe-france (classement) KO — rank null : " + e);
        }
        return ranks;
    }
}
