package restaurante.config;

/**
 * Parámetros efectivos de UNA simulación. Se construyen a partir de las
 * constantes de {@link Config}; los flags de línea de comandos solo pueden
 * cambiar T (--t) o pedir el modo rápido (--repeticiones).
 *
 * Es un record inmutable, así que se puede compartir entre hilos sin sincronizar.
 */
public record Parametros(
        long t,
        int mesas,
        int personasPorMesa,
        int mozos,
        int cocineros,
        int cajeros,
        Rango llegada,
        Rango eleccion,
        Rango comida,
        Rango anotar,
        Rango servir,
        Rango limpiar,
        Rango cobrar,
        double escalaCocina) {

    public Parametros {
        if (t <= 0 || mesas <= 0 || personasPorMesa <= 0 || mozos <= 0 || cocineros <= 0 || cajeros <= 0) {
            throw new IllegalArgumentException("Todas las cantidades y T tienen que ser positivas");
        }
    }

    /** Parámetros tal cual están en {@link Config}. */
    public static Parametros porDefecto() {
        return new Parametros(
                Config.T, Config.M, Config.P, Config.Z, Config.C, Config.Y,
                new Rango(Config.TP_MIN, Config.TP_MAX),
                new Rango(Config.TM_MIN, Config.TM_MAX),
                new Rango(Config.TQ_MIN, Config.TQ_MAX),
                new Rango(Config.TZ_MIN, Config.TZ_MAX),
                new Rango(Config.TR_MIN, Config.TR_MAX),
                new Rango(Config.TL_MIN, Config.TL_MAX),
                new Rango(Config.TY_MIN, Config.TY_MAX),
                1.0);
    }

    /**
     * Parámetros para el modo de estrés: todos los tiempos escalados por
     * {@link Config#ESCALA_RAPIDA} y T = {@link Config#T_RAPIDO}. Según el número
     * de variante se varían M, P, Z, C e Y para cubrir casos borde (P = 1, un solo
     * mozo, etc.).
     */
    public static Parametros rapidos(int variante) {
        double f = Config.ESCALA_RAPIDA;
        int mesas = 2 + variante % 3;          // 2..4
        int personas = 1 + variante % 3;       // 1..3
        int mozos = 1 + variante % 2;          // 1..2
        int cocineros = 1 + (variante / 2) % 3; // 1..3
        int cajeros = 1 + (variante / 3) % 2;  // 1..2
        return new Parametros(
                Config.T_RAPIDO, mesas, personas, mozos, cocineros, cajeros,
                new Rango(Config.TP_MIN, Config.TP_MAX).escalar(f),
                new Rango(Config.TM_MIN, Config.TM_MAX).escalar(f),
                new Rango(Config.TQ_MIN, Config.TQ_MAX).escalar(f),
                new Rango(Config.TZ_MIN, Config.TZ_MAX).escalar(f),
                new Rango(Config.TR_MIN, Config.TR_MAX).escalar(f),
                new Rango(Config.TL_MIN, Config.TL_MAX).escalar(f),
                new Rango(Config.TY_MIN, Config.TY_MAX).escalar(f),
                f);
    }

    /** Copia con otro T (flag --t). */
    public Parametros conT(long nuevoT) {
        return new Parametros(nuevoT, mesas, personasPorMesa, mozos, cocineros, cajeros,
                llegada, eleccion, comida, anotar, servir, limpiar, cobrar, escalaCocina);
    }

    /** Capacidad de la puerta: P * M clientes adentro como máximo. */
    public int capacidad() {
        return mesas * personasPorMesa;
    }

    public String resumen() {
        return "T=" + t + "ms M=" + mesas + " P=" + personasPorMesa + " Z=" + mozos
                + " C=" + cocineros + " Y=" + cajeros + " | TP " + llegada + ", TM " + eleccion
                + ", TQ " + comida + ", TZ " + anotar + ", TR " + servir + ", TL " + limpiar
                + ", TY " + cobrar + ", escala TC x" + escalaCocina;
    }
}
