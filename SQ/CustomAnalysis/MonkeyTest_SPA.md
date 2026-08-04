# Monkey Test - Snippet de Custom Analysis

Un test de Permutación Monte Carlo diseñado para StrategyQuant X (SQX) que evalúa la robustez de la ventaja de entrada y salida de una estrategia de trading frente al azar del timing de mercado.

---

## 1. Resumen y Propósito
El **Monkey Test** es un método de validación estadística usado para determinar si el rendimiento histórico de una estrategia es resultado de una ventaja genuina (entradas y salidas precisas) o simplemente suerte estadística (p. ej., operar durante una tendencia fuerte y prolongada donde cualquier entrada aleatoria ganaría dinero).

Simula "monos" operando la estrategia tomando la secuencia exacta de operaciones (tiempos de permanencia, direcciones y perfiles de riesgo) y aplicando **desplazamientos temporales circulares aleatorios** a lo largo de la base de datos histórica de velas. Si la estrategia real supera a un percentil alto de estas ejecuciones aleatorizadas, pasa el test.

---

## 2. Lógica Central y Funcionamiento
Para cada estrategia del databank, el snippet:
1. **Lee Datos Históricos Nativos**: Localiza la conexión de símbolo y el timeframe a partir del backtest principal y parsea dinámicamente el fichero nativo de base de datos BDF de StrategyQuant (`.dat`).
2. **Filtra por Periodo Muestral**: Aplica el periodo seleccionado (`FULL`, `IS`, `OOS`, `ISV`, o segmentos numerados como `OOS1`, `OOS2`, `ISV1`, etc.) para restringir tanto el conjunto de operaciones real de la estrategia como las simulaciones de los monos a esa ventana. Las órdenes se filtran directamente por su sample type de SQX, de modo que cada segmento numerado queda genuinamente aislado — un periodo nunca se sustituye silenciosamente por las operaciones de otro. El rango de desplazamiento circular queda automáticamente acotado a las velas cubiertas por las operaciones filtradas. Si el periodo seleccionado no contiene operaciones (p. ej. el backtest se ejecutó sin un periodo OOS configurado), la estrategia se marca como `LOW TRADES`. Si se especifica un segmento numerado inexistente (p. ej. `OOS3` cuando sólo existen 2 segmentos OOS), se marca como `FAILED (INVALID PERIOD)`.
   * **`FULL` también evalúa cada periodo por separado.** Además del agregado, sondea `IS`, `OOS`, `ISV` y cada segmento existente `OOS1..10` / `ISV1..10`, ejecutando el test de forma independiente sobre cada uno, para poder inspeccionar sus resultados de forma aislada desde el Databank. Esto **no** multiplica el coste por el número de periodos: cada periodo sólo simula sus propias operaciones (total ≈2-3× el agregado solo). Cuando la estrategia tiene un único segmento OOS, `OOS` y `OOS1` son el mismo periodo (SQX copia sus estadísticas), así que se simula una vez y se publica bajo ambos.
3. **Realiza Desplazamientos Circulares**: Genera $N$ ejecuciones aleatorizadas. Para cada ejecución (mono), todas las operaciones se desplazan hacia adelante en el tiempo un offset aleatorio, dando la vuelta al llegar al límite del histórico.
4. **Simula la Evaluación del Recorrido**:
   * **Entradas**: Se abren al precio de Apertura de la vela desplazada.
   * **Salidas**: Se evalúan vela a vela para comprobar si el Stop Loss (SL) o el Objetivo de Beneficio (PT) se alcanza primero. Si la operación original no tenía SL/TP, se usa el número de velas como límite de salida forzoso.
   * **Salida de Viernes**: Cierra automáticamente las operaciones en el umbral de salida de viernes si está definido.
   * **Igualación de Riesgo**: Ajusta el tamaño de posición simulado (lotes) proporcionalmente si el precio de entrada difiere del precio de entrada original, manteniendo idéntico el riesgo monetario del Stop Loss.
5. **Evaluación Estadística por Percentil**: Compara el beneficio neto de la estrategia original frente a la distribución de los $N$ monos. Si el beneficio original es mayor que el umbral de percentil definido de los beneficios de los monos, la estrategia pasa.

---

## 3. Cómo Usarlo y Argumentos de Entrada

### Configuración en StrategyQuant X

#### 1. Tarea de Custom Analysis
1. Añade una tarea de **Custom Analysis** a tu proyecto.
2. En **Analysis type**, selecciona **Per Strategy Analysis** (esto habilita el cómputo multihilo usando todos los núcleos de CPU disponibles).
3. Selecciona **MonkeyTest** como método de análisis en el desplegable.
4. En el campo **Input Args**, configura tus parámetros como una cadena separada por comas: `numMonkeys,percentile,period,replicationMode,shiftingMode`. Opcionalmente, añade la palabra clave `ResultsPluginCache` en cualquier punto de esa misma cadena para además escribir los artefactos de caché que consume el ResultsPlugin Databank Monkey Test (ver [sección 4](#4-salidas-esperadas) más abajo).

#### 2. Pestañas de Ranking y Retests del Builder
Como el snippet usa la firma `Per Strategy Analysis`, también puedes seleccionar **MonkeyTest** en el desplegable de filtro de **Custom Analysis** en:
* La pestaña **Ranking** de la configuración de Builder/Genético (para descartar estrategias automáticamente durante la generación).
* La configuración de **Retests** (para descartar estrategias tras retestearlas sobre datos nuevos).

### Argumentos de Entrada
| Parámetro | Valor por Defecto | Descripción | Ejemplo |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | El número de simulaciones aleatorizadas de monos a ejecutar por estrategia. | `1000` |
| **percentile** | `95.0` | El umbral de confianza estadística. La estrategia debe superar este porcentaje de ejecuciones de monos para pasar. | `99.0` |
| **period** | `FULL` | Ventana muestral donde se ejecuta el test: `FULL` (backtest completo — **y además cada periodo por separado**, ver [sección 2](#2-lógica-central-y-funcionamiento)), `IS` (sólo In-Sample), `OOS` (Out-of-Sample combinado), `ISV` (In-Sample Validation), o sub-periodos numerados concretos (`OOS1`..`OOS10`, `ISV1`..`ISV10`). Este valor también decide qué periodo determina el veredicto PASSED/FAILED. | `OOS2` |
| **replicationMode**| `IndivBars` | Modo de simulación de salida de la operación: `SLTP` (distancia de SL y TP), `AvgBars` (exposición media fija), o `IndivBars` (exposición individual por operación). | `SLTP` |
| **shiftingMode**   | `Random` | Modo de desplazamiento temporal circular: `Constant` (desplazamiento global constante) o `Random` (desplazamiento aleatorio por operación). | `Constant` |
| **ResultsPluginCache** | *(ausente)* | Palabra clave opcional, no posicional — se detecta como una subcadena, sin distinguir mayúsculas/minúsculas, en cualquier punto de la cadena de Input Args, así que puede añadirse tras cualquiera de los 5 parámetros anteriores. Cuando está presente, el snippet escribe los artefactos de caché (CSV + meta.json) descritos en la [sección 4](#4-salidas-esperadas). Cuando está ausente (por defecto), **no se escribe ningún fichero de caché**, sea cual sea el resultado del test. | `500,95,OOS2,IndivBars,Random,ResultsPluginCache` |
| **AutoDiscard** | *(ausente)* | Palabra clave opcional, mismas reglas de detección que `ResultsPluginCache` (subcadena sin distinguir mayúsculas/minúsculas, se puede combinar con ella). Controla si `filterStrategy` puede indicarle al motor de SQX que excluya la estrategia cuando el test falla — ver [sección 4](#4-salidas-esperadas) para la explicación completa. **Ausente por defecto: ninguna estrategia se excluye nunca**, sea PASSED o FAILED. | `500,95,OOS2,IndivBars,Random,AutoDiscard` |

*Ejemplo de Input Args:* `500,95,OOS2,IndivBars,Random` (Ejecuta 500 monos sobre las operaciones de OOS2, usando salidas de exposición individual por vela y desplazamiento aleatorio por operación; no se escribe ningún fichero de caché). Omitir replicationMode y shiftingMode los establece automáticamente a `IndivBars` y `Random`. Añade `ResultsPluginCache` en cualquier punto de la cadena, p. ej. `500,95,OOS2,IndivBars,Random,ResultsPluginCache`, para además escribir los ficheros de caché del ResultsPlugin.


---

## 4. Salidas Esperadas

### Requisito: Instalar la Columna de Databank Monkey Test

El snippet de Custom Analysis **MonkeyTest** sólo escribe resultados en los metadatos de la estrategia. Para **mostrar** esos resultados como columnas en el databank de SQX, también debes instalar y activar los snippets complementarios de **Databank Column**:

- **Ficheros**: `SQ/Columns/Databanks/MonkeyTestColumn.java` y `SQ/Columns/Databanks/MonkeyTestZScoreColumn.java` (ubicados junto a este snippet bajo `user/extend/Snippets/`)
- **Nombres de columna en SQX**: `Monkey Test` (tipo: Text) y `Monkey Z-Score` (tipo: Decimal2)

**Pasos de instalación:**
1. Asegúrate de que ambos ficheros de columna estén presentes en `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Reinicia SQX (o fuerza la recompilación de snippets) para que las columnas se registren.
3. En la vista de Databank, abre el selector de columnas y añade las columnas **"Monkey Test"** / **"Monkey Z-Score"**.

> **Importante:** ambas columnas deben **recompilarse** tras actualizar este Custom Analysis. Las versiones anteriores ignoraban el selector de sample type del Databank y mostraban el mismo valor almacenado en todas las columnas de periodo.

> Sin las Databank Columns instaladas, el test se sigue ejecutando y filtra estrategias mediante la columna `FiltersResult`, pero los resultados individuales (`PASSED`, `FAILED`, `LOW TRADES`, etc.) no serán visibles en la rejilla del databank.

Estos snippets (Custom Analysis + Databank Columns) están diseñados para trabajar juntos y deberían instalarse todos para la experiencia completa.

### Columnas del Databank
Los resultados se almacenan **por periodo**, usando una clave por sufijo de periodo, de modo que varias ejecuciones sobre periodos distintos pueden coexistir en la misma estrategia sin sobrescribirse:

| Clave | Se escribe para |
| :--- | :--- |
| `MonkeyTestResult<sufijo>` | Resultado de ese periodo (ver lista de estados abajo). |
| `MonkeyTestPercentile<sufijo>` | Percentil de rango alcanzado frente a la distribución de monos, p. ej. `85.20%`. |
| `MonkeyTestZScore<sufijo>` | Z-Score del beneficio real frente a la media/desviación de los monos. |

Sufijos válidos: `_IS`, `_OOS`, `_ISV`, `_OOS1`..`_OOS10`, `_ISV1`..`_ISV10`, `_Full`.

Las dos columnas resuelven el sufijo automáticamente a partir del **selector de sample type del Databank** — exactamente igual que las columnas `Synth*` de `CVSintetica` — así que seleccionar *OOS2* en ese selector muestra los valores `_OOS2`. La resolución es **estricta**: si un periodo no se ha evaluado, la columna muestra `N/A` en vez de caer al valor de otro periodo. Las claves legacy sin sufijo (escritas por versiones anteriores) se siguen aceptando, pero sólo bajo *Full Sample*.

Las claves sin sufijo se escriben **únicamente cuando el periodo pedido es `FULL`**, donde representan legítimamente el agregado; para cualquier otro periodo pedido se limpian, así nunca pueden mostrar el valor de un único periodo etiquetado como si fuera el total.

* **Estados de la columna Monkey Test** (clave `MonkeyTestResult<sufijo>`):
  * `PASSED`: El beneficio neto de la estrategia superó el percentil definido de las ejecuciones aleatorizadas de monos.
  * `FAILED`: La estrategia no superó el umbral de percentil.
  * `LOW TRADES`: La estrategia tiene menos de 20 operaciones en ese periodo (demasiado pocas para un análisis estadístico fiable). También aparece cuando el periodo contiene cero operaciones, lo que normalmente significa que el backtest no se configuró con ese periodo muestral.
  * `FAILED (INVALID PERIOD)`: Se pidió un segmento numerado (p. ej. `OOS3`) que no existe en la estrategia (p. ej. la estrategia sólo tiene 2 segmentos OOS).
  * `FAILED (NO DATA)`: Faltaba el fichero histórico `.dat` del símbolo/timeframe en las carpetas de histórico de SQX.
  * `ERROR`: Ocurrió un error de ejecución inesperado.
* **Columna Filters Result** (claves `FiltersResultFailedReason` / `FilterResult`):
  * Dibuja un **PASSED verde** (`Passed`) si el test pasa (y ningún otro filtro falló).
  * Dibuja un **FAILED rojo** (`Failed Monkey Test` o `Failed Monkey Test (Invalid Period)`) si la estrategia falla.
  * **El veredicto proviene únicamente del periodo pedido en Input Args.** Cuando `FULL` calcula también los demás periodos, esos resultados adicionales se publican para inspección pero nunca afectan al veredicto.

### Exclusión de estrategias (`AutoDiscard`)

Marcar una estrategia como FAILED (arriba) es puramente visual — nunca elimina nada por sí mismo. Que el motor de SQX reciba realmente la orden de excluir una estrategia fallida depende de la palabra clave `AutoDiscard` ([Argumentos de Entrada](#argumentos-de-entrada)):

* **`AutoDiscard` ausente (por defecto): ninguna estrategia se excluye nunca.** `filterStrategy` siempre devuelve `true` al motor de SQX, exactamente igual que `CVSintetica`. Cada estrategia procesada — PASSED o FAILED — se queda donde la tarea la habría puesto de todos modos, completamente marcada con su resultado real.
* **`AutoDiscard` presente:** `filterStrategy` devuelve el veredicto real PASSED/FAILED, dejando que el motor de SQX actúe en consecuencia.

Esto importa porque SQX tiene **dos mecanismos independientes y sin relación entre sí** que pueden acabar excluyendo una estrategia, y sólo uno de ellos se ve afectado por `AutoDiscard`:

1. **Copiar entre dos databanks distintos** (una tarea de Custom Analysis con databank de Entrada/Salida distintos): el motor de SQX sólo copia al databank de Salida las estrategias para las que `filterStrategy` devolvió `true`. Las fallidas **nunca se copian**, sin importar ningún ajuste de la interfaz — este es exactamente el escenario que motivó añadir `AutoDiscard`: sin él, una tarea que copia p. ej. `SynthTestFiltered - IS` → `MonkeyTest - OOS` descartaría silenciosamente cada estrategia fallida, dejando en el databank de salida sólo las PASSED, sin forma de saber cuántas se descartaron ni por qué.
2. **El checkbox nativo "Filter by results of custom analysis"** (configuración de la tarea, *"If true strategies that don't pass will be removed"*): sólo relevante cuando el databank de Entrada y el de Salida son el **mismo** (análisis in-place). Borra las estrategias fallidas de ese databank, pero **sólo si `AutoDiscard` también hace que `filterStrategy` devuelva `false`** — con `AutoDiscard` ausente, este checkbox no tiene ningún efecto, ya que `filterStrategy` nunca reporta un fallo al motor.

En resumen: `AutoDiscard` es el único interruptor que decide si *cualquiera* de los dos mecanismos puede llegar a eliminar una estrategia. Déjalo fuera para conservar siempre cada estrategia, marcada con su resultado real, en cualquier configuración de tarea.

### Ficheros de Caché para el ResultsPlugin Databank Monkey Test (v3)
Para que el ResultsPlugin **Databank Monkey Test** muestre automáticamente la campana de Gauss y los gráficos comparativos de equity sin recalcular, el snippet puede escribir dos artefactos de caché por estrategia en:
`user/extend/ResultsPlugins/DatabankMonkeyTest/cache/`

> **Opt-in mediante `ResultsPluginCache`:** estos dos ficheros sólo se escriben cuando la palabra clave `ResultsPluginCache` está presente en Input Args (ver la [tabla de Argumentos de Entrada](#argumentos-de-entrada)). Por defecto (palabra clave ausente), el snippet sigue calculando y almacenando los valores de databank `MonkeyTestResult<sufijo>`, `MonkeyTestPercentile<sufijo>` y `MonkeyTestZScore<sufijo>` por periodo, pero **omite por completo la escritura de estos ficheros de caché** — no se crea ni actualiza nada en disco. Añade la palabra clave sólo si pretendes inspeccionar el resultado de esa estrategia en el ResultsPlugin Databank Monkey Test, para evitar acumular ficheros de caché de estrategias que no piensas revisar.

> **Un par de caché por estrategia — sólo para el periodo pedido.** El ResultsPlugin localiza estos ficheros únicamente por el nombre de la estrategia (el periodo no forma parte del nombre de fichero), así que sólo puede existir un par por estrategia. Cuando `FULL` evalúa todos los periodos, la caché se escribe **exclusivamente para el periodo pedido en Input Args**; los demás periodos se siguen publicando como valores de Databank pero no se cachean. El campo `period` dentro de `meta.json` siempre identifica a qué periodo pertenecen los datos cacheados.

* **`[NombreEstrategia]_monkey_simulation_data.csv`** — un CSV compacto "ancho" con hasta 50 curvas de equity representativas de los monos (no un volcado completo a nivel de operación). Cada fila es el recorrido de balance completo de un mono: `monkey_id;b0;b1;...;bT` (separado por punto y coma, decimales con punto, sin comillas, UTF-8 sin BOM). Las filas se seleccionan de la distribución completa de beneficios de los monos — el más bajo (`min`), el más alto (`max`), y hasta 48 curvas intermedias espaciadas uniformemente por rango de percentil — para que el plugin pueda dibujar un "espagueti" representativo de curvas de equity frente a la equity de la estrategia real, obtenida por separado desde `GET_ORDERS`.
* **`[NombreEstrategia]_monkey_simulation_data.meta.json`** — todos los KPIs escalares más el array completo de beneficios de los monos, esquema versión 3:

  | Campo | Descripción |
  | :--- | :--- |
  | `schemaVersion` | Siempre `3`. Marca esto como el formato de caché actual. |
  | `strategyName`, `period` | Nombre de la estrategia y periodo muestral usado para el test cacheado (`FULL`, `IS`, `OOS`, `ISV`, `OOS1`..`OOS10`, `ISV1`..`ISV10`) — siempre el periodo pedido en Input Args. |
  | `tradeFromMs`, `tradeToMs` | Rango en ms de época (UTC) de las operaciones reales usadas — permite al plugin verificar que la caché corresponde a la estrategia cargada actualmente antes de confiar en ella. |
  | `numTrades`, `numMonkeys`, `percentile` | Configuración del test realmente usada. |
  | `replicationMode` | Modo de simulación de salida de la operación usado: `SLTP`, `AvgBars`, o `IndivBars`. |
  | `shiftingMode` | Modo de desplazamiento temporal circular usado: `Constant` o `Random`. |
  | `initialBalance` | Balance inicial, igual a `b0` en cada fila del CSV. |
  | `realProfit`, `monkeyThreshold`, `meanMonkey`, `stdMonkey`, `zScore`, `rankPercentile` | Estadísticas comparando la estrategia real frente a la distribución completa de los N monos. |
  | `status` | `"PASSED"`, `"FAILED"`, o `"LOW TRADES"` — cadenas exactas, usadas directamente por las etiquetas del plugin. |
  | `meanHoldingPeriod` | La duración media de la operación en velas. |
  | `monkeyProfits` | El array completo de N beneficios de monos, ordenado ascendentemente — impulsa el histograma gaussiano. |
  | `generatedAtUtc`, `source` | Frescura y origen de la caché (`"CustomAnalysis"` aquí; el plugin también puede escribir su propia caché con `"Plugin"` cuando el usuario ejecuta un cálculo en vivo desde su propio botón "Run Monkey Test"). |

> **Integración con el ResultsPlugin:** cuando se hace doble clic en una estrategia en el databank, la pestaña de Resultados "Databank Monkey Test" carga automáticamente estos ficheros de caché y renderiza los gráficos sin necesidad de que el usuario vuelva a ejecutar la simulación. El contrato completo de caché v3 — incluyendo los formatos exactos de campo, el algoritmo de selección de curvas, y cómo cada elemento de la interfaz consume estos campos — es la especificación autorizada en:
> `user/extend/ResultsPlugins/DatabankMonkeyTest/MTCustomAnalysisImprovementPlan.md`

> Igual que en v1, los ficheros de caché sólo se escriben cuando la palabra clave `ResultsPluginCache` está presente en Input Args **y** el test se ejecuta por completo (es decir, no para resultados `LOW TRADES`, `FAILED (NO DATA)`, o `ERROR`); el plugin recurre a un recálculo en vivo cuando no encuentra una caché coincidente.
