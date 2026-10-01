package restaurante.simulacion;

import java.util.concurrent.ThreadLocalRandom;

import restaurante.config.Rango;
import restaurante.modelo.Menu;

/**
 * Números aleatorios para la simulación. Usa {@link ThreadLocalRandom}: cada
 * hilo tiene su propio generador, así que no hay contención ni estado
 * compartido (es seguro para hilos sin sincronizar).
 */
public final class Aleatorio {

    private static final Menu[] MENUS = Menu.values();

    private Aleatorio() {
    }

    /** Un valor entre min y max (ambos incluidos). */
    public static long entre(Rango r) {
        if (r.min() >= r.max()) {
            return r.min();
        }
        return ThreadLocalRandom.current().nextLong(r.min(), r.max() + 1);
    }

    /** Duerme un tiempo aleatorio dentro del rango. */
    public static void dormir(Rango r) throws InterruptedException {
        Thread.sleep(entre(r));
    }

    /** Un menú al azar de la lista de menús disponibles. */
    public static Menu menu() {
        return MENUS[ThreadLocalRandom.current().nextInt(MENUS.length)];
    }
}
