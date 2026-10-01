package restaurante.actores;

import java.util.concurrent.CountDownLatch;

import restaurante.config.Parametros;
import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.EstadoCliente;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.Pago;
import restaurante.recursos.Caja;
import restaurante.recursos.Restaurante;
import restaurante.simulacion.Aleatorio;

/**
 * Cajero: consumidor de la cola única de pago. Le cobra al primero de la cola
 * (TY), completa su Future y vuelve a esperar. Termina con la poison pill.
 */
public final class Cajero implements Runnable {

    private final int id;
    private final Caja caja;
    private final Parametros p;
    private final EstadoRestaurante estado;
    private final CountDownLatch terminados;

    public Cajero(int id, Restaurante restaurante, CountDownLatch terminados) {
        this.id = id;
        this.caja = restaurante.caja();
        this.p = restaurante.parametros();
        this.estado = restaurante.estado();
        this.terminados = terminados;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Pago pago = caja.tomar();
                if (pago.esFin()) {
                    break;
                }
                final int cliente = pago.clienteId();
                estado.notificar(Actor.cajero(id), "Atiende al cliente " + cliente,
                        v -> {
                            v.quitarDeCaja(cliente);
                            v.cliente(cliente).estado = EstadoCliente.PAGANDO;
                            v.cajero(id).poner(EstadoEmpleado.COBRANDO, "Cliente " + cliente, -1);
                        });
                Aleatorio.dormir(p.cobrar());
                estado.notificar(Actor.cajero(id),
                        "Cobra $" + pago.menu().precio() + " (" + pago.menu().nombre() + ") al cliente " + cliente,
                        v -> {
                            var c = v.cajero(id);
                            c.poner(EstadoEmpleado.ESPERANDO, "", -1);
                            c.tareas++;
                        });
                caja.cobrar(pago, id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            estado.notificar(Actor.cajero(id), "No quedan clientes por cobrar: cierra su caja",
                    v -> v.cajero(id).poner(EstadoEmpleado.TERMINADO, "", -1));
            terminados.countDown();
        }
    }
}
