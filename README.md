# Simulador concurrente de restaurante — Entregable 2

Programación Avanzada 2026 · Ferreiro, Bruschera

Simulación en Java 17 de un restaurante con **clientes, mozos, cocineros y cajeros** que corren de forma concurrente durante un tiempo **T**. Al llegar a T el restaurante cierra de forma ordenada. Cada cambio se muestra en la consola, en un **log de texto** y en una **interfaz web en vivo** (HTML/CSS/JS sin dependencias, servida por el `HttpServer` del JDK).

La documentación técnica completa está en [`docs/Documentacion.md`](docs/Documentacion.md).

## Requisitos

- JDK 17 (probado con Eclipse Temurin 17.0.17).
- No hace falta Maven, Gradle ni ninguna librería externa.

## Compilar

PowerShell:

```powershell
.\compilar.ps1
```

CMD:

```bat
compilar.bat
```

A mano:

```bash
javac -Xlint:all -encoding UTF-8 -d out $(find src -name "*.java")
```

> Si PowerShell bloquea los scripts, ejecutá `powershell -ExecutionPolicy Bypass -File .\compilar.ps1`.

## Ejecutar

Los comandos se ejecutan **desde la carpeta del proyecto**, porque el servidor busca los archivos en `web/` y el log se escribe en `logs/`.

| Comando | Qué hace |
|---|---|
| `.\ejecutar.ps1` o `ejecutar.bat` | **Modo web.** Levanta `http://localhost:8080`, que prueba el siguiente puerto si está ocupado, y abre el navegador. La simulación arranca con el botón **Iniciar simulación**. Al terminar, el servidor queda vivo para revisar el resultado; se cierra con Ctrl+C. |
| `java -cp out restaurante.Main --consola` | **Solo consola.** Arranca enseguida, muestra cada cambio con el estado completo del restaurante y el proceso **termina solo**. |
| `java -cp out restaurante.Main --consola --t 15000` | Igual, pero con T = 15 s. |
| `java -cp out restaurante.Main --repeticiones 20` | **Estrés.** Corre 20 simulaciones cortas seguidas con tiempos chicos y distintos M, P, Z, C e Y, y verifica los invariantes de cada una. |
| `java -cp out restaurante.Main --sin-navegador` | Modo web sin abrir el navegador automáticamente. |
| `java -cp out restaurante.Main --ayuda` | Muestra las opciones. |

Los scripts `ejecutar.ps1` y `ejecutar.bat` aceptan los mismos flags, por ejemplo `.\ejecutar.ps1 --consola --t 15000`.

Al final de cada simulación se imprime y se loguea una **verificación de invariantes**:

- entraron = pagaron + retirados sin pedir;
- no queda nadie adentro;
- todas las mesas están LIBRES;
- las colas están vacías;
- platos cocinados = platos pedidos;
- todos los pools están terminados y no quedan hilos vivos;
- y otros más.

## Cambiar las constantes

Todas las constantes están en [`src/restaurante/config/Config.java`](src/restaurante/config/Config.java) como `static final`:

- `T`, `M`, `P`, `Z`, `C`, `Y`.
- Los rangos `TP_MIN/TP_MAX`, `TM_*`, `TQ_*`, `TZ_*`, `TR_*`, `TL_*`, `TY_*`.
- El menú: nombre, precio y `TC_MIN/TC_MAX` de cada plato.
- El puerto web, las carpetas y los parámetros del modo estrés (`T_RAPIDO`, `ESCALA_RAPIDA`).

Después de cambiarlas hay que volver a compilar. El flag `--t` solo pisa T para hacer pruebas.

## Log

Cada simulación genera `logs/simulacion-AAAAMMDD-HHmmss-SSS.log`, en UTF-8. Contiene:

- los parámetros;
- cada evento, en orden y numerado, con el estado completo del restaurante en ese momento: afuera, sala de espera, cada mesa con sus clientes, mozos, cola de mozos, cocina, cocineros, caja y totales;
- al final, la verificación de invariantes.

## Interfaz web

Todo entra en una sola pantalla, sin hacer scroll:

- **Barra superior:** estado ABIERTO/CERRANDO/CERRADO, reloj de T con barra de progreso, 5 números clave (adentro, afuera, atendidos, se fueron sin comer, recaudación) y el botón Iniciar.
- **Mapa del restaurante en movimiento** (plano visto desde arriba, en SVG):
  - **Afuera:** la fila de clientes que esperan entrar. La puerta se ve verde cuando está abierta y roja cuando está cerrada.
  - **Sala de espera:** los grupos que se van formando hasta juntar P.
  - **Salón:** las M mesas con P sillas cada una. Cada cliente es una ficha numerada, con un color según su estado, y aparece el plato cuando está comiendo.
  - **Estación de mozos:** los mozos (Z1, Z2…) caminan hasta la mesa que atienden, con un ícono: 📝 pedido, 🍽️ servir, 🧽 limpiar.
  - **Cocina:** cada cocinero en su hornalla, con el plato que cocina, y las comandas en orden de llegada (los puntos marcan cada plato: gris pendiente, naranja cocinando, verde listo).
  - **Caja:** los cajeros, el cliente al que le están cobrando y la cola de pago.
  - **Salida:** los clientes se van caminando hacia la salida.
- **Panel lateral:**
  - **Personal:** el estado actual de cada mozo, cocinero y cajero.
  - **Eventos:** cada cambio a medida que ocurre, con lo más nuevo arriba.
- **Resumen final:** al terminar, una ventana con los totales y la verificación de invariantes.
- Tema claro/oscuro (botón 🌓) y leyenda de colores debajo del mapa.

## Estructura

```
src/restaurante/
  Main.java              arranque y flags
  config/                Config (constantes), Parametros, Rango
  modelo/                Mesa (monitor + CyclicBarrier), Menu, Pedido, Plato, TareaMozo, Pago, Ticket, enums
  recursos/              Restaurante (Semaphore, AtomicBoolean), SalaDeEspera (monitor), Cocina (ReentrantLock),
                         Caja (BlockingQueue), ColaMozos (PriorityBlockingQueue), Contadores (atómicos)
  actores/               Cliente, Mozo, Cocinero, Cajero, GeneradorClientes
  estado/                EstadoRestaurante (ReentrantReadWriteLock), Vista, Snapshot (inmutable), Evento, Actor
  simulacion/            Simulacion (coordinador y cierre), FabricaHilos, Aleatorio, Resultado, Invariante
  display/               Display (hilo consumidor), LogArchivo, FormatoTexto, Json
  web/                   ServidorWeb (HttpServer), CanalSSE
web/                     index.html, styles.css, app.js
docs/Documentacion.md    documento de decisiones
```
