package restaurante.estado;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Registro central del estado que se muestra y punto único de notificación.
 *
 * <p>Usa un {@link ReentrantReadWriteLock}: los actores ESCRIBEN (cada cambio
 * pasa por {@link #notificar}) y el servidor web LEE ({@link #snapshotActual()})
 * sin bloquear a otros lectores.
 *
 * <p>Dentro del write lock se hacen, de forma atómica, tres cosas: aplicar el
 * cambio, sacar la foto inmutable ({@link Snapshot}) y encolar el
 * {@link Evento}. Por eso el orden de la cola de eventos coincide exactamente
 * con el orden de los cambios, y cada evento lleva el estado del momento en que
 * se mandó la notificación.
 *
 * <p>Anti-deadlock: este write lock es un "lock hoja". Se puede tomar teniendo
 * otro lock (el monitor de una mesa, el lock de la cocina), pero mientras se lo
 * tiene NO se toma ningún otro lock de la aplicación (la cola de eventos es
 * ilimitada, así que {@code add} nunca se bloquea). Sin esperas circulares no
 * puede haber deadlock.
 */
public final class EstadoRestaurante {

    /** Cambio a aplicar sobre la vista. Se ejecuta con el write lock tomado: tiene que ser corto. */
    @FunctionalInterface
    public interface Cambio {
        void aplicar(Vista v);
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final BlockingQueue<Evento> colaEventos = new LinkedBlockingQueue<>();
    private final Vista vista;
    private final long tiempoTotalMs;

    // Protegido por el write lock.
    private long secuencia;

    // Se escribe una sola vez desde el coordinador y se lee desde todos los hilos.
    private volatile long inicioNanos = -1;

    public EstadoRestaurante(int mesas, int personasPorMesa, int mozos, int cocineros, int cajeros,
            long tiempoTotalMs) {
        this.vista = new Vista(mesas, personasPorMesa, mozos, cocineros, cajeros);
        this.tiempoTotalMs = tiempoTotalMs;
    }

    /** Arranca el reloj de la simulación. */
    public void iniciarReloj() {
        inicioNanos = System.nanoTime();
    }

    /** Milisegundos transcurridos desde que arrancó la simulación. */
    public long tiempoMs() {
        long inicio = inicioNanos;
        return inicio < 0 ? 0 : (System.nanoTime() - inicio) / 1_000_000;
    }

    /**
     * Aplica un cambio a la vista y encola el evento con la foto resultante.
     *
     * @param actor       quién produce el cambio
     * @param descripcion texto legible de la acción
     * @param cambio      modificación de la vista (puede ser null)
     */
    public void notificar(Actor actor, String descripcion, Cambio cambio) {
        lock.writeLock().lock();
        try {
            if (cambio != null) {
                cambio.aplicar(vista);
            }
            long seq = ++secuencia;
            long t = tiempoMs();
            Snapshot foto = vista.foto(seq, t, tiempoTotalMs);
            colaEventos.add(new Evento(seq, t, actor, descripcion, foto));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Foto del estado actual, tomada con el read lock (la usa el servidor web). */
    public Snapshot snapshotActual() {
        lock.readLock().lock();
        try {
            return vista.foto(secuencia, tiempoMs(), tiempoTotalMs);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Cola de eventos que consume el Display (productor-consumidor). */
    public BlockingQueue<Evento> colaEventos() {
        return colaEventos;
    }

    /** Encola la poison pill del Display. */
    public void finalizarDisplay() {
        colaEventos.add(Evento.FIN);
    }

    public long tiempoTotalMs() {
        return tiempoTotalMs;
    }
}
