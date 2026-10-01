package restaurante.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Canal de Server-Sent Events hacia los navegadores.
 *
 * <p>El Display publica cada evento (ya convertido a JSON). Cada navegador
 * conectado tiene su propia cola ilimitada ({@link Suscriptor}) que vacía su
 * hilo HTTP: así un navegador lento nunca frena al Display (desacople
 * productor-consumidor).
 *
 * <p>Se guarda el historial completo para que un navegador que se conecta
 * tarde (o recarga la página) reciba todos los eventos y pueda usar el modo
 * "viaje en el tiempo". Con el encabezado {@code Last-Event-ID} solo se
 * reenvía lo que le falta.
 *
 * <p>Los métodos que tocan historial y suscriptores son {@code synchronized}
 * para que suscribirse y publicar sean atómicos entre sí (sin huecos ni
 * duplicados). Adentro solo se agregan elementos a colas ilimitadas: nunca se
 * bloquea con el monitor tomado.
 */
public final class CanalSSE {

    /** Cola de mensajes pendientes de un navegador. */
    public static final class Suscriptor {
        private final BlockingQueue<String> cola = new LinkedBlockingQueue<>();

        public BlockingQueue<String> cola() {
            return cola;
        }
    }

    private record Mensaje(long id, String texto) {
    }

    private final List<Mensaje> historial = new ArrayList<>();
    private final List<Suscriptor> suscriptores = new CopyOnWriteArrayList<>();
    private String mensajeFin;

    /** Publica un evento de la simulación. Lo llama el hilo Display. */
    public synchronized void publicarEvento(long id, String json) {
        String texto = "id: " + id + "\nevent: evento\ndata: " + json + "\n\n";
        historial.add(new Mensaje(id, texto));
        for (Suscriptor s : suscriptores) {
            s.cola.add(texto);
        }
    }

    /** Publica el resumen final de la simulación. */
    public synchronized void publicarFin(String json) {
        mensajeFin = "event: fin\ndata: " + json + "\n\n";
        for (Suscriptor s : suscriptores) {
            s.cola.add(mensajeFin);
        }
    }

    /** Registra un navegador y le encola todo lo que se perdió desde {@code ultimoId}. */
    public synchronized Suscriptor suscribir(long ultimoId) {
        Suscriptor s = new Suscriptor();
        for (Mensaje m : historial) {
            if (m.id() > ultimoId) {
                s.cola.add(m.texto());
            }
        }
        if (mensajeFin != null) {
            s.cola.add(mensajeFin);
        }
        suscriptores.add(s);
        return s;
    }

    public void desuscribir(Suscriptor s) {
        suscriptores.remove(s);
    }

    public int conectados() {
        return suscriptores.size();
    }
}
