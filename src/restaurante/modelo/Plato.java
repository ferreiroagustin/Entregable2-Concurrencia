package restaurante.modelo;

/**
 * Un plato de un pedido: qué menú es y para qué cliente.
 *
 * Los campos mutables ({@code estado}, {@code cocineroId}) solo se leen y
 * escriben con el lock de {@code Cocina} tomado, por eso no necesitan ser
 * volatile: el lock da la relación happens-before entre cocineros.
 */
public final class Plato {

    private final Pedido pedido;
    private final Menu menu;
    private final int clienteId;

    // Protegidos por Cocina.lock
    private EstadoPlato estado = EstadoPlato.PENDIENTE;
    private int cocineroId = -1;

    public Plato(Pedido pedido, Menu menu, int clienteId) {
        this.pedido = pedido;
        this.menu = menu;
        this.clienteId = clienteId;
    }

    public Pedido pedido() {
        return pedido;
    }

    public Menu menu() {
        return menu;
    }

    public int clienteId() {
        return clienteId;
    }

    /** Llamar solo con el lock de la cocina tomado. */
    public EstadoPlato estado() {
        return estado;
    }

    /** Llamar solo con el lock de la cocina tomado. */
    public int cocineroId() {
        return cocineroId;
    }

    /** Llamar solo con el lock de la cocina tomado. */
    public void empezarACocinar(int cocinero) {
        this.estado = EstadoPlato.COCINANDO;
        this.cocineroId = cocinero;
    }

    /** Llamar solo con el lock de la cocina tomado. */
    public void terminar() {
        this.estado = EstadoPlato.LISTO;
    }
}
