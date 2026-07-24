package veille;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Filet LLM (Source B de CLAUDE.md) : l'historique de saison d'un joueur est en
 * PROSE sur sa page Wikipédia — trop libre pour des règles sèches. On passe ce
 * texte à Claude Haiku avec consigne « réponds UNIQUEMENT en JSON » et on en tire
 * les lignes {@code players[].lines}. C'est de l'extraction de langage, pas un
 * agent : le LLM lit un texte fourni, il ne va rien chercher.
 *
 * Le déterministe reste prioritaire : le {@code rank} vient de l'infobox (jamais
 * du LLM), le statut des tournois vient des tableaux, et l'appariement d'article
 * est d'abord tenté DÉTERMINISTIQUEMENT. Haiku n'intervient que sur deux points
 * où aucune règle ne tranche :
 * <ul>
 *   <li>{@link #extractSeason} — l'historique de saison en PROSE → {@code lines[]} ;</li>
 *   <li>{@link #pickArticle} — l'APPARIEMENT d'article en dernier recours, si la
 *       vérification déterministe (dates + niveau) n'a rien retenu ; le choix est
 *       ensuite REVALIDÉ puis mémorisé (cf. {@link WikiTournament}).</li>
 * </ul>
 *
 * Échec gracieux TOTAL : sans {@code ANTHROPIC_API_KEY}, sur toute erreur, ou si
 * la réponse n'est pas le JSON attendu, on renvoie vide / {@code null} — l'appelant
 * conserve alors la dernière bonne valeur (cache) ou {@code present = null}. Le
 * filet ne peut ni casser ni vider data.json. Économie de tokens : la prose n'est
 * relue que si la RÉVISION Wikipédia change ; l'appariement, jamais plus d'une fois
 * par tournoi (mémorisé dans {@link Aliases}).
 *
 * Seuls {@link #ask} et {@link #askArticle} font du réseau ; {@link #parseSeasonLines}
 * et {@link #parsePickedTitle} sont pures et testées.
 */
final class LlmNet {

    private LlmNet() {}

    private static final String MODEL = "claude-haiku-4-5";
    private static final long MAX_TOKENS = 900;
    private static final String SYSTEM =
            "Tu extrais des résultats de badminton. Réponds UNIQUEMENT en JSON, aucune prose.";
    /** Nb max de lignes conservées par joueur (les plus récentes). */
    static final int MAX_LINES = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static AnthropicClient client;

    /** La clé API est-elle disponible ? Sans elle, le filet est inerte. */
    static boolean enabled() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    /**
     * Extrait les lignes de saison d'un joueur depuis la prose de sa page
     * Wikipédia (via Haiku). Renvoie une liste VIDE sur clé absente, prose vide,
     * ou toute erreur — l'appelant garde alors sa dernière bonne valeur.
     */
    static List<DataJson.LineJson> extractSeason(String player, int year, String prose,
                                                 List<Tournament> calendar, LocalDate today) {
        if (!enabled() || prose == null || prose.isBlank()) return List.of();
        try {
            return ask(player, year, prose, calendar, today);
        } catch (Exception e) {
            System.err.println("filet LLM KO (" + player + ") — dernière valeur conservée : " + e);
            return List.of();
        }
    }

    /** Seule méthode réseau du filet (non testée unitairement, cf. CLAUDE.md). */
    private static List<DataJson.LineJson> ask(String player, int year, String prose,
                                               List<Tournament> calendar, LocalDate today) {
        System.out.println("filet LLM → Haiku : historique " + year + " d'" + player
                + " (" + prose.length() + " car.)");

        String user = "Voici un extrait Wikipédia sur " + player + " :\n\n" + prose + "\n\n"
                + "N'extrais QUE les résultats de la saison " + year + ". IGNORE toute mention "
                + "d'une autre saison (année ≠ " + year + "), même citée en passant.\n"
                + "Du PLUS RÉCENT au plus ancien, réponds UNIQUEMENT avec un tableau JSON, "
                + "format exact :\n"
                + "[{\"year\":" + year + ","
                + "\"tournament\":\"<nom du tournoi TEL QUEL dans le texte, sans le traduire, "
                + "ex. Japan Open, Singapore Open, Orléans Masters>\","
                + "\"stage\":\"<résultat en français, ex. Vainqueur, Finaliste, Demi-finaliste, "
                + "1/4 de finale, Éliminé au 1er tour>\",\"tone\":\"win|out|null\"}]\n"
                + "\"year\" : l'année du résultat (doit valoir " + year + ").\n"
                + "\"tone\" : \"win\" = remporte le tournoi ; \"out\" = éliminé ; "
                + "null = en cours ou indéterminé.\n"
                + "NE DONNE JAMAIS de date : les dates ne sont pas dans le texte, ne les invente "
                + "pas. Si une information n'est pas EXPLICITEMENT écrite, OMETS-la. "
                + "Garde le nom du tournoi tel quel (ne traduis pas). Maximum " + MAX_LINES
                + " entrées. N'invente rien : n'inclus que ce qui est écrit.";

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM)
                .addUserMessage(user)
                .build();

        StringBuilder text = new StringBuilder();
        client().messages().create(params).content()
                .forEach(b -> b.text().ifPresent(tb -> text.append(tb.text())));
        return parseSeasonLines(text.toString(), year, calendar, today);
    }

    /** Entrée brute avant tri/étiquetage (filet déterministe par-dessus Haiku). */
    private record Raw(LocalDate sortDate, String dateLabel, String tournament,
                       String stage, String tone) {}

    /**
     * Transforme la réponse de Haiku en {@code lines} pour la saison {@code year}.
     * Filet DÉTERMINISTIQUE par-dessus le LLM :
     * <ul>
     *   <li>toute entrée dont le champ {@code year} ≠ {@code year} visé est REJETÉE
     *       (Haiku remonte parfois une autre saison) ;</li>
     *   <li>la DATE et le NOM d'affichage viennent du calendrier BWF, jamais de Haiku
     *       (qui inventait les dates) : on apparie STRICTEMENT le nom au calendrier
     *       ({@link #matchTournament}) et on prend ses dates + son nom (déjà en usage
     *       côté current/upcoming). Hors calendrier World Tour → date {@code null} et
     *       nom français si connu ({@link #frenchName}), sinon nom d'origine ;</li>
     *   <li>tri chronologique DÉCROISSANT par ces dates déterministes ; les lignes
     *       sans date passent APRÈS celles qui en ont, sans casser l'ordre ;</li>
     *   <li>{@code medal} calculée depuis le stade ({@link #medalFor}).</li>
     * </ul>
     * Tolère prose/clôtures Markdown (premier {@code [} … dernier {@code ]}). Un
     * tone hors contrat → null ; une entrée sans tournoi ni stade est écartée. Tout
     * JSON invalide → liste vide, jamais d'exception.
     */
    static List<DataJson.LineJson> parseSeasonLines(String raw, int year,
                                                    List<Tournament> calendar, LocalDate today) {
        if (raw == null) return List.of();
        int a = raw.indexOf('[');
        int b = raw.lastIndexOf(']');
        if (a < 0 || b <= a) return List.of();
        List<Raw> rows = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(raw.substring(a, b + 1));
            if (!arr.isArray()) return List.of();
            for (JsonNode n : arr) {
                String tournament = txt(n, "tournament");
                String stage = txt(n, "stage");
                if (tournament == null && stage == null) continue;   // rien d'exploitable
                Integer y = yearOf(n);
                if (y != null && y != year) continue;                // saison étrangère → rejetée
                JsonNode toneNode = n.path("tone");
                String tone = toneNode.isTextual() ? toneNode.asText().trim() : null;
                if (tone != null && !"win".equals(tone) && !"out".equals(tone)) tone = null;
                // Date + nom DÉTERMINISTES via appariement strict au calendrier BWF.
                Tournament m = tournament != null ? matchTournament(tournament, calendar, today) : null;
                LocalDate sortDate = m != null ? m.start() : null;
                String dateLabel = m != null ? FrDates.dateRange(m.start(), m.end(), false) : null;
                String name = m != null ? m.name() : frenchName(tournament);
                rows.add(new Raw(sortDate, dateLabel, name, stage, tone));
            }
        } catch (Exception e) {
            return List.of();
        }
        // Tri chronologique décroissant sur les dates déterministes ; les lignes sans
        // date passent après (List.sort est stable → leur ordre relatif est préservé).
        rows.sort((x, z) -> {
            if (x.sortDate() == null && z.sortDate() == null) return 0;
            if (x.sortDate() == null) return 1;
            if (z.sortDate() == null) return -1;
            return z.sortDate().compareTo(x.sortDate());
        });

        List<DataJson.LineJson> out = new ArrayList<>();
        for (Raw r : rows) {
            if (out.size() >= MAX_LINES) break;
            String label = out.isEmpty() ? "Dernier" : "Puis";
            String value = r.tournament() != null && r.stage() != null
                    ? r.tournament() + " · " + r.stage()
                    : (r.tournament() != null ? r.tournament() : r.stage());
            out.add(new DataJson.LineJson(label, r.dateLabel(), r.tournament(), r.stage(),
                    medalFor(r.stage(), r.tone()), r.tone(), value));
        }
        return out;
    }

    /** Champ texte non vide d'un nœud JSON, sinon {@code null}. */
    private static String txt(JsonNode n, String field) {
        JsonNode f = n.path(field);
        return f.isTextual() && !f.asText().isBlank() ? f.asText().trim() : null;
    }

    /** Année d'une entrée ({@code "year":2026} ou {@code "year":"2026"}), sinon null. */
    private static Integer yearOf(JsonNode n) {
        JsonNode y = n.path("year");
        if (y.isInt()) return y.asInt();
        if (y.isTextual() && y.asText().trim().matches("\\d{4}")) return Integer.parseInt(y.asText().trim());
        return null;
    }

    /** Mots à retirer des noms de tournoi pour l'appariement : articles, génériques,
     *  sponsors, années, niveaux. On GARDE le type d'épreuve (open/masters/…) et la
     *  géographie — ce sont eux qui distinguent « Japan Open » de « … Masters Japan ». */
    private static final Set<String> NAME_NOISE = Set.of(
            "de", "du", "des", "d", "l", "le", "la", "les", "et", "the", "of", "and", "for", "a",
            "badminton", "super", "tournament", "world", "tour", "bwf",
            "hsbc", "yonex", "victor", "daihatsu", "sands", "li", "ning", "lining",
            "petronas", "perodua", "toyota", "kapal", "api", "sathio", "group", "ltd", "co",
            "powered", "by", "presented", "polytron", "kff", "antica", "sunrise", "hylo",
            "clash", "clans", "celcomdigi", "guwahati");
    /** Types d'épreuve : ne peuvent PAS suffire seuls à apparier (« Open » tout court). */
    private static final Set<String> EVENT_TYPES = Set.of(
            "open", "masters", "international", "championships", "championship",
            "finals", "final", "cup", "series", "challenge", "games", "olympics");

    /**
     * Jetons DISTINCTIFS d'un nom (type d'épreuve + géographie), sponsors/années/
     * niveaux retirés — mais on garde « open »/« masters » (à la différence de
     * {@code nameTokens}) : c'est le type d'épreuve qui sépare « Japan Open » de
     * « Kumamoto Masters Japan ».
     */
    static Set<String> coreTokens(String name) {
        Set<String> out = new HashSet<>();
        for (String w : TextUtil.stripAccents(name.toLowerCase(Locale.ROOT)).split("[^a-z0-9]+")) {
            if (w.length() < 2 || w.matches("\\d{4}") || w.matches("100|300|500|750|1000")) continue;
            if (!NAME_NOISE.contains(w)) out.add(w);
        }
        return out;
    }

    /**
     * Apparie STRICTEMENT le nom rendu par Haiku (langue source) à un tournoi du
     * calendrier BWF — dans l'esprit de {@code WikiTournament.matchesTournament} :
     * <ul>
     *   <li>correspondance sur le NOYAU du nom : chaque jeton distinctif de l'extrait
     *       doit être présent dans le tournoi du calendrier (sous-ensemble). Ainsi
     *       « Japan Open » ⊄ « Kumamoto Masters Japan » (il manque « open ») et
     *       « India Open » ⊄ « Syed Modi India International » ;</li>
     *   <li>il faut au moins un jeton NON générique (pas seulement « open ») ;</li>
     *   <li>COHÉRENCE CHRONOLOGIQUE : un résultat de saison est un fait passé — un
     *       tournoi commençant APRÈS {@code today} ne peut l'expliquer, on l'écarte
     *       (c'est ce qui bloquait « Japan Open » → « Kumamoto Masters » de novembre).</li>
     * </ul>
     * Plusieurs éditions passées valides → la plus récente. Aucun appariement fiable
     * → {@code null} (le front n'affiche alors pas de date : mieux que fausse).
     */
    static Tournament matchTournament(String extracted, List<Tournament> calendar, LocalDate today) {
        if (extracted == null || calendar == null) return null;
        Set<String> core = coreTokens(extracted);
        boolean hasDistinctive = core.stream().anyMatch(t -> !EVENT_TYPES.contains(t));
        if (!hasDistinctive) return null;                       // « Open » seul n'apparie rien
        Tournament best = null;
        for (Tournament t : calendar) {
            Set<String> have = coreTokens(t.name() + " " + t.location());
            if (!have.containsAll(core)) continue;              // noyau exigé (sous-ensemble)
            if (today != null && t.start().isAfter(today)) continue;   // pas de tournoi futur
            if (best == null || t.start().isAfter(best.start())) best = t;   // édition la + récente
        }
        return best;
    }

    /**
     * Nom d'affichage français d'un tournoi HORS calendrier World Tour (Coupe Thomas,
     * Championnats d'Europe…). Table courte et stable, appariée par jetons (robuste
     * aux variantes « Badminton »/pluriel). Aucun équivalent connu → nom d'origine
     * (on n'invente pas de traduction).
     */
    static String frenchName(String original) {
        if (original == null) return null;
        String n = TextUtil.norm(original);
        if (n.contains("thomas") && n.contains("cup")) return "Coupe Thomas";
        if (n.contains("uber") && n.contains("cup")) return "Coupe Uber";
        if (n.contains("sudirman")) return "Coupe Sudirman";
        if (n.contains("european") && n.contains("team")) return "Championnats d'Europe par équipes";
        if (n.contains("european") && n.contains("championship")) return "Championnats d'Europe";
        if (n.contains("world") && n.contains("championship")) return "Championnats du monde";
        return original;                                        // pas d'équivalent connu
    }

    /**
     * Médaille d'un stade (DÉTERMINISTE, jamais Haiku ni le front) selon l'échelle
     * badminton — pas de match pour la 3e place, les DEUX demi-finalistes ont le
     * bronze : 🥇 vainqueur, 🥈 finaliste, 🥉 demi-finaliste, 🎯 encore en lice,
     * ⚫ éliminé avant les demies (1/4, 1/8, tours, stade non précisé). L'ordre des
     * tests importe : « demi »/« 1/2 » AVANT « finaliste », et « de finale » (1/4,
     * 1/8) ne doit pas être pris pour une finale.
     */
    static String medalFor(String stage, String tone) {
        String s = stage == null ? "" : TextUtil.norm(stage);
        if ("win".equals(tone) || s.contains("vainqueur") || s.contains("sacre")
                || s.contains("champion")) return "🥇";
        if (s.contains("demi") || s.contains("1/2")) return "🥉";
        if (s.contains("finaliste")
                || (s.contains("finale") && !s.contains("de finale")
                    && !s.contains("1/4") && !s.contains("1/8"))) return "🥈";
        if (s.contains("en lice") || s.contains("en cours")) return "🎯";
        return "⚫";
    }

    // ------------------------------------------------------------
    //  Appariement d'article — dernier recours (cf. WikiTournament)
    // ------------------------------------------------------------

    /**
     * Dernier recours d'APPARIEMENT : quand aucun candidat n'a passé la vérification
     * déterministe (dates + niveau), Haiku choisit parmi les titres candidats lequel
     * désigne le tournoi BWF (nom + dates + niveau). Le titre retenu est ENSUITE
     * revalidé par {@code WikiTournament.matchesTournament} puis mémorisé — Haiku
     * n'est donc sollicité qu'une fois par tournoi non trivial. Sans clé API, sans
     * candidats, ou sur toute erreur → {@code null} (comportement inchangé).
     */
    static String pickArticle(String bwfName, String dates, String tierLabel, List<String> candidates) {
        if (!enabled() || candidates == null || candidates.isEmpty()) return null;
        try {
            return askArticle(bwfName, dates, tierLabel, candidates);
        } catch (Exception e) {
            System.err.println("filet Haiku appariement KO (" + bwfName + ") — ignoré : " + e);
            return null;
        }
    }

    /** Seule méthode réseau de l'appariement (non testée, cf. CLAUDE.md). */
    private static String askArticle(String bwfName, String dates, String tierLabel,
                                     List<String> candidates) {
        System.out.println("filet Haiku appariement → « " + bwfName + " » parmi " + candidates);
        StringBuilder user = new StringBuilder();
        user.append("Tournoi de badminton BWF à apparier : « ").append(bwfName)
                .append(" », niveau ").append(tierLabel).append(", dates ").append(dates).append(".\n")
                .append("Parmi ces titres d'articles Wikipédia, lequel désigne EXACTEMENT ce tournoi ")
                .append("(bonne édition, badminton — pas un autre sport ni une autre année) ?\n");
        for (String c : candidates) user.append("- ").append(c).append('\n');
        user.append("Réponds UNIQUEMENT en JSON : {\"title\":\"<un titre recopié EXACTEMENT ")
                .append("depuis la liste, ou null si aucun ne correspond>\"}.");

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(120)
                .system(SYSTEM)
                .addUserMessage(user.toString())
                .build();

        StringBuilder text = new StringBuilder();
        client().messages().create(params).content()
                .forEach(b -> b.text().ifPresent(tb -> text.append(tb.text())));
        return parsePickedTitle(text.toString(), candidates);
    }

    /**
     * Extrait le titre choisi de la réponse Haiku. On n'accepte qu'un titre
     * EXACTEMENT présent dans la liste des candidats (garde-fou anti-hallucination :
     * Haiku ne peut pas inventer un titre). Tolère prose/clôtures autour de l'objet
     * JSON. {@code title:null}, JSON invalide, ou titre hors liste → {@code null}.
     */
    static String parsePickedTitle(String raw, List<String> candidates) {
        if (raw == null || candidates == null) return null;
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode obj = MAPPER.readTree(raw.substring(a, b + 1));
            JsonNode title = obj.path("title");
            if (!title.isTextual()) return null;                    // null/absent → aucun choix
            String picked = title.asText().trim();
            return candidates.contains(picked) ? picked : null;     // doit être un vrai candidat
        } catch (Exception e) {
            return null;
        }
    }

    /** Client unique, paresseux (jamais construit si la clé manque). */
    private static synchronized AnthropicClient client() {
        if (client == null) {
            client = AnthropicOkHttpClient.builder()
                    .fromEnv()
                    .timeout(Duration.ofSeconds(30))
                    .build();
        }
        return client;
    }
}
