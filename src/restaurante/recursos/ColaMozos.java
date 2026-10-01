package restaurante.recursos;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.Mesa;
import restaurante.modelo.Pedido;
import restaurante.modelo.TareaMozo;
import restaurante.modelo.TipoTarea;

/**
 * Buzón de los mozos (pasaje de mensajes, patrón Productor-Consumidor).
 *
 * <p>Productores: las mesas (llamar al mozo), la cocina (platos listos) y los
 * clientes que se van (mesa sucia). Consumidores: los Z mozos.
 *
 * <p>Es una {@link PriorityBlockingQueue} ILIMITADA: {@code put} nunca se
 * bloquea, así que se puede encolar teniendo el monitor de una mesa sin riesgo
 * de deadlock. {@code take} bloquea al mozo mientras no haya trabajo
 * ("si no hay nada que hacer se quedan esperando").
 */
public final class ColaMozos {

    private final PriorityBlockingQueue<TareaMozo> cola = new PriorityBlockingQueue<>();
    private final AtomicLong secuencia = new AtomicLong();
    private final EstadoRestaurante estado;

    public ColaMozos(EstadoRestaurante estado) {
        this.estado = estado;
    }

    /**
     * Deposita una tarea. Primero se registra en la vista y después se pone en
     * la cola: así el evento "se encola" siempre precede al evento "un mozo la toma".
     */
    public void encolar(TipoTarea tipo, Mesa mesa, Pedido pedido, Actor quien, String descripcion,
            EstadoRestaurante.Cambio cambioExtra) {
        TareaMozo tarea = new TareaMozo(tipo, mesa, pedido, secuencia.incrementAndGet());
        estado.notificar(quien, descripcion, v -> {
            if (cambioExtra != null) {
                cambioExtra.aplicar(v);
            }
            v.encolarTareaMozo(tarea.secuencia(), tipo, tarea.mesaId());
        });
        cola.put(tarea);
    }

    /** Bloquea hasta que haya una tarea (la de mayor prioridad; FIFO a igual prioridad). */
    public TareaMozo tomar() throws InterruptedException {
        return cola.take();
    }

    /** Encola una poison pill por mozo. Tienen la prioridad más baja: se procesan al final. */
    public void encolarFin(int cantidadMozos) {
        for (int i = 0; i < cantidadMozos; i++) {
            cola.put(TareaMozo.fin(secuencia.incrementAndGet()));
        }
    }

    public boolean estaVacia() {
        return cola.isEmpty();
    }

    public int tamanio() {
        return cola.size();
    }
}
