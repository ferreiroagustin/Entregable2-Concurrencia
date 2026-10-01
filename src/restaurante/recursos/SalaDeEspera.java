package restaurante.recursos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.modelo.EstadoCliente;
import restaurante.modelo.Mesa;

/**
 * Sala de espera: MONITOR ({@code synchronized} + {@code wait/notifyAll}) donde
 * los clientes que entraron se agrupan de a P, en orden de llegada. Cada grupo
 * completo espera una mesa LIBRE; cuando la consigue, los P clientes van juntos
 * a la misma mesa.
 *
 * <p>La sala lleva su propia lista de mesas libres (no toca el monitor de las
 * mesas): así no hay locks anidados entre la sala y las mesas. Los mozos,
 * después de limpiar una mesa y SALIR del monitor de esa mesa, llaman a
 * {@link #mesaLiberada}.
 */
public final class SalaDeEspera {

    /** Mesa y asiento que le tocó a un cliente. */
    public record Asignacion(Mesa mesa, int asiento) {
    }

    private static final class Grupo {
        final int id;
        final List<Integer> clientes = new ArrayList<>();
        Mesa mesa;

        Grupo(int id) {
            this.id = id;
        }
    }

    private final int personasPorMesa;
    private final EstadoRestaurante estado;
    private final AtomicBoolean abierto;
    private final Contadores contadores;

    // ---- Protegido por el monitor (this) ----
    private final Deque<Mesa> mesasLibres = new ArrayDeque<>();
    private final Deque<Grupo> gruposEsperandoMesa = new ArrayDeque<>();
    private final Map<Integer, Grupo> grupoDeCliente = new HashMap<>();
    private Grupo enFormacion;
    private int idsGrupo;
    private boolean cerrada;

    public SalaDeEspera(int personasPorMesa, Mesa[] mesas, EstadoRestaurante estado, AtomicBoolean abierto,
            Contadores contadores) {
        this.personasPorMesa = personasPorMesa;
        this.estado = estado;
        this.abierto = abierto;
        this.contadores = contadores;
        for (Mesa m : mesas) {
            mesasLibres.add(m);
        }
    }

    /**
     * El cliente (que ya consiguió lugar en la puerta) entra y se suma al grupo
     * en formación. Es atómico respecto del cierre: si las puertas ya se
     * cerraron, no entra.
     *
     * @return true si entró; false si el restaurante ya cerró
     */
    public synchronized boolean ingresar(int clienteId) {
        if (cerrada || !abierto.get()) {
            return false;
        }
        contadores.entraron.incrementAndGet();
        if (enFormacion == null) {
            enFormacion = new Grupo(++idsGrupo);
        }
        final Grupo g = enFormacion;
        g.clientes.add(clienteId);
        grupoDeCliente.put(clienteId, g);
        final int n = g.clientes.size();
        final long ahora = estado.tiempoMs();
        estado.notificar(Actor.cliente(clienteId),
                "Entra al restaurante y se suma al grupo " + g.id + " (" + n + "/" + personasPorMesa + ")",
                v -> {
                    var c = v.cliente(clienteId);
                    c.estado = EstadoCliente.AGRUPANDOSE;
                    c.grupo = g.id;
                    c.entradaMs = ahora;
                    v.entraron++;
                });
        if (n == personasPorMesa) {
            enFormacion = null;
            gruposEsperandoMesa.add(g);
            final List<Integer> integrantes = List.copyOf(g.clientes);
            estado.notificar(Actor.SISTEMA, "El grupo " + g.id + " está completo " + integrantes + " y espera mesa",
                    v -> {
                        for (int id : integrantes) {
                            v.cliente(id).estado = EstadoCliente.ESPERANDO_MESA;
                        }
                    });
            asignarMesas();
            notifyAll();
        }
        return true;
    }

    /**
     * El cliente espera (wait) a que su grupo esté completo y tenga mesa.
     *
     * @return la mesa y el asiento, o null si el restaurante cerró antes
     */
    public synchronized Asignacion esperarMesa(int clienteId) throws InterruptedException {
        Grupo g = grupoDeCliente.get(clienteId);
        try {
            while (g.mesa == null && !cerrada) {
                wait();
            }
        } finally {
            grupoDeCliente.remove(clienteId);
        }
        if (g.mesa != null) {
            return new Asignacion(g.mesa, g.clientes.indexOf(clienteId));
        }
        return null;
    }

    /**
     * Asigna mesas libres a los grupos completos, en orden de llegada. Llamar con el monitor tomado.
     * Si las puertas ya se cerraron (aunque la sala todavía no) no se asignan mesas nuevas:
     * esos grupos no van a poder pedir, así que se retiran desde la sala.
     */
    private void asignarMesas() {
        while (!cerrada && abierto.get() && !gruposEsperandoMesa.isEmpty() && !mesasLibres.isEmpty()) {
            final Grupo g = gruposEsperandoMesa.poll();
            final Mesa m = mesasLibres.poll();
            g.mesa = m;
            final List<Integer> integrantes = List.copyOf(g.clientes);
            estado.notificar(Actor.SISTEMA, "El grupo " + g.id + " " + integrantes + " pasa a la mesa " + m.id(),
                    v -> {
                        for (int id : integrantes) {
                            v.cliente(id).mesa = m.id();
                        }
                    });
        }
    }

    /** Un mozo terminó de limpiar una mesa: vuelve a estar disponible. */
    public synchronized void mesaLiberada(Mesa mesa) {
        mesasLibres.add(mesa);
        asignarMesas();
        notifyAll();
    }

    /**
     * Cierre: los grupos incompletos y los que esperan mesa se retiran.
     * Los grupos que ya tienen mesa siguen hacia su mesa (y allí el cierre de
     * la mesa decide si pidieron o no).
     */
    public synchronized void cerrar() {
        cerrada = true;
        enFormacion = null;
        gruposEsperandoMesa.clear();
        estado.notificar(Actor.SISTEMA, "Se cierra la sala de espera: los grupos sin mesa se retiran", null);
        notifyAll();
    }

    /** Cantidad de mesas libres (para invariantes). */
    public synchronized int mesasLibres() {
        return mesasLibres.size();
    }

    /** Clientes que todavía esperan en la sala (para invariantes). */
    public synchronized int clientesEsperando() {
        return grupoDeCliente.size();
    }
}
