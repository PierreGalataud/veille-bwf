package veille;

import java.time.LocalDate;

/** Un tournoi World Tour extrait du calendrier BWF. */
record Tournament(
        String name, String tier, String location, String prize,
        LocalDate start, LocalDate end) {}
