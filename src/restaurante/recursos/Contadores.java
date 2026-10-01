package restaurante.recursos;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contadores REALES de la simulación. Son clases atómicas: cada incremento es
 * una operación indivisible (CAS) y visible para todos los hilos, sin locks.
 * Se usan para verificar los invariantes al final.
 */
public final class Contadores {
    /** Clientes creados por el generador. */
    public final AtomicInteger generados = new AtomicInteger();
    /** Clientes que entraron al restaurante (pasaron la puerta). */
    public final AtomicInteger entraron = new AtomicInteger();
    /** Clientes que se fueron sin entrar (esperaban afuera cuando se cerró). */
    public final AtomicInteger retiradosAfuera = new AtomicInteger();
    /** Clientes que entraron pero se fueron sin hacer el pedido (por el cierre). */
    public final AtomicInteger retiradosSinPedir = new AtomicInteger();
    /** Clientes que pagaron y se fueron. */
    public final AtomicInteger pagaron = new AtomicInteger();
    /** Ids de clientes y de pedidos. */
    public final AtomicInteger idsClientes = new AtomicInteger();
    public final AtomicInteger idsPedidos = new AtomicInteger();
}
