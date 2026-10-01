package restaurante.display;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Log de la simulación en un archivo de texto UTF-8:
 * {@code logs/simulacion-AAAAMMDD-HHmmss-SSS.log}.
 *
 * <p>Lo escribe principalmente el hilo Display (en orden de eventos). El
 * coordinador agrega el encabezado y la verificación final, y el manejador de
 * excepciones no capturadas puede escribir errores; por eso los métodos son
 * {@code synchronized}.
 */
public final class LogArchivo {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path ruta;
    private final BufferedWriter escritor;
    private boolean cerrado;
    private boolean avisoError;

    private LogArchivo(Path ruta, BufferedWriter escritor) {
        this.ruta = ruta;
        this.escritor = escritor;
    }

    /** Crea la carpeta (si hace falta) y un archivo nuevo con la fecha y hora actual. */
    public static LogArchivo crear(String carpeta) throws IOException {
        Path dir = Path.of(carpeta);
        Files.createDirectories(dir);
        String base = "simulacion-" + LocalDateTime.now().format(FORMATO);
        Path ruta = dir.resolve(base + ".log");
        int n = 1;
        while (Files.exists(ruta)) {
            ruta = dir.resolve(base + "-" + (n++) + ".log");
        }
        return new LogArchivo(ruta, Files.newBufferedWriter(ruta, StandardCharsets.UTF_8));
    }

    public synchronized void escribir(String texto) {
        if (cerrado) {
            return;
        }
        try {
            escritor.write(texto);
            escritor.newLine();
        } catch (IOException e) {
            if (!avisoError) {
                avisoError = true;
                System.err.println("No se pudo escribir el log " + ruta + ": " + e.getMessage());
            }
        }
    }

    public synchronized void vaciarBuffer() {
        if (cerrado) {
            return;
        }
        try {
            escritor.flush();
        } catch (IOException e) {
            System.err.println("No se pudo vaciar el log: " + e.getMessage());
        }
    }

    public synchronized void cerrar() {
        if (cerrado) {
            return;
        }
        cerrado = true;
        try {
            escritor.close();
        } catch (IOException e) {
            System.err.println("No se pudo cerrar el log: " + e.getMessage());
        }
    }

    public synchronized boolean estaCerrado() {
        return cerrado;
    }

    public Path ruta() {
        return ruta;
    }
}
