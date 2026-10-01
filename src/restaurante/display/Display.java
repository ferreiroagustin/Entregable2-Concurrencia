package restaurante.display;

import java.io.PrintStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import restaurante.estado.Evento;
import restaurante.web.CanalSSE;

/**
 * Hilo de display (único consumidor de la cola de eventos).
 *
 * <p>Consume la {@link BlockingQueue} de eventos EN ORDEN y, por cada cambio:
 * <ul>
 * <li>escribe en el log la acción y el estado completo del restaurante;</li>
 * <li>lo muestra en la consola (completo, resumido o nada, según el modo);</li>
 * <li>lo publica en formato JSON para el frontend web (SSE).</li>
 * </ul>
 * Termina al recibir la poison pill {@link Evento#FIN}. Como la pill se encola
 * cuando ya terminaron todos los actores, antes de terminar el Display procesa
 * TODO lo que quedó en la cola ("al llegar a T sigue mostrando hasta vaciarla").
 */
public final class Display implements Runnable {

    /** Cuánto se muestra en la consola. */
    public enum Modo {
        /** Línea del evento + estado completo del restaurante. */
        COMPLETO,
        /** Solo la línea del evento. */
        RESUMIDO,
        /** Nada (se usa en las repeticiones de estrés; el log se escribe igual). */
        SILENCIOSO
    }

    private final BlockingQueue<Evento> cola;
    private final LogArchivo log;
    private final CanalSSE canal;
    private final Modo modo;
    private final PrintStream salida;
    private final AtomicLong procesados = new AtomicLong();
    private volatile long ultimaSecuencia;

    public Display(BlockingQueue<Evento> cola, LogArchivo log, CanalSSE canal, Modo modo, PrintStream salida) {
        this.cola = cola;
        this.log = log;
        this.canal = canal;
        this.modo = modo;
        this.salida = salida;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Evento e = cola.take();
                if (e.esFin()) {
                    break;
                }
                procesar(e);
                if (cola.isEmpty()) {
                    log.vaciarBuffer();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Aun interrumpido, se vacía lo que quedó en la cola sin bloquear.
            Evento e;
            while ((e = cola.poll()) != null && !e.esFin()) {
                procesar(e);
            }
        } finally {
            log.vaciarBuffer();
        }
    }

    private void procesar(Evento e) {
        try {
            if (e.secuencia() <= ultimaSecuencia) {
                log.escribir("ADVERTENCIA: evento fuera de orden #" + e.secuencia());
            }
            ultimaSecuencia = e.secuencia();
            String linea = FormatoTexto.linea(e);
            String estado = FormatoTexto.estado(e.snapshot());
            log.escribir(linea);
            log.escribir(estado);
            switch (modo) {
                case COMPLETO -> salida.println(linea + System.lineSeparator() + estado);
                case RESUMIDO -> salida.println(linea);
                case SILENCIOSO -> {
                }
            }
            if (canal != null) {
                canal.publicarEvento(e.secuencia(), Json.evento(e));
            }
            procesados.incrementAndGet();
        } catch (RuntimeException ex) {
            // Un error mostrando un evento no tiene que matar al Display.
            System.err.println("Error en el Display procesando el evento #" + e.secuencia() + ": " + ex);
            log.escribir("ERROR en el Display procesando el evento #" + e.secuencia() + ": " + ex);
        }
    }

    public long procesados() {
        return procesados.get();
    }

    public long ultimaSecuencia() {
        return ultimaSecuencia;
    }
}
