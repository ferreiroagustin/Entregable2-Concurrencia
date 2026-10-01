package restaurante.recursos;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import restaurante.config.Config;
import restaurante.config.Parametros;
import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.Mesa;

/**
 * Fachada del restaurante: agrupa todos los recursos compartidos.
 *
 * <ul>
 * <li>{@code puerta}: {@link Semaphore} con P*M permisos (justo/FIFO). Como
 * máximo P*M clientes adentro; el resto espera afuera.</li>
 * <li>{@code abierto}: {@link AtomicBoolean}, visible para todos los hilos sin
 * locks. Cuando pasa a false, las puertas están cerradas.</li>
 * <li>Las mesas, la sala de espera, la cocina, la caja y la cola de los mozos.</li>
 * </ul>
 */
public final class Restaurante {

    private final Parametros parametros;
    private final EstadoRestaurante estado;
    private final Semaphore puerta;
    private final AtomicBoolean abierto = new AtomicBoolean(true);
    private final Contadores contadores = new Contadores();
    private final Mesa[] mesas;
    private final ColaMozos colaMozos;
    private final SalaDeEspera sala;
    private final Cocina cocina;
    private final Caja caja;

    public Restaurante(Parametros p, EstadoRestaurante estado) {
        this.parametros = p;
        this.estado = estado;
        this.puerta = new Semaphore(p.capacidad(), true);
        this.colaMozos = new ColaMozos(estado);
        this.mesas = new Mesa[p.mesas()];
        for (int i = 0; i < p.mesas(); i++) {
            mesas[i] = new Mesa(i + 1, p.personasPorMesa(), estado, colaMozos);
        }
        this.sala = new SalaDeEspera(p.personasPorMesa(), mesas, estado, abierto, contadores);
        this.cocina = new Cocina(estado);
        this.caja = new Caja(estado);
    }

    /**
     * El cliente espera afuera hasta conseguir lugar. Usa {@code tryAcquire}
     * con timeout en un bucle para poder darse cuenta de que el restaurante
     * cerró y retirarse (un {@code acquire} sin timeout lo dejaría bloqueado).
     *
     * @return true si consiguió un permiso de la puerta (hay que liberarlo al salir)
     */
    public boolean esperarParaEntrar() throws InterruptedException {
        while (abierto.get()) {
            if (puerta.tryAcquire(Config.ESPERA_PUERTA_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    /** El cliente sale del local: libera su lugar. */
    public void salir() {
        puerta.release();
    }

    /** Paso 1 del cierre: se cierran las puertas. */
    public void cerrarPuertas() {
        if (abierto.compareAndSet(true, false)) {
            estado.notificar(Actor.SISTEMA, "Se cumplió el tiempo T: se CIERRAN las puertas del restaurante",
                    v -> v.estadoGeneral = "CERRANDO");
        }
    }

    public boolean estaAbierto() {
        return abierto.get();
    }

    public int lugaresLibres() {
        return puerta.availablePermits();
    }

    public int nuevoIdCliente() {
        return contadores.idsClientes.incrementAndGet();
    }

    public int nuevoIdPedido() {
        return contadores.idsPedidos.incrementAndGet();
    }

    public Parametros parametros() {
        return parametros;
    }

    public EstadoRestaurante estado() {
        return estado;
    }

    public Contadores contadores() {
        return contadores;
    }

    public Mesa[] mesas() {
        return mesas.clone();
    }

    public ColaMozos colaMozos() {
        return colaMozos;
    }

    public SalaDeEspera sala() {
        return sala;
    }

    public Cocina cocina() {
        return cocina;
    }

    public Caja caja() {
        return caja;
    }
}
