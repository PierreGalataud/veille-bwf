# CLAUDE.md — Veille BWF World Tour

Contexte et règles pour travailler sur ce dépôt. À lire avant toute modification.

## Ce qu'est le projet

Un tableau de bord des tournois de badminton du **BWF World Tour** de la semaine
en cours, avec suivi prioritaire des joueurs français (Alex Lanier, les frères
Christo Popov et Toma Junior Popov, le double mixte Delrue/Gicquel).

Niveaux suivis (les 5 du World Tour) : **World Tour Finals, Super 1000, Super 750,
Super 500, Super 300**.

Deux priorités d'affichage, dans cet ordre : (1) les tournois de la semaine
courante, (2) ceux où des Français sont en lice.

## Architecture — la règle d'or

Le projet = **deux programmes indépendants reliés par un seul fichier pivot**.
Ils ne s'accordent que sur la forme de `public/data.json`.

```
Collecteur Java  --écrit-->  public/data.json  --lu par-->  Front React
   (collector/)               (LE CONTRAT)                  (src/)
```

**Ne jamais casser le schéma de `data.json` d'un seul côté.** Si tu modifies la
structure, tu modifies le collecteur ET `src/App.jsx` dans le même commit.

## Carte du dépôt

```
index.html, vite.config.js, package.json   -> config du front (Vite + React)
src/main.jsx                               -> point d'entrée React
src/App.jsx                                -> TOUT l'affichage, piloté par data.json
src/styles.css                             -> thème (maquette validée)
public/data.json                           -> le contrat de données
collector/pom.xml                          -> Maven, Java 17 (Jsoup activé)
collector/src/main/java/veille/Collector.java -> le collecteur
.github/workflows/refresh.yml              -> automatisation (Actions -> commit -> Vercel)
```

## Le contrat `data.json`

`tier` ∈ `"wtf" | "1000" | "750" | "500" | "300"`. `tone` ∈ `"win" | "out" | null`.
`frenchStatus.present` est À TROIS ÉTATS : `true` (Français engagés), `false`
(page equipe-france lue, « aucun Français »), `null` (aucune page appariée →
statut INCONNU, à ne jamais confondre avec un `false` confirmé). Le front
distingue visuellement le `null` (bandeau rayé) du `false` (bandeau gris plein).

```json
{
  "generatedAt": "ISO-8601 UTC",
  "weekLabel": "Semaine du …",
  "current": [
    {
      "name": "…", "tier": "500", "location": "…", "dates": "…",
      "prize": "…", "timezone": "UTC+…", "dayLabel": "…",
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

`App.jsx` mappe `tier` via `TIER_COLOR` / `TIER_LABEL` / `TIER_SHORT` — mets-les à
jour si tu ajoutes un niveau.

## Carte des sources (VÉRIFIÉ — ne pas dévier)

| Donnée | Source | Accès |
|---|---|---|
| Calendrier + niveaux + prize | corporate.bwfbadminton.com/events/calendar/ | **Jsoup OK** (WordPress rendu serveur) |
| Statut + résultats des Français | equipe-france.fr/badminton/... | **Jsoup OK** (rendu serveur, FR-centré) |
| Tableaux / scores live | TournamentSoftware, Flashscore | **INTERDIT** — robots.txt bloque, ne pas scraper |

Détails utiles :
- **Calendrier** : grande table groupée par mois. La colonne CATEGORY donne le
  niveau en clair (`HSBC BWF World Tour Super 500` -> `500`, etc.). On ne garde que
  les catégories commençant par `HSBC BWF World Tour`. La ligne détail contient le
  GUID TournamentSoftware (utile comme identifiant, PAS pour scraper le site).
- **equipe-france** : page par tournoi avec phrase de participation + fil daté
  `DATE · TOURNOI · TITRE`. C'est la source de `frenchStatus` et de `players[]`.
- **Pas de score point par point** : les sources live sont bloquées. On se limite
  au grain « tour » (sorti / qualifié / en quart), rafraîchi quelques fois par jour.

## État d'avancement

- [x] Tuyau complet : collecteur -> data.json -> Actions -> commit -> Vercel.
- [x] **Calendrier réel** (Jsoup sur le calendrier BWF) : `current` / `upcoming`.
- [ ] **Suivi des Français** (en cours) : `buildFrenchStatus()` via equipe-france,
      remplit `players[]` et `frenchStatus`. Découpage déterministe + table de
      mots-clés pour classer les titres (win/out/tour). Haiku = filet de sécurité
      ultérieur, pas maintenant.
- [ ] Réconciliation des noms de tournois BWF <-> equipe-france (table d'alias ou
      correspondance par nom + dates).

## Quand l'IA (Claude/Haiku) sert — et quand non

L'IA n'a sa place qu'à 3 endroits : extraire des pages HTML instables, réconcilier
les noms entre sources, classer un titre en texte libre que les règles ne savent
pas trancher. **Tout le reste reste du code déterministe** (filtrage par date/niveau,
découpage du fil, dédup). Ne mets pas d'appel LLM là où une table de mots-clés suffit.

## Commandes

```bash
# Front
npm install
npm run dev      # http://localhost:5173
npm run build

# Collecteur (régénère public/data.json)
mvn -f collector/pom.xml compile exec:java -Dexec.args="public/data.json"
```

Après modif du collecteur, **toujours** le relancer et vérifier que `data.json`
reste valide et conforme au schéma avant de committer.

## Déploiement

```
cron / clic  ->  GitHub Actions  ->  collecteur  ->  commit data.json
                                                       | (push)
                                                       v
                                          Vercel rebuild + déploie
```

- Vercel ne sert que du statique : **jamais** de Java ni d'appel API côté Vercel.
- Les secrets (ex. `ANTHROPIC_API_KEY`) vont dans **GitHub Actions secrets**,
  jamais dans Vercel ni dans le code.
- Le workflow ne commite que si `data.json` a changé.

## Garde-fous

- **Ne pas coder en dur de données badminton dans `src/App.jsx`** : tout vient du JSON.
- **Échec gracieux** : si une source échoue, ne réécris PAS un `data.json` vide ou
  cassé (cela viderait le site). Garde la dernière bonne version ou sors en erreur.
- **Ne jamais scraper TournamentSoftware ni Flashscore** (robots.txt).
- Usage personnel : User-Agent explicite, requêtes espacées, cache, cron raisonnable.
- Contenu en français, **UTF-8** partout.

## Identités à ne pas confondre

- C'est **Alex Lanier** (et non « Lasnier »).
- « Popov » = **deux frères** : **Christo Popov** et **Toma Junior Popov**. Un titre
  qui dit seulement « Popov » est ambigu : privilégier les mentions avec prénom.
- Le double : **Delphine Delrue / Thom Gicquel** — l'ordre des noms varie selon les
  sources (« Gicquel-Delrue », « Delrue-Gicquel ») ; matcher sur l'un ou l'autre.
