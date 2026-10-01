package restaurante.config;

/**
 * Rango de tiempo [min, max] en milisegundos. Es inmutable (record).
 */
public record Rango(long min, long max) {

    public Rango {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Rango inválido: " + min + ".." + max);
        }
    }

    /** Devuelve un rango nuevo con ambos extremos multiplicados por {@code factor}. */
    public Rango escalar(double factor) {
        long nuevoMin = Math.max(1, Math.round(min * factor));
        long nuevoMax = Math.max(nuevoMin, Math.round(max * factor));
        return new Rango(nuevoMin, nuevoMax);
    }

    @Override
    public String toString() {
        return min + "-" + max + " ms";
    }
}
