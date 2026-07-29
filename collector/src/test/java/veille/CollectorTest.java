package veille;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires du collecteur — fonctions pures uniquement, jamais de réseau
 * (cf. CLAUDE.md). Couvre les deux sources Wikipédia : statut d'un tournoi lu dans
 * les tableaux ({@link WikiTournament}, déterministe) et historique de saison d'un
 * joueur ({@link WikiPlayer} déterministe + {@link LlmNet} pour la prose).
 */
class CollectorTest {

    // ------------------------------------------------------------
    // Normalisation et jetons (TextUtil)
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

        @Test
        void hasWordNeMatchePasEnSousChaine() {
            assertTrue(TextUtil.hasWord(TextUtil.norm("Toma Junior Popov"), "popov"));
            assertFalse(TextUtil.hasWord(TextUtil.norm("Christophe Martin"), "christo"));
        }
    }

    // ------------------------------------------------------------
    // Calendrier BWF (parsing des jours)
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
    }

    // ------------------------------------------------------------
    // Fenêtre temporelle (Window) — bornes inclusives + fuseau Europe/Paris
    // ------------------------------------------------------------
    @Nested
    class FenetreTemporelle {

        /** Le China Open réel de la semaine du bug : 21 – 26 juillet 2026 (finale le 26). */
        private final Tournament chinaOpen = new Tournament(
                "VICTOR China Open 2026", "1000", "Changzhou, CHN", "2 000 000 $",
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 26));

        /** BORNES INCLUSIVES : en cours le jour d'ouverture ET le jour de la finale. */
        @Test
        void enCoursJusquAuDernierJourInclus() {
            assertFalse(Window.isCurrent(chinaOpen, LocalDate.of(2026, 7, 20)));  // veille
            assertTrue(Window.isCurrent(chinaOpen, LocalDate.of(2026, 7, 21)));   // 1er jour
            assertTrue(Window.isCurrent(chinaOpen, LocalDate.of(2026, 7, 24)));
            assertTrue(Window.isCurrent(chinaOpen, LocalDate.of(2026, 7, 26)));   // FINALE
            assertFalse(Window.isCurrent(chinaOpen, LocalDate.of(2026, 7, 27)));  // J+1
        }

        /** Le jour de la finale, le tournoi n'est ni à venir ni passé : il est en cours. */
        @Test
        void leJourDeLaFinaleNestNiAvenirNiPasse() {
            LocalDate finale = LocalDate.of(2026, 7, 26);
            assertFalse(Window.isUpcoming(chinaOpen, finale));
            assertFalse(Window.isPast(chinaOpen, finale));
        }

        /** BASCULE « semaine dernière » : seulement à J+1, jamais le jour même. */
        @Test
        void basculeVersSemaineDerniereSeulementAJplus1() {
            assertFalse(Window.isPast(chinaOpen, LocalDate.of(2026, 7, 25)));
            assertFalse(Window.isPast(chinaOpen, LocalDate.of(2026, 7, 26)));   // jour de la finale
            assertTrue(Window.isPast(chinaOpen, LocalDate.of(2026, 7, 27)));    // J+1
        }

        /** Le jour d'ouverture est « en cours », pas « à venir » (borne de gauche incluse). */
        @Test
        void aVenirSeulementAvantLouverture() {
            assertTrue(Window.isUpcoming(chinaOpen, LocalDate.of(2026, 7, 20)));
            assertFalse(Window.isUpcoming(chinaOpen, LocalDate.of(2026, 7, 21)));
        }

        /** Horizon de résolution FR des à-venir : borne des 14 jours INCLUSE. */
        @Test
        void startsWithinInclutLaBorne() {
            LocalDate today = LocalDate.of(2026, 7, 7);            // départ à J+14
            assertTrue(Window.startsWithin(chinaOpen, today, 14));
            assertFalse(Window.startsWithin(chinaOpen, LocalDate.of(2026, 7, 6), 14));
        }

        /** FUSEAU : « aujourd'hui » est la journée du lecteur français, pas celle
         *  du runner CI. À 00 h 30 à Paris le 26, il est encore le 25 en UTC —
         *  la journée de la finale doit quand même être commencée. */
        @Test
        void todayEstLaJourneeParisienneNonUtc() {
            java.time.Instant matinDu26 = java.time.Instant.parse("2026-07-25T22:30:00Z");
            assertEquals(LocalDate.of(2026, 7, 26), Window.today(matinDu26));
            assertEquals(LocalDate.of(2026, 7, 25),                       // ce que dirait UTC brut
                    LocalDate.ofInstant(matinDu26, java.time.ZoneOffset.UTC));
        }

        /** Le tournoi reste dans current à TOUTE heure UTC du jour de la finale
         *  (dimanche 26 juillet à Paris), du premier au dernier instant. */
        @Test
        void resteEnCoursToutLeJourDeLaFinaleQuelleQueSoitLheureUtc() {
            String[] runsUtc = {
                    "2026-07-25T22:00:00Z",   // 00 h 00 à Paris, le 26 vient de commencer
                    "2026-07-25T23:30:00Z",   // 01 h 30 à Paris — UTC dit encore « 25 »
                    "2026-07-26T00:00:00Z",   // cron 0 h UTC
                    "2026-07-26T06:00:00Z",
                    "2026-07-26T12:00:00Z",
                    "2026-07-26T18:00:00Z",   // 20 h à Paris, après la finale
                    "2026-07-26T21:59:00Z"};  // 23 h 59 à Paris, dernière minute du 26
            for (String run : runsUtc) {
                LocalDate today = Window.today(java.time.Instant.parse(run));
                assertEquals(LocalDate.of(2026, 7, 26), today, "run " + run);
                assertTrue(Window.isCurrent(chinaOpen, today), "run " + run);
                assertFalse(Window.isPast(chinaOpen, today), "run " + run);
            }
            // Et il ne sort de current qu'au premier instant du 27 à Paris (22 h UTC le 26).
            LocalDate lendemain = Window.today(java.time.Instant.parse("2026-07-26T22:00:00Z"));
            assertEquals(LocalDate.of(2026, 7, 27), lendemain);
            assertFalse(Window.isCurrent(chinaOpen, lendemain));
            assertTrue(Window.isPast(chinaOpen, lendemain));
        }

        /** Le fuseau suit l'heure d'hiver (UTC+1) — pas d'offset codé en dur. */
        @Test
        void todaySuitLheureDhiver() {
            assertEquals(LocalDate.of(2026, 1, 12),
                    Window.today(java.time.Instant.parse("2026-01-11T23:30:00Z")));   // 00 h 30 à Paris
            assertEquals(LocalDate.of(2026, 1, 11),
                    Window.today(java.time.Instant.parse("2026-01-11T22:30:00Z")));   // 23 h 30 à Paris
        }
    }

    // ------------------------------------------------------------
    // Tête d'affiche — jamais de « aucun tournoi » s'il y a un tournoi à montrer
    // ------------------------------------------------------------
    @Nested
    class TeteDaffiche {

        /** Le tournoi qui a démarré la semaine du bug (finale le dimanche 26). */
        private final Tournament chine = new Tournament(
                "VICTOR China Open 2026", "1000", "Changzhou, CHN", "2 000 000 $",
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 26));
        /** Le suivant au calendrier : démarre le 28 (donc rien entre les deux). */
        private final Tournament taipei = new Tournament(
                "YONEX Taipei Open 2026", "300", "Taipei, TPE", "240 000 $",
                LocalDate.of(2026, 7, 28), LocalDate.of(2026, 8, 2));

        /** Le lundi 27 : la Chine est finie, rien n'a démarré depuis → elle RESTE
         *  en tête d'affiche, à l'état « termine ». Jamais « aucun tournoi ». */
        @Test
        void tournoiTermineResteEnTeteDaffiche() {
            List<Window.Featured> f = Window.featured(
                    List.of(chine, taipei), LocalDate.of(2026, 7, 27));
            assertEquals(1, f.size());
            assertEquals("VICTOR China Open 2026", f.get(0).tournament().name());
            assertEquals("termine", f.get(0).status());
        }

        /** Dès qu'un tournoi démarre (le 28), l'ancien SORT et le nouveau est en cours. */
        @Test
        void leNouveauTournoiChasseLancien() {
            List<Window.Featured> f = Window.featured(
                    List.of(chine, taipei), LocalDate.of(2026, 7, 28));
            assertEquals(1, f.size());
            assertEquals("YONEX Taipei Open 2026", f.get(0).tournament().name());
            assertEquals("en_cours", f.get(0).status());
        }

        /** Pendant le tournoi (jour de la finale compris) : « en_cours », pas de bascule. */
        @Test
        void pendantLeTournoiEnCoursBornesInclusives() {
            for (int jour = 21; jour <= 26; jour++) {
                List<Window.Featured> f = Window.featured(
                        List.of(chine, taipei), LocalDate.of(2026, 7, jour));
                assertEquals(1, f.size(), "le " + jour);
                assertEquals("VICTOR China Open 2026", f.get(0).tournament().name(), "le " + jour);
                assertEquals("en_cours", f.get(0).status(), "le " + jour);
            }
        }

        /** FUSEAU + BORNES : le 26 à 6 h UTC (8 h à Paris), le tournoi qui finit le
         *  26 est encore « en_cours » — et il l'est encore à 21 h UTC (23 h à Paris). */
        @Test
        void leJourDeLaFinaleResteEnCoursEnHeureParis() {
            for (String run : new String[]{
                    "2026-07-25T22:30:00Z",   // 00 h 30 à Paris le 26 (UTC dit encore « 25 »)
                    "2026-07-26T06:00:00Z",   // 8 h à Paris
                    "2026-07-26T21:00:00Z"}) { // 23 h à Paris
                List<Window.Featured> f = Window.featured(
                        List.of(chine, taipei), Window.today(java.time.Instant.parse(run)));
                assertEquals("VICTOR China Open 2026", f.get(0).tournament().name(), run);
                assertEquals("en_cours", f.get(0).status(), run);
            }
        }

        /** Deux tournois la même semaine (niveaux différents) : les DEUX en cours,
         *  triés par date de début — la règle « termine » ne s'applique qu'à défaut. */
        @Test
        void deuxTournoisSimultanesRestentTousDeuxEnCours() {
            Tournament autre = new Tournament("Kaohsiung Masters 2026", "300", "Kaohsiung, TPE",
                    "—", LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 26));
            List<Window.Featured> f = Window.featured(
                    List.of(autre, chine), LocalDate.of(2026, 7, 24));
            assertEquals(2, f.size());
            assertEquals("VICTOR China Open 2026", f.get(0).tournament().name());   // 21 avant 22
            assertTrue(f.stream().allMatch(x -> "en_cours".equals(x.status())));
        }

        /** Entre deux tournois terminés, c'est le plus RÉCEMMENT DÉMARRÉ qui tient
         *  l'affiche (« aucun tournoi n'a commencé après lui »). */
        @Test
        void leDernierDemarreTientLaffiche() {
            Tournament vieux = new Tournament("Japan Open 2026", "750", "Tokyo, JPN", "—",
                    LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 19));
            List<Window.Featured> f = Window.featured(
                    List.of(vieux, chine), LocalDate.of(2026, 7, 27));
            assertEquals("VICTOR China Open 2026", f.get(0).tournament().name());
        }

        /** Aucun tournoi encore commencé (début de saison) → liste vide, seul cas
         *  où le front affiche un état vide. On n'invente pas une tête d'affiche. */
        @Test
        void videSiAucunTournoiNaEncoreCommence() {
            assertTrue(Window.featured(List.of(taipei), LocalDate.of(2026, 7, 20)).isEmpty());
            assertTrue(Window.featured(List.of(), LocalDate.of(2026, 7, 27)).isEmpty());
            assertTrue(Window.featured(null, LocalDate.of(2026, 7, 27)).isEmpty());
        }
    }

    // ------------------------------------------------------------
    // Mémoire du calendrier — la source oublie les tournois terminés
    // ------------------------------------------------------------
    @Nested
    class MemoireCalendrier {

        private final Tournament chine = new Tournament(
                "VICTOR China Open 2026", "1000", "Changzhou, CHN", "2 000 000 $",
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 26));
        private final Tournament taipei = new Tournament(
                "YONEX Taipei Open 2026", "300", "Taipei, TPE", "250 000 $",
                LocalDate.of(2026, 7, 28), LocalDate.of(2026, 8, 2));

        /** LE CAS RÉEL : le 29 juillet, la source ne publie plus que les tournois
         *  à venir — le China Open terminé en a disparu. La mémoire le rend. */
        @Test
        void mergeRendLeTournoiDisparuDeLaSource() {
            List<Tournament> merged = CalendarMemory.merge(
                    List.of(chine, taipei),            // mémoire (vue au run précédent)
                    List.of(taipei),                   // ce que la source publie aujourd'hui
                    LocalDate.of(2026, 7, 29));
            assertEquals(2, merged.size());
            assertEquals("VICTOR China Open 2026", merged.get(0).name());   // trié par début
            // …et la tête d'affiche du 27 redevient possible grâce à lui.
            assertEquals("VICTOR China Open 2026",
                    Window.featured(merged, LocalDate.of(2026, 7, 27)).get(0).tournament().name());
        }

        /** Sans mémoire, le 27 juillet n'a RIEN à montrer : c'est le bug d'origine
         *  (« aucun tournoi en cours » le lendemain d'une finale). */
        @Test
        void sansMemoireLaTeteDaffficheEstVide() {
            assertTrue(Window.featured(List.of(taipei), LocalDate.of(2026, 7, 27)).isEmpty());
        }

        /** La SOURCE fait autorité : une édition re-publiée écrase la mémorisée
         *  (dates ou dotation corrigées), sans doublon. */
        @Test
        void laSourceEcraseLaMemoire() {
            Tournament corrige = new Tournament(taipei.name(), taipei.tier(), taipei.location(),
                    "300 000 $", taipei.start(), taipei.end());
            List<Tournament> merged = CalendarMemory.merge(
                    List.of(taipei), List.of(corrige), LocalDate.of(2026, 7, 29));
            assertEquals(1, merged.size());
            assertEquals("300 000 $", merged.get(0).prize());
        }

        /** Mémoire bornée à la saison : une édition N-1 est oubliée (sinon elle
         *  daterait à tort une ligne de la saison en cours). */
        @Test
        void mergeOublieLesSaisonsPassees() {
            Tournament an2025 = new Tournament("VICTOR China Open 2025", "1000", "Changzhou, CHN",
                    "—", LocalDate.of(2025, 7, 22), LocalDate.of(2025, 7, 27));
            List<Tournament> merged = CalendarMemory.merge(
                    List.of(an2025, chine), List.of(), LocalDate.of(2026, 7, 29));
            assertEquals(1, merged.size());
            assertEquals("VICTOR China Open 2026", merged.get(0).name());
        }

        /** Aller-retour JSON sans perte (dates ISO, ordre conservé). */
        @Test
        void memoireAllerRetourJson() throws Exception {
            List<Tournament> l = List.of(chine, taipei);
            assertEquals(l, CalendarMemory.fromJson(CalendarMemory.toJson(l)));
        }

        /** Fichier corrompu ou ligne illisible → on repart sans, jamais d'exception. */
        @Test
        void memoireCorrompueRendListeVide() {
            assertTrue(CalendarMemory.fromJson("pas du json").isEmpty());
            assertTrue(CalendarMemory.fromJson("{\"a\":1}").isEmpty());
            assertTrue(CalendarMemory.fromJson(
                    "[{\"name\":\"X\",\"start\":\"pas une date\",\"end\":\"2026-07-26\"}]").isEmpty());
        }
    }

    // ------------------------------------------------------------
    // Source A — statut français d'un tournoi (draws Wikipédia)
    // ------------------------------------------------------------
    @Nested
    class StatutTournoi {

        /** Requête de recherche : année + nom nettoyé (sponsors/année retirés). */
        @Test
        void searchQueryNettoieLeNom() {
            assertEquals("2026 china open badminton",
                    WikiTournament.searchQuery("VICTOR China Open 2026", 2026));
            assertEquals("2026 china macau open badminton",
                    WikiTournament.searchQuery("SANDS CHINA LTD. Macau Open 2026", 2026));
        }

        /** Présélection (pré-filtre titre) : on garde les candidats de la BONNE
         *  année partageant un jeton, DANS L'ORDRE de pertinence. Un homonyme d'un
         *  autre sport passe ce pré-filtre (d'où la vérification §matchesTournament). */
        @Test
        void shortlistGardeAnneeEtJetonDansLordre() {
            List<String> titles = List.of(
                    "China Open (badminton)",          // sans année → écarté
                    "2026 China Open (badminton)",
                    "2026 China Open",                 // homonyme éventuel → gardé, à vérifier
                    "2025 China Open (badminton)");    // mauvaise année → écarté
            assertEquals(List.of("2026 China Open (badminton)", "2026 China Open"),
                    WikiTournament.shortlist(titles, TextUtil.nameTokens("China Open"), 2026));
        }

        @Test
        void shortlistVideSiAucuneEditionDatee() {
            assertTrue(WikiTournament.shortlist(
                    List.of("2025 French Open (badminton)", "French Open (badminton)"),
                    TextUtil.nameTokens("French Open"), 2026).isEmpty());
        }

        /** Niveau lu dans l'infobox : code G2L<n> (ou libellé) → tier ; sinon null. */
        @Test
        void parseLevelMappeLeCodeBwf() {
            assertEquals("1000", WikiTournament.parseLevel("|level = G2L2\n"));
            assertEquals("500", WikiTournament.parseLevel("| level          = G2L4 \n"));
            assertEquals("wtf", WikiTournament.parseLevel("|level = G2L1"));
            assertEquals("750", WikiTournament.parseLevel("| level = Super 750"));
            assertNull(WikiTournament.parseLevel("|category = Grand Slam\n"));  // pas de niveau badminton
        }

        @Test
        void parseInfoboxDatesAncreSurLannee() {
            assertArrayEquals(
                    new java.time.LocalDate[]{
                            java.time.LocalDate.of(2026, 6, 9), java.time.LocalDate.of(2026, 6, 14)},
                    WikiTournament.parseInfoboxDates("|dates = 9–14 June\n", 2026));
            assertArrayEquals(
                    new java.time.LocalDate[]{
                            java.time.LocalDate.of(2026, 6, 30), java.time.LocalDate.of(2026, 7, 5)},
                    WikiTournament.parseInfoboxDates("|dates = 30 June – 5 July\n", 2026));
            // Champ tennis « date » (≠ « dates ») → non lu.
            assertNull(WikiTournament.parseInfoboxDates("|date = 18 January – 1 February 2026\n", 2026));
        }

        /** Un article n'est retenu que si niveau ET dates concordent (± 1 jour). */
        @Test
        void matchesTournamentExigeNiveauEtDates() {
            String badm = "{{Infobox badminton event\n|dates = 9–14 June\n|level = G2L4\n"
                    + "|prize_money = 500000\n}}";
            java.time.LocalDate s = java.time.LocalDate.of(2026, 6, 9);
            java.time.LocalDate e = java.time.LocalDate.of(2026, 6, 14);
            assertTrue(WikiTournament.matchesTournament(badm, s, e, "500"));
            assertFalse(WikiTournament.matchesTournament(badm, s, e, "300"));   // niveau contredit
            assertFalse(WikiTournament.matchesTournament(badm,               // dates hors plage
                    java.time.LocalDate.of(2026, 7, 14), java.time.LocalDate.of(2026, 7, 19), "500"));
        }

        /** ANTI-RÉGRESSION tennis : l'article « 2026 Australian Open » (tennis) doit
         *  être REJETÉ pour le tournoi BWF Super 500 du 9–14 juin (ni niveau
         *  badminton, ni champ « dates », dates de janvier). */
        @Test
        void matchesTournamentRejetteLarticleTennis() {
            String tennis = "{{Infobox tennis event|2026|Australian Open|\n"
                    + "|date = 18 January – 1 February 2026\n"
                    + "|edition = 114th\n"
                    + "|category = [[Grand Slam (tennis)|Grand Slam]]\n"
                    + "|surface = Hard\n}}";
            assertFalse(WikiTournament.matchesTournament(tennis,
                    java.time.LocalDate.of(2026, 6, 9), java.time.LocalDate.of(2026, 6, 14), "500"));
        }

        /** Un Français au tableau → present true, avec son stade le plus avancé. */
        @Test
        void frenchStatusPresentAvecStade() {
            String wt = "{{colbegin}}\n"
                    + "# {{flagicon|CHN}} [[Shi Yuqi]] ''(quarter-finals)''\n"
                    + "# {{flagicon|FRA}} [[Christo Popov]] ''(second round)''\n"
                    + "{{colend}}\n"
                    + "| RD1-team4 = {{flagicon|FRA}} [[Toma Junior Popov]]\n"
                    + "| RD1-team5 = '''{{flagicon|FRA}} [[Christo Popov|C Popov]]'''\n";
            WikiTournament.FrenchStatus fs = WikiTournament.parseFrenchStatus(wt);
            assertEquals(Boolean.TRUE, fs.present());
            assertFalse(fs.confirm());
            assertTrue(fs.note().contains("Christo Popov — 2e tour"));
            assertTrue(fs.note().contains("Toma Junior Popov — En lice"));
        }

        /** Tableau publié sans Français → present false (confirmé), jamais null. */
        @Test
        void frenchStatusAbsentQuandTableauSansFrancais() {
            String wt = "| RD1-team1 = [[Shi Yuqi]]\n| RD1-team2 = [[Anders Antonsen]]\n";
            WikiTournament.FrenchStatus fs = WikiTournament.parseFrenchStatus(wt);
            assertEquals(Boolean.FALSE, fs.present());
            assertFalse(fs.confirm());
        }

        /** Pas de tableau (article stub) OU article absent → present null (inconnu). */
        @Test
        void frenchStatusInconnuSansTableau() {
            assertNull(WikiTournament.parseFrenchStatus("Un article sans aucun bracket.").present());
            assertNull(WikiTournament.parseFrenchStatus(null).present());
            assertTrue(WikiTournament.parseFrenchStatus(null).confirm());
        }

        /** Infobox du 2026 China Open une fois les finales jouées (extrait réel). */
        private String infoboxComplete() {
            return "{{Infobox badminton event\n|dates          = 21–26 July \n|level          = G2L2\n"
                    + "| MS             = [[Chou Tien-chen]]\n| country_MS     = TPE\n"
                    + "| WS             = [[Akane Yamaguchi]]\n| country_WS     = JPN\n"
                    + "| MD1            = [[Fajar Alfian]]\n| country_MD1    = INA\n"
                    + "| MD2            = [[Muhammad Shohibul Fikri]]\n| country_MD2    = INA\n"
                    + "| WD1            = [[Liu Shengshu]]\n| country_WD1    = CHN\n"
                    + "| WD2            = [[Tan Ning (badminton)|Tan Ning]]\n| country_WD2    = CHN\n"
                    + "| XD1            = [[Guo Xinwa]]\n| country_XD1    = CHN\n"
                    + "| XD2            = [[Chen Fanghui]]\n| country_XD2    = CHN\n}}\n";
        }

        /** Les 5 disciplines publiées → champions lus, paires jointes, pays gardé
         *  quelle que soit la nationalité (le vainqueur est rarement français). */
        @Test
        void parseChampionsLitLesCinqDisciplines() {
            WikiTournament.Champions c = WikiTournament.parseChampions(infoboxComplete());
            assertEquals("Chou Tien-chen", c.ms().name());
            assertEquals("TPE", c.ms().country());
            assertEquals("Akane Yamaguchi", c.ws().name());
            assertEquals("Fajar Alfian / Muhammad Shohibul Fikri", c.md().name());
            assertEquals("INA", c.md().country());                    // paire d'un seul pays
            assertEquals("Liu Shengshu / Tan Ning", c.wd().name());    // [[cible|affichage]]
            assertEquals("Guo Xinwa / Chen Fanghui", c.xd().name());
        }

        /** TOURNOI EN COURS (cas réel du Taipei Open) : les champs existent mais
         *  sont VIDES → aucun champion, jamais un palmarès inventé. */
        @Test
        void parseChampionsNullQuandLeBlocEstVide() {
            String enCours = "{{Infobox badminton event\n|dates = 28 July–2 August\n|level = G2L5\n"
                    + "| MS             = \n| country_MS     = \n| WS             = \n"
                    + "| country_WS     = \n| MD1            = \n| MD2            = \n"
                    + "| WD1            = \n| WD2            = \n| XD1            = \n"
                    + "| XD2            = \n}}\n";
            assertNull(WikiTournament.parseChampions(enCours));
            assertNull(WikiTournament.parseChampions("Un article sans infobox."));
            assertNull(WikiTournament.parseChampions(null));
        }

        /** DÉLAI WIKIPÉDIA : le bloc se remplit discipline par discipline. Moins de
         *  5 disciplines (ou une paire à moitié saisie) → TOUT OU RIEN, on renvoie
         *  null et le front dira « résultats en attente ». */
        @Test
        void parseChampionsToutOuRienSiPartiel() {
            String sansMixte = infoboxComplete()
                    .replace("| XD1            = [[Guo Xinwa]]\n", "| XD1            = \n");
            assertNull(WikiTournament.parseChampions(sansMixte));      // 4 disciplines sur 5

            String paireAmoitie = infoboxComplete()
                    .replace("| MD2            = [[Muhammad Shohibul Fikri]]\n", "| MD2            = \n");
            assertNull(WikiTournament.parseChampions(paireAmoitie));   // double à moitié saisi
        }

        /** Pays absent → le champion reste valable (nom seul) ; paire de deux
         *  nationalités → les deux codes, sans doublon. */
        @Test
        void parseChampionsToleranteSurLePays() {
            String c = infoboxComplete()
                    .replace("| country_MS     = TPE\n", "| country_MS     = \n")
                    .replace("| country_XD2    = CHN\n", "| country_XD2    = JPN\n");
            WikiTournament.Champions ch = WikiTournament.parseChampions(c);
            assertEquals("Chou Tien-chen", ch.ms().name());
            assertNull(ch.ms().country());                             // pays manquant toléré
            assertEquals("CHN / JPN", ch.xd().country());              // paire mixte
        }

        /** Nettoyage d'un champ de champion : lien, homonymie, template, gras. */
        @Test
        void plainNameNettoieLeChamp() {
            assertEquals("Tan Ning", WikiTournament.plainName("[[Tan Ning (badminton)|Tan Ning]]"));
            assertEquals("Tan Ning", WikiTournament.plainName("[[Tan Ning (badminton)]]"));
            assertEquals("Chou Tien-chen", WikiTournament.plainName(" [[Chou Tien-chen]] "));
            assertEquals("Chou Tien-chen",
                    WikiTournament.plainName("{{flagicon|TPE}} '''[[Chou Tien-chen]]'''"));
            assertEquals("Anders Antonsen", WikiTournament.plainName("Anders Antonsen"));
            assertNull(WikiTournament.plainName("   "));
            assertNull(WikiTournament.plainName(null));
        }

        @Test
        void stageFrTraduitLesAnnotations() {
            assertArrayEquals(new String[]{"Vainqueur", "7"}, WikiTournament.stageFr("champion"));
            assertArrayEquals(new String[]{"Finaliste", "6"}, WikiTournament.stageFr("final"));
            assertArrayEquals(new String[]{"1/2 finale", "5"}, WikiTournament.stageFr("semi-finals"));
            assertArrayEquals(new String[]{"1/4 de finale", "4"}, WikiTournament.stageFr("quarter-finals"));
            assertArrayEquals(new String[]{"2e tour", "2"}, WikiTournament.stageFr("second round"));
            assertArrayEquals(new String[]{"En lice", "0"}, WikiTournament.stageFr(null));
        }
    }

    // ------------------------------------------------------------
    // Source B (déterministe) — classement, nettoyage, section de saison
    // ------------------------------------------------------------
    @Nested
    class PageJoueur {

        @Test
        void parseCurrentRankingLitLePremierEntier() {
            assertEquals("#7 mondial", WikiPlayer.parseCurrentRanking(
                    "| current_ranking = 7\n| current_ranking_date = 9 June 2026"));
            assertEquals("#5 mondial", WikiPlayer.parseCurrentRanking(
                    "| current_ranking = 5 (MS, 9 June 2026)<br />20 (MD)"));
        }

        /** Ni highest_ranking ni current_ranking_date ne doivent être pris pour le
         *  classement courant ; champ absent → null (on ne devine pas). */
        @Test
        void parseCurrentRankingNullSiAbsent() {
            assertNull(WikiPlayer.parseCurrentRanking(
                    "| highest_ranking = 3\n| current_ranking_date = 9 June 2026"));
            assertNull(WikiPlayer.parseCurrentRanking(null));
        }

        @Test
        void cleanWikitextRetireBalisageEtGardeLaProse() {
            String s = "In 2026, [[Alex Lanier|Lanier]] won the [[Orléans Masters]]."
                    + "<ref name=x>{{cite web |url=http://a |date=1 Jan 2027}}</ref> '''Bravo'''";
            String out = WikiPlayer.cleanWikitext(s);
            assertEquals("In 2026, Lanier won the Orléans Masters. Bravo", out);
        }

        /** DÉCOUPAGE PAR SAISON, pas par mention : un paragraphe qui raconte 2025 mais
         *  cite « 2026 » en passant NE doit PAS être retenu (sinon Haiku extrait deux
         *  saisons — bug en amont du LLM). Seule la section 2026 remonte. */
        @Test
        void seasonTextDecoupeParSaisonPasParMention() {
            String wt = "== Career ==\n\n"
                    + "In 2025, he reached the Malaysia final, a springboard he hoped to build on "
                    + "in 2026.\n\n"                                   // ouvre sur 2025 → écarté
                    + "In 2026, he won the [[Foo Open]].<ref>{{cite web |date=3 Jan 2027}}</ref>\n\n"
                    + "== Achievements ==\n\nUnrelated table mentioning 2026 everywhere.\n";
            String season = WikiPlayer.seasonText(wt, 2026);
            assertTrue(season.contains("he won the Foo Open"));
            assertFalse(season.contains("Malaysia"));                 // le paragraphe 2025 est exclu
            assertFalse(season.contains("Achievements"));
            assertFalse(season.contains("Unrelated"));
        }

        /** Sous-titre d'année (=== 2026 ===) : la prose suivante hérite de la saison
         *  même si elle n'ouvre pas sur l'année. */
        @Test
        void seasonTextSuitLesSousTitresDannee() {
            String wt = "== Career ==\n\n=== 2025 ===\n\nA quiet year.\n\n"
                    + "=== 2026 ===\n\nIn July, he won the [[Bar Open]].\n";
            String season = WikiPlayer.seasonText(wt, 2026);
            assertTrue(season.contains("he won the Bar Open"));
            assertFalse(season.contains("quiet"));
        }

        @Test
        void seasonTextNullSiRienPourLAnnee() {
            assertNull(WikiPlayer.seasonText("== Career ==\n\nIn 2024, he debuted.\n", 2026));
        }

        /** Le cache par joueur fait l'aller-retour JSON sans perte (idempotence des
         *  runs : révision inchangée + format identique → même sortie, zéro Haiku). */
        @Test
        void cacheJoueurAllerRetour() throws Exception {
            WikiPlayer.PlayerCache c = new WikiPlayer.PlayerCache(
                    WikiPlayer.EXTRACTION_VERSION, 1362273323L, "2026-07-24T00:00:00Z", "#7 mondial",
                    List.of(new DataJson.LineJson("Dernier", "juin", "Open du Japon",
                            "Vainqueur", "🥇", "win", "Open du Japon · Vainqueur")));
            assertEquals(c, WikiPlayer.cacheFromJson(WikiPlayer.cacheToJson(c)));
        }

        @Test
        void cacheJoueurCorrompuRendNull() {
            assertNull(WikiPlayer.cacheFromJson("pas du json"));
            assertNull(WikiPlayer.cacheFromJson("[1,2,3]"));
        }
    }

    // ------------------------------------------------------------
    // Source B (LLM) — extraction des lignes depuis la réponse de Haiku
    // ------------------------------------------------------------
    @Nested
    class ExtractionProse {

        private final LocalDate today = LocalDate.of(2026, 7, 24);

        /** Calendrier BWF de test (noms + lieux anglais, comme la vraie source), avec
         *  les pièges d'homonymie : « … Japan » et « … India » de novembre. */
        private List<Tournament> cal() {
            return List.of(
                    new Tournament("DAIHATSU Japan Open 2026", "750", "Tokyo, JPN", "—",
                            LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 19)),
                    new Tournament("Kumamoto Masters Japan 2026", "500", "Kumamoto, JPN", "—",
                            LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 15)),
                    new Tournament("YONEX SUNRISE India Open 2026", "750", "New Delhi, IND", "—",
                            LocalDate.of(2026, 1, 13), LocalDate.of(2026, 1, 18)),
                    new Tournament("Syed Modi India International 2026", "300", "Lucknow, IND", "—",
                            LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 29)),
                    new Tournament("Orléans Masters 2026", "300", "Orléans, FRA", "—",
                            LocalDate.of(2026, 3, 17), LocalDate.of(2026, 3, 22)),
                    new Tournament("KFF Singapore Open 2026", "750", "Singapore, SGP", "—",
                            LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 31)));
        }

        /** RÉGRESSION : « Japan Open » ne doit PAS s'apparier à « Kumamoto Masters
         *  Japan » (jeton « japan » partagé mais type d'épreuve différent) ; il
         *  s'apparie à « DAIHATSU Japan Open » (juillet). */
        @Test
        void matchStrictNAttrapePasLHomonyme() {
            Tournament m = LlmNet.matchTournament("Japan Open", cal(), today);
            assertEquals("DAIHATSU Japan Open 2026", m.name());
            assertEquals(LocalDate.of(2026, 7, 14), m.start());
        }

        /** RÉGRESSION : « India Open » ⊄ « Syed Modi India International » — il
         *  s'apparie à l'Open de l'Inde de janvier. */
        @Test
        void matchStrictIndiaOpenPasSyedModi() {
            Tournament m = LlmNet.matchTournament("India Open", cal(), today);
            assertEquals("YONEX SUNRISE India Open 2026", m.name());
            assertEquals(1, m.start().getMonthValue());   // janvier
        }

        /** COHÉRENCE CHRONOLOGIQUE : un tournoi commençant après « aujourd'hui » ne
         *  peut expliquer un résultat passé → rejeté (« Kumamoto Masters » de nov.). */
        @Test
        void matchRejetteUneDateFuture() {
            assertNull(LlmNet.matchTournament("Kumamoto Masters", cal(), today));
            // Le même appariement redevient valide si « aujourd'hui » est après nov.
            assertNull(LlmNet.matchTournament("Japan Open", List.of(), today));   // calendrier vide
        }

        /** Un type d'épreuve seul (« Open ») n'apparie rien (garde-fou anti-sur-match). */
        @Test
        void matchExigeUnJetonDistinctif() {
            assertNull(LlmNet.matchTournament("Open", cal(), today));
        }

        /** Table de traduction des épreuves hors World Tour (courte et stable). */
        @Test
        void frenchNameTraduitLesEpreuvesHorsWt() {
            assertEquals("Coupe Thomas", LlmNet.frenchName("Thomas Cup"));
            assertEquals("Championnats d'Europe par équipes",
                    LlmNet.frenchName("European Men's Team Badminton Championship"));
            assertEquals("Championnats d'Europe", LlmNet.frenchName("European Championships"));
            assertEquals("Singapore Open", LlmNet.frenchName("Singapore Open")); // pas d'équivalent
        }

        /** La DATE et le NOM d'affichage viennent du calendrier BWF, jamais de Haiku. */
        @Test
        void parseSeasonLinesDateEtNomViennentDuCalendrier() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Japan Open\",\"stage\":\"Vainqueur\","
                            + "\"tone\":\"win\"}]", 2026, cal(), today);
            assertEquals(1, l.size());
            assertEquals("DAIHATSU Japan Open 2026", l.get(0).tournament());   // nom du calendrier
            assertEquals("14 – 19 juillet", l.get(0).date());                  // dates du calendrier
            assertEquals("🥇", l.get(0).medal());
        }

        /** Un champ « date » rendu par Haiku est IGNORÉ (source de l'instabilité). */
        @Test
        void parseSeasonLinesIgnoreLaDateDeHaiku() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"date\":\"octobre\",\"tournament\":\"Singapore Open\","
                            + "\"stage\":\"Vainqueur\",\"tone\":\"win\"}]", 2026, cal(), today);
            assertEquals("26 – 31 mai", l.get(0).date());       // calendrier (mai), pas « octobre »
            assertFalse(String.valueOf(l.get(0).date()).contains("octobre"));
        }

        /** Hors calendrier World Tour → date null + nom français (Coupe Thomas). */
        @Test
        void parseSeasonLinesHorsCalendrierDateNullNomFr() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Thomas Cup\",\"stage\":\"Finaliste\","
                            + "\"tone\":\"out\"}]", 2026, cal(), today);
            assertEquals(1, l.size());
            assertNull(l.get(0).date());
            assertEquals("Coupe Thomas", l.get(0).tournament());
        }

        /** Filet DÉTERMINISTE : toute ligne d'une AUTRE saison (year ≠ visé) est rejetée. */
        @Test
        void parseSeasonLinesRejetteLesAutresSaisons() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Singapore Open\",\"stage\":\"Vainqueur\","
                            + "\"tone\":\"win\"},"
                            + "{\"year\":2025,\"tournament\":\"French Open\",\"stage\":\"Vainqueur\","
                            + "\"tone\":\"win\"}]", 2026, cal(), today);
            assertEquals(1, l.size());
            assertEquals("KFF Singapore Open 2026", l.get(0).tournament());
        }

        /** Tri chronologique décroissant sur les dates du CALENDRIER (juillet avant mars). */
        @Test
        void parseSeasonLinesTrieParDatesDuCalendrier() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(   // Haiku : Orléans (mars) d'abord
                    "[{\"year\":2026,\"tournament\":\"Orléans Masters\",\"stage\":\"Vainqueur\"},"
                            + "{\"year\":2026,\"tournament\":\"Japan Open\",\"stage\":\"Vainqueur\"}]",
                    2026, cal(), today);
            assertEquals("DAIHATSU Japan Open 2026", l.get(0).tournament());   // juillet en tête
            assertEquals("Orléans Masters 2026", l.get(1).tournament());
        }

        /** Les lignes SANS date (hors calendrier) passent après celles qui en ont. */
        @Test
        void parseSeasonLinesLignesSansDateEnFin() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Thomas Cup\",\"stage\":\"Finaliste\"},"
                            + "{\"year\":2026,\"tournament\":\"Japan Open\",\"stage\":\"Vainqueur\"}]",
                    2026, cal(), today);
            assertEquals("DAIHATSU Japan Open 2026", l.get(0).tournament());   // daté d'abord
            assertNull(l.get(1).date());                                       // non daté ensuite
        }

        /** On tolère prose/clôtures Markdown ; un tone hors contrat → null ; une
         *  entrée sans tournoi ni stade est écartée. */
        @Test
        void parseSeasonLinesToleranteEtDansLeContrat() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "```json\n[{\"year\":2026,\"tournament\":\"Malaysia Open\","
                            + "\"stage\":\"Éliminé au 1er tour\",\"tone\":\"sorti\"},"
                            + "{\"year\":2026}]\n```", 2026, cal(), today);
            assertEquals(1, l.size());                 // la 2e entrée (vide) est écartée
            assertNull(l.get(0).tone());               // « sorti » hors contrat → null
            assertEquals("⚫", l.get(0).medal());       // 1er tour → éliminé avant les demies
            assertEquals("Malaysia Open", l.get(0).tournament());   // hors calendrier → nom d'origine
        }

        @Test
        void parseSeasonLinesVideSurJsonInvalide() {
            assertTrue(LlmNet.parseSeasonLines("pas du json", 2026, cal(), today).isEmpty());
            assertTrue(LlmNet.parseSeasonLines(null, 2026, cal(), today).isEmpty());
            assertTrue(LlmNet.parseSeasonLines("{\"tournament\":\"X\"}", 2026, cal(), today).isEmpty());
        }

        /** Médaille DÉTERMINISTE selon le stade (échelle badminton : deux demies = 🥉). */
        @Test
        void medalForMappeLeStade() {
            assertEquals("🥇", LlmNet.medalFor("Vainqueur", "win"));
            assertEquals("🥈", LlmNet.medalFor("Finaliste", "out"));
            assertEquals("🥉", LlmNet.medalFor("Demi-finaliste", "out"));
            assertEquals("🥉", LlmNet.medalFor("1/2 finale", null));
            assertEquals("⚫", LlmNet.medalFor("1/4 de finale", "out"));
            assertEquals("⚫", LlmNet.medalFor("Éliminé au 1er tour", "out"));
            assertEquals("⚫", LlmNet.medalFor("Éliminé (stade non précisé)", "out"));
            assertEquals("🎯", LlmNet.medalFor("En lice (en cours)", null));
        }
    }

    // ------------------------------------------------------------
    // Appariement — mémoire (aliases.json) et filet Haiku de dernier recours
    // ------------------------------------------------------------
    @Nested
    class Appariement {

        @Test
        void aliasesAllerRetourJson() throws Exception {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("SATHIO GROUP Australian Badminton Open 2026", "2026 Australian Open (badminton)");
            m.put("VICTOR China Open 2026", "2026 China Open (badminton)");
            assertEquals(m, Aliases.fromJson(Aliases.toJson(m)));
        }

        @Test
        void aliasesCorrompuRendMapVide() {
            assertTrue(Aliases.fromJson("pas du json").isEmpty());
            assertTrue(Aliases.fromJson("[1,2,3]").isEmpty());
        }

        /** Haiku ne peut PAS inventer un titre : seul un titre exactement présent
         *  dans les candidats est retenu ; title:null / hors liste / cassé → null. */
        @Test
        void parsePickedTitleNaccepteQueUnCandidat() {
            List<String> cands = List.of("2026 China Open (badminton)", "2026 China Open");
            assertEquals("2026 China Open (badminton)",
                    LlmNet.parsePickedTitle("{\"title\":\"2026 China Open (badminton)\"}", cands));
            assertEquals("2026 China Open",           // tolère les clôtures Markdown
                    LlmNet.parsePickedTitle("```json\n{\"title\":\"2026 China Open\"}\n```", cands));
            assertNull(LlmNet.parsePickedTitle("{\"title\":null}", cands));
            assertNull(LlmNet.parsePickedTitle("{\"title\":\"2099 Invented Open\"}", cands)); // hors liste
            assertNull(LlmNet.parsePickedTitle("pas du json", cands));
        }
    }
}
