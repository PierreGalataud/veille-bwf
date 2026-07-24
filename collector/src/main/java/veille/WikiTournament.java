package veille;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source A (CLAUDE.md) — statut français d'un tournoi, lu DÉTERMINISTIQUEMENT
 * dans le tableau (draw) de sa page Wikipédia. Aucun LLM pour lire le draw : les
 * tableaux annotent chaque tête de série entre parenthèses (« ''(champion)'' »,
 * « ''(second round)'' »…), ce qui suffit à dire jusqu'où un Français est allé.
 *
 * APPARIEMENT DE L'ARTICLE (durci) : un titre n'est retenu qu'après VÉRIFICATION
 * de son contenu, pas de son seul titre (« 2026 Australian Open » tennis passerait
 * le filtre titre). {@link #matchesTournament} exige que l'infobox confirme le
 * NIVEAU (G2L… -> tier) ET les DATES (chevauchement ± 1 jour). L'ordre :
 * <ol>
 *   <li>{@link Aliases} : appariement déjà mémorisé -> wikitexte direct, zéro recherche ;</li>
 *   <li>recherche + vérification déterministe du 1er candidat conforme ;</li>
 *   <li>dernier recours : Haiku choisit parmi les candidats ({@link LlmNet#pickArticle}),
 *       puis on REVALIDE par {@link #matchesTournament} avant d'accepter.</li>
 * </ol>
 * Tout appariement accepté est écrit dans {@link Aliases} : plus jamais de recherche
 * ni de Haiku sur ce tournoi. Aucun candidat vérifié -> {@code present = null}.
 *
 * Trois états, jamais confondus (contrat {@code frenchStatus.present}) : true (un
 * Français au tableau) / false (tableau publié, aucun) / null (article non vérifié
 * ou tableau non publié — INCONNU).
 *
 * Fonctions pures ({@link #searchQuery}, {@link #shortlist}, {@link #matchesTournament},
 * {@link #parseLevel}, {@link #parseInfoboxDates}, {@link #parseFrenchStatus},
 * {@link #stageFr}) testées ; seul {@link #resolve} fait du réseau (via {@link Wiki}).
 */
final class WikiTournament {

    private WikiTournament() {}

    /** Jetons de présence française à repérer dans les liens du tableau. « Popov »
     *  couvre les deux frères — le statut du tournoi est « des Français en lice ». */
    private static final String[] FR_ALIASES = {"lanier", "popov"};
    /** Sponsors et mots à retirer du nom pour bâtir une requête de recherche propre. */
    private static final Set<String> QUERY_NOISE = Set.of(
            "hsbc", "bwf", "world", "tour", "yonex", "victor", "daihatsu",
            "sands", "ltd", "co", "petronas", "perodua", "toyota", "kapal", "api",
            "sathio", "group", "li", "ning", "lining", "powered", "by", "presented");
    /** Code de niveau BWF de l'infobox Wikipédia (G2L<n>) → tier du contrat. */
    private static final Map<String, String> LEVEL_TIER = Map.of(
            "1", "wtf", "2", "1000", "3", "750", "4", "500", "5", "300");
    /** Libellé humain d'un tier, pour le prompt d'appariement Haiku. */
    private static final Map<String, String> TIER_LABEL = Map.of(
            "wtf", "World Tour Finals", "1000", "Super 1000", "750", "Super 750",
            "500", "Super 500", "300", "Super 300");
    /** Mois anglais (infobox Wikipédia) → numéro. */
    private static final Map<String, Integer> EN_MONTH = new java.util.HashMap<>();
    static {
        String[] en = {"january", "february", "march", "april", "may", "june", "july",
                "august", "september", "october", "november", "december"};
        for (int i = 0; i < en.length; i++) EN_MONTH.put(en[i], i + 1);
    }

    /** Statut français d'un tournoi (mappé en {@code DataJson.FrenchStatusJson}). */
    record FrenchStatus(Boolean present, String title, String note, boolean confirm) {
        static FrenchStatus unknown(String note) {
            return new FrenchStatus(null, "Statut français inconnu", note, true);
        }
    }

    // ------------------------------------------------------------
    //  Orchestration (réseau)
    // ------------------------------------------------------------

    /**
     * Résout le statut français d'un tournoi : trouve et VÉRIFIE l'article, lit son
     * wikitexte, en extrait la présence française. Échec gracieux : toute
     * indisponibilité ou aucun article vérifié → statut {@code null} (inconnu),
     * jamais un « aucun » inventé.
     */
    static FrenchStatus resolve(Tournament t) {
        try {
            String wikitext = articleWikitext(t);
            if (wikitext == null) {
                return FrenchStatus.unknown(
                        "Aucun article Wikipédia vérifié (dates + niveau) pour « " + t.name() + " ».");
            }
            return parseFrenchStatus(wikitext);
        } catch (Exception e) {
            System.err.println("Wikipédia (statut « " + t.name() + " ») KO : " + e);
            return FrenchStatus.unknown("Wikipédia momentanément indisponible — statut inconnu.");
        }
    }

    /**
     * Renvoie le wikitexte de l'article VÉRIFIÉ pour ce tournoi (et mémorise
     * l'appariement), ou {@code null}. Mémoire d'abord (zéro recherche), puis
     * vérification déterministe, puis filet Haiku revalidé.
     */
    private static String articleWikitext(Tournament t) throws Exception {
        int year = t.start().getYear();

        // 1) Mémoire d'appariement : entrée présente → wikitexte direct.
        String remembered = Aliases.get(t.name());
        if (remembered != null) {
            System.out.println("appariement mémorisé : « " + t.name() + " » → " + remembered);
            return Wiki.wikitext(remembered);
        }

        // 2) Recherche + vérification déterministe (dates + niveau via l'infobox).
        List<String> candidates = Wiki.search(searchQuery(t.name(), year));
        Set<String> tokens = TextUtil.nameTokens(t.name() + " " + t.location());
        for (String title : shortlist(candidates, tokens, year)) {
            String wt = Wiki.wikitext(title);
            if (matchesTournament(wt, t.start(), t.end(), t.tier())) {
                accept(t.name(), title);
                return wt;
            }
        }

        // 3) Dernier recours : Haiku choisit parmi les candidats, PUIS on revalide.
        String picked = LlmNet.pickArticle(t.name(),
                FrDates.dateRange(t.start(), t.end(), true),
                TIER_LABEL.getOrDefault(t.tier(), t.tier()), candidates);
        if (picked != null) {
            String wt = Wiki.wikitext(picked);
            if (matchesTournament(wt, t.start(), t.end(), t.tier())) {
                accept(t.name(), picked);
                return wt;
            }
            System.err.println("filet Haiku appariement : « " + picked
                    + " » proposé mais recalé à la revalidation — ignoré.");
        }
        return null;
    }

    private static void accept(String bwfName, String title) {
        System.out.println("appariement vérifié : « " + bwfName + " » → " + title);
        Aliases.put(bwfName, title);
    }

    // ------------------------------------------------------------
    //  Fonctions pures — recherche & vérification
    // ------------------------------------------------------------

    /**
     * Requête de recherche : {@code <année> <nom nettoyé> badminton}. On retire
     * l'année du nom, les sponsors et les mots génériques (le suffixe d'article
     * « (badminton) » étant irrégulier, on cherche au lieu de deviner l'URL).
     */
    static String searchQuery(String name, int year) {
        StringBuilder core = new StringBuilder();
        for (String w : TextUtil.stripAccents(name.toLowerCase(Locale.ROOT)).split("[^a-z0-9]+")) {
            if (w.isBlank() || w.matches("\\d{4}") || QUERY_NOISE.contains(w)) continue;
            core.append(w).append(' ');
        }
        return year + " " + core.toString().trim() + " badminton";
    }

    /**
     * Présélectionne, DANS L'ORDRE DE PERTINENCE, les candidats plausibles : titre
     * commençant par l'année visée ET partageant un jeton de nom. C'est un simple
     * pré-filtre — chaque candidat sera ensuite VÉRIFIÉ par {@link #matchesTournament}
     * (le titre ne suffit pas : « 2026 Australian Open » tennis passerait ici).
     */
    static List<String> shortlist(List<String> titles, Set<String> bwfTokens, int year) {
        String prefix = year + " ";
        List<String> out = new ArrayList<>();
        for (String tt : titles) {
            if (tt.startsWith(prefix) && TextUtil.sharedTokens(bwfTokens, TextUtil.nameTokens(tt)) > 0) {
                out.add(tt);
            }
        }
        return out;
    }

    /**
     * Un article Wikipédia correspond-il VRAIMENT à ce tournoi BWF ? On exige DEUX
     * signaux concordants dans l'infobox, pas le titre :
     * <ul>
     *   <li>NIVEAU : le code {@code level = G2L<n>} (ou un libellé « Super … » /
     *       « Finals ») mappe sur le {@code tier} attendu ;</li>
     *   <li>DATES : la plage {@code dates = …} (ancrée sur l'année BWF) chevauche
     *       les dates BWF à ± 1 jour près.</li>
     * </ul>
     * Un seul signal manquant ou contradictoire → {@code false} (rejet). C'est ce
     * qui recale un article de tennis homonyme (pas de niveau badminton, dates hors
     * plage). Anti-régression testé sur « 2026 Australian Open » (tennis).
     */
    static boolean matchesTournament(String wikitext, LocalDate bwfStart, LocalDate bwfEnd,
                                     String tier) {
        if (wikitext == null) return false;
        String level = parseLevel(wikitext);
        if (level == null || !level.equals(tier)) return false;      // niveau absent ou contredit
        LocalDate[] dates = parseInfoboxDates(wikitext, bwfStart.getYear());
        if (dates == null) return false;                             // dates absentes
        return !dates[0].isAfter(bwfEnd.plusDays(1))
                && !dates[1].isBefore(bwfStart.minusDays(1));         // chevauchement ± 1 jour
    }

    /**
     * Tier lu dans l'infobox : code {@code | level = G2L<n>} (n=1..5 → wtf..300),
     * avec repli sur un libellé textuel (« Super 750 », « World Tour Finals »).
     * {@code null} si aucun niveau badminton World Tour n'est reconnu — c'est le
     * cas d'un article non-badminton (tennis…), qui sera rejeté.
     */
    static String parseLevel(String wikitext) {
        if (wikitext == null) return null;
        Matcher m = Pattern.compile("(?im)^\\s*\\|\\s*level\\s*=\\s*([^\\n]*)").matcher(wikitext);
        if (!m.find()) return null;
        String v = m.group(1).toUpperCase(Locale.ROOT);
        Matcher g = Pattern.compile("G2L(\\d)").matcher(v);
        if (g.find()) return LEVEL_TIER.get(g.group(1));             // peut être null si G2L6 (S100)
        if (v.contains("FINALS")) return "wtf";
        if (v.contains("SUPER 1000")) return "1000";
        if (v.contains("SUPER 750")) return "750";
        if (v.contains("SUPER 500")) return "500";
        if (v.contains("SUPER 300")) return "300";
        return null;
    }

    /**
     * Dates lues dans l'infobox ({@code | dates = 9–14 June}, « 30 June – 5 July »).
     * Sans année dans le champ : on l'ancre sur {@code year} (l'article est daté).
     * Enroulement déc.→janv. géré (mois de fin &lt; mois de début → année+1).
     * {@code [start, end]} ou {@code null} si illisible (dont le champ tennis
     * {@code | date =}, distinct de {@code dates}).
     */
    static LocalDate[] parseInfoboxDates(String wikitext, int year) {
        if (wikitext == null) return null;
        Matcher m = Pattern.compile("(?im)^\\s*\\|\\s*dates\\s*=\\s*([^\\n]*)").matcher(wikitext);
        if (!m.find()) return null;
        String low = TextUtil.stripAccents(m.group(1).toLowerCase(Locale.ROOT));

        List<int[]> months = new ArrayList<>();   // {monthNum, position}
        Matcher mm = Pattern.compile(
                "january|february|march|april|may|june|july|august|september|october|november|december")
                .matcher(low);
        while (mm.find()) months.add(new int[]{EN_MONTH.get(mm.group()), mm.start()});
        List<int[]> days = new ArrayList<>();      // {day, position}
        Matcher dm = Pattern.compile("\\b(\\d{1,2})\\b").matcher(low);
        while (dm.find()) days.add(new int[]{Integer.parseInt(dm.group(1)), dm.start()});
        if (months.isEmpty() || days.isEmpty()) return null;

        int startMonth = months.get(0)[0];
        int endMonth = months.get(months.size() - 1)[0];
        int startDay = days.get(0)[0];
        int lastMonthPos = months.get(months.size() - 1)[1];
        int endDay = startDay;
        for (int[] d : days) if (d[1] < lastMonthPos) endDay = d[0];  // dernier jour avant le dernier mois
        try {
            LocalDate start = LocalDate.of(year, startMonth, startDay);
            int endYear = year + (endMonth < startMonth ? 1 : 0);
            LocalDate end = LocalDate.of(endYear, endMonth, endDay);
            return new LocalDate[]{start, end};
        } catch (Exception e) {
            return null;                                              // jour/mois invalide
        }
    }

    // ------------------------------------------------------------
    //  Fonctions pures — lecture du draw
    // ------------------------------------------------------------

    /** Lien Wikipédia [[cible(|affichage)]] éventuellement suivi de « ''(résultat)'' ». */
    private static final Pattern LINK = Pattern.compile(
            "\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\](?:\\s*''\\(([^)]*)\\)'')?");

    /**
     * Déduit le statut français du wikitexte d'une page tournoi. Un tableau est
     * réputé publié s'il contient des entrées de bracket ({@code RDx-teamY}). On
     * relève chaque lien de joueur dont la cible cite un Français suivi, et son
     * annotation de résultat (le stade le plus avancé l'emporte si le joueur
     * apparaît dans plusieurs disciplines).
     */
    static FrenchStatus parseFrenchStatus(String wikitext) {
        if (wikitext == null || wikitext.isBlank()) {
            return FrenchStatus.unknown("Article Wikipédia introuvable pour ce tournoi.");
        }
        if (!wikitext.contains("-team")) {   // pas de bracket → tableau non publié
            return FrenchStatus.unknown("Tableau non publié sur Wikipédia — statut inconnu.");
        }
        // Joueur → meilleur stade (label) atteint ; TreeMap pour un ordre stable.
        TreeMap<String, String> label = new TreeMap<>();
        TreeMap<String, Integer> rank = new TreeMap<>();
        Matcher m = LINK.matcher(wikitext);
        while (m.find()) {
            String target = m.group(1).trim();
            String norm = TextUtil.norm(target);
            boolean french = false;
            for (String a : FR_ALIASES) if (TextUtil.hasWord(norm, a)) french = true;
            if (!french) continue;
            String[] fr = stageFr(m.group(2));           // {label, rank} ; annotation nulle → en lice
            int r = Integer.parseInt(fr[1]);
            if (!rank.containsKey(target) || r > rank.get(target)) {
                rank.put(target, r);
                label.put(target, fr[0]);
            }
        }
        if (label.isEmpty()) {
            return new FrenchStatus(false, "Aucun Français engagé",
                    "Aucun Français au tableau (Wikipédia).", false);
        }
        StringBuilder note = new StringBuilder();
        for (var e : label.entrySet()) {
            if (note.length() > 0) note.append(" · ");
            note.append(e.getKey()).append(" — ").append(e.getValue());
        }
        return new FrenchStatus(true, "Français au tableau", note.toString(), false);
    }

    /**
     * Traduit l'annotation anglaise d'un tableau Wikipédia en stade français +
     * rang de progression (1er tour = 1 … champion = 7). Annotation absente
     * (joueur encore en lice, ou tête de série non renseignée) → « En lice », 0.
     * Les échelons « quarter/semi » sont testés AVANT « final » (qu'ils contiennent).
     */
    static String[] stageFr(String annotation) {
        if (annotation == null || annotation.isBlank()) return new String[]{"En lice", "0"};
        String a = annotation.toLowerCase(Locale.ROOT);
        if (a.contains("champion")) return new String[]{"Vainqueur", "7"};
        if (a.contains("quarter")) return new String[]{"1/4 de finale", "4"};
        if (a.contains("semi")) return new String[]{"1/2 finale", "5"};
        if (a.contains("runner") || a.contains("final")) return new String[]{"Finaliste", "6"};
        if (a.contains("third")) return new String[]{"3e tour", "3"};
        if (a.contains("second")) return new String[]{"2e tour", "2"};
        if (a.contains("first")) return new String[]{"1er tour", "1"};
        if (a.contains("withdrew") || a.contains("withdrawn")) return new String[]{"Forfait", "0"};
        if (a.contains("qualif")) return new String[]{"Qualifications", "1"};
        return new String[]{TextUtil.capitalize(annotation.trim()), "0"};
    }
}
