package veille;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collecteur v2 — orchestration du pipeline (cf. CLAUDE.md, carte des sources).
 *
 * <pre>
 *   BwfCalendar   — calendrier World Tour (source PRIMAIRE : échec = exit 1)
 *   EquipeFrance  — frenchStatus + fil des Bleus + classement (SECONDAIRE, best-effort)
 *   PlayerResults — agrégation players[] par tables de mots-clés (déterministe)
 *   DataJson      — le contrat data.json, sérialisé par Jackson
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
        List<Tournament> all = BwfCalendar.fetchTournaments();

        if (all.isEmpty()) {
            // Soit la page a changé de structure, soit le fetch a renvoyé une
            // page vide/erreur : on refuse d'écrire un data.json vide.
            throw new IllegalStateException(
                    "aucun tournoi World Tour extrait — structure de page changée ou réponse invalide");
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Tournament> current = new ArrayList<>();
        List<Tournament> upcoming = new ArrayList<>();
        for (Tournament t : all) {
            if (!t.start().isAfter(today) && !t.end().isBefore(today)) {
                current.add(t);              // start <= today <= end
            } else if (t.start().isAfter(today)) {
                upcoming.add(t);             // à venir
            }
            // sinon : tournoi passé → ignoré
        }
        current.sort((a, b) -> a.start().compareTo(b.start()));
        upcoming.sort((a, b) -> a.start().compareTo(b.start()));

        // Enrichissement du statut français via equipe-france (best-effort).
        // On résout AUSSI les tournois à venir PROCHES (départ ≤ 14 jours) : leur
        // page equipe-france existe déjà et alimente upcoming[].french au lieu du
        // « à confirmer » permanent. Au-delà, la sélection n'est pas publiée —
        // inutile de sonder.
        List<Tournament> toResolve = new ArrayList<>(current);
        for (Tournament t : upcoming) {
            if (!t.start().isAfter(today.plusDays(UPCOMING_FR_DAYS))) toResolve.add(t);
        }
        Map<String, EquipeFrance.FrenchStatus> frByName = EquipeFrance.buildFrenchStatus(toResolve);

        List<DataJson.CurrentJson> currentJson = new ArrayList<>();
        for (Tournament t : current) {
            EquipeFrance.FrenchStatus fs =
                    frByName.getOrDefault(t.name(), EquipeFrance.FrenchStatus.SOURCE_DOWN);
            currentJson.add(new DataJson.CurrentJson(
                    t.name(), t.tier(), t.location(),
                    FrDates.dateRange(t.start(), t.end(), true),
                    t.prize(),
                    "—", // fuseau absent du calendrier BWF (cf. BwfCalendar)
                    dayLabel(t.start(), t.end(), today),
                    new ArrayList<>(),
                    new DataJson.FrenchStatusJson(fs.present(), fs.title(), fs.note(), fs.confirm())));
        }

        List<DataJson.UpcomingJson> upcomingJson = new ArrayList<>();
        for (Tournament t : upcoming) {
            upcomingJson.add(new DataJson.UpcomingJson(
                    FrDates.dateRange(t.start(), t.end(), false),
                    t.name(), t.tier(), frenchLabel(frByName.get(t.name()))));
        }

        // players : suivi des Français via le fil daté d'equipe-france. On passe le
        // calendrier BWF (dates des tournois) et la date du jour pour décider si un
        // tournoi est « en cours » à partir des DATES, jamais des mots du titre.
        DataJson root = new DataJson(
                Instant.now().toString(),
                weekLabel(today),
                currentJson,
                PlayerResults.buildPlayers(all, today),
                upcomingJson);

        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** Horizon de résolution du statut FR des tournois à venir (jours). */
    private static final long UPCOMING_FR_DAYS = 14;

    /**
     * Libellé court FR d'un tournoi à venir. On n'affirme que le confirmé
     * (mêmes trois états que frenchStatus) : non résolu ou inconnu → « à
     * confirmer », jamais un « aucun » inventé.
     */
    private static String frenchLabel(EquipeFrance.FrenchStatus fs) {
        if (fs == null) return "FR : à confirmer";
        if (Boolean.TRUE.equals(fs.present())) return "FR : engagés";
        if (Boolean.FALSE.equals(fs.present())) return "FR : aucun engagé";
        return "FR : à confirmer";
    }

    private static String weekLabel(LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate sunday = monday.plusDays(6);
        return "Semaine du " + FrDates.dateRange(monday, sunday, true);
    }

    /** « Jour 2 / 6 · 10 juin » pour un tournoi en cours. */
    private static String dayLabel(LocalDate start, LocalDate end, LocalDate today) {
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
