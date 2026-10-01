package restaurante.modelo;

/**
 * Tipos de tarea que puede recibir un mozo. El número de prioridad más chico se
 * atiende primero: servir platos que ya están listos (se enfrían) es lo más
 * urgente, después tomar pedidos y por último limpiar. FIN es la "poison pill"
 * y tiene la prioridad más baja para que los mozos terminen todo lo pendiente
 * antes de irse.
 */
public enum TipoTarea {
    SERVIR(0, "Servir platos"),
    TOMAR_PEDIDO(1, "Tomar pedido"),
    LIMPIAR(2, "Limpiar mesa"),
    FIN(3, "Fin del turno");

    private final int prioridad;
    private final String descripcion;

    TipoTarea(int prioridad, String descripcion) {
        this.prioridad = prioridad;
        this.descripcion = descripcion;
    }

    public int prioridad() {
        return prioridad;
    }

    public String descripcion() {
        return descripcion;
    }
}
