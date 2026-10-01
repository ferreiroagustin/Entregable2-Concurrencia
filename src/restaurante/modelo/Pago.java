package restaurante.modelo;

import java.util.concurrent.CompletableFuture;

/**
 * Mensaje que un cliente deja en la cola única de la caja. El cajero completa
 * el {@code futuro} cuando termina de cobrar (patrón Future/Promise).
 * El pago con {@code fin = true} es la "poison pill" de los cajeros.
 */
public record Pago(int clienteId, Menu menu, CompletableFuture<Ticket> futuro, boolean fin) {

    public static Pago de(int clienteId, Menu menu) {
        return new Pago(clienteId, menu, new CompletableFuture<>(), false);
    }

    public static Pago finDeTurno() {
        return new Pago(-1, null, null, true);
    }

    public boolean esFin() {
        return fin;
    }
}
