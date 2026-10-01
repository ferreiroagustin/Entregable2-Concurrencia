package restaurante.estado;

/** Quién generó un evento. Inmutable. */
public record Actor(TipoActor tipo, int id) {

    public enum TipoActor { SISTEMA, CLIENTE, MOZO, COCINERO, CAJERO, MESA }

    public static final Actor SISTEMA = new Actor(TipoActor.SISTEMA, 0);

    public static Actor cliente(int id) {
        return new Actor(TipoActor.CLIENTE, id);
    }

    public static Actor mozo(int id) {
        return new Actor(TipoActor.MOZO, id);
    }

    public static Actor cocinero(int id) {
        return new Actor(TipoActor.COCINERO, id);
    }

    public static Actor cajero(int id) {
        return new Actor(TipoActor.CAJERO, id);
    }

    public static Actor mesa(int id) {
        return new Actor(TipoActor.MESA, id);
    }

    public String nombre() {
        return switch (tipo) {
            case SISTEMA -> "Restaurante";
            case CLIENTE -> "Cliente " + id;
            case MOZO -> "Mozo " + id;
            case COCINERO -> "Cocinero " + id;
            case CAJERO -> "Cajero " + id;
            case MESA -> "Mesa " + id;
        };
    }
}
