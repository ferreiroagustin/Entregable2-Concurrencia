package restaurante.actores;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import restaurante.config.Parametros;
import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.Menu;
import restaurante.modelo.Mesa;
import restaurante.modelo.Ticket;
import restaurante.modelo.TipoTarea;
import restaurante.recursos.Restaurante;
import restaurante.recursos.SalaDeEspera;
import restaurante.simulacion.Aleatorio;

/**
 * Ciclo de vida completo de un cliente. Corre en el pool de clientes
 * ({@code newCachedThreadPool}).
 *
 * <ol>
 * <li>Llega y espera afuera hasta conseguir lugar (Semaphore de la puerta).</li>
 * <li>Entra y se agrupa de a P en la sala de espera (monitor).</li>
 * <li>El grupo consigue mesa y se sienta.</li>
 * <li>Elige el menú (TM) y espera en la barrera a sus compañeros; el último llama al mozo.</li>
 * <li>Espera al mozo y hace el pedido.</li>
 * <li>Espera la comida, come (TQ), paga en la caja (Future) y se va.</li>
 * </ol>
 * Si el restaurante cierra antes de que el mozo tome el pedido, se retira sin pedir.
 */
public final class Cliente implements Runnable {

    private final int id;
    private final Restaurante restaurante;
    private final Parametros p;
    private final EstadoRestaurante estado;

    public Cliente(int id, Restaurante restaurante) {
        this.id = id;
        this.restaurante = restaurante;
        this.p = restaurante.parametros();
        this.estado = restaurante.estado();
    }

    @Override
    public void run() {
        final long llegada = estado.tiempoMs();
        restaurante.contadores().generados.incrementAndGet();
        estado.notificar(Actor.cliente(id), "Llega al restaurante", v -> v.nuevoCliente(id, llegada));
        boolean tienePermiso = false;
        try {
            // 1. Esperar afuera
            tienePermiso = restaurante.esperarParaEntrar();
            if (!tienePermiso) {
                retirarseAfuera("Las puertas se cerraron mientras esperaba afuera: se va sin entrar");
                return;
            }
            // 2. Entrar y agruparse
            SalaDeEspera sala = restaurante.sala();
            if (!sala.ingresar(id)) {
                restaurante.salir();
                tienePermiso = false;
                retirarseAfuera("Las puertas se cerraron mientras esperaba afuera: se va sin entrar");
                return;
            }
            final long entrada = estado.tiempoMs();
            SalaDeEspera.Asignacion asignacion = sala.esperarMesa(id);
            if (asignacion == null) {
                restaurante.contadores().retiradosSinPedir.incrementAndGet();
                estado.notificar(Actor.cliente(id), "El restaurante cerró antes de que su grupo tuviera mesa: se retira",
                        v -> {
                            v.quitarCliente(id);
                            v.retiradosSinPedir++;
                        });
                return;
            }
            // 3. Sentarse
            Mesa mesa = asignacion.mesa();
            mesa.sentarse(id, asignacion.asiento());
            // 4. Elegir y esperar a los compañeros (barrera)
            final Menu menu = Aleatorio.menu();
            mesa.elegir(id, asignacion.asiento(), menu, Aleatorio.entre(p.eleccion()));
            mesa.esperarCompaneros();
            // 5. Esperar al mozo
            if (!mesa.esperarMozo()) {
                restaurante.contadores().retiradosSinPedir.incrementAndGet();
                boolean ultimo = mesa.retirarse(id, "Se retira sin pedir porque el restaurante cerró.",
                        v -> {
                            v.quitarCliente(id);
                            v.retiradosSinPedir++;
                        });
                avisarMesaSucia(mesa, ultimo);
                return;
            }
            // 6. Esperar la comida y comer
            mesa.esperarComida();
            Aleatorio.dormir(p.comida());
            // 7. Pagar (Future/Promise: el cajero completa el ticket)
            CompletableFuture<Ticket> futuro = restaurante.caja().pagar(id, menu);
            final Ticket ticket = futuro.get();
            final long estadia = estado.tiempoMs() - entrada;
            restaurante.contadores().pagaron.incrementAndGet();
            // 8. Irse
            boolean ultimo = mesa.retirarse(id, "Pagó $" + ticket.monto() + " y se retira del restaurante.",
                    v -> {
                        v.quitarCliente(id);
                        v.registrarVenta(menu, ticket.monto(), estadia);
                    });
            avisarMesaSucia(mesa, ultimo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            estado.notificar(Actor.cliente(id), "ERROR: el hilo del cliente fue interrumpido", null);
        } catch (ExecutionException e) {
            estado.notificar(Actor.cliente(id), "ERROR al pagar: " + e.getCause(), null);
        } finally {
            if (tienePermiso) {
                restaurante.salir();
            }
        }
    }

    private void retirarseAfuera(String motivo) {
        restaurante.contadores().retiradosAfuera.incrementAndGet();
        estado.notificar(Actor.cliente(id), motivo, v -> {
            v.quitarCliente(id);
            v.retiradosAfuera++;
        });
    }

    /** El último cliente que deja la mesa le avisa a los mozos que hay que limpiarla. */
    private void avisarMesaSucia(Mesa mesa, boolean ultimo) {
        if (ultimo) {
            restaurante.colaMozos().encolar(TipoTarea.LIMPIAR, mesa, null, Actor.mesa(mesa.id()),
                    "La mesa " + mesa.id() + " quedó sucia: se pide a un mozo que la limpie", null);
        }
    }
}
