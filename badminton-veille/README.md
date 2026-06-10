# Veille BWF World Tour

Tableau de bord des tournois BWF World Tour de la semaine, avec suivi des Français (Lanier, Popov).

- **Front** : React (Vite), déployé sur Vercel. Il lit `public/data.json`.
- **Collecteur** : Java 17 / Maven (`collector/`). Il écrit `public/data.json`.
- **Liaison** : GitHub Actions exécute le collecteur sur un horaire et commite `data.json` ;
  chaque push redéclenche automatiquement le déploiement Vercel.

```
GitHub Actions (horaire) → collecteur Java → public/data.json
        → git push → Vercel rebuild auto → votre-site.vercel.app
```

## 1. Créer le dépôt GitHub

1. Sur github.com : **New repository**, nom `veille-bwf`, **sans** README ni .gitignore (le projet en a déjà).
2. Dans ce dossier, en ligne de commande :

```bash
git init
git add .
git commit -m "Initial commit : veille BWF"
git branch -M main
git remote add origin https://github.com/VOTRE-PSEUDO/veille-bwf.git
git push -u origin main
```

(Pas à l'aise avec git ? **GitHub Desktop** fait la même chose en mode graphique.)

## 2. Importer dans Vercel

1. Vercel → **Add New… → Project** → choisissez `veille-bwf`.
2. Vercel détecte **Vite** tout seul. Ne changez rien, cliquez **Deploy**.
3. Au bout d'une minute, le site est en ligne sur `https://veille-bwf-xxxx.vercel.app`.

## 3. Activer le rafraîchissement automatique

1. Onglet **Actions** du dépôt → activez les workflows si demandé.
2. Workflow **« Rafraîchir les données »** → bouton **Run workflow** pour un premier test.
   Il compile le collecteur, régénère `public/data.json`, le commite — et Vercel se redéploie seul.
3. Ensuite il tourne automatiquement (toutes les 6 h par défaut, voir `.github/workflows/refresh.yml`).

## Lancer en local (optionnel)

```bash
# le front
npm install
npm run dev            # http://localhost:5173

# le collecteur (régénère public/data.json)
mvn -f collector/pom.xml compile exec:java -Dexec.args="public/data.json"
```

## Étapes suivantes

- **Scraping réel** : remplacer `buildData()` dans `collector/src/main/java/veille/Collector.java`
  par une vraie collecte (Jsoup, dépendance déjà préparée dans `collector/pom.xml`).
- **Branche Claude** : pour l'extraction et la réconciliation des noms, ajouter le SDK
  `com.anthropic:anthropic-java` et stocker la clé dans **Settings → Secrets → Actions**
  (`ANTHROPIC_API_KEY`), puis l'exposer au job dans le workflow.

## Bon à savoir

- Le plan Vercel **Hobby** est gratuit et réservé à un usage personnel.
- BWF / TournamentSoftware n'ont pas d'API publique : usage personnel, requêtes espacées, cache.
- Un workflow planifié GitHub se met en pause après ~60 jours sans activité du dépôt ;
  les commits réguliers du collecteur le maintiennent actif.
