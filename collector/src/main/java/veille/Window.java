package veille;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Fenêtre temporelle du tableau de bord : « aujourd'hui », les bornes qui
 * décident si un tournoi est EN COURS, À VENIR ou PASSÉ, et QUI occupe la tête
 * d'affiche ({@link #featured}). Un seul endroit pour ces règles, partagé par
 * tout le collecteur (et, à venir, par la Dépêche).
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
 *
 * <h2>3. Tête d'affiche : la fin d'un tournoi ne vide pas l'affiche</h2>
 * {@link #featured} choisit ce qu'on montre : les tournois en cours, ou — à
 * défaut — le dernier tournoi terminé, tant qu'aucun autre n'a démarré.
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

    // ------------------------------------------------------------
    //  Tête d'affiche : jamais de « aucun tournoi » s'il y a un tournoi à montrer
    // ------------------------------------------------------------

    /** États d'affichage du contrat ({@code current[].status}). */
    static final String EN_COURS = "en_cours";
    static final String TERMINE = "termine";

    /** Un tournoi en tête d'affiche + son état d'affichage. */
    record Featured(Tournament tournament, String status) {}

    /**
     * Qui occupe la tête d'affiche aujourd'hui — c'est-à-dire {@code current[]}.
     *
     * <p>RÈGLE : un tournoi ne quitte la tête d'affiche que lorsqu'un AUTRE
     * tournoi World Tour démarre, pas à sa date de fin. Tant que le calendrier
     * n'a rien ouvert depuis, le dernier tournoi reste affiché, à l'état
     * {@code termine} (avec ses champions) — le tableau de bord ne dit donc
     * jamais « aucun tournoi » alors qu'un tournoi récent peut être montré.
     *
     * <ol>
     *   <li>des tournois sont en cours ({@code start <= today <= end}) → ce sont
     *       eux, à l'état {@code en_cours}, tous (deux niveaux peuvent se
     *       chevaucher la même semaine) ;</li>
     *   <li>sinon, le tournoi le plus RÉCEMMENT DÉMARRÉ parmi les terminés, seul,
     *       à l'état {@code termine}. « Le plus récemment démarré » EST la règle
     *       « aucun tournoi n'a commencé après lui » ;</li>
     *   <li>calendrier sans aucun tournoi déjà commencé (début de saison) → liste
     *       vide, seul cas où le front affiche un état vide.</li>
     * </ol>
     * Dès qu'un nouveau tournoi passe {@code en_cours}, l'ancien sort d'ici (il
     * pourra alimenter la Dépêche « semaine dernière », cf. {@link #isPast}).
     */
    static List<Featured> featured(List<Tournament> all, LocalDate today) {
        if (all == null) return List.of();
        List<Featured> live = new ArrayList<>();
        for (Tournament t : all) {
            if (isCurrent(t, today)) live.add(new Featured(t, EN_COURS));
        }
        if (!live.isEmpty()) {
            live.sort((a, b) -> a.tournament().start().compareTo(b.tournament().start()));
            return live;
        }
        Tournament last = null;
        for (Tournament t : all) {
            if (isPast(t, today) && (last == null || startedAfter(t, last))) last = t;
        }
        return last == null ? List.of() : List.of(new Featured(last, TERMINE));
    }

    /** Départ le plus tardif ; à égalité, fin la plus tardive puis nom (ordre stable). */
    private static boolean startedAfter(Tournament t, Tournament ref) {
        int c = t.start().compareTo(ref.start());
        if (c != 0) return c > 0;
        c = t.end().compareTo(ref.end());
        if (c != 0) return c > 0;
        return t.name().compareTo(ref.name()) > 0;
    }
}
