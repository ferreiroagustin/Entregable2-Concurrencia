package restaurante.recursos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.EstadoPlato;
import restaurante.modelo.Pedido;
import restaurante.modelo.Plato;

/**
 * Cocina: cola FIFO de pedidos protegida con un {@link ReentrantLock} (justo) y
 * una {@link Condition} "hayPlatos".
 *
 * <p>Se eligió ReentrantLock + Condition (y no una BlockingQueue) porque la
 * operación del cocinero no es "sacar el primer elemento": es "buscar, en el
 * primer pedido que tenga uno, un plato SIN COCINAR y marcarlo" sin sacar el
 * pedido de la cola. Varios cocineros pueden trabajar a la vez sobre el mismo
 * pedido. Esa búsqueda + marcado tiene que ser atómica.
 *
 * <p>Todo {@code lock()} tiene su {@code unlock()} en un {@code finally} y
 * todo {@code await()} está dentro de un {@code while}.
 */
public final class Cocina {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition hayPlatos = lock.newCondition();
    private final EstadoRestaurante estado;

    // ---- Protegido por lock ----
    private final List<Pedido> pedidos = new ArrayList<>();
    private boolean cerrada;

    private final AtomicInteger platosPedidos = new AtomicInteger();
    private final AtomicInteger platosCocinados = new AtomicInteger();

    public Cocina(EstadoRestaurante estado) {
        this.estado = estado;
    }

    /** Un mozo deja un pedido: se agrega al final de la cola y se despierta a los cocineros. */
    public void recibirPedido(Pedido pedido, int mozoId) {
        lock.lock();
        try {
            pedidos.add(pedido);
            platosPedidos.addAndGet(pedido.platos().size());
            estado.notificar(Actor.mozo(mozoId),
                    "Deja el pedido #" + pedido.id() + " de la mesa " + pedido.mesa().id() + " en la cocina ("
                            + pedido.platos().size() + " platos)",
                    v -> {
                        var p = v.agregarPedido(pedido.id(), pedido.mesa().id());
                        for (Plato pl : pedido.platos()) {
                            v.agregarPlato(p, pl.clienteId(), pl.menu());
                        }
                        v.platosPedidos += pedido.platos().size();
                        var m = v.mozo(mozoId);
                        m.poner(EstadoEmpleado.ESPERANDO, "", -1);
                        m.tareas++;
                    });
            hayPlatos.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * El cocinero toma, del primer pedido de la cola que tenga uno, un plato sin
     * cocinar. Si no hay, espera ({@code await}) en la Condition.
     *
     * @return el plato a cocinar, o null si la cocina cerró y no queda nada
     */
    public Plato tomarPlato(int cocineroId) throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                Plato plato = primerPlatoPendiente();
                if (plato != null) {
                    plato.empezarACocinar(cocineroId);
                    final Plato p = plato;
                    estado.notificar(Actor.cocinero(cocineroId),
                            "Empieza a cocinar " + p.menu().nombre() + " (pedido #" + p.pedido().id() + ", mesa "
                                    + p.pedido().mesa().id() + ")",
                            v -> {
                                var pv = v.pedido(p.pedido().id()).platoDe(p.clienteId());
                                pv.estado = EstadoPlato.COCINANDO;
                                pv.cocinero = cocineroId;
                                v.cliente(p.clienteId()).plato = EstadoPlato.COCINANDO;
                                v.cocinero(cocineroId).poner(EstadoEmpleado.COCINANDO,
                                        p.menu().nombre() + " (pedido #" + p.pedido().id() + ")",
                                        p.pedido().mesa().id());
                            });
                    return plato;
                }
                if (cerrada) {
                    return null;
                }
                hayPlatos.await();
            }
        } finally {
            lock.unlock();
        }
    }

    private Plato primerPlatoPendiente() {
        for (Pedido p : pedidos) {
            for (Plato pl : p.platos()) {
                if (pl.estado() == EstadoPlato.PENDIENTE) {
                    return pl;
                }
            }
        }
        return null;
    }

    /**
     * El cocinero deja el plato listo.
     *
     * @return true si con este plato se completó el pedido (hay que avisar a los mozos)
     */
    public boolean platoListo(Plato plato, int cocineroId) {
        lock.lock();
        try {
            plato.terminar();
            platosCocinados.incrementAndGet();
            final boolean completo = plato.pedido().platoListo() == 0;
            if (completo) {
                pedidos.remove(plato.pedido());
            }
            estado.notificar(Actor.cocinero(cocineroId),
                    "Terminó " + plato.menu().nombre() + " (pedido #" + plato.pedido().id() + ")"
                            + (completo ? ": el pedido está completo" : ""),
                    v -> {
                        var pv = v.pedido(plato.pedido().id());
                        pv.platoDe(plato.clienteId()).estado = EstadoPlato.LISTO;
                        pv.listo = completo;
                        v.cliente(plato.clienteId()).plato = EstadoPlato.LISTO;
                        v.platosCocinados++;
                        var c = v.cocinero(cocineroId);
                        c.poner(EstadoEmpleado.ESPERANDO, "", -1);
                        c.tareas++;
                    });
            return completo;
        } finally {
            lock.unlock();
        }
    }

    /** Cierre: despierta a todos los cocineros; los que no tengan nada que cocinar terminan. */
    public void cerrar() {
        lock.lock();
        try {
            cerrada = true;
            hayPlatos.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** true si no quedan pedidos en la cola (para invariantes). */
    public boolean vacia() {
        lock.lock();
        try {
            return pedidos.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public int platosPedidos() {
        return platosPedidos.get();
    }

    public int platosCocinados() {
        return platosCocinados.get();
    }
}
