# Monkey Test v2.00 - Snippet de Custom Analysis

Un test de Permutación Monte Carlo diseñado para StrategyQuant X (SQX) que evalúa la robustez de la ventaja de entrada y salida de una estrategia de trading frente al azar del timing de mercado.

---

## 1. Resumen y Propósito

El **Monkey Test** es un método de validación estadística usado para determinar si el rendimiento histórico de una estrategia es resultado de una ventaja genuina (entradas y salidas precisas) o simplemente suerte estadística (p. ej., operar durante una tendencia fuerte y prolongada donde cualquier entrada aleatoria ganaría dinero).

Simula "monos" que ejecutan el mismo número de operaciones que la estrategia original, con la misma duración media por operación y la misma dirección, pero con **entradas colocadas al azar** a lo largo del periodo analizado. Si la estrategia real supera a un percentil alto de estas ejecuciones aleatorizadas, pasa el test.

La versión 2.00 sustituye el cálculo operación a operación de la v1.00 por una **calibración única por periodo**: se mide cuánto vale monetariamente una unidad de desplazamiento de precio en la estrategia original, y el beneficio de cada mono se obtiene multiplicando ese factor por su desplazamiento total acumulado. El resultado es un cálculo más simple, más rápido y con menos superficie de error.

---

## 2. Lógica Central y Funcionamiento

Para cada estrategia del databank, el snippet:

### 2.1. Lee los datos históricos nativos

Localiza la conexión de símbolo y el timeframe a partir del backtest principal y parsea directamente el fichero nativo de base de datos BDF de StrategyQuant (`.dat`).

> **Importante**: este snippet **no** utiliza el motor de backtest de SQX. La simulación es propia y opera sobre las velas crudas del `.dat`. Esto tiene una consecuencia directa: el spread no se aplica automáticamente y hay que aplicarlo de forma explícita (ver [sección 2.5](#25-aplicación-del-spread)).

### 2.2. Filtra por periodo muestral

Aplica el periodo seleccionado (`FULL`, `IS`, `OOS`, `ISV`, o segmentos numerados como `OOS1`, `OOS2`, `ISV1`) para restringir tanto el conjunto de operaciones real como las simulaciones de los monos a esa ventana. Las órdenes se filtran directamente por su sample type de SQX, de modo que cada segmento numerado queda genuinamente aislado — un periodo nunca se sustituye silenciosamente por las operaciones de otro.

* Si el periodo seleccionado no contiene operaciones, la estrategia se marca como `LOW TRADES`.
* Si se especifica un segmento numerado inexistente (p. ej. `OOS3` cuando sólo existen 2 segmentos OOS), se marca como `FAILED (INVALID PERIOD)`.
* **`FULL` también evalúa cada periodo por separado.** Además del agregado, sondea `IS`, `OOS`, `ISV` y cada segmento existente `OOS1..10` / `ISV1..10`, ejecutando el test de forma independiente sobre cada uno. Esto **no** multiplica el coste por el número de periodos: cada periodo sólo simula sus propias operaciones. Cuando la estrategia tiene un único segmento OOS, `OOS` y `OOS1` son el mismo periodo (SQX copia sus estadísticas), así que se simula una vez y se publica bajo ambos sufijos.

### 2.3. Determina el modo de cálculo según el Money Management

El snippet trabaja de dos formas distintas según el método de gestión monetaria declarado en la estrategia original:

| Modo | Cuándo se aplica | Unidad de medida |
| :--- | :--- | :--- |
| **A** | El money management es **Fixed Size** (lotaje constante) | Desplazamiento de precio absoluto |
| **B** | Cualquier otro money management (**Fixed Amount**, Risk %, etc.: lotaje variable) | Desplazamiento porcentual respecto al precio de entrada |

El modo se lee del XML de configuración de la estrategia (`RiskMoneyManagement > MoneyManagement > Method[@use="true"]`). **El money management declarado manda, sin degradación**: si está declarado `FixedSize`, se usa MODO A aunque los lotajes observados varíen. Sólo si el XML no se puede parsear se recurre a comprobar empíricamente si todos los lotajes son iguales.

### 2.4. Calibra el ratio monetario (el corazón de la v2.00)

Se recorre una sola vez el conjunto de operaciones reales de la estrategia y se calcula:

```
K = suma(|beneficio bruto de cada operación|) / suma(|desplazamiento de cada operación|)
```

Donde el beneficio bruto es el P&L **sin comisiones ni swaps**, y el desplazamiento es:

* En **MODO A**: la diferencia de precio con signo, `(precio de cierre - precio de apertura) x dirección`.
* En **MODO B**: esa misma diferencia expresada como porcentaje del precio de entrada de la operación. No se trata del beneficio porcentual respecto al capital de la cuenta, sino de las unidades porcentuales de desplazamiento del precio del activo en cada operación.

`K` es, por tanto, el **valor monetario medio bruto por unidad de desplazamiento** (por pip en MODO A, por cada 1% en MODO B). Es una **constante única por periodo**, aplicada por igual a todos los monos y a todas sus operaciones.

### 2.5. Aplicación del spread

Los precios de las órdenes de la estrategia original ya incorporan el spread (SQX abre en ask y cierra en bid), mientras que las velas del fichero `.dat` son precios crudos. Para que ambos lados sean comparables, **a cada operación simulada se le resta el spread del desplazamiento**. Matemáticamente equivale a entrar en ask y salir en bid, pero en una sola resta.

El spread se lee siempre del XML de configuración de la estrategia (`Data > Setups > Setup > Chart[@spread]`), en *points*, y se convierte a precio multiplicándolo por el `tickSize` del instrumento. Cuando hay varios `Setup` (cross-checks sobre mercados adicionales), se empareja por el atributo `@symbol` para no coger el spread del mercado equivocado.

### 2.6. Calcula la duración de las operaciones de los monos

La duración se mide como **posición fraccionaria en el eje de barras**, no en tiempo de calendario:

```
posición(t) = índice de la barra que contiene t + fracción transcurrida dentro de esa barra
duración    = posición(cierre) - posición(apertura)
```

Al apoyarse en el índice de barra, los fines de semana y festivos no cuentan (los índices son contiguos aunque el calendario salte), mientras que el término fraccionario conserva la resolución sub-barra del backtest original.

Cuando la duración media resultante no es un número entero de barras, se aplica el **dithering determinista**: si la media es de 1,5625 barras H4 (6h15m), imposible de replicar con duraciones enteras, con 16 operaciones se asignan **9 operaciones de 2 barras y 7 de 1 barra**, lo que suma exactamente 25 barras = 100 horas. Redondear a 2 barras daría 32 barras (+28% de sobreexposición); truncar a 1 daría 16 (-36%).

Las operaciones que reciben la barra extra se eligen **al azar y sin repetición en cada mono**, de modo que las operaciones largas quedan repartidas por todo el periodo en lugar de concentrarse en una zona. El número de operaciones largas es siempre exactamente el mismo, así que la exposición total es idéntica en todos los monos y difiere de la de la estrategia original en **como máximo media barra sobre el periodo completo**.

### 2.7. Coloca las entradas sin solapamiento

Las N entradas se distribuyen dentro de la ventana del periodo repartiendo al azar la holgura sobrante en huecos entre operaciones. Esto garantiza:

* **Cero solapamiento**: la separación mínima entre entradas consecutivas es la duración real de la operación anterior.
* **Todo dentro del periodo**: la última operación termina dentro de la ventana evaluada.
* **Mismo número de operaciones** que la estrategia original, siempre.

### 2.8. Calcula el beneficio de cada mono

```
beneficio bruto = K x (desplazamiento total acumulado del mono)
beneficio neto  = beneficio bruto - comisiones y swaps totales de la estrategia original
```

Dado que todos los monos ejecutan el mismo número de operaciones que la estrategia original, y que el objetivo del test es medir la ventaja frente al azar sin que los costes influyan en el resultado, se asume que las comisiones y swaps de cada mono son exactamente los mismos que los de la estrategia original.

### 2.9. Evaluación estadística por percentil

Compara el beneficio neto de la estrategia original frente a la distribución de los N monos. Si el beneficio original es mayor que el umbral de percentil definido, la estrategia pasa.

El beneficio de la estrategia original se **reconstruye con la misma fórmula** que los monos (`K x desplazamiento total - comisiones`), de modo que cualquier error de aproximación afecta por igual a ambos lados. En MODO A ese valor reconstruido coincide al céntimo con el Net Profit real de SQX.

---

## 3. Cómo Usarlo y Argumentos de Entrada

### Configuración en StrategyQuant X

#### 1. Tarea de Custom Analysis

1. Añade una tarea de **Custom Analysis** a tu proyecto.
2. En **Analysis type**, selecciona **Per Strategy Analysis** (esto habilita el cómputo multihilo usando todos los núcleos de CPU disponibles).
3. Selecciona **MonkeyTest_v2_00** como método de análisis en el desplegable.
4. En el campo **Input Args**, configura tus parámetros como una cadena separada por comas: `numMonkeys,percentile,period`, más las palabras clave opcionales que necesites.

#### 2. Pestañas de Ranking y Retests del Builder

Como el snippet usa la firma `Per Strategy Analysis`, también puedes seleccionar **MonkeyTest_v2_00** en el desplegable de filtro de **Custom Analysis** en:

* La pestaña **Ranking** de la configuración de Builder/Genético (para descartar estrategias automáticamente durante la generación).
* La configuración de **Retests** (para descartar estrategias tras retestearlas sobre datos nuevos).

### Argumentos de Entrada

| Parámetro | Valor por Defecto | Descripción | Ejemplo |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | El número de simulaciones aleatorizadas de monos a ejecutar por estrategia. | `1000` |
| **percentile** | `95.0` | El umbral de confianza estadística. La estrategia debe superar este porcentaje de ejecuciones de monos para pasar. | `99.0` |
| **period** | `FULL` | Ventana muestral donde se ejecuta el test: `FULL` (backtest completo — **y además cada periodo por separado**), `IS`, `OOS`, `ISV`, o sub-periodos numerados (`OOS1`..`OOS10`, `ISV1`..`ISV10`). Este valor también decide qué periodo determina el veredicto PASSED/FAILED. | `OOS2` |
| **ResultsPluginCache** | *(ausente)* | Palabra clave opcional, no posicional — se detecta como subcadena, sin distinguir mayúsculas/minúsculas, en cualquier punto de la cadena. Cuando está presente, el snippet escribe los artefactos de caché (CSV + meta.json) descritos en la [sección 4](#4-salidas-esperadas). Cuando está ausente, **no se escribe ningún fichero de caché**. | `500,95,OOS2,ResultsPluginCache` |
| **AutoDiscard** | *(ausente)* | Palabra clave opcional, mismas reglas de detección. Controla si `filterStrategy` puede indicarle al motor de SQX que excluya la estrategia cuando el test falla. **Ausente por defecto: ninguna estrategia se excluye nunca**, sea PASSED o FAILED. | `500,95,OOS2,AutoDiscard` |
| **Precision=M1** / **M1** | *(ausente: timeframe principal)* | Palabra clave opcional. Ejecuta la simulación sobre los datos históricos de velas de 1 minuto (`SYMBOL_M1.dat` o `SYMBOL_1M.dat`), lo que aumenta la resolución con la que se replica la duración de las operaciones. Si los datos de 1 minuto no están disponibles, se emite una advertencia y se vuelve automáticamente al timeframe principal. | `500,95,FULL,Precision=M1` |
| **SegmentDuration=N** | *(ausente: off)* | Palabra clave opcional (ej. `SegmentDuration=300`). Divide el periodo analizado en sub-segmentos continuos ajustados a N días y ejecuta el test sobre cada uno, de forma adicional e independiente al test principal. | `500,95,FULL,SegmentDuration=300` |
| **Debug** | *(ausente)* | Palabra clave opcional, mismas reglas de detección. Escribe un volcado de diagnóstico en `user/extend/Snippets/SQ/CustomAnalysis/MonkeyTest_v2_debug.log` (ver [sección 4](#volcado-de-diagnóstico-debug)). Sólo vuelca el **primer mono** de cada periodo, y nunca los sub-segmentos de `SegmentDuration`. Ausente por defecto: sin ella no se escribe ningún fichero. | `500,95,OOS2,Debug` |

> **Argumentos eliminados respecto a la v1.00:** `replicationMode` (`SLTP` / `AvgBars` / `IndivBars`) y `shiftingMode` (`Constant` / `Random`) **ya no existen**. La v2.00 siempre simula con duración media diteada y entradas aleatorias sin solapamiento. Si una tarea heredada de la v1.00 los sigue pasando en las posiciones 4 y 5, el snippet los detecta, los ignora y emite una advertencia en el log explicando que ya no aplican.

*Ejemplo de Input Args:* `500,95,OOS2` ejecuta 500 monos sobre las operaciones de OOS2. Para ejecutar el test con precisión intrabarra de 1 minuto: `500,95,FULL,Precision=M1`. Para activar el test segmentado por tiempo: `500,95,FULL,SegmentDuration=300`.

> **Nota sobre la Precisión de Datos:** la opción de precisión seleccionada en la configuración general del backtest de SQX **no influye** en la fuente de datos que lee este Custom Analysis. Por defecto siempre leerá el fichero del timeframe principal de la estrategia. Para forzar la lectura de los datos de 1 minuto es **imprescindible** incluir explícitamente la palabra clave `Precision=M1` o `M1`.

---

## 4. Salidas Esperadas

### Requisito: Instalar las Columnas de Databank de Monkey Test

El snippet de Custom Analysis sólo escribe resultados en los metadatos de la estrategia. Para **mostrar** esos resultados como columnas en el databank de SQX, también debes instalar y activar los snippets complementarios de **Databank Column**:

- **Ficheros**: `SQ/Columns/Databanks/MonkeyTestColumn.java`, `MonkeyTestZScoreColumn.java` y `MonkeyMedianProfit.java`
- **Nombres de columna en SQX**: `Monkey Test` (Text), `Monkey Z-Score` (Decimal2) y `MonkeyMedianProfit` (Decimal2)

**Pasos de instalación:**

1. Asegúrate de que los ficheros de columna estén presentes en `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Reinicia SQX (o fuerza la recompilación de snippets) para que las columnas se registren.
3. En la vista de Databank, abre el selector de columnas y añade las columnas correspondientes.

> Sin las Databank Columns instaladas, el test se sigue ejecutando y filtra estrategias mediante la columna `FiltersResult`, pero los resultados individuales no serán visibles en la rejilla del databank.

### Claves publicadas

Los resultados se almacenan **por periodo**, usando una clave por sufijo de periodo, de modo que varias ejecuciones sobre periodos distintos pueden coexistir en la misma estrategia sin sobrescribirse:

| Clave | Contenido |
| :--- | :--- |
| `MonkeyTestResult<sufijo>` | Resultado de ese periodo (ver lista de estados abajo). |
| `MonkeyTestPercentile<sufijo>` | Percentil de rango alcanzado frente a la distribución de monos, p. ej. `85.20%`. |
| `MonkeyTestZScore<sufijo>` | Z-Score del beneficio real frente a la media/desviación de los monos. |
| `MonkeyTestMedianProfit<sufijo>` | Mediana de Net Profit obtenida por los monos en ese periodo. |
| `MonkeyTestRealProfit<sufijo>` | Beneficio de la estrategia reconstruido con la misma fórmula que los monos. |
| `MonkeyTestMode<sufijo>` | Modo de cálculo aplicado: `A (FixedSize)` o `B (Variable)`. |
| `MonkeyTestSpread<sufijo>` | Spread efectivamente aplicado, en points. |
| `MonkeyTestExposureRatio<sufijo>` | Exposición total de los monos frente a la de la estrategia original (debe estar muy cerca de 1). |
| `MonkeyTest_SegCount_<PERIODO>` | Metadato: número total de sub-segmentos creados para el periodo. |
| `MonkeyTest_SegDays_<PERIODO>` | Metadato: duración real promedio en días de cada sub-segmento. |
| `MonkeyTest_SegTargetDays` | Metadato: días de duración objetivo solicitados en Input Args. |

Sufijos válidos: `_IS`, `_OOS`, `_ISV`, `_OOS1`..`_OOS10`, `_ISV1`..`_ISV10`, `_Full`, así como los sufijos segmentados `_Seg_<LABEL>_<J>`.

Las columnas resuelven el sufijo automáticamente a partir del **selector de sample type del Databank**. La resolución es **estricta**: si un periodo no se ha evaluado, la columna muestra `N/A` en vez de caer al valor de otro periodo. Las claves numéricas sólo se escriben cuando el test se completó, de modo que un `LOW TRADES` muestra `N/A` y nunca un engañoso `0.00`.

> **Auditoría del cálculo**: no se publica una clave con el Net Profit de SQX porque la columna nativa **Net Profit** ya responde al selector de sample type. Poniéndola junto a `MonkeyTestRealProfit` se comprueba directamente la calidad de la reconstrucción: en MODO A ambas deben coincidir al céntimo.

### Estados de la columna Monkey Test

* `PASSED`: el beneficio neto de la estrategia superó el percentil definido de las ejecuciones aleatorizadas.
* `FAILED`: la estrategia no superó el umbral de percentil.
* `LOW TRADES`: la estrategia tiene menos de 20 operaciones en ese periodo. También aparece cuando el periodo contiene cero operaciones, lo que normalmente significa que el backtest no se configuró con ese periodo muestral.
* `FAILED (INVALID PERIOD)`: se pidió un segmento numerado que no existe en la estrategia.
* `INSUFFICIENT SPACE`: las operaciones no caben en el periodo sin solaparse. Teóricamente inalcanzable si la estrategia original no solapa operaciones; delata datos corruptos.
* `FAILED (NO DATA)`: faltaba el fichero histórico `.dat` del símbolo/timeframe.
* `ERROR`: ocurrió un error de ejecución inesperado.

### Invariantes del layout (siempre activas)

En cada mono se comprueban tres invariantes sobre el reparto de las entradas. **No hay que activarlas**: corren siempre, calladas, y sólo emiten un `WARN` en el log de SQX si alguna se viola.

| Invariante | Qué garantiza |
| :--- | :--- |
| **A1** | Cero solapamiento: `entrada[k] ≥ entrada[k-1] + duración[k-1]` |
| **A2** | Todo dentro de la ventana: la primera entrada no cae antes de `idxMin` y la última salida no pasa de `idxMax` |
| **A3** | El dithering repartió exactamente las barras planificadas: `Σduración == n·baseBars + numExtra` |

Un `WARN` de A1, A2 o A3 **siempre es un bug del algoritmo de layout, nunca una condición de mercado ni un dato raro**. Si aparece, los resultados de ese periodo no son fiables. Se reporta sólo la primera violación de cada periodo para no inundar el log; el mensaje incluye el número de mono, la operación implicada y los valores concretos.

Corren en todos los monos y no sólo con `Debug` activo a propósito: si sólo se comprobasen en modo diagnóstico, una violación en producción pasaría desapercibida, que es justo el escenario que interesa detectar. El coste es lineal en el número de operaciones y despreciable frente a la propia simulación.

### Volcado de diagnóstico (`Debug`)

Con la palabra clave `Debug` se escribe `user/extend/Snippets/SQ/CustomAnalysis/MonkeyTest_v2_debug.log`, en dos bloques por periodo:

1. **`CALIBRATION`** — de dónde sale `K`: número de operaciones, `Σ|beneficio bruto|`, `Σ|desplazamiento|`, desplazamiento neto, comisiones totales y **el reparto de `CommSwapApplied` (cuántas órdenes true y cuántas false)**. Ese reparto es diagnóstico por sí solo: si sale mezclado dentro de un mismo periodo, conviene mirarlo con lupa. Incluye también el modo, el spread aplicado, el `tickSize`, la duración media y la ventana de barras.
2. **`LAYOUT (monkey #0)`** — el reparto completo del primer mono, una fila por operación con `k`, entrada, duración, salida y **`gapToPrev`**. Se vuelcan todas las operaciones y no una muestra, porque el objetivo es auditar el no-solapamiento a mano: **cualquier `gapToPrev` negativo es un solapamiento**. La cabecera indica además si A1/A2/A3 pasaron.

El volcado va a un fichero propio y no al log de SQX porque éste llega a cientos de MB al día y quedaría inservible. El escritor está sincronizado, ya que `Per Strategy Analysis` corre multihilo; cada línea lleva el nombre del hilo, que es el mismo identificador que aparece en el log de SQX y permite correlacionar ambos.

> El fichero se abre en modo **append** y no se rota ni se limpia solo. Conviene borrarlo entre ejecuciones para no mezclar diagnósticos de pasadas distintas.

### Columna Filters Result

* Dibuja un **PASSED verde** si el test pasa (y ningún otro filtro falló).
* Dibuja un **FAILED rojo** si la estrategia falla.
* **El veredicto proviene únicamente del periodo pedido en Input Args.** Cuando `FULL` calcula también los demás periodos, esos resultados adicionales se publican para inspección pero nunca afectan al veredicto.

### Exclusión de estrategias (`AutoDiscard`)

Marcar una estrategia como FAILED es puramente visual — nunca elimina nada por sí mismo. Que el motor de SQX reciba realmente la orden de excluir una estrategia fallida depende de la palabra clave `AutoDiscard`:

* **`AutoDiscard` ausente (por defecto): ninguna estrategia se excluye nunca.** `filterStrategy` siempre devuelve `true` al motor de SQX. Cada estrategia procesada se queda donde la tarea la habría puesto de todos modos, marcada con su resultado real.
* **`AutoDiscard` presente:** `filterStrategy` devuelve el veredicto real, dejando que el motor de SQX actúe en consecuencia.

Esto importa porque SQX tiene **dos mecanismos independientes** que pueden excluir una estrategia, y sólo uno se ve afectado por `AutoDiscard`:

1. **Copiar entre dos databanks distintos**: el motor sólo copia al databank de salida las estrategias para las que `filterStrategy` devolvió `true`. Sin `AutoDiscard`, una tarea que copia entre databanks distintos conservaría todas las estrategias, marcadas con su resultado.
2. **El checkbox nativo "Filter by results of custom analysis"**: sólo relevante cuando el databank de entrada y el de salida son el **mismo**. Borra las estrategias fallidas de ese databank, pero **sólo si `AutoDiscard` también hace que `filterStrategy` devuelva `false`**.

### Ficheros de Caché para el ResultsPlugin (schema v4)

Cuando la palabra clave `ResultsPluginCache` está presente, el snippet escribe dos artefactos por estrategia en `user/extend/ResultsPlugins/DatabankMonkeyTest/cache/`:

* **`[NombreEstrategia]_monkey_simulation_data.csv`** — hasta 50 curvas de equity representativas de los monos, seleccionadas de la distribución completa (la más baja, la más alta y hasta 48 intermedias espaciadas uniformemente por percentil). Cada fila es el recorrido de balance completo de un mono: `monkey_id;b0;b1;...;bT`.
* **`[NombreEstrategia]_monkey_simulation_data.meta.json`** — todos los KPIs escalares más el array completo de beneficios de los monos.

Campos añadidos en el schema v4 respecto al v3: `mode` (modo de cálculo aplicado), `ratioPerUnit` (el valor de `K`), `spreadPoints` y `exposureRatio`. Todos los campos del v3 se mantienen para que el plugin siga leyendo la caché.

> **Un par de caché por estrategia — sólo para el periodo pedido.** El ResultsPlugin localiza estos ficheros únicamente por el nombre de la estrategia, así que sólo puede existir un par por estrategia. Cuando `FULL` evalúa todos los periodos, la caché se escribe **exclusivamente para el periodo pedido en Input Args**. El campo `period` dentro de `meta.json` siempre identifica a cuál corresponde.

---

## 5. Decisiones de Diseño y Por Qué

Esta sección documenta por qué ciertas alternativas aparentemente mejores se descartaron a propósito. Sin estas razones es previsible que alguien las "arregle" en el futuro, rompiendo la metodología.

### 5.1. K es una constante única, no un ratio heredado por operación

Es técnicamente viable valorar la operación `k` de cada mono con el ratio de la operación `k` de la estrategia original (`suma(pk x Kk)` en vez de `K x suma(pk)`), al mismo coste computacional, y eso reduciría el error de aproximación en MODO B.

**Se descarta deliberadamente.** Haría que dos desplazamientos idénticos valiesen distinto según a qué operación original les tocara emparejarse, de modo que un mono que cayera sobre una operación con un stop loss anormalmente lejano vería su resultado dominado por ella. Eso inyecta en la distribución varianza procedente de la gestión de riesgo de la estrategia, cuando lo que el test mide es exclusivamente la calidad del *timing*. Con `K` global, todas las operaciones de todos los monos pesan lo mismo en términos monetarios a lo largo del periodo, y la dispersión refleja únicamente el azar de las entradas.

### 5.2. La calibración se pondera por magnitud, no por el neto

`K` podría calcularse como `beneficio bruto total / desplazamiento total`. Cuando el lotaje es fijo, esa fórmula y la ponderada por magnitud dan **exactamente el mismo número**, porque para cada operación se cumple `beneficio = K x desplazamiento` con la misma `K` en ganadoras y perdedoras.

Ejemplo con lote fijo y `K` real de 10 EUR/pip:

| Operación | Pips | EUR bruto |
| :--- | :--- | :--- |
| 1 | +50 | +500 |
| 2 | -30 | -300 |
| 3 | +20 | +200 |
| 4 | -38 | -380 |
| **Neto** | **+2** | **+20** |

Neto entre neto: `20 / 2` = 10 EUR/pip. Ponderado por magnitud: `1380 / 138` = 10 EUR/pip. Idénticos.

La diferencia aparece cuando el desplazamiento neto se acerca a cero. Si la operación 4 pierde 40 pips en lugar de 38, el neto en pips es 0 y el neto en euros también: la fórmula neto entre neto daría `0/0` y la estrategia sería inevaluable. La ponderada por magnitud da `1400 / 140` = 10 EUR/pip, correcta como siempre.

**El signo de cada operación no se pierde**: vive en el desplazamiento acumulado de cada mono, no en `K`. `K` es un factor de conversión de unidades y por naturaleza es positivo; un mono que acumule desplazamiento negativo obtiene un beneficio negativo.

### 5.3. El money management declarado manda, sin degradar a MODO B

Si el XML declara `FixedSize` pero los lotajes observados varían, el snippet registra una advertencia pero **mantiene el MODO A**. Elegir mal el modo no es neutral.

MODO B normaliza cada desplazamiento por el precio de entrada, así que sobre una estrategia de lote fijo pondera de más las operaciones abiertas a precios bajos. Ejemplo con lote fijo a 10 EUR/punto:

| Operación | Precio de entrada | Desplazamiento | EUR | % |
| :--- | :--- | :--- | :--- | :--- |
| 1 | 10.000 | +100 pts | +1.000 | 1,0% |
| 2 | 20.000 | +100 pts | +1.000 | 0,5% |

`K` en MODO A = 2000/200 = 10 EUR/punto. `K` en MODO B = 2000/1,5 = 1.333,33 EUR/%.

Un mono que haga sus dos operaciones cerca de 10.000, ambas +100 puntos, obtendría **2.000 EUR** en MODO A (correcto) y **2.666,67 EUR** en MODO B: un 33% de sobrestimación. La divergencia crece con lo que haya derivado el precio del activo durante el periodo.

### 5.4. El spread hay que aplicarlo explícitamente

A diferencia del Custom Analysis de CV Sintética — que instancia el `BacktestEngine` de SQX con un `ChartSetup` y por tanto hereda el spread gratis — este snippet ejecuta una simulación propia sobre las velas crudas del fichero `.dat`. **Nada aplica el spread si no lo aplica el propio código.** Conviene tenerlo presente para no acabar contándolo dos veces, o ninguna.

### 5.5. El spread se resta al mono, no se suma a la estrategia original

Los precios de las órdenes de SQX ya incorporan el spread; las velas del `.dat` no. Igualar ambos lados admite dos soluciones simétricas: restarlo al mono, o devolvérselo a la estrategia original.

Se resta al mono porque replica los costes reales de operar. Sumarlo a la original mediría un edge bruto que ningún operador puede capturar en la práctica.

### 5.6. Spread fijo del XML, nunca el spread real por vela

Los ficheros de datos de SQX pueden almacenar el spread histórico barra a barra, y la estructura que el snippet ya utiliza para leer las velas lo expone. Sería un dato más rico.

**No se usa.** La estrategia original se backtesteó con el valor fijo configurado en el XML, que es el coste que realmente sufrió. Aplicar a los monos un spread distinto los pondría a operar bajo condiciones que la referencia nunca tuvo, rompiendo justamente la comparabilidad que este test necesita preservar. El dato más rico sería aquí el dato equivocado.

### 5.7. El veredicto se emite sobre el beneficio reconstruido

El beneficio de la estrategia original se recalcula con la misma fórmula que el de los monos, en lugar de tomar el Net Profit que reporta SQX. Así, cualquier error de aproximación afecta por igual a ambos lados de la comparación.

En MODO A el valor reconstruido coincide al céntimo con el Net Profit real, porque `K` es exacta cuando el lotaje es constante. Poner `MonkeyTestRealProfit` junto a la columna nativa **Net Profit** permite auditarlo de un vistazo: una divergencia en MODO A delata un money management mal declarado.

### 5.8. La duración se mide como posición fraccionaria en el eje de barras

Hay dos formas erróneas de medir la duración de una operación, y ambas producen sesgos reales:

* **Tiempo de calendario dividido por la duración de la barra**: una operación H4 abierta el viernes a las 20:00 y cerrada el lunes a las 04:00 son 56 horas, que esa fórmula convierte en 14 barras cuando en el mercado sólo existen unas 2. Los fines de semana y festivos se cuentan como barras inexistentes e inflan la duración objetivo.
* **Diferencia de índices de barra enteros**: cuantiza cada operación por separado antes de promediar. Con una estrategia H4 backtesteada a precisión M1 — el caso habitual cuando no se pasa `Precision=M1` al test —, una operación de 6h15m y otra de 7h50m darían ambas 1 barra; la media saldría 1,0 exacta y **el dithering no tendría nada que repartir**, dejando al mono expuesto 4 horas por operación frente a las 6h15m reales.

La cuantización debe ocurrir una sola vez, sobre la media, nunca sobre cada operación.

### 5.9. Los monos no cierran los viernes

La estrategia original puede tener un cierre forzoso de fin de semana, y sus operaciones vienen ya acortadas por él. Como la duración media objetivo se calcula sobre esas operaciones, ese efecto **ya está incorporado**. Volver a aplicar el corte a los monos los penalizaría por segunda vez y los dejaría sistemáticamente menos expuestos que la referencia.

Además, al medir en barras el fin de semana desaparece del cómputo: una operación de N barras son N barras de mercado, cruce o no el sábado en el calendario. La exposición **de mercado** queda replicada sin necesidad de cortar nada.

### 5.10. El dithering usa una permutación aleatoria con conteo fijo

Las barras extra se reparten al azar entre operaciones distintas en cada mono, en lugar de recaer siempre sobre las primeras. Como las operaciones van en orden cronológico, asignarlas por índice concentraría invariablemente las operaciones largas al inicio del periodo, y de forma idéntica en todos los monos.

**No debe sustituirse por una probabilidad independiente por operación.** Parece más aleatorio, pero el número de operaciones largas pasaría a seguir una distribución binomial y la exposición temporal total variaría de un mono a otro, perdiendo la propiedad que los hace comparables entre sí y con la estrategia.

### 5.11. La separación entre entradas es la duración real de cada operación

Es lo que garantiza matemáticamente que las N operaciones quepan en el periodo. Si la estrategia original no solapa operaciones, se cumple que la suma de sus duraciones es menor o igual que la longitud del periodo — pero esa garantía sólo se hereda si la separación mínima no se redondea al alza de forma uniforme.

---

## 6. Diferencias frente a la v1.00

La versión 1.00 se conserva como `MonkeyTest_v1_00.java` y sigue apareciendo en el desplegable de SQX, lo que permite ejecutar ambas sobre el mismo databank y comparar resultados.

| Aspecto | v1.00 | v2.00 |
| :--- | :--- | :--- |
| Cálculo del P&L | Operación a operación, reconstruyendo el valor del pip y escalando el lotaje | Calibración única por periodo (`K`) |
| `replicationMode` | `SLTP` / `AvgBars` / `IndivBars` | Eliminado: siempre duración media diteada |
| `shiftingMode` | `Constant` / `Random` | Eliminado: siempre aleatorio sin solapamiento |
| Solapamiento entre operaciones | Posible cuando la parte fraccionaria de la duración media era menor de 0,5 | Imposible por construcción |
| Ventana del periodo | Las operaciones podían terminar fuera de la ventana evaluada | Siempre dentro |
| Número de operaciones | Podía recortarse, comparando contra las N primeras de la estrategia | Siempre el mismo que la estrategia |
| Duración de las operaciones | Tiempo de calendario entre duración de barra (contaba fines de semana) | Posición fraccionaria en el eje de barras |
| Reparto del dithering | Siempre a las primeras operaciones, igual en todos los monos | Permutación aleatoria distinta en cada mono |
| Cierre de viernes | Aplicado a los monos (doble penalización) | Eliminado |
| Spread | No se aplicaba | Se resta a cada operación simulada |
| Comisiones y swaps | Prorrateadas por operación, restadas siempre | Totales de la estrategia, respetando `CommSwapApplied` |
| Modo de cálculo | Heurístico sobre el XML y los lotajes | Money management declarado, vía API de SQX |
| Valores numéricos fallidos | Se publicaba `0.00` en `LOW TRADES` / `ERROR` | Se publica `N/A` |
