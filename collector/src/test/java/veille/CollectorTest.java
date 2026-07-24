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

        /** Calendrier BWF de test (nom + lieu anglais, comme la vraie source). */
        private List<Tournament> cal() {
            return List.of(
                    new Tournament("DAIHATSU Japan Open 2026", "750", "Tokyo, JPN", "—",
                            LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 19)),
                    new Tournament("Orléans Masters 2026", "300", "Orléans, FRA", "—",
                            LocalDate.of(2026, 3, 17), LocalDate.of(2026, 3, 22)),
                    new Tournament("KFF Singapore Open 2026", "750", "Singapore, SGP", "—",
                            LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 31)));
        }

        /** La DATE vient du calendrier BWF (apparié sur le nom source), jamais de Haiku. */
        @Test
        void parseSeasonLinesDateVientDuCalendrier() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Japan Open\",\"stage\":\"Vainqueur\","
                            + "\"tone\":\"win\"}]", 2026, cal());
            assertEquals(1, l.size());
            assertEquals("Japan Open", l.get(0).tournament());
            assertEquals("14 – 19 juillet", l.get(0).date());   // dates du calendrier
            assertEquals("🥇", l.get(0).medal());
        }

        /** Un champ « date » rendu par Haiku est IGNORÉ (source de l'instabilité) :
         *  seule la date du calendrier compte, sinon null. */
        @Test
        void parseSeasonLinesIgnoreLaDateDeHaiku() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"date\":\"octobre\",\"tournament\":\"Singapore Open\","
                            + "\"stage\":\"Vainqueur\",\"tone\":\"win\"}]", 2026, cal());
            assertEquals("26 – 31 mai", l.get(0).date());       // calendrier (mai), pas « octobre »
            assertFalse(String.valueOf(l.get(0).date()).contains("octobre"));
        }

        /** Tournoi hors calendrier World Tour (Coupe Thomas…) → date null, sans exception. */
        @Test
        void parseSeasonLinesHorsCalendrierDonneDateNull() {
            assertNull(LlmNet.matchDates("Thomas Cup", cal()));
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Thomas Cup\",\"stage\":\"Finaliste\","
                            + "\"tone\":\"out\"}]", 2026, cal());
            assertEquals(1, l.size());
            assertNull(l.get(0).date());
        }

        /** Appariement calendrier réutilise la normalisation de noms existante. */
        @Test
        void matchDatesApparieAuCalendrier() {
            assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 31)},
                    LlmNet.matchDates("Singapore Open", cal()));
            assertNull(LlmNet.matchDates("Japan Open", List.of()));   // calendrier vide → null
        }

        /** Filet DÉTERMINISTE : toute ligne d'une AUTRE saison (year ≠ visé) est rejetée. */
        @Test
        void parseSeasonLinesRejetteLesAutresSaisons() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Singapore Open\",\"stage\":\"Vainqueur\","
                            + "\"tone\":\"win\"},"
                            + "{\"year\":2025,\"tournament\":\"French Open\",\"stage\":\"Vainqueur\","
                            + "\"tone\":\"win\"}]", 2026, cal());
            assertEquals(1, l.size());
            assertEquals("Singapore Open", l.get(0).tournament());
        }

        /** Tri chronologique décroissant sur les dates du CALENDRIER, quel que soit
         *  l'ordre rendu par Haiku (juillet avant mars). */
        @Test
        void parseSeasonLinesTrieParDatesDuCalendrier() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(   // Haiku : Orléans (mars) d'abord
                    "[{\"year\":2026,\"tournament\":\"Orléans Masters\",\"stage\":\"Vainqueur\"},"
                            + "{\"year\":2026,\"tournament\":\"Japan Open\",\"stage\":\"Vainqueur\"}]",
                    2026, cal());
            assertEquals("Japan Open", l.get(0).tournament());     // juillet remonte en tête
            assertEquals("Orléans Masters", l.get(1).tournament());
        }

        /** Les lignes SANS date (hors calendrier) passent après celles qui en ont. */
        @Test
        void parseSeasonLinesLignesSansDateEnFin() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "[{\"year\":2026,\"tournament\":\"Thomas Cup\",\"stage\":\"Finaliste\"},"
                            + "{\"year\":2026,\"tournament\":\"Japan Open\",\"stage\":\"Vainqueur\"}]",
                    2026, cal());
            assertEquals("Japan Open", l.get(0).tournament());     // daté d'abord
            assertNull(l.get(1).date());                           // non daté ensuite
        }

        /** On tolère prose/clôtures Markdown ; un tone hors contrat → null ; une
         *  entrée sans tournoi ni stade est écartée. */
        @Test
        void parseSeasonLinesToleranteEtDansLeContrat() {
            List<DataJson.LineJson> l = LlmNet.parseSeasonLines(
                    "```json\n[{\"year\":2026,\"tournament\":\"Indonesia Open\","
                            + "\"stage\":\"Éliminé au 1er tour\",\"tone\":\"sorti\"},"
                            + "{\"year\":2026}]\n```", 2026, cal());
            assertEquals(1, l.size());                 // la 2e entrée (vide) est écartée
            assertNull(l.get(0).tone());               // « sorti » hors contrat → null
            assertEquals("⚫", l.get(0).medal());       // 1er tour → éliminé avant les demies
            assertEquals("Indonesia Open", l.get(0).tournament());
        }

        @Test
        void parseSeasonLinesVideSurJsonInvalide() {
            assertTrue(LlmNet.parseSeasonLines("pas du json", 2026, cal()).isEmpty());
            assertTrue(LlmNet.parseSeasonLines(null, 2026, cal()).isEmpty());
            assertTrue(LlmNet.parseSeasonLines("{\"tournament\":\"X\"}", 2026, cal()).isEmpty());
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
