package restaurante.recursos;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.EstadoCliente;
import restaurante.modelo.Menu;
import restaurante.modelo.Pago;
import restaurante.modelo.Ticket;

/**
 * Caja: UNA SOLA cola de pago ({@link LinkedBlockingQueue}, FIFO) compartida
 * por los Y cajeros. Productores: los clientes. Consumidores: los cajeros.
 * El cliente recibe un {@link CompletableFuture} que el cajero completa
 * cuando termina de cobrar (patrón Future/Promise).
 */
public final class Caja {

    private final BlockingQueue<Pago> cola = new LinkedBlockingQueue<>();
    private final AtomicLong recaudacion = new AtomicLong();
    private final EstadoRestaurante estado;

    public Caja(EstadoRestaurante estado) {
        this.estado = estado;
    }

    /** El cliente se pone en la cola para pagar. */
    public CompletableFuture<Ticket> pagar(int clienteId, Menu menu) {
        Pago pago = Pago.de(clienteId, menu);
        estado.notificar(Actor.cliente(clienteId), "Terminó de comer y hace la cola para pagar",
                v -> {
                    v.cliente(clienteId).estado = EstadoCliente.EN_COLA_CAJA;
                    v.encolarCaja(clienteId);
                });
        cola.add(pago);
        return pago.futuro();
    }

    /** El cajero espera al primero de la cola. */
    public Pago tomar() throws InterruptedException {
        return cola.take();
    }

    /** Registra el cobro y completa el Future del cliente. */
    public Ticket cobrar(Pago pago, int cajeroId) {
        long monto = pago.menu().precio();
        recaudacion.addAndGet(monto);
        Ticket ticket = new Ticket(pago.clienteId(), pago.menu(), monto, cajeroId);
        pago.futuro().complete(ticket);
        return ticket;
    }

    /** Una poison pill por cajero. */
    public void cerrar(int cantidadCajeros) {
        for (int i = 0; i < cantidadCajeros; i++) {
            cola.add(Pago.finDeTurno());
        }
    }

    public long recaudacion() {
        return recaudacion.get();
    }

    public boolean colaVacia() {
        return cola.isEmpty();
    }
}
