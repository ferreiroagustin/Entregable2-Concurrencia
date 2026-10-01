package restaurante.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import restaurante.config.Config;
import restaurante.display.Json;
import restaurante.simulacion.FabricaHilos;
import restaurante.simulacion.Simulacion;

/**
 * Servidor HTTP mínimo con el {@code HttpServer} del JDK (sin dependencias).
 *
 * <ul>
 * <li>{@code GET /}, {@code /index.html}, {@code /styles.css}, {@code /app.js}: archivos de {@code web/}.</li>
 * <li>{@code GET /config}: constantes de la simulación.</li>
 * <li>{@code GET /estado}: snapshot actual (read lock) y resumen si ya terminó.</li>
 * <li>{@code GET /eventos}: stream SSE con todos los eventos.</li>
 * <li>{@code POST /iniciar}: arranca la simulación (una sola vez por ejecución).</li>
 * </ul>
 * Las peticiones se atienden en un pool de hilos daemon (cada conexión SSE
 * ocupa un hilo mientras el navegador está conectado).
 */
public final class ServidorWeb {

    private static final Map<String, String> ARCHIVOS = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/styles.css", "styles.css",
            "/app.js", "app.js");

    private final HttpServer servidor;
    private final ExecutorService ejecutor;
    private final Simulacion simulacion;
    private final CanalSSE canal;
    private final Path carpetaWeb;
    private final int puerto;

    private ServidorWeb(HttpServer servidor, int puerto, Simulacion simulacion, CanalSSE canal) {
        this.servidor = servidor;
        this.puerto = puerto;
        this.simulacion = simulacion;
        this.canal = canal;
        this.carpetaWeb = buscarCarpetaWeb();
        this.ejecutor = Executors.newCachedThreadPool(new FabricaHilos("http", true, null));
        servidor.setExecutor(ejecutor);
        servidor.createContext("/", this::estatico);
        servidor.createContext("/config", this::config);
        servidor.createContext("/estado", this::estado);
        servidor.createContext("/eventos", this::eventos);
        servidor.createContext("/iniciar", this::iniciar);
    }

    /** Crea y arranca el servidor en el primer puerto libre a partir de {@link Config#PUERTO_WEB}. */
    public static ServidorWeb iniciar(Simulacion simulacion, CanalSSE canal) throws IOException {
        IOException ultimo = null;
        for (int i = 0; i < Config.PUERTOS_A_PROBAR; i++) {
            int puerto = Config.PUERTO_WEB + i;
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), 0);
                ServidorWeb web = new ServidorWeb(s, puerto, simulacion, canal);
                s.start();
                return web;
            } catch (BindException e) {
                System.out.println("El puerto " + puerto + " está ocupado, pruebo el siguiente...");
                ultimo = e;
            }
        }
        throw new IOException("No hay puertos libres entre " + Config.PUERTO_WEB + " y "
                + (Config.PUERTO_WEB + Config.PUERTOS_A_PROBAR - 1), ultimo);
    }

    public int puerto() {
        return puerto;
    }

    public String url() {
        return "http://localhost:" + puerto + "/";
    }

    public void detener() {
        servidor.stop(0);
        ejecutor.shutdownNow();
        try {
            ejecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void estatico(HttpExchange ex) throws IOException {
        try (ex) {
            if (!esMetodo(ex, "GET") && !esMetodo(ex, "HEAD")) {
                responder(ex, 405, "text/plain; charset=utf-8", "Método no permitido");
                return;
            }
            String ruta = ex.getRequestURI().getPath();
            String archivo = ARCHIVOS.get(ruta);
            if (archivo == null) {
                responder(ex, 404, "text/plain; charset=utf-8", "No encontrado: " + ruta);
                return;
            }
            Path p = carpetaWeb.resolve(archivo);
            if (!Files.isRegularFile(p)) {
                responder(ex, 404, "text/plain; charset=utf-8",
                        "No se encontró " + p.toAbsolutePath() + ". Ejecutá el programa desde la carpeta del proyecto.");
                return;
            }
            byte[] contenido = Files.readAllBytes(p);
            responder(ex, 200, tipoDeContenido(archivo), contenido);
        }
    }

    private void config(HttpExchange ex) throws IOException {
        try (ex) {
            responder(ex, 200, "application/json; charset=utf-8", Json.config(simulacion.parametros()));
        }
    }

    private void estado(HttpExchange ex) throws IOException {
        try (ex) {
            String json = Json.estado(simulacion.iniciada(), simulacion.estado().snapshotActual(),
                    simulacion.resultado());
            responder(ex, 200, "application/json; charset=utf-8", json);
        }
    }

    private void iniciar(HttpExchange ex) throws IOException {
        try (ex) {
            if (!esMetodo(ex, "POST")) {
                responder(ex, 405, "application/json; charset=utf-8", Json.mensaje(false, "Usá POST"));
                return;
            }
            consumirCuerpo(ex);
            if (simulacion.iniciarEnSegundoPlano()) {
                System.out.println(">>> Simulación iniciada desde el navegador");
                responder(ex, 200, "application/json; charset=utf-8", Json.mensaje(true, "Simulación iniciada"));
            } else {
                responder(ex, 409, "application/json; charset=utf-8",
                        Json.mensaje(false, "La simulación ya fue iniciada (una sola vez por ejecución)"));
            }
        }
    }

    /** Stream SSE: queda abierto mientras el navegador esté conectado. */
    private void eventos(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        CanalSSE.Suscriptor s = canal.suscribir(ultimoIdRecibido(ex));
        try (OutputStream os = ex.getResponseBody()) {
            os.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (!Thread.currentThread().isInterrupted()) {
                String msg = s.cola().poll(Config.PING_SSE_MS, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    msg = ": ping\n\n";
                }
                os.write(msg.getBytes(StandardCharsets.UTF_8));
                // Se mandan juntos los mensajes que ya estén esperando (menos flushes).
                for (int i = 0; i < 200 && (msg = s.cola().poll()) != null; i++) {
                    os.write(msg.getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            }
        } catch (IOException e) {
            // El navegador cerró la conexión: no es un error.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            canal.desuscribir(s);
            ex.close();
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static long ultimoIdRecibido(HttpExchange ex) {
        String valor = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (valor == null) {
            String q = ex.getRequestURI().getQuery();
            if (q != null && q.startsWith("desde=")) {
                valor = q.substring("desde=".length());
            }
        }
        if (valor == null) {
            return 0;
        }
        try {
            return Long.parseLong(valor.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean esMetodo(HttpExchange ex, String metodo) {
        return metodo.equalsIgnoreCase(ex.getRequestMethod());
    }

    private static void consumirCuerpo(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            in.readAllBytes();
        }
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, String cuerpo) throws IOException {
        responder(ex, codigo, tipo, cuerpo.getBytes(StandardCharsets.UTF_8));
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, byte[] cuerpo) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = esMetodo(ex, "HEAD");
        ex.sendResponseHeaders(codigo, head ? -1 : cuerpo.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(cuerpo);
            }
        }
    }

    private static String tipoDeContenido(String archivo) {
        if (archivo.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (archivo.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (archivo.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    /** Busca la carpeta web/ en el directorio actual o al lado de la carpeta de clases compiladas. */
    private static Path buscarCarpetaWeb() {
        Path local = Path.of(Config.CARPETA_WEB);
        if (Files.isDirectory(local)) {
            return local;
        }
        try {
            Path clases = Path.of(ServidorWeb.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidata = clases.getParent().resolve(Config.CARPETA_WEB);
            if (Files.isDirectory(candidata)) {
                return candidata;
            }
        } catch (URISyntaxException | RuntimeException e) {
            // Se usa la ruta local por defecto.
        }
        return local;
    }
}
