package veille;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suivi des Français ({@code players[]}) via le fil daté d'equipe-france :
 * découpage déterministe DATE · TOURNOI · TITRE, rattachement par noms en mots
 * entiers, puis AGRÉGATION par tournoi en un résumé centré joueur — stade le plus
 * avancé atteint + issue (win/out/null) — via des tables de mots-clés
 * déterministes (pas de LLM). Les cas indécidables restent {@code tone: null} :
 * c'est le point d'entrée du futur filet LLM (étape A), pas un bug.
 */
final class PlayerResults {

    private PlayerResults() {}

    /**
     * Joueurs suivis. Les deux frères Popov sont distingués par leur prénom ; un
     * « popov » sans prénom est traité à part (ambigu, cf. {@link #classify}).
     */
    private static final String LANIER = "Alex Lanier";
    private static final String CHRISTO = "Christo Popov";
    private static final String TOMA = "Toma Junior Popov";
    private static final String DOUBLE = "Delphine Delrue / Thom Gicquel";

    /** Slug equipe-france de chaque joueur, pour la jonction avec le classement. */
    private static final String SLUG_LANIER = "alex-lanier";
    private static final String SLUG_CHRISTO = "christo-popov";
    private static final String SLUG_TOMA = "toma-junior-popov";

    /**
     * Tables déterministes (pas de LLM) pour résumer un tournoi du point de vue
     * d'un joueur. Mots-clés sur texte normalisé (sans accents ni apostrophes).
     *
     * Marqueurs d'ISSUE :
     *  - WIN : le joueur gagne le tournoi (→ stade Vainqueur) ;
     *  - OUT : le joueur sort (« fin de parcours », « s'incline », « tombent »,
     *    « privés »…). Une mention groupée (« Lanier et Popov », « Gicquel-Delrue »)
     *    vaut aussi signal de sortie.
     * Le STADE atteint est l'échelon le plus avancé nommé (cf. {@link #stageOf}) :
     * 1er tour(1) &lt; 1/8(2) &lt; 1/4(3) &lt; 1/2(4) &lt; Finale(5) &lt; Vainqueur(6).
     */
    private static final String[] WIN_MARKERS = {
            "sacre", "vainqueur", "champion", "titre", "realise le double"
    };
    private static final String[] OUT_MARKERS = {
            "fin de parcours", "sincline", "tombent", "prive", "elimin", "chute"
    };
    /**
     * Verbes d'OPPOSITION : un titre qui les contient ET nomme ≥ 2 joueurs suivis
     * oppose ces joueurs (« X domine Y »). La table de mots-clés ne sait pas
     * distinguer le gagnant du perdant → on neutralise le résultat des joueurs cités
     * (tone null, « résultat à préciser ») plutôt que d'en sacrer un à tort.
     * « bat » est testé à part comme MOT ENTIER (cf. classify) : en simple
     * sous-chaîne il matcherait « combat », « battu », « débat ».
     */
    private static final String[] OPP_VERBS = {
            "domine", "simpose face", "elimin", "prive", "ecarte", "renverse"
    };
    /**
     * Formes COLLECTIVES de sortie (pluriel) : « X et Y éliminés » est une sortie
     * groupée des joueurs cités, PAS une opposition « X élimine Y ». Testées AVANT
     * OPP_VERBS dans classify pour ne jamais neutraliser une élimination groupée
     * en « résultat à préciser » (règle CLAUDE.md : mention groupée = signal de
     * sortie).
     */
    private static final String[] COLLECTIVE_OUT = {
            "elimines", "sinclinent", "tombent", "prives"
    };
    /** Catégories génériques du fil qui ne désignent pas un tournoi (à ignorer). */
    private static final Set<String> GENERIC_CATS = new HashSet<>(List.of("badminton"));
    /** Nb max de lignes conservées par joueur (les plus récentes). */
    private static final int MAX_LINES = 6;
    /**
     * Hors calendrier BWF (Championnats d'Europe, Orléans…), faute de dates
     * précises : un tournoi est réputé terminé si son titre le plus récent date de
     * plus de ~10 jours (un tournoi dure ~1 semaine). Cf. {@link #isOngoing}.
     */
    private static final long ONGOING_MAX_AGE_DAYS = 10;

    /** Joueurs suivis cités par une entrée du fil, et nature du titre. */
    record Mention(boolean lanier, boolean christo, boolean toma, boolean dble,
                   boolean ambiguousPopov, boolean disputed) {}

    /**
     * Rattache une entrée du fil aux joueurs suivis (titre + slug d'URL + catégorie)
     * et qualifie le titre : « Popov » sans prénom = ambigu (deux frères) ;
     * opposition = titre nommant ≥ 2 joueurs suivis distincts ET un verbe
     * d'affrontement → résultat indécidable (cf. OPP_VERBS / TourAgg.absorb).
     *
     * Les noms se reconnaissent en MOTS ENTIERS (« toma » ⊄ « automatique »,
     * « christo » ⊄ « Christophe »). Une forme collective (« éliminés » pluriel)
     * prime sur les verbes d'opposition : c'est une sortie groupée, pas un duel.
     */
    static Mention classify(FeedItem it) {
        String hay = TextUtil.norm(it.title() + " " + it.href() + " " + it.tournoi());

        boolean hasChristo = TextUtil.hasWord(hay, "christo");
        boolean hasToma = TextUtil.hasWord(hay, "toma");
        boolean hasPopov = TextUtil.hasWord(hay, "popov");
        boolean hasLanier = TextUtil.hasWord(hay, "lanier");
        boolean hasDouble = TextUtil.hasWord(hay, "delrue") || TextUtil.hasWord(hay, "gicquel");
        boolean ambiguousPopov = hasPopov && !hasChristo && !hasToma;

        int tracked = (hasLanier ? 1 : 0) + (hasChristo ? 1 : 0) + (hasToma ? 1 : 0)
                + (ambiguousPopov ? 1 : 0) + (hasDouble ? 1 : 0);
        String title = TextUtil.norm(it.title());
        boolean collective = TextUtil.containsAny(title, COLLECTIVE_OUT);
        boolean disputed = tracked >= 2 && !collective
                && (TextUtil.containsAny(title, OPP_VERBS) || TextUtil.hasWord(title, "bat"));

        return new Mention(hasLanier, hasChristo, hasToma, hasDouble, ambiguousPopov, disputed);
    }

    /**
     * Résumé d'un tournoi du point de vue d'un joueur : stade le plus avancé NOMMÉ
     * + issue (win/out/null). Agrège plusieurs titres du fil sur un même tournoi.
     */
    private static final class TourAgg {
        final String tournoi;
        final String date;     // date (texte) de la mention la plus récente (1re vue)
        LocalDate lastDate;    // même date, parsée (pour décider « en cours »)
        boolean win = false;   // marqueur de victoire finale (sacré/vainqueur…)
        boolean explicitOut = false; // marqueur explicite de sortie (s'incline…)
        boolean disputed = false; // titre d'opposition (≥2 suivis) → résultat à préciser
        int stage = 0;         // stade le plus avancé NOMMÉ (titres nominatifs)

        TourAgg(String tournoi, String date, LocalDate lastDate) {
            this.tournoi = tournoi;
            this.date = date;
            this.lastDate = lastDate;
        }

        /**
         * Absorbe un titre normalisé concernant ce joueur sur ce tournoi.
         * Nominatif : fournit l'issue (win/out) ET le stade. Ambigu (« Popov »
         * sans prénom) : vaut SEULEMENT signal de sortie, sans inventer le stade
         * individuel. Opposition (« X domine Y » entre deux suivis) : on n'extrait
         * NI issue NI stade — on ne sait pas qui a gagné → simple drapeau « disputed »
         * (cf. règles CLAUDE.md).
         */
        void absorb(String normTitle, boolean ambiguous, boolean disputed, LocalDate titleDate) {
            if (titleDate != null && (lastDate == null || titleDate.isAfter(lastDate))) {
                lastDate = titleDate;
            }
            if (disputed) { this.disputed = true; return; }
            if (ambiguous) { explicitOut = true; return; }
            if (TextUtil.containsAny(normTitle, WIN_MARKERS)) win = true;
            if (TextUtil.containsAny(normTitle, OUT_MARKERS)) explicitOut = true;
            stage = Math.max(stage, stageOf(normTitle));
        }

        /**
         * Construit la ligne JSON {label,date,tournament,stage,tone,value}. L'état
         * « en cours » vient des DATES ({@code ongoing}), jamais des mots du titre.
         * <ul>
         *   <li>victoire finale → {@code Vainqueur} / win ;</li>
         *   <li>sortie explicite (titre d'élimination) → {@code Éliminé <stade>} /
         *       out (stade exact = stade le plus avancé atteint) ;</li>
         *   <li>tournoi EN COURS, sans verdict → {@code <stade> (en cours)} / null ;</li>
         *   <li>tournoi TERMINÉ avec un stade atteint → le joueur est sorti, borne
         *       basse {@code Éliminé (<stade> ou plus loin)} / out ;</li>
         *   <li>titre d'opposition seul (résultat indécidable) → {@code résultat à
         *       préciser} / null — jamais « Vainqueur » à tort.</li>
         * </ul>
         */
        DataJson.LineJson toLine(String label, boolean ongoing) {
            String tone;
            String stade;
            if (win) {                            // victoire finale (prime sur tout)
                tone = "win";
                stade = "Vainqueur";
            } else if (explicitOut) {             // sortie nommée → stade exact
                tone = "out";
                stade = stage > 0
                        ? "Éliminé " + outExact(stage)
                        : "Éliminé (stade non précisé)";
            } else if (ongoing) {                 // tournoi en cours → encore en lice
                tone = null;
                stade = stage > 0
                        ? TextUtil.capitalize(stageShort(stage)) + " (en cours)"
                        : "En lice (en cours)";
            } else if (stage > 0) {               // terminé, un stade atteint → sorti
                tone = "out";
                stade = "Éliminé (" + stageShort(stage) + " ou plus loin)";
            } else if (disputed) {                // opposition seule → indécidable
                tone = null;
                stade = "résultat à préciser";
            } else {                              // terminé, rien de nommé → sorti
                tone = "out";
                stade = "Éliminé (stade non précisé)";
            }
            // value ne cite JAMAIS un autre joueur : « <tournoi> · <stade lisible> ».
            return new DataJson.LineJson(label, date, tournoi, stade, tone,
                    tournoi + " · " + stade);
        }
    }

    /** Accumulateur par joueur : un résumé par tournoi, ordre antéchronologique. */
    private static final class PlayerAcc {
        final String name;
        final String slug;
        String rank = "";
        final Map<String, TourAgg> byTour = new LinkedHashMap<>();

        PlayerAcc(String name, String slug) { this.name = name; this.slug = slug; }

        /** Rattache un titre du fil au tournoi (ignore les catégories non-tournoi). */
        void add(FeedItem it, boolean ambiguous, boolean disputed, LocalDate titleDate) {
            String tour = it.tournoi();
            if (tour.isEmpty() || GENERIC_CATS.contains(TextUtil.norm(tour))) return;
            TourAgg agg = byTour.computeIfAbsent(tour, k -> new TourAgg(tour, it.date(), titleDate));
            agg.absorb(TextUtil.norm(it.title()), ambiguous, disputed, titleDate);
        }

        DataJson.PlayerJson toJson(List<Tournament> bwf, LocalDate today) {
            List<DataJson.LineJson> lines = new ArrayList<>();
            for (TourAgg agg : byTour.values()) {
                if (lines.size() >= MAX_LINES) break;
                boolean ongoing = isOngoing(agg.tournoi, agg.lastDate, bwf, today);
                lines.add(agg.toLine(lines.isEmpty() ? "Dernier" : "Puis", ongoing));
            }
            return new DataJson.PlayerJson(name, rank.isEmpty() ? null : rank, lines);
        }

        boolean hasLines() { return !byTour.isEmpty(); }
    }

    /**
     * Construit {@code players[]} depuis le fil daté d'equipe-france. Échec
     * gracieux : toute indisponibilité de la source renvoie une liste vide
     * (data.json reste écrit, on ne casse rien).
     */
    static List<DataJson.PlayerJson> buildPlayers(List<Tournament> bwf, LocalDate today) {
        List<FeedItem> feed;
        try {
            feed = EquipeFrance.fetchFeed();
        } catch (Exception e) {
            System.err.println("equipe-france (fil actus) KO — players[] vide : " + e);
            return new ArrayList<>();
        }

        PlayerAcc lanier = new PlayerAcc(LANIER, SLUG_LANIER);
        PlayerAcc christo = new PlayerAcc(CHRISTO, SLUG_CHRISTO);
        PlayerAcc toma = new PlayerAcc(TOMA, SLUG_TOMA);
        PlayerAcc dbl = new PlayerAcc(DOUBLE, null);

        // Le fil est antéchronologique (plus récent en premier) : on l'exploite tel
        // quel, la 1re ligne retenue par joueur devient « Dernier ».
        for (FeedItem it : feed) {
            LocalDate titleDate = FrDates.parseFeedDate(it.date(), today);
            Mention m = classify(it);

            if (m.lanier()) lanier.add(it, false, m.disputed(), titleDate);
            if (m.christo()) christo.add(it, false, m.disputed(), titleDate);
            if (m.toma()) toma.add(it, false, m.disputed(), titleDate);
            // « Popov » sans prénom : ambigu (deux frères). Signal de sortie pour
            // les DEUX, sans inventer le stade individuel (cf. TourAgg.absorb).
            if (m.ambiguousPopov()) {
                christo.add(it, true, m.disputed(), titleDate);
                toma.add(it, true, m.disputed(), titleDate);
            }
            if (m.dble()) dbl.add(it, false, m.disputed(), titleDate);
        }

        // Classement mondial (simple messieurs) — source autoritaire pour rank.
        Map<String, String> ranks = EquipeFrance.buildRanks();
        for (PlayerAcc p : List.of(lanier, christo, toma)) {
            String r = ranks.get(p.slug);
            if (r != null) p.rank = r;
        }

        List<DataJson.PlayerJson> out = new ArrayList<>();
        for (PlayerAcc p : List.of(lanier, christo, toma, dbl)) {
            if (p.hasLines()) out.add(p.toJson(bwf, today));
        }
        return out;
    }

    /**
     * Un tournoi du fil est-il « en cours » aujourd'hui (UTC) ? Décision par DATES,
     * jamais par le texte. 1) Si le tournoi s'apparie au calendrier BWF (jeton de
     * nom commun + la date du titre tombe dans sa plage), on tranche par ses dates
     * réelles. 2) Sinon (Championnats d'Europe, Orléans…), il est réputé terminé si
     * son titre le plus récent date de plus de {@link #ONGOING_MAX_AGE_DAYS} jours.
     * Sans date exploitable, on n'affirme jamais « en cours ».
     */
    static boolean isOngoing(String cat, LocalDate lastDate,
                             List<Tournament> bwf, LocalDate today) {
        Set<String> catTokens = TextUtil.nameTokens(cat);
        Set<String> catStrong = TextUtil.minusWeak(catTokens);
        Tournament match = null;
        for (Tournament t : bwf) {
            Set<String> tTokens = TextUtil.nameTokens(t.name() + " " + t.location());
            if (TextUtil.sharedTokens(catTokens, tTokens) < 1) continue;
            boolean dateInside = lastDate != null
                    && !lastDate.isBefore(t.start()) && !lastDate.isAfter(t.end());
            if (dateInside) { match = t; break; }   // édition confirmée par la date
            // Repli par le nom seul (sans date confirmante) : exige un jeton
            // DISTINCTIF partagé — « masters » seul relierait l'Orléans Masters
            // aux Masters asiatiques et leur emprunterait leurs dates.
            if (match == null && TextUtil.sharedTokens(catStrong, TextUtil.minusWeak(tTokens)) >= 1) {
                match = t;
            }
        }
        if (match != null) {
            return !match.start().isAfter(today) && !match.end().isBefore(today);
        }
        if (lastDate == null) return false;          // pas de date → pas « en cours »
        long age = ChronoUnit.DAYS.between(lastDate, today);
        return age >= 0 && age <= ONGOING_MAX_AGE_DAYS;
    }

    /**
     * Stade atteint d'après un titre normalisé : 1er tour(1) … Vainqueur(6), ou 0
     * si aucun stade n'est nommé. Les échelons « X de finale » sont testés AVANT la
     * finale « sèche » pour ne pas confondre « quart/huitième/demi de finale » avec
     * « (atteint la) finale ». Le niveau 6 réutilise {@link #WIN_MARKERS} : une
     * victoire finale et un stade « Vainqueur » sont le même signal.
     */
    static int stageOf(String t) {
        if (TextUtil.containsAny(t, WIN_MARKERS)) return 6;
        if (TextUtil.containsAny(t, "finaliste", "prives de double", "sincline en finale")) return 5;
        boolean reachedFinal = t.contains("finale")
                && !t.contains("quart") && !t.contains("huitieme")
                && !t.contains("demi") && !t.contains("de finale");
        if (reachedFinal) return 5;
        if (t.contains("demi")) return 4;
        if (t.contains("quart")) return 3;
        if (t.contains("huitieme")) return 2;
        if (TextUtil.containsAny(t, "premier tour", "1er tour", "tombent des")) return 1;
        return 0;
    }

    /** Nom court du stade (échelle 1..5) : « 1er tour », « 1/8 de finale »… */
    private static String stageShort(int stage) {
        switch (stage) {
            case 1: return "1er tour";
            case 2: return "1/8 de finale";
            case 3: return "1/4 de finale";
            case 4: return "1/2 finale";
            case 5: return "finale";
            default: return "";
        }
    }

    /** Suffixe « Éliminé … » d'une sortie NOMMÉE (« au » au 1er tour, sinon « en »). */
    private static String outExact(int stage) {
        return stage == 1 ? "au 1er tour" : "en " + stageShort(stage);
    }
}
