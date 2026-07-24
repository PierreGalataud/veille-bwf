package veille;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Accès à l'API MediaWiki de Wikipédia anglophone (cf. carte des sources
 * CLAUDE.md) : recherche d'article, identifiant de révision, wikitexte. C'est
 * la SEULE couche réseau des deux sources Wikipédia :
 * <ul>
 *   <li>{@link WikiTournament} — statut français d'un tournoi (draws) ;</li>
 *   <li>{@link WikiPlayer} — historique de saison d'un joueur.</li>
 * </ul>
 *
 * On ne DEVINE jamais l'URL d'un article (le suffixe « (badminton) » est
 * irrégulier) : on passe par {@link #search}. Requêtes espacées (politesse) et
 * User-Agent explicite via {@link Http}. Toute méthode réseau lève sur échec —
 * les appelants dégradent gracieusement (statut inconnu / dernière bonne valeur).
 * Aucune méthode ici n'est testée unitairement (réseau uniquement, cf. CLAUDE.md).
 */
final class Wiki {

    private Wiki() {}

    private static final String API = "https://en.wikipedia.org/w/api.php";
    /** Pause de politesse entre deux requêtes à l'API (cf. garde-fous). */
    private static final long THROTTLE_MS = 400;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static long lastFetchMs = 0;

    /** Point de passage unique : applique la pause commune puis lit le corps JSON. */
    private static JsonNode get(String query) throws Exception {
        long wait = THROTTLE_MS - (System.currentTimeMillis() - lastFetchMs);
        if (wait > 0) Thread.sleep(wait);
        try {
            return MAPPER.readTree(Http.getString(API + "?" + query + "&format=json"));
        } finally {
            lastFetchMs = System.currentTimeMillis();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Recherche plein-texte : renvoie les titres d'articles candidats, par
     * pertinence décroissante (l'appelant filtre par année + jetons de nom).
     */
    static List<String> search(String query) throws Exception {
        JsonNode root = get("action=query&list=search&srlimit=8&srsearch=" + enc(query));
        List<String> titles = new ArrayList<>();
        for (JsonNode hit : root.path("query").path("search")) {
            String title = hit.path("title").asText(null);
            if (title != null && !title.isBlank()) titles.add(title);
        }
        return titles;
    }

    /**
     * Identifiant de la dernière révision d'un article (clé du cache par joueur,
     * cf. {@link WikiPlayer}). {@code -1} si l'article n'existe pas / réponse
     * inattendue.
     */
    static long revisionId(String title) throws Exception {
        JsonNode root = get("action=query&prop=revisions&rvprop=ids&rvlimit=1&titles=" + enc(title));
        for (JsonNode page : root.path("query").path("pages")) {
            JsonNode rev = page.path("revisions").path(0).path("revid");
            if (rev.isNumber()) return rev.asLong();
        }
        return -1;
    }

    /** Wikitexte brut d'un article ({@code action=parse}). {@code null} si absent. */
    static String wikitext(String title) throws Exception {
        JsonNode root = get("action=parse&prop=wikitext&page=" + enc(title));
        JsonNode wt = root.path("parse").path("wikitext").path("*");
        return wt.isTextual() ? wt.asText() : null;
    }
}
