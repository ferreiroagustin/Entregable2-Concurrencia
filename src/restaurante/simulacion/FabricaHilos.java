package restaurante.simulacion;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fábrica de hilos para los pools: les pone un nombre legible, un
 * {@link Thread.UncaughtExceptionHandler} que registra cualquier excepción no
 * capturada (en consola y en el log) y lleva la cuenta de los hilos creados,
 * para poder verificar al final que todos terminaron.
 */
public final class FabricaHilos implements ThreadFactory {

    private final String prefijo;
    private final boolean daemon;
    private final Thread.UncaughtExceptionHandler manejador;
    private final AtomicInteger contador = new AtomicInteger();
    private final List<Thread> hilos = new CopyOnWriteArrayList<>();

    public FabricaHilos(String prefijo, boolean daemon, Thread.UncaughtExceptionHandler manejador) {
        this.prefijo = prefijo;
        this.daemon = daemon;
        this.manejador = manejador;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefijo + "-" + contador.incrementAndGet());
        t.setDaemon(daemon);
        if (manejador != null) {
            t.setUncaughtExceptionHandler(manejador);
        }
        hilos.add(t);
        return t;
    }

    /** Espera (join) a que terminen todos los hilos creados, con un tiempo máximo total. */
    public void esperarHilos(long timeoutMs) throws InterruptedException {
        long limite = System.currentTimeMillis() + timeoutMs;
        for (Thread t : hilos) {
            long resta = limite - System.currentTimeMillis();
            if (resta <= 0) {
                return;
            }
            t.join(resta);
        }
    }

    /** Cantidad de hilos de esta fábrica que siguen vivos. */
    public int vivos() {
        int n = 0;
        for (Thread t : hilos) {
            if (t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    public int creados() {
        return contador.get();
    }

    public String prefijo() {
        return prefijo;
    }
}
