package veille;

/**
 * Plage de dates equipe-france SANS année : mois/jour de début et de fin.
 * {@link #UNKNOWN} si la source n'en donne pas (ex. lien glané sur l'accueil).
 */
record EfDateRange(int startMonth, int startDay, int endMonth, int endDay) {

    static final EfDateRange UNKNOWN = new EfDateRange(0, 0, 0, 0);

    boolean known() {
        return startMonth != 0 && endMonth != 0;
    }
}
