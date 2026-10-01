package restaurante.modelo;

/** Estados de una mesa. Las transiciones las controla el monitor {@link Mesa}. */
public enum EstadoMesa {
    LIBRE("Libre"),
    ELIGIENDO("Clientes eligiendo"),
    ESPERANDO_MOZO("Esperando al mozo"),
    TOMANDO_PEDIDO("Mozo tomando el pedido"),
    ESPERANDO_COMIDA("Esperando la comida"),
    SIRVIENDO("Mozo sirviendo"),
    COMIENDO("Comiendo"),
    SUCIA("Sucia"),
    LIMPIANDO("Limpiando");

    private final String descripcion;

    EstadoMesa(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }
}
