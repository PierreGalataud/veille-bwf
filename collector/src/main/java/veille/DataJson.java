package veille;

import java.util.List;

/**
 * LE CONTRAT {@code public/data.json} (cf. CLAUDE.md), sous forme de records
 * sérialisés tels quels par Jackson : l'ordre des composants = l'ordre des clés
 * JSON, les {@code null} sont émis. Toute modification ici touche AUSSI
 * {@code src/App.jsx}, dans le même commit.
 */
record DataJson(
        String generatedAt,
        String weekLabel,
        List<CurrentJson> current,
        List<PlayerJson> players,
        List<UpcomingJson> upcoming) {

    /** Tournoi de la semaine courante. {@code tier} ∈ wtf|1000|750|500|300. */
    record CurrentJson(
            String name, String tier, String location, String dates,
            String prize, String timezone, String dayLabel,
            List<SeedJson> seeds, FrenchStatusJson frenchStatus) {}

    /** Tête de série (hors périmètre du collecteur actuel : liste vide). */
    record SeedJson(String rank, String name) {}

    /** {@code present} à TROIS états : true / false / null (inconnu) — cf. CLAUDE.md. */
    record FrenchStatusJson(Boolean present, String title, String note, boolean confirm) {}

    record PlayerJson(String name, String rank, List<LineJson> lines) {}

    /**
     * {@code tone} ∈ win|out|null. {@code medal} = médaille d'affichage calculée
     * DÉTERMINISTIQUEMENT depuis le stade (🥇 vainqueur, 🥈 finaliste, 🥉 demi,
     * ⚫ éliminé avant les demies, 🎯 en lice) — jamais devinée par le front ni par
     * Haiku. {@code label} (« Dernier »/« Puis ») conservé pour compat mais plus
     * affiché. {@code value} = repli d'affichage non structuré.
     */
    record LineJson(String label, String date, String tournament,
                    String stage, String medal, String tone, String value) {}

    record UpcomingJson(String dates, String name, String tier, String french) {}
}
