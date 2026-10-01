package restaurante.display;

import java.util.List;
import java.util.StringJoiner;

import restaurante.estado.Evento;
import restaurante.estado.Snapshot;
import restaurante.modelo.Menu;

/**
 * Convierte eventos y snapshots a texto legible para la consola y el log.
 * Solo trabaja sobre objetos inmutables, así que no necesita sincronización.
 */
public final class FormatoTexto {

    private static final String SANGRIA = "    ";

    private FormatoTexto() {
    }

    /** Línea principal del evento: número, tiempo, actor y acción. */
    public static String linea(Evento e) {
        return String.format("#%06d [%s] %-12s | %s", e.secuencia(), tiempo(e.tiempoMs()), e.actor().nombre(),
                e.descripcion());
    }

    /** Estado completo del restaurante (varias líneas, con sangría). */
    public static String estado(Snapshot s) {
        StringBuilder sb = new StringBuilder(1024);
        Snapshot.Estadisticas st = s.stats();

        sb.append(SANGRIA).append("Restaurante: ").append(s.estado())
                .append(" | t=").append(tiempo(s.tiempoMs())).append(" de ").append(tiempo(s.tiempoTotalMs()))
                .append(" | Afuera (").append(s.afuera().size()).append("): ").append(clientes(s.afuera()))
                .append('\n');

        sb.append(SANGRIA).append("Sala de espera: ");
        if (s.sala().isEmpty()) {
            sb.append("vacía");
        } else {
            StringJoiner j = new StringJoiner(" | ");
            for (Snapshot.GrupoVista g : s.sala()) {
                j.add("G" + g.grupo() + " " + clientes(g.clientes())
                        + (g.completo() ? " completo, espera mesa" : " formándose"));
            }
            sb.append(j);
        }
        sb.append('\n');

        for (Snapshot.MesaVista m : s.mesas()) {
            sb.append(SANGRIA).append("Mesa ").append(m.id()).append(" [").append(m.estado()).append(']');
            if (m.mozo() > 0) {
                sb.append(" (mozo ").append(m.mozo()).append(')');
            }
            sb.append(": ");
            StringJoiner j = new StringJoiner(" | ");
            for (int idCliente : m.asientos()) {
                if (idCliente < 0) {
                    j.add("-");
                } else {
                    j.add(describirCliente(s, idCliente));
                }
            }
            sb.append(j).append('\n');
        }

        sb.append(SANGRIA).append("Mozos: ").append(empleados(s.mozos())).append('\n');
        sb.append(SANGRIA).append("Cola de mozos: ");
        if (s.colaMozos().isEmpty()) {
            sb.append("vacía");
        } else {
            StringJoiner j = new StringJoiner(", ");
            for (Snapshot.TareaVista t : s.colaMozos()) {
                j.add(t.tipo() + " M" + t.mesa());
            }
            sb.append(j);
        }
        sb.append('\n');

        sb.append(SANGRIA).append("Cocina: ");
        if (s.cocina().isEmpty()) {
            sb.append("sin pedidos");
        } else {
            StringJoiner j = new StringJoiner(" | ");
            for (Snapshot.PedidoVista p : s.cocina()) {
                StringJoiner platos = new StringJoiner(", ");
                int listos = 0;
                for (Snapshot.PlatoVista pl : p.platos()) {
                    String quien = pl.cocinero() > 0 ? " coc." + pl.cocinero() : "";
                    platos.add(corto(pl.menu()) + " " + pl.estado() + quien);
                    if (pl.estado() == restaurante.modelo.EstadoPlato.LISTO) {
                        listos++;
                    }
                }
                j.add("#" + p.id() + " M" + p.mesa() + " " + listos + "/" + p.platos().size() + " [" + platos + "]"
                        + (p.listo() ? " LISTO PARA RETIRAR" : ""));
            }
            sb.append(j);
        }
        sb.append('\n');
        sb.append(SANGRIA).append("Cocineros: ").append(empleados(s.cocineros())).append('\n');
        sb.append(SANGRIA).append("Caja: cola ").append(clientes(s.colaCaja())).append(" | ")
                .append(empleados(s.cajeros())).append('\n');

        sb.append(SANGRIA).append("Totales: llegaron ").append(st.generados())
                .append(" | entraron ").append(st.entraron())
                .append(" | adentro ").append(st.adentro())
                .append(" | pagaron ").append(st.pagaron())
                .append(" | sin pedir ").append(st.retiradosSinPedir())
                .append(" | se fueron sin entrar ").append(st.retiradosAfuera())
                .append(" | recaudación $").append(st.recaudacion())
                .append(" | platos ").append(st.platosCocinados()).append('/').append(st.platosPedidos());
        return sb.toString();
    }

    private static String describirCliente(Snapshot s, int idCliente) {
        for (Snapshot.ClienteVista c : s.clientes()) {
            if (c.id() == idCliente) {
                return "C" + c.id() + " " + c.estado() + (c.menu() != null ? " " + corto(c.menu()) : "")
                        + (c.plato() != null ? "(" + c.plato() + ")" : "");
            }
        }
        return "C" + idCliente;
    }

    private static String empleados(List<Snapshot.EmpleadoVista> lista) {
        StringJoiner j = new StringJoiner(" | ");
        for (Snapshot.EmpleadoVista e : lista) {
            j.add(e.nombre() + ": " + e.estado() + (e.detalle().isEmpty() ? "" : " (" + e.detalle() + ")"));
        }
        return j.toString();
    }

    private static String clientes(List<Integer> ids) {
        if (ids.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(" ", "[", "]");
        for (int id : ids) {
            j.add("C" + id);
        }
        return j.toString();
    }

    public static String corto(Menu m) {
        String n = m.name();
        return n.charAt(0) + n.substring(1).toLowerCase();
    }

    /** Formato mm:ss.mmm */
    public static String tiempo(long ms) {
        long min = ms / 60_000;
        long seg = (ms / 1000) % 60;
        long mili = ms % 1000;
        return String.format("%02d:%02d.%03d", min, seg, mili);
    }
}
