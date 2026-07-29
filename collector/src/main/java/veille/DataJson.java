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

    /**
     * Tournoi en tête d'affiche. {@code tier} ∈ wtf|1000|750|500|300.
     *
     * <p>{@code status} ∈ {@code "en_cours"} | {@code "termine"} (cf.
     * {@link Window#featured}) : un tournoi reste en tête d'affiche APRÈS sa
     * finale, jusqu'à ce qu'un autre tournoi World Tour démarre.
     *
     * <p>{@code champions} n'est renseigné qu'à l'état {@code termine}, et
     * seulement si les 5 disciplines sont publiées sur Wikipédia (tout ou rien) ;
     * {@code null} sinon → le front affiche « résultats en attente », jamais un
     * palmarès partiel.
     */
    record CurrentJson(
            String name, String tier, String location, String dates,
            String prize, String timezone, String dayLabel, String status,
            ChampionsJson champions,
            List<SeedJson> seeds, FrenchStatusJson frenchStatus) {}

    /** Les 5 vainqueurs d'un tournoi terminé (toutes nationalités, pas que les Bleus). */
    record ChampionsJson(ChampionJson ms, ChampionJson ws, ChampionJson md,
                         ChampionJson wd, ChampionJson xd) {}

    /** Vainqueur d'une discipline : nom (paire jointe par « / »), pays nullable. */
    record ChampionJson(String name, String country) {}

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
