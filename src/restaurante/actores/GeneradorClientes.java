package restaurante.actores;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import restaurante.recursos.Restaurante;
import restaurante.simulacion.Aleatorio;

/**
 * Crea un cliente nuevo cada TPmin..TPmax mientras el restaurante esté
 * abierto y lo envía al pool de clientes.
 *
 * <p>En lugar de {@code sleep} espera sobre el {@link CountDownLatch} de
 * cierre con timeout: si llega la señal de cierre, se despierta enseguida y
 * deja de generar clientes.
 */
public final class GeneradorClientes implements Runnable {

    private final Restaurante restaurante;
    private final ExecutorService poolClientes;
    private final CountDownLatch senalCierre;

    public GeneradorClientes(Restaurante restaurante, ExecutorService poolClientes, CountDownLatch senalCierre) {
        this.restaurante = restaurante;
        this.poolClientes = poolClientes;
        this.senalCierre = senalCierre;
    }

    @Override
    public void run() {
        try {
            while (restaurante.estaAbierto()) {
                poolClientes.execute(new Cliente(restaurante.nuevoIdCliente(), restaurante));
                long espera = Aleatorio.entre(restaurante.parametros().llegada());
                if (senalCierre.await(espera, TimeUnit.MILLISECONDS)) {
                    break; // se cumplió T
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
            // El pool de clientes ya no acepta tareas: el restaurante está cerrando.
        }
    }
}
