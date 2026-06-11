# Rapport d'audit — Veille BWF World Tour

Audit du code au 2026-06-11 (commit `011cfba`, branche `main`). Aucune modification
appliquée : ce document liste les corrections et améliorations recommandées, avec
explications, pour être confié à un agent/LLM chargé de les réaliser.

**Instructions à l'agent exécutant** (non négociables, issues de `CLAUDE.md`) :
- Lire `CLAUDE.md` AVANT toute modification.
- Toute modification du schéma de `public/data.json` touche le collecteur ET
  `src/App.jsx` dans le même commit. Aucune recommandation ci-dessous ne change le
  schéma, sauf mention explicite.
- Ne jamais scraper TournamentSoftware ni Flashscore.
- Ne pas coder en dur de données badminton dans le front.
- Ne pas pousser le déterministe plus loin sur l'interprétation du langage : les cas
  ambigus restent marqués `tone: null` (point d'entrée du futur filet LLM, étape A).
- Après toute modif du collecteur : le relancer et vérifier `data.json` (schéma +
  UTF-8) avant de committer.
- Fichiers concernés : `collector/src/main/java/veille/Collector.java` (= « Collector »),
  `src/App.jsx`, `.github/workflows/refresh.yml`, `collector/pom.xml`.

---

## 1. BUGS (priorité haute)

### 1.1 Faux positifs sur la détection des joueurs par sous-chaîne
**Où :** `Collector.buildPlayers`, ~lignes 941-945.

Le rattachement d'un titre à un joueur se fait par `hay.contains("toma")`,
`hay.contains("christo")`, etc. sur le texte normalisé. Problème : ce sont des
recherches de **sous-chaîne sans frontière de mot** :
- `"toma"` matche « au**toma**tique », « **toma**te » → un titre contenant
  « qualification automatique » serait attribué à Toma Junior Popov ;
- `"christo"` matche « **Christo**phe » → tout titre citant un Christophe
  (entraîneur, autre joueur) serait attribué à Christo Popov ;
- `"bat"` (dans `OPP_VERBS`) matche « com**bat** », « **bat**tu », « dé**bat** » —
  `containsAny` sur ce verbe est trop permissif.

**Correction :** remplacer les `contains` de noms de joueurs (et le verbe `bat`) par
des matchs à frontière de mot sur le texte normalisé, p. ex. précompiler des
`Pattern.compile("\\btoma\\b")`, `\\bchristo\\b`, `\\bbat\\b`. Garder `contains`
pour les marqueurs où le préfixe est voulu (`elimin`, `selectionn`, `engag`…).
Ajouter des tests unitaires (cf. §4) avec les contre-exemples ci-dessus.

### 1.2 Comparaison de dates cassée au passage d'année
**Où :** `Collector.datesOverlapRange` + `ord()`, ~lignes 1130-1141.

Le chevauchement de dates est testé via `ord = mois*100 + jour`, **sans année**.
Un tournoi à cheval décembre → janvier (cas réel : un Super 1000 début janvier dont
la page equipe-france indique « 30 décembre – 4 janvier ») donne `start=1230 > end=0104`
et le test `bs <= ee && es <= be` échoue systématiquement. Conséquence : la page
equipe-france n'est jamais confirmée → `frenchStatus.present = null` à tort pendant
toute la période du Nouvel An. Idem pour `datesOverlap` (entrées du calendrier EF).

**Correction :** dans `datesOverlapRange`, détecter l'enroulement (mois de fin <
mois de début) et le traiter, p. ex. en convertissant en `LocalDate` avec l'année du
tournoi BWF (`t.start().getYear()`, +1 sur la borne de fin si elle « enroule »), puis
comparer des vraies dates. Tester : plage BWF 30 déc – 4 janv vs plage EF identique ;
plage BWF en juin vs plage EF en juin (non-régression).

### 1.3 « X et Y éliminés » avalé par le drapeau « disputed »
**Où :** `Collector.buildPlayers` ~ligne 952, `OPP_VERBS` ~ligne 765, `TourAgg.absorb`.

`OPP_VERBS` contient `elimin` et `prive`. Or ce sont AUSSI des marqueurs de sortie
collective (`OUT_MARKERS`). Résultat : un titre comme « Lanier et Popov éliminés au
1er tour » nomme ≥ 2 suivis ET contient `elimin` → `disputed = true` → `absorb`
retourne immédiatement **sans enregistrer la sortie ni le stade**. C'est contraire à
la règle de `CLAUDE.md` (« une mention groupée vaut signal de sortie ») : l'issue
finit en « résultat à préciser » au lieu de « Éliminé au 1er tour » / `out`.

**Correction (heuristique sèche, sans sur-traiter) :** distinguer la voix passive
plurielle de l'opposition active AVANT de poser `disputed` : si le titre normalisé
contient une forme plurielle/collective (`elimines`, `sinclinent`, `tombent`,
`prives`), c'est une sortie collective → traiter comme `OUT_MARKERS` (les joueurs
cités prennent le signal de sortie), PAS comme une opposition. Ne garder `disputed`
que pour les formes actives singulières (`domine`, `bat`, `simpose face`, `ecarte`,
`renverse`, `elimine ` au singulier suivi d'un nom). Les cas restants indécidables
demeurent `tone: null` — ne pas chercher plus loin, c'est le périmètre de l'étape A.

### 1.4 Appariement de tournoi sur 1 seul jeton : « Masters » relie tout aux Masters
**Où :** `Collector.isOngoing`, ~lignes 988-1005.

Le repli « candidat par le nom, à défaut » (`if (match == null) match = t;`) accepte
le **premier** tournoi BWF partageant ≥ 1 jeton, même sans confirmation par la date.
Or « masters » n'est pas dans `NAME_STOPWORDS` : « Orléans Masters » (hors World
Tour) partage le jeton « masters » avec « VICTOR Korea Masters », « Kumamoto Masters
Japan », « LI-NING China Masters »… Le statut « en cours » d'Orléans peut alors être
tranché par les dates d'un Masters asiatique sans rapport. Même fragilité dans
`resolveFrenchStatus` (le score nom est borné par les dates, mais le jeton « masters »
gonfle des candidats sans rapport).

**Correction :** ajouter `masters` (et par cohérence `finals` est déjà distinctif —
le laisser) aux `NAME_STOPWORDS` ? **Non** : « masters » est parfois le seul jeton
distinctif (« Orléans Masters »). Préférer : dans `isOngoing`, n'accepter le repli
« par le nom seul » que si le partage est ≥ 2 jetons OU si le jeton partagé n'est pas
un mot générique (introduire une petite liste `WEAK_TOKENS = {masters, japan, open…}`
— en fait `open` est déjà stopword). Au minimum : exiger 2 jetons partagés pour le
repli sans date, en gardant le chemin « date dans la plage » inchangé.

### 1.5 Écriture non atomique de `data.json`
**Où :** `Collector.main`, ~ligne 120.

`Files.writeString(out, json)` tronque puis écrit. Un kill au mauvais moment (timeout
CI, Ctrl-C local) laisse un `data.json` **tronqué/corrompu**, ce qui viole le garde-fou
« échec gracieux : ne jamais réécrire un data.json cassé ». En CI le fichier corrompu
serait committé (il « a changé »).

**Correction :** écrire dans un fichier temporaire du même dossier
(`Files.createTempFile(out.getParent(), …)`) puis `Files.move(tmp, out,
StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)` avec repli sans
`ATOMIC_MOVE` si le système le refuse.

### 1.6 `git push` du workflow sans rebase préalable
**Où :** `.github/workflows/refresh.yml`, step « Committer si data.json a changé ».

Si un commit a été poussé sur `main` entre le checkout et le push (modif manuelle,
autre run), le `git push` échoue et le run entier est marqué en erreur alors que les
données sont bonnes. Le groupe `concurrency` protège des runs du workflow entre eux,
pas des pushes humains.

**Correction :** avant `git push`, faire `git pull --rebase origin main` (le commit
local ne touche que `data.json`, le rebase est sûr). Ajouter aussi
`timeout-minutes: 15` au job (le collecteur fait des requêtes réseau avec throttle ;
sans timeout un blocage consommerait 6 h de runner).

---

## 2. ROBUSTESSE / FIABILITÉ (priorité moyenne)

### 2.1 Validation du JSON produit avant commit (CI)
Le workflow commite tout `data.json` modifié, même structurellement faux (régression
du collecteur). Ajouter un step de validation entre la génération et le commit :
vérification que le fichier est un JSON valide et que les clés du contrat existent
(`generatedAt`, `weekLabel`, `current[]`, `players[]`, `upcoming[]`, et pour chaque
`current[i]` : `tier` ∈ {wtf,1000,750,500,300}, `frenchStatus.present` ∈
{true,false,null}). Réalisable en un step `jq -e` (déjà présent sur ubuntu-latest),
p. ex. test des types et des enums ; en cas d'échec, ne pas committer et sortir en
erreur. C'est la mécanisation du garde-fou « vérifier que data.json reste conforme ».

### 2.2 Front : aucune défense si une clé du contrat manque
**Où :** `src/App.jsx` lignes 74-75, 181.

`data.current.filter(...)`, `data.upcoming.filter(...)`, `data.players.map(...)`
plantent (écran blanc) si une clé est absente d'un `data.json` ancien ou partiel —
contraire à l'esprit « échec gracieux ». Correction minimale, sans changer le
schéma : `const current = data.current ?? [];` etc. en tête de rendu. Ajouter aussi
un état vide pour la liste `upcoming` (la section « En cours » en a un, « À venir »
et « Vos Français » non : si tout est filtré/vide, sections muettes).

### 2.3 Front : test d'inconnu incomplet sur `present`
**Où :** `src/App.jsx` ligne 149.

`frBannerClass` traite correctement `undefined` comme inconnu, mais l'icône teste
`present === null` strictement : un `present` absent (undefined) afficherait 🇫🇷 avec
le style « unknown ». Utiliser `t.frenchStatus.present == null` (égalité lâche
volontaire, couvre null et undefined) pour l'icône, afin que les deux rendus restent
cohérents avec le contrat à trois états.

### 2.4 Footer trompeur sur les sources
**Où :** `src/App.jsx` lignes 213-215.

Le footer affiche « Sources visées : BWF (Match Centre), TournamentSoftware (tableaux
& live), Flashscore… » alors que `CLAUDE.md` interdit TournamentSoftware et Flashscore
(robots.txt) et que les vraies sources sont le calendrier corporate BWF et
equipe-france.fr. Mettre le texte en accord avec la réalité : « Sources :
corporate.bwfbadminton.com (calendrier), equipe-france.fr (Français) ». De même, le
badge « En direct » (ligne 90) est codé en dur alors que les données sont rafraîchies
toutes les 6 h — le remplacer par un libellé honnête (p. ex. « Màj auto » ) ou le
piloter par l'ancienneté de `generatedAt`.

### 2.5 Throttle incomplet côté equipe-france
**Où :** `Collector.harvestCandidates`, ~lignes 535-552.

`getPage` respecte la pause de 800 ms, mais `harvestCandidates` enchaîne deux
`fetch()` directs (calendrier puis accueil) sans pause, et `buildRanks`/`buildPlayers`
ont chacun leur `Thread.sleep` manuel dispersé. Centraliser : faire passer TOUTES les
requêtes equipe-france par une méthode unique `fetchEf(url)` qui applique le throttle
et le User-Agent, et supprimer les `Thread.sleep` épars. Comportement identique,
politesse garantie partout, code plus simple.

### 2.6 User-Agent placeholder
**Où :** `Collector.USER_AGENT`, ligne 70-71. `+https://github.com` ne pointe vers
rien. Mettre l'URL réelle du dépôt (ou une adresse de contact) : c'est le garde-fou
« usage personnel : User-Agent explicite ».

### 2.7 `parseParticipation` : repli arbitraire sur le 2e paragraphe
**Où :** ~lignes 701-704. Si aucun `p.intro` ne contient de mot-clé, on prend
aveuglément `intros.get(1)` comme « phrase de participation » et on la classe. Un
paragraphe sans rapport peut alors contenir « engag… » par hasard et produire un
`present: true` erroné. Correction : suppression du repli — sans phrase reconnue,
retourner directement `unknown` (la fin de la méthode le fait déjà proprement).
Le repli actuel n'apporte qu'un risque.

---

## 3. REFACTORING (priorité moyenne/basse — comportement inchangé)

### 3.1 `Collector.java` : 1 170 lignes, 3 responsabilités
Le fichier mêle (a) parsing du calendrier BWF, (b) statut français (étape 1),
(c) suivi joueurs (étape 2), plus les utilitaires texte. Avant d'ajouter l'étape A
(client LLM) puis B (agent), découper en classes du même package, sans rien changer
au comportement :
- `BwfCalendar` — fetch + `parseTournaments`, `tierOf`, `parsePrize`, dates ;
- `EquipeFrance` — fetch throttlé (cf. §2.5), `harvestCandidates`,
  `resolveFrenchStatus`, `parseParticipation`, `parseEfDates`, `buildRanks`,
  `parseFeed` ;
- `PlayerResults` — `buildPlayers`, `TourAgg`, `PlayerAcc`, tables de mots-clés,
  `stageOf` ;
- `TextUtil` — `norm`, `stripAccents`, `nameTokens`, `sharedTokens`, `containsAny` ;
- `Collector` — orchestration + sérialisation JSON.
Bénéfice direct : les méthodes deviennent testables (cf. §4) et l'étape A se branche
dans une classe dédiée (`LlmRefiner`) sans grossir le monolithe.

### 3.2 `MONTH_NUM` : `Map<String,int[]>` avec tableaux à 1 élément
~Lignes 77-90. Reliquat : la valeur est un `int[]{i+1}` dont seul `[0]` sert.
Remplacer par `Map<String,Integer>`. Idem, les paires `{mois,jour}` en `int[]` et
`int[][]` (`EfEntry`, `parseEfDates`) gagneraient à devenir un petit
`record MonthDay(int month, int day)` (ou `java.time.MonthDay`) — lisibilité et fin
des indices magiques `range[0][1]`.

### 3.3 Sérialisation JSON : `Map<String,Object>` partout
`buildData`/`toLine`/`toJson` construisent le contrat à la main via des
`LinkedHashMap`. Jackson sérialise très bien des records : définir des records
`DataRoot`, `CurrentTournament`, `FrenchStatusJson`, `PlayerJson`, `LineJson`,
`UpcomingJson` reflétant exactement le contrat. Le schéma devient alors visible et
vérifiable par le compilateur (un renommage de champ casse la compilation au lieu de
casser silencieusement le front). **Attention :** sortie strictement identique exigée
(ordre des clés, `null` sérialisés) — comparer l'ancien et le nouveau `data.json`
avant de committer.

### 3.4 Doublons de mots-clés entre `WIN_MARKERS` et `stageOf`
`stageOf` reteste `"vainqueur", "sacre", "champion", "titre"` (ligne 1080) déjà
présents dans `WIN_MARKERS` (ligne 753). Faire pointer `stageOf` niveau 6 sur la
constante `WIN_MARKERS` pour qu'un futur ajout (« s'adjuge », « couronné ») ne soit
pas à faire à deux endroits. Idem pour la paire `score()` appelée deux fois par
comparaison dans le tri de `resolveFrenchStatus` (~ligne 479) : précomputer les
scores dans une map avant le tri (micro-perf, surtout lisibilité).

### 3.5 Petites choses
- `PlayerAcc.slug` est `null` pour le double et jamais utilisé ailleurs que la
  jointure rank : rendre la jointure explicite plutôt que de transporter un null.
- `extractYear` regex sur `doc.outerHtml()` (~1,4 Mo) : cibler les `<script>` est
  suffisant, mais c'est cosmétique.
- `vite.config.js`/`package.json` : RAS. Dépendances saines (jsoup 1.18.1,
  jackson 2.17.2, React 18.3) — pas de CVE connue, pas d'urgence de bump.

---

## 4. TESTS (priorité haute — il n'y en a AUCUN)

Le collecteur n'a aucun test alors que sa valeur est concentrée dans des fonctions
**pures** parfaites pour des tests unitaires JUnit (ajouter `junit-jupiter` en scope
test + surefire dans `collector/pom.xml`) :

- `stageOf` : « s'incline en finale » → 5 ; « quart de finale » → 3 ; « atteint la
  finale » → 5 ; « demi-finale » → 4 ; titre sans stade → 0.
- `parseEfDates` : « 16 – 21 juin », « 30 juin – 5 juillet », libellé responsive
  dupliqué, cellule sans mois → `{0,0}`.
- `parseDayRange` : « 09 -14 », « 30 - 05 », texte sans chiffre → null.
- `datesOverlapRange` : cas nominal + **passage d'année** (test rouge avant le fix 1.2).
- `norm` / `nameTokens` / `sharedTokens` : accents, apostrophes, stopwords, préfixe 5.
- La logique de classement de `buildPlayers` : extraire la boucle de classification
  d'un `FeedItem` en méthode testable et couvrir les règles de `CLAUDE.md` :
  nominatif > collectif ; « Popov » seul → ambigu pour les deux ; « X domine Y » →
  disputed ; « Lanier et Popov éliminés » → out collectif (test rouge avant fix 1.3) ;
  « qualification automatique » ne matche PAS Toma (test rouge avant fix 1.1).
- `parseTournaments` / `parseFeed` / `parseParticipation` : tests sur des fixtures
  HTML **enregistrées** dans `src/test/resources` (un extrait du calendrier BWF et
  d'une page equipe-france) — jamais de réseau dans les tests.

Ajouter ensuite `mvn -f collector/pom.xml test` comme step du workflow avant la
génération. C'est le meilleur investissement avant la bascule IA : le filet LLM
(étape A) s'appuiera sur la fiabilité des marquages `tone: null` produits ici.

---

## 5. AMÉLIORATIONS FONCTIONNELLES (basse priorité, optionnelles)

- **Rank du double Delrue/Gicquel** toujours `null` : seule la page de classement
  simple messieurs est lue (`EF_RANK_M`). Si equipe-france publie un classement
  double mixte exploitable, l'ajouter sur le même modèle ; sinon documenter que
  c'est volontaire.
- **Rafraîchissement du front** : la page ne refait jamais le fetch ; un
  `setInterval` (15-30 min) sur `/data.json` avec comparaison de `generatedAt`
  suffirait, et permettrait de griser le badge « En direct » quand `generatedAt`
  date de plus de ~12 h (signal qu'un run CI a échoué).
- **`upcoming[].french` figé** à « FR : à confirmer » : champ prévu par le contrat
  mais jamais alimenté. Soit l'alimenter pour les tournois proches (le calendrier
  equipe-france, déjà parsé dans `harvestCandidates`, contient ces entrées datées —
  l'info est presque gratuite), soit le retirer du contrat (modif des DEUX côtés).
- **Clés React** : `key={i}` sur les listes ; utiliser `t.name`/`p.name` comme clé,
  plus stable lors des filtres de tiers.

---

## 6. Ce qui est BIEN et ne doit PAS être « amélioré »

Pour éviter qu'un agent zélé ne « corrige » des choix volontaires :
- Le **trois-états** `present: true/false/null` est un invariant du contrat — ne
  jamais le réduire à un booléen.
- Les cas `tone: null` / « stade non précisé » / « résultat à préciser » sont des
  **marquages volontaires** pour l'étape A (LLM), pas des bugs à résoudre à coups de
  règles supplémentaires.
- L'échec gracieux du collecteur (exit 1 sans réécrire le fichier ; sources
  secondaires en best-effort) est correct — le préserver dans tout refactoring.
- Les coquilles recopiées telles quelles depuis equipe-france (prénoms inversés) sont
  un choix assumé.
- Le throttle, le cache de pages et `MAX_EF_TRIES` sont des garde-fous de politesse,
  pas des lenteurs à optimiser.

## 7. Ordre d'exécution suggéré

1. §4 squelette de tests (rouge sur 1.1, 1.2, 1.3) — JUnit + fixtures.
2. §1.1, 1.2, 1.3, 1.4 — corrections de logique, tests au vert.
3. §1.5, 1.6, 2.1 — atomicité + durcissement CI.
4. §2.2-2.7 — robustesse front + politesse réseau.
5. §3 — refactoring en classes (comportement identique, diff de `data.json` vide).
6. §5 — fonctionnel optionnel.

Chaque lot = un commit, collecteur relancé et `data.json` vérifié à chaque fois.
