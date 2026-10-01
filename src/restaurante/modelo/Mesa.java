package restaurante.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.recursos.ColaMozos;

/**
 * Mesa del restaurante, implementada como MONITOR (patrón Monitor):
 * todo el estado mutable está protegido por el lock intrínseco del objeto
 * ({@code synchronized}) y los hilos esperan condiciones con
 * {@code wait()} dentro de un {@code while} y se despiertan con
 * {@code notifyAll()}.
 *
 * <p>Además tiene una {@link CyclicBarrier} de P partes: los P clientes eligen
 * su menú y esperan en la barrera; la acción de barrera (que ejecuta el último
 * en llegar) llama al mozo. Es cíclica, así que se reutiliza con cada grupo que
 * ocupa la mesa.
 *
 * <p>Cierre: {@link #cerrar()} marca la ocupación como cancelada si el mozo
 * todavía no tomó el pedido. La carrera "el mozo toma el pedido" vs "se cierra
 * el restaurante" se resuelve acá adentro: las dos operaciones son
 * synchronized sobre la misma mesa, así que gana la primera que entra al
 * monitor y la otra ve el resultado.
 *
 * <p>Orden de locks: dentro del monitor solo se llama a
 * {@link EstadoRestaurante#notificar} (lock hoja) y a {@link ColaMozos#encolar}
 * (cola ilimitada + lock hoja). Nunca se toma el monitor de otra mesa ni el de
 * la sala de espera. La acción de barrera corre con el lock interno de la
 * barrera tomado y entra al monitor (barrera → mesa); por eso con el monitor
 * tomado nunca se llama a la barrera (ver {@link #terminarLimpieza}).
 */
public final class Mesa {

    private final int id;
    private final int capacidad;
    private final EstadoRestaurante estado;
    private final ColaMozos colaMozos;
    private final CyclicBarrier barrera;

    // ---- Estado protegido por el monitor (this) ----
    private EstadoMesa estadoMesa = EstadoMesa.LIBRE;
    private int ocupantes;
    private final Eleccion[] elecciones;
    /** El restaurante está cerrando. */
    private boolean cerrando;
    /** La ocupación actual no va a pedir (cierre antes del pedido o barrera rota). */
    private boolean cancelada;
    /** El mozo ya tomó el pedido de la ocupación actual. */
    private boolean pedidoTomado;
    /** Los platos ya están en la mesa. */
    private boolean servida;

    public Mesa(int id, int capacidad, EstadoRestaurante estado, ColaMozos colaMozos) {
        this.id = id;
        this.capacidad = capacidad;
        this.estado = estado;
        this.colaMozos = colaMozos;
        this.elecciones = new Eleccion[capacidad];
        this.barrera = new CyclicBarrier(capacidad, this::llamarMozo);
    }

    public int id() {
        return id;
    }

    public int capacidad() {
        return capacidad;
    }

    /** Estado actual (lectura sincronizada, para los invariantes). */
    public synchronized EstadoMesa estadoActual() {
        return estadoMesa;
    }

    // ------------------------------------------------------------------
    // Operaciones de los clientes
    // ------------------------------------------------------------------

    /** Un cliente del grupo asignado se sienta en su asiento. */
    public synchronized void sentarse(int clienteId, int asiento) {
        ocupantes++;
        if (estadoMesa == EstadoMesa.LIBRE) {
            estadoMesa = EstadoMesa.ELIGIENDO;
        }
        final EstadoMesa nuevo = estadoMesa;
        estado.notificar(Actor.cliente(clienteId),
                "Se sienta en la mesa " + id + " (asiento " + (asiento + 1) + ") y mira el menú",
                v -> {
                    var c = v.cliente(clienteId);
                    c.mesa = id;
                    c.asiento = asiento;
                    c.estado = EstadoCliente.ELIGIENDO;
                    v.mesa(id).estado = nuevo;
                });
    }

    /**
     * El cliente tarda {@code ms} en elegir. Se usa {@code wait(timeout)} en un
     * bucle (y no {@code sleep}) para que el cierre pueda despertarlo con
     * {@code notifyAll()} y cortar la elección.
     *
     * @return true si eligió; false si la ocupación se canceló por el cierre
     */
    public synchronized boolean elegir(int clienteId, int asiento, Menu menu, long ms) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        long resta;
        while (!cancelada && (resta = limite - System.nanoTime()) > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, resta);
        }
        if (cancelada) {
            return false;
        }
        elecciones[asiento] = new Eleccion(clienteId, asiento, menu);
        estado.notificar(Actor.cliente(clienteId), "Elige " + menu.nombre() + " y espera al resto de la mesa " + id,
                v -> {
                    var c = v.cliente(clienteId);
                    c.menu = menu;
                    c.estado = EstadoCliente.ESPERANDO_COMPANEROS;
                });
        return true;
    }

    /**
     * Barrera: el cliente espera a que los P de la mesa hayan elegido. NO es
     * synchronized: si lo fuera, la acción de barrera (que necesita el monitor)
     * no podría ejecutarse.
     */
    public void esperarCompaneros() throws InterruptedException {
        try {
            barrera.await();
        } catch (BrokenBarrierException e) {
            // Otro cliente de la mesa fue interrumpido: esta ocupación no va a pedir.
            marcarCancelada();
        }
    }

    /** Acción de la barrera: la ejecuta el último cliente en elegir. */
    private synchronized void llamarMozo() {
        if (cancelada) {
            return;
        }
        estadoMesa = EstadoMesa.ESPERANDO_MOZO;
        colaMozos.encolar(TipoTarea.TOMAR_PEDIDO, this, null, Actor.mesa(id),
                "Todos eligieron: la mesa " + id + " llama a un mozo",
                v -> {
                    v.mesa(id).estado = EstadoMesa.ESPERANDO_MOZO;
                    v.clientesDeMesa(id, c -> c.estado = EstadoCliente.ESPERANDO_MOZO);
                });
    }

    private synchronized void marcarCancelada() {
        if (!pedidoTomado) {
            cancelada = true;
        }
        notifyAll();
    }

    /**
     * El cliente espera que llegue el mozo.
     *
     * @return true si el mozo tomó el pedido; false si se canceló (cierre)
     */
    public synchronized boolean esperarMozo() throws InterruptedException {
        while (!pedidoTomado && !cancelada) {
            wait();
        }
        return pedidoTomado;
    }

    /** El cliente espera que el mozo traiga la comida de toda la mesa. */
    public synchronized void esperarComida() throws InterruptedException {
        while (!servida) {
            wait();
        }
    }

    /**
     * El cliente deja la mesa. El último en irse la deja SUCIA.
     *
     * @param cambioCliente cambio de la vista del cliente que se aplica en el mismo evento
     * @return true si era el último cliente de la mesa (hay que avisar a los mozos que la limpien)
     */
    public synchronized boolean retirarse(int clienteId, String descripcion, EstadoRestaurante.Cambio cambioCliente) {
        ocupantes--;
        final boolean ultimo = ocupantes == 0;
        if (ultimo) {
            estadoMesa = EstadoMesa.SUCIA;
        }
        estado.notificar(Actor.cliente(clienteId),
                descripcion + (ultimo ? " La mesa " + id + " queda vacía y sucia." : ""),
                v -> {
                    cambioCliente.aplicar(v);
                    if (ultimo) {
                        v.mesa(id).estado = EstadoMesa.SUCIA;
                        v.mesa(id).mozo = -1;
                    }
                });
        return ultimo;
    }

    // ------------------------------------------------------------------
    // Operaciones de los mozos
    // ------------------------------------------------------------------

    /**
     * El mozo llega a tomar el pedido. Es atómico respecto del cierre.
     *
     * @return las elecciones de los clientes, o null si la mesa se canceló (la tarea se descarta)
     */
    public synchronized List<Eleccion> tomarPedido(int mozoId, long secuenciaTarea) {
        if (cancelada) {
            notifyAll();
            return null;
        }
        pedidoTomado = true;
        estadoMesa = EstadoMesa.TOMANDO_PEDIDO;
        estado.notificar(Actor.mozo(mozoId), "Llega a la mesa " + id + " y anota el pedido",
                v -> {
                    v.quitarTareaMozo(secuenciaTarea);
                    v.mozo(mozoId).poner(EstadoEmpleado.TOMANDO_PEDIDO, "Anotando el pedido de la mesa " + id, id);
                    v.mesa(id).estado = EstadoMesa.TOMANDO_PEDIDO;
                    v.mesa(id).mozo = mozoId;
                    v.clientesDeMesa(id, c -> c.estado = EstadoCliente.PIDIENDO);
                });
        notifyAll();
        List<Eleccion> lista = new ArrayList<>(capacidad);
        for (Eleccion e : elecciones) {
            if (e != null) {
                lista.add(e);
            }
        }
        return lista;
    }

    /** El pedido de la mesa ya está en la cocina: los clientes esperan la comida. */
    public synchronized void pedidoEnCocina(int mozoId, int pedidoId) {
        estadoMesa = EstadoMesa.ESPERANDO_COMIDA;
        estado.notificar(Actor.mozo(mozoId), "Lleva el pedido #" + pedidoId + " de la mesa " + id + " a la cocina",
                v -> {
                    v.mesa(id).estado = EstadoMesa.ESPERANDO_COMIDA;
                    v.mesa(id).mozo = -1;
                    v.clientesDeMesa(id, c -> {
                        c.estado = EstadoCliente.ESPERANDO_COMIDA;
                        c.plato = EstadoPlato.PENDIENTE;
                    });
                });
        notifyAll();
    }

    /** El mozo empieza a llevar los platos a la mesa. */
    public synchronized void empezarAServir(int mozoId, long secuenciaTarea, int pedidoId) {
        estadoMesa = EstadoMesa.SIRVIENDO;
        estado.notificar(Actor.mozo(mozoId),
                "Retira de la cocina los platos del pedido #" + pedidoId + " y los lleva a la mesa " + id,
                v -> {
                    v.quitarTareaMozo(secuenciaTarea);
                    v.quitarPedido(pedidoId);
                    v.mozo(mozoId).poner(EstadoEmpleado.SIRVIENDO, "Llevando el pedido #" + pedidoId + " a la mesa " + id, id);
                    v.mesa(id).estado = EstadoMesa.SIRVIENDO;
                    v.mesa(id).mozo = mozoId;
                });
    }

    /** Los platos quedan en la mesa: despierta a los clientes para que coman. */
    public synchronized void servir(int mozoId) {
        servida = true;
        estadoMesa = EstadoMesa.COMIENDO;
        estado.notificar(Actor.mozo(mozoId), "Sirve los platos en la mesa " + id + ": ¡a comer!",
                v -> {
                    v.mesa(id).estado = EstadoMesa.COMIENDO;
                    v.mesa(id).mozo = -1;
                    liberarMozo(v, mozoId);
                    v.clientesDeMesa(id, c -> {
                        c.estado = EstadoCliente.COMIENDO;
                        c.plato = EstadoPlato.SERVIDO;
                    });
                });
        notifyAll();
    }

    public synchronized void empezarLimpieza(int mozoId, long secuenciaTarea) {
        estadoMesa = EstadoMesa.LIMPIANDO;
        estado.notificar(Actor.mozo(mozoId), "Levanta los platos y limpia la mesa " + id,
                v -> {
                    v.quitarTareaMozo(secuenciaTarea);
                    v.mozo(mozoId).poner(EstadoEmpleado.LIMPIANDO, "Limpiando la mesa " + id, id);
                    v.mesa(id).estado = EstadoMesa.LIMPIANDO;
                    v.mesa(id).mozo = mozoId;
                });
    }

    /**
     * Deja la mesa LIBRE y lista para el próximo grupo.
     *
     * <p>El {@code reset()} de la barrera se hace ANTES de entrar al monitor: la
     * acción de barrera toma el lock interno de la barrera y después el monitor
     * de la mesa, así que tomar el lock de la barrera teniendo el monitor
     * invertiría ese orden. Mientras se limpia no hay clientes en la mesa, así
     * que nadie está esperando en la barrera.
     */
    public void terminarLimpieza(int mozoId) {
        if (barrera.isBroken()) {
            barrera.reset();
        }
        synchronized (this) {
            terminarLimpiezaEnMonitor(mozoId);
        }
    }

    private void terminarLimpiezaEnMonitor(int mozoId) {
        ocupantes = 0;
        pedidoTomado = false;
        servida = false;
        cancelada = cerrando;
        Arrays.fill(elecciones, null);
        estadoMesa = EstadoMesa.LIBRE;
        estado.notificar(Actor.mozo(mozoId), "Terminó de limpiar: la mesa " + id + " queda libre",
                v -> {
                    v.mesa(id).estado = EstadoMesa.LIBRE;
                    v.mesa(id).mozo = -1;
                    liberarMozo(v, mozoId);
                });
    }

    /** El mozo terminó una tarea y vuelve a estar disponible (se llama dentro de un Cambio). */
    private static void liberarMozo(restaurante.estado.Vista v, int mozoId) {
        var m = v.mozo(mozoId);
        m.poner(EstadoEmpleado.ESPERANDO, "", -1);
        m.tareas++;
    }

    // ------------------------------------------------------------------
    // Cierre
    // ------------------------------------------------------------------

    /**
     * Cierre del restaurante. Si el pedido todavía no fue tomado, la ocupación
     * se cancela y se despierta a todos (los que eligen, los que esperan al
     * mozo); esos clientes se retiran sin pedir. Si ya fue tomado, no cambia
     * nada: los clientes siguen normalmente.
     */
    public synchronized void cerrar() {
        cerrando = true;
        if (!pedidoTomado) {
            cancelada = true;
            if (ocupantes > 0) {
                estado.notificar(Actor.mesa(id),
                        "Cierre: la mesa " + id + " todavía no pidió, sus clientes se retiran", null);
            }
        }
        notifyAll();
    }
}
