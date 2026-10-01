package restaurante.estado;

/**
 * Notificación de un cambio. Lleva el {@link Snapshot} inmutable del momento en
 * que se notificó. Un evento con secuencia negativa es la "poison pill" del
 * Display.
 */
public record Evento(long secuencia, long tiempoMs, Actor actor, String descripcion, Snapshot snapshot) {

    /** Poison pill: le indica al Display que no hay más eventos. */
    public static final Evento FIN = new Evento(-1, 0, Actor.SISTEMA, "FIN", null);

    public boolean esFin() {
        return secuencia < 0;
    }
}
