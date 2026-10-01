package restaurante.modelo;

/** Estados por los que pasa un cliente durante su visita. */
public enum EstadoCliente {
    ESPERANDO_AFUERA("Esperando afuera"),
    AGRUPANDOSE("Agrupándose en la sala de espera"),
    ESPERANDO_MESA("Grupo completo, esperando mesa libre"),
    ELIGIENDO("Eligiendo el menú"),
    ESPERANDO_COMPANEROS("Ya eligió, espera al resto de la mesa"),
    ESPERANDO_MOZO("Esperando al mozo"),
    PIDIENDO("Haciendo el pedido"),
    ESPERANDO_COMIDA("Esperando la comida"),
    COMIENDO("Comiendo"),
    EN_COLA_CAJA("En la cola de la caja"),
    PAGANDO("Pagando");

    private final String descripcion;

    EstadoCliente(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }
}
