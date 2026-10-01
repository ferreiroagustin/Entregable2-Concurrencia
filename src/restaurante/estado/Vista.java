package restaurante.estado;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import restaurante.modelo.EstadoCliente;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.EstadoMesa;
import restaurante.modelo.EstadoPlato;
import restaurante.modelo.Menu;
import restaurante.modelo.TipoTarea;

/**
 * Representación MUTABLE del estado del restaurante que se usa para mostrarlo.
 *
 * Regla de uso: una instancia de Vista solo la toca {@link EstadoRestaurante},
 * y únicamente mientras tiene tomado el write lock (dentro de
 * {@code notificar}) o el read lock (para sacar una foto). Por eso ninguna
 * de estas clases tiene sincronización propia: la da el
 * {@code ReentrantReadWriteLock} de EstadoRestaurante.
 *
 * Esta vista es independiente de los objetos de sincronización reales (mesas,
 * cocina, caja). Así el Display nunca necesita tomar el monitor de una mesa ni
 * el lock de la cocina para mostrar algo: no hay anidamiento de locks.
 */
public final class Vista {

    public static final class ClienteV {
        public final int id;
        public EstadoCliente estado = EstadoCliente.ESPERANDO_AFUERA;
        public int mesa = -1;
        public int asiento = -1;
        public int grupo = -1;
        public Menu menu;
        public EstadoPlato plato;
        public final long llegadaMs;
        public long entradaMs = -1;

        ClienteV(int id, long llegadaMs) {
            this.id = id;
            this.llegadaMs = llegadaMs;
        }
    }

    public static final class EmpleadoV {
        public final int id;
        public final String nombre;
        public EstadoEmpleado estado = EstadoEmpleado.ESPERANDO;
        public String detalle = "";
        public int mesa = -1;
        public int tareas;

        EmpleadoV(int id, String nombre) {
            this.id = id;
            this.nombre = nombre;
        }

        /** Atajo para cambiar estado, detalle y mesa de una vez. */
        public void poner(EstadoEmpleado nuevoEstado, String nuevoDetalle, int nuevaMesa) {
            this.estado = nuevoEstado;
            this.detalle = nuevoDetalle;
            this.mesa = nuevaMesa;
        }
    }

    public static final class MesaV {
        public final int id;
        public EstadoMesa estado = EstadoMesa.LIBRE;
        public int mozo = -1;

        MesaV(int id) {
            this.id = id;
        }
    }

    public static final class PlatoV {
        public final int cliente;
        public final Menu menu;
        public EstadoPlato estado = EstadoPlato.PENDIENTE;
        public int cocinero = -1;

        PlatoV(int cliente, Menu menu) {
            this.cliente = cliente;
            this.menu = menu;
        }
    }

    public static final class PedidoV {
        public final int id;
        public final int mesa;
        public final List<PlatoV> platos = new ArrayList<>();
        public boolean listo;

        PedidoV(int id, int mesa) {
            this.id = id;
            this.mesa = mesa;
        }

        public PlatoV platoDe(int clienteId) {
            for (PlatoV p : platos) {
                if (p.cliente == clienteId) {
                    return p;
                }
            }
            throw new IllegalStateException("El pedido " + id + " no tiene plato del cliente " + clienteId);
        }
    }

    private record TareaV(long secuencia, TipoTarea tipo, int mesa) {
    }

    private final int personasPorMesa;
    private final TreeMap<Integer, ClienteV> clientes = new TreeMap<>();
    private final EmpleadoV[] mozos;
    private final EmpleadoV[] cocineros;
    private final EmpleadoV[] cajeros;
    private final MesaV[] mesas;
    private final List<TareaV> colaMozos = new ArrayList<>();
    private final List<PedidoV> cocina = new ArrayList<>();
    private final List<Integer> colaCaja = new ArrayList<>();
    private final Map<Menu, long[]> ventasPorMenu = new EnumMap<>(Menu.class);

    // Estado general y contadores (solo para mostrar; los contadores "reales"
    // son atómicos y viven en los recursos).
    public String estadoGeneral = "SIN_INICIAR";
    public int generados;
    public int entraron;
    public int retiradosAfuera;
    public int retiradosSinPedir;
    public int pagaron;
    public int platosPedidos;
    public int platosCocinados;
    public long recaudacion;
    public long sumaEstadiaMs;

    Vista(int cantMesas, int personasPorMesa, int cantMozos, int cantCocineros, int cantCajeros) {
        this.personasPorMesa = personasPorMesa;
        this.mozos = crearEmpleados(cantMozos, "Mozo");
        this.cocineros = crearEmpleados(cantCocineros, "Cocinero");
        this.cajeros = crearEmpleados(cantCajeros, "Cajero");
        this.mesas = new MesaV[cantMesas];
        for (int i = 0; i < cantMesas; i++) {
            mesas[i] = new MesaV(i + 1);
        }
        for (Menu m : Menu.values()) {
            ventasPorMenu.put(m, new long[2]);
        }
    }

    private static EmpleadoV[] crearEmpleados(int cantidad, String rol) {
        EmpleadoV[] v = new EmpleadoV[cantidad];
        for (int i = 0; i < cantidad; i++) {
            v[i] = new EmpleadoV(i + 1, rol + " " + (i + 1));
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Clientes
    // ------------------------------------------------------------------

    public ClienteV nuevoCliente(int id, long tiempoMs) {
        ClienteV c = new ClienteV(id, tiempoMs);
        clientes.put(id, c);
        generados++;
        return c;
    }

    public ClienteV cliente(int id) {
        ClienteV c = clientes.get(id);
        if (c == null) {
            throw new IllegalStateException("Cliente " + id + " no está en la vista");
        }
        return c;
    }

    public void quitarCliente(int id) {
        clientes.remove(id);
    }

    /** Aplica una acción a cada cliente sentado (o asignado) a la mesa indicada. */
    public void clientesDeMesa(int mesaId, Consumer<ClienteV> accion) {
        for (ClienteV c : clientes.values()) {
            if (c.mesa == mesaId) {
                accion.accept(c);
            }
        }
    }

    // ------------------------------------------------------------------
    // Empleados y mesas
    // ------------------------------------------------------------------

    public EmpleadoV mozo(int id) {
        return mozos[id - 1];
    }

    public EmpleadoV cocinero(int id) {
        return cocineros[id - 1];
    }

    public EmpleadoV cajero(int id) {
        return cajeros[id - 1];
    }

    public MesaV mesa(int id) {
        return mesas[id - 1];
    }

    // ------------------------------------------------------------------
    // Colas
    // ------------------------------------------------------------------

    public void encolarTareaMozo(long secuencia, TipoTarea tipo, int mesa) {
        colaMozos.add(new TareaV(secuencia, tipo, mesa));
    }

    public void quitarTareaMozo(long secuencia) {
        colaMozos.removeIf(t -> t.secuencia() == secuencia);
    }

    public PedidoV agregarPedido(int id, int mesa) {
        PedidoV p = new PedidoV(id, mesa);
        cocina.add(p);
        return p;
    }

    public PlatoV agregarPlato(PedidoV pedido, int cliente, Menu menu) {
        PlatoV p = new PlatoV(cliente, menu);
        pedido.platos.add(p);
        return p;
    }

    public PedidoV pedido(int id) {
        for (PedidoV p : cocina) {
            if (p.id == id) {
                return p;
            }
        }
        throw new IllegalStateException("Pedido " + id + " no está en la cocina");
    }

    public void quitarPedido(int id) {
        for (Iterator<PedidoV> it = cocina.iterator(); it.hasNext();) {
            if (it.next().id == id) {
                it.remove();
            }
        }
    }

    public void encolarCaja(int clienteId) {
        colaCaja.add(clienteId);
    }

    public void quitarDeCaja(int clienteId) {
        colaCaja.remove(Integer.valueOf(clienteId));
    }

    public void registrarVenta(Menu menu, long monto, long estadiaMs) {
        long[] v = ventasPorMenu.get(menu);
        v[0]++;
        v[1] += monto;
        recaudacion += monto;
        pagaron++;
        sumaEstadiaMs += estadiaMs;
    }

    // ------------------------------------------------------------------
    // Foto inmutable
    // ------------------------------------------------------------------

    Snapshot foto(long secuencia, long tiempoMs, long tiempoTotalMs) {
        List<Snapshot.ClienteVista> listaClientes = new ArrayList<>(clientes.size());
        List<Integer> afuera = new ArrayList<>();
        TreeMap<Integer, List<Integer>> grupos = new TreeMap<>();
        TreeMap<Integer, Boolean> gruposCompletos = new TreeMap<>();
        List<List<Integer>> asientos = new ArrayList<>(mesas.length);
        for (int i = 0; i < mesas.length; i++) {
            List<Integer> a = new ArrayList<>(personasPorMesa);
            for (int j = 0; j < personasPorMesa; j++) {
                a.add(-1);
            }
            asientos.add(a);
        }
        for (ClienteV c : clientes.values()) {
            listaClientes.add(new Snapshot.ClienteVista(c.id, c.estado, c.mesa, c.asiento, c.grupo,
                    c.menu, c.plato, c.llegadaMs));
            if (c.estado == EstadoCliente.ESPERANDO_AFUERA) {
                afuera.add(c.id);
            } else if (c.estado == EstadoCliente.AGRUPANDOSE || c.estado == EstadoCliente.ESPERANDO_MESA) {
                grupos.computeIfAbsent(c.grupo, g -> new ArrayList<>()).add(c.id);
                gruposCompletos.put(c.grupo, c.estado == EstadoCliente.ESPERANDO_MESA);
            }
            if (c.mesa > 0 && c.asiento >= 0) {
                asientos.get(c.mesa - 1).set(c.asiento, c.id);
            }
        }
        List<Snapshot.GrupoVista> sala = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : grupos.entrySet()) {
            sala.add(new Snapshot.GrupoVista(e.getKey(), e.getValue(), gruposCompletos.get(e.getKey())));
        }
        List<Snapshot.MesaVista> listaMesas = new ArrayList<>(mesas.length);
        for (MesaV m : mesas) {
            listaMesas.add(new Snapshot.MesaVista(m.id, m.estado, m.mozo, asientos.get(m.id - 1)));
        }
        List<Snapshot.TareaVista> tareas = new ArrayList<>(colaMozos.size());
        List<TareaV> ordenadas = new ArrayList<>(colaMozos);
        ordenadas.sort((a, b) -> a.tipo().prioridad() != b.tipo().prioridad()
                ? Integer.compare(a.tipo().prioridad(), b.tipo().prioridad())
                : Long.compare(a.secuencia(), b.secuencia()));
        for (TareaV t : ordenadas) {
            tareas.add(new Snapshot.TareaVista(t.secuencia(), t.tipo(), t.mesa()));
        }
        List<Snapshot.PedidoVista> pedidos = new ArrayList<>(cocina.size());
        for (PedidoV p : cocina) {
            List<Snapshot.PlatoVista> platos = new ArrayList<>(p.platos.size());
            for (PlatoV pl : p.platos) {
                platos.add(new Snapshot.PlatoVista(pl.cliente, pl.menu, pl.estado, pl.cocinero));
            }
            pedidos.add(new Snapshot.PedidoVista(p.id, p.mesa, p.listo, platos));
        }
        List<Snapshot.VentaMenu> ventas = new ArrayList<>();
        for (Map.Entry<Menu, long[]> e : ventasPorMenu.entrySet()) {
            ventas.add(new Snapshot.VentaMenu(e.getKey(), (int) e.getValue()[0], e.getValue()[1]));
        }
        int adentro = clientes.size() - afuera.size();
        Snapshot.Estadisticas stats = new Snapshot.Estadisticas(generados, entraron, retiradosAfuera,
                retiradosSinPedir, pagaron, adentro, afuera.size(), recaudacion, platosPedidos,
                platosCocinados, pagaron == 0 ? 0 : sumaEstadiaMs / pagaron, ventas);
        return new Snapshot(secuencia, tiempoMs, tiempoTotalMs, estadoGeneral, listaClientes,
                fotoEmpleados(mozos), fotoEmpleados(cocineros), fotoEmpleados(cajeros), listaMesas,
                afuera, sala, tareas, pedidos, colaCaja, stats);
    }

    private static List<Snapshot.EmpleadoVista> fotoEmpleados(EmpleadoV[] empleados) {
        List<Snapshot.EmpleadoVista> l = new ArrayList<>(empleados.length);
        for (EmpleadoV e : empleados) {
            l.add(new Snapshot.EmpleadoVista(e.id, e.nombre, e.estado, e.detalle, e.mesa, e.tareas));
        }
        return l;
    }
}
