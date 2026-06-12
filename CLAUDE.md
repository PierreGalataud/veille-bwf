# CLAUDE.md — Veille BWF World Tour

Contexte et règles pour travailler sur ce dépôt. À lire avant toute modification.

## Ce qu'est le projet

Un tableau de bord des tournois de badminton du **BWF World Tour** de la semaine
en cours, avec suivi prioritaire des joueurs français (Alex Lanier, les frères
Christo Popov et Toma Junior Popov, le double Delphine Delrue / Thom Gicquel).

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
collector/pom.xml                          -> Maven, Java 17 (Jsoup + JUnit 5)
collector/src/main/java/veille/           -> le collecteur, découpé par rôle :
  Collector.java       orchestration + écriture atomique de data.json
  BwfCalendar.java     calendrier BWF (tiers, dates, dotation)
  EquipeFrance.java    statut FR, fil des Bleus, classement (throttle commun fetchEf)
  PlayerResults.java   agrégation players[] (classify, tables de mots-clés, stades)
  DataJson.java        LE CONTRAT data.json en records typés (cf. ci-dessous)
  TextUtil / FrDates / Http / Tournament / FeedItem / EfDateRange (utilitaires, modèle)
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

`upcoming[].french` : résolu via equipe-france pour les tournois démarrant sous
14 jours (`UPCOMING_FR_DAYS`) -> `"FR : engagés"` / `"FR : aucun engagé"` /
`"FR : à confirmer"` (non résolu, ou sélection non publiée — on n'invente jamais
un « aucun »). Au-delà de 14 jours : « à confirmer » sans sonder.

`frenchStatus.present` est à **TROIS états**, jamais confondus :
- `true`  : Français engagés (détails dans `note`).
- `false` : page equipe-france lue, aucun Français engagé.
- `null`  : aucune page equipe-france appariée -> **statut inconnu**.

« Pas trouvé » (`null`) et « trouvé, personne » (`false`) doivent rester distincts,
dans le collecteur ET à l'affichage. `App.jsx` mappe `tier` via `TIER_COLOR` /
`TIER_LABEL` / `TIER_SHORT`.

## Carte des sources (VÉRIFIÉ — ne pas dévier)

| Donnée | Source | Accès |
|---|---|---|
| Calendrier + niveaux + prize | corporate.bwfbadminton.com/events/calendar/ | **Jsoup OK** (WordPress rendu serveur) |
| Statut + résultats des Français | equipe-france.fr/badminton/... | **Jsoup OK** (rendu serveur, FR-centré) |
| Tableaux / scores live | TournamentSoftware, Flashscore | **INTERDIT** — robots.txt bloque, ne pas scraper |

- **Calendrier** : table groupée par mois ; colonne CATEGORY -> niveau (on ne garde
  que `HSBC BWF World Tour …`). La ligne détail contient le GUID TournamentSoftware
  (identifiant seulement, PAS pour scraper le site).
- **equipe-france** : page par tournoi (phrase de participation) + fil daté
  `DATE · TOURNOI · TITRE`. Source de `frenchStatus` et de `players[]`.
- **Pas de score point par point** : sources live bloquées. Grain « tour » seulement
  (sorti / qualifié / en quart), rafraîchi quelques fois par jour.

## État d'avancement

### Phase déterministe — TERMINÉE (logique de fond)
- [x] Tuyau complet : collecteur -> data.json -> Actions -> commit -> Vercel.
- [x] Calendrier réel (Jsoup) : `current` / `upcoming`, niveaux, dates, prize.
- [x] `frenchStatus` à trois états via equipe-france (réconciliation par dates + nom).
- [x] `players[]` : résultats des Bleus, classés par table de mots-clés.

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

### Prochaines étapes — bascule vers l'IA
- [x] **Étape A — Filet LLM (Haiku), ciblé.** FAIT (`LlmNet.java`) : appels
      `claude-haiku-4-5` UNIQUEMENT sur les cas marqués incertains (oppositions
      « résultat à préciser », sorties « stade non précisé » — jamais les lignes
      « en cours »). Une fonction texte->texte appelée par `PlayerResults.toJson`
      quand une règle sèche — **pas un agent**. Échec gracieux total : sans
      `ANTHROPIC_API_KEY` (GitHub Actions secrets, passée par refresh.yml) ou sur
      toute erreur, la valeur déterministe est conservée — data.json identique.
      Chaque cas envoyé/appliqué est loggé (règle vs filet). Fonctions pures
      (`isUncertain`/`parseVerdicts`/`applyVerdict`) testées ; seul `ask` fait
      du réseau.
- [ ] **Étape B — Agent « La Dépêche des Français ».** Produit un résumé hebdo/mensuel
      des Bleus à partir des faits DÉJÀ collectés, ton pince-sans-rire. Variante agent :
      peut aller chercher l'ambiance côté presse (s'inspirer du registre, **sans
      recopier** de contenu protégé). C'est de la production de langage, pas du parsing.
      Reste un invité PAR-DESSUS les faits : s'il échoue, le tableau de bord tourne sans.

## Règles de classement des résultats (déterministe en place)

Tout est dans `PlayerResults.java` (classify + tables), couvert par les tests.

- Les noms de joueurs se reconnaissent en **MOTS ENTIERS** (`TextUtil.hasWord`),
  jamais en sous-chaîne : « toma » ⊄ « automatique », « christo » ⊄ « Christophe »,
  « bat » ⊄ « combat ».
- Un titre **nominatif** (joueur cité seul) prime sur une mention **collective**
  ambiguë pour le même tournoi (ex. « Christo s'incline au 1er tour » > « Lanier et
  Popov éliminés »).
- « **Popov** » sans prénom = ambigu (deux frères) : rattaché aux deux, `tone: null`.
- Titre **opposant deux joueurs suivis** (« X domine Y ») : on ne classe personne
  « Vainqueur » par défaut -> `tone: null`, `stage` « résultat à préciser ». Une règle
  par mots-clés ne peut pas distinguer sujet et objet.
- MAIS une forme **collective au pluriel** (« X et Y éliminés », « s'inclinent »,
  « privés ») n'est PAS une opposition : c'est un signal de sortie pour tous les
  cités (`COLLECTIVE_OUT`, testé AVANT `OPP_VERBS`). Ne pas re-fusionner les deux.
- Table titre -> tone : `win` (sacré/vainqueur/remporte/titre/champion) ; `out`
  (fin de parcours/s'incline/éliminé/1er tour/privés de) ; `null` (quart/demi/huitième/
  qualifié/en finale). Un titre non classé garde `tone: null` et n'est jamais jeté.
- « En cours » se décide par les DATES, jamais par les mots. L'appariement d'un
  tournoi du fil au calendrier BWF exige un jeton DISTINCTIF : « masters » seul ne
  suffit pas (`WEAK_TOKENS`), sinon Orléans hériterait des dates du Korea Masters.

## Limites assumées (laissées au filet LLM, étape A)

Volontairement NON sur-traitées en déterministe — les marteler avec plus de règles
rendrait le code fragile pour un gain marginal :
- les `stage` « stade non précisé » (perte de grain lors de l'agrégation) ;
- les titres d'opposition entre deux Français (« X domine Y ») ;
- les phrasés alambiqués sans mot-clé reconnu (« les Bleus rentrent bredouilles »).
Ces cas sont **marqués** (`tone: null`) pour devenir le point d'entrée du LLM, pas
corrigés à coups de règles supplémentaires.

Autres limites ASSUMÉES (vérifiées, ne pas re-creuser) :
- **`rank` du double Delrue/Gicquel = null pour toujours** : equipe-france ne publie
  que les classements de SIMPLE (Delrue y est « Non classé », vérifié juin 2026).
  Pas de classement par paire exploitable.
- **Sélection « pas disponible »** sur une page tournoi à venir -> « FR : à
  confirmer », jamais un « aucun » : la page bascule d'elle-même quand la FFBaD
  publie la sélection.

## Quand l'IA sert — et quand non

L'IA n'a sa place qu'où il n'y a pas de bonne réponse unique calculable : interpréter
un titre ambigu (étape A), produire un résumé avec un ton (étape B). **Tout le reste
reste déterministe** (fetch, filtrage par date/niveau, découpage du fil, dédup). Ne
mets pas d'appel LLM là où une règle suffit. Le LLM est un invité, jamais le moteur :
les faits fiables viennent du pipeline ; si l'IA échoue, l'appli fonctionne sans elle.

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
  TOUTE requête equipe-france passe par `EquipeFrance.fetchEf` (throttle commun),
  ne pas appeler `Http.fetch` en direct pour ce site.
- Contenu en français, **UTF-8** partout. PIÈGE : le séparateur de milliers de
  `prize` est une espace insécable fine **U+202F littérale** dans
  `BwfCalendar.parsePrize` (invisible à l'œil) — ne pas la « corriger » en espace
  simple, le diff de data.json le révélerait.
- **Toute nouvelle méthode de LOGIQUE arrive AVEC ses tests JUnit** (parsing,
  classement, appariement, dates, normalisation…) — même commit. Pour un bug :
  test ROUGE d'abord, correction ensuite. Si la logique est enfouie dans une
  méthode d'orchestration ou de réseau, l'EXTRAIRE pour la rendre testable
  (modèle : `classify()` extrait de `buildPlayers`). Seuls l'orchestration et les
  accès réseau ne se testent pas unitairement.
- Les tests JUnit ne font **jamais de réseau** (fonctions pures et fixtures
  uniquement) : un test qui fetch est un bug de test.

## Identités à ne pas confondre

- **Alex Lanier** (et non « Lasnier »).
- « Popov » = **deux frères** : **Christo Popov** et **Toma Junior Popov**. Mention
  sans prénom = ambiguë (cf. règles de classement).
- Double : **Delphine Delrue / Thom Gicquel** — l'ordre varie selon les sources
  (« Gicquel-Delrue », « Delrue-Gicquel »), matcher sur l'un ou l'autre. Les coquilles
  de la source (prénoms inversés) sont recopiées telles quelles, pas corrigées.
