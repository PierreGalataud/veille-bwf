package veille;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import veille.PlayerResults.Mention;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires du collecteur — fonctions pures uniquement, jamais de réseau.
 * Couvre notamment les règles de classement de CLAUDE.md et les bugs 1.1 à 1.4
 * du RAPPORT-AMELIORATIONS.md (frontières de mot, passage d'année, sorties
 * collectives vs opposition, appariement de tournoi sur jeton faible).
 */
class CollectorTest {

    private static Tournament t(String name, LocalDate start, LocalDate end) {
        return new Tournament(name, "500", "", "—", start, end);
    }

    private static FeedItem feed(String title) {
        return new FeedItem("5 juin", "Open d'Indonésie", title, "/2026/06/05/article");
    }

    // ------------------------------------------------------------
    // Normalisation et jetons
    // ------------------------------------------------------------
    @Nested
    class Normalisation {
        @Test
        void normRetireAccentsEtApostrophes() {
            assertEquals("elimine dentree", TextUtil.norm("Éliminé d'entrée"));
        }

        @Test
        void nameTokensFiltreSponsorsEtGeneriques() {
            assertEquals(Set.of("canada"), TextUtil.nameTokens("YONEX Canada Open 2026"));
        }

        @Test
        void sharedTokensTolereVariantesFrEnParPrefixe() {
            assertEquals(1, TextUtil.sharedTokens(
                    TextUtil.nameTokens("Open d'Indonésie"),
                    TextUtil.nameTokens("KAPAL API Indonesia Open 2026")));
        }
    }

    // ------------------------------------------------------------
    // Stades (échelle 1..6)
    // ------------------------------------------------------------
    @Nested
    class Stades {
        @Test
        void echelleDesStades() {
            assertEquals(6, PlayerResults.stageOf(TextUtil.norm("Sacré champion à Sydney")));
            assertEquals(5, PlayerResults.stageOf(TextUtil.norm("Alex Lanier s'incline en finale")));
            assertEquals(5, PlayerResults.stageOf(TextUtil.norm("Alex Lanier atteint la finale")));
            assertEquals(4, PlayerResults.stageOf(TextUtil.norm("Battu en demi-finale")));
            assertEquals(3, PlayerResults.stageOf(TextUtil.norm("Qualifié pour les quarts de finale")));
            assertEquals(2, PlayerResults.stageOf(TextUtil.norm("Sorti en huitième de finale")));
            assertEquals(1, PlayerResults.stageOf(TextUtil.norm("Éliminé au 1er tour")));
            assertEquals(0, PlayerResults.stageOf(TextUtil.norm("Une semaine de préparation")));
        }
    }

    // ------------------------------------------------------------
    // Parsing des dates (calendriers BWF et equipe-france)
    // ------------------------------------------------------------
    @Nested
    class ParsingDates {
        @Test
        void parseDayRangeLitLesDeuxJours() {
            assertArrayEquals(new int[]{9, 14}, BwfCalendar.parseDayRange("09 -14"));
            assertArrayEquals(new int[]{30, 5}, BwfCalendar.parseDayRange("30 - 05"));
            assertArrayEquals(new int[]{21, 21}, BwfCalendar.parseDayRange("21"));
            assertNull(BwfCalendar.parseDayRange("TBC"));
        }

        @Test
        void parseEfDatesMemeMois() {
            assertEquals(new EfDateRange(6, 16, 6, 21),
                    EquipeFrance.parseEfDates("16 – 21 juin"));
        }

        @Test
        void parseEfDatesAChevalSurDeuxMois() {
            assertEquals(new EfDateRange(6, 30, 7, 5),
                    EquipeFrance.parseEfDates("Du 30 juin au 5 juillet"));
        }

        @Test
        void parseEfDatesSansDate() {
            assertEquals(EfDateRange.UNKNOWN,
                    EquipeFrance.parseEfDates("dates non communiquées"));
        }
    }

    // ------------------------------------------------------------
    // Chevauchement de dates — bug 1.2 (passage d'année)
    // ------------------------------------------------------------
    @Nested
    class ChevauchementDates {
        @Test
        void chevauchementMemeMois() {
            Tournament australie = t("Australian Open",
                    LocalDate.of(2026, 6, 9), LocalDate.of(2026, 6, 14));
            assertTrue(EquipeFrance.datesOverlapRange(australie,
                    new EfDateRange(6, 9, 6, 14)));
            assertFalse(EquipeFrance.datesOverlapRange(australie,
                    new EfDateRange(6, 15, 6, 21)));
        }

        @Test
        void datesAbsentesNeChevauchentJamais() {
            Tournament australie = t("Australian Open",
                    LocalDate.of(2026, 6, 9), LocalDate.of(2026, 6, 14));
            assertFalse(EquipeFrance.datesOverlapRange(australie, EfDateRange.UNKNOWN));
        }

        /** Bug 1.2 : tournoi à cheval décembre → janvier, l'enroulement d'année
         *  doit être géré (mois*100+jour ne suffisait pas). */
        @Test
        void chevauchementAuPassageDannee() {
            Tournament nouvelAn = t("New Year Open",
                    LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 4));
            assertTrue(EquipeFrance.datesOverlapRange(nouvelAn,
                    new EfDateRange(12, 30, 1, 4)));
        }

        /** Une plage déc.–janv. ne doit pas matcher un tournoi début janvier disjoint. */
        @Test
        void plageEnrouleeDisjointeNeMatchePas() {
            Tournament janvier = t("January Open",
                    LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 10));
            assertFalse(EquipeFrance.datesOverlapRange(janvier,
                    new EfDateRange(12, 30, 1, 4)));
        }
    }

    // ------------------------------------------------------------
    // Classification des titres — bugs 1.1 (frontières de mot) et 1.3
    // (sortie collective vs opposition)
    // ------------------------------------------------------------
    @Nested
    class Classification {
        /** Bug 1.1 : « automatique » contient « toma » — ne doit PAS matcher Toma. */
        @Test
        void automatiqueNeMatchePasToma() {
            Mention m = PlayerResults.classify(feed("Qualification automatique pour les Bleus"));
            assertFalse(m.toma());
            assertFalse(m.christo());
            assertFalse(m.lanier());
        }

        /** Bug 1.1 : « Christophe » contient « christo » — ne doit PAS matcher Christo Popov. */
        @Test
        void christopheNeMatchePasChristo() {
            Mention m = PlayerResults.classify(feed("Christophe Martin nommé entraîneur national"));
            assertFalse(m.christo());
        }

        /** Bug 1.1 : « combat » contient « bat » — pas un verbe d'opposition. */
        @Test
        void combatNestPasUnVerbeDopposition() {
            Mention m = PlayerResults.classify(
                    feed("Un combat splendide entre Alex Lanier et Toma Junior Popov"));
            assertTrue(m.lanier());
            assertTrue(m.toma());
            assertFalse(m.disputed());
        }

        /** Bug 1.3 : sortie COLLECTIVE (« éliminés » pluriel) = signal de sortie
         *  pour les cités, pas une opposition à neutraliser (règle CLAUDE.md). */
        @Test
        void sortieCollectiveNestPasUneOpposition() {
            Mention m = PlayerResults.classify(feed("Alex Lanier et les Popov éliminés d'entrée"));
            assertTrue(m.lanier());
            assertTrue(m.ambiguousPopov());
            assertFalse(m.disputed());
        }

        /** Opposition active entre deux suivis → indécidable (règle CLAUDE.md). */
        @Test
        void oppositionActiveResteIndecidable() {
            Mention m = PlayerResults.classify(feed("Toma Junior Popov domine Alex Lanier en finale"));
            assertTrue(m.toma());
            assertTrue(m.lanier());
            assertTrue(m.disputed());
        }

        @Test
        void verbeBattreEntierEstUneOpposition() {
            Mention m = PlayerResults.classify(feed("Christo Popov bat son frère Toma au 1er tour"));
            assertTrue(m.christo());
            assertTrue(m.toma());
            assertTrue(m.disputed());
        }

        /** « Popov » sans prénom = ambigu, rattaché aux deux frères (règle CLAUDE.md). */
        @Test
        void popovSansPrenomEstAmbigu() {
            Mention m = PlayerResults.classify(feed("Popov s'incline au 1er tour"));
            assertTrue(m.ambiguousPopov());
            assertFalse(m.christo());
            assertFalse(m.toma());
        }

        @Test
        void doubleMatcheSurLunOuLautreNom() {
            assertTrue(PlayerResults.classify(feed("Delphine Delrue et Thom Gicquel privés de finale")).dble());
            assertTrue(PlayerResults.classify(feed("Gicquel-Delrue en quarts de finale")).dble());
        }
    }

    // ------------------------------------------------------------
    // « En cours » par les dates — bug 1.4 (appariement sur jeton faible)
    // ------------------------------------------------------------
    @Nested
    class EnCours {
        private final Tournament koreaMasters = t("VICTOR Korea Masters 2026",
                LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 9));
        private final Tournament indonesiaOpen = t("KAPAL API Indonesia Open 2026",
                LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 7));

        /** Bug 1.4 : « Orléans Masters » ne partage que le jeton générique
         *  « masters » avec le Korea Masters — il ne doit PAS hériter de ses dates. */
        @Test
        void jetonMastersSeulNapparieAucunTournoi() {
            assertFalse(PlayerResults.isOngoing("Orléans Masters",
                    LocalDate.of(2026, 3, 23),
                    List.of(koreaMasters),
                    LocalDate.of(2026, 8, 5)));
        }

        /** Non-régression : repli par jeton distinctif (indonésie/indonesia)
         *  même quand la date du titre sort de la plage (article de bilan). */
        @Test
        void replParJetonDistinctifApresLaFin() {
            assertFalse(PlayerResults.isOngoing("Open d'Indonésie",
                    LocalDate.of(2026, 6, 8),
                    List.of(indonesiaOpen),
                    LocalDate.of(2026, 6, 8)));
        }

        @Test
        void tournoiApparieEnCoursParSesDates() {
            assertTrue(PlayerResults.isOngoing("Open d'Indonésie",
                    LocalDate.of(2026, 6, 5),
                    List.of(indonesiaOpen),
                    LocalDate.of(2026, 6, 5)));
        }

        @Test
        void horsCalendrierRecentReputeEnCours() {
            assertTrue(PlayerResults.isOngoing("Championnats d'Europe",
                    LocalDate.of(2026, 6, 9), List.of(), LocalDate.of(2026, 6, 11)));
            assertFalse(PlayerResults.isOngoing("Championnats d'Europe",
                    LocalDate.of(2026, 4, 13), List.of(), LocalDate.of(2026, 6, 11)));
        }

        @Test
        void sansDateOnNaffirmeJamaisEnCours() {
            assertFalse(PlayerResults.isOngoing("Championnats d'Europe",
                    null, List.of(), LocalDate.of(2026, 6, 11)));
        }
    }
}
