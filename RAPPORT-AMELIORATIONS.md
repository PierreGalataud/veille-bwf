# Audit qualité — CLOS (appliqué intégralement le 2026-06-11)

Ce fichier est la fiche de clôture d'un audit du code mené puis appliqué le
2026-06-11. **Il ne décrit aucun problème actuel** : tout ce qui suit est corrigé.
Le rapport d'audit complet (analyse détaillée, lignes, justifications) est dans
l'historique git de ce fichier ; les corrections sont dans les commits « Audit
lot 1 » à « Audit lot 6 ».

Les enseignements durables (bonnes pratiques, pièges, limites assumées) ont été
**migrés dans CLAUDE.md**, qui reste la seule référence à lire avant de modifier
le code. Ne pas ré-appliquer ce qui suit.

## Ce qui a été fait

| Lot | Contenu | Où |
|---|---|---|
| 1 | Harnais JUnit 5 + extraction `classify()` testable, 25 tests (6 rouges documentant les bugs) | `collector/src/test/…/CollectorTest.java` |
| 2 | Bugs corrigés : noms en mots entiers, chevauchement au passage d'année, sorties collectives ≠ oppositions, appariement « masters » | `PlayerResults`, `EquipeFrance` |
| 3 | Écriture atomique de data.json ; CI : step tests, validation jq du contrat, `pull --rebase`, timeout | `Collector.writeAtomic`, `refresh.yml` |
| 4 | Front : replis sur clés manquantes, états vides, badge de fraîcheur, footer honnête ; throttle equipe-france centralisé (`fetchEf`) ; `parseParticipation` sans repli risqué | `App.jsx`, `EquipeFrance` |
| 5 | Découpage du monolithe en classes (`BwfCalendar`, `EquipeFrance`, `PlayerResults`, `DataJson`…) — comportement identique vérifié au diff de data.json près | `collector/src/main/java/veille/` |
| 6 | `upcoming[].french` résolu pour les tournois à ≤ 14 jours ; auto-refresh du front (15 min) ; rank du double documenté comme volontairement null | `Collector`, `App.jsx`, `PlayerResults` |

## Méthode (à réutiliser pour un futur audit)

1. Tests ROUGES d'abord : chaque bug reproduit par un test avant correction.
2. Un lot = un commit, collecteur relancé et diff de `data.json` inspecté à chaque
   lot (c'est ce diff qui a attrapé la régression U+202F du lot 5).
3. Refactoring en DERNIER, sous la protection des tests, avec exigence de diff
   vide sur la sortie.
4. Respect des choix volontaires du projet (trois états, `tone: null` marqués pour
   le LLM, échec gracieux) — un audit ne « corrige » pas les invariants.
