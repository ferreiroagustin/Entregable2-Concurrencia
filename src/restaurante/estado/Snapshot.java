package restaurante.estado;

import java.util.List;

import restaurante.modelo.EstadoCliente;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.EstadoMesa;
import restaurante.modelo.EstadoPlato;
import restaurante.modelo.Menu;
import restaurante.modelo.TipoTarea;

/**
 * Foto INMUTABLE y completa del restaurante en el instante en que se generó un
 * evento. Todas las listas se copian con {@link List#copyOf}, y todos los
 * componentes son records o enums, así que el Display (u otro hilo) puede
 * leerla sin ningún lock y sin riesgo de ver un estado a medio modificar.
 *
 * En los campos enteros que identifican a otro actor, -1 significa "ninguno".
 */
public record Snapshot(
        long secuencia,
        long tiempoMs,
        long tiempoTotalMs,
        String estado,
        List<ClienteVista> clientes,
        List<EmpleadoVista> mozos,
        List<EmpleadoVista> cocineros,
        List<EmpleadoVista> cajeros,
        List<MesaVista> mesas,
        List<Integer> afuera,
        List<GrupoVista> sala,
        List<TareaVista> colaMozos,
        List<PedidoVista> cocina,
        List<Integer> colaCaja,
        Estadisticas stats) {

    public Snapshot {
        clientes = List.copyOf(clientes);
        mozos = List.copyOf(mozos);
        cocineros = List.copyOf(cocineros);
        cajeros = List.copyOf(cajeros);
        mesas = List.copyOf(mesas);
        afuera = List.copyOf(afuera);
        sala = List.copyOf(sala);
        colaMozos = List.copyOf(colaMozos);
        cocina = List.copyOf(cocina);
        colaCaja = List.copyOf(colaCaja);
    }

    /** Cliente presente (afuera o adentro). menu y plato pueden ser null. */
    public record ClienteVista(int id, EstadoCliente estado, int mesa, int asiento, int grupo,
            Menu menu, EstadoPlato plato, long llegadaMs) {
    }

    /** Mozo, cocinero o cajero. */
    public record EmpleadoVista(int id, String nombre, EstadoEmpleado estado, String detalle,
            int mesa, int tareas) {
    }

    /** Mesa: estado, mozo que la atiende y cliente sentado en cada asiento (-1 = vacío). */
    public record MesaVista(int id, EstadoMesa estado, int mozo, List<Integer> asientos) {
        public MesaVista {
            asientos = List.copyOf(asientos);
        }
    }

    /** Grupo que se está formando o que espera mesa en la sala de espera. */
    public record GrupoVista(int grupo, List<Integer> clientes, boolean completo) {
        public GrupoVista {
            clientes = List.copyOf(clientes);
        }
    }

    /** Tarea pendiente en la cola de los mozos. */
    public record TareaVista(long secuencia, TipoTarea tipo, int mesa) {
    }

    /** Pedido en la cocina. */
    public record PedidoVista(int id, int mesa, boolean listo, List<PlatoVista> platos) {
        public PedidoVista {
            platos = List.copyOf(platos);
        }
    }

    public record PlatoVista(int cliente, Menu menu, EstadoPlato estado, int cocinero) {
    }

    public record VentaMenu(Menu menu, int cantidad, long monto) {
    }

    public record Estadisticas(
            int generados,
            int entraron,
            int retiradosAfuera,
            int retiradosSinPedir,
            int pagaron,
            int adentro,
            int esperandoAfuera,
            long recaudacion,
            int platosPedidos,
            int platosCocinados,
            long estadiaPromedioMs,
            List<VentaMenu> porMenu) {
        public Estadisticas {
            porMenu = List.copyOf(porMenu);
        }
    }
}
