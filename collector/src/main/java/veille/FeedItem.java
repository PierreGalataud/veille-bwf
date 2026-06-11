package veille;

/** Une entrée du fil d'actualités equipe-france : DATE · TOURNOI · TITRE, plus le
 *  slug de l'URL (améliore le rappel des noms de joueurs). */
record FeedItem(String date, String tournoi, String title, String href) {}
