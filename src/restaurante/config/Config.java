package restaurante.config;

/**
 * TODAS las constantes de la simulación. Para cambiar el comportamiento del
 * restaurante alcanza con modificar estos valores y volver a compilar.
 *
 * Los tiempos están expresados en milisegundos.
 */
public final class Config {

    private Config() {
    }

    // ------------------------------------------------------------------
    // Constantes principales del enunciado
    // ------------------------------------------------------------------

    /** T: duración de la simulación (el restaurante cierra al llegar a T). */
    public static final long T = 60_000;
    /** M: cantidad de mesas. */
    public static final int M = 4;
    /** P: personas por mesa. */
    public static final int P = 2;
    /** Z: cantidad de mozos. */
    public static final int Z = 2;
    /** C: cantidad de cocineros. */
    public static final int C = 2;
    /** Y: cantidad de cajas (cajeros). */
    public static final int Y = 1;

    /** TPmin / TPmax: tiempo entre la llegada de dos clientes. */
    public static final long TP_MIN = 300;
    public static final long TP_MAX = 1_200;
    /** TMmin / TMmax: tiempo que tarda un cliente en elegir el menú. */
    public static final long TM_MIN = 1_000;
    public static final long TM_MAX = 3_000;
    /** TQmin / TQmax: tiempo que tarda un cliente en comer. */
    public static final long TQ_MIN = 3_000;
    public static final long TQ_MAX = 6_000;
    /** TZmin / TZmax: tiempo que tarda un mozo en anotar el pedido y llevarlo a la cocina. */
    public static final long TZ_MIN = 500;
    public static final long TZ_MAX = 1_200;
    /** TRmin / TRmax: tiempo que tarda un mozo en retirar los platos de la cocina y servirlos. */
    public static final long TR_MIN = 500;
    public static final long TR_MAX = 1_000;
    /** TLmin / TLmax: tiempo que tarda un mozo en levantar los platos y limpiar la mesa. */
    public static final long TL_MIN = 800;
    public static final long TL_MAX = 1_500;
    /** TYmin / TYmax: tiempo que tarda un cajero en cobrarle a un cliente. */
    public static final long TY_MIN = 500;
    public static final long TY_MAX = 1_000;

    // ------------------------------------------------------------------
    // Menú: cada plato tiene su precio (en pesos) y su propio TCmin / TCmax
    // ------------------------------------------------------------------

    public static final String MILANESA_NOMBRE = "Milanesa con papas fritas";
    public static final long MILANESA_PRECIO = 9_500;
    public static final long MILANESA_TC_MIN = 2_000;
    public static final long MILANESA_TC_MAX = 4_000;

    public static final String ENSALADA_NOMBRE = "Ensalada César";
    public static final long ENSALADA_PRECIO = 6_200;
    public static final long ENSALADA_TC_MIN = 800;
    public static final long ENSALADA_TC_MAX = 1_500;

    public static final String PIZZA_NOMBRE = "Pizza de muzzarella";
    public static final long PIZZA_PRECIO = 8_000;
    public static final long PIZZA_TC_MIN = 1_800;
    public static final long PIZZA_TC_MAX = 3_200;

    public static final String RAVIOLES_NOMBRE = "Ravioles con tuco";
    public static final long RAVIOLES_PRECIO = 8_800;
    public static final long RAVIOLES_TC_MIN = 1_500;
    public static final long RAVIOLES_TC_MAX = 3_000;

    public static final String HAMBURGUESA_NOMBRE = "Hamburguesa completa";
    public static final long HAMBURGUESA_PRECIO = 9_000;
    public static final long HAMBURGUESA_TC_MIN = 1_500;
    public static final long HAMBURGUESA_TC_MAX = 2_500;

    public static final String FLAN_NOMBRE = "Flan con dulce de leche";
    public static final long FLAN_PRECIO = 4_500;
    public static final long FLAN_TC_MIN = 500;
    public static final long FLAN_TC_MAX = 1_000;

    // ------------------------------------------------------------------
    // Infraestructura
    // ------------------------------------------------------------------

    /** Puerto preferido del servidor web. Si está ocupado se prueba el siguiente. */
    public static final int PUERTO_WEB = 8080;
    /** Cuántos puertos consecutivos se prueban antes de rendirse. */
    public static final int PUERTOS_A_PROBAR = 10;
    /** Carpeta donde se guardan los logs. */
    public static final String CARPETA_LOGS = "logs";
    /** Carpeta con los archivos estáticos del frontend. */
    public static final String CARPETA_WEB = "web";

    /** Cada cuánto reintenta entrar un cliente que espera afuera (tryAcquire con timeout). */
    public static final long ESPERA_PUERTA_MS = 100;
    /** Tiempo máximo que el coordinador espera a cada grupo de hilos durante el cierre. */
    public static final long TIMEOUT_CIERRE_MS = 120_000;
    /** Cada cuánto se manda un "ping" por SSE para detectar navegadores desconectados. */
    public static final long PING_SSE_MS = 15_000;

    // ------------------------------------------------------------------
    // Modo de prueba rápido (--repeticiones)
    // ------------------------------------------------------------------

    /** T usado en cada repetición del modo de estrés. */
    public static final long T_RAPIDO = 3_000;
    /** Factor por el que se multiplican todos los tiempos en el modo de estrés. */
    public static final double ESCALA_RAPIDA = 0.03;
}
