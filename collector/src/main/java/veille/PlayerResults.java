package veille;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrateur de {@code players[]} — le suivi individuel des Français, désormais
 * fondé sur Wikipédia (cf. carte des sources CLAUDE.md). Périmètre RÉDUIT à deux
 * joueurs : Alex Lanier et Christo Popov (Toma Junior Popov et le double
 * Delrue/Gicquel ne sont plus suivis individuellement).
 *
 * Tout le travail — classement d'infobox (déterministe) + historique de saison
 * (Haiku sur prose, caché par révision) — est délégué à {@link WikiPlayer}. Échec
 * gracieux : un joueur sans rank ni ligne n'est pas émis ; la liste peut être vide
 * sans casser data.json.
 */
final class PlayerResults {

    private PlayerResults() {}

    /** Les deux joueurs suivis : {nom affiché, titre d'article Wikipédia, slug de cache}. */
    private static final List<WikiPlayer.Roster> ROSTER = List.of(
            new WikiPlayer.Roster("Alex Lanier", "Alex Lanier", "alex-lanier"),
            new WikiPlayer.Roster("Christo Popov", "Christo Popov", "christo-popov"));

    /**
     * Construit {@code players[]} pour la saison {@code year}. Le {@code calendar}
     * BWF (tous les tournois de la saison) sert à DATER déterministiquement les
     * lignes (cf. {@link LlmNet#matchDates}). Un joueur n'est retenu que s'il
     * apporte quelque chose (un rank ou au moins une ligne).
     */
    static List<DataJson.PlayerJson> buildPlayers(int year, List<Tournament> calendar) {
        List<DataJson.PlayerJson> out = new ArrayList<>();
        for (WikiPlayer.Roster r : ROSTER) {
            DataJson.PlayerJson p = WikiPlayer.resolve(r, year, calendar);
            if (p.rank() != null || !p.lines().isEmpty()) out.add(p);
        }
        return out;
    }
}
