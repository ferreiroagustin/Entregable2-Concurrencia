/* Presentación — navegación, escalado, resaltado de Java y diagramas animados. Sin dependencias. */
(() => {
  "use strict";

  const ANCHO = 1920;
  const ALTO = 1080;
  const deck = document.querySelector(".deck");
  const slides = Array.from(document.querySelectorAll(".slide"));
  const total = slides.length;
  // Las slides con la clase "anexo" quedan fuera del recorrido principal y de su numeración.
  const primerAnexo = slides.findIndex((sl) => sl.classList.contains("anexo"));
  const principal = primerAnexo < 0 ? total : primerAnexo;
  const etiqueta = (n) => (n < principal ? `${n + 1} / ${principal}` : `Anexo ${n - principal + 1} / ${total - principal}`);
  const barra = document.querySelector(".progreso > div");
  const num = document.querySelector(".hud .num");
  const panelNotas = document.querySelector(".notas");
  const ayuda = document.querySelector(".ayuda");
  const params = new URLSearchParams(location.search);
  const reducido = window.matchMedia("(prefers-reduced-motion: reduce)").matches || params.has("final");
  if (params.has("final")) document.documentElement.classList.add("sin-anim");
  if (params.get("tema") === "oscuro") document.documentElement.dataset.theme = "dark";

  let actual = 0;

  // ---------- Escalado del escenario 16:9 ----------
  function escalar() {
    const s = Math.min(window.innerWidth / ANCHO, window.innerHeight / ALTO);
    deck.style.transform = `scale(${s}) translate(-50%, -50%)`;
  }
  window.addEventListener("resize", escalar);
  escalar();

  // ---------- Índices para la aparición escalonada ----------
  slides.forEach((sl, n) => {
    sl.querySelectorAll(".rev").forEach((el, i) => {
      if (!el.style.getPropertyValue("--i")) el.style.setProperty("--i", i);
    });
    // pie con número
    const pie = document.createElement("div");
    pie.className = "pie";
    pie.innerHTML = `<span>Restaurante concurrente · Ferreiro · Bruschera</span><span>${etiqueta(n)}</span>`;
    if (!sl.classList.contains("portada")) sl.appendChild(pie);
  });

  // ---------- Largo de las aristas que se dibujan ----------
  document.querySelectorAll("svg .draw").forEach((p) => {
    try { p.style.setProperty("--len", Math.ceil(p.getTotalLength()) + 2); } catch (_) { /* sin layout */ }
  });

  // ---------- Resaltado mínimo de Java ----------
  const KW = new Set(("public private final static void synchronized while if else return try finally catch throws throw new " +
    "boolean int long true false null this break case default switch for record class interface volatile").split(" "));
  function resaltar(pre) {
    const src = pre.textContent;
    const re = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:[^"\\\n]|\\.)*")|(@\w+)|\b(\d[\d_]*)\b|\b([A-Za-z_]\w*)\b/g;
    let out = "";
    let ult = 0;
    let m;
    const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    while ((m = re.exec(src))) {
      out += esc(src.slice(ult, m.index));
      const [t] = m;
      if (m[1]) out += `<span class="t-com">${esc(t)}</span>`;
      else if (m[2]) out += `<span class="t-str">${esc(t)}</span>`;
      else if (m[3]) out += `<span class="t-an">${esc(t)}</span>`;
      else if (m[4]) out += `<span class="t-num">${t}</span>`;
      else if (KW.has(t)) out += `<span class="t-kw">${t}</span>`;
      else if (/^[A-Z]/.test(t)) out += `<span class="t-ty">${t}</span>`;
      else out += t;
      ult = re.lastIndex;
    }
    out += esc(src.slice(ult));
    // marcas ⟦…⟧ para resaltar un fragmento
    out = out.replace(/⟦/g, '<span class="hl">').replace(/⟧/g, "</span>");
    pre.innerHTML = out;
  }
  document.querySelectorAll("pre.code").forEach(resaltar);

  // ---------- Puntos que viajan por los caminos (mensajes) ----------
  const SVGNS = "http://www.w3.org/2000/svg";
  if (!reducido) {
    document.querySelectorAll("svg [data-flujo]").forEach((camino, k) => {
      if (!camino.id) camino.id = "flujo-" + k;
      const [color, dur, cant] = camino.dataset.flujo.split(",");
      const n = Number(cant || 2);
      for (let i = 0; i < n; i++) {
        const c = document.createElementNS(SVGNS, "circle");
        c.setAttribute("r", camino.dataset.r || "9");
        c.setAttribute("fill", color);
        c.setAttribute("class", "dot");
        c.setAttribute("opacity", "0");
        const am = document.createElementNS(SVGNS, "animateMotion");
        am.setAttribute("dur", dur + "s");
        am.setAttribute("repeatCount", "indefinite");
        am.setAttribute("begin", ((Number(dur) / n) * i + (k % 5) * 0.37).toFixed(2) + "s");
        am.setAttribute("rotate", "auto");
        const mp = document.createElementNS(SVGNS, "mpath");
        mp.setAttributeNS("http://www.w3.org/1999/xlink", "href", "#" + camino.id);
        mp.setAttribute("href", "#" + camino.id);
        am.appendChild(mp);
        const op = document.createElementNS(SVGNS, "animate");
        op.setAttribute("attributeName", "opacity");
        op.setAttribute("values", "0;1;1;0");
        op.setAttribute("keyTimes", "0;0.08;0.88;1");
        op.setAttribute("dur", dur + "s");
        op.setAttribute("repeatCount", "indefinite");
        op.setAttribute("begin", am.getAttribute("begin"));
        c.appendChild(am);
        c.appendChild(op);
        (camino.closest("svg").querySelector(".capa-puntos") || camino.parentNode).appendChild(c);
      }
    });
  }

  // ---------- Navegación ----------
  function ir(n, desdeHash) {
    n = Math.max(0, Math.min(total - 1, n));
    slides.forEach((sl, i) => {
      sl.classList.toggle("active", i === n);
      sl.classList.toggle("prev", i < n);
      sl.setAttribute("aria-hidden", i === n ? "false" : "true");
    });
    actual = n;
    barra.style.width = Math.min(1, (n + 1) / principal) * 100 + "%";
    num.textContent = etiqueta(n);
    document.title = `${n + 1}. ${slides[n].dataset.titulo || "Restaurante concurrente"} · Restaurante concurrente`;
    if (!desdeHash) history.replaceState(null, "", "#" + (n + 1));
    pintarNotas();
  }
  const siguiente = () => ir(actual + 1);
  const anterior = () => ir(actual - 1);

  function pintarNotas() {
    const aside = slides[actual].querySelector("aside.notes");
    panelNotas.innerHTML = `<h4>Notas del orador · ${actual + 1}. ${slides[actual].dataset.titulo || ""}</h4>` +
      (aside ? aside.innerHTML : "<p>Sin notas.</p>");
  }

  function pantallaCompleta() {
    if (!document.fullscreenElement) document.documentElement.requestFullscreen?.().catch(() => {});
    else document.exitFullscreen?.();
  }

  document.addEventListener("keydown", (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    switch (e.key) {
      case "ArrowRight": case "ArrowDown": case "PageDown": case " ": case "Enter":
        e.preventDefault(); siguiente(); break;
      case "ArrowLeft": case "ArrowUp": case "PageUp": case "Backspace":
        e.preventDefault(); anterior(); break;
      case "Home": ir(0); break;
      case "End": ir(actual < principal - 1 ? principal - 1 : total - 1); break;
      case "f": case "F": pantallaCompleta(); break;
      case "n": case "N": panelNotas.hidden = !panelNotas.hidden; break;
      case "t": case "T":
        document.documentElement.dataset.theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark"; break;
      case "?": case "h": case "H": ayuda.hidden = !ayuda.hidden; break;
      case "Escape": ayuda.hidden = true; panelNotas.hidden = true; break;
      default: break;
    }
  });

  document.querySelector(".viewport").addEventListener("click", (e) => {
    if (e.target.closest("a, button")) return;
    if (window.getSelection && String(window.getSelection()).length) return;
    if (e.clientX < window.innerWidth * 0.25) anterior(); else siguiente();
  });
  document.querySelector("[data-acc=prev]").addEventListener("click", anterior);
  document.querySelector("[data-acc=next]").addEventListener("click", siguiente);
  document.querySelector("[data-acc=full]").addEventListener("click", pantallaCompleta);
  document.querySelector("[data-acc=notas]").addEventListener("click", () => { panelNotas.hidden = !panelNotas.hidden; });
  document.querySelector("[data-acc=ayuda]").addEventListener("click", () => { ayuda.hidden = !ayuda.hidden; });
  ayuda.addEventListener("click", () => { ayuda.hidden = true; });

  // el HUD aparece solo al mover el mouse
  let ocultarHud = null;
  window.addEventListener("mousemove", () => {
    document.body.classList.add("hud-visible");
    clearTimeout(ocultarHud);
    ocultarHud = setTimeout(() => document.body.classList.remove("hud-visible"), 2200);
  });

  // gesto táctil
  let toqueX = null;
  window.addEventListener("touchstart", (e) => { toqueX = e.touches[0].clientX; }, { passive: true });
  window.addEventListener("touchend", (e) => {
    if (toqueX === null) return;
    const dx = e.changedTouches[0].clientX - toqueX;
    if (Math.abs(dx) > 50) (dx < 0 ? siguiente : anterior)();
    toqueX = null;
  });

  window.addEventListener("hashchange", () => {
    const n = parseInt(location.hash.slice(1), 10);
    if (!Number.isNaN(n) && n - 1 !== actual) ir(n - 1, true);
  });

  if (params.has("notas")) panelNotas.hidden = false;
  const inicial = parseInt(location.hash.slice(1), 10);
  ir(Number.isNaN(inicial) ? 0 : inicial - 1, true);
})();
