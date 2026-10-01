package restaurante.modelo;

/**
 * Mensaje que se deposita en la cola de prioridad de los mozos.
 * Es inmutable. Se ordena por prioridad y, dentro de la misma prioridad,
 * por número de secuencia (FIFO), lo que evita la inanición entre tareas
 * del mismo tipo.
 */
public final class TareaMozo implements Comparable<TareaMozo> {

    private final TipoTarea tipo;
    private final Mesa mesa;
    private final Pedido pedido;
    private final long secuencia;

    public TareaMozo(TipoTarea tipo, Mesa mesa, Pedido pedido, long secuencia) {
        this.tipo = tipo;
        this.mesa = mesa;
        this.pedido = pedido;
        this.secuencia = secuencia;
    }

    /** Crea la "poison pill" que le indica a un mozo que termine. */
    public static TareaMozo fin(long secuencia) {
        return new TareaMozo(TipoTarea.FIN, null, null, secuencia);
    }

    public boolean esFin() {
        return tipo == TipoTarea.FIN;
    }

    public TipoTarea tipo() {
        return tipo;
    }

    public Mesa mesa() {
        return mesa;
    }

    public Pedido pedido() {
        return pedido;
    }

    public long secuencia() {
        return secuencia;
    }

    public int mesaId() {
        return mesa == null ? -1 : mesa.id();
    }

    @Override
    public int compareTo(TareaMozo otra) {
        int porPrioridad = Integer.compare(tipo.prioridad(), otra.tipo.prioridad());
        return porPrioridad != 0 ? porPrioridad : Long.compare(secuencia, otra.secuencia);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TareaMozo otra && otra.secuencia == secuencia && otra.tipo == tipo;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(secuencia) * 31 + tipo.hashCode();
    }

    @Override
    public String toString() {
        return tipo.descripcion() + (mesa == null ? "" : " (mesa " + mesa.id() + ")");
    }
}
