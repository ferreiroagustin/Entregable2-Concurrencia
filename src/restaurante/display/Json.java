package restaurante.display;

import java.util.List;

import restaurante.config.Parametros;
import restaurante.config.Rango;
import restaurante.estado.Evento;
import restaurante.estado.Snapshot;
import restaurante.modelo.EstadoCliente;
import restaurante.modelo.EstadoEmpleado;
import restaurante.modelo.EstadoMesa;
import restaurante.modelo.EstadoPlato;
import restaurante.modelo.Menu;
import restaurante.simulacion.Invariante;
import restaurante.simulacion.Resultado;

/**
 * Serializador JSON mínimo hecho a mano (sin librerías externas).
 *
 * <p>Convención: los ids enteros negativos (-1 = "ninguno") se escriben como
 * {@code null}. Los enums se escriben con su {@code name()}.
 * Los nombres de campo tienen que coincidir con los que usa {@code web/app.js}.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------
    // Escritor de objetos
    // ------------------------------------------------------------------

    /** Ayudante para escribir un objeto JSON poniendo las comas en su lugar. */
    private static final class Obj {
        private final StringBuilder sb;
        private boolean primero = true;

        Obj(StringBuilder sb) {
            this.sb = sb;
            sb.append('{');
        }

        Obj clave(String k) {
            if (!primero) {
                sb.append(',');
            }
            primero = false;
            cadena(sb, k);
            sb.append(':');
            return this;
        }

        Obj num(String k, long v) {
            clave(k).sb.append(v);
            return this;
        }

        Obj num(String k, double v) {
            clave(k).sb.append(v);
            return this;
        }

        /** Entero que representa un id: si es negativo se escribe null. */
        Obj id(String k, int v) {
            clave(k);
            if (v < 0) {
                sb.append("null");
            } else {
                sb.append(v);
            }
            return this;
        }

        Obj texto(String k, String v) {
            clave(k);
            if (v == null) {
                sb.append("null");
            } else {
                cadena(sb, v);
            }
            return this;
        }

        Obj enumeracion(String k, Enum<?> v) {
            return texto(k, v == null ? null : v.name());
        }

        Obj bool(String k, boolean v) {
            clave(k).sb.append(v);
            return this;
        }

        Obj ids(String k, List<Integer> v) {
            clave(k);
            listaIds(sb, v);
            return this;
        }

        void fin() {
            sb.append('}');
        }
    }

    /** Escribe una cadena JSON con todos los caracteres especiales escapados. */
    static void cadena(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Devuelve la cadena como literal JSON (útil para pruebas). */
    public static String cadena(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        cadena(sb, s);
        return sb.toString();
    }

    private static void listaIds(StringBuilder sb, List<Integer> ids) {
        sb.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            int v = ids.get(i);
            if (v < 0) {
                sb.append("null");
            } else {
                sb.append(v);
            }
        }
        sb.append(']');
    }

    // ------------------------------------------------------------------
    // Evento y snapshot
    // ------------------------------------------------------------------

    public static String evento(Evento e) {
        StringBuilder sb = new StringBuilder(4096);
        Obj o = new Obj(sb)
                .num("seq", e.secuencia())
                .num("t", e.tiempoMs())
                .enumeracion("tipo", e.actor().tipo())
                .num("actorId", e.actor().id())
                .texto("actor", e.actor().nombre())
                .texto("desc", e.descripcion());
        o.clave("snap");
        snapshot(sb, e.snapshot());
        o.fin();
        return sb.toString();
    }

    public static String snapshot(Snapshot s) {
        StringBuilder sb = new StringBuilder(4096);
        snapshot(sb, s);
        return sb.toString();
    }

    private static void snapshot(StringBuilder sb, Snapshot s) {
        Obj o = new Obj(sb)
                .num("seq", s.secuencia())
                .num("t", s.tiempoMs())
                .num("total", s.tiempoTotalMs())
                .texto("estado", s.estado());

        o.clave("clientes");
        sb.append('[');
        for (int i = 0; i < s.clientes().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.ClienteVista c = s.clientes().get(i);
            new Obj(sb).num("id", c.id()).enumeracion("estado", c.estado()).id("mesa", c.mesa())
                    .id("asiento", c.asiento()).id("grupo", c.grupo()).enumeracion("menu", c.menu())
                    .enumeracion("plato", c.plato()).num("llegada", c.llegadaMs()).fin();
        }
        sb.append(']');

        o.clave("mozos");
        empleados(sb, s.mozos());
        o.clave("cocineros");
        empleados(sb, s.cocineros());
        o.clave("cajeros");
        empleados(sb, s.cajeros());

        o.clave("mesas");
        sb.append('[');
        for (int i = 0; i < s.mesas().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.MesaVista m = s.mesas().get(i);
            new Obj(sb).num("id", m.id()).enumeracion("estado", m.estado()).id("mozo", m.mozo())
                    .ids("asientos", m.asientos()).fin();
        }
        sb.append(']');

        o.ids("afuera", s.afuera());

        o.clave("sala");
        sb.append('[');
        for (int i = 0; i < s.sala().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.GrupoVista g = s.sala().get(i);
            new Obj(sb).num("grupo", g.grupo()).ids("clientes", g.clientes()).bool("completo", g.completo()).fin();
        }
        sb.append(']');

        o.clave("colaMozos");
        sb.append('[');
        for (int i = 0; i < s.colaMozos().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.TareaVista t = s.colaMozos().get(i);
            new Obj(sb).num("seq", t.secuencia()).enumeracion("tipo", t.tipo()).id("mesa", t.mesa()).fin();
        }
        sb.append(']');

        o.clave("cocina");
        sb.append('[');
        for (int i = 0; i < s.cocina().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.PedidoVista p = s.cocina().get(i);
            Obj op = new Obj(sb).num("id", p.id()).id("mesa", p.mesa()).bool("listo", p.listo());
            op.clave("platos");
            sb.append('[');
            for (int j = 0; j < p.platos().size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                Snapshot.PlatoVista pl = p.platos().get(j);
                new Obj(sb).num("cliente", pl.cliente()).enumeracion("menu", pl.menu())
                        .enumeracion("estado", pl.estado()).id("cocinero", pl.cocinero()).fin();
            }
            sb.append(']');
            op.fin();
        }
        sb.append(']');

        o.ids("colaCaja", s.colaCaja());

        o.clave("stats");
        estadisticas(sb, s.stats());
        o.fin();
    }

    private static void empleados(StringBuilder sb, List<Snapshot.EmpleadoVista> lista) {
        sb.append('[');
        for (int i = 0; i < lista.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.EmpleadoVista e = lista.get(i);
            new Obj(sb).num("id", e.id()).texto("nombre", e.nombre()).enumeracion("estado", e.estado())
                    .texto("detalle", e.detalle()).id("mesa", e.mesa()).num("tareas", e.tareas()).fin();
        }
        sb.append(']');
    }

    private static void estadisticas(StringBuilder sb, Snapshot.Estadisticas st) {
        Obj o = new Obj(sb)
                .num("generados", st.generados())
                .num("entraron", st.entraron())
                .num("retiradosAfuera", st.retiradosAfuera())
                .num("retiradosSinPedir", st.retiradosSinPedir())
                .num("pagaron", st.pagaron())
                .num("adentro", st.adentro())
                .num("esperandoAfuera", st.esperandoAfuera())
                .num("recaudacion", st.recaudacion())
                .num("platosPedidos", st.platosPedidos())
                .num("platosCocinados", st.platosCocinados())
                .num("estadiaPromedio", st.estadiaPromedioMs());
        o.clave("porMenu");
        sb.append('[');
        for (int i = 0; i < st.porMenu().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Snapshot.VentaMenu v = st.porMenu().get(i);
            new Obj(sb).enumeracion("menu", v.menu()).num("cantidad", v.cantidad()).num("monto", v.monto()).fin();
        }
        sb.append(']');
        o.fin();
    }

    // ------------------------------------------------------------------
    // Configuración y resumen
    // ------------------------------------------------------------------

    public static String config(Parametros p) {
        StringBuilder sb = new StringBuilder(2048);
        Obj o = new Obj(sb)
                .num("T", p.t())
                .num("M", p.mesas())
                .num("P", p.personasPorMesa())
                .num("Z", p.mozos())
                .num("C", p.cocineros())
                .num("Y", p.cajeros())
                .num("escalaCocina", p.escalaCocina());
        o.clave("rangos");
        Obj r = new Obj(sb);
        rango(r, sb, "TP", p.llegada());
        rango(r, sb, "TM", p.eleccion());
        rango(r, sb, "TQ", p.comida());
        rango(r, sb, "TZ", p.anotar());
        rango(r, sb, "TR", p.servir());
        rango(r, sb, "TL", p.limpiar());
        rango(r, sb, "TY", p.cobrar());
        r.fin();

        o.clave("menus");
        sb.append('[');
        Menu[] menus = Menu.values();
        for (int i = 0; i < menus.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Menu m = menus[i];
            Rango tc = m.coccion().escalar(p.escalaCocina());
            new Obj(sb).texto("codigo", m.name()).texto("nombre", m.nombre()).num("precio", m.precio())
                    .num("tcMin", tc.min()).num("tcMax", tc.max()).fin();
        }
        sb.append(']');

        o.clave("estadosCliente");
        descripciones(sb, EstadoCliente.values());
        o.clave("estadosMesa");
        descripciones(sb, EstadoMesa.values());
        o.clave("estadosEmpleado");
        descripciones(sb, EstadoEmpleado.values());
        o.clave("estadosPlato");
        descripciones(sb, EstadoPlato.values());
        o.fin();
        return sb.toString();
    }

    private static void rango(Obj r, StringBuilder sb, String nombre, Rango rango) {
        r.clave(nombre);
        new Obj(sb).num("min", rango.min()).num("max", rango.max()).fin();
    }

    private static void descripciones(StringBuilder sb, Enum<?>[] valores) {
        sb.append('[');
        for (int i = 0; i < valores.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Enum<?> e = valores[i];
            String desc;
            if (e instanceof EstadoCliente c) {
                desc = c.descripcion();
            } else if (e instanceof EstadoMesa m) {
                desc = m.descripcion();
            } else if (e instanceof EstadoEmpleado em) {
                desc = em.descripcion();
            } else if (e instanceof EstadoPlato pl) {
                desc = pl.descripcion();
            } else {
                desc = e.name();
            }
            new Obj(sb).texto("codigo", e.name()).texto("descripcion", desc).fin();
        }
        sb.append(']');
    }

    /** Resumen final de una simulación (evento SSE "fin" y /estado). */
    public static String resumen(Resultado r) {
        StringBuilder sb = new StringBuilder(2048);
        Obj o = new Obj(sb)
                .num("duracion", r.duracionMs())
                .num("T", r.parametros().t())
                .num("eventos", r.eventos())
                .texto("log", r.rutaLog())
                .bool("todosOk", r.todosOk());
        o.clave("stats");
        estadisticas(sb, r.snapshotFinal().stats());
        o.clave("invariantes");
        sb.append('[');
        List<Invariante> inv = r.invariantes();
        for (int i = 0; i < inv.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Invariante v = inv.get(i);
            new Obj(sb).texto("nombre", v.nombre()).bool("ok", v.ok()).texto("detalle", v.detalle()).fin();
        }
        sb.append(']');
        o.fin();
        return sb.toString();
    }

    /** Respuesta de /estado. */
    public static String estado(boolean iniciada, Snapshot snapshot, Resultado resultado) {
        StringBuilder sb = new StringBuilder(4096);
        Obj o = new Obj(sb).bool("iniciada", iniciada).bool("terminada", resultado != null);
        o.clave("snapshot");
        if (snapshot == null) {
            sb.append("null");
        } else {
            snapshot(sb, snapshot);
        }
        o.clave("resumen");
        sb.append(resultado == null ? "null" : resumen(resultado));
        o.fin();
        return sb.toString();
    }

    /** Objeto simple {"ok":..,"mensaje":..}. */
    public static String mensaje(boolean ok, String mensaje) {
        StringBuilder sb = new StringBuilder();
        new Obj(sb).bool("ok", ok).texto("mensaje", mensaje).fin();
        return sb.toString();
    }
}
