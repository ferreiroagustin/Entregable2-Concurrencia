/*
 * Frontend del simulador: un plano del restaurante visto desde arriba, en vivo.
 *
 * Fuentes de datos (ver ServidorWeb.java y Json.java):
 *   - GET /config  -> constantes (M, P, Z, C, Y, T, menús y descripciones de estados)
 *   - GET /estado  -> snapshot actual (y resumen, si ya terminó)
 *   - EventSource /eventos -> un evento SSE "evento" por cada cambio + "fin" con el resumen
 *
 * Cada evento trae el snapshot completo del restaurante en el momento de la
 * notificación. El mapa dibuja siempre el último snapshot recibido (un render por
 * frame como máximo) y las fichas se desplazan con transiciones CSS.
 */
(() => {
  "use strict";

  const NS = "http://www.w3.org/2000/svg";
  const $ = (id) => document.getElementById(id);

  const EMOJI_MENU = {
    MILANESA: "🍖", ENSALADA: "🥗", PIZZA: "🍕", RAVIOLES: "🍝", HAMBURGUESA: "🍔", FLAN: "🍮"
  };
  const ICONO_MOZO = { TOMANDO_PEDIDO: "📝", SIRVIENDO: "🍽️", LIMPIANDO: "🧽" };
  const ICONO_ROL = { MOZO: "🤵", COCINERO: "👨‍🍳", CAJERO: "💵" };
  const MAX_EVENTOS = 200;
  const MAX_AFUERA = 57;
  const SALIDA = { x: 150, y: 555 };

  const app = {
    cfg: null,
    desc: { cliente: {}, mesa: {}, empleado: {} },
    G: null,                 // geometría del mapa
    fichas: new Map(),       // clave -> { g, circulo, texto, titulo, badge }
    ultimoSeq: 0,
    pendiente: null,         // último snapshot sin dibujar
    logPendiente: [],        // eventos sin agregar al panel
    cantEventos: 0,
    frame: 0,
    estado: "SIN_INICIAR",
    tServidor: 0,
    tLocal: 0,
    resumen: null,
    fuente: null
  };

  // ------------------------------------------------------------------
  // Utilidades SVG
  // ------------------------------------------------------------------

  function el(tag, attrs, padre) {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (padre) padre.appendChild(e);
    return e;
  }

  function texto(x, y, contenido, clase, padre) {
    const t = el("text", { x, y, class: clase }, padre);
    t.textContent = contenido;
    return t;
  }

  function limpiar(nodo) {
    while (nodo.firstChild) nodo.removeChild(nodo.firstChild);
  }

  const dinero = (n) => "$" + Number(n).toLocaleString("es-AR");

  function mmss(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  }

  // ------------------------------------------------------------------
  // Plano fijo: zonas, paredes, mesas, hornallas y cajas
  // ------------------------------------------------------------------

  function construirMapa(cfg) {
    const fondo = $("capaFondo");
    limpiar(fondo);
    const G = { mesas: new Map(), cocineros: [], cajeros: [] };

    // Zonas
    el("rect", { x: 0, y: 0, width: 112, height: 600, class: "zona-vereda" }, fondo);
    texto(14, 24, "AFUERA", "rotulo", fondo);
    el("rect", { x: 125, y: 10, width: 180, height: 270, rx: 8, class: "zona" }, fondo);
    texto(135, 30, "SALA DE ESPERA", "rotulo", fondo);
    el("rect", { x: 125, y: 290, width: 180, height: 210, rx: 8, class: "zona" }, fondo);
    texto(135, 310, "MOZOS", "rotulo", fondo);
    el("rect", { x: 125, y: 510, width: 180, height: 80, rx: 8, class: "zona" }, fondo);
    texto(135, 530, "SALIDA", "rotulo", fondo);
    el("rect", { x: 315, y: 10, width: 435, height: 580, rx: 8, class: "zona-piso" }, fondo);
    texto(325, 30, "SALÓN", "rotulo", fondo);
    el("rect", { x: 760, y: 10, width: 230, height: 330, rx: 8, class: "zona-cocina" }, fondo);
    texto(770, 30, "COCINA", "rotulo", fondo);
    el("rect", { x: 760, y: 350, width: 230, height: 240, rx: 8, class: "zona-caja" }, fondo);
    texto(770, 370, "CAJA", "rotulo", fondo);

    // Paredes: puerta de entrada (cambia de color al cerrar) y salida
    el("line", { x1: 115, y1: 5, x2: 115, y2: 250, class: "pared" }, fondo);
    el("line", { x1: 115, y1: 350, x2: 115, y2: 525, class: "pared" }, fondo);
    el("line", { x1: 115, y1: 585, x2: 115, y2: 595, class: "pared" }, fondo);
    G.puerta = el("line", { x1: 115, y1: 252, x2: 115, y2: 348, class: "puerta abierta" }, fondo);

    // Mesas en grilla dentro del salón
    const M = cfg.M, P = cfg.P;
    const cols = M <= 3 ? M : M === 4 ? 2 : M <= 9 ? 3 : 4;
    const filas = Math.ceil(M / cols);
    const ancho = 415, alto = 545;
    const celdaW = ancho / cols, celdaH = alto / filas;
    const r = Math.max(16, Math.min(40, Math.min(celdaW, celdaH) * 0.19));
    const radioSilla = r + 17;
    for (let i = 0; i < M; i++) {
      const id = i + 1;
      const cx = 325 + celdaW * (i % cols + 0.5);
      const cy = 38 + celdaH * (Math.floor(i / cols) + 0.5) - 10;
      const asientos = [];
      for (let k = 0; k < P; k++) {
        const ang = (-90 + 360 * k / P) * Math.PI / 180;
        const s = { x: cx + radioSilla * Math.cos(ang), y: cy + radioSilla * Math.sin(ang),
                    px: cx + r * 0.55 * Math.cos(ang), py: cy + r * 0.55 * Math.sin(ang) };
        el("circle", { cx: s.x, cy: s.y, r: 12, class: "silla" }, fondo);
        asientos.push(s);
      }
      const circulo = el("circle", { cx, cy, r, class: "mesa", "data-estado": "LIBRE" }, fondo);
      texto(cx, P > 0 ? cy - r * 0.15 : cy, "M" + id, "mesa-nombre", fondo);
      G.mesas.set(id, {
        id, cx, cy, r, asientos, circulo,
        etiquetaY: cy + radioSilla + 26,
        mozo: (n) => {
          const ang = (35 + n * 30) * Math.PI / 180;
          return { x: cx + (radioSilla + 25) * Math.cos(ang), y: cy + (radioSilla + 25) * Math.sin(ang) };
        }
      });
    }

    // Hornallas de los cocineros
    const C = cfg.C;
    const porFila = Math.min(C, 3);
    const filasC = Math.ceil(C / porFila);
    const pasoC = Math.min(75, 140 / Math.max(1, filasC - 1 || 1));
    for (let i = 0; i < C; i++) {
      const x = 760 + 230 / (porFila + 1) * (i % porFila + 1);
      const y = 62 + Math.floor(i / porFila) * pasoC;
      el("rect", { x: x - 24, y: y + 14, width: 48, height: 26, rx: 4, class: "hornalla" }, fondo);
      G.cocineros.push({ x, y, fuego: { x: x - 11, y: y + 27 }, plato: { x: x + 11, y: y + 27 } });
    }
    texto(770, 228, "COMANDAS", "rotulo-chico", fondo);

    // Cajas
    const Y = cfg.Y;
    for (let i = 0; i < Y; i++) {
      const x = 760 + 230 / (Y + 1) * (i + 1);
      el("rect", { x: x - 22, y: 402, width: 44, height: 8, rx: 3, class: "mostrador" }, fondo);
      G.cajeros.push({ x, y: 387, cliente: { x, y: 428 } });
    }
    texto(770, 462, "COLA DE PAGO", "rotulo-chico", fondo);

    G.afuera = (i) => {
      const j = Math.min(i, MAX_AFUERA - 1);
      return { x: 22 + Math.floor(j / 19) * 32, y: 50 + (j % 19) * 28 };
    };
    G.sala = (gi, mi, grupos) => {
      const paso = Math.min(40, 225 / Math.max(1, grupos));
      const pasoX = Math.min(28, 150 / Math.max(1, P));
      return { x: 150 + mi * pasoX, y: 55 + gi * paso };
    };
    G.estacion = (i) => ({ x: 150 + (i % 5) * 34, y: 340 + Math.floor(i / 5) * 40 });
    G.cola = (i) => {
      const j = Math.min(i, 23);
      return { x: 780 + (j % 6) * 36, y: 482 + Math.floor(j / 6) * 30 };
    };
    app.G = G;
  }

  // ------------------------------------------------------------------
  // Fichas (clientes y empleados) que se mueven por el mapa
  // ------------------------------------------------------------------

  function crearFicha(clave, clase, radio, letra, inicio) {
    const g = el("g", { class: "ficha " + clase }, $("capaFichas"));
    const titulo = el("title", {}, g);
    const circulo = el("circle", { r: radio, cx: 0, cy: 0 }, g);
    const t = texto(0, 0.5, letra, "letra", g);
    const f = { g, titulo, circulo, texto: t, badge: null, saliendo: false };
    g.style.transform = `translate(${inicio.x}px, ${inicio.y}px)`;
    app.fichas.set(clave, f);
    return f;
  }

  function mover(f, p) {
    f.g.style.transform = `translate(${p.x}px, ${p.y}px)`;
  }

  function ponerBadge(f, emoji) {
    if (!emoji) {
      if (f.badge) { f.badge.remove(); f.badge = null; }
      return;
    }
    if (!f.badge) f.badge = texto(11, -12, "", "badge", f.g);
    f.badge.textContent = emoji;
  }

  function sacarCliente(clave, f) {
    f.saliendo = true;
    const estabaAfuera = f.g.dataset.estado === "ESPERANDO_AFUERA";
    if (estabaAfuera) {
      f.g.classList.add("saliendo");
      mover(f, { x: -30, y: 300 });
      setTimeout(() => { f.g.remove(); app.fichas.delete(clave); }, 800);
      return;
    }
    mover(f, SALIDA);
    setTimeout(() => {
      f.g.classList.add("saliendo");
      mover(f, { x: 70, y: SALIDA.y });
    }, 700);
    setTimeout(() => { f.g.remove(); app.fichas.delete(clave); }, 1500);
  }

  // ------------------------------------------------------------------
  // Render de un snapshot
  // ------------------------------------------------------------------

  function render(s) {
    const G = app.G;
    if (!G) return;
    app.estado = s.estado;
    app.tServidor = s.t;
    app.tLocal = performance.now();

    const pill = $("estadoGeneral");
    pill.dataset.estado = s.estado;
    pill.textContent = s.estado.replace("_", " ");
    G.puerta.setAttribute("class", "puerta " + (s.estado === "ABIERTO" || s.estado === "SIN_INICIAR" ? "abierta" : "cerrada"));
    $("btnIniciar").disabled = s.estado !== "SIN_INICIAR";

    const st = s.stats;
    $("stAdentro").textContent = `${st.adentro}/${app.cfg.M * app.cfg.P}`;
    $("stAfuera").textContent = st.esperandoAfuera;
    $("stPagaron").textContent = st.pagaron;
    $("stSinComer").textContent = st.retiradosSinPedir + st.retiradosAfuera;
    $("stRecaudacion").textContent = dinero(st.recaudacion);

    renderDecoracion(s);
    renderClientes(s);
    renderEmpleados(s);
    renderPersonal(s);
    actualizarReloj();
  }

  /** Lo que cambia y no se mueve: estado de mesas, platos, comandas, grupos. */
  function renderDecoracion(s) {
    const G = app.G;
    const deco = $("capaDeco");
    limpiar(deco);

    const clientes = new Map(s.clientes.map((c) => [c.id, c]));

    for (const m of s.mesas) {
      const g = G.mesas.get(m.id);
      if (!g) continue;
      g.circulo.dataset.estado = m.estado;
      if (m.estado !== "LIBRE") {
        let etiqueta = app.desc.mesa[m.estado] || m.estado;
        // La mesa sigue "comiendo" hasta que se va el último; si ya nadie come, están pagando
        if (m.estado === "COMIENDO" && !m.asientos.some((cid) => cid != null && clientes.get(cid)?.estado === "COMIENDO")) {
          etiqueta = "Clientes pagando";
        }
        texto(g.cx, g.etiquetaY, etiqueta, "mesa-estado", deco);
      }
      if (m.estado === "SUCIA") texto(g.cx, g.cy + g.r * 0.4, "🍽️", "icono-mesa", deco);
      if (m.estado === "LIMPIANDO") texto(g.cx, g.cy + g.r * 0.4, "🧽", "icono-mesa", deco);
      // Plato servido delante de cada cliente
      m.asientos.forEach((cid, k) => {
        const c = cid == null ? null : clientes.get(cid);
        if (c && c.menu && c.estado === "COMIENDO" && g.asientos[k]) {
          texto(g.asientos[k].px, g.asientos[k].py, EMOJI_MENU[c.menu] || "🍽️", "plato-emoji", deco);
        }
      });
    }

    // Grupos que se forman en la sala de espera
    s.sala.forEach((gr, gi) => {
      const a = G.sala(gi, 0, s.sala.length);
      const b = G.sala(gi, Math.max(0, app.cfg.P - 1), s.sala.length);
      el("rect", {
        x: a.x - 15, y: a.y - 15, width: b.x - a.x + 30, height: 30, rx: 15,
        class: "grupo-marco" + (gr.completo ? " completo" : "")
      }, deco);
    });

    // Afuera: si hay más de los que entran, se indica cuántos
    if (s.afuera.length > MAX_AFUERA) texto(10, 592, `+${s.afuera.length - MAX_AFUERA} más`, "mas", deco);
    if (s.colaCaja.length > 24) texto(770, 588, `+${s.colaCaja.length - 24} más`, "mas", deco);

    // Hornallas: fuego y plato que se está cocinando
    const cocinando = new Map();
    for (const p of s.cocina) {
      for (const pl of p.platos) {
        if (pl.estado === "COCINANDO" && pl.cocinero != null) cocinando.set(pl.cocinero, pl.menu);
      }
    }
    s.cocineros.forEach((c, i) => {
      const h = G.cocineros[i];
      if (!h) return;
      const menu = cocinando.get(c.id);
      if (menu) {
        texto(h.fuego.x, h.fuego.y, "🔥", "fuego", deco);
        texto(h.plato.x, h.plato.y, EMOJI_MENU[menu] || "🍳", "fuego", deco);
      }
    });

    // Comandas en orden de llegada (FIFO). Verde = pedido completo para retirar.
    const visibles = s.cocina.slice(0, 8);
    visibles.forEach((p, i) => {
      const x = 770 + (i % 4) * 54, y = 236 + Math.floor(i / 4) * 46;
      el("rect", { x, y, width: 50, height: 40, rx: 5, class: "comanda" + (p.listo ? " lista" : "") }, deco);
      texto(x + 6, y + 15, "M" + p.mesa, "comanda-texto", deco);
      p.platos.slice(0, 5).forEach((pl, j) => {
        const color = pl.estado === "PENDIENTE" ? "var(--c-afuera)"
          : pl.estado === "COCINANDO" ? "var(--c-comida)" : "var(--ok)";
        el("circle", { cx: x + 9 + j * 8, cy: y + 29, r: 3.5, fill: color }, deco);
      });
    });
    if (s.cocina.length > 8) texto(770, 334, `+${s.cocina.length - 8} comandas`, "mas", deco);
  }

  function renderClientes(s) {
    const G = app.G;
    const pos = new Map();

    // Primero las sillas: la mesa sigue "anotando" al cliente hasta que se va
    // todo el grupo, así que la caja y la cola de pago tienen que pisar esto.
    for (const m of s.mesas) {
      const g = G.mesas.get(m.id);
      if (!g) continue;
      m.asientos.forEach((cid, k) => {
        if (cid != null && g.asientos[k]) pos.set(cid, g.asientos[k]);
      });
    }
    s.afuera.forEach((id, i) => pos.set(id, G.afuera(i)));
    s.sala.forEach((gr, gi) => gr.clientes.forEach((id, mi) => pos.set(id, G.sala(gi, mi, s.sala.length))));
    s.colaCaja.forEach((id, i) => pos.set(id, G.cola(i)));
    s.cajeros.forEach((c, i) => {
      const m = c.estado === "COBRANDO" && /(\d+)/.exec(c.detalle || "");
      if (m && G.cajeros[i]) pos.set(Number(m[1]), G.cajeros[i].cliente);
    });

    const presentes = new Set();
    const nuevas = [];
    for (const c of s.clientes) {
      const clave = "c" + c.id;
      presentes.add(clave);
      let destino = pos.get(c.id);
      if (!destino) {
        // Posición de respaldo según el estado (no debería pasar)
        destino = c.estado === "ESPERANDO_AFUERA" ? G.afuera(s.afuera.length)
          : c.estado === "EN_COLA_CAJA" || c.estado === "PAGANDO" ? G.cola(s.colaCaja.length)
          : { x: 215, y: 260 };
      }
      let f = app.fichas.get(clave);
      if (!f || f.saliendo) {
        f = crearFicha(clave, "cliente", 11, String(c.id), { x: -20, y: destino.y });
        nuevas.push([f, destino]);
      } else {
        mover(f, destino);
      }
      f.g.dataset.estado = c.estado;
      const menu = c.menu ? " · " + (EMOJI_MENU[c.menu] || "") + " " + menuNombre(c.menu) : "";
      f.titulo.textContent = `Cliente ${c.id}: ${app.desc.cliente[c.estado] || c.estado}${menu}`;
    }

    if (nuevas.length) {
      // Fuerza el estilo inicial para que la transición de entrada se vea
      $("capaFichas").getBoundingClientRect();
      for (const [f, d] of nuevas) mover(f, d);
    }

    for (const [clave, f] of app.fichas) {
      if (clave[0] === "c" && !presentes.has(clave) && !f.saliendo) sacarCliente(clave, f);
    }
  }

  function renderEmpleados(s) {
    const G = app.G;

    const porMesa = new Map();
    s.mozos.forEach((m, i) => {
      const clave = "z" + m.id;
      const f = app.fichas.get(clave) || crearFicha(clave, "mozo", 12, "Z" + m.id, G.estacion(i));
      const enMesa = m.mesa != null && m.estado !== "ESPERANDO" && m.estado !== "TERMINADO" && G.mesas.has(m.mesa);
      if (enMesa) {
        const n = porMesa.get(m.mesa) || 0;
        porMesa.set(m.mesa, n + 1);
        mover(f, G.mesas.get(m.mesa).mozo(n));
      } else {
        mover(f, G.estacion(i));
      }
      ponerBadge(f, ICONO_MOZO[m.estado]);
      f.g.classList.toggle("inactivo", m.estado === "TERMINADO");
      f.titulo.textContent = descEmpleado(m);
    });

    s.cocineros.forEach((c, i) => {
      const clave = "k" + c.id;
      const h = G.cocineros[i];
      if (!h) return;
      const f = app.fichas.get(clave) || crearFicha(clave, "cocinero", 12, "C" + c.id, h);
      mover(f, h);
      f.g.classList.toggle("inactivo", c.estado === "TERMINADO");
      f.titulo.textContent = descEmpleado(c);
    });

    s.cajeros.forEach((c, i) => {
      const clave = "y" + c.id;
      const h = G.cajeros[i];
      if (!h) return;
      const f = app.fichas.get(clave) || crearFicha(clave, "cajero", 12, "Y" + c.id, h);
      mover(f, h);
      ponerBadge(f, c.estado === "COBRANDO" ? "💲" : null);
      f.g.classList.toggle("inactivo", c.estado === "TERMINADO");
      f.titulo.textContent = descEmpleado(c);
    });
  }

  function descEmpleado(e) {
    let t = `${e.nombre}: ${app.desc.empleado[e.estado] || e.estado}`;
    if (e.mesa != null) t += ` (mesa ${e.mesa})`;
    if (e.detalle) t += ` · ${e.detalle}`;
    return t;
  }

  function menuNombre(codigo) {
    const m = app.cfg && app.cfg.menus.find((x) => x.codigo === codigo);
    return m ? m.nombre : codigo;
  }

  /** Lista compacta con el estado actual de cada empleado. */
  function renderPersonal(s) {
    const ul = $("personal");
    const filas = [];
    const agregar = (rol, e) => {
      let que = app.desc.empleado[e.estado] || e.estado;
      if (e.mesa != null) que += ` · M${e.mesa}`;
      if (e.detalle) que += ` · ${e.detalle}`;
      filas.push({ icono: ICONO_ROL[rol], nombre: e.nombre, que, activo: e.estado !== "ESPERANDO" && e.estado !== "TERMINADO" });
    };
    s.mozos.forEach((e) => agregar("MOZO", e));
    s.cocineros.forEach((e) => agregar("COCINERO", e));
    s.cajeros.forEach((e) => agregar("CAJERO", e));

    while (ul.children.length > filas.length) ul.lastChild.remove();
    filas.forEach((f, i) => {
      let li = ul.children[i];
      if (!li) {
        li = document.createElement("li");
        li.innerHTML = '<span class="icono"></span><span class="nombre"></span><span class="que"></span>';
        ul.appendChild(li);
      }
      li.classList.toggle("activo", f.activo);
      li.children[0].textContent = f.icono;
      li.children[1].textContent = f.nombre;
      li.children[2].textContent = f.que;
      li.title = f.que;
    });
  }

  // ------------------------------------------------------------------
  // Panel de eventos
  // ------------------------------------------------------------------

  function volcarEventos() {
    if (!app.logPendiente.length) return;
    const ol = $("eventos");
    const frag = document.createDocumentFragment();
    // Lo más nuevo arriba
    for (let i = app.logPendiente.length - 1; i >= Math.max(0, app.logPendiente.length - MAX_EVENTOS); i--) {
      const ev = app.logPendiente[i];
      const li = document.createElement("li");
      li.className = "nuevo";
      li.dataset.tipo = ev.tipo;
      const t = document.createElement("span");
      t.className = "t";
      t.textContent = (ev.t / 1000).toFixed(1) + "s";
      const d = document.createElement("span");
      const quien = document.createElement("span");
      quien.className = "quien";
      quien.textContent = ev.actor + " ";
      d.append(quien, document.createTextNode(ev.desc));
      li.append(t, d);
      frag.appendChild(li);
    }
    ol.insertBefore(frag, ol.firstChild);
    while (ol.children.length > MAX_EVENTOS) ol.lastChild.remove();
    app.logPendiente = [];
    $("cantEventos").textContent = app.cantEventos;
  }

  // ------------------------------------------------------------------
  // Llegada de eventos y ciclo de render
  // ------------------------------------------------------------------

  function recibirEvento(ev) {
    if (ev.seq <= app.ultimoSeq) return; // duplicado tras una reconexión
    app.ultimoSeq = ev.seq;
    app.cantEventos++;
    app.logPendiente.push(ev);
    if (app.logPendiente.length > MAX_EVENTOS) app.logPendiente.shift();
    app.pendiente = ev.snap;
    programar();
  }

  function programar() {
    if (app.frame) return;
    app.frame = requestAnimationFrame(() => {
      app.frame = 0;
      if (app.pendiente) {
        const s = app.pendiente;
        app.pendiente = null;
        render(s);
      }
      volcarEventos();
    });
  }

  function actualizarReloj() {
    if (!app.cfg) return;
    const T = app.cfg.T;
    let t = app.tServidor;
    if (app.estado === "ABIERTO") t += performance.now() - app.tLocal;
    const mostrado = Math.min(t, T);
    $("reloj").textContent = `${mmss(mostrado)} / ${mmss(T)}`;
    $("relojBarra").style.width = (app.estado === "SIN_INICIAR" ? 0 : Math.min(100, mostrado / T * 100)) + "%";
  }

  // ------------------------------------------------------------------
  // Resumen final
  // ------------------------------------------------------------------

  function mostrarResumen(r) {
    app.resumen = r;
    $("btnResumen").hidden = false;
    const st = r.stats;
    const ok = r.invariantes.filter((i) => i.ok).length;
    const cuerpo = $("modalCuerpo");
    cuerpo.innerHTML = "";

    const dl = document.createElement("dl");
    dl.className = "resumen";
    const filas = [
      ["Duración real", mmss(r.duracion) + ` (T = ${mmss(r.T)})`],
      ["Clientes que llegaron", st.generados],
      ["Comieron y pagaron", st.pagaron],
      ["No llegaron a entrar (puerta cerrada)", st.retiradosAfuera],
      ["Entraron pero no llegaron a pedir", st.retiradosSinPedir],
      ["Platos cocinados", st.platosCocinados],
      ["Estadía promedio", (st.estadiaPromedio / 1000).toFixed(1) + " s"],
      ["Recaudación", dinero(st.recaudacion)],
      ["Eventos mostrados", r.eventos]
    ];
    for (const [k, v] of filas) {
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = v;
      dl.append(dt, dd);
    }
    cuerpo.appendChild(dl);

    const det = document.createElement("details");
    det.className = "invariantes";
    const sum = document.createElement("summary");
    sum.textContent = `${r.todosOk ? "✅" : "❌"} Verificación: ${ok}/${r.invariantes.length} invariantes OK`;
    const ul = document.createElement("ul");
    for (const inv of r.invariantes) {
      const li = document.createElement("li");
      li.textContent = `${inv.ok ? "✔" : "✘"} ${inv.nombre} → ${inv.detalle}`;
      ul.appendChild(li);
    }
    det.append(sum, ul);
    cuerpo.appendChild(det);

    if (r.log) {
      const p = document.createElement("p");
      p.className = "log-ruta";
      p.textContent = "Log completo: " + r.log;
      cuerpo.appendChild(p);
    }
    $("modal").hidden = false;
  }

  // ------------------------------------------------------------------
  // Conexión con el servidor
  // ------------------------------------------------------------------

  function conectarEventos() {
    if (app.fuente) app.fuente.close();
    const fuente = new EventSource(app.ultimoSeq > 0 ? `/eventos?desde=${app.ultimoSeq}` : "/eventos");
    app.fuente = fuente;
    fuente.addEventListener("evento", (e) => {
      try {
        recibirEvento(JSON.parse(e.data));
      } catch (err) {
        console.error("Evento inválido", err);
      }
    });
    fuente.addEventListener("fin", (e) => {
      try {
        const r = JSON.parse(e.data);
        setTimeout(() => { if (!app.resumen) mostrarResumen(r); }, 900);
      } catch (err) {
        console.error("Resumen inválido", err);
      }
      fuente.close();
    });
    fuente.onerror = () => {
      if (fuente.readyState === EventSource.CLOSED && !app.resumen) setTimeout(conectarEventos, 2000);
    };
  }

  async function iniciar() {
    const boton = $("btnIniciar");
    boton.disabled = true;
    try {
      const resp = await fetch("/iniciar", { method: "POST" });
      if (!resp.ok) {
        const datos = await resp.json().catch(() => ({}));
        alert(datos.mensaje || "No se pudo iniciar la simulación");
      }
    } catch (err) {
      boton.disabled = false;
      alert("No se pudo contactar al servidor");
    }
  }

  /** Snapshot "vacío" para dibujar el local antes de iniciar. */
  function snapshotInicial(cfg) {
    const emp = (n, rol) => Array.from({ length: n }, (_, i) =>
      ({ id: i + 1, nombre: `${rol} ${i + 1}`, estado: "ESPERANDO", detalle: "", mesa: null, tareas: 0 }));
    return {
      seq: 0, t: 0, estado: "SIN_INICIAR", clientes: [],
      mozos: emp(cfg.Z, "Mozo"), cocineros: emp(cfg.C, "Cocinero"), cajeros: emp(cfg.Y, "Cajero"),
      mesas: Array.from({ length: cfg.M }, (_, i) => ({ id: i + 1, estado: "LIBRE", mozo: null, asientos: [] })),
      afuera: [], sala: [], colaMozos: [], cocina: [], colaCaja: [],
      stats: { generados: 0, entraron: 0, retiradosAfuera: 0, retiradosSinPedir: 0, pagaron: 0, adentro: 0,
               esperandoAfuera: 0, recaudacion: 0, platosPedidos: 0, platosCocinados: 0, estadiaPromedio: 0, porMenu: [] }
    };
  }

  function cargarConfig(cfg) {
    app.cfg = cfg;
    for (const e of cfg.estadosCliente) app.desc.cliente[e.codigo] = e.descripcion;
    for (const e of cfg.estadosMesa) app.desc.mesa[e.codigo] = e.descripcion;
    for (const e of cfg.estadosEmpleado) app.desc.empleado[e.codigo] = e.descripcion;
    construirMapa(cfg);

    const leyenda = [
      ["var(--c-afuera)", "Afuera"], ["var(--c-sala)", "Sala de espera"], ["var(--c-eligiendo)", "Eligiendo"],
      ["var(--c-mozo)", "Llamando al mozo"], ["var(--c-pidiendo)", "Pidiendo"], ["var(--c-comida)", "Esperando comida"],
      ["var(--c-comiendo)", "Comiendo"], ["var(--c-caja)", "Pagando"]
    ];
    const div = $("leyenda");
    div.innerHTML = "";
    for (const [color, nombre] of leyenda) {
      const s = document.createElement("span");
      const i = document.createElement("i");
      i.style.background = color;
      s.append(i, document.createTextNode(nombre));
      div.appendChild(s);
    }
    const s = document.createElement("span");
    s.textContent = `Z = mozo · C = cocinero · Y = cajero · M = mesa · puerta verde/roja = abierta/cerrada  (M=${cfg.M}, P=${cfg.P}, Z=${cfg.Z}, C=${cfg.C}, Y=${cfg.Y})`;
    div.appendChild(s);
  }

  // ------------------------------------------------------------------
  // Tema claro / oscuro
  // ------------------------------------------------------------------

  function alternarTema() {
    const actual = document.documentElement.getAttribute("data-theme")
      || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
    const nuevo = actual === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", nuevo);
    try { localStorage.setItem("tema", nuevo); } catch (e) { /* sin almacenamiento */ }
  }

  // ------------------------------------------------------------------
  // Arranque
  // ------------------------------------------------------------------

  async function arrancar() {
    try {
      const tema = localStorage.getItem("tema");
      if (tema) document.documentElement.setAttribute("data-theme", tema);
    } catch (e) { /* sin almacenamiento */ }

    $("btnIniciar").addEventListener("click", iniciar);
    $("btnTema").addEventListener("click", alternarTema);
    $("btnResumen").addEventListener("click", () => { if (app.resumen) mostrarResumen(app.resumen); });
    $("modal").addEventListener("click", (e) => { if (e.target.closest("[data-cerrar]")) $("modal").hidden = true; });
    document.addEventListener("keydown", (e) => { if (e.key === "Escape") $("modal").hidden = true; });
    setInterval(actualizarReloj, 250);

    try {
      const cfg = await (await fetch("/config")).json();
      cargarConfig(cfg);
      const estado = await (await fetch("/estado")).json();
      render(estado.snapshot || snapshotInicial(cfg));
      $("btnIniciar").disabled = estado.iniciada;
      conectarEventos();
      if (estado.resumen) setTimeout(() => { if (!app.resumen) mostrarResumen(estado.resumen); }, 900);
    } catch (err) {
      console.error(err);
      alert("No se pudo cargar el estado inicial. ¿Está corriendo el servidor?");
    }
  }

  arrancar();
})();
