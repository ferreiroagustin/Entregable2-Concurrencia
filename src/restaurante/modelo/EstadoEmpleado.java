package restaurante.modelo;

/** Estados de mozos, cocineros y cajeros. */
public enum EstadoEmpleado {
    ESPERANDO("Esperando trabajo"),
    TOMANDO_PEDIDO("Tomando un pedido"),
    SIRVIENDO("Sirviendo platos"),
    LIMPIANDO("Limpiando una mesa"),
    COCINANDO("Cocinando"),
    COBRANDO("Cobrando"),
    TERMINADO("Terminó su turno");

    private final String descripcion;

    EstadoEmpleado(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }
}
