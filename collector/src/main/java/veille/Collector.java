package veille;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Collecteur v1.
 *
 * Pour l'instant il émet un instantané connu de la semaine, afin que toute la
 * chaîne fonctionne de bout en bout (collecteur -> data.json -> GitHub Actions
 * -> Vercel). C'est volontaire : on valide le tuyau avant d'y faire couler de
 * l'eau.
 *
 * ÉTAPE SUIVANTE (scraping réel) : remplacer le contenu de buildData() par une
 * vraie collecte. La dépendance Jsoup est déjà préparée (commentée) dans
 * pom.xml. Esquisse :
 *
 *   Document doc = Jsoup.connect("https://bwfworldtour.bwfbadminton.com/calendar/")
 *                       .userAgent("veille-bwf/1.0").timeout(15000).get();
 *   // ... extraire les tournois de la semaine, filtrer sur les 5 niveaux,
 *   //     repérer Lanier / Popov, puis construire le JSON ci-dessous.
 *
 * Argument optionnel : chemin de sortie (défaut "public/data.json").
 */
public class Collector {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "public/data.json");
        String json = buildData();

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.println("data.json écrit : " + out.toAbsolutePath());
    }

    private static String buildData() {
        String generatedAt = Instant.now().toString();

        // JSON de la semaine. Les guillemets n'ont pas besoin d'échappement
        // dans un text block Java. Seul __GENERATED_AT__ est substitué.
        String template = """
        {
          "generatedAt": "__GENERATED_AT__",
          "weekLabel": "Semaine du 9 – 14 juin 2026",
          "current": [
            {
              "name": "SATHIO GROUP Australian Open",
              "tier": "500",
              "location": "Sydney Olympic Park, Australie",
              "dates": "9 – 14 juin 2026",
              "prize": "500 000 $",
              "timezone": "UTC+10",
              "dayLabel": "Jour 2 / 6 · 10 juin",
              "seeds": [
                { "rank": "TS1", "name": "Anders Antonsen (DEN)" },
                { "rank": "TS2", "name": "Chou Tien-chen (TPE)" },
                { "rank": "—", "name": "Lin Chun-yi (TPE)" },
                { "rank": "SD", "name": "Akane Yamaguchi (JPN), P.V. Sindhu (IND), Pornpawee Chochuwong (THA)" }
              ],
              "frenchStatus": {
                "present": false,
                "title": "Aucun Français dans le tableau",
                "note": "Lanier et les frères Popov ne figurent pas dans les aperçus ni le rapport du jour 1 — déplacement probablement écarté après la tournée asiatique. À valider sur le tableau officiel.",
                "confirm": true
              }
            }
          ],
          "players": [
            {
              "name": "Alex Lanier",
              "rank": "#8 mondial",
              "lines": [
                { "label": "Dernier", "value": "Vainqueur — Open de Singapour (S750)", "tone": "win" },
                { "label": "Puis", "value": "éliminé — Open d'Indonésie (S1000)", "tone": "out" },
                { "label": "Prochain", "value": "Macau Open (S300) — engagement à confirmer", "tone": null }
              ]
            },
            {
              "name": "Christo Popov",
              "rank": "#7 mondial",
              "lines": [
                { "label": "Dernier", "value": "sorti au 1er tour — Open d'Indonésie (S1000)", "tone": "out" },
                { "label": "Prochain", "value": "Macau Open (S300) — à confirmer", "tone": null }
              ]
            },
            {
              "name": "Toma Junior Popov",
              "rank": "",
              "lines": [
                { "label": "Référence", "value": "finaliste de l'Orléans Masters (S300)", "tone": null },
                { "label": "Prochain", "value": "calendrier à confirmer (entry lists non publiées)", "tone": null }
              ]
            },
            {
              "name": "Gicquel / Delrue",
              "rank": "double mixte",
              "lines": [
                { "label": "Dernier", "value": "quart de finale — Open d'Indonésie (S1000)", "tone": "out" }
              ]
            }
          ],
          "upcoming": [
            { "dates": "16 – 21 juin", "name": "Macau Open", "tier": "300", "french": "FR : à confirmer" },
            { "dates": "23 – 28 juin", "name": "US Open", "tier": "300", "french": "FR : à confirmer" },
            { "dates": "30 juin – 5 juil.", "name": "Canada Open", "tier": "300", "french": "FR : à confirmer" },
            { "dates": "Juillet", "name": "China Open", "tier": "1000", "french": "FR : à confirmer" }
          ]
        }
        """;

        return template.replace("__GENERATED_AT__", generatedAt);
    }
}
