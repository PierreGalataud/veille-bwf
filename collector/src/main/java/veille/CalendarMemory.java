package veille;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mémoire du calendrier BWF de la SAISON EN COURS (cf. CLAUDE.md).
 *
 * <p>POURQUOI : la page calendrier de corporate.bwfbadminton.com ne liste que les
 * tournois À VENIR — une édition DISPARAÎT de la source dès qu'elle est finie
 * (vérifié : le 29 juillet 2026, le China Open du 21–26 juillet n'y figurait
 * déjà plus). Sans mémoire, le collecteur perd le tournoi qui vient de se
 * terminer : plus de tête d'affiche « termine » le lendemain d'une finale
 * (« aucun tournoi en cours » alors qu'il y a tout à montrer), et plus de dates
 * déterministes pour les lignes de saison des joueurs.
 *
 * <p>On garde donc, à côté de {@link Aliases} et du cache joueur, la liste des
 * tournois DÉJÀ VUS. Même contrat qu'eux : fichier committé par le workflow (les
 * runners sont jetables), écriture ATOMIQUE, échec gracieux (fichier illisible →
 * mémoire vide, on repart du calendrier seul).
 *
 * <p>PÉRIMÈTRE : saison courante uniquement ({@link #merge} oublie les éditions
 * terminées avant l'année en cours). On évite ainsi qu'une édition N-1 du même
 * tournoi vienne dater à tort une ligne de la saison N ({@link LlmNet#matchTournament}
 * retient l'édition passée la plus récente).
 *
 * <p>{@link #merge}, {@link #toJson} et {@link #fromJson} sont pures et testées ;
 * seules {@link #load}/{@link #save} font de l'I/O.
 */
final class CalendarMemory {

    private CalendarMemory() {}

    static final Path FILE = Path.of("collector", "calendar.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Calendrier COMPLET de la saison : ce que la source publie aujourd'hui, plus
     * ce qu'on avait vu avant qu'elle ne l'oublie. Persiste le résultat.
     */
    static List<Tournament> remember(List<Tournament> fetched, LocalDate today) {
        List<Tournament> merged = merge(load(), fetched, today);
        save(merged);
        return merged;
    }

    // ------------------------------------------------------------
    //  Fonctions pures
    // ------------------------------------------------------------

    /**
     * Fusionne mémoire et calendrier fraîchement lu, trié par date de début.
     * <ul>
     *   <li>clé = nom + date de début (une édition par saison) ;</li>
     *   <li>la version FRAÎCHE gagne toujours : la source reste l'autorité si des
     *       dates ou une dotation changent ;</li>
     *   <li>on oublie les éditions terminées avant l'année en cours (mémoire
     *       bornée à la saison, cf. javadoc de classe).</li>
     * </ul>
     */
    static List<Tournament> merge(List<Tournament> remembered, List<Tournament> fetched,
                                  LocalDate today) {
        Map<String, Tournament> byKey = new LinkedHashMap<>();
        if (remembered != null) for (Tournament t : remembered) if (t != null) byKey.put(key(t), t);
        if (fetched != null) for (Tournament t : fetched) if (t != null) byKey.put(key(t), t);

        List<Tournament> out = new ArrayList<>();
        for (Tournament t : byKey.values()) {
            if (t.end().getYear() >= today.getYear()) out.add(t);   // saison courante
        }
        out.sort((a, b) -> {
            int c = a.start().compareTo(b.start());
            return c != 0 ? c : a.name().compareTo(b.name());
        });
        return out;
    }

    private static String key(Tournament t) {
        return t.name() + "@" + t.start();
    }

    /**
     * Ligne du fichier : dates en ISO ({@code "2026-07-21"}), lisibles au diff et
     * sans dépendance Jackson supplémentaire (pas de module java.time ici).
     */
    private record Row(String name, String tier, String location, String prize,
                       String start, String end) {}

    /** Sérialise la mémoire (diff stable : déjà triée par {@link #merge}). */
    static String toJson(List<Tournament> tournaments) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (Tournament t : tournaments) {
            rows.add(new Row(t.name(), t.tier(), t.location(), t.prize(),
                    t.start().toString(), t.end().toString()));
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
    }

    /**
     * Relit la mémoire. Fichier corrompu, non-liste, ou ligne sans dates lisibles
     * → ignorés (liste vide au pire), jamais d'exception : la mémoire est un
     * confort, jamais une source d'échec du run.
     */
    static List<Tournament> fromJson(String json) {
        List<Tournament> out = new ArrayList<>();
        try {
            List<Row> rows = MAPPER.readValue(json, new TypeReference<List<Row>>() {});
            if (rows == null) return out;
            for (Row r : rows) {
                if (r == null || r.name() == null) continue;
                try {
                    out.add(new Tournament(r.name(), r.tier(), r.location(), r.prize(),
                            LocalDate.parse(r.start()), LocalDate.parse(r.end())));
                } catch (Exception ignored) {
                    // ligne illisible : on l'oublie, le calendrier la réapportera
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    // ------------------------------------------------------------
    //  Persistance (non testée : I/O)
    // ------------------------------------------------------------

    private static List<Tournament> load() {
        try {
            if (Files.exists(FILE)) {
                List<Tournament> l = fromJson(Files.readString(FILE, StandardCharsets.UTF_8));
                System.out.println("calendrier : mémoire chargée, " + l.size()
                        + " tournoi(s) (" + FILE + ")");
                return l;
            }
        } catch (Exception e) {
            System.err.println("calendrier : mémoire illisible, repartie à vide : " + e);
        }
        return new ArrayList<>();
    }

    /** Écriture ATOMIQUE (temp + rename) — la mémoire ne doit jamais être tronquée. */
    private static void save(List<Tournament> tournaments) {
        try {
            if (FILE.getParent() != null) Files.createDirectories(FILE.getParent());
            Path tmp = Files.createTempFile(FILE.toAbsolutePath().getParent(), "calendar", ".tmp");
            try {
                Files.writeString(tmp, toJson(tournaments), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            System.err.println("calendrier : échec d'écriture (mémoire non mise à jour) : " + e);
        }
    }
}
