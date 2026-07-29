package veille;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collecteur v2 — orchestration du pipeline (cf. CLAUDE.md, carte des sources).
 *
 * <pre>
 *   BwfCalendar     — calendrier World Tour (source PRIMAIRE : échec = exit 1)
 *   WikiTournament  — frenchStatus depuis les tableaux Wikipédia (Source A, best-effort)
 *   PlayerResults   — players[] via WikiPlayer (rank d'infobox + prose→Haiku, Source B)
 *   DataJson        — le contrat data.json, sérialisé par Jackson
 * </pre>
 *
 * Échec gracieux : si le calendrier BWF échoue ou ne donne rien, on NE réécrit
 * PAS data.json et on sort en erreur, pour ne pas vider le site. Les sources
 * secondaires dégradent en « inconnu » / vide sans bloquer l'écriture.
 *
 * Hors périmètre : les têtes de série ({@code seeds}) ; dotation et fuseau
 * restent neutres quand la source ne les donne pas.
 *
 * Argument optionnel : chemin de sortie (défaut "public/data.json").
 */
public class Collector {

    public static void main(String[] args) {
        Path out = Path.of(args.length > 0 ? args[0] : "public/data.json");
        try {
            String json = buildData();

            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            writeAtomic(out, json);
            System.out.println("data.json écrit : " + out.toAbsolutePath());
        } catch (Exception e) {
            // Échec gracieux : on laisse l'ancien data.json intact (pas de commit
            // côté CI puisque le fichier n'a pas changé) et on signale l'échec.
            System.err.println("Collecte échouée — data.json laissé intact : " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String buildData() throws Exception {
        // « Aujourd'hui » = la journée du lecteur français (Europe/Paris), pas
        // celle du runner CI (UTC) ; bornes INCLUSIVES des deux côtés — cf. Window.
        return buildData(Window.today());
    }

    /** Même chose à date IMPOSÉE : rejoue un run d'un jour donné (vérifications). */
    static String buildData(LocalDate today) throws Exception {
        List<Tournament> fetched = BwfCalendar.fetchTournaments();

        if (fetched.isEmpty()) {
            // Soit la page a changé de structure, soit le fetch a renvoyé une
            // page vide/erreur : on refuse d'écrire un data.json vide.
            throw new IllegalStateException(
                    "aucun tournoi World Tour extrait — structure de page changée ou réponse invalide");
        }

        // La source ne publie que les tournois À VENIR : une édition disparaît du
        // calendrier dès sa finale jouée. On la garde en mémoire (cf.
        // CalendarMemory) — sans quoi la tête d'affiche « termine » et les dates
        // des lignes joueurs perdraient le tournoi qui vient de se terminer.
        List<Tournament> all = CalendarMemory.remember(fetched, today);

        // Tête d'affiche : les tournois en cours, ou à défaut le dernier terminé
        // tant qu'aucun autre n'a démarré (cf. Window.featured) — le tableau de
        // bord ne dit jamais « aucun tournoi » s'il reste un tournoi à montrer.
        List<Window.Featured> featured = Window.featured(all, today);

        List<Tournament> upcoming = new ArrayList<>();
        for (Tournament t : all) {
            if (Window.isUpcoming(t, today)) upcoming.add(t);
        }
        upcoming.sort((a, b) -> a.start().compareTo(b.start()));

        // Article Wikipédia du tournoi (Source A, déterministe) : statut français
        // (draw) + champions (infobox), en une lecture. On résout la tête d'affiche
        // ET les à-venir PROCHES (départ ≤ 14 jours) : leur page existe déjà et
        // alimente upcoming[].french. Au-delà, l'article de l'édition n'est en
        // général pas encore créé — inutile de sonder.
        Map<String, WikiTournament.Article> wikiByName = new HashMap<>();
        for (Window.Featured f : featured) {
            wikiByName.put(f.tournament().name(), WikiTournament.resolve(f.tournament()));
        }
        for (Tournament t : upcoming) {
            if (Window.startsWithin(t, today, UPCOMING_FR_DAYS)) {
                wikiByName.put(t.name(), WikiTournament.resolve(t));
            }
        }

        List<DataJson.CurrentJson> currentJson = new ArrayList<>();
        for (Window.Featured f : featured) {
            Tournament t = f.tournament();
            WikiTournament.Article a = wikiByName.getOrDefault(t.name(),
                    WikiTournament.Article.unknown("Statut inconnu."));
            WikiTournament.FrenchStatus fs = a.french();
            boolean done = Window.TERMINE.equals(f.status());
            currentJson.add(new DataJson.CurrentJson(
                    t.name(), t.tier(), t.location(),
                    FrDates.dateRange(t.start(), t.end(), true),
                    t.prize(),
                    "—", // fuseau absent du calendrier BWF (cf. BwfCalendar)
                    dayLabel(t.start(), t.end(), today, done),
                    f.status(),
                    // Champions seulement pour un tournoi TERMINÉ, et seulement si
                    // les 5 disciplines sont publiées (parseChampions est tout ou rien).
                    done ? championsJson(a.champions()) : null,
                    new ArrayList<>(),
                    new DataJson.FrenchStatusJson(fs.present(), fs.title(), fs.note(), fs.confirm())));
        }

        List<DataJson.UpcomingJson> upcomingJson = new ArrayList<>();
        for (Tournament t : upcoming) {
            WikiTournament.Article a = wikiByName.get(t.name());
            upcomingJson.add(new DataJson.UpcomingJson(
                    FrDates.dateRange(t.start(), t.end(), false),
                    t.name(), t.tier(), frenchLabel(a == null ? null : a.french())));
        }

        // players : suivi individuel des Français (Lanier, Christo Popov) via
        // Wikipédia — classement d'infobox + historique de saison (Haiku, caché
        // par révision). Cf. PlayerResults / WikiPlayer.
        DataJson root = new DataJson(
                Instant.now().toString(),
                weekLabel(today),
                currentJson,
                PlayerResults.buildPlayers(today.getYear(), all, today),
                upcomingJson);

        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** Horizon de résolution du statut FR des tournois à venir (jours). */
    private static final long UPCOMING_FR_DAYS = 14;

    /**
     * Libellé court FR d'un tournoi à venir. On n'affirme que le confirmé : un
     * Français au tableau → « engagés ». Sinon « à confirmer » — pour un tournoi
     * à venir, un tableau Wikipédia incomplet (« aucun Français ») ne prouve rien
     * (l'article n'est pas figé), on n'invente donc jamais un « aucun ».
     */
    private static String frenchLabel(WikiTournament.FrenchStatus fs) {
        if (fs != null && Boolean.TRUE.equals(fs.present())) return "FR : engagés";
        return "FR : à confirmer";
    }

    private static String weekLabel(LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate sunday = monday.plusDays(6);
        return "Semaine du " + FrDates.dateRange(monday, sunday, true);
    }

    /** Mappe les champions de Wikipédia vers le contrat ({@code null} si non publiés). */
    private static DataJson.ChampionsJson championsJson(WikiTournament.Champions c) {
        if (c == null) return null;
        return new DataJson.ChampionsJson(
                champion(c.ms()), champion(c.ws()), champion(c.md()),
                champion(c.wd()), champion(c.xd()));
    }

    private static DataJson.ChampionJson champion(WikiTournament.Champion c) {
        return c == null ? null : new DataJson.ChampionJson(c.name(), c.country());
    }

    /**
     * « Jour 2 / 6 · 10 juin » pour un tournoi en cours ; « Terminé · 26 juillet »
     * (jour de la finale) pour un tournoi encore en tête d'affiche mais fini — on
     * n'affiche pas un « Jour 6 / 6 » qui laisserait croire qu'il joue encore.
     */
    private static String dayLabel(LocalDate start, LocalDate end, LocalDate today, boolean done) {
        if (done) {
            return "Terminé · " + end.getDayOfMonth() + " " + FrDates.monthName(end.getMonthValue());
        }
        long total = end.toEpochDay() - start.toEpochDay() + 1;
        long day = today.toEpochDay() - start.toEpochDay() + 1;
        if (day < 1) day = 1;
        if (day > total) day = total;
        return "Jour " + day + " / " + total + " · "
                + today.getDayOfMonth() + " " + FrDates.monthName(today.getMonthValue());
    }

    /**
     * Écriture atomique : fichier temporaire du même dossier puis renommage.
     * Garde-fou « échec gracieux » : un processus tué en pleine écriture (timeout
     * CI, Ctrl-C) ne doit jamais laisser un data.json tronqué — qui serait ensuite
     * committé tel quel par le workflow.
     */
    private static void writeAtomic(Path out, String content) throws java.io.IOException {
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), "data", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp); // ne reste que si le move a échoué
        }
    }
}
