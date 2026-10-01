package restaurante.modelo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pedido de una mesa: un plato por cliente. {@code platosPendientes} es atómico
 * porque varios cocineros pueden terminar platos del mismo pedido; el que lo
 * deja en cero es el único que avisa a los mozos.
 */
public final class Pedido {

    private final int id;
    private final Mesa mesa;
    private final List<Plato> platos;
    private final AtomicInteger platosPendientes;

    public Pedido(int id, Mesa mesa, List<Eleccion> elecciones) {
        this.id = id;
        this.mesa = mesa;
        List<Plato> lista = new ArrayList<>();
        for (Eleccion e : elecciones) {
            lista.add(new Plato(this, e.menu(), e.clienteId()));
        }
        this.platos = Collections.unmodifiableList(lista);
        this.platosPendientes = new AtomicInteger(lista.size());
    }

    public int id() {
        return id;
    }

    public Mesa mesa() {
        return mesa;
    }

    public List<Plato> platos() {
        return platos;
    }

    /** Marca un plato como listo y devuelve cuántos faltan. */
    public int platoListo() {
        return platosPendientes.decrementAndGet();
    }

    public int pendientes() {
        return platosPendientes.get();
    }
}
