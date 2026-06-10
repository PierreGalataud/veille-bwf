# CLAUDE.md — Veille BWF World Tour

Contexte et règles pour travailler sur ce dépôt. À lire avant toute modification.

## Ce qu'est le projet

Un tableau de bord des tournois de badminton du **BWF World Tour** de la semaine
en cours, avec suivi prioritaire des joueurs français (Alex Lanier, les frères
Christo Popov et Toma Junior Popov, le double mixte Gicquel/Delrue).

Niveaux suivis (les 5 du World Tour) : **World Tour Finals, Super 1000, Super 750,
Super 500, Super 300**.

Deux priorités d'affichage, dans cet ordre : (1) les tournois de la semaine
courante, (2) ceux où des Français sont en lice.

## Architecture — la règle d'or

Le projet = **deux programmes indépendants reliés par un seul fichier pivot**.
Ils ne s'appellent jamais directement ; ils s'accordent uniquement sur la forme
de `public/data.json`.

```
Collecteur Java  ──écrit──▶  public/data.json  ──lu par──▶  Front React
   (collector/)               (LE CONTRAT)                  (src/)
```

**Conséquence impérative : ne jamais casser le schéma de `data.json` d'un seul
côté.** Si tu modifies la structure, tu modifies les DEUX côtés dans le même
commit (le collecteur qui le produit ET `src/App.jsx` qui le lit), sinon le site
se vide silencieusement.

## Carte du dépôt

```
index.html, vite.config.js, package.json   → config du front (Vite + React)
src/main.jsx                               → point d'entrée React
src/App.jsx                                → TOUT l'affichage, piloté par data.json
src/styles.css                             → thème (maquette validée, ne pas dénaturer)
public/data.json                           → le contrat de données (instantané courant)
collector/pom.xml                          → Maven, Java 17 (Jsoup/Anthropic prêts en commentaire)
collector/src/main/java/veille/Collector.java → le collecteur
.github/workflows/refresh.yml              → automatisation (voir « Déploiement »)
```

## Le contrat `data.json`

Schéma à respecter exactement. `tier` ∈ `"wtf" | "1000" | "750" | "500" | "300"`.
Le champ `tone` ∈ `"win" | "out" | null`.

```json
{
  "generatedAt": "ISO-8601 UTC",
  "weekLabel": "Semaine du …",
  "current": [
    {
      "name": "…", "tier": "500", "location": "…", "dates": "…",
      "prize": "…", "timezone": "UTC+…", "dayLabel": "Jour x / y · …",
      "seeds": [ { "rank": "TS1", "name": "…" } ],
      "frenchStatus": { "present": false, "title": "…", "note": "…", "confirm": true }
    }
  ],
  "players": [
    { "name": "…", "rank": "#x mondial",
      "lines": [ { "label": "Dernier", "value": "…", "tone": "win" } ] }
  ],
  "upcoming": [
    { "dates": "…", "name": "…", "tier": "300", "french": "FR : à confirmer" }
  ]
}
```

`App.jsx` mappe `tier` vers une couleur et un libellé (`TIER_COLOR`, `TIER_LABEL`,
`TIER_SHORT`). Si tu ajoutes un niveau, mets ces trois maps à jour.

## État actuel et tâche en cours

Le collecteur est en **v1 figée** : `Collector.buildData()` renvoie un instantané
codé en dur (Open d'Australie + état des Français), seul `generatedAt` change.
Toute la tuyauterie (Actions → commit → déploiement Vercel) fonctionne déjà.

**Prochaine tâche : remplacer `buildData()` par une collecte réelle.**
- Commencer par le **calendrier BWF** (lister les tournois de la semaine, filtrer
  sur les 5 niveaux), AVANT de toucher aux tableaux (draws) de TournamentSoftware,
  plus complexes.
- Décommenter les dépendances dans `collector/pom.xml` (Jsoup, puis Jackson).
- L'IA (SDK `com.anthropic:anthropic-java`) ne sert qu'à 3 choses : extraire des
  pages HTML instables, réconcilier les noms entre sources, rédiger la synthèse.
  Tout le reste (filtrage par date/niveau, dédup) doit rester du code déterministe.

## Commandes

```bash
# Front
npm install
npm run dev      # http://localhost:5173
npm run build    # produit dist/ (ce que Vercel construit)

# Collecteur (régénère public/data.json)
mvn -f collector/pom.xml compile exec:java -Dexec.args="public/data.json"
```

Après avoir modifié le collecteur, **toujours** le relancer et vérifier que
`public/data.json` reste un JSON valide et conforme au schéma ci-dessus avant de
committer.

## Déploiement (ne pas chercher à le contourner)

```
cron / clic  →  GitHub Actions  →  lance le collecteur  →  commit data.json
                                                               │ (push)
                                                               ▼
                                                  Vercel rebuild + déploie
```

- Vercel ne sert que des fichiers statiques. Il **n'exécute jamais** le Java et
  n'appelle jamais d'API. Ne lui confie aucune logique serveur.
- **Les secrets (ex. `ANTHROPIC_API_KEY`) vont dans les GitHub Actions secrets**,
  jamais dans Vercel, jamais dans le code, jamais committés.
- Le workflow ne commite que si `data.json` a changé.

## Garde-fous

- **Ne pas coder en dur de données badminton dans `src/App.jsx`** : tout vient du
  JSON. Le front doit rester « bête ».
- **Échec gracieux** : si le scraping échoue, le collecteur ne doit PAS écrire un
  `data.json` vide ou cassé (cela viderait le site en ligne). Préférer : ne rien
  réécrire et sortir en erreur, pour que le commit n'efface pas la dernière bonne
  version.
- **Pas d'API publique BWF** : usage personnel, requêtes espacées, `User-Agent`
  explicite, mise en cache. Ne pas marteler les serveurs.
- **Garder la CI verte** et la fréquence du cron raisonnable (les minutes Actions
  et déploiements Vercel sont gratuits mais limités). Fréquence plus élevée
  seulement les jours de tournoi.
- **Contenu en français, encodage UTF-8** partout (le collecteur écrit en UTF-8 ;
  le `pom.xml` fixe `project.build.sourceEncoding=UTF-8`).

## Identités à ne pas confondre

- C'est **Alex Lanier** (et non « Lasnier »).
- « Popov » = **deux frères** distincts : **Christo Popov** et **Toma Junior Popov**.
- La réconciliation de noms entre sources (BWF, Flashscore, FFBaD) est un vrai
  sujet : prévoir une table d'alias plutôt que des comparaisons exactes.
