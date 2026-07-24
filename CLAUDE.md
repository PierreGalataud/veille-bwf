# CLAUDE.md — Veille BWF World Tour

Contexte et règles pour travailler sur ce dépôt. À lire avant toute modification.

## Ce qu'est le projet

Un tableau de bord des tournois de badminton du **BWF World Tour** de la semaine
en cours, avec suivi prioritaire des joueurs français. **Suivi individuel réduit à
deux joueurs : Alex Lanier et Christo Popov** (`players[]`). Le statut français
d'un tournoi (`frenchStatus`) reste, lui, large : tout Français au tableau compte
(Lanier ou l'un des deux frères **Popov**).

Niveaux suivis (les 5 du World Tour) : **World Tour Finals, Super 1000, Super 750,
Super 500, Super 300**.

Deux priorités d'affichage : (1) les tournois de la semaine courante, (2) ceux où
des Français sont en lice.

## Architecture — la règle d'or

Deux programmes indépendants reliés par un seul fichier pivot. Ils ne s'accordent
que sur la forme de `public/data.json`.

```
Collecteur Java  --écrit-->  public/data.json  --lu par-->  Front React
   (collector/)               (LE CONTRAT)                  (src/)
```

**Ne jamais casser le schéma de `data.json` d'un seul côté.** Toute modif de
structure touche le collecteur ET `src/App.jsx` dans le même commit.

## Carte du dépôt

```
index.html, vite.config.js, package.json   -> config du front (Vite + React)
src/main.jsx                               -> point d'entrée React
src/App.jsx                                -> TOUT l'affichage, piloté par data.json
src/styles.css                             -> thème (maquette validée)
public/data.json                           -> le contrat de données
collector/pom.xml                          -> Maven, Java 17 (Jsoup + JUnit 5 + SDK Anthropic)
collector/cache/<slug>.json                -> cache Haiku PAR JOUEUR, clé = révision
                                              Wikipédia (committé par le workflow ;
                                              révision déjà vue = zéro appel Haiku)
collector/aliases.json                     -> mémoire d'appariement tournoi BWF ->
                                              article Wikipédia VÉRIFIÉ (committé ;
                                              entrée présente = zéro recherche, zéro Haiku)
collector/src/main/java/veille/           -> le collecteur, découpé par rôle :
  Collector.java       orchestration + écriture atomique de data.json
  BwfCalendar.java     calendrier BWF (tiers, dates, dotation)
  Wiki.java            couche réseau API MediaWiki (search / revision / wikitext)
  WikiTournament.java  Source A : frenchStatus depuis les tableaux (draws), déterministe ;
                       appariement d'article VÉRIFIÉ (dates + niveau) + mémoire Aliases
  WikiPlayer.java      Source B : rank (infobox) + historique de saison (prose→Haiku), caché
  Aliases.java         mémoire d'appariement (collector/aliases.json), lecture/écriture atomique
  LlmNet.java          appels Haiku : prose de saison -> lines[] ; appariement de dernier recours
  PlayerResults.java   orchestrateur players[] (roster Lanier + Christo Popov)
  DataJson.java        LE CONTRAT data.json en records typés (cf. ci-dessous)
  TextUtil / FrDates / Http / Tournament (utilitaires, modèle)
collector/src/test/java/veille/CollectorTest.java -> tests JUnit (fonctions pures, JAMAIS de réseau)
.github/workflows/refresh.yml              -> automatisation (tests -> collecteur ->
                                              validation jq du contrat -> commit -> Vercel)
```

## Le contrat `data.json` (schéma à jour)

Le contrat est TYPÉ côté Java : `DataJson.java` (records sérialisés tels quels par
Jackson, ordre des composants = ordre des clés). Toute modif du schéma passe par ce
fichier ET `src/App.jsx`, même commit. Le workflow VALIDE le contrat (step jq) avant
tout commit de data.json.

`tier` ∈ `"wtf" | "1000" | "750" | "500" | "300"`. `tone` ∈ `"win" | "out" | null`.

```json
{
  "generatedAt": "ISO-8601 UTC",
  "weekLabel": "Semaine du …",
  "current": [
    {
      "name": "…", "tier": "500", "location": "…", "dates": "…",
      "prize": "…", "timezone": "…", "dayLabel": "…",
      "seeds": [ { "rank": "TS1", "name": "…" } ],
      "frenchStatus": { "present": true, "title": "…", "note": "…", "confirm": false }
    }
  ],
  "players": [
    {
      "name": "…",
      "rank": "#x mondial",        // ou null si introuvable
      "lines": [
        { "label": "Dernier", "date": "5 juin", "tournament": "Open d'Indonésie",
          "stage": "Éliminé au 1er tour", "tone": "out",
          "value": "Open d'Indonésie · Éliminé au 1er tour" }   // value = repli
      ]
    }
  ],
  "upcoming": [
    { "dates": "…", "name": "…", "tier": "300", "french": "FR : à confirmer" }
  ]
}
```

`upcoming[].french` : résolu via le tableau Wikipédia pour les tournois démarrant
sous 14 jours (`UPCOMING_FR_DAYS`) -> `"FR : engagés"` (un Français au tableau)
sinon `"FR : à confirmer"`. On n'affirme JAMAIS un « aucun » pour un à-venir : un
tableau Wikipédia non figé ne prouve pas l'absence. Au-delà de 14 jours (article
d'édition souvent pas encore créé) : « à confirmer » sans sonder.

`frenchStatus.present` (tournois `current`) est à **TROIS états**, jamais confondus :
- `true`  : un Français (Lanier / Popov) figure au tableau Wikipédia (`note` = qui,
  et jusqu'où — « Christo Popov — 2e tour »).
- `false` : tableau publié (bracket présent), aucun Français.
- `null`  : article introuvable OU tableau non publié -> **statut inconnu**.

« Pas trouvé » (`null`) et « trouvé, personne » (`false`) doivent rester distincts,
dans le collecteur ET à l'affichage. `App.jsx` mappe `tier` via `TIER_COLOR` /
`TIER_LABEL` / `TIER_SHORT`.

## Carte des sources (VÉRIFIÉ — ne pas dévier)

| Donnée | Source | Accès |
|---|---|---|
| Calendrier + niveaux + prize | corporate.bwfbadminton.com/events/calendar/ | **Jsoup OK** (WordPress rendu serveur) |
| Statut français d'un tournoi (`frenchStatus`) | Wikipédia EN — **page du tournoi** (tableau/draws) | **API MediaWiki** (`Wiki.java`) |
| Rank + historique de saison (`players[]`) | Wikipédia EN — **page du joueur** (infobox + prose) | **API MediaWiki** (`Wiki.java`) |
| Tableaux / scores live | TournamentSoftware, Flashscore | **INTERDIT** — robots.txt bloque, ne pas scraper |

> **equipe-france.fr est ABANDONNÉ** (peu fiable). Ne PAS le réintroduire pour le
> suivi des Français : tout passe désormais par Wikipédia.

- **Calendrier** : table groupée par mois ; colonne CATEGORY -> niveau (on ne garde
  que `HSBC BWF World Tour …`). La ligne détail contient le GUID TournamentSoftware
  (identifiant seulement, PAS pour scraper le site).
- **Wikipédia — page tournoi** (`WikiTournament`, Source A) : on ne DEVINE jamais
  l'URL (le suffixe « (badminton) » est irrégulier) — on cherche via l'API
  (`list=search`), on retient le titre commençant par l'année visée + partageant un
  jeton, puis on lit son wikitexte. Les **Seeds** annotent chaque tête de série entre
  parenthèses (« ''(champion)'' », « ''(second round)'' »… -> `stageFr`) ; le bracket
  (`RDx-teamY`) atteste que le tableau est publié. Déterministe, **zéro LLM**.
- **Wikipédia — page joueur** (`WikiPlayer`, Source B) : `current_ranking` de
  l'infobox -> `rank` (déterministe, 1er entier = simple). La section « Career »
  est en prose -> nettoyée, filtrée sur l'année, passée à **Haiku** (`LlmNet`) qui
  en tire `lines[]`. Caché par **révision Wikipédia** (cf. plus bas).
- **Pas de score point par point** : grain « tour » seulement (2e tour / 1/4 /
  Vainqueur…), rafraîchi quelques fois par jour.

## État d'avancement

### Phase déterministe — TERMINÉE (logique de fond)
- [x] Tuyau complet : collecteur -> data.json -> Actions -> commit -> Vercel.
- [x] Calendrier réel (Jsoup) : `current` / `upcoming`, niveaux, dates, prize.
- [x] `frenchStatus` à trois états via le tableau Wikipédia du tournoi (Source A).
- [x] `players[]` : rank d'infobox + historique de saison (Source B, Wikipédia).

La logique de fond est complète et fiable. Ce qui reste côté déterministe n'est plus
de la logique mais de la **finition** : affichage, cas vides, fuseau/têtes de série
si une source simple existe. Pas de surprise, et **ne pas chercher à pousser le
déterministe plus loin sur l'interprétation du langage** (voir Limites).

### Audit qualité — APPLIQUÉ (2026-06-11)

Le code a été audité et durci (détail : historique git, commits « Audit lot 1-6 ») :
- [x] Tests JUnit sur les fonctions pures (classify, stades, dates, jetons) +
      step de tests dans le workflow. **Toute évolution des règles passe d'abord
      par un test.**
- [x] Bugs corrigés : noms de joueurs en MOTS ENTIERS (« toma » ⊄ « automatique »),
      chevauchement de dates au passage d'année, sorties collectives ≠ oppositions,
      appariement de tournoi interdit sur le seul jeton « masters ».
- [x] Écriture ATOMIQUE de data.json ; validation jq du contrat en CI ;
      `git pull --rebase` avant push ; timeout du job.
- [x] Front : replis si une clé du contrat manque, états vides, badge de fraîcheur
      piloté par `generatedAt` (12 h), auto-refresh 15 min des onglets ouverts.
- [x] Collecteur découpé en classes (comportement identique, vérifié au diff de
      data.json près de `generatedAt`).

### Sources Wikipédia + filet LLM — EN PLACE
- [x] **Source A — statut d'un tournoi (déterministe).** `WikiTournament` lit le
      tableau (draws) de la page Wikipédia et en tire `frenchStatus` à trois états.
      **Zéro LLM pour lire le draw** : l'annotation de résultat est déjà entre
      parenthèses dans les Seeds. L'APPARIEMENT de l'article est vérifié sur le
      contenu (dates + niveau, `matchesTournament`), mémorisé (`Aliases`), avec
      filet Haiku (`pickArticle`) revalidé en dernier recours. Fonctions pures
      testées (`searchQuery`, `shortlist`, `matchesTournament`, `parseLevel`,
      `parseInfoboxDates`, `parseFrenchStatus`, `stageFr`, `parsePickedTitle`,
      `Aliases.to|fromJson`) ; seuls `resolve`/`Wiki.*`/`askArticle` font du réseau.
- [x] **Source B — historique de saison (Haiku sur prose).** `WikiPlayer` lit le
      `rank` de l'infobox (déterministe) puis passe la prose « Career » de l'année
      à Haiku (`LlmNet.parseSeasonLines`, JSON strict) pour `lines[]`. **Cache
      AGRESSIF par révision Wikipédia** : avant tout appel Haiku, on lit
      `revisionId` ; si elle est identique au cache `collector/cache/<slug>.json`,
      on réutilise rank + lines SANS réseau ni token. Le cache est committé par le
      workflow (runners jetables). Échec gracieux TOTAL : sans `ANTHROPIC_API_KEY`
      (GitHub Actions secret), sur page absente ou erreur, on garde la dernière
      bonne valeur du cache (et le `rank` déterministe reste servi). On NE fige pas
      une révision dont l'extraction a échoué (retentée au run suivant). Fonctions
      pures testées (`parseCurrentRanking`, `cleanWikitext`, `seasonText`,
      `cacheTo|FromJson`, `parseSeasonLines`) ; seuls `Wiki.*`, `WikiPlayer.resolve`
      et `LlmNet.ask` font du réseau.
- [ ] **Étape B — Agent « La Dépêche des Français ».** Produit un résumé hebdo/mensuel
      des Bleus à partir des faits DÉJÀ collectés, ton pince-sans-rire. Variante agent :
      peut aller chercher l'ambiance côté presse (s'inspirer du registre, **sans
      recopier** de contenu protégé). C'est de la production de langage, pas du parsing.
      Reste un invité PAR-DESSUS les faits : s'il échoue, le tableau de bord tourne sans.

## Règles de lecture Wikipédia (déterministe en place)

- **Appariement d'article tournoi** — on ne se fie JAMAIS au seul titre. Deux
  étapes :
  1. **Pré-filtre titre** (`shortlist`) : candidats commençant par l'année visée
     (`2026 …`) ET partageant un jeton de nom, DANS L'ORDRE de pertinence. Un
     homonyme d'un autre sport passe ce filtre — c'est voulu, d'où l'étape 2.
  2. **Vérification du contenu** (`matchesTournament`, fonction pure) : l'infobox
     doit confirmer le **niveau** (`level = G2L<n>` -> tier : L1=wtf, L2=1000,
     L3=750, L4=500, L5=300) ET les **dates** (`dates = …` ancrées sur l'année,
     chevauchement ± 1 jour avec les dates BWF). Un seul signal manquant ou
     contradictoire -> rejet, on teste le candidat suivant.

  **Contre-exemple (anti-régression testé)** : pour le tournoi BWF Super 500 du
  9–14 juin (Australian Open badminton), la recherche renvoie aussi « 2026
  Australian Open » (TENNIS) — même année, jeton « australian » partagé. Il est
  REJETÉ : son infobox n'a pas de niveau badminton (`category = Grand Slam`, pas de
  `level`), pas de champ `dates` (tennis = `date`), et ses dates sont en janvier.
  Seul « 2026 Australian Open (badminton) » passe.

  Aucun candidat vérifié -> `frenchStatus` null (jamais un faux match). Tout
  appariement accepté est **mémorisé** dans `collector/aliases.json` : au run
  suivant, entrée présente -> wikitexte direct, **zéro recherche `list=search`**.
- **Noms de joueurs en MOTS ENTIERS** (`TextUtil.hasWord`), jamais en sous-chaîne :
  « popov » matche « Toma Junior Popov » mais « christo » ⊄ « Christophe ».
- **Stade depuis l'annotation** (`stageFr`) : les Seeds Wikipédia annotent le
  résultat entre parenthèses. Ordre de test : `champion` -> `quarter`/`semi` AVANT
  `final` (qu'ils contiennent) -> `runner`/`final` -> `third`/`second`/`first`. Pas
  d'annotation = joueur encore en lice -> « En lice » (rang 0). Le stade le plus
  avancé l'emporte si le joueur apparaît en plusieurs disciplines.
- **Rank depuis l'infobox** (`WikiPlayer.parseCurrentRanking`) : 1er entier du champ
  `current_ranking` (le simple ; le double suit après `<br />`). Ne jamais confondre
  avec `highest_ranking` ni `current_ranking_date`. Champ absent -> `null`.
- **Prose de saison** (`WikiPlayer.seasonText`) : on NETTOIE le wikitexte AVANT de
  filtrer par année — sinon « 2026 » traîne dans les dates d'accès des `<ref>` (faux
  positifs). On isole « Career » jusqu'au prochain titre de niveau 2, puis on garde
  les paragraphes citant l'année.

## Limites assumées (vérifiées, ne pas re-creuser)

- **Périmètre `players[]` = Lanier + Christo Popov uniquement.** Toma Junior Popov
  et le double Delrue/Gicquel ne sont plus suivis individuellement (choix produit).
  Ils comptent encore pour `frenchStatus` (tout « Popov » au tableau = Français).
- **`lines[]` viennent de la prose Wikipédia via Haiku** : sans clé API, elles
  restent celles du cache (ou vides) — le `rank` déterministe, lui, s'affiche
  toujours. Le grain dépend de ce que Wikipédia écrit (une élimination non relatée
  n'apparaît pas ; on n'invente rien).
- **Statut d'un à-venir** : un tableau Wikipédia non figé ne prouve pas l'absence
  de Français -> `upcoming[].french` ne dit jamais « aucun », seulement « engagés »
  ou « à confirmer ».

## Quand l'IA sert — et quand non

L'IA n'a sa place qu'où il n'y a pas de bonne réponse unique calculable :
- extraire des résultats d'une **prose** de saison (Source B) ;
- **apparier un article en DERNIER RECOURS** (`LlmNet.pickArticle`) : seulement si
  la vérification déterministe (dates + niveau) n'a retenu aucun candidat. Haiku
  choisit alors parmi les titres candidats ; son choix est **revalidé** par
  `matchesTournament` (il ne peut pas inventer un titre) puis **mémorisé** dans
  `aliases.json` — plus jamais d'appel Haiku sur ce tournoi ;
- produire un résumé avec un ton (étape B, à venir).

**Tout le reste reste déterministe** : fetch, filtrage par date/niveau, appariement
d'article *quand l'infobox suffit*, lecture des tableaux (Source A) et du `rank`
d'infobox. Ne mets pas d'appel LLM là où une règle suffit — les tableaux Wikipédia
sont déjà structurés, ne les fais PAS lire par Haiku. Le LLM est un invité, jamais
le moteur : si l'IA échoue, l'appli fonctionne sans elle (rank + frenchStatus restent).

## Commandes

```bash
# Front
npm install
npm run dev      # http://localhost:5173
npm run build

# Collecteur : tests puis régénération de public/data.json
mvn -f collector/pom.xml test
mvn -f collector/pom.xml compile exec:java -Dexec.args="public/data.json"
```

Après modif du collecteur, **toujours** : lancer les tests, relancer le collecteur,
et vérifier que le diff de `data.json` est celui attendu (un refactor « comportement
identique » doit donner un diff vide hors `generatedAt`) avant de committer.

## Déploiement

```
cron / clic  ->  GitHub Actions  ->  collecteur  ->  commit data.json
                                                       | (push)
                                                       v
                                          Vercel rebuild + déploie
```

- Vercel ne sert que du statique : **jamais** de Java ni d'appel API côté Vercel.
  Les appels LLM (étapes A/B) se font dans le collecteur, sous GitHub Actions.
- Secrets (ex. `ANTHROPIC_API_KEY`) -> **GitHub Actions secrets**, jamais dans Vercel
  ni dans le code.
- Le workflow ne commite que si `data.json` a changé, ET seulement après : tests
  JUnit verts + validation jq du contrat. Un data.json invalide n'est JAMAIS commité.
- Un déploiement Vercel ne rafraîchit PAS les onglets déjà ouverts : c'est
  l'auto-refresh du front (15 min) qui s'en charge ; le badge passe à « Données
  anciennes » si `generatedAt` a plus de 12 h (signal qu'un run CI a échoué).

## Garde-fous

- **Ne pas coder en dur de données badminton dans `src/App.jsx`** : tout vient du JSON.
- **Échec gracieux** : si une source (ou un appel LLM) échoue, ne réécris PAS un
  `data.json` vide ou cassé. Garde la dernière bonne version ou sors en erreur.
  L'écriture est ATOMIQUE (temp + rename, `Collector.writeAtomic`) : ne pas revenir
  à un `Files.writeString` direct.
- **Ne jamais scraper TournamentSoftware ni Flashscore** (robots.txt).
- Usage personnel : User-Agent explicite (pointe vers ce dépôt), requêtes espacées —
  TOUT accès Wikipédia passe par `Wiki` (API MediaWiki, throttle commun) ; ne pas
  appeler `Http` en direct pour Wikipédia. On ne DEVINE jamais une URL d'article.
- Contenu en français, **UTF-8** partout. PIÈGE : le séparateur de milliers de
  `prize` est une espace insécable fine **U+202F littérale** dans
  `BwfCalendar.parsePrize` (invisible à l'œil) — ne pas la « corriger » en espace
  simple, le diff de data.json le révélerait.
- **Toute nouvelle méthode de LOGIQUE arrive AVEC ses tests JUnit** (parsing,
  classement, appariement, dates, normalisation…) — même commit. Pour un bug :
  test ROUGE d'abord, correction ensuite. Si la logique est enfouie dans une
  méthode d'orchestration ou de réseau, l'EXTRAIRE pour la rendre testable
  (modèle : `parseFrenchStatus` / `parseSeasonLines` extraits de leur `resolve`
  réseau). Seuls l'orchestration et les accès réseau ne se testent pas unitairement.
- Les tests JUnit ne font **jamais de réseau** (fonctions pures et fixtures
  uniquement) : un test qui fetch est un bug de test.
- **Test anti-régression à conserver** : `matchesTournamentRejetteLarticleTennis`
  (classe `StatutTournoi`) — l'article tennis « 2026 Australian Open » doit rester
  rejeté pour le tournoi BWF Super 500 du 9–14 juin. Ne pas assouplir
  `matchesTournament` au point de le laisser passer.

## Identités à ne pas confondre

- **Alex Lanier** (et non « Lasnier »). Suivi individuel. Article Wikipédia :
  `Alex Lanier`.
- « Popov » = **deux frères** : **Christo Popov** (suivi individuel, article
  `Christo Popov`) et **Toma Junior Popov** (plus suivi dans `players[]`, mais
  compté pour `frenchStatus`). Au tableau d'un tournoi, les deux comptent comme
  Français.
- Le double **Delphine Delrue / Thom Gicquel** n'est plus suivi (retiré avec le
  passage à Wikipédia).
