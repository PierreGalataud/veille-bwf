package veille;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Utilitaires texte partagés : normalisation, jetons de noms, recherche de mots. */
final class TextUtil {

    private TextUtil() {}

    /**
     * Mots à ignorer pour la réconciliation des noms de tournois : articles,
     * mots génériques et marques de sponsors. On garde les jetons géographiques
     * et distinctifs (australian, canada, china, finals…).
     */
    private static final Set<String> NAME_STOPWORDS = new HashSet<>(List.of(
            "open", "de", "du", "des", "d", "le", "la", "les", "l", "et",
            "badminton", "tournament", "championship", "championships",
            "super", "1000", "750", "500", "300", "100", "2026", "2027",
            "ltd", "co", "group", "powered", "by",
            // sponsors fréquents
            "hsbc", "yonex", "victor", "li", "ning", "lining", "daihatsu",
            "sathio", "sands", "petronas", "toyota", "perodua", "sandschina"));

    /**
     * Jetons trop génériques pour apparier un tournoi à eux SEULS (partagés par
     * plusieurs tournois sans lien : Orléans/Korea/Kumamoto Masters…). Gardés dans
     * les jetons (utiles combinés à d'autres), mais ignorés par le repli « nom
     * seul » de {@link PlayerResults#isOngoing}.
     */
    private static final Set<String> WEAK_TOKENS = Set.of("masters");

    static Set<String> minusWeak(Set<String> tokens) {
        Set<String> out = new HashSet<>(tokens);
        out.removeAll(WEAK_TOKENS);
        return out;
    }

    /** Minuscules + accents retirés + apostrophes supprimées (matching robuste). */
    static String norm(String s) {
        return stripAccents(s.toLowerCase(Locale.ROOT)).replaceAll("['`’]", "");
    }

    static String stripAccents(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    /** Jetons normalisés et significatifs d'un nom (accents et stopwords retirés). */
    static Set<String> nameTokens(String name) {
        Set<String> tokens = new HashSet<>();
        for (String raw : stripAccents(name.toLowerCase(Locale.ROOT)).split("[^a-z0-9]+")) {
            if (raw.length() >= 2 && !NAME_STOPWORDS.contains(raw)) tokens.add(raw);
        }
        return tokens;
    }

    /** Nombre de jetons communs, en tolérant les variantes FR/EN par préfixe. */
    static int sharedTokens(Set<String> a, Set<String> b) {
        int shared = 0;
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y) || (x.length() >= 5 && y.length() >= 5
                        && (x.startsWith(y.substring(0, 5)) || y.startsWith(x.substring(0, 5))))) {
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    static boolean containsAny(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    /** Présence de {@code word} comme MOT ENTIER (bornes non alphanumériques) dans
     *  un texte normalisé — « toma » ne matche pas « automatique », mais matche
     *  bien « toma-junior-popov » dans un slug d'URL (le tiret borne le mot). */
    static boolean hasWord(String hay, String word) {
        int i = -1;
        while ((i = hay.indexOf(word, i + 1)) >= 0) {
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            int j = i + word.length();
            boolean rightOk = j >= hay.length() || !Character.isLetterOrDigit(hay.charAt(j));
            if (leftOk && rightOk) return true;
        }
        return false;
    }

    static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
