package restaurante.actores;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import restaurante.config.Parametros;
import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.Eleccion;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.Mesa;
import restaurante.modelo.Pedido;
import restaurante.modelo.TareaMozo;
import restaurante.recursos.ColaMozos;
import restaurante.recursos.Restaurante;
import restaurante.simulacion.Aleatorio;

/**
 * Mozo: consumidor de la cola de prioridad {@link ColaMozos}. No tiene mesas
 * asignadas: atiende la tarea más prioritaria disponible (SERVIR &gt;
 * TOMAR_PEDIDO &gt; LIMPIAR) y, si no hay ninguna, se queda bloqueado en
 * {@code take()}. Termina al recibir la poison pill FIN.
 */
public final class Mozo implements Runnable {

    private final int id;
    private final Restaurante restaurante;
    private final Parametros p;
    private final EstadoRestaurante estado;
    private final ColaMozos cola;
    private final CountDownLatch terminados;

    public Mozo(int id, Restaurante restaurante, CountDownLatch terminados) {
        this.id = id;
        this.restaurante = restaurante;
        this.p = restaurante.parametros();
        this.estado = restaurante.estado();
        this.cola = restaurante.colaMozos();
        this.terminados = terminados;
    }

    @Override
    public void run() {
        try {
            while (true) {
                TareaMozo tarea = cola.tomar();
                if (tarea.esFin()) {
                    break;
                }
                switch (tarea.tipo()) {
                    case TOMAR_PEDIDO -> tomarPedido(tarea);
                    case SERVIR -> servir(tarea);
                    case LIMPIAR -> limpiar(tarea);
                    default -> throw new IllegalStateException("Tarea desconocida: " + tarea);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            estado.notificar(Actor.mozo(id), "No quedan clientes: termina su turno",
                    v -> v.mozo(id).poner(EstadoEmpleado.TERMINADO, "", -1));
            terminados.countDown();
        }
    }

    private void tomarPedido(TareaMozo tarea) throws InterruptedException {
        Mesa mesa = tarea.mesa();
        List<Eleccion> elecciones = mesa.tomarPedido(id, tarea.secuencia());
        if (elecciones == null) {
            // El cierre ganó la carrera: los clientes se fueron sin pedir.
            estado.notificar(Actor.mozo(id),
                    "Iba a la mesa " + mesa.id() + " pero sus clientes se retiraron por el cierre: descarta la tarea",
                    v -> {
                        v.quitarTareaMozo(tarea.secuencia());
                        v.mozo(id).poner(EstadoEmpleado.ESPERANDO, "", -1);
                    });
            return;
        }
        Aleatorio.dormir(p.anotar());
        Pedido pedido = new Pedido(restaurante.nuevoIdPedido(), mesa, elecciones);
        // Primero se actualiza la mesa y DESPUÉS se entrega a la cocina: así un
        // pedido muy rápido nunca puede servirse antes de que la mesa sepa que lo pidió.
        mesa.pedidoEnCocina(id, pedido.id());
        restaurante.cocina().recibirPedido(pedido, id);
    }

    private void servir(TareaMozo tarea) throws InterruptedException {
        Mesa mesa = tarea.mesa();
        mesa.empezarAServir(id, tarea.secuencia(), tarea.pedido().id());
        Aleatorio.dormir(p.servir());
        mesa.servir(id);
    }

    private void limpiar(TareaMozo tarea) throws InterruptedException {
        Mesa mesa = tarea.mesa();
        mesa.empezarLimpieza(id, tarea.secuencia());
        Aleatorio.dormir(p.limpiar());
        mesa.terminarLimpieza(id);
        // Se avisa a la sala DESPUÉS de soltar el monitor de la mesa (sin locks anidados).
        restaurante.sala().mesaLiberada(mesa);
    }
}
