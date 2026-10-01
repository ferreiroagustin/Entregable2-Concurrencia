package restaurante.simulacion;

/** Resultado de verificar una condición que tiene que cumplirse al terminar la simulación. */
public record Invariante(String nombre, boolean ok, String detalle) {

    @Override
    public String toString() {
        return (ok ? "[OK]    " : "[FALLA] ") + nombre + " -> " + detalle;
    }
}
