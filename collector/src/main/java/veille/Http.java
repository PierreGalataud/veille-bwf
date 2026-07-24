package veille;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Accès HTTP commun : User-Agent explicite et timeout (cf. garde-fous CLAUDE.md). */
final class Http {

    private Http() {}

    private static final String USER_AGENT =
            "veille-bwf/1.0 (projet personnel; +https://github.com/PierreGalataud/veille-bwf)";
    private static final int TIMEOUT_MS = 20000;

    static Document fetch(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0) // le calendrier BWF fait ~1,4 Mo : ne pas tronquer (défaut = 1 Mo)
                .get();
    }

    /**
     * Récupère un corps de réponse BRUT (non HTML) — pour l'API MediaWiki qui
     * répond en JSON. {@code ignoreContentType} évite que Jsoup rejette un
     * {@code application/json}, {@code maxBodySize(0)} ne tronque pas les gros
     * wikitextes.
     */
    static String getString(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .ignoreContentType(true)
                .maxBodySize(0)
                .execute()
                .body();
    }
}
