package restaurante.actores;

import java.util.concurrent.CountDownLatch;

import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.Pedido;
import restaurante.modelo.Plato;
import restaurante.modelo.TipoTarea;
import restaurante.recursos.Cocina;
import restaurante.recursos.Restaurante;
import restaurante.simulacion.Aleatorio;

/**
 * Cocinero: toma de la cocina un plato sin cocinar del primer pedido de la
 * cola, lo cocina durante el TC propio de ese menú y lo deja listo. Si con ese
 * plato el pedido quedó completo, avisa a los mozos (encola SERVIR).
 * Termina cuando la cocina está cerrada y no queda nada por cocinar.
 */
public final class Cocinero implements Runnable {

    private final int id;
    private final Restaurante restaurante;
    private final Cocina cocina;
    private final EstadoRestaurante estado;
    private final double escala;
    private final CountDownLatch terminados;

    public Cocinero(int id, Restaurante restaurante, CountDownLatch terminados) {
        this.id = id;
        this.restaurante = restaurante;
        this.cocina = restaurante.cocina();
        this.estado = restaurante.estado();
        this.escala = restaurante.parametros().escalaCocina();
        this.terminados = terminados;
    }

    @Override
    public void run() {
        try {
            Plato plato;
            while ((plato = cocina.tomarPlato(id)) != null) {
                Aleatorio.dormir(plato.menu().coccion().escalar(escala));
                if (cocina.platoListo(plato, id)) {
                    Pedido pedido = plato.pedido();
                    restaurante.colaMozos().encolar(TipoTarea.SERVIR, pedido.mesa(), pedido, Actor.cocinero(id),
                            "Pedido #" + pedido.id() + " de la mesa " + pedido.mesa().id()
                                    + " completo: avisa a los mozos que lo pueden retirar",
                            null);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            estado.notificar(Actor.cocinero(id), "La cocina cerró y no quedan pedidos: termina su turno",
                    v -> v.cocinero(id).poner(EstadoEmpleado.TERMINADO, "", -1));
            terminados.countDown();
        }
    }
}
