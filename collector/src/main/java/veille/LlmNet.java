package veille;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    static List<DataJson.LineJson> extractSeason(String player, int year, String prose) {
        if (!enabled() || prose == null || prose.isBlank()) return List.of();
        try {
            return ask(player, year, prose);
        } catch (Exception e) {
            System.err.println("filet LLM KO (" + player + ") — dernière valeur conservée : " + e);
            return List.of();
        }
    }

    /** Seule méthode réseau du filet (non testée unitairement, cf. CLAUDE.md). */
    private static List<DataJson.LineJson> ask(String player, int year, String prose) {
        System.out.println("filet LLM → Haiku : historique " + year + " d'" + player
                + " (" + prose.length() + " car.)");

        String user = "Voici un extrait Wikipédia sur " + player + " :\n\n" + prose + "\n\n"
                + "N'extrais QUE les résultats de la saison " + year + ". IGNORE toute mention "
                + "d'une autre saison (année ≠ " + year + "), même citée en passant.\n"
                + "Du PLUS RÉCENT au plus ancien, réponds UNIQUEMENT avec un tableau JSON, "
                + "format exact :\n"
                + "[{\"year\":" + year + ",\"date\":\"<mois en français, avec le jour si connu, "
                + "ex. « 31 mai » ou « juillet »>\","
                + "\"tournament\":\"<nom du tournoi en français, ex. Open du Japon>\","
                + "\"stage\":\"<résultat en français, ex. Vainqueur, Finaliste, Demi-finaliste, "
                + "1/4 de finale, Éliminé au 1er tour>\",\"tone\":\"win|out|null\"}]\n"
                + "\"year\" : l'année du résultat (doit valoir " + year + ").\n"
                + "\"tone\" : \"win\" = remporte le tournoi ; \"out\" = éliminé ; "
                + "null = en cours ou indéterminé.\n"
                + "Traduis les noms de tournois en français. Maximum " + MAX_LINES
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
        return parseSeasonLines(text.toString(), year);
    }

    /** Entrée brute avant tri/étiquetage (filet déterministe par-dessus Haiku). */
    private record Raw(String date, String tournament, String stage, String tone) {}

    /**
     * Transforme la réponse de Haiku en {@code lines} pour la saison {@code year}.
     * Filet DÉTERMINISTIQUE par-dessus le LLM :
     * <ul>
     *   <li>toute entrée dont le champ {@code year} ≠ {@code year} visé est REJETÉE
     *       (Haiku remonte parfois une autre saison) ;</li>
     *   <li>tri chronologique DÉCROISSANT par date (« octobre » ne passe jamais
     *       avant « mai ») — on ne se fie pas à l'ordre rendu par Haiku ;</li>
     *   <li>{@code medal} calculée depuis le stade ({@link #medalFor}).</li>
     * </ul>
     * Tolère prose/clôtures Markdown (premier {@code [} … dernier {@code ]}). Un
     * tone hors contrat → null ; une entrée sans tournoi ni stade est écartée. Tout
     * JSON invalide → liste vide, jamais d'exception.
     */
    static List<DataJson.LineJson> parseSeasonLines(String raw, int year) {
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
                rows.add(new Raw(txt(n, "date"), tournament, stage, tone));
            }
        } catch (Exception e) {
            return List.of();
        }
        // Tri chronologique décroissant (stable) : le plus récent en premier.
        rows.sort((x, z) -> Integer.compare(sortKey(z.date()), sortKey(x.date())));

        List<DataJson.LineJson> out = new ArrayList<>();
        for (Raw r : rows) {
            if (out.size() >= MAX_LINES) break;
            String label = out.isEmpty() ? "Dernier" : "Puis";
            String value = r.tournament() != null && r.stage() != null
                    ? r.tournament() + " · " + r.stage()
                    : (r.tournament() != null ? r.tournament() : r.stage());
            out.add(new DataJson.LineJson(label, r.date(), r.tournament(), r.stage(),
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

    /**
     * Clé de tri d'une date FR (« 31 mai », « juillet ») : {@code mois*100 + jour}.
     * Mois inconnu → 0 (rejeté en fin de liste). Ne confond pas une année (4
     * chiffres) avec un jour (le motif {@code \b\d{1,2}\b} ne matche pas « 2026 »).
     */
    static int sortKey(String date) {
        if (date == null) return 0;
        String low = TextUtil.stripAccents(date.toLowerCase(java.util.Locale.ROOT));
        int month = 0;
        for (var e : FrDates.FR_MONTH_NUM.entrySet()) {
            if (low.contains(TextUtil.stripAccents(e.getKey()))) { month = e.getValue(); break; }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{1,2})\\b").matcher(low);
        int day = m.find() ? Integer.parseInt(m.group(1)) : 0;
        return month * 100 + day;
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
