package veille;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mémoire d'appariement tournoi BWF → article Wikipédia VALIDÉ (cf. CLAUDE.md).
 * Le nom BWF exact (ex. « SATHIO GROUP Australian Badminton Open 2026 ») pointe
 * vers le titre d'article vérifié (ex. « 2026 Australian Open (badminton) »).
 *
 * But : consulter ce fichier AVANT toute recherche MediaWiki. Une entrée présente
 * = zéro appel {@code list=search} (et zéro filet Haiku d'appariement) — l'article
 * a déjà été confirmé par {@link WikiTournament#matchesTournament}. Committé par le
 * workflow comme {@code collector/cache/} : les runners sont jetables, sans commit
 * la mémoire serait reperdue à chaque run.
 *
 * Échec gracieux : fichier corrompu → mémoire vide (au pire on ré-apparie). Écriture
 * ATOMIQUE (temp + rename) : un run tué ne laisse pas un JSON tronqué.
 * {@link #toJson}/{@link #fromJson} sont pures et testées.
 */
final class Aliases {

    private Aliases() {}

    static final Path FILE = Path.of("collector", "aliases.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Chargé paresseusement depuis {@link #FILE} (jamais touché si aucun tournoi). */
    private static Map<String, String> map;

    /** Titre d'article mémorisé pour ce nom BWF, ou {@code null} si inconnu. */
    static synchronized String get(String bwfName) {
        return load().get(bwfName);
    }

    /** Mémorise un appariement VALIDÉ et persiste (petit fichier, écriture rare). */
    static synchronized void put(String bwfName, String title) {
        load().put(bwfName, title);
        save();
    }

    // ------------------------------------------------------------
    //  Fonctions pures
    // ------------------------------------------------------------

    /** Sérialise en JSON trié par clé (diff stable au commit). */
    static String toJson(Map<String, String> m) throws Exception {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new TreeMap<>(m));
    }

    /** Relit la mémoire. Fichier corrompu/non-objet → map vide, jamais d'exception. */
    static Map<String, String> fromJson(String json) {
        try {
            Map<String, String> m =
                    MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------
    //  Persistance (non testée : I/O)
    // ------------------------------------------------------------

    private static Map<String, String> load() {
        if (map == null) {
            map = new LinkedHashMap<>();
            try {
                if (Files.exists(FILE)) {
                    map.putAll(fromJson(Files.readString(FILE, StandardCharsets.UTF_8)));
                    System.out.println("appariements : mémoire chargée, " + map.size()
                            + " entrée(s) (" + FILE + ")");
                }
            } catch (Exception e) {
                System.err.println("appariements : mémoire illisible, repartie à vide : " + e);
            }
        }
        return map;
    }

    /** Écriture ATOMIQUE (temp + rename) — la mémoire ne doit jamais être tronquée. */
    private static void save() {
        try {
            if (FILE.getParent() != null) Files.createDirectories(FILE.getParent());
            Path tmp = Files.createTempFile(FILE.toAbsolutePath().getParent(), "aliases", ".tmp");
            try {
                Files.writeString(tmp, toJson(map), StandardCharsets.UTF_8);
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
            System.err.println("appariements : échec d'écriture (ré-apparié au prochain run) : " + e);
        }
    }
}
