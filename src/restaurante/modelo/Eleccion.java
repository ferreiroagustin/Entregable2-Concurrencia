package restaurante.modelo;

/** Lo que eligió un cliente de la mesa. Inmutable. */
public record Eleccion(int clienteId, int asiento, Menu menu) {
}
