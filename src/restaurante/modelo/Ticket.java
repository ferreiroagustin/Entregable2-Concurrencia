package restaurante.modelo;

/** Comprobante de pago que el cajero le entrega al cliente (resultado del Future). */
public record Ticket(int clienteId, Menu menu, long monto, int cajeroId) {
}
