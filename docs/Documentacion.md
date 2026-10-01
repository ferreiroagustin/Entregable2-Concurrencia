# Entregable 2 — Concurrencia

## Simulador concurrente de restaurante

| | |
|---|---|
| **Materia** | Programación Avanzada 2026 |
| **Docentes** | Ing. Martha Giménez · Ing. Alejandro Greco |
| **Integrantes** | Ferreiro · Bruschera |
| **Fecha** | Octubre de 2026 |
| **Lenguaje** | Java 17 (JDK Eclipse Temurin), sin dependencias externas |

---

## 1. Introducción y alcance

Este trabajo es un programa en Java que simula un restaurante. Los **clientes**, **mozos**, **cocineros** y **cajeros** se ejecutan de forma concurrente, cada uno en su propio hilo, durante un tiempo **T**. Al cumplirse T el restaurante **cierra de forma ordenada**:

- se cierran las puertas;
- los clientes que todavía no hicieron su pedido se retiran;
- los que ya pidieron terminan normalmente;
- el personal sigue trabajando hasta que no quedan clientes;
- por último terminan todos los hilos y se liberan todos los recursos.

Además del simulador, se entrega lo siguiente:

- **Hilo Display.** Consume, en orden, una cola de notificaciones. Por cada cambio muestra el estado **completo** del restaurante en el momento en que se produjo: clientes, mozos, cocineros, cajeros, mesas y colas.
- **Log en archivo de texto** (`logs/simulacion-<fecha>.log`) con todas las acciones y el estado completo después de cada una.
- **Interfaz web en vivo** (HTML/CSS/JS sin dependencias), servida con el `HttpServer` del JDK mediante *Server-Sent Events*. Muestra un mapa del restaurante en movimiento con todos los actores y la lista de eventos.
- **Verificación automática de invariantes** al final de cada simulación, y un **modo de estrés** (`--repeticiones n`) que corre n simulaciones seguidas con tiempos muy cortos para buscar deadlocks y condiciones de carrera.

El grupo tiene 2 integrantes, así que **no se implementaron los requerimientos extra** (sartenes y Take Away).

### Constantes

Todas están en `restaurante.config.Config`, como `static final`.

| Constante | Valor por defecto | Significado |
|---|---|---|
| T | 60 000 ms | Duración de la simulación |
| M / P | 4 / 2 | Mesas / personas por mesa (capacidad P·M = 8) |
| Z / C / Y | 2 / 2 / 1 | Mozos / cocineros / cajas |
| TP | 300–1 200 ms | Tiempo entre llegadas de clientes |
| TM | 1 000–3 000 ms | Elegir el menú |
| TQ | 3 000–6 000 ms | Comer |
| TZ | 500–1 200 ms | Anotar el pedido y llevarlo a la cocina |
| TR | 500–1 000 ms | Retirar los platos y servirlos |
| TL | 800–1 500 ms | Levantar los platos y limpiar |
| TY | 500–1 000 ms | Cobrar |
| TC | depende del menú | Por ejemplo, Milanesa 2 000–4 000, Ensalada 800–1 500, Flan 500–1 000 |

---

## 2. Arquitectura

### 2.1 Paquetes

| Paquete | Clases | Responsabilidad |
|---|---|---|
| `restaurante` | `Main` | Arranque, lectura de los flags (`--consola`, `--t`, `--repeticiones`, `--sin-navegador`) |
| `config` | `Config`, `Parametros`, `Rango` | Constantes y parámetros inmutables de una corrida |
| `modelo` | `Mesa`, `Menu`, `Pedido`, `Plato`, `Eleccion`, `TareaMozo`, `TipoTarea`, `Pago`, `Ticket`, enums de estado | Entidades del dominio. `Mesa` es un **monitor** |
| `recursos` | `Restaurante`, `SalaDeEspera`, `Cocina`, `Caja`, `ColaMozos`, `Contadores` | Recursos compartidos y sus mecanismos de sincronización |
| `actores` | `Cliente`, `Mozo`, `Cocinero`, `Cajero`, `GeneradorClientes` | Los `Runnable` que ejecutan los pools |
| `estado` | `EstadoRestaurante`, `Vista`, `Snapshot`, `Evento`, `Actor` | Registro del estado visible y punto único de notificación |
| `simulacion` | `Simulacion`, `FabricaHilos`, `Aleatorio`, `Resultado`, `Invariante` | Coordinador: pools, temporizador, cierre e invariantes |
| `display` | `Display`, `LogArchivo`, `FormatoTexto`, `Json` | Hilo de display, log de texto y serialización JSON hecha a mano |
| `web` | `ServidorWeb`, `CanalSSE` | Servidor HTTP del JDK y canal SSE hacia el navegador |

### 2.2 Hilos y pools

| Pool (`Executors`) | Hilos | Tarea |
|---|---|---|
| `newFixedThreadPool(Z)` | Z | `Mozo` |
| `newFixedThreadPool(C)` | C | `Cocinero` |
| `newFixedThreadPool(Y)` | Y | `Cajero` |
| `newCachedThreadPool()` | uno por cliente activo | `Cliente` |
| `newSingleThreadExecutor()` | 1 | `GeneradorClientes` |
| `newSingleThreadExecutor()` | 1 | `Display` |
| `newScheduledThreadPool(1)` | 1 | Temporizador: a los T ms libera el `CountDownLatch` de cierre |
| `newCachedThreadPool()` (hilos daemon) | variable | Peticiones HTTP y conexiones SSE (solo en modo web) |

Todos los pools usan `FabricaHilos`, que:

- les pone nombres legibles (`mozo-1`, `cliente-7`…);
- les instala un `UncaughtExceptionHandler` que registra cualquier excepción no capturada en la consola y en el log;
- guarda los hilos creados, para comprobar al final que **ninguno quedó vivo**.

### 2.3 Flujo de mensajes

```
                 GeneradorClientes (cada TP)
                          │ execute()
                          ▼
   ┌──────────── Cliente (pool de clientes) ─────────────────────────────────────┐
   │ Semaphore(P·M) ─► SalaDeEspera (monitor) ─► Mesa (monitor + CyclicBarrier)  │
   └──────────────────────────────────────┬──────────────────────────┬───────────┘
            acción de la barrera:          │                          │ CompletableFuture<Ticket>
            TOMAR_PEDIDO                   │ LIMPIAR (último que      ▼
                 │                         │  se va)          Caja: LinkedBlockingQueue<Pago>
                 ▼                         ▼                          │ take()
        ColaMozos: PriorityBlockingQueue<TareaMozo>  ◄── SERVIR ──┐    ▼
                 │ take()                                        │  Cajero ×Y
                 ▼                                               │
              Mozo ×Z ── recibirPedido() ──► Cocina (ReentrantLock + Condition)
                                                   │ tomarPlato()/platoListo()
                                                   ▼
                                              Cocinero ×C ──────┘ (si el pedido quedó completo)

 Todos los actores ──notificar()──► EstadoRestaurante (RW lock) ──► LinkedBlockingQueue<Evento>
                                                                          │ take() en orden
                                                                          ▼
                                                     Display ──► consola · log · CanalSSE ──► navegador
```

### 2.4 Diagrama de estados de la mesa

```
           sentarse()                barrera (todos eligieron)        tomarPedido()
  LIBRE ─────────────► ELIGIENDO ─────────────────────────► ESPERANDO_MOZO ─────────► TOMANDO_PEDIDO
    ▲                     │                                    │                          │ pedidoEnCocina()
    │                     │ cierre (no pidió)                  │ cierre (no pidió)        ▼
    │                     └──────────────┐   ┌─────────────────┘                  ESPERANDO_COMIDA
    │                                    ▼   ▼                                          │ empezarAServir()
    │                       (los clientes se retiran sin pedir)                         ▼
    │ terminarLimpieza()                 │                                          SIRVIENDO
    │                                    │ retirarse() del último                       │ servir()
 LIMPIANDO ◄── empezarLimpieza() ── SUCIA ◄────────────────────────────── COMIENDO ◄────┘
                                              retirarse() del último (después de pagar)
```

### 2.5 Ciclo de vida de cada actor

**Cliente** (`Cliente.run`):

1. Llega y espera afuera con `Restaurante.esperarParaEntrar()`, que hace `tryAcquire` con timeout en un bucle mientras el restaurante esté abierto.
2. Entra con `SalaDeEspera.ingresar()` y queda en un grupo de P en formación.
3. Espera mesa en `SalaDeEspera.esperarMesa()` (`wait`).
4. Se sienta con `Mesa.sentarse()`.
5. Elige su menú con `Mesa.elegir()`.
6. Espera a sus compañeros en la barrera (`Mesa.esperarCompaneros()`).
7. Espera al mozo con `Mesa.esperarMozo()`.
8. Espera la comida con `Mesa.esperarComida()`.
9. Come durante TQ.
10. Paga con `Caja.pagar()` y espera el `Future`.
11. Sale con `Mesa.retirarse()` y `Restaurante.salir()`, que libera el permiso. Si fue el último de la mesa, encola `LIMPIAR`.

**Mozo** (`Mozo.run`) hace `take()` sobre la `PriorityBlockingQueue` y atiende la tarea que le toca:

- **TOMAR_PEDIDO:**
  1. `Mesa.tomarPedido()`: si devuelve `null`, la mesa se canceló y la tarea se descarta.
  2. Anota durante TZ.
  3. `Mesa.pedidoEnCocina()`.
  4. `Cocina.recibirPedido()`.
- **SERVIR:** `Mesa.empezarAServir()`, espera TR y llama a `Mesa.servir()`.
- **LIMPIAR:** `Mesa.empezarLimpieza()`, espera TL, llama a `Mesa.terminarLimpieza()` y después a `SalaDeEspera.mesaLiberada()`.
- **FIN:** termina (poison pill).

**Cocinero** (`Cocinero.run`):

1. `Cocina.tomarPlato()`.
2. Cocina durante el TC del menú.
3. `Cocina.platoListo()`. Si el pedido quedó completo, encola `SERVIR`.
4. Si `tomarPlato()` devuelve `null`, la cocina cerró y no queda nada por cocinar, así que el cocinero termina.

**Cajero** (`Cajero.run`):

1. `Caja.tomar()`.
2. Cobra durante TY.
3. `Caja.cobrar()`, que completa el `CompletableFuture` del cliente.
4. Termina con la poison pill `Pago.finDeTurno()`.

---

## 3. Herramientas utilizadas y justificación

| Herramienta | Dónde se usa (clase · método) | Por qué |
|---|---|---|
| **`synchronized` + `wait` / `notifyAll`** (monitor) | `Mesa`: `sentarse`, `elegir`, `esperarMozo`, `esperarComida`, `tomarPedido`, `pedidoEnCocina`, `servir`, `retirarse`, `terminarLimpieza`, `cerrar`… · `SalaDeEspera`: `ingresar`, `esperarMesa`, `mesaLiberada`, `cerrar` | Cada mesa tiene varias condiciones: "ya vino el mozo", "ya está servida", "se canceló por el cierre". Varios hilos (clientes, mozos y el coordinador) las esperan y las modifican. El monitor junta el estado y la espera en un solo lugar, y resuelve de forma natural la carrera entre el mozo y el cierre: gana el primero que entra al monitor. Se usa `notifyAll` (y no `notify`) porque en la misma mesa esperan hilos que buscan condiciones distintas. Todo `wait` está dentro de un `while`, para cubrir los despertares espurios y las notificaciones que no son para ese hilo. |
| **`wait(timeout)` como espera interrumpible** | `Mesa.elegir` (con `TimeUnit.timedWait`) | Elegir el menú tarda TM, pero si el restaurante cierra mientras tanto el cliente tiene que enterarse **enseguida**. Un `sleep` no se puede despertar sin interrumpir el hilo. En cambio, `wait(timeout)` dentro de un bucle se despierta con el `notifyAll` de `Mesa.cerrar()`. |
| **`ReentrantLock` (justo) + `Condition`** | `Cocina`: `recibirPedido`, `tomarPlato` (`hayPlatos.await()`), `platoListo`, `cerrar` (`signalAll`) | Lo que hace el cocinero no es "sacar el primero de la cola". Es "buscar, en el **primer pedido que tenga uno**, un plato **sin cocinar** y marcarlo como suyo", sin sacar el pedido de la cola, porque varios cocineros pueden repartirse los platos de un mismo pedido. Esa búsqueda más el marcado tienen que ser atómicos, y una `BlockingQueue` no los resuelve. El lock *fair* evita que un cocinero acapare la cocina, y la `Condition` con nombre deja el código más claro que un `wait` genérico. `unlock()` siempre va en un `finally`. |
| **`ReentrantReadWriteLock`** | `EstadoRestaurante`: `notificar` (write lock) y `snapshotActual` (read lock) | Muchos escritores (todos los actores) y lectores esporádicos (el servidor web, con `/estado`, y la verificación final). El read lock permite que varios lectores lean a la vez sin bloquearse entre ellos. Bajo el write lock se aplica el cambio, se toma la foto y se encola el evento, todo en forma atómica. Eso garantiza que **el orden de la cola sea el orden real de los cambios**. |
| **`Semaphore(P·M, true)`** | `Restaurante.esperarParaEntrar` (`tryAcquire` con timeout) y `Restaurante.salir` (`release`) | Modela la capacidad del local ("pueden ingresar P·M clientes, el resto espera afuera"). Es *fair*: un permiso liberado va al primero de la fila de espera del semáforo. Se usa `tryAcquire(timeout)` dentro de un bucle para que el cliente note el cierre y se vaya. Consecuencia aceptada: cuando a un cliente se le vence el timeout (100 ms) vuelve a la fila por detrás, así que el orden de entrada de los que esperan afuera es *aproximadamente* el de llegada, no estrictamente FIFO (el enunciado no pide orden para los de afuera). `release` va en el `finally` de `Cliente.run`, así el permiso **siempre** se devuelve. |
| **Clases atómicas** | `Restaurante.abierto` (`AtomicBoolean`, con `compareAndSet` en `cerrarPuertas`) · `Contadores` (`AtomicInteger`: generados, entraron, pagaron…) · `Pedido.platosPendientes` (`AtomicInteger`) · `Caja.recaudacion` (`AtomicLong`) · `ColaMozos.secuencia` (`AtomicLong`) · `Simulacion.iniciada` (`AtomicBoolean`) | Son contadores y banderas que se actualizan desde muchos hilos con una sola operación indivisible (CAS), sin locks y con visibilidad garantizada. `platosPendientes.decrementAndGet() == 0` asegura que **un solo** cocinero, el que termina el último plato, avise a los mozos. |
| **`CyclicBarrier(P, acción)`** | Una por mesa: `Mesa.esperarCompaneros` (`await`) y acción de barrera `Mesa.llamarMozo` | "Luego de que los P clientes seleccionan el menú llaman a un mozo." Es exactamente una barrera de P partes. La **acción de barrera** la ejecuta el último en llegar, y es la que llama al mozo. Es **cíclica**, así que la misma barrera sirve para cada grupo que ocupa la mesa. Si se rompe (`BrokenBarrierException`), la ocupación se cancela y la barrera se resetea al limpiar la mesa. |
| **`CountDownLatch`** | `Simulacion.senalCierre` (1): lo libera el temporizador en T, y lo esperan el coordinador y `GeneradorClientes` (`await(tp)` en lugar de `sleep`) · `Simulacion.empleadosTerminados` (Z+C+Y): cada empleado hace `countDown` en su `finally` | Son señales de un solo uso. La del cierre despierta **al instante** al generador, que estaba esperando TP. La de los empleados es una barrera de finalización: el coordinador sabe con certeza cuándo terminaron todos. |
| **Pasaje de mensajes: `BlockingQueue`** | `ColaMozos` (`PriorityBlockingQueue<TareaMozo>`) · `Caja` (`LinkedBlockingQueue<Pago>`, cola única) · `EstadoRestaurante.colaEventos` (`LinkedBlockingQueue<Evento>`) · `CanalSSE.Suscriptor` (`LinkedBlockingQueue<String>`, una por navegador) | Desacoplan productores de consumidores sin compartir estado mutable. `take()` deja al consumidor bloqueado sin consumir CPU cuando no hay trabajo ("si no hay nada que hacer se quedan esperando"). La cola de mozos es de **prioridad**, porque las tareas no valen lo mismo (ver 5.1). Todas son **ilimitadas**: `put`/`add` nunca bloquean, y eso es clave para evitar deadlocks (ver 6.1). |
| **`Executors`** (pools de hilos) | `Simulacion.correr`: `newFixedThreadPool` (Z, C, Y), `newCachedThreadPool` (clientes), `newSingleThreadExecutor` (generador, display), `newScheduledThreadPool` (temporizador) · `ServidorWeb` (pool daemon) | Separan la creación y gestión de hilos de la lógica de cada actor. Los empleados son una cantidad fija y conocida, de ahí el pool fijo. Los clientes son una cantidad variable, de ahí el pool *cached*. El Display tiene que ser un hilo único para garantizar el orden. `shutdown()` + `awaitTermination()` dan un cierre controlado. |
| **`CompletableFuture`** (Future/Promise) | `Caja.pagar` devuelve el futuro · `Cajero` lo completa en `Caja.cobrar` · `Cliente` hace `get()` | El cliente queda esperando **su** ticket, sin tener que buscarlo en una estructura compartida. |
| **`volatile`** | `EstadoRestaurante.inicioNanos`, `Simulacion.resultado`, `Display.ultimaSecuencia` | Variables que escribe un hilo y leen otros, sin necesidad de exclusión mutua: una sola escritura, o un solo escritor con lectores. `volatile` garantiza que no se lean valores viejos de la caché. |
| **`CopyOnWriteArrayList`** | `CanalSSE.suscriptores`, `FabricaHilos.hilos` | Muchas lecturas (recorrer para publicar) y pocas escrituras (cuando se conecta un navegador o se crea un hilo). Se puede iterar sin lock y sin `ConcurrentModificationException`. |
| **Objetos inmutables** | `Snapshot` (records con `List.copyOf`), `Evento`, `Parametros`, `Rango`, `TareaMozo`, `Pago`, `Ticket`, `Eleccion` | Un objeto inmutable es seguro para hilos **por construcción**. El Display, el log y el servidor leen el snapshot sin ningún lock. |
| **`ThreadLocalRandom`** | `Aleatorio` | Cada hilo tiene su propio generador, así que no hay contención ni estado compartido. |

### 3.1 Alternativas que se descartaron

- **Un único lock global para todo el restaurante.** Es correcto pero serializa a todos los actores, y la concurrencia desaparece. Se prefirió un lock por recurso: cada mesa, la sala, la cocina.
- **`BlockingQueue` para la cocina.** No permite tomar *un plato* de un pedido dejando el pedido en la cola para los otros cocineros. Se usó `ReentrantLock` + `Condition`.
- **`Thread.sleep` mientras se elige el menú.** No se puede despertar con el cierre. Se usó `wait(timeout)`.
- **`acquire()` bloqueante en la puerta.** Un cliente que espera afuera nunca se enteraría del cierre. Se usó `tryAcquire(timeout)` en un bucle.
- **Que el Display lea los objetos reales (mesas, cocina).** Lo obligaría a tomar sus locks y a anidarlos, y podría mostrar estados intermedios. Se usó una vista propia con fotos inmutables.
- **`Phaser`.** Es más flexible, pero innecesario: el número de partes por mesa es fijo (P), y `CyclicBarrier` con su acción de barrera expresa la regla exacta del enunciado.
- **`System.exit` para terminar.** Taparía hilos colgados. El programa termina porque **todos los hilos no-daemon terminan**, y eso se verifica.

---

## 4. Patrones de diseño concurrente

### 4.1 Productor-Consumidor (patrón principal)

**Dónde se usa.** Aparece en cuatro lugares:

| Productores | Búfer | Consumidores |
|---|---|---|
| Mesas (acción de barrera), cocineros (pedido listo), clientes (mesa sucia) | `ColaMozos` — `PriorityBlockingQueue<TareaMozo>` | Z mozos |
| Clientes | `Caja` — `LinkedBlockingQueue<Pago>` (cola **única**) | Y cajeros |
| Mozos | `Cocina` — lista FIFO de pedidos con `ReentrantLock` + `Condition` | C cocineros |
| Todos los actores | `LinkedBlockingQueue<Evento>` | 1 Display |

**Por qué se eligió.** El enunciado describe exactamente esta situación:

- "los mozos [...] si los clientes no llaman [...] se quedan esperando";
- "existe una única cola de clientes y los cajeros van cobrando al primero de la lista";
- "un hilo [...] que reciba las notificaciones de cada cambio, en una cola, y los vaya procesando en orden".

El patrón cumple esos requisitos y además:

1. **Desacopla.** El productor no sabe qué consumidor va a atender el mensaje. Así "los mozos no tienen asignadas mesas específicas": cualquier mozo libre toma cualquier tarea.
2. **No hace espera activa.** `take()` bloquea al consumidor sin gastar CPU hasta que llega un mensaje.
3. **Balancea la carga.** Con Y cajeros sobre una sola cola, el primer cajero libre atiende al primer cliente de la fila, que es la política "fila única" del enunciado.
4. **Ordena.** Un único consumidor de eventos (el Display) sobre una cola FIFO garantiza que los cambios se muestren en el orden en que ocurrieron.
5. **Permite apagar con *poison pills*.** Un mensaje especial al final de la cola hace que el consumidor termine **después** de procesar todo lo anterior.
6. **Evita deadlocks.** Con colas ilimitadas, producir nunca bloquea. Un productor puede encolar mientras tiene el monitor de una mesa sin riesgo.

### 4.2 Monitor

`Mesa` y `SalaDeEspera` encapsulan su estado y lo protegen con su lock intrínseco. Las esperas por condición se hacen con `wait`/`notifyAll`. Ningún otro objeto toca ese estado directamente.

### 4.3 Working Threads (pool de hilos)

Los actores son tareas (`Runnable`) que ejecutan pools de `Executors`. La cantidad de hilos de trabajo es fija para el personal (Z, C, Y) y elástica para los clientes.

### 4.4 Barrier

Cada mesa tiene una `CyclicBarrier(P, acción)`: nadie llama al mozo hasta que eligieron los P. La acción de barrera hace la llamada una sola vez.

### 4.5 Future / Promise

`Caja.pagar` devuelve un `CompletableFuture<Ticket>` (la *promesa*) que completa el cajero. El cliente espera el resultado con `get()`.

### 4.6 Poison Pill

- **Mozos:** `TareaMozo.fin()`, con la prioridad **más baja**, para que antes se procesen todas las limpiezas pendientes.
- **Cajeros:** `Pago.finDeTurno()`.
- **Display:** `Evento.FIN`.

Cada consumidor termina cuando la recibe, y como la pill va al final, primero procesa todo lo que tenía pendiente. Para los cocineros se usa la variante "bandera de cierre + `signalAll`" (`Cocina.cerrar`), porque su "cola" no es una `BlockingQueue`.

### 4.7 Objeto inmutable (Snapshot)

Cada evento lleva una foto `Snapshot` inmutable. Es la manera más simple de compartir datos entre hilos de forma segura: no hay nada que sincronizar.

---

## 5. Decisiones de funcionamiento del simulador

### 5.1 Interpretaciones del enunciado

- **"Realizó su pedido" significa que el mozo ya lo tomó.** Es decir, `Mesa.tomarPedido()` ya se ejecutó. Hasta ese momento, aunque el cliente haya elegido y la mesa haya llamado al mozo, el cierre lo hace retirarse sin pedir. Se eligió así porque el pedido se *realiza* cuando el mozo llega ("cuando el mozo llega hacen el pedido").
- **Capacidad P·M y mesas sucias.** La puerta deja entrar P·M personas, y un cliente libera su lugar **cuando sale del local** (después de pagar). Por eso una mesa sucia que todavía no se limpió no reduce la capacidad: los que entren esperan en la sala hasta que haya una mesa libre.
- **Formación de grupos.** Los clientes se agrupan de a P **en orden de entrada** (FIFO). Un grupo completo espera la primera mesa libre (FIFO de grupos). El grupo incompleto "se queda esperando", como pide el enunciado. La sala lleva su propia lista de mesas libres y asigna la mesa al grupo completo; después cada integrante se sienta en el asiento que le corresponde.
- **Prioridades del mozo: SERVIR > TOMAR_PEDIDO > LIMPIAR.**
  - Servir es lo más urgente: la comida está lista y se enfría.
  - Tomar pedidos alimenta a la cocina.
  - Limpiar puede esperar un poco: la capacidad de la puerta no depende de eso.
  - Dentro de la misma prioridad el orden es FIFO, por número de secuencia.
- **Riesgo de inanición de LIMPIAR.** Con prioridades siempre existe el riesgo de que una tarea de prioridad baja no se atienda nunca. Acá está **acotado**:
  - las tareas SERVIR y TOMAR_PEDIDO son finitas, porque como mucho hay una por mesa ocupada;
  - una mesa sucia no genera nuevos pedidos;
  - por lo tanto, cuando se agotan, los mozos limpian;
  - en el cierre, la poison pill tiene la prioridad más baja de todas, así que primero se termina toda la limpieza.
- **Varios cocineros trabajan en el mismo pedido.** "Un cocinero libre toma del primer pedido de la cola algún menú sin cocinar." Si el primer pedido tiene varios platos sin empezar, otro cocinero libre toma el siguiente plato **del mismo pedido**. Solo cuando no quedan platos pendientes en el primero se pasa al segundo. El cocinero que completa el último plato es el único que avisa a los mozos (`AtomicInteger` en `Pedido`).
- **Un plato por cliente.** El pedido de una mesa tiene P platos, y cada cliente paga su propio plato.
- **El cliente deja la mesa después de pagar.** "Se retiran de forma individual. Cuando se van los P clientes la mesa queda libre": el último que se va deja la mesa SUCIA y pide que la limpien. Cuando el mozo termina de limpiar, la mesa queda LIBRE.
- **Mesas canceladas por el cierre.** Sus clientes se van sin pedir. La mesa queda SUCIA y un mozo la limpia igual, así que al final todas las mesas terminan LIBRES.

### 5.2 Orden de cierre (`Simulacion.correr`)

1. **Se cierran las puertas.** `Restaurante.cerrarPuertas()` pone `abierto = false`. El generador deja de crear clientes, porque lo despierta el latch. Los que esperan afuera salen de su bucle `tryAcquire` y se retiran. Si alguno consiguió permiso justo en ese instante, `SalaDeEspera.ingresar()` verifica `abierto` dentro del monitor, lo rechaza y el cliente devuelve el permiso. Desde este momento la sala tampoco asigna mesas nuevas (`asignarMesas` verifica `abierto`): si un mozo libera una mesa entre el cierre de las puertas y el cierre de la sala, ningún grupo se sienta solo para tener que irse.
2. **Se cierra la sala de espera.** `SalaDeEspera.cerrar()` hace `notifyAll`: los grupos incompletos y los que esperaban mesa se retiran sin pedir.
3. **Se cierra cada mesa.** Con `Mesa.cerrar()`, si el pedido no fue tomado, la ocupación se cancela y se hace `notifyAll`:
   - los clientes que estaban eligiendo cortan la espera;
   - todos pasan por la barrera (los P siempre llegan, así que la barrera no se traba) y la acción de barrera ve la cancelación y **no** llama al mozo;
   - los que esperaban al mozo se despiertan y se van sin pedir.
4. **Los que ya pidieron terminan normalmente:** comen, pagan y se van. El coordinador hace `shutdown()` + `awaitTermination()` del pool de clientes.
5. **El personal termina.** Cuando ya no quedan clientes:
   - Z poison pills a los mozos (de prioridad más baja, así primero limpian lo pendiente);
   - `Cocina.cerrar()` con `signalAll` a los cocineros;
   - Y poison pills a los cajeros;
   - el coordinador espera con `empleadosTerminados.await()`.
6. **Se apagan todos los pools** (`shutdown` + `awaitTermination`).
7. **El Display vacía su cola.** Se encola `Evento.FIN`; el Display procesa todo lo anterior y termina.
8. **Verificación de invariantes**, que se imprime y se escribe en el log.
9. **Se cierra el log.** En modo web también se publica el evento SSE `fin` con el resumen.

**La carrera "el mozo toma el pedido" contra "se cierra la mesa".** Las dos operaciones son `synchronized` sobre la **misma** mesa, así que se ejecutan una después de la otra:

- Si entra primero `tomarPedido`, se marca `pedidoTomado = true` y el cierre ya no cancela nada: los clientes comen y pagan.
- Si entra primero `cerrar`, se marca `cancelada = true`. Cuando el mozo llega, `tomarPedido` devuelve `null` y la tarea se descarta.

En el monitor no hay un estado intermedio posible. Las dos ramas se probaron con un caso dirigido, además de con el modo estrés.

---

## 6. Decisiones de diseño e implementación

### 6.1 Cómo se evitan los deadlocks

Para que haya un deadlock se necesitan cuatro condiciones a la vez (Coffman). Atacamos la de **espera circular** con estas reglas:

1. **Nunca se toman dos locks de la aplicación anidados.**
   - Un monitor de mesa nunca toma el de otra mesa ni el de la sala.
   - La sala no toca el monitor de las mesas: tiene su propia lista de mesas libres.
   - El mozo avisa a la sala (`mesaLiberada`) **después** de salir del monitor de la mesa.
   - El cocinero encola SERVIR **después** de soltar el lock de la cocina.
   - La única cadena entre locks de la aplicación es **barrera → mesa**: la acción de barrera (`Mesa.llamarMozo`) corre con el lock interno de la `CyclicBarrier` tomado y entra al monitor de la mesa. Nunca se toma en el orden inverso: `Mesa.terminarLimpieza()` hace el `reset()` de la barrera (si estaba rota) **antes** de entrar al monitor.
2. **Lock hoja.** La única excepción es el write lock de `EstadoRestaurante`. Se toma *teniendo* otro lock (por ejemplo, desde `Mesa.servir`), pero mientras se lo tiene **no se toma ningún otro lock** de la aplicación: solo se modifica la vista y se agrega a una cola ilimitada. Un lock que siempre es el último de la cadena no puede formar un ciclo. Lo mismo vale para `CanalSSE`, que es una hoja dentro de la cadena del Display, y para los locks internos de las `BlockingQueue`.
3. **Colas ilimitadas.** `put`/`add` nunca bloquean, así que encolar con un monitor tomado (`Mesa.llamarMozo` → `ColaMozos.encolar`) no puede dejar a nadie esperando. Las únicas esperas indefinidas son `take()` y `wait()`, y en esos casos el hilo **no** tiene ningún otro lock: el `wait` suelta el monitor propio.
4. **La barrera no se ejecuta dentro del monitor.** `Mesa.esperarCompaneros()` **no** es `synchronized`: si lo fuera, la acción de barrera (que sí necesita el monitor) no podría correr. Los P clientes del grupo siempre llegan a la barrera, porque incluso los cancelados la atraviesan, y por eso nunca queda un `await` esperando para siempre.
5. **Sin espera indefinida en la puerta.** Se usa `tryAcquire` con timeout, para que el cliente re-evalúe el cierre.
6. **Esperas con límite en el coordinador.** `awaitTermination` y `await` del latch tienen un tiempo máximo (`Config.TIMEOUT_CIERRE_MS`). Si se superara, se informa el error, se vuelca la pila de todos los hilos al log (`Simulacion.volcarHilos`) y se fuerza con `shutdownNow`. Esto es una red de seguridad: en las pruebas nunca se activó.

**Inanición.** Las colas son FIFO y el `Semaphore` y el `ReentrantLock` son *fair*. Las prioridades de los mozos están acotadas (ver 5.1).

**Bloqueo activo (livelock).** No hay reintentos que se cedan el paso mutuamente. El único bucle con reintento es el `tryAcquire` de la puerta, que no cede nada.

### 6.2 Manejo de excepciones e interrupciones

- **`InterruptedException`.** En todos los actores se captura, se **restaura el flag** con `Thread.currentThread().interrupt()` y el hilo termina de forma ordenada.
- **Los `finally` liberan siempre los recursos:**
  - `Cliente` devuelve el permiso de la puerta;
  - `Mozo`, `Cocinero` y `Cajero` hacen `countDown` del latch y notifican TERMINADO;
  - `Cocina` hace `unlock`;
  - `EstadoRestaurante` hace `unlock` del write lock y del read lock.
- **El coordinador no abandona el cierre.** Si se lo interrumpe, anota la interrupción, sigue esperando y la restaura al final.
- **`BrokenBarrierException`.** Cancela la ocupación de la mesa (`Mesa.marcarCancelada`). La barrera se resetea al limpiar la mesa.
- **`ExecutionException`** (el futuro del pago) se informa como error del cliente.
- **Errores en el Display.** Una excepción al procesar un evento se captura y se loguea. El Display **no muere** y sigue con el siguiente.
- **Excepciones no capturadas.** El `UncaughtExceptionHandler` de `FabricaHilos` las registra con su pila en la consola y en el log.
- **Errores de I/O del log** se informan una sola vez y no interrumpen la simulación.
- **Errores del navegador en SSE.** Una `IOException` porque el navegador se desconectó no es un error: se da de baja al suscriptor.

### 6.3 Visibilidad (sin caché) y happens-before

El enunciado pide que los datos compartidos sean siempre visibles. Por el modelo de memoria de Java, todo lo que hace un hilo antes de una de estas acciones es visible para el hilo que hace la acción correspondiente después:

| Acción del primer hilo | Acción correspondiente del otro hilo | Dónde se usa |
|---|---|---|
| Salir de un monitor (`synchronized`) | Entrar al mismo monitor | `Mesa`, `SalaDeEspera` |
| `unlock()` | `lock()` | `Cocina`, `EstadoRestaurante` |
| `put()` en una `BlockingQueue` | `take()` del mismo elemento | Mozos, cajeros, Display |
| `release()` del semáforo | `acquire()` / `tryAcquire()` | Puerta |
| `countDown()` | Retorno de `await()` | Latches |
| Completar un `CompletableFuture` | Retorno de su `get()` | Pago |
| Terminar las tareas de un pool | Retorno de `awaitTermination` | Coordinador |
| Escribir una variable `volatile` o un atómico | Leer esa variable | `abierto`, contadores, `inicioNanos`, `resultado` |

Gracias a esto:

- `Plato.estado` no necesita `volatile`: solo se lee y escribe con el lock de la cocina tomado.
- Los mensajes (`TareaMozo`, `Pago`, `Evento`) son inmutables y se publican a través de colas concurrentes, lo que se llama **publicación segura**.

### 6.4 Snapshots inmutables

Cada actor informa su cambio llamando a `EstadoRestaurante.notificar(actor, descripción, cambio)`. Bajo el write lock:

1. se aplica `cambio` sobre una `Vista` mutable (privada, solo accesible con el lock);
2. se arma un `Snapshot` (un record con `List.copyOf` y componentes inmutables);
3. se encola el `Evento`.

Así cada evento lleva el estado del restaurante **del momento exacto en que se mandó la notificación**, como pide el enunciado, aunque el Display lo procese más tarde. La vista está separada de los objetos de sincronización reales (mesas, cocina): mostrar el estado nunca requiere tomar sus locks.

### 6.5 Verificación de invariantes

Al final de cada simulación (`Simulacion.verificarInvariantes`) se comprueba, usando los contadores **atómicos reales** y el estado real de los recursos:

1. Llegaron = entraron + se fueron sin entrar.
2. **Entraron = pagaron + retirados sin pedir.**
3. **No queda ningún cliente adentro.** La puerta tiene P·M permisos libres y no hay nadie en la sala.
4. **Todas las mesas están LIBRES**, tanto en el monitor como en la vista y en la sala.
5. **Las colas están vacías:** mozos, caja, cocina, eventos, afuera y sala.
6. **Platos cocinados = platos pedidos = clientes que pagaron.**
7. Recaudación de la caja = suma de los tickets.
8. Todos los empleados terminaron (latch en 0).
9. El Display procesó todos los eventos, en orden.
10. **Todos los pools terminados y ningún hilo de la simulación vivo.**

Si alguno falla, se marca como `[FALLA]` en la consola, el log y el modal web.

---

## 7. Display, log y frontend: cómo se garantiza el orden

- **Un solo punto de notificación.** El número de secuencia se asigna bajo el write lock, en el mismo paso en que se encola el evento. Por eso el orden de la cola es igual al orden de los números, que a su vez es igual al orden real de los cambios.
- **Un solo consumidor.** El Display corre en un `newSingleThreadExecutor` y procesa la cola FIFO de a un evento. Verifica además que las secuencias sean crecientes; si no lo fueran, dejaría una advertencia en el log.
- **Al llegar a T el Display sigue.** La poison pill se encola recién cuando terminaron todos los actores, así que el Display "continúa mostrando todos los cambios" y termina cuando la cola se vacía.
- **Por cada evento**, el Display:
  - escribe en el log la línea del evento más el **estado completo** (`FormatoTexto.estado`);
  - lo muestra en la consola, completo en `--consola`, en una línea en modo web y en silencio en el estrés;
  - lo publica como JSON (`Json.evento`, serializador hecho a mano con escape correcto de cadenas) en `CanalSSE`.
- **`CanalSSE`.** Cada navegador tiene su propia cola: un navegador lento no frena al Display. El canal guarda el historial, así que quien se conecta tarde o recarga la página recibe todo; con `Last-Event-ID` solo recibe lo que le falta.
- **Frontend (`web/app.js`).**
  - Dibuja un **mapa del restaurante visto desde arriba** (SVG) con todos los actores, más un panel lateral con el estado del personal y la lista de eventos.
  - Para soportar ráfagas, dibuja solo el último snapshot pendiente en cada `requestAnimationFrame`. Los eventos intermedios igual se agregan al panel de eventos, en orden.
  - Cada cliente y cada empleado es una "ficha" identificada por su id. En cada snapshot se calcula su posición (fila de afuera, grupo de la sala, silla de su mesa, cola de pago, caja) y la ficha se desplaza con una transición CSS, así se ve el movimiento.
  - Cuando un cliente deja de aparecer en el snapshot, se lo anima caminando hacia la salida.

---

## 8. Cómo compilar y ejecutar

```powershell
.\compilar.ps1                              # o compilar.bat
.\ejecutar.ps1                              # modo web: http://localhost:8080 → "Iniciar simulación"
java -cp out restaurante.Main --consola     # solo consola, termina solo
java -cp out restaurante.Main --consola --t 15000
java -cp out restaurante.Main --repeticiones 20   # estrés con tiempos chicos
```

Las constantes se cambian en `src/restaurante/config/Config.java` y después hay que recompilar. El detalle está en el `README.md`.

### Resultados de las pruebas

- `javac -Xlint:all` compila **sin errores ni warnings**.
- `--consola --t 15000` termina solo, en unos 20–26 s (lo que tardan en irse los que ya habían pedido), con los **10 invariantes OK** (entre 165 y 190 eventos según la corrida). `--consola --t 1` también cierra en orden.
- `--repeticiones 120` y `--repeticiones 150`: **120/120 y 150/150 OK**, con M, P, Z, C e Y variados (P = 1 incluido), sin ningún bloqueo.
- Pruebas dirigidas aparte (fuera del proyecto) con configuraciones extremas: todo en 1 (M = P = Z = C = Y = 1) con tiempos 0, T de 1 a 400 ms, P = 5 con M = 1, llegadas cada 0–1 ms con miles de clientes, mozos lentos para que el cierre encuentre `TOMAR_PEDIDO` encolados. **300 simulaciones (210 con la versión final del código), 0 fallas.** En los logs se comprobó que el cierre cayó en todas las fases del cliente (afuera, grupo incompleto, grupo esperando mesa, eligiendo, en la barrera, esperando al mozo, pidiendo, esperando la comida, comiendo, en la cola de la caja y pagando).
- Prueba dirigida de la carrera `Mesa.tomarPedido` contra `Mesa.cerrar` (25 000 carreras sobre una mesa aislada, P de 1 a 4): en todos los casos o el mozo tomó el pedido y los P clientes siguieron, o se canceló y los P se fueron sin pedir; nunca un estado mixto ni un hilo colgado.
- Modo web:
  - todos los endpoints responden;
  - `POST /iniciar` funciona una sola vez (la segunda da 409);
  - el stream SSE entrega JSON válido con todos los eventos en orden y el resumen final;
  - la reconexión con `Last-Event-ID` reenvía solo lo que falta.

---

## 9. Conclusión

**Sobre el producto.** El simulador cumple todos los requerimientos funcionales y técnicos del enunciado:

- cuatro roles concurrentes;
- cierre ordenado en T con todas sus reglas;
- display en orden con el estado completo de cada momento;
- log en archivo;
- todas las herramientas obligatorias (`synchronized`, `ReentrantLock`, `ReentrantReadWriteLock`, atómicos, `Semaphore`, `CyclicBarrier`, `CountDownLatch`, `BlockingQueue`, `Executors`, `wait/notifyAll`);
- varios patrones concurrentes justificados.

La verificación automática de invariantes y el modo estrés dan evidencia concreta, no solo argumentos, de que al terminar no quedan clientes, mesas ocupadas, mensajes perdidos ni hilos vivos. El mapa en movimiento de la interfaz web permite **ver** la concurrencia: cómo varios cocineros se reparten un pedido, cómo la barrera retiene a la mesa hasta que todos eligieron, y cómo el cierre deja ir a los que no pidieron mientras los demás terminan.

**Sobre lo aprendido.**

- Lo más difícil no fue el caso normal sino el **cierre**. Terminar hilos que están bloqueados en esperas distintas (afuera, en la sala, en la barrera, esperando al mozo, en `take()`) obliga a pensar, para cada espera, *quién* la va a despertar y *cómo* se entera de que tiene que irse.
- Aprendimos a elegir la herramienta según la forma del problema:
  - una barrera para "esperar a que todos elijan";
  - un monitor cuando hay varias condiciones sobre el mismo estado;
  - un `ReentrantLock` con `Condition` cuando la operación no es un simple *take*;
  - colas cuando los actores solo necesitan pasarse trabajo.
- Prevenir deadlocks es más fácil por **diseño** (orden de locks, lock hoja, colas ilimitadas, nada de esperas con locks tomados) que por depuración.
- Los objetos inmutables simplifican muchísimo compartir información entre hilos.
- Un sistema concurrente es **no determinista**: dos ejecuciones nunca dan el mismo orden. Por eso conviene verificar *propiedades* (invariantes) en muchas corridas, en lugar de comparar salidas.
