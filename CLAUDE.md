# CLAUDE.md — Veille BWF World Tour

Contexte et règles pour travailler sur ce dépôt. À lire avant toute modification.

## Ce qu'est le projet

Un tableau de bord des grands tournois internationaux de badminton de la semaine
en cours, avec suivi prioritaire des joueurs français. **Suivi individuel réduit à
deux joueurs : Alex Lanier et Christo Popov** (`players[]`). Le statut français
d'un tournoi (`frenchStatus`) reste, lui, large : tout Français au tableau compte
(Lanier ou l'un des deux frères **Popov**).

Niveaux suivis — les 5 du World Tour : **World Tour Finals, Super 1000, Super 750,
Super 500, Super 300** — PLUS les deux grands championnats individuels seniors :
**Championnats du monde** (`monde`) et **Championnats d'Europe** (`europe`).

### Périmètre : catégories du calendrier BWF (VÉRIFIÉ sur la liste officielle)

Le filtre se fait sur la **CATÉGORIE** de la ligne, pas sur le nom (`BwfCalendar.tierOf`).

| Catégorie BWF | tier | |
|---|---|---|
| `HSBC BWF World Tour Finals` | `wtf` | ✅ |
| `HSBC BWF World Tour Super 1000 / 750 / 500 / 300` | `1000`…`300` | ✅ |
| `Grade 1 – Individual Tournaments` | `monde` | ✅ Championnats du monde |
| `Continental Individual Championships` | `europe` | ✅ **européen seulement** |
| `Grade 1 – Individual Junior Tournaments` | — | ❌ Mondial **junior** |
| `Grade 1 – Individual Senior Tournaments` | — | ❌ **vétérans** |
| `Grade 1 – Junior Team Tournaments` · `Grade 1 – Team Tournaments` | — | ❌ par équipes |
| `Continental Junior Individual Championships` | — | ❌ |
| `Continental Junior Team Championships` · `Continental Team Championships` | — | ❌ |
| `BWF Tour Super 100`, `International Challenge`, `International Series`, `Future Series`, `Junior *`, `Multi-Sport Games`, `Continental Individual Games` | — | ❌ |

**Les libellés à rejeter ne diffèrent que d'UN mot de ceux à garder.** D'où deux
régimes d'appariement dans `tierOf`, volontairement différents :
- World Tour : **préfixe** `HSBC BWF World Tour` + niveau — souple (un changement
  de sponsor ne doit pas vider le tableau de bord), et sans risque de confusion
  (aucune catégorie junior ne porte ce préfixe) ;
- championnats : libellé **EXACT** normalisé (minuscules, accents, tiret U+2013 du
  « Grade 1 – … » ramené au tiret simple, espaces compactés). Un `contains()` ferait
  passer le Mondial junior — verrouillé par `tierOfRejetteLesVariantesJuniorsSeniorsEtEquipes`.

**Filtre régional** (`BwfCalendar.isEuropean`) : `Continental Individual Championships`
couvre TOUS les continents (All Africa, Badminton Asia, Pan Am, Oceania…). On ne garde
que l'européen : mot « European »/« Europe » dans le nom, **ou** pays hôte membre de
Badminton Europe. Sans ce filtre, le tableau afficherait les championnats d'Afrique.

**Pas de dotation** hors World Tour : la BWF n'affiche aucun prize money pour ces
événements → `prize` vaut `"—"` (`BwfCalendar.formatPrize` renvoie `null` quand le
bouton n'a pas de montant, jamais une exception ni un montant inventé).

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
collector/calendar.json                    -> mémoire du calendrier de la saison
                                              (committé) : la source BWF OUBLIE un
                                              tournoi dès sa finale jouée
collector/src/main/java/veille/           -> le collecteur, découpé par rôle :
  Collector.java       orchestration + écriture atomique de data.json
  BwfCalendar.java     calendrier BWF (tiers, dates, dotation)
  Wiki.java            couche réseau API MediaWiki (search / revision / wikitext)
  WikiTournament.java  Source A : frenchStatus depuis les tableaux (draws) + champions
                       depuis l'infobox, déterministe ; appariement d'article VÉRIFIÉ
                       (dates + niveau) + mémoire Aliases
  WikiPlayer.java      Source B : rank (infobox) + historique de saison (prose→Haiku), caché
  Aliases.java         mémoire d'appariement (collector/aliases.json), lecture/écriture atomique
  CalendarMemory.java  mémoire du calendrier de la saison (collector/calendar.json) :
                       fusionne la source et ce qu'elle a oublié, écriture atomique
  LlmNet.java          appels Haiku : prose de saison -> lines[] ; appariement de dernier recours
  PlayerResults.java   orchestrateur players[] (roster Lanier + Christo Popov)
  DataJson.java        LE CONTRAT data.json en records typés (cf. ci-dessous)
  Window.java          fenêtre temporelle : « aujourd'hui » en Europe/Paris + bornes
                       INCLUSIVES current / upcoming / passé (cf. ci-dessous)
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

`tier` ∈ `"wtf" | "1000" | "750" | "500" | "300" | "monde" | "europe"` (cf. la table
des catégories plus haut ; le step jq du workflow valide cette liste, **la tenir à
jour des deux côtés**). `status` ∈ `"en_cours" | "termine"`.
`tone` ∈ `"win" | "out" | null`.
`medal` (échelle badminton — deux demi-finalistes ont le bronze, pas de petite
finale) : `🥇` vainqueur · `🥈` finaliste · `🥉` demi-finaliste · `⚫` éliminé avant
les demies (1/4, 1/8, tours, stade non précisé) · `🎯` encore en lice. Calculée
DÉTERMINISTIQUEMENT côté collecteur (`LlmNet.medalFor`, depuis `stage`) — jamais par
Haiku ni deviné par le front.

```json
{
  "generatedAt": "ISO-8601 UTC",
  "weekLabel": "Semaine du …",
  "current": [                        // la TÊTE D'AFFICHE, pas « la semaine »
    {
      "name": "…", "tier": "500", "location": "…", "dates": "…",
      "prize": "…", "timezone": "…", "dayLabel": "…",
      "status": "en_cours",           // ou "termine" (cf. Window.featured)
      "champions": null,              // état termine ET 5 disciplines publiées, sinon null
      "seeds": [ { "rank": "TS1", "name": "…" } ],
      "frenchStatus": { "present": true, "title": "…", "note": "…", "confirm": false }
    },
    {
      "…": "…", "status": "termine",
      "champions": {                  // TOUT OU RIEN : les 5, ou null
        "ms": { "name": "Chou Tien-chen",  "country": "TPE" },
        "ws": { "name": "Akane Yamaguchi", "country": "JPN" },
        "md": { "name": "Fajar Alfian / Muhammad Shohibul Fikri", "country": "INA" },
        "wd": { "name": "Liu Shengshu / Tan Ning", "country": "CHN" },
        "xd": { "name": "Guo Xinwa / Chen Fanghui", "country": "CHN" }
      }
    }
  ],
  "players": [
    {
      "name": "…",
      "rank": "#x mondial",        // ou null si introuvable
      "lines": [                         // triées du + récent au + ancien (collecteur)
        { "label": "Dernier", "date": "26 – 31 mai", "tournament": "Singapore Open",
          "stage": "Vainqueur", "medal": "🥇", "tone": "win",
          "value": "Singapore Open · Vainqueur" }   // value = repli
      ]     // date issue du calendrier BWF (nullable) ; label conservé mais PLUS affiché
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
  et jusqu'où — « Christo Popov — 2e tour »). **PRÉSENCE et PARCOURS sont deux
  choses** : la `note` ne liste que les joueurs dont le stade est réellement annoté
  (cf. `stageFr`). Aucun stade lisible pour aucun d'eux -> `present` reste `true`,
  `note` = « Français au tableau, résultats non disponibles sur Wikipédia. » — jamais
  une note vide, jamais un « aucun Français », jamais un stade inventé.
- `false` : tableau publié (bracket présent), aucun Français.
- `null`  : article introuvable OU tableau non publié -> **statut inconnu**.

« Pas trouvé » (`null`) et « trouvé, personne » (`false`) doivent rester distincts,
dans le collecteur ET à l'affichage. `App.jsx` mappe `tier` via `TIER_COLOR` /
`TIER_LABEL` / `TIER_SHORT` et liste `ALL_TIERS` (filtres de niveau) : **tout
nouveau tier s'ajoute aux quatre, plus une couleur `--t-<tier>` dans `styles.css`**.

## Fenêtre temporelle — bornes INCLUSIVES, fuseau Europe/Paris (`Window.java`)

Un seul endroit décide de « aujourd'hui » et de current / upcoming / passé :
**`Window`**. Le reste du collecteur ne recalcule JAMAIS ces bornes à la main.

- **Référence de temps = `Europe/Paris`, jamais UTC.** Le collecteur tourne sous
  GitHub Actions (horloge UTC) mais le lecteur est en France : la journée affichée
  doit être SA journée. Un run à 22 h 30 UTC est déjà le lendemain à Paris (UTC+2
  l'été) ; en UTC brut, la fenêtre, le « Jour 4 / 6 » et le libellé de semaine
  étaient décalés d'un jour pendant ce créneau. `Window.ZONE` est un `ZoneId` (la
  bascule heure d'été / hiver est gérée) — **ne jamais coder un offset en dur, ni
  rappeler `LocalDate.now(ZoneOffset.UTC)`** : on passe par `Window.today()`.
  `generatedAt`, lui, reste un instant ISO-8601 **UTC** (c'est le contrat).
- **Bornes inclusives des deux côtés** : `isCurrent` = `start <= today <= end`. Le
  jour de la finale (dernier jour, souvent le dimanche) le tournoi reste dans
  `current` TOUTE la journée ; il n'en sort qu'à **J+1**. Même borne pour le passé
  (`isPast` = `end < today`) : c'est le seuil de bascule vers « semaine dernière »
  (à venir, la Dépêche DOIT l'utiliser, pas re-comparer les dates elle-même). Le
  jour d'ouverture est déjà `current`, jamais `upcoming` (`isUpcoming` = `start >
  today`). L'horizon FR des à-venir (`startsWithin`, `UPCOMING_FR_DAYS` = 14) inclut
  lui aussi sa borne.
- Aucune comparaison stricte sur une borne : dans `Window`, `isAfter` / `isBefore`
  sont niés. Une comparaison stricte sur `end` fait disparaître le tournoi le jour
  de sa finale — le bug d'origine, verrouillé par les tests `FenetreTemporelle`
  (dates figées, instants UTC figés, aucun accès à l'horloge ni au réseau).

### Tête d'affiche (`Window.featured`) — `current[]` n'est pas « la semaine »

Le tableau de bord ne dit JAMAIS « aucun tournoi » tant qu'un tournoi récent peut
être montré. `current[]` = la tête d'affiche, choisie ainsi :

1. des tournois sont en cours (`start <= today <= end`) → tous, `status = "en_cours"`
   (deux niveaux peuvent se chevaucher la même semaine) ;
2. sinon, le tournoi le plus **récemment démarré** parmi les terminés, SEUL, avec
   `status = "termine"` et ses `champions`. « Le plus récemment démarré » EST la
   règle « aucun tournoi n'a commencé après lui » ;
3. aucun tournoi encore commencé → liste vide (seul cas d'état vide côté front).

**Un tournoi ne quitte donc pas l'affiche à sa date de fin, mais au démarrage du
suivant.** Une fois sorti, il pourra alimenter la Dépêche « semaine dernière »
(`isPast`). `dayLabel` suit l'état : « Jour 4 / 6 · 24 juillet » en cours,
« Terminé · 26 juillet » ensuite (jamais un « Jour 6 / 6 » trompeur).

### La source oublie les tournois terminés (`CalendarMemory`)

**VÉRIFIÉ** : la page calendrier BWF ne publie que les tournois À VENIR — une
édition en disparaît dès sa finale jouée (le 29 juillet 2026, le China Open du
21–26 juillet n'y était déjà plus). Sans mémoire, le lendemain d'une finale il n'y
a donc RIEN à mettre en tête d'affiche — c'est la cause réelle du « aucun tournoi
en cours » du lundi, et aussi des dates manquantes dans `players[].lines`.

`CalendarMemory` (fichier `collector/calendar.json`, committé par le workflow comme
`aliases.json` et le cache joueur) garde les tournois DÉJÀ VUS et les fusionne avec
la source à chaque run : clé `nom@début`, la version fraîche gagne toujours (la
source reste l'autorité), et on n'y garde que la **saison courante** — une édition
N-1 daterait à tort une ligne de la saison N (`LlmNet.matchTournament` retient
l'édition passée la plus récente). Le collecteur travaille ensuite sur ce
calendrier fusionné, jamais sur le seul fetch.

## Carte des sources (VÉRIFIÉ — ne pas dévier)

| Donnée | Source | Accès |
|---|---|---|
| Calendrier + catégories + prize | corporate.bwfbadminton.com/events/calendar/ | **Jsoup OK** (WordPress rendu serveur) |
| Statut français d'un tournoi (`frenchStatus`) | Wikipédia EN — **page du tournoi** (tableau/draws) | **API MediaWiki** (`Wiki.java`) |
| Vainqueurs des 5 disciplines (`champions`) | Wikipédia EN — **page du tournoi** : bloc Champions de l'infobox, à défaut tableau « Medalists » (Mondiaux) | **API MediaWiki** (même lecture que `frenchStatus`) |
| Rank + historique de saison (`players[]`) | Wikipédia EN — **page du joueur** (infobox + prose) | **API MediaWiki** (`Wiki.java`) |
| Tableaux / scores live | TournamentSoftware, Flashscore | **INTERDIT** — robots.txt bloque, ne pas scraper |

> **equipe-france.fr est ABANDONNÉ** (peu fiable). Ne PAS le réintroduire pour le
> suivi des Français : tout passe désormais par Wikipédia.

- **Calendrier** : table groupée par mois ; colonne CATEGORY -> tier (cf. la table
  des catégories en tête de fichier : les 5 `HSBC BWF World Tour …`, plus
  `Grade 1 – Individual Tournaments` et `Continental Individual Championships`).
  La ligne détail contient le GUID TournamentSoftware (identifiant seulement, PAS
  pour scraper le site).
- **Wikipédia — page tournoi** (`WikiTournament`, Source A) : on ne DEVINE jamais
  l'URL (le suffixe « (badminton) » est irrégulier) — on cherche via l'API
  (`list=search`), on retient le titre commençant par l'année visée + partageant un
  jeton, puis on lit son wikitexte. Le **bracket** (`RDx-teamY`) atteste que le
  tableau est publié ET porte le parcours de chaque joueur (le gras désigne le
  vainqueur de chaque match -> `parseBracketRuns`) ; les **Seeds**, qui n'annotent
  que les têtes de série (« ''(champion)'' », « ''(second round)'' »… -> `stageFr`),
  ne servent plus qu'en repli. Déterministe, **zéro LLM**. La MÊME lecture rend
  aussi les **champions** (infobox), en un seul appel réseau.
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
- [x] Tête d'affiche à deux états (`en_cours` / `termine`) + champions des 5
      disciplines : un tournoi reste affiché jusqu'au démarrage du suivant, et la
      mémoire du calendrier (`CalendarMemory`) compense l'oubli de la source BWF.
- [x] Périmètre élargi aux **Championnats du monde** et **d'Europe** (tiers `monde`
      et `europe`) : catégories du calendrier, appariement d'article sans code de
      niveau, champions depuis le tableau « Medalists », filtre régional des
      continentaux. Vérifié en rejouant le collecteur aux 20 et 25 août 2026
      (Mondiaux de New Delhi) et au 10 avril 2026 (Euros de Huelva).

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
      `rank` de l'infobox (déterministe) puis passe la prose de la SAISON de l'année
      (découpée par section, cf. règles) à Haiku (`LlmNet.parseSeasonLines`, JSON
      strict) pour `lines[]` : filtre `year` déterministe + tri par date + `medal`
      calculée du stade. **Cache AGRESSIF par révision Wikipédia + `formatVersion`** :
      avant tout appel Haiku, on lit `revisionId` ; si elle est identique au cache
      `collector/cache/<slug>.json` ET que le format n'a pas changé, on réutilise
      rank + lines SANS réseau ni token. Le cache est committé par le workflow
      (runners jetables). Échec gracieux TOTAL : sans `ANTHROPIC_API_KEY` (GitHub
      Actions secret), sur page absente ou erreur, on garde la dernière bonne valeur
      du cache (et le `rank` déterministe reste servi). On NE fige pas une révision
      dont l'extraction a échoué (retentée au run suivant). Les DATES viennent du
      calendrier BWF (`matchTournament`, appariement STRICT + cohérence chrono),
      jamais de Haiku (règle « pas de fait absent de la source ») ; nom d'affichage
      = nom du calendrier ou table `frenchName`. Fonctions pures testées
      (`parseCurrentRanking`, `cleanWikitext`, `seasonText`, `declaredYear`,
      `cacheTo|FromJson`, `parseSeasonLines`, `coreTokens`, `matchTournament`,
      `frenchName`, `medalFor`) ; seuls `Wiki.*`, `WikiPlayer.resolve` et
      `LlmNet.ask` font du réseau.
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
- **Appariement d'un CHAMPIONNAT (`monde`, `europe`) — le niveau ne sert plus de
  preuve** (`matchesChampionship`). VÉRIFIÉ : les Mondiaux 2026 écrivent `level = 1`
  (pas `G2L<n>`) et les Euros 2026 laissent le champ VIDE. `parseLevel` renvoie donc
  `null` et la règle habituelle rejetterait le bon article. On remplace ce signal par
  trois autres, **tous exigés**, sans rien relâcher :
  1. le modèle **`{{Infobox badminton event}}`** (casse libre) — c'est LUI qui prend
     le relais du niveau pour recaler un homonyme d'un autre sport ;
  2. le mot « championship(s) » dans l'identité (titre de l'article, à défaut le
     champ `| name =` de l'infobox, déballé de son `{{nowrap|…}}`) ;
  3. le bon périmètre — « world » pour `monde`, « europe(an) » pour `europe` — et
     **aucun mot d'édition voisine** : `junior`, `youth`, `senior`, `para`, `team`,
     `qualification`, `u15/u17/u19`. Sans cette liste, « 2026 BWF World Junior
     Championships » et « 2026 BWF Para-Badminton World Championships » passeraient.
  Les DATES restent vérifiées comme pour tout le monde (± 1 jour).
  La **requête de recherche** garde « bwf » et « world » pour ces tiers
  (`searchQuery(name, year, tier)`) : ce ne sont pas des sponsors mais l'identité de
  l'épreuve — sans eux, « 2026 championships badminton » place les championnats
  d'Asie devant les Mondiaux.
- **Champions d'un championnat : le tableau « Medalists »** (`parseMedalists`).
  VÉRIFIÉ sur les Mondiaux 2026 : la page principale n'a NI bloc Champions dans
  l'infobox NI tableau — les draws vivent dans des sous-articles par discipline
  (`… – Men's singles`). Le seul résultat publié est le tableau des médaillés :
  `parseChampions` bascule dessus quand l'infobox est muette, **avec le même tout ou
  rien** (moins de 5 disciplines -> `null`). Deux écritures de cellule coexistent :
  `{{flagmedalist|[[X]]|FRA}}` (simple, le pays est le DERNIER segment) et
  `{{flagcountry|CHN}}` + deux liens (paire). PIÈGE testé : « Men's singles » est un
  SUFFIXE de « Women's singles » — la recherche de la ligne exige une borne à gauche.
  Les Euros, eux, ont bien le bloc d'infobox ET les draws : ils passent par le chemin
  habituel.
- **`frenchStatus` sans tableau : repli sur le PODIUM.** Faute de draw sur la page des
  Mondiaux, on lit les médaillés. Un Français au podium -> `present = true`, titre
  « Français sur le podium ». Un Français ÉLIMINÉ avant les demies y est invisible :
  aucun Français au podium ne prouve donc rien -> on reste à `null` (inconnu),
  **jamais un « aucun »**.
- **Champions = bloc de l'infobox, TOUT OU RIEN** (`WikiTournament.parseChampions`).
  Les vainqueurs sont des champs nommés de `{{Infobox badminton event}}` : `MS`,
  `WS`, puis `MD1`/`MD2`, `WD1`/`WD2`, `XD1`/`XD2` pour les paires, chacun doublé
  d'un `country_<champ>`. C'est du champ nommé, comme le niveau et les dates :
  **zéro LLM**. Deux pièges, tous deux testés :
  1. **Délai Wikipédia** : pendant le tournoi les champs EXISTENT mais sont VIDES,
     puis se remplissent discipline par discipline après les finales. Moins de 5
     disciplines (ou une paire à moitié saisie) -> on renvoie `null`, le front dit
     « résultats en attente ». **Jamais un palmarès partiel donné pour définitif** ;
     le passage suivant du collecteur le complètera.
  2. **Champ vide et regex gourmande** : `\s*` autour du `=` saute la fin de ligne
     et lit le champ SUIVANT (`country_MS` deviendrait le vainqueur du simple).
     D'où `[ \t]*` dans `infoboxField` — ne pas « simplifier » en `\s*`.
  Le pays peut manquer (nom affiché seul) ; une paire de deux nationalités donne
  « INA / JPN ». Les champions sont affichés **quel que soit le pays du vainqueur**
  (il est rarement français — c'est voulu).
- **Noms de joueurs en MOTS ENTIERS** (`TextUtil.hasWord`), jamais en sous-chaîne :
  « popov » matche « Toma Junior Popov » mais « christo » ⊄ « Christophe ».
- **LE PARCOURS VIENT DU BRACKET** (`parseBracketRuns`), pas des annotations. Les
  Seeds n'annotent que les TÊTES DE SÉRIE : un Français non classé n'y a jamais une
  ligne, et l'ancien défaut « En lice » le faisait passer pour encore en course —
  faux dès le tournoi terminé (VÉRIFIÉ : Toma Junior Popov s'affichait « En lice »
  au LI-NING China Masters 2026, fini ; il était même FINALISTE du China Open).
  Le bracket, lui, décrit tout le monde, et **Wikipédia met en GRAS le vainqueur de
  chaque match**. Structure régulière par discipline (VÉRIFIÉ China Masters, Euros,
  China Open, Taipei, Korea Masters) :

  ```
  == Men's singles ==
  === Seeds ===            annotations (repli seulement)
  === Finals ===           {{4TeamBracket}}    RD1 = 1/2, RD2 = finale
  === Top half ===
  ==== Section 1 ====      {{8TeamBracket}}    RD1 = 1er tour … RD3 = 1/4
  ==== Section 2 ====      (ou {{16TeamBracket…}} pour un tableau de 64)
  === Bottom half ===      ==== Section 3/4 ====
  ```

  On raisonne en **tours RESTANTS jusqu'au titre** (0 = vainqueur, 1 = finale,
  2 = demie, 3 = quart…), ce qui rend la taille du tableau sans importance :
  `depth(RDk) = (log2(N) - k) + base`, avec `base = 1` en phase finale et `base = 3`
  en section (son vainqueur file en demie). Les tours au-delà des quarts sont
  nommés depuis le DÉBUT du tableau, dont le nombre de tours est lu sur la
  **STRUCTURE** des brackets, jamais sur les seules cases françaises (sinon un
  tableau où aucun Bleu ne joue le 1er tour serait pris pour un tableau plus petit,
  et « 2e tour » deviendrait « 1er tour »). Bracket sous un en-tête inconnu
  (`=== Qualification ===`) -> **ignoré** : un tour de qualif n'est pas un tour de
  tableau.
- **Trois sorts, et le PIÈGE du match non joué** (`Fate`) : gras = match GAGNÉ ;
  adversaire en gras = ÉLIMINÉ à ce tour ; **aucun gras des deux côtés = match PAS
  ENCORE JOUÉ**. Ce troisième état est indispensable : dès qu'un joueur gagne,
  l'éditeur l'inscrit AUSSITÔT dans la case du tour suivant, qui n'est évidemment
  pas en gras. Sans `PENDING`, un joueur sur le point de jouer son quart serait lu
  « éliminé en quart ». `WON` (hors finale) et `PENDING` donnent tous deux
  « encore en lice (<prochain tour>) ». Le tour le plus profond atteint l'emporte,
  toutes disciplines confondues.
- **Stade depuis l'annotation** (`stageFr`) — REPLI, pour ce que le bracket ne dit
  pas (forfait, joueur absent du tableau). Ordre de test : `champion` ->
  `quarter`/`semi` AVANT `final` (qu'ils contiennent) -> `runner`/`final` ->
  `third`/`second`/`first`. Une annotation non répertoriée est rendue telle quelle
  (rang 0) : elle vient de la source, on ne la jette pas. **Le bracket est
  prioritaire** quand les deux savent quelque chose.
- **Pas d'annotation ET pas de bracket = AUCUNE information, jamais un stade par
  défaut.** `stageFr` renvoie `null` (annotation absente, vide, ou sans aucune
  lettre — « (2) » est un numéro de tête de série, pas un résultat) et
  `parseFrenchStatus` n'affiche pas ce joueur.
- **Les italiques de l'annotation sont OPTIONNELLES** (regex `LINK`). VÉRIFIÉ sur
  plusieurs articles (China Open, Championnats d'Europe) : Wikipédia écrit
  `''(quarter-finals)''` **mais** `'''[[X]] (champion)'''` — le vainqueur est en gras
  et son annotation n'a PAS d'italiques. Exiger `''` faisait rater précisément le
  stade le plus important : le champion ressortait « En lice ». On tolère donc
  espaces et apostrophes de mise en forme avant la parenthèse, **mais pas de saut de
  ligne** (une annotation est sur sa ligne ; sinon on attraperait une parenthèse de
  prose plus bas), et une parenthèse **sans aucune lettre** (« (2) », un numéro de
  tête de série) n'est pas un stade.
- **Rank depuis l'infobox** (`WikiPlayer.parseCurrentRanking`) : 1er entier du champ
  `current_ranking` (le simple ; le double suit après `<br />`). Ne jamais confondre
  avec `highest_ranking` ni `current_ranking_date`. Champ absent -> `null`.
- **Prose de saison — DÉCOUPAGE PAR SAISON, pas par mention** (`WikiPlayer.seasonText`).
  Le bug à éviter : un paragraphe qui raconte 2025 mais cite « 2026 » en passant,
  retenu en entier, fait extraire DEUX saisons par Haiku (le défaut est en amont du
  LLM). On isole donc la SECTION de l'année : on nettoie « Career » (retirer les
  `<ref>` AVANT toute détection d'année — leurs dates d'accès polluent), puis on
  parcourt les blocs en suivant une « saison active » posée par un sous-titre
  d'année (`=== 2026 ===`) ou par un paragraphe qui OUVRE sur l'année
  (`declaredYear` : année dans les ~40 premiers caractères). On ne garde que les
  blocs de l'année visée ; une année citée en passant ne déclare rien.
- **Double filet côté extraction** : (1) le prompt Haiku somme « n'extrais QUE la
  saison <année> » et demande un champ `year` par ligne ; (2) `parseSeasonLines`
  REJETTE toute ligne dont `year` ≠ année visée.
- **DATES : jamais par Haiku — règle « ne jamais demander au LLM un fait absent de
  sa source ».** La prose Wikipédia ne contient PAS les dates ; comme le schéma en
  exigeait une, Haiku fabriquait une valeur plausible et INSTABLE (Singapour est
  passé de « octobre » à « août » entre deux runs sur la même révision). Correctif :
  le prompt n'exige plus `date` (schéma = `year`, `tournament`, `stage`, `tone` ;
  consigne « n'invente jamais de date, omets tout fait absent »), et le nom de
  tournoi est gardé en langue SOURCE (anglais) — pas traduit — pour l'apparier au
  calendrier BWF.
- **APPARIEMENT STRICT du tournoi** (`LlmNet.matchTournament`, dans l'esprit de
  `matchesTournament`) : un simple jeton partagé ne suffit PAS (sinon « Japan Open »
  attrape « Kumamoto Masters Japan » et « India Open » attrape « Syed Modi India
  International »). On exige (1) que le NOYAU du nom extrait (jetons distinctifs,
  sponsors/années/niveaux retirés mais type d'épreuve `open`/`masters`/… CONSERVÉ,
  cf. `coreTokens`) soit un SOUS-ENSEMBLE des jetons du tournoi du calendrier ; (2)
  au moins un jeton non générique (« Open » seul n'apparie rien) ; (3) **cohérence
  chronologique** : un résultat de saison est un fait passé, un tournoi commençant
  APRÈS `today` ne peut l'expliquer et est écarté (c'est ce qui bloquait les dates de
  novembre pour un titre de juillet). Plusieurs éditions passées valides -> la plus
  récente. Aucun appariement fiable -> `date: null`.
- **NOM d'affichage en français** : un tournoi apparié prend le nom du CALENDRIER
  (déjà en usage côté current/upcoming), pas l'anglais de Haiku. Hors World Tour
  (Coupe Thomas, Championnats d'Europe, … par équipes) -> petite table de traduction
  en dur (`LlmNet.frenchName`, appariée par jetons, robuste aux variantes). Aucun
  équivalent connu -> nom d'origine (on n'invente pas de traduction).
- **Calendrier BWF = tournois À VENIR seulement** : la source oublie une édition
  dès sa finale jouée. `CalendarMemory` rattrape ce qu'on a DÉJÀ VU (cf. plus haut),
  mais rien avant la mise en place de la mémoire : `date: null` (et nom d'origine)
  reste un cas NORMAL pour les tournois du début de saison, pas un bug — mieux vaut
  pas de date qu'une fausse.
- **Tri par ces dates déterministes** : `parseSeasonLines` ordonne les lignes du
  plus récent au plus ancien sur les dates du calendrier ; les lignes sans date
  passent après, sans casser l'ordre. On ne se fie jamais à l'ordre rendu par Haiku.
- **Cache versionné** (`WikiPlayer.EXTRACTION_VERSION`) : une évolution de la logique
  d'extraction périme les entrées de `collector/cache/` MÊME à révision Wikipédia
  identique. Le cache stocke `formatVersion` ; s'il diffère, on ré-extrait (sinon on
  servirait des lignes périmées). Bump la constante à chaque changement d'extraction.

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
- **Les Championnats de France sont HORS PÉRIMÈTRE** : compétition nationale, absente
  du calendrier BWF (qui ne liste que les épreuves qu'elle sanctionne). Ne pas
  chercher à les y trouver — il faudrait une source FFBaD distincte, avec son propre
  parsing et ses propres règles de fraîcheur. Non prévu.
- **Mondiaux : pas de bracket sur la page principale** -> `frenchStatus` vient du
  PODIUM (cf. règles de lecture). Un Français sorti avant les demies n'y apparaît pas :
  le tableau de bord dira « statut inconnu », pas « aucun ». Les brackets complets
  existent, mais dans les SOUS-ARTICLES par discipline (`… – Men's singles`) : les
  lire donnerait le parcours exact comme sur un tournoi World Tour, au prix de 5
  requêtes MediaWiki de plus par tournoi. Pas fait — à rouvrir si les Mondiaux
  deviennent frustrants à suivre (`parseBracketRuns` s'appliquerait tel quel).
- **`players[].lines` : pas de date pour les Mondiaux.** `LlmNet.matchTournament`
  exige un jeton distinctif hors type d'épreuve ; « BWF World Championships » se
  réduit à `{championships}` (« bwf »/« world » sont du bruit d'appariement) -> aucun
  appariement, `date: null`, nom affiché « Championnats du monde » via `frenchName`.
  Ne PAS retirer « world » de `NAME_NOISE` pour corriger ça : le calendrier contient
  « VICTOR BWF World **Junior** Championships », qui serait alors apparié à tort.

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
d'article *quand l'infobox suffit*, lecture des tableaux (Source A), les CHAMPIONS
(bloc de l'infobox), le choix de la tête d'affiche (`Window.featured`), `rank`
d'infobox, et les DATES des lignes (calendrier BWF). Ne mets pas d'appel LLM là où une règle
suffit — les tableaux Wikipédia sont déjà structurés, ne les fais PAS lire par Haiku.

**Règle d'or : ne JAMAIS demander au LLM un fait absent de sa source.** La prose
Wikipédia n'a pas les dates → on ne les demande pas à Haiku (il en inventerait, et
sa réponse serait instable d'un run à l'autre) ; on les prend au calendrier BWF, ou
on met `null`. Un champ imposé au schéma que la source ne porte pas = une invitation
à halluciner. Le LLM est un invité, jamais le moteur : si l'IA échoue, l'appli
fonctionne sans elle (rank + frenchStatus restent).

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
- **Tests anti-régression à conserver** : classe `FenetreTemporelle` — le tournoi
  du 21–26 juillet reste `current` le 26 (jour de la finale) à TOUTE heure UTC du
  run, et ne bascule « passé » qu'au 27. Ne pas rendre une borne stricte ni
  recalculer « aujourd'hui » ailleurs que dans `Window`.
- **Tests anti-régression à conserver** : classes `TeteDaffiche` et
  `MemoireCalendrier` — le 27 juillet, le tournoi fini la veille tient encore
  l'affiche en `termine` (jamais « aucun tournoi »), il sort dès le démarrage du
  suivant le 28, et la mémoire du calendrier le rend même quand la source BWF l'a
  oublié. Plus `parseChampionsToutOuRienSiPartiel` : moins de 5 disciplines → `null`.
- **Tests anti-régression à conserver** : classe `CategoriesCalendrier` —
  `tierOfRejetteLesVariantesJuniorsSeniorsEtEquipes` (le Mondial junior, le Mondial
  vétérans et les épreuves par équipes ne passent JAMAIS) et
  `isEuropeanNeGardeQueLeChampionnatEuropeen` (les championnats d'Afrique et d'Asie
  ne passent pas). Ne pas remplacer l'appariement EXACT de ces catégories par un
  `contains()`. Plus, dans `StatutTournoi` :
  `matchesChampionshipRejetteLesEditionsVoisines`,
  `matchesChampionshipRejetteLarticleDunAutreSport` (l'anti-régression tennis vaut
  AUSSI sur un tier championnat — c'est `{{Infobox badminton event}}` qui prend le
  relais du niveau) et `parseMedalistsNeConfondPasSimpleDamesEtSimpleMessieurs`.
- **Tests anti-régression à conserver** : `stageFrNinventePasDeStadeSansAnnotation`,
  `frenchStatusNaffichePasUnJoueurSansAnnotation` et
  `frenchStatusSansAucunStadeLisibleResteVrai` — ne PAS réintroduire un stade par
  défaut dans `stageFr` (un tournoi terminé passerait pour en cours), et ne pas
  confondre « aucun Français » avec « aucun stade connu ».
- **Tests anti-régression à conserver (bracket)** :
  `bracketNeDeclarePasElimineUnMatchNonJoue` (le piège `PENDING` — ne jamais le
  supprimer, un joueur qui s'apprête à jouer serait déclaré éliminé),
  `bracketDonneLeTourDunJoueurNonTeteDeSerie`,
  `bracketNumeroteLesToursSelonLaTailleDuTableau` (le nb de tours se lit sur la
  structure, pas sur les cases françaises), `bracketIgnoreLesQualifications` et
  `frenchStatusPrefereLeBracketEtGardeLeForfaitDesSeeds`.
- **Une date, un fuseau** : toute logique de date passe par `Window.today()`
  (Europe/Paris) et prend son instant en paramètre pour rester testable. Pas de
  `LocalDate.now()` disséminé dans le code.

## Identités à ne pas confondre

- **Alex Lanier** (et non « Lasnier »). Suivi individuel. Article Wikipédia :
  `Alex Lanier`.
- « Popov » = **deux frères** : **Christo Popov** (suivi individuel, article
  `Christo Popov`) et **Toma Junior Popov** (plus suivi dans `players[]`, mais
  compté pour `frenchStatus`). Au tableau d'un tournoi, les deux comptent comme
  Français.
- Le double **Delphine Delrue / Thom Gicquel** n'est plus suivi (retiré avec le
  passage à Wikipédia).
