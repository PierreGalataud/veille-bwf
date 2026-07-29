package veille;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Fenêtre temporelle du tableau de bord : « aujourd'hui » et les bornes qui
 * décident si un tournoi est EN COURS, À VENIR ou PASSÉ. Un seul endroit pour
 * ces deux règles, partagé par tout le collecteur (et, à venir, par la Dépêche).
 *
 * <h2>1. Référence de temps : Europe/Paris, jamais UTC</h2>
 * Le collecteur tourne sous GitHub Actions, dont l'horloge est en UTC, mais le
 * lecteur est en France : la journée affichée doit être SA journée. En été Paris
 * est à UTC+2 — un run à 22 h 30 UTC est déjà le lendemain à Paris. Calculer
 * « aujourd'hui » en UTC brut décalait donc la fenêtre (et le « Jour 4 / 6 », et
 * le libellé de semaine) de jusqu'à deux heures par rapport à ce que voit
 * l'utilisateur. On ancre tout sur {@link #ZONE}, y compris l'heure d'hiver
 * (UTC+1) : {@code ZoneId} gère la bascule, on ne code JAMAIS un offset en dur.
 *
 * <h2>2. Bornes INCLUSIVES des deux côtés</h2>
 * Un tournoi est en cours pour {@code start <= today <= end} : le jour de la
 * finale (dernier jour, souvent un dimanche) il reste dans {@code current} toute
 * la journée. Il n'en sort qu'à J+1 — c'est la même borne qui le fait basculer
 * côté « passé » / « semaine dernière » ({@link #isPast}). Aucune comparaison
 * stricte ici : {@code isAfter}/{@code isBefore} sont toujours niés, jamais
 * utilisés tels quels sur une borne.
 */
final class Window {

    private Window() {}

    /** Fuseau de référence : la journée telle que la voit le lecteur français. */
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** « Aujourd'hui » à Paris (horloge système). */
    static LocalDate today() {
        return today(Instant.now());
    }

    /** « Aujourd'hui » à Paris pour un instant donné — testable, sans horloge. */
    static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    /** En cours : {@code start <= today <= end} (bornes INCLUSIVES, cf. §2). */
    static boolean isCurrent(Tournament t, LocalDate today) {
        return !t.start().isAfter(today) && !t.end().isBefore(today);
    }

    /** À venir : commence APRÈS aujourd'hui (le jour d'ouverture est déjà « en cours »). */
    static boolean isUpcoming(Tournament t, LocalDate today) {
        return t.start().isAfter(today);
    }

    /**
     * Passé : fini AVANT aujourd'hui, donc au plus tôt à J+1 (le jour de la
     * finale n'est pas du passé). Seuil de bascule vers « semaine dernière ».
     */
    static boolean isPast(Tournament t, LocalDate today) {
        return t.end().isBefore(today);
    }

    /** Démarre dans les {@code days} prochains jours, borne INCLUSE (J+days compte). */
    static boolean startsWithin(Tournament t, LocalDate today, long days) {
        return !t.start().isAfter(today.plusDays(days));
    }
}
