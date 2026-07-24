package veille;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source B (CLAUDE.md) — historique de saison d'un joueur, depuis sa page
 * Wikipédia. Deux grains :
 * <ul>
 *   <li>DÉTERMINISTE : le classement mondial, lu dans l'infobox
 *       ({@code current_ranking}) → {@code players[].rank} ;</li>
 *   <li>LLM : l'historique de la saison est en PROSE — trop libre pour des règles.
 *       On extrait la section pertinente et on la passe à Haiku ({@link LlmNet})
 *       → {@code players[].lines}.</li>
 * </ul>
 *
 * CACHE PAR RÉVISION (économie de tokens, cf. CLAUDE.md) : avant tout appel
 * Haiku, on lit l'identifiant de révision de la page ({@link Wiki#revisionId}).
 * Si la révision est identique à celle du cache {@code collector/cache/<slug>.json},
 * on réutilise rank + lines SANS toucher au réseau ni à Haiku. Le cache est
 * committé par le workflow (runners jetables). Échec gracieux : sans clé API, sur
 * page absente ou erreur, on garde la dernière bonne valeur du cache.
 *
 * Fonctions pures testées : {@link #parseCurrentRanking}, {@link #cleanWikitext},
 * {@link #seasonText}, {@link #cacheToJson}, {@link #cacheFromJson}. Seul
 * {@link #resolve} fait du réseau.
 */
final class WikiPlayer {

    private WikiPlayer() {}

    /** Un joueur suivi : nom d'affichage (FR), titre d'article Wikipédia, slug de cache. */
    record Roster(String display, String title, String slug) {}

    /**
     * Contenu persisté du cache d'un joueur (fichier committé, un par joueur).
     * {@code formatVersion} versionne la LOGIQUE d'extraction : une révision
     * Wikipédia inchangée ne suffit pas à réutiliser le cache si le format a évolué
     * (découpage par saison, filtre d'année, médailles…) — sinon on servirait des
     * lignes périmées. Bump {@link #EXTRACTION_VERSION} à chaque changement.
     */
    record PlayerCache(int formatVersion, long wikiRevisionId, String extractedAt,
                       String rank, List<DataJson.LineJson> lines) {}

    /** Version de la logique d'extraction (cf. {@link PlayerCache}). Bump = ré-extraction.
     *  v3 : dates issues du calendrier BWF (plus jamais de date inventée par Haiku). */
    static final int EXTRACTION_VERSION = 3;

    static final Path CACHE_DIR = Path.of("collector", "cache");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------
    //  Orchestration (réseau)
    // ------------------------------------------------------------

    /**
     * Construit l'entrée {@code players[]} d'un joueur. Séquence :
     * <ol>
     *   <li>lit la révision Wikipédia ; identique au cache → renvoie le cache
     *       (zéro réseau supplémentaire, zéro token) ;</li>
     *   <li>sinon lit le wikitexte : rank (infobox) + prose de saison ;</li>
     *   <li>Haiku extrait les lignes ; on persiste {révision, rank, lines}.</li>
     * </ol>
     * Sans clé API : rank frais + lignes du cache, sans persister (on retentera
     * l'extraction quand une clé sera présente). Toute erreur réseau → cache.
     */
    static DataJson.PlayerJson resolve(Roster r, int year, List<Tournament> calendar) {
        PlayerCache cache = loadCache(r.slug());
        long rev;
        try {
            rev = Wiki.revisionId(r.title());
        } catch (Exception e) {
            System.err.println("Wikipédia (révision « " + r.display() + " ») KO — cache : " + e);
            return fromCache(r, cache);
        }

        if (cache != null && rev > 0 && cache.wikiRevisionId() == rev
                && cache.formatVersion() == EXTRACTION_VERSION) {
            System.out.println("Wikipédia : « " + r.display() + " » révision inchangée ("
                    + rev + ") — cache réutilisé, pas d'appel Haiku");
            return new DataJson.PlayerJson(r.display(), cache.rank(), cache.lines());
        }
        if (cache != null && cache.formatVersion() != EXTRACTION_VERSION) {
            System.out.println("cache joueur périmé (format v" + cache.formatVersion()
                    + " ≠ v" + EXTRACTION_VERSION + ") — ré-extraction de « " + r.display() + " »");
        }

        String wt;
        try {
            wt = Wiki.wikitext(r.title());
        } catch (Exception e) {
            System.err.println("Wikipédia (page « " + r.display() + " ») KO — cache : " + e);
            return fromCache(r, cache);
        }
        if (wt == null) return fromCache(r, cache);

        String rank = parseCurrentRanking(wt);   // déterministe (jamais le LLM)

        if (!LlmNet.enabled()) {
            // Pas de clé : on saute l'affinage prose. Rank frais + dernières lignes
            // connues, SANS persister (la révision reste « à extraire » pour un run
            // futur muni d'une clé).
            System.out.println("filet LLM inerte (pas de clé) — « " + r.display()
                    + " » : rank déterministe, lignes du cache conservées");
            return new DataJson.PlayerJson(r.display(), rank,
                    cache != null ? cache.lines() : List.of());
        }

        List<DataJson.LineJson> lines =
                LlmNet.extractSeason(r.display(), year, seasonText(wt, year), calendar);
        if (lines.isEmpty()) {
            // Haiku indisponible / réponse vide : on garde la dernière bonne valeur
            // et on NE fige PAS cette révision (retentée au prochain run).
            System.out.println("filet LLM sans résultat — « " + r.display()
                    + " » : lignes du cache conservées");
            return new DataJson.PlayerJson(r.display(), rank,
                    cache != null ? cache.lines() : List.of());
        }

        saveCache(r.slug(), new PlayerCache(EXTRACTION_VERSION, rev, Instant.now().toString(), rank, lines));
        return new DataJson.PlayerJson(r.display(), rank, lines);
    }

    /** Replie sur le cache (ou vide) quand le réseau lâche avant l'extraction. */
    private static DataJson.PlayerJson fromCache(Roster r, PlayerCache cache) {
        return cache != null
                ? new DataJson.PlayerJson(r.display(), cache.rank(), cache.lines())
                : new DataJson.PlayerJson(r.display(), null, List.of());
    }

    // ------------------------------------------------------------
    //  Fonctions pures
    // ------------------------------------------------------------

    private static final Pattern RANKING = Pattern.compile(
            "\\|\\s*current_ranking\\s*=\\s*([^\\n]*)");

    /**
     * Lit le classement mondial dans l'infobox ({@code | current_ranking = 7} ou
     * {@code | current_ranking = 5 (MS, ...)}) → « #N mondial ». Le PREMIER entier
     * de la valeur est le simple (le double suit, après un {@code <br />}).
     * {@code null} si le champ manque (on ne devine jamais).
     */
    static String parseCurrentRanking(String wikitext) {
        if (wikitext == null) return null;
        Matcher m = RANKING.matcher(wikitext);
        if (m.find()) {
            Matcher d = Pattern.compile("\\d+").matcher(m.group(1));
            if (d.find()) return "#" + d.group() + " mondial";
        }
        return null;
    }

    /** Un an décrit un début de propos si l'année apparaît dans les ~40 premiers
     *  caractères d'un paragraphe (« In 2026, … », « The 2026 season … »). */
    private static final int SEASON_OPEN_THRESHOLD = 40;
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");

    /**
     * Extrait la prose de la SAISON visée. On DÉCOUPE par saison, jamais par simple
     * mention : un paragraphe qui raconte 2025 mais cite « 2026 » en passant ne doit
     * PAS être retenu (sinon Haiku extrait deux saisons — le bug est en amont du LLM).
     *
     * Méthode : on isole « Career » (jusqu'au prochain titre de niveau 2), on nettoie
     * le wikitexte (retirer les {@code <ref>} AVANT toute détection d'année, sinon
     * leurs dates d'accès polluent), puis on parcourt les blocs en suivant une
     * « saison active » — posée par un sous-titre d'année ({@code === 2026 ===}) ou
     * par un paragraphe qui OUVRE sur l'année. On ne garde que les blocs de l'année
     * visée. {@code null} si rien pour l'année.
     */
    static String seasonText(String wikitext, int year) {
        if (wikitext == null) return null;
        Matcher h = Pattern.compile("(?im)^==+[^=\\n]*career[^=\\n]*==+\\s*$").matcher(wikitext);
        String body;
        if (h.find()) {
            int start = h.end();
            Matcher next = Pattern.compile("\\n==[^=]").matcher(wikitext);
            body = wikitext.substring(start, next.find(start) ? next.start() : wikitext.length());
        } else {
            body = wikitext;
        }
        String clean = cleanWikitext(body);
        int active = 0;
        StringBuilder sb = new StringBuilder();
        for (String block : clean.split("\\n\\n+")) {
            String b = block.trim();
            if (b.isEmpty()) continue;
            Integer declared = declaredYear(b);
            if (declared != null) active = declared;
            // Les sous-titres (=== 2026 ===) posent la saison mais ne sont pas de la prose.
            if (active == year && !b.startsWith("=")) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(b);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Saison « déclarée » par un bloc : l'année d'un sous-titre ({@code === 2026 ===}),
     * ou l'année qui OUVRE un paragraphe (dans les premiers caractères). {@code null}
     * si le bloc ne fait que citer une année plus loin (mention en passant).
     */
    static Integer declaredYear(String block) {
        Matcher m = YEAR.matcher(block);
        if (!m.find()) return null;
        if (block.startsWith("=")) return Integer.parseInt(m.group());   // sous-titre d'année
        return m.start() <= SEASON_OPEN_THRESHOLD ? Integer.parseInt(m.group()) : null;
    }

    /**
     * Débarrasse un wikitexte de son balisage pour n'en garder que la prose :
     * balises {@code <ref>} (avec dates d'accès parasites), templates
     * {@code {{…}}} (imbriqués compris), liens {@code [[cible|texte]]} → texte,
     * gras/italique. Les sauts de ligne (frontières de paragraphe) sont préservés.
     */
    static String cleanWikitext(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?is)<ref[^>]*/>", "");
        s = s.replaceAll("(?is)<ref[^>]*>.*?</ref>", "");
        String prev;                                   // templates imbriqués : itérer
        do {
            prev = s;
            s = s.replaceAll("\\{\\{[^{}]*\\}\\}", "");
        } while (!s.equals(prev));
        s = s.replaceAll("\\[\\[[^\\]|]*\\|([^\\]]*)\\]\\]", "$1");
        s = s.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1");
        s = s.replace("'''", "").replace("''", "");
        s = s.replaceAll("[ \\t]+", " ");
        return s.trim();
    }

    // ------------------------------------------------------------
    //  Cache par joueur (fichier committé)
    // ------------------------------------------------------------

    /** Sérialise le cache d'un joueur (JSON lisible et diffable au commit). */
    static String cacheToJson(PlayerCache cache) throws Exception {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cache);
    }

    /** Relit le cache d'un joueur. Fichier corrompu/incomplet → {@code null},
     *  jamais d'exception : au pire on rappelle Wikipédia + Haiku. */
    static PlayerCache cacheFromJson(String json) {
        try {
            PlayerCache c = MAPPER.readValue(json, PlayerCache.class);
            return c != null && c.lines() != null ? c : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path cacheFile(String slug) {
        return CACHE_DIR.resolve(slug + ".json");
    }

    private static PlayerCache loadCache(String slug) {
        Path f = cacheFile(slug);
        if (!Files.exists(f)) return null;
        try {
            return cacheFromJson(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("cache joueur illisible (" + f + ") : " + e);
            return null;
        }
    }

    private static void saveCache(String slug, PlayerCache cache) {
        try {
            Files.createDirectories(CACHE_DIR);
            Files.writeString(cacheFile(slug), cacheToJson(cache), StandardCharsets.UTF_8);
            System.out.println("cache joueur écrit (" + cacheFile(slug) + ", révision "
                    + cache.wikiRevisionId() + ")");
        } catch (Exception e) {
            System.err.println("cache joueur : échec d'écriture (" + slug
                    + ") — extraction refaite au prochain run : " + e);
        }
    }
}
