package restaurante.modelo;

/** Estados de un plato dentro de un pedido. */
public enum EstadoPlato {
    PENDIENTE("Sin cocinar"),
    COCINANDO("En el fuego"),
    LISTO("Listo para retirar"),
    SERVIDO("Servido");

    private final String descripcion;

    EstadoPlato(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }
}
