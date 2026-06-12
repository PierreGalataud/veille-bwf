package veille;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filet LLM (étape A de CLAUDE.md) : appels Claude Haiku CIBLÉS, uniquement sur
 * les lignes que le déterministe a MARQUÉES incertaines (titres d'opposition
 * « X domine Y » → {@code tone: null} / « résultat à préciser », et sorties au
 * « stade non précisé »). Une fonction texte→texte, pas un agent : le LLM affine,
 * il ne remplace jamais le pipeline.
 *
 * Échec gracieux TOTAL : sans {@code ANTHROPIC_API_KEY}, ou si l'appel échoue,
 * ou si la réponse n'est pas le JSON attendu, la valeur déterministe est
 * conservée telle quelle — le collecteur produit exactement le même data.json
 * qu'avant. Le filet ne peut ni casser ni vider data.json.
 *
 * Traçabilité : chaque cas envoyé à Haiku et chaque verdict appliqué (ou ignoré)
 * est loggé — on sait toujours si c'est la règle ou le filet qui a parlé.
 *
 * Seul {@link #ask} fait du réseau ; {@link #isUncertain}, {@link #parseVerdicts}
 * et {@link #applyVerdict} sont des fonctions pures, couvertes par les tests.
 */
final class LlmNet {

    private LlmNet() {}

    private static final String MODEL = "claude-haiku-4-5";
    private static final long MAX_TOKENS = 300;
    private static final String SYSTEM =
            "Tu classes des résultats de badminton. Réponds UNIQUEMENT en JSON, aucune prose.";
    /** Stade des lignes d'opposition indécidables (cf. PlayerResults.toLine). */
    private static final String STAGE_DISPUTED = "résultat à préciser";
    /** Marqueur des sorties dont le stade s'est perdu à l'agrégation. */
    private static final String STAGE_UNSPECIFIED = "stade non précisé";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Cache des verdicts PERSISTÉ entre les runs (fichier committé par le
     * workflow, comme data.json). Les titres d'equipe-france sont immuables :
     * un (tournoi, titres) déjà vu ne repart JAMAIS vers Haiku — zéro token
     * inutile, et data.json reste stable à l'octet près d'un run à l'autre
     * (Haiku reformule sinon ses réponses, d'où commits + rebuilds parasites).
     * « Lanier domine Toma » concerne deux joueurs mais ne coûte qu'une requête.
     */
    static final Path CACHE_FILE = Path.of("collector", "llm-cache.json");
    private static Map<String, List<Verdict>> cache;
    private static AnthropicClient client;

    /** Verdict de Haiku pour un joueur : tone ∈ win|out|null, stage libre (FR). */
    record Verdict(String player, String tone, String stage) {}

    /** Entrée du fichier de cache — format lisible et diffable au commit. */
    record CacheEntry(String tournament, List<String> titles, List<Verdict> verdicts) {}

    /** La clé API est-elle disponible ? Sans elle, le filet est inerte. */
    static boolean enabled() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    /**
     * Une ligne est « marquée incertaine » par le déterministe si :
     * opposition indécidable (tone null + « résultat à préciser »), ou sortie au
     * stade non précisé. Les lignes « en cours » (tone null aussi) ne sont PAS
     * incertaines : leur tone null est un état normal, pas une règle qui sèche.
     */
    static boolean isUncertain(DataJson.LineJson line) {
        if (line == null || line.stage() == null) return false;
        if (line.tone() == null && STAGE_DISPUTED.equals(line.stage())) return true;
        return line.stage().contains(STAGE_UNSPECIFIED);
    }

    /**
     * Affine une ligne incertaine via Haiku. Point d'entrée unique du filet :
     * ne fait RIEN (ligne rendue telle quelle) si la ligne n'est pas marquée,
     * si la clé API manque, ou si quoi que ce soit échoue en route.
     */
    static DataJson.LineJson refine(String playerName, DataJson.LineJson line, List<String> titles) {
        if (!isUncertain(line) || titles.isEmpty() || !enabled()) return line;
        String key = cacheKey(line.tournament(), titles);
        try {
            List<Verdict> verdicts = cache().get(key);
            if (verdicts == null) {
                verdicts = ask(line.tournament(), titles);
                cache().put(key, verdicts);
                // Les réponses vides ne se persistent pas : probable raté de
                // formatage de Haiku, on retentera au prochain run.
                if (!verdicts.isEmpty()) saveCache();
            }
            DataJson.LineJson refined = applyVerdict(line, verdicts, playerName);
            if (refined != line) {
                System.out.println("filet LLM ✓ " + playerName + " · " + line.tournament()
                        + " : tone " + line.tone() + " → " + refined.tone()
                        + ", stage « " + line.stage() + " » → « " + refined.stage() + " »");
            } else {
                System.out.println("filet LLM — " + playerName + " · " + line.tournament()
                        + " : pas de verdict exploitable, valeur déterministe conservée");
            }
            return refined;
        } catch (Exception e) {
            // Échec gracieux : on ne retente pas ce cas DANS CE RUN (entrée vide
            // en mémoire, jamais persistée) et la valeur déterministe reste.
            cache().putIfAbsent(key, List.of());
            System.err.println("filet LLM KO (" + playerName + " · " + line.tournament()
                    + ") — valeur déterministe conservée : " + e);
            return line;
        }
    }

    /** Seule méthode réseau du filet (non testée unitairement, cf. CLAUDE.md). */
    private static List<Verdict> ask(String tournament, List<String> titles) {
        System.out.println("filet LLM → Haiku [" + tournament + "] titres : " + titles);

        StringBuilder user = new StringBuilder();
        user.append("Titres du fil equipe-france concernant le tournoi « ")
                .append(tournament).append(" » :\n");
        for (String t : titles) user.append("- ").append(t).append('\n');
        user.append("Joueurs suivis : ").append(String.join(", ", List.of(
                        PlayerResults.LANIER, PlayerResults.CHRISTO,
                        PlayerResults.TOMA, PlayerResults.DOUBLE)))
                .append(".\n")
                .append("Pour chaque joueur suivi cité dans ces titres, donne son résultat ")
                .append("dans ce tournoi. Réponds UNIQUEMENT avec un tableau JSON, format exact :\n")
                .append("[{\"player\":\"<nom recopié exactement depuis la liste>\",")
                .append("\"tone\":\"win|out|null\",")
                .append("\"stage\":\"<stade en français, ex. Éliminé au 2e tour>\"}]\n")
                .append("\"tone\" : \"win\" = remporte le TOURNOI ; \"out\" = éliminé du tournoi ; ")
                .append("null = toujours en lice ou indéterminé (gagner UN MATCH n'est pas \"win\"). ")
                .append("Si les titres ne permettent pas de trancher, mets null — n'invente rien.");

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM)
                .addUserMessage(user.toString())
                .build();

        StringBuilder text = new StringBuilder();
        client().messages().create(params).content()
                .forEach(b -> b.text().ifPresent(tb -> text.append(tb.text())));
        return parseVerdicts(text.toString());
    }

    /**
     * Extrait les verdicts du texte renvoyé par Haiku. Tolère prose ou clôtures
     * Markdown autour du tableau (on isole le premier {@code [} … dernier
     * {@code ]}). Un tone hors contrat (autre que win/out/null) disqualifie le
     * verdict ; « null » (chaîne) et null (JSON) valent tous deux tone null.
     * Tout JSON invalide → liste vide, jamais d'exception.
     */
    static List<Verdict> parseVerdicts(String raw) {
        if (raw == null) return List.of();
        int a = raw.indexOf('[');
        int b = raw.lastIndexOf(']');
        if (a < 0 || b <= a) return List.of();
        List<Verdict> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(raw.substring(a, b + 1));
            if (!arr.isArray()) return List.of();
            for (JsonNode n : arr) {
                String player = n.path("player").asText(null);
                if (player == null || player.isBlank()) continue;
                JsonNode toneNode = n.path("tone");
                String tone = toneNode.isTextual() ? toneNode.asText() : null;
                if ("null".equals(tone) || (tone != null && tone.isBlank())) tone = null;
                if (tone != null && !"win".equals(tone) && !"out".equals(tone)) continue;
                JsonNode stageNode = n.path("stage");
                String stage = stageNode.isTextual() && !stageNode.asText().isBlank()
                        ? stageNode.asText().trim() : null;
                out.add(new Verdict(player, tone, stage));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * Applique à la ligne le verdict du joueur (appariement par nom normalisé
     * EXACT — le prompt impose de recopier les noms de la liste). Le filet ne
     * fait qu'AFFINER, il ne contredit jamais : sur une ligne dont le tone
     * déterministe est déjà posé (out « stade non précisé »), seul un verdict du
     * MÊME tone est accepté — il précise le stade. Un verdict win ou null y est
     * ignoré (vu en réel : Haiku renversait un out en win sur « s'imposent pour
     * leur retour », ou posait « En lice » sur une ligne éliminée). Sans verdict
     * exploitable, la ligne sort intacte.
     */
    static DataJson.LineJson applyVerdict(DataJson.LineJson line, List<Verdict> verdicts,
                                          String playerName) {
        String wanted = TextUtil.norm(playerName);
        for (Verdict v : verdicts) {
            if (!TextUtil.norm(v.player()).equals(wanted)) continue;
            if (line.tone() != null && !line.tone().equals(v.tone())) return line;
            String tone = v.tone() != null ? v.tone() : line.tone();
            String stage = v.stage() != null ? v.stage() : line.stage();
            if (java.util.Objects.equals(tone, line.tone()) && stage.equals(line.stage())) {
                return line;                      // rien de neuf
            }
            return new DataJson.LineJson(line.label(), line.date(), line.tournament(),
                    stage, tone, line.tournament() + " · " + stage);
        }
        return line;                              // joueur absent du verdict
    }

    /** Clé de cache d'un cas : le tournoi puis les titres, un par ligne. Les
     *  titres du fil sont mono-ligne, la clé est donc réversible (cf. cacheToJson). */
    static String cacheKey(String tournament, List<String> titles) {
        return tournament + "\n" + String.join("\n", titles);
    }

    /**
     * Sérialise le cache en JSON lisible (une entrée par cas). Les listes de
     * verdicts VIDES (échec d'appel ou réponse inexploitable) sont écartées :
     * elles ne valent rien à figer, on retentera au prochain run.
     */
    static String cacheToJson(Map<String, List<Verdict>> cache) throws Exception {
        List<CacheEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<Verdict>> e : cache.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String[] lines = e.getKey().split("\n", -1);
            entries.add(new CacheEntry(lines[0],
                    List.of(Arrays.copyOfRange(lines, 1, lines.length)), e.getValue()));
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
    }

    /** Relit le cache. Tout fichier corrompu ou incomplet → cache vide, jamais
     *  d'exception : au pire on rappelle Haiku, on ne casse rien. */
    static Map<String, List<Verdict>> cacheFromJson(String json) {
        Map<String, List<Verdict>> out = new LinkedHashMap<>();
        try {
            List<CacheEntry> entries =
                    MAPPER.readValue(json, new TypeReference<List<CacheEntry>>() {});
            for (CacheEntry e : entries) {
                if (e.tournament() == null || e.titles() == null
                        || e.verdicts() == null || e.verdicts().isEmpty()) continue;
                out.put(cacheKey(e.tournament(), e.titles()), e.verdicts());
            }
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** Cache unique, chargé paresseusement depuis {@link #CACHE_FILE} (jamais
     *  touché si aucune ligne incertaine ne se présente). */
    private static synchronized Map<String, List<Verdict>> cache() {
        if (cache == null) {
            cache = new LinkedHashMap<>();
            try {
                if (Files.exists(CACHE_FILE)) {
                    cache.putAll(cacheFromJson(Files.readString(CACHE_FILE, StandardCharsets.UTF_8)));
                    System.out.println("filet LLM : cache chargé, " + cache.size()
                            + " entrée(s) (" + CACHE_FILE + ")");
                }
            } catch (Exception e) {
                System.err.println("filet LLM : cache illisible, reparti à vide : " + e);
            }
        }
        return cache;
    }

    /** Persiste le cache après chaque nouveau verdict (fichier minuscule : seuls
     *  les cas incertains y entrent). Échec gracieux : un raté d'écriture coûte
     *  au pire des appels refaits au prochain run. */
    private static void saveCache() {
        try {
            if (CACHE_FILE.getParent() != null) Files.createDirectories(CACHE_FILE.getParent());
            Files.writeString(CACHE_FILE, cacheToJson(cache()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("filet LLM : échec d'écriture du cache "
                    + "(appels refaits au prochain run) : " + e);
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
