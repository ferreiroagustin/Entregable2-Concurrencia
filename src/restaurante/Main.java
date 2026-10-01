package restaurante;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

import restaurante.config.Parametros;
import restaurante.display.Display;
import restaurante.simulacion.Invariante;
import restaurante.simulacion.Resultado;
import restaurante.simulacion.Simulacion;
import restaurante.web.CanalSSE;
import restaurante.web.ServidorWeb;

/**
 * Punto de entrada.
 *
 * <pre>
 *   java -cp out restaurante.Main                      modo web (abre el navegador)
 *   java -cp out restaurante.Main --consola            solo consola: arranca enseguida y termina solo
 *   java -cp out restaurante.Main --t 15000            cambia T (en ms) para probar
 *   java -cp out restaurante.Main --repeticiones 20    20 simulaciones cortas seguidas (estrés)
 *   java -cp out restaurante.Main --sin-navegador      modo web sin abrir el navegador
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        boolean consola = false;
        boolean abrirNavegador = true;
        Long t = null;
        int repeticiones = 0;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--consola" -> consola = true;
                    case "--sin-navegador" -> abrirNavegador = false;
                    case "--t" -> t = Long.parseLong(valor(args, ++i, "--t"));
                    case "--repeticiones" -> repeticiones = Integer.parseInt(valor(args, ++i, "--repeticiones"));
                    case "--ayuda", "-h", "--help" -> {
                        mostrarAyuda();
                        return;
                    }
                    default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
                }
            }
            if (t != null && t <= 0 || repeticiones < 0) {
                throw new IllegalArgumentException("T y las repeticiones tienen que ser positivos");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Argumentos inválidos: " + e.getMessage());
            mostrarAyuda();
            return;
        }

        if (repeticiones > 0) {
            correrRepeticiones(repeticiones, t);
            return;
        }

        Parametros p = Parametros.porDefecto();
        if (t != null) {
            p = p.conT(t);
        }

        if (consola) {
            Simulacion s = new Simulacion(p, Display.Modo.COMPLETO, null, System.out);
            s.ejecutar();
            return;
        }

        CanalSSE canal = new CanalSSE();
        Simulacion s = new Simulacion(p, Display.Modo.RESUMIDO, canal, System.out);
        try {
            ServidorWeb web = ServidorWeb.iniciar(s, canal);
            System.out.println("==============================================================");
            System.out.println(" Simulador de restaurante - interfaz web en " + web.url());
            System.out.println(" Abrí esa dirección y tocá \"Iniciar simulación\".");
            System.out.println(" Al terminar, el servidor queda vivo para revisar el resultado.");
            System.out.println(" Para salir: Ctrl+C.");
            System.out.println("==============================================================");
            if (abrirNavegador) {
                abrirNavegador(web.url());
            }
        } catch (IOException e) {
            System.err.println("No se pudo iniciar el servidor web (" + e.getMessage()
                    + "). Se ejecuta en modo consola.");
            new Simulacion(p, Display.Modo.COMPLETO, null, System.out).ejecutar();
        }
    }

    /** Valor de una opción que lo requiere (por ejemplo, el número después de --t). */
    private static String valor(String[] args, int i, String opcion) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Falta el valor de " + opcion);
        }
        return args[i];
    }

    private static void correrRepeticiones(int n, Long t) {
        System.out.println("Modo estrés: " + n + " simulaciones cortas seguidas (tiempos x"
                + restaurante.config.Config.ESCALA_RAPIDA + ")");
        int ok = 0;
        for (int i = 1; i <= n; i++) {
            Parametros p = Parametros.rapidos(i);
            if (t != null) {
                p = p.conT(t);
            }
            Resultado r = new Simulacion(p, Display.Modo.SILENCIOSO, null, System.out).ejecutar();
            var st = r.snapshotFinal().stats();
            System.out.printf("Repetición %2d/%d | M=%d P=%d Z=%d C=%d Y=%d | %5d ms | eventos %5d | llegaron %3d,"
                    + " pagaron %3d, sin pedir %2d, sin entrar %3d | %s%n",
                    i, n, p.mesas(), p.personasPorMesa(), p.mozos(), p.cocineros(), p.cajeros(), r.duracionMs(),
                    r.eventos(), st.generados(), st.pagaron(), st.retiradosSinPedir(), st.retiradosAfuera(),
                    r.todosOk() ? "INVARIANTES OK" : "FALLA");
            if (r.todosOk()) {
                ok++;
            } else {
                for (Invariante inv : r.invariantes()) {
                    if (!inv.ok()) {
                        System.out.println("    " + inv);
                    }
                }
                System.out.println("    Ver log: " + r.rutaLog());
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        System.out.println("==============================================================");
        System.out.println(" Repeticiones OK: " + ok + "/" + n + (ok == n ? "  -> TODAS OK" : "  -> HUBO FALLAS"));
        System.out.println("==============================================================");
    }

    private static void abrirNavegador(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                System.out.println("No se puede abrir el navegador automáticamente: abrí " + url);
            }
        } catch (Exception | LinkageError e) {
            System.out.println("No se pudo abrir el navegador (" + e.getMessage() + "): abrí " + url);
        }
    }

    private static void mostrarAyuda() {
        System.out.println("Uso: java -cp out restaurante.Main [opciones]");
        System.out.println("  (sin opciones)       Modo web: servidor en http://localhost:8080 y abre el navegador");
        System.out.println("  --consola            Solo consola: arranca enseguida, muestra cada cambio y termina solo");
        System.out.println("  --t <ms>             Cambia la duración T de la simulación (para pruebas)");
        System.out.println("  --repeticiones <n>   Corre n simulaciones cortas seguidas con tiempos chicos (estrés)");
        System.out.println("  --sin-navegador      Modo web sin abrir el navegador automáticamente");
    }
}
