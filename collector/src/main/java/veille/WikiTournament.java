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
 * NIVEAU (G2L… -> tier) ET les DATES (chevauchement ± 1 jour) ; pour les
 * championnats ({@code monde}, {@code europe}), qui n'ont PAS de code de niveau,
 * c'est {@link #matchesChampionship} qui prend le relais. L'ordre :
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
 * CHAMPIONNATS : leur page principale peut ne porter ni bloc Champions ni tableau
 * (VÉRIFIÉ sur les Mondiaux 2026 — les draws vivent dans des sous-articles par
 * discipline). Le tableau des médaillés, lui, y est : {@link #parseMedalists} le lit
 * et sert de repli à {@link #parseChampions} comme à {@link #parseFrenchStatus}.
 *
 * Fonctions pures ({@link #searchQuery}, {@link #shortlist}, {@link #matchesTournament},
 * {@link #matchesChampionship}, {@link #infoboxName}, {@link #parseLevel},
 * {@link #parseInfoboxDates}, {@link #parseChampions}, {@link #parseMedalists},
 * {@link #cellChampion}, {@link #parseFrenchStatus}, {@link #stageFr}) testées ;
 * seul {@link #resolve} fait du réseau (via {@link Wiki}).
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
    /**
     * Même bruit, MOINS « bwf » et « world » : pour un championnat, ces deux mots
     * ne sont pas du sponsoring mais l'identité de l'épreuve. Les retirer laissait
     * la requête « 2026 championships badminton », qui met les championnats d'Asie
     * devant les Mondiaux.
     */
    private static final Set<String> CHAMPIONSHIP_QUERY_NOISE = minus(QUERY_NOISE, "bwf", "world");

    /** Tiers hors World Tour : les championnats individuels seniors (cf. BwfCalendar). */
    private static final Set<String> CHAMPIONSHIP_TIERS = Set.of("monde", "europe");

    /** {@code tier} nullable toléré ({@code Set.of(…).contains(null)} lèverait). */
    private static boolean isChampionship(String tier) {
        return tier != null && CHAMPIONSHIP_TIERS.contains(tier);
    }

    private static Set<String> minus(Set<String> base, String... drop) {
        Set<String> out = new java.util.HashSet<>(base);
        out.removeAll(Set.of(drop));
        return Set.copyOf(out);
    }

    /** Code de niveau BWF de l'infobox Wikipédia (G2L<n>) → tier du contrat. */
    private static final Map<String, String> LEVEL_TIER = Map.of(
            "1", "wtf", "2", "1000", "3", "750", "4", "500", "5", "300");
    /** Libellé humain d'un tier, pour le prompt d'appariement Haiku. */
    private static final Map<String, String> TIER_LABEL = Map.of(
            "wtf", "World Tour Finals", "1000", "Super 1000", "750", "Super 750",
            "500", "Super 500", "300", "Super 300",
            "monde", "Championnats du monde", "europe", "Championnats d'Europe");
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

    /** Vainqueur d'une discipline : nom (paire jointe par « / ») + code pays BWF. */
    record Champion(String name, String country) {}

    /** Les 5 champions d'un tournoi terminé — TOUT OU RIEN (cf. {@link #parseChampions}). */
    record Champions(Champion ms, Champion ws, Champion md, Champion wd, Champion xd) {}

    /**
     * Ce qu'on tire d'un article de tournoi, en UNE lecture : le statut français
     * (draw) et les champions (infobox). {@code champions} reste {@code null} tant
     * que les 5 disciplines ne sont pas publiées.
     */
    record Article(FrenchStatus french, Champions champions) {
        static Article unknown(String note) {
            return new Article(FrenchStatus.unknown(note), null);
        }
    }

    /** Disciplines du contrat, dans l'ordre d'affichage : champ(s) d'infobox. */
    private static final String[][] EVENTS = {
            {"MS"}, {"WS"}, {"MD1", "MD2"}, {"WD1", "WD2"}, {"XD1", "XD2"}};

    // ------------------------------------------------------------
    //  Orchestration (réseau)
    // ------------------------------------------------------------

    /**
     * Résout un tournoi : trouve et VÉRIFIE l'article, lit son wikitexte UNE fois,
     * en extrait la présence française (draw) et les champions (infobox). Échec
     * gracieux : toute indisponibilité ou aucun article vérifié → statut
     * {@code null} (inconnu) et pas de champions, jamais un « aucun » inventé.
     */
    static Article resolve(Tournament t) {
        try {
            String wikitext = articleWikitext(t);
            if (wikitext == null) {
                return Article.unknown(
                        "Aucun article Wikipédia vérifié (dates + niveau) pour « " + t.name() + " ».");
            }
            return new Article(parseFrenchStatus(wikitext), parseChampions(wikitext));
        } catch (Exception e) {
            System.err.println("Wikipédia (statut « " + t.name() + " ») KO : " + e);
            return Article.unknown("Wikipédia momentanément indisponible — statut inconnu.");
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
        List<String> candidates = Wiki.search(searchQuery(t.name(), year, t.tier()));
        Set<String> tokens = TextUtil.nameTokens(t.name() + " " + t.location());
        for (String title : shortlist(candidates, tokens, year)) {
            String wt = Wiki.wikitext(title);
            if (matchesTournament(title, wt, t.start(), t.end(), t.tier())) {
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
            if (matchesTournament(picked, wt, t.start(), t.end(), t.tier())) {
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
        return searchQuery(name, year, null);
    }

    /** Idem, en tenant compte du tier : un championnat garde « bwf »/« world »
     *  (cf. {@link #CHAMPIONSHIP_QUERY_NOISE}). */
    static String searchQuery(String name, int year, String tier) {
        Set<String> noise = isChampionship(tier)
                ? CHAMPIONSHIP_QUERY_NOISE : QUERY_NOISE;
        StringBuilder core = new StringBuilder();
        for (String w : TextUtil.stripAccents(name.toLowerCase(Locale.ROOT)).split("[^a-z0-9]+")) {
            if (w.isBlank() || w.matches("\\d{4}") || noise.contains(w)) continue;
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
        return matchesTournament(null, wikitext, bwfStart, bwfEnd, tier);
    }

    /**
     * Idem, avec le TITRE du candidat — nécessaire pour les championnats, dont
     * l'identité ne tient pas dans un code de niveau (cf. {@link #matchesChampionship}).
     * {@code title} peut être {@code null} : on retombe alors sur le champ
     * {@code | name =} de l'infobox.
     */
    static boolean matchesTournament(String title, String wikitext, LocalDate bwfStart,
                                     LocalDate bwfEnd, String tier) {
        if (wikitext == null) return false;
        if (!datesMatch(wikitext, bwfStart, bwfEnd)) return false;   // dates absentes ou hors plage
        if (isChampionship(tier)) return matchesChampionship(title, wikitext, tier);
        String level = parseLevel(wikitext);
        return level != null && level.equals(tier);                  // niveau absent ou contredit
    }

    /** Les dates de l'infobox chevauchent-elles celles du calendrier (± 1 jour) ? */
    private static boolean datesMatch(String wikitext, LocalDate bwfStart, LocalDate bwfEnd) {
        LocalDate[] dates = parseInfoboxDates(wikitext, bwfStart.getYear());
        if (dates == null) return false;
        return !dates[0].isAfter(bwfEnd.plusDays(1))
                && !dates[1].isBefore(bwfStart.minusDays(1));
    }

    /** Modèle d'infobox propre au badminton — un article d'un autre sport ne l'a jamais.
     *  (La casse varie d'un article à l'autre : « Infobox » / « infobox ».) */
    private static final Pattern BADMINTON_INFOBOX =
            Pattern.compile("(?i)\\{\\{\\s*infobox\\s+badminton\\s+event");

    /** Éditions VOISINES d'un championnat senior individuel, à ne jamais confondre
     *  avec lui : juniors, jeunes, vétérans, para, épreuves par équipes, qualifs. */
    private static final String[] CHAMPIONSHIP_EXCLUDE = {
            "junior", "juniors", "youth", "senior", "seniors", "para", "parabadminton",
            "team", "teams", "qualification", "qualifying", "u15", "u17", "u19"};

    /**
     * Vérification propre aux championnats ({@code monde}, {@code europe}).
     *
     * <p>Le contrôle habituel s'appuie sur {@code level = G2L<n>} — les Mondiaux et
     * les Euros ne le portent PAS (VÉRIFIÉ : les Mondiaux 2026 écrivent
     * {@code level = 1}, les Euros 2026 laissent le champ vide). On remplace donc ce
     * signal par trois autres, tous exigés, sans rien relâcher pour autant :
     * <ol>
     *   <li>le modèle {@code {{Infobox badminton event}}} — c'est LUI qui recale un
     *       homonyme d'un autre sport, à la place du niveau (anti-régression tennis) ;</li>
     *   <li>le mot « championship(s) » dans l'identité de l'article ;</li>
     *   <li>le bon périmètre : « world » pour les Mondiaux, « europe(an) » pour les
     *       Euros, et AUCUN mot d'édition voisine ({@link #CHAMPIONSHIP_EXCLUDE}) —
     *       sans quoi « 2026 BWF World Junior Championships » ou « 2026 BWF
     *       Para-Badminton World Championships » passeraient pour les Mondiaux.</li>
     * </ol>
     * Les dates, elles, restent vérifiées par l'appelant.
     */
    static boolean matchesChampionship(String title, String wikitext, String tier) {
        if (wikitext == null || !BADMINTON_INFOBOX.matcher(wikitext).find()) return false;
        String id = TextUtil.norm(
                title != null && !title.isBlank() ? title : infoboxName(wikitext));
        if (id.isBlank()) return false;
        if (!TextUtil.hasWord(id, "championships") && !TextUtil.hasWord(id, "championship")) {
            return false;
        }
        for (String bad : CHAMPIONSHIP_EXCLUDE) if (TextUtil.hasWord(id, bad)) return false;
        if ("monde".equals(tier)) return TextUtil.hasWord(id, "world");
        return TextUtil.hasWord(id, "european") || TextUtil.hasWord(id, "europe");
    }

    /**
     * Champ {@code | name =} de l'infobox, débarrassé de ses modèles et liens
     * ({@code {{nowrap|2026 European Badminton Championships}}} → le texte seul).
     * Repli d'identité quand le titre de l'article n'est pas connu.
     */
    static String infoboxName(String wikitext) {
        String raw = infoboxField(wikitext, "name");
        if (raw == null) return "";
        return raw.replaceAll("\\{\\{[^|}]*\\|([^}]*)\\}\\}", "$1")   // {{nowrap|X}} → X
                  .replaceAll("[{}\\[\\]']", " ")
                  .replaceAll("\\s+", " ").trim();
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
    //  Fonctions pures — champions (bloc « Champions » de l'infobox)
    // ------------------------------------------------------------

    /**
     * Vainqueurs des 5 disciplines, lus DÉTERMINISTIQUEMENT dans le bloc
     * « Champions » de l'infobox ({@code | MS = [[…]] | country_MS = TPE},
     * {@code MD1}/{@code MD2} pour une paire…). Zéro LLM : c'est du champ nommé,
     * comme le niveau et les dates.
     *
     * <p><b>TOUT OU RIEN.</b> Wikipédia remplit ces champs APRÈS les finales : le
     * jour de la finale ils existent mais sont VIDES, et ils se remplissent
     * discipline par discipline. Tant que les 5 ne sont pas là (paire incomplète
     * comprise), on renvoie {@code null} — le front affichera « résultats en
     * attente » plutôt qu'un palmarès partiel présenté comme définitif. Le passage
     * suivant du collecteur les récupérera.
     *
     * <p>Le pays peut manquer sans invalider le champion (on affiche le nom seul) ;
     * pour une paire de deux nationalités, les codes sont joints (« INA / JPN »).
     */
    /** Lien Wikipédia d'un champ de champion : {@code [[cible]]} ou {@code [[cible|affichage]]}. */
    private static final Pattern NAME_LINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]*))?\\]\\]");

    static Champions parseChampions(String wikitext) {
        if (wikitext == null) return null;
        Champion[] won = new Champion[EVENTS.length];
        for (int i = 0; i < EVENTS.length; i++) {
            won[i] = champion(wikitext, EVENTS[i]);
            if (won[i] == null) {
                // Pas de bloc Champions dans l'infobox : c'est le cas des
                // Championnats du monde, qui publient un tableau « Medalists ».
                return championsFromMedalists(wikitext);
            }
        }
        return new Champions(won[0], won[1], won[2], won[3], won[4]);
    }

    /** Les 5 médaillés d'or du tableau « Medalists », ou {@code null} (tout ou rien). */
    private static Champions championsFromMedalists(String wikitext) {
        List<Medalists> rows = parseMedalists(wikitext);
        if (rows.size() != EVENTS.length) return null;
        return new Champions(rows.get(0).gold(), rows.get(1).gold(), rows.get(2).gold(),
                rows.get(3).gold(), rows.get(4).gold());
    }

    /** Champion d'une discipline : 1 champ (simple) ou 2 (paire, les DEUX exigés). */
    private static Champion champion(String wikitext, String[] fields) {
        StringBuilder names = new StringBuilder();
        List<String> countries = new ArrayList<>();
        for (String f : fields) {
            String name = plainName(infoboxField(wikitext, f));
            if (name == null) return null;        // paire à moitié saisie = pas publiée
            if (names.length() > 0) names.append(" / ");
            names.append(name);
            String c = infoboxField(wikitext, "country_" + f);
            if (c != null && !c.isBlank() && !countries.contains(c.trim())) countries.add(c.trim());
        }
        return new Champion(names.toString(),
                countries.isEmpty() ? null : String.join(" / ", countries));
    }

    /**
     * Valeur brute d'un champ d'infobox ({@code | <nom> = …}), ou {@code null}.
     * Espaces HORIZONTAUX seulement ({@code [ \t]}) autour du {@code =} : dans le
     * bloc Champions, un champ VIDE est le cas normal (tournoi en cours) et un
     * {@code \s*} gourmand sauterait la fin de ligne pour lire le champ SUIVANT
     * (« country_MS » deviendrait le vainqueur du simple messieurs).
     */
    private static String infoboxField(String wikitext, String name) {
        Matcher m = Pattern.compile(
                "(?im)^[ \\t]*\\|[ \\t]*" + Pattern.quote(name) + "[ \\t]*=[ \\t]*([^\\n]*)")
                .matcher(wikitext);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Nom affichable d'un champ de champion : on garde le libellé du lien
     * ({@code [[Tan Ning (badminton)|Tan Ning]]} → « Tan Ning »), sinon la cible
     * sans son homonymie ({@code [[X (badminton)]]} → « X »), et on retire
     * templates et gras. Champ vide (tournoi en cours) → {@code null}.
     */
    static String plainName(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\{\\{[^}]*\\}\\}", " ")       // {{flagicon|TPE}}, {{nowrap|…}}
                      .replace("'''", "").replace("''", "");
        Matcher m = NAME_LINK.matcher(s);
        if (m.find()) {
            s = m.group(2) != null && !m.group(2).isBlank()
                    ? m.group(2)                                   // [[cible|AFFICHAGE]]
                    : m.group(1).replaceAll("\\s*\\([^)]*\\)\\s*$", "");  // [[X (badminton)]] → X
        } else {
            s = s.replaceAll("[\\[\\]]", "");                      // nom sans lien
        }
        s = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------
    //  Fonctions pures — tableau « Medalists » (Championnats du monde)
    // ------------------------------------------------------------

    /** Podium d'une discipline : or, argent, et les DEUX bronzes (pas de petite finale). */
    record Medalists(Champion gold, Champion silver, List<Champion> bronze) {}

    /**
     * Libellés de discipline du tableau « Medalists », dans l'ordre du contrat.
     * PIÈGE : « Men's singles » est un SUFFIXE de « Women's singles » — la recherche
     * exige donc une borne à gauche, sinon la ligne du simple dames serait lue comme
     * celle du simple messieurs.
     */
    private static final String[] MEDAL_EVENTS = {
            "men's singles", "women's singles", "men's doubles",
            "women's doubles", "mixed doubles"};

    /**
     * Podiums lus dans le tableau « Medalists » d'une page de championnat.
     *
     * <p>VÉRIFIÉ sur les Championnats du monde 2026 : contrairement aux tournois du
     * World Tour, la page ne porte NI bloc Champions dans l'infobox NI tableau (les
     * draws vivent dans des sous-articles par discipline). Le seul résultat présent
     * sur la page principale est ce tableau de médaillés — {@code {{MedalistTable}}},
     * une cellule par médaille, dans l'ordre or / argent / bronze.
     *
     * <p><b>TOUT OU RIEN</b>, comme le bloc Champions : une discipline manquante (ou
     * un tableau pas encore rempli) → liste vide, et le front dit « résultats en
     * attente » plutôt que d'annoncer un palmarès partiel.
     */
    static List<Medalists> parseMedalists(String wikitext) {
        String table = medalistsTable(wikitext);
        if (table == null) return List.of();
        List<Medalists> out = new ArrayList<>();
        for (String event : MEDAL_EVENTS) {
            Medalists row = medalRow(table, event);
            if (row == null || row.gold() == null) return List.of();
            out.add(row);
        }
        return out;
    }

    /** Extrait le wikitexte du tableau des médaillés, ou {@code null} s'il n'y en a pas. */
    private static String medalistsTable(String wikitext) {
        if (wikitext == null) return null;
        Matcher m = Pattern.compile("(?i)\\{\\{\\s*medalist ?table").matcher(wikitext);
        if (!m.find()) return null;
        int end = wikitext.indexOf("\n|}", m.start());
        return wikitext.substring(m.start(), end < 0 ? wikitext.length() : end);
    }

    /**
     * Podium d'une discipline : on repère sa cellule, puis on lit les cellules
     * suivantes de la ligne (or, argent, 1er bronze) et la première cellule de la
     * ligne d'après (2e bronze, porté par le {@code rowspan} de l'or et de l'argent).
     */
    private static Medalists medalRow(String table, String event) {
        String low = table.toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("(?<![a-z])" + event.replace("'", "['’]")).matcher(low);
        if (!m.find()) return null;
        int rowEnd = indexOfRowBreak(table, m.start());
        List<String> cells = rowCells(table.substring(m.start(), rowEnd));
        if (cells.size() < 3) return null;                 // discipline + or + argent au minimum
        List<Champion> bronze = new ArrayList<>();
        if (cells.size() > 3) addIfPresent(bronze, cellChampion(cells.get(3)));
        addIfPresent(bronze, secondBronze(table, rowEnd));
        return new Medalists(cellChampion(cells.get(1)), cellChampion(cells.get(2)), bronze);
    }

    /**
     * 2e bronze : il est seul sur la ligne SUIVANTE, l'or et l'argent la couvrant
     * par leur {@code rowspan="2"}. On s'arrête net si cette ligne ouvre déjà une
     * autre discipline — un tableau incomplet ne doit pas déporter un médaillé.
     */
    private static Champion secondBronze(String table, int rowEnd) {
        String next = table.substring(Math.min(rowEnd + 3, table.length()),
                indexOfRowBreak(table, rowEnd + 3));
        String low = next.toLowerCase(Locale.ROOT);
        for (String event : MEDAL_EVENTS) if (low.contains(event)) return null;
        for (String cell : rowCells(next)) {
            Champion c = cellChampion(cell);
            if (c != null) return c;
        }
        return null;
    }

    private static void addIfPresent(List<Champion> into, Champion c) {
        if (c != null) into.add(c);
    }

    /** Position du prochain séparateur de ligne {@code \n|-}, ou la fin du tableau. */
    private static int indexOfRowBreak(String table, int from) {
        if (from >= table.length()) return table.length();
        int i = table.indexOf("\n|-", from);
        return i < 0 ? table.length() : i;
    }

    /** Cellules d'une ligne : le wikitexte en met une par ligne, introduite par « | ». */
    private static List<String> rowCells(String row) {
        List<String> out = new ArrayList<>();
        for (String part : row.split("\\n[ \\t]*\\|")) out.add(part);
        return out;
    }

    /**
     * Médaillé d'une cellule : le ou les noms (paire jointe par « / ») et le pays.
     * Deux écritures coexistent dans ces tableaux — {@code {{flagmedalist|[[X]]|FRA}}}
     * pour un simple (le pays est le DERNIER segment), {@code {{flagcountry|CHN}}}
     * suivi des deux joueurs pour une paire. Cellule sans joueur → {@code null}.
     */
    static Champion cellChampion(String cell) {
        if (cell == null) return null;
        String country = null;
        Matcher fm = Pattern.compile("(?i)\\{\\{\\s*flagmedalist\\s*\\|([^}]*)\\}\\}").matcher(cell);
        if (fm.find()) {
            String[] seg = fm.group(1).split("\\|");
            if (seg.length > 1) country = seg[seg.length - 1].trim();
        } else {
            Matcher cm = Pattern.compile(
                    "(?i)\\{\\{\\s*(?:flagcountry|flagicon|flagathlete|bd|flag)\\s*\\|\\s*([A-Za-z]{2,3})\\b")
                    .matcher(cell);
            if (cm.find()) country = cm.group(1).trim().toUpperCase(Locale.ROOT);
        }
        StringBuilder names = new StringBuilder();
        Matcher lm = NAME_LINK.matcher(cell);
        while (lm.find()) {
            String n = lm.group(2) != null && !lm.group(2).isBlank()
                    ? lm.group(2).trim()
                    : lm.group(1).replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            if (n.isEmpty()) continue;
            if (names.length() > 0) names.append(" / ");
            names.append(n);
        }
        if (names.length() == 0) return null;
        return new Champion(names.toString(),
                country == null || country.isBlank() ? null : country);
    }

    // ------------------------------------------------------------
    //  Fonctions pures — lecture du draw
    // ------------------------------------------------------------

    /**
     * Lien Wikipédia [[cible(|affichage)]] éventuellement suivi de son annotation de
     * résultat entre parenthèses.
     *
     * <p>Les italiques autour de l'annotation sont OPTIONNELLES et les apostrophes
     * de mise en forme peuvent tomber des deux côtés. VÉRIFIÉ sur plusieurs articles
     * (China Open, Championnats d'Europe) : Wikipédia écrit {@code ''(quarter-finals)''}
     * mais {@code '''[[X]] (champion)'''} — le vainqueur est en gras, et son annotation
     * n'a PAS d'italiques. Exiger {@code ''} faisait rater précisément le stade le
     * plus important : le champion ressortait « En lice ». L'espace toléré avant la
     * parenthèse exclut le saut de ligne — une annotation est toujours sur sa ligne,
     * et on ne veut pas attraper une parenthèse de prose plus bas.
     */
    private static final Pattern LINK = Pattern.compile(
            "\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?\\]\\](?:[ \\t']*\\(([^)]*)\\))?");

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
        if (!wikitext.contains("-team")) {   // pas de bracket sur cette page
            // Les Championnats du monde n'en ont JAMAIS : les tableaux vivent dans
            // des sous-articles par discipline. Le podium, lui, est sur la page —
            // il ne dit pas tout, mais ce qu'il dit est sûr. Aucun Français au
            // podium ne prouve rien → on reste sur « inconnu », jamais un « aucun ».
            FrenchStatus podium = frenchFromMedalists(wikitext);
            return podium != null ? podium
                    : FrenchStatus.unknown("Tableau non publié sur Wikipédia — statut inconnu.");
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
     * Statut français déduit du seul PODIUM, quand la page ne publie pas de tableau
     * (Championnats du monde). On ne voit que les médaillés : un Français absent du
     * podium reste invisible, donc l'absence ne se conclut pas → {@code null}, et
     * l'appelant garde « statut inconnu ». Le titre le dit franchement.
     */
    private static FrenchStatus frenchFromMedalists(String wikitext) {
        List<Medalists> rows = parseMedalists(wikitext);
        if (rows.isEmpty()) return null;
        TreeMap<String, String> stages = new TreeMap<>();
        TreeMap<String, Integer> rank = new TreeMap<>();
        for (Medalists row : rows) {
            // Rangs de la même échelle que stageFr : le stade le plus avancé
            // l'emporte si le joueur médaille dans deux disciplines.
            collectFrench(stages, rank, row.gold(), "Vainqueur", 7);
            collectFrench(stages, rank, row.silver(), "Finaliste", 6);
            for (Champion b : row.bronze()) collectFrench(stages, rank, b, "1/2 finale", 5);
        }
        if (stages.isEmpty()) return null;
        StringBuilder note = new StringBuilder();
        for (var e : stages.entrySet()) {
            if (note.length() > 0) note.append(" · ");
            note.append(e.getKey()).append(" — ").append(e.getValue());
        }
        return new FrenchStatus(true, "Français sur le podium", note.toString(), false);
    }

    /** Retient les joueurs suivis d'une cellule de podium (une paire = deux noms). */
    private static void collectFrench(TreeMap<String, String> into, TreeMap<String, Integer> rank,
                                      Champion c, String stage, int r) {
        if (c == null) return;
        for (String raw : c.name().split(" / ")) {
            String name = raw.trim();
            String norm = TextUtil.norm(name);
            boolean french = false;
            for (String a : FR_ALIASES) if (TextUtil.hasWord(norm, a)) french = true;
            if (!french) continue;
            if (!rank.containsKey(name) || r > rank.get(name)) {
                rank.put(name, r);
                into.put(name, stage);
            }
        }
    }

    /**
     * Traduit l'annotation anglaise d'un tableau Wikipédia en stade français +
     * rang de progression (1er tour = 1 … champion = 7). Annotation absente
     * (joueur encore en lice, ou tête de série non renseignée) → « En lice », 0.
     * Les échelons « quarter/semi » sont testés AVANT « final » (qu'ils contiennent).
     */
    static String[] stageFr(String annotation) {
        if (annotation == null || annotation.isBlank()) return new String[]{"En lice", "0"};
        // Parenthèse sans aucune lettre (« (2) », un numéro de tête de série) :
        // ce n'est pas une annotation de résultat, on ne l'affiche pas comme un stade.
        if (!annotation.matches(".*\\p{L}.*")) return new String[]{"En lice", "0"};
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
