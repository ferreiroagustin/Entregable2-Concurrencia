package restaurante.simulacion;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import restaurante.actores.Cajero;
import restaurante.actores.Cocinero;
import restaurante.actores.GeneradorClientes;
import restaurante.actores.Mozo;
import restaurante.config.Config;
import restaurante.config.Parametros;
import restaurante.display.Display;
import restaurante.display.Json;
import restaurante.display.LogArchivo;
import restaurante.estado.Actor;
import restaurante.estado.EstadoRestaurante;
import restaurante.estado.Snapshot;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.EstadoMesa;
import restaurante.modelo.Mesa;
import restaurante.recursos.Contadores;
import restaurante.recursos.Restaurante;
import restaurante.web.CanalSSE;

/**
 * Coordinador de UNA simulación: crea los pools de hilos (Executors), arranca a
 * todos los actores, espera el tiempo T y ejecuta el cierre ordenado:
 *
 * <ol>
 * <li>Se cierran las puertas ({@code abierto = false}).</li>
 * <li>Se retiran los que esperan afuera, los grupos de la sala y las mesas que
 * todavía no pidieron.</li>
 * <li>Los que ya pidieron terminan normalmente (se espera al pool de clientes).</li>
 * <li>Poison pills a mozos y cajeros, {@code cerrar()} + {@code signalAll} a la
 * cocina, y se espera con un {@link CountDownLatch} a que terminen todos los
 * empleados.</li>
 * <li>Se apagan todos los pools.</li>
 * <li>Poison pill al Display, que vacía su cola y termina.</li>
 * <li>Verificación de invariantes y cierre del log.</li>
 * </ol>
 */
public final class Simulacion {

    private final Parametros p;
    private final Display.Modo modo;
    private final CanalSSE canal;
    private final PrintStream salida;
    private final EstadoRestaurante estado;
    private final Restaurante restaurante;

    /** Se libera cuando se cumple T (lo dispara el temporizador). */
    private final CountDownLatch senalCierre = new CountDownLatch(1);
    /** Barrera de finalización: cada mozo, cocinero y cajero hace countDown al terminar. */
    private final CountDownLatch empleadosTerminados;
    private final AtomicBoolean iniciada = new AtomicBoolean();
    private volatile Resultado resultado;

    // Solo los usa el hilo coordinador.
    private final Map<String, ExecutorService> pools = new LinkedHashMap<>();
    private final List<FabricaHilos> fabricas = new ArrayList<>();
    private LogArchivo log;
    private boolean interrumpido;

    public Simulacion(Parametros p, Display.Modo modo, CanalSSE canal, PrintStream salida) {
        this.p = p;
        this.modo = modo;
        this.canal = canal;
        this.salida = salida;
        this.estado = new EstadoRestaurante(p.mesas(), p.personasPorMesa(), p.mozos(), p.cocineros(), p.cajeros(),
                p.t());
        this.restaurante = new Restaurante(p, estado);
        this.empleadosTerminados = new CountDownLatch(p.mozos() + p.cocineros() + p.cajeros());
    }

    /** Ejecuta la simulación en el hilo actual y devuelve el resultado. Solo se puede llamar una vez. */
    public Resultado ejecutar() {
        if (!iniciada.compareAndSet(false, true)) {
            throw new IllegalStateException("La simulación ya fue iniciada");
        }
        return correr();
    }

    /**
     * Arranca la simulación en un hilo coordinador nuevo (lo usa el servidor web).
     *
     * @return false si ya se había iniciado
     */
    public boolean iniciarEnSegundoPlano() {
        if (!iniciada.compareAndSet(false, true)) {
            return false;
        }
        Thread coordinador = new Thread(() -> {
            try {
                correr();
            } catch (RuntimeException e) {
                System.err.println("Error en el coordinador de la simulación: " + e);
                e.printStackTrace();
            }
        }, "coordinador");
        coordinador.start();
        return true;
    }

    public boolean iniciada() {
        return iniciada.get();
    }

    public Resultado resultado() {
        return resultado;
    }

    public EstadoRestaurante estado() {
        return estado;
    }

    public Parametros parametros() {
        return p;
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    private Resultado correr() {
        long inicioNanos = System.nanoTime();
        try {
            log = LogArchivo.crear(Config.CARPETA_LOGS);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el archivo de log", e);
        }
        log.escribir("==================================================================");
        log.escribir(" SIMULACIÓN DEL RESTAURANTE - " + LocalDateTime.now());
        log.escribir(" Parámetros: " + p.resumen());
        log.escribir("==================================================================");

        Thread.UncaughtExceptionHandler manejador = (hilo, ex) -> {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            String msg = "EXCEPCIÓN NO CAPTURADA en el hilo " + hilo.getName() + ": " + sw;
            System.err.println(msg);
            log.escribir(msg);
        };

        // Display primero, para no perder ningún evento.
        ExecutorService poolDisplay = Executors.newSingleThreadExecutor(fabrica("display", manejador));
        pools.put("display", poolDisplay);
        Display display = new Display(estado.colaEventos(), log, canal, modo, salida);
        poolDisplay.execute(display);

        estado.iniciarReloj();
        estado.notificar(Actor.SISTEMA, "Se ABRE el restaurante: " + p.resumen(), v -> v.estadoGeneral = "ABIERTO");

        ExecutorService poolMozos = Executors.newFixedThreadPool(p.mozos(), fabrica("mozo", manejador));
        ExecutorService poolCocineros = Executors.newFixedThreadPool(p.cocineros(), fabrica("cocinero", manejador));
        ExecutorService poolCajeros = Executors.newFixedThreadPool(p.cajeros(), fabrica("cajero", manejador));
        ExecutorService poolClientes = Executors.newCachedThreadPool(fabrica("cliente", manejador));
        ExecutorService poolGenerador = Executors.newSingleThreadExecutor(fabrica("generador", manejador));
        ScheduledExecutorService temporizador = Executors.newScheduledThreadPool(1,
                fabrica("temporizador", manejador));
        pools.put("mozos", poolMozos);
        pools.put("cocineros", poolCocineros);
        pools.put("cajeros", poolCajeros);
        pools.put("clientes", poolClientes);
        pools.put("generador", poolGenerador);
        pools.put("temporizador", temporizador);

        for (int i = 1; i <= p.mozos(); i++) {
            poolMozos.execute(new Mozo(i, restaurante, empleadosTerminados));
        }
        for (int i = 1; i <= p.cocineros(); i++) {
            poolCocineros.execute(new Cocinero(i, restaurante, empleadosTerminados));
        }
        for (int i = 1; i <= p.cajeros(); i++) {
            poolCajeros.execute(new Cajero(i, restaurante, empleadosTerminados));
        }
        poolGenerador.execute(new GeneradorClientes(restaurante, poolClientes, senalCierre));
        temporizador.schedule(senalCierre::countDown, p.t(), TimeUnit.MILLISECONDS);

        // ---- Esperar el tiempo T ----
        try {
            senalCierre.await();
        } catch (InterruptedException e) {
            interrumpido = true;
            log.escribir("El coordinador fue interrumpido: se adelanta el cierre");
            senalCierre.countDown();
        }

        // ---- CIERRE ORDENADO ----
        // 1. Se cierran las puertas.
        restaurante.cerrarPuertas();
        apagar("generador", poolGenerador);
        // 2. Se retiran los de la sala de espera y las mesas que no pidieron
        //    (los de afuera salen solos de su bucle tryAcquire al ver abierto = false).
        restaurante.sala().cerrar();
        for (Mesa m : restaurante.mesas()) {
            m.cerrar();
        }
        // 3. Los que ya pidieron terminan normalmente.
        apagar("clientes", poolClientes);
        estado.notificar(Actor.SISTEMA, "Ya no quedan clientes en el restaurante: el personal termina su turno", null);
        // 4. Mozos, cocineros y cajeros terminan (poison pills / cierre + signalAll).
        restaurante.colaMozos().encolarFin(p.mozos());
        restaurante.cocina().cerrar();
        restaurante.caja().cerrar(p.cajeros());
        esperarEmpleados();
        // 5. Se apagan todos los pools.
        apagar("mozos", poolMozos);
        apagar("cocineros", poolCocineros);
        apagar("cajeros", poolCajeros);
        temporizador.shutdownNow();
        apagar("temporizador", temporizador);
        estado.notificar(Actor.SISTEMA, "Restaurante CERRADO: terminaron todos los hilos", v -> v.estadoGeneral = "CERRADO");
        // 6. El Display vacía su cola y termina.
        estado.finalizarDisplay();
        apagar("display", poolDisplay);
        for (FabricaHilos f : fabricas) {
            try {
                f.esperarHilos(5_000);
            } catch (InterruptedException e) {
                interrumpido = true;
            }
        }

        // Verificación de invariantes.
        Snapshot fin = estado.snapshotActual();
        List<Invariante> invariantes = verificarInvariantes(fin, display);
        long duracion = (System.nanoTime() - inicioNanos) / 1_000_000;
        Resultado r = new Resultado(p, duracion, fin, invariantes, log.ruta().toString(), display.procesados());
        String informe = informe(r);
        log.escribir(informe);
        if (modo != Display.Modo.SILENCIOSO) {
            salida.println(informe);
        }
        resultado = r;
        if (canal != null) {
            canal.publicarFin(Json.resumen(r));
        }
        // 7. Se cierra el log.
        log.cerrar();
        if (interrumpido) {
            Thread.currentThread().interrupt();
        }
        return r;
    }

    private FabricaHilos fabrica(String prefijo, Thread.UncaughtExceptionHandler manejador) {
        FabricaHilos f = new FabricaHilos(prefijo, false, manejador);
        fabricas.add(f);
        return f;
    }

    /** shutdown + awaitTermination con tiempo máximo; si no termina, se informa y se fuerza. */
    private void apagar(String nombre, ExecutorService pool) {
        pool.shutdown();
        long limite = System.currentTimeMillis() + Config.TIMEOUT_CIERRE_MS;
        while (true) {
            long resta = limite - System.currentTimeMillis();
            try {
                if (pool.awaitTermination(Math.max(resta, 0), TimeUnit.MILLISECONDS)) {
                    return;
                }
                String msg = "ERROR: el pool '" + nombre + "' no terminó en " + Config.TIMEOUT_CIERRE_MS
                        + " ms (posible bloqueo). Se fuerza con shutdownNow().";
                System.err.println(msg);
                log.escribir(msg);
                volcarHilos();
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
                return;
            } catch (InterruptedException e) {
                // Se recuerda la interrupción y se sigue esperando: el cierre tiene que completarse.
                interrumpido = true;
            }
        }
    }

    private void esperarEmpleados() {
        long limite = System.currentTimeMillis() + Config.TIMEOUT_CIERRE_MS;
        while (true) {
            try {
                if (!empleadosTerminados.await(Math.max(0, limite - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS)) {
                    String msg = "ERROR: los empleados no terminaron a tiempo (quedan "
                            + empleadosTerminados.getCount() + ")";
                    System.err.println(msg);
                    log.escribir(msg);
                    volcarHilos();
                }
                return;
            } catch (InterruptedException e) {
                interrumpido = true;
            }
        }
    }

    /** Vuelca al log y a stderr la pila de todos los hilos (diagnóstico de bloqueos). */
    private void volcarHilos() {
        StringBuilder sb = new StringBuilder("---- Volcado de hilos ----\n");
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            sb.append(e.getKey().getName()).append(" (").append(e.getKey().getState()).append(")\n");
            for (StackTraceElement el : e.getValue()) {
                sb.append("    at ").append(el).append('\n');
            }
        }
        System.err.println(sb);
        log.escribir(sb.toString());
    }

    // ------------------------------------------------------------------
    // Invariantes
    // ------------------------------------------------------------------

    private List<Invariante> verificarInvariantes(Snapshot fin, Display display) {
        List<Invariante> l = new ArrayList<>();
        Contadores c = restaurante.contadores();
        int generados = c.generados.get();
        int entraron = c.entraron.get();
        int afuera = c.retiradosAfuera.get();
        int sinPedir = c.retiradosSinPedir.get();
        int pagaron = c.pagaron.get();
        Snapshot.Estadisticas st = fin.stats();

        l.add(new Invariante("Llegaron = entraron + se fueron sin entrar",
                generados == entraron + afuera && st.generados() == generados,
                generados + " = " + entraron + " + " + afuera));

        l.add(new Invariante("Entraron = pagaron + retirados sin pedir",
                entraron == pagaron + sinPedir && st.entraron() == entraron && st.pagaron() == pagaron
                        && st.retiradosSinPedir() == sinPedir,
                entraron + " = " + pagaron + " + " + sinPedir));

        int lugares = restaurante.lugaresLibres();
        l.add(new Invariante("No queda ningún cliente adentro",
                fin.clientes().isEmpty() && st.adentro() == 0 && lugares == p.capacidad()
                        && restaurante.sala().clientesEsperando() == 0,
                "clientes en la vista=" + fin.clientes().size() + ", lugares libres en la puerta=" + lugares + "/"
                        + p.capacidad()));

        StringBuilder mesas = new StringBuilder();
        boolean mesasOk = true;
        for (Mesa m : restaurante.mesas()) {
            EstadoMesa em = m.estadoActual();
            mesas.append("M").append(m.id()).append('=').append(em).append(' ');
            mesasOk &= em == EstadoMesa.LIBRE;
        }
        for (Snapshot.MesaVista mv : fin.mesas()) {
            mesasOk &= mv.estado() == EstadoMesa.LIBRE;
        }
        mesasOk &= restaurante.sala().mesasLibres() == p.mesas();
        l.add(new Invariante("Todas las mesas están LIBRES", mesasOk, mesas.toString().trim()));

        boolean colasOk = restaurante.colaMozos().estaVacia() && restaurante.caja().colaVacia()
                && restaurante.cocina().vacia() && fin.colaMozos().isEmpty() && fin.colaCaja().isEmpty()
                && fin.cocina().isEmpty() && fin.sala().isEmpty() && fin.afuera().isEmpty()
                && estado.colaEventos().isEmpty();
        l.add(new Invariante("Las colas están vacías", colasOk,
                "mozos=" + restaurante.colaMozos().tamanio() + ", caja=" + (restaurante.caja().colaVacia() ? 0 : "?")
                        + ", cocina=" + (restaurante.cocina().vacia() ? 0 : "?") + ", eventos="
                        + estado.colaEventos().size()));

        int pedidos = restaurante.cocina().platosPedidos();
        int cocinados = restaurante.cocina().platosCocinados();
        l.add(new Invariante("Platos cocinados = platos pedidos",
                pedidos == cocinados && cocinados == pagaron && st.platosCocinados() == cocinados
                        && st.platosPedidos() == pedidos,
                cocinados + " = " + pedidos + " (y = " + pagaron + " clientes que pagaron)"));

        l.add(new Invariante("Recaudación de la caja = suma de los tickets",
                restaurante.caja().recaudacion() == st.recaudacion(),
                "$" + restaurante.caja().recaudacion() + " = $" + st.recaudacion()));

        boolean empleadosOk = empleadosTerminados.getCount() == 0;
        for (Snapshot.EmpleadoVista e : fin.mozos()) {
            empleadosOk &= e.estado() == EstadoEmpleado.TERMINADO;
        }
        for (Snapshot.EmpleadoVista e : fin.cocineros()) {
            empleadosOk &= e.estado() == EstadoEmpleado.TERMINADO;
        }
        for (Snapshot.EmpleadoVista e : fin.cajeros()) {
            empleadosOk &= e.estado() == EstadoEmpleado.TERMINADO;
        }
        l.add(new Invariante("Todos los empleados terminaron su turno", empleadosOk,
                "latch=" + empleadosTerminados.getCount()));

        l.add(new Invariante("El Display procesó todos los eventos en orden",
                display.procesados() == fin.secuencia() && display.ultimaSecuencia() == fin.secuencia(),
                display.procesados() + " procesados de " + fin.secuencia()));

        StringBuilder detallePools = new StringBuilder();
        boolean poolsOk = true;
        for (Map.Entry<String, ExecutorService> e : pools.entrySet()) {
            boolean t = e.getValue().isTerminated();
            poolsOk &= t;
            if (!t) {
                detallePools.append(e.getKey()).append(" sin terminar; ");
            }
        }
        int vivos = 0;
        int creados = 0;
        for (FabricaHilos f : fabricas) {
            vivos += f.vivos();
            creados += f.creados();
        }
        poolsOk &= vivos == 0;
        detallePools.append(pools.size()).append(" pools terminados, hilos creados=").append(creados)
                .append(", vivos=").append(vivos);
        l.add(new Invariante("Todos los pools terminados y sin hilos vivos", poolsOk, detallePools.toString()));
        return l;
    }

    private static String informe(Resultado r) {
        Snapshot.Estadisticas st = r.snapshotFinal().stats();
        StringBuilder sb = new StringBuilder();
        sb.append("==================================================================\n");
        sb.append(" VERIFICACIÓN DE INVARIANTES\n");
        sb.append("==================================================================\n");
        for (Invariante i : r.invariantes()) {
            sb.append(' ').append(i).append('\n');
        }
        sb.append("------------------------------------------------------------------\n");
        sb.append(" Duración real: ").append(r.duracionMs()).append(" ms (T = ").append(r.parametros().t())
                .append(" ms) | eventos: ").append(r.eventos()).append('\n');
        sb.append(" Llegaron ").append(st.generados()).append(", entraron ").append(st.entraron())
                .append(", pagaron ").append(st.pagaron()).append(", sin pedir ").append(st.retiradosSinPedir())
                .append(", se fueron sin entrar ").append(st.retiradosAfuera()).append('\n');
        sb.append(" Recaudación: $").append(st.recaudacion()).append(" | estadía promedio: ")
                .append(st.estadiaPromedioMs()).append(" ms\n");
        sb.append(" Log: ").append(r.rutaLog()).append('\n');
        sb.append(r.todosOk() ? " RESULTADO: TODOS LOS INVARIANTES OK\n"
                : " RESULTADO: ¡FALLÓ AL MENOS UN INVARIANTE!\n");
        sb.append("==================================================================");
        return sb.toString();
    }
}
