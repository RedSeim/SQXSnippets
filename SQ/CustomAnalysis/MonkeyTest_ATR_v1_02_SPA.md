# Monkey Test ATR v1.02 - Snippet de Custom Analysis

Un test de Permutación Monte Carlo para StrategyQuant X (SQX) que mide el **edge geométrico** de una estrategia — su capacidad de anticipar desplazamiento de precio por unidad de exposición — comparándolo contra el azar, sin convertir nunca a dinero.

> ⚠️ **Los resultados de la v1.02 no son comparables con los de la v1.00.** Los monos de esta versión barajan su secuencia de direcciones y replican la exposición de cada sentido por separado, así que la distribución de referencia es distinta —y más exigente— que la de la versión anterior. Percentiles y Z-Scores calculados con v1.00 deben recalcularse; no tiene sentido mezclarlos en la misma tabla. El motivo de ambos cambios está en [5.15](#515-la-secuencia-de-direcciones-se-baraja-en-cada-mono) y [5.16](#516-la-exposición-se-replica-por-dirección-no-sólo-en-total).

---

## 1. Qué es este test y por qué existe

### 1.1. La idea del Monkey Test

El **Monkey Test** es un método de validación estadística que determina si el rendimiento histórico de una estrategia es una ventaja genuina (entradas y salidas precisas) o simplemente suerte: por ejemplo, haber operado durante una tendencia fuerte y prolongada donde cualquier entrada aleatoria habría ganado dinero.

Simula "monos" que ejecutan el mismo número de operaciones que la estrategia original, con el mismo número de operaciones en cada dirección y la misma exposición total al mercado en cada una de ellas, pero con **entradas colocadas al azar** a lo largo del periodo analizado y **en orden aleatorio**. Si la estrategia real supera a un percentil alto de estas ejecuciones aleatorizadas, pasa el test.

Dicho de otro modo, al mono se le entregan exactamente los mismos "materiales" que tuvo la estrategia —cuántas veces operó en cada sentido y cuánto tiempo estuvo expuesto en cada uno— y se le quita lo único que se quiere medir: saber *cuándo* y *en qué orden* usarlos.

### 1.2. El problema que este test resuelve

La forma más natural de medir ese edge es **en dinero**: comparar el beneficio de la estrategia contra el de los monos. Y con lotaje fijo, comparar beneficios equivale exactamente a comparar la suma de los desplazamientos en pips de todas las operaciones, porque cada pip vale siempre lo mismo.

Y ahí está el problema: **esa suma no pondera todas las operaciones por igual**.

Si la volatilidad relativa de un activo es más o menos constante, su volatilidad **en pips absolutos** crece con el nivel de precio. Un movimiento del 1% son 100 pips cuando el activo cotiza a 10.000 y 200 pips cuando cotiza a 20.000. Así que en un activo con deriva de precio —un índice a largo plazo, oro, cripto, una acción en crecimiento— **las operaciones ejecutadas en el tramo de precios altos pesan aritméticamente mucho más en el total**, aunque en términos relativos hayan capturado lo mismo.

La consecuencia es un falso positivo perfectamente posible: una estrategia que acertó el timing *sólo* en el tramo de precios elevados, y que lo hizo peor que los monos durante la mayor parte del histórico, puede aun así salir PASSED porque ese puñado de operaciones domina la suma.

Dicho de otro modo, el test deja de medir esto:

> **Edge**: capacidad de generar retornos por unidad de exposición al mercado, en comparación con el azar.

y pasa a medir esto otro, sin declararlo:

> Capacidad de generar retornos por unidad de exposición, en comparación con el azar, **en periodos de precio elevado del activo**.

Son dos magnitudes distintas. Y la segunda es precisamente el tipo de ventaja dependiente del régimen de mercado que el Monkey Test declara existir para detectar.

### 1.3. La solución: normalizar por ATR

Este test divide el desplazamiento de cada operación por el **ATR vigente en el momento de su entrada**, y no convierte nunca a dinero. La unidad pasa a ser *"cuántos ATR de movimiento capturó esta operación"*, que es adimensional y comparable a lo largo de todo el histórico.

Comparación rápida de las tres aproximaciones posibles:

| Aproximación | Qué corrige | Qué no corrige |
| :--- | :--- | :--- |
| **Monetaria** | Nada — es la medida natural, y reconstruye con exactitud el dinero real que generó la estrategia. | Sujeta al sesgo de nivel de precio descrito arriba. Obliga además a tratar de forma distinta las estrategias de lotaje fijo y las de lotaje variable. |
| **Porcentual** | El nivel de precio: normalizar por el precio de entrada iguala el peso de un mismo movimiento relativo esté el activo alto o bajo. | El **régimen de volatilidad local**. Dos entradas al mismo precio, una en plena consolidación y otra en expansión de volatilidad, se tratan igual pese a que el desplazamiento esperado es muy distinto. Es una aproximación, no una corrección completa. |
| **ATR** (la de este test) | Ambos a la vez: el ATR escala con el nivel de precio **y** con la volatilidad vigente en ese momento concreto. Es la magnitud que de verdad determina cuánto desplazamiento cabe esperar. | Nada de lo anterior. A cambio, deja de expresar dinero (ver [1.5](#15-alcance-y-limitaciones)). |

Como beneficio adicional, **el money management de la estrategia deja de importar**. Un test que mida en dinero necesita saber si el lotaje es fijo o variable para convertir desplazamiento en euros correctamente, y aplicar una fórmula distinta en cada caso. Aquí no se convierte nada, así que la misma fórmula sirve para cualquier estrategia sea cual sea su dimensionamiento.

Normalizar por volatilidad es además la práctica estándar en investigación cuantitativa —los "múltiplos de R" o de ATR existen justo para poder comparar operaciones entre regímenes, épocas o instrumentos distintos— y tiene una ventaja estadística: al quedar todos los términos de la suma en una magnitud típica parecida, la varianza se estabiliza y el percentil y el Z-Score resultan más interpretables.

### 1.4. El flujo de trabajo que motivó este diseño

Este test no nació como una herramienta genérica, sino para resolver un problema dentro de un flujo de desarrollo concreto — el de quien lo escribió. Conocerlo importa por dos razones: explica varias decisiones de diseño que de otro modo parecerían arbitrarias, y delimita en qué situaciones la aproximación encaja y en cuáles no.

Ese flujo tiene dos fases:

**Fase 1 — minado de reglas.** Se buscan reglas con ventaja estadística, todavía sin Stop Loss ni Take Profit. En SQX eso obliga a usar **lote fijo**, porque sin SL no hay ningún otro money management disponible. Es una limitación de la herramienta, no una decisión: ese lotaje se va a descartar en cuanto la regla avance. Este test evalúa esas reglas.

**Fase 2 — estrategias completas.** Sobre las reglas que sobrevivieron se construyen estrategias añadiendo gestión de riesgo, con **SL basado en ATR**, dejando que el optimizador busque el periodo apropiado en un rango amplio (ATR 50–350, u otros según el caso).

**Por qué eso lleva a medir en ATR y no en dinero.** En la fase 1 el lotaje fijo es un artefacto técnico, así que medir euros de un dimensionamiento que se va a tirar dice poco sobre lo que la regla valdrá después. Y como el destino es un SL proporcional a la volatilidad, existe una correspondencia aprovechable: cuando el tamaño de posición es inversamente proporcional a una medida de volatilidad —lo que ocurre al arriesgar una cantidad fija con `SL = k·ATR`—, el dinero generado es proporcional al desplazamiento normalizado por esa misma medida. Medir el edge geométrico anticipa entonces el resultado de la fase 2 **mejor que medir euros de un lote que no se va a usar**.

> **Matiz honesto, para no sobrevender el argumento.** El periodo de ATR usado aquí (14 por defecto) no tiene por qué coincidir con el que acabe usando el SL de la fase 2. Si el SL termina siendo `k·ATR(200)`, el dinero es proporcional al desplazamiento normalizado multiplicado por el cociente `ATR(14)/ATR(200)` — la estructura temporal de volatilidad, que fluctúa alrededor de 1 en vez de ser constante. La correspondencia es por tanto **aproximada y fuertemente positiva, no casi exacta**.
>
> Lo que sí es robusto a esa elección es **la corrección del sesgo, que es la razón de ser de este test**: cualquier ATR escala con el nivel de precio de la misma forma, así que el sesgo hacia los tramos de precio elevado desaparece se use el periodo que se use. El residuo es un cociente de volatilidades que revierte a la media y no deriva sistemáticamente con el precio.

**Por qué el periodo se fija y no se ata al del SL futuro**: en la fase 1 todavía no existe ningún SL, así que no hay "el periodo de ATR de esta estrategia" al que ajustarse. Y aunque lo hubiera, normalizar cada estrategia por su propio periodo haría que la métrica dejara de ser comparable entre estrategias del mismo databank, que es exactamente para lo que sirve. ATR(14) es el estándar de la industria — el original de Wilder — lo que hace la cifra interpretable sin explicaciones.

**Cuándo esta aproximación NO es la más adecuada.** El test está pensado y probado para el flujo de arriba; fuera de él conviene valorar si sigue siendo la herramienta correcta:

* **La estrategia ya está completa, con su gestión de riesgo real, y lo que quieres saber es cuánto dinero habría ganado frente al azar.** Ésa es una pregunta distinta y la responde mejor un test que mida en dinero.
* **El Stop Loss no es proporcional a la volatilidad** — fijo en pips, un porcentaje del precio, o basado en estructura como máximos y mínimos previos. La correspondencia entre edge geométrico y dinero se debilita, porque el tamaño de posición deja de escalar con el ATR.
* **Operas de verdad con lote fijo en producción**, no como artefacto de minado. Entonces el desplazamiento en pips absolutos **sí** es lo que determina tu P&L, y normalizar por ATR mide deliberadamente algo distinto de lo que vas a cobrar.
* **El instrumento no tiene deriva de precio apreciable** — por ejemplo un par de divisas que lleva décadas oscilando en un rango acotado. Ahí el sesgo que este test corrige es pequeño, y una medida monetaria es exacta además de directamente interpretable.

### 1.5. Alcance y limitaciones

Conviene ser explícito sobre lo que este test **no** garantiza:

- **No mide dinero.** Sus cifras son múltiplos de ATR, no euros. Para saber cuánto habría ganado una estrategia con su money management real hace falta un test que sí mida en dinero; en este proyecto existe uno complementario, descrito en la [sección 6](#6-relación-con-el-test-monetario-complementario).
- **Valida edge direccional a duración media diteada.** Que una regla pase **no garantiza** que un SL/TP concreto capture ese edge: añadir SL/TP cambia *cuándo* sale cada operación, no sólo cómo se dimensiona. Un TP demasiado ajustado puede cortar la ganancia antes de que se desarrolle; un SL demasiado ancho puede dejar correr pérdidas más allá de lo que el desplazamiento medio sugiere.
- **No sustituye a validar la estrategia completa en la fase 2.** Es un filtro de "¿merece la pena desarrollar esta regla?", no un veredicto final.

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
* **`FULL` también evalúa cada periodo por separado.** Además del agregado, sondea `IS`, `OOS`, `ISV` y cada segmento existente `OOS1..10` / `ISV1..10`, ejecutando el test de forma independiente sobre cada uno. Esto **no** multiplica el coste por el número de periodos: cada periodo sólo simula sus propias operaciones.
* **Con un único segmento OOS, `OOS` y `OOS1` son el mismo periodo** — SQX copia las estadísticas de uno sobre el otro. En ese caso el test se simula una sola vez y **el resultado se publica bajo los dos sufijos**, así que las columnas de OOS y de OOS1 muestran lo mismo. Da igual cómo hayas pedido el periodo: sirve tanto si lanzas con `FULL`, como con `OOS`, como con `OOS1`. Cuando la estrategia sí tiene varios segmentos OOS, el agregado y cada parte numerada son periodos distintos y **nunca** se publican cruzados.

### 2.3. Calcula el ATR

El ATR se calcula reproduciendo bit a bit el mismo algoritmo que usa el indicador ATR interno de SQX, en vez de una fórmula de manual aproximada. Esto elimina cualquier duda sobre si el valor coincide con el que vería el usuario en un gráfico de SQX: es el mismo cálculo, barra a barra, no una aproximación que converge con el tiempo.

La fórmula tiene un único denominador variable, `D_i = min(i+1, P)` (donde `i` es el índice de la vela dentro del histórico completo, empezando en 0, y `P` es el periodo, 14 salvo que se pase `ATRPeriod=N`):

```
TR_0  = high_0 - low_0                                              (la primera vela no tiene cierre anterior)
ATR_0 = TR_0

TR_i  = max(high_i - low_i, |high_i - close_(i-1)|, |low_i - close_(i-1)|)   para i ≥ 1
ATR_i = (ATR_(i-1) * (D_i - 1) + TR_i) / D_i                                  para i ≥ 1
```

Mientras `i+1` sea menor que `P` (es decir, durante las primeras `P-1` velas después de la primera), `D_i` crece con cada vela nueva y la fórmula equivale, en la práctica, a una media acumulativa de los valores de `TR` vistos hasta ese punto. En cuanto `i+1` alcanza `P`, `D_i` se congela en `P` y la fórmula pasa a ser el suavizado de Wilder de siempre, con denominador fijo. No hay una fase de semilla separada de la recursión: es la misma fórmula en todo el rango, con un denominador que crece hasta tocar techo y luego se mantiene constante.

Tres propiedades importantes:

* **Es causal, sin lookahead.** Una entrada en la barra `i` ocurre en su *apertura*, así que la única información disponible es la de las barras anteriores. Se usa el ATR de la **última barra completada**, nunca el de la barra que contiene el instante de entrada — cuyo máximo y mínimo todavía no se conocen. Vale igual para las operaciones reales y para las de los monos: cualquier asimetría aquí invalidaría la comparación.
* **Se calcula sobre el histórico completo**, no sobre el recorte del periodo, así que el calentamiento de `P` barras queda absorbido por los datos previos y nunca afecta a un periodo evaluable real.
* **Se calcula siempre sobre el timeframe principal**, incluso con `Precision=M1` (ver [2.6](#26-precisión-y-escala-del-atr)).

### 2.4. Mide el desplazamiento normalizado

**Operaciones reales** de la estrategia — sus precios ya incorporan el spread, porque SQX llena en ask/bid:

```
dirección   = corto ? -1 : +1
atrDisp_i   = ((precio de cierre - precio de apertura) * dirección) / ATR en la entrada
edgeReal    = suma de todos los atrDisp_i
```

**Operaciones de los monos** — las velas del `.dat` son precios crudos, así que hay que restar el spread. Aquí `dirección` es la que le tocó a esa operación **en el sorteo de ese mono** (ver [2.8](#28-baraja-los-pares-dirección-duración)), no la de la operación real que ocupa esa posición:

```
atrDisp_k   = ((precio de salida - precio de entrada) * dirección - spread) / ATR en la entrada
edgeMono    = suma de todos los atrDisp_k
```

**El veredicto se emite sobre la SUMA**, no sobre ninguna media: se compara `edgeReal` contra la distribución de las N sumas de los monos.

> Para el veredicto, suma y media son **matemáticamente equivalentes**, porque cada mono ejecuta exactamente el mismo número de operaciones que la estrategia. Dividir todos los valores por el mismo `n` no altera ni el percentil ni el Z-Score. La distinción sólo importa al comparar estrategias entre sí en el Databank, donde `n` sí varía — de ahí que se publiquen las dos cifras.

### 2.5. Aplicación del spread

El spread se resta **en unidades de precio y antes de normalizar**, porque es un desplazamiento de precio: entrar en ask y salir en bid equivale exactamente a penalizar el desplazamiento en un spread completo.

Se lee siempre del XML de configuración de la estrategia (`Data > Setups > Setup > Chart[@spread]`), en *points*, y se convierte a precio multiplicándolo por el `tickSize` del instrumento. Cuando hay varios `Setup` (cross-checks sobre mercados adicionales), se empareja por el atributo `@symbol` para no coger el spread del mercado equivocado.

### 2.6. Precisión y escala del ATR

`Precision=M1` hace que la simulación corra sobre velas de un minuto, lo que aumenta la resolución con la que se replican las duraciones. Pero **el ATR sigue calculándose sobre el timeframe principal de la estrategia**.

La razón es directa: un ATR(14) sobre M1 mide la volatilidad de los últimos 14 minutos, que no tiene nada que ver con la escala a la que opera una estrategia H4. Si el ATR cambiara con la precisión, la misma estrategia produciría cifras incomparables según cómo se lanzase el test.

Internamente, cada vela de la ventana de simulación se mapea a la última barra completada del gráfico principal. La consecuencia práctica es que **los valores de edge sí son comparables entre una ejecución con `Precision=M1` y otra sin ella**.

### 2.7. Calcula la duración de las operaciones de los monos

La duración se mide como **posición fraccionaria en el eje de barras**, no en tiempo de calendario:

```
posición(t) = índice de la barra que contiene t + fracción transcurrida dentro de esa barra
duración    = posición(cierre) - posición(apertura)
```

Al apoyarse en el índice de barra, los fines de semana y festivos no cuentan (los índices son contiguos aunque el calendario salte), mientras que el término fraccionario conserva la resolución sub-barra del backtest original.

**La media se calcula por separado para cada dirección.** Las operaciones en largo forman una bolsa y las que van en corto otra, cada una con su propia duración media. No es un detalle: promediar ambas juntas borraría la asimetría direccional de la exposición y abriría un falso positivo — ver [5.16](#516-la-exposición-se-replica-por-dirección-no-sólo-en-total).

Cuando la duración media de una bolsa no es un número entero de barras, se aplica el **dithering determinista**: si la media es de 1,5625 barras H4 (6h15m), imposible de replicar con duraciones enteras, con 16 operaciones se asignan **9 operaciones de 2 barras y 7 de 1 barra**, lo que suma exactamente 25 barras = 100 horas. Redondear a 2 barras daría 32 barras (+28% de sobreexposición); truncar a 1 daría 16 (-36%).

Las operaciones que reciben la barra extra se eligen **al azar y sin repetición en cada mono**, de modo que las de mayor duración quedan repartidas por todo el periodo en lugar de concentrarse en una zona. Cuántas la reciben es siempre exactamente el mismo número, así que la exposición total es idéntica en todos los monos y difiere de la de la estrategia original en **como máximo media barra por bolsa sobre el periodo completo**.

### 2.8. Baraja los pares dirección-duración

Llegado este punto el mono tiene N operaciones, cada una con su dirección y su duración. Antes de colocarlas en el tiempo, **los pares (dirección, duración) se barajan al completo en cada mono**, con una permutación aleatoria uniforme.

Barajar los dos valores **juntos**, como una pareja indivisible, es lo que hace que:

* **El orden sea completamente aleatorio.** La secuencia de largos y cortos a lo largo del tiempo es distinta en cada mono y no guarda relación con la de la estrategia original.
* **Los conteos se conserven.** Una permutación no puede cambiar cuántos elementos de cada tipo hay: el número de operaciones en largo y en corto es exactamente el de la estrategia.
* **La exposición de cada dirección se conserve.** Como cada duración viaja pegada a la dirección de la bolsa que la generó, el total de barras en largo y el total en corto siguen siendo los mismos por mucho que cambie el orden.

### 2.9. Coloca las entradas sin solapamiento

Las N entradas se distribuyen dentro de la ventana del periodo repartiendo al azar la holgura sobrante en huecos entre operaciones. Esto garantiza:

* **Cero solapamiento**: la separación mínima entre entradas consecutivas es la duración real de la operación anterior.
* **Todo dentro del periodo**: la última operación termina dentro de la ventana evaluada.
* **Mismo número de operaciones** que la estrategia original, siempre.

### 2.10. Evaluación estadística por percentil

Compara `edgeReal` frente a la distribución de los N monos. Si supera el umbral de percentil definido, la estrategia pasa. Se calculan la media, la desviación típica (n−1), el Z-Score, el percentil de rango y la mediana de la distribución de los monos.

---

## 3. Cómo Usarlo y Argumentos de Entrada

### Configuración en StrategyQuant X

#### 1. Tarea de Custom Analysis

1. Añade una tarea de **Custom Analysis** a tu proyecto.
2. En **Analysis type**, selecciona **Per Strategy Analysis** (esto habilita el cómputo multihilo usando todos los núcleos de CPU disponibles).
3. Selecciona **MonkeyTest_ATR_v1_02** como método de análisis en el desplegable.
4. En el campo **Input Args**, configura tus parámetros como una cadena separada por comas: `numMonkeys,percentile,period`, más las palabras clave opcionales que necesites.

#### 2. Pestañas de Ranking y Retests del Builder

Como el snippet usa la firma `Per Strategy Analysis`, también puedes seleccionar **MonkeyTest_ATR_v1_02** en el desplegable de filtro de **Custom Analysis** en:

* La pestaña **Ranking** de la configuración de Builder/Genético (para descartar estrategias automáticamente durante la generación).
* La configuración de **Retests** (para descartar estrategias tras retestearlas sobre datos nuevos).

### Argumentos de Entrada

| Parámetro | Valor por Defecto | Descripción | Ejemplo |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | El número de simulaciones aleatorizadas de monos a ejecutar por estrategia. | `1000` |
| **percentile** | `95.0` | El umbral de confianza estadística. La estrategia debe superar este porcentaje de ejecuciones de monos para pasar. | `70.0` |
| **period** | `FULL` | Ventana muestral donde se ejecuta el test: `FULL` (backtest completo — **y además cada periodo por separado**), `IS`, `OOS`, `ISV`, o sub-periodos numerados (`OOS1`..`OOS10`, `ISV1`..`ISV10`). Este valor también decide qué periodo determina el veredicto PASSED/FAILED. Si la estrategia tiene un único segmento OOS, `OOS` y `OOS1` son intercambiables: pidas el que pidas, el resultado aparece en las dos columnas ([2.2](#22-filtra-por-periodo-muestral)). | `OOS2` |
| **ATRPeriod=N** | `14` | Palabra clave opcional, no posicional. Periodo del ATR usado para normalizar. No hay que tocarlo en el uso normal: existe para poder comprobar que el ranking no depende críticamente del periodo elegido. Debe ser ≥ 2. | `500,70,OOS2,ATRPeriod=50` |
| **AutoDiscard** | *(ausente)* | Palabra clave opcional, detectada como subcadena sin distinguir mayúsculas en cualquier punto de la cadena. Controla si `filterStrategy` puede indicarle al motor de SQX que excluya la estrategia cuando el test falla. **Ausente por defecto: ninguna estrategia se excluye nunca**, sea PASSED o FAILED. | `500,70,OOS2,AutoDiscard` |
| **Precision=M1** / **M1** | *(ausente: timeframe principal)* | Palabra clave opcional. Ejecuta la simulación sobre datos de velas de 1 minuto, lo que aumenta la resolución con la que se replica la duración. **No afecta a la escala del ATR** ([2.6](#26-precisión-y-escala-del-atr)). Si los datos de 1 minuto no están disponibles, se emite una advertencia y se vuelve al timeframe principal. | `500,70,FULL,Precision=M1` |
| **SegmentDuration=N** | *(ausente: off)* | Palabra clave opcional (ej. `SegmentDuration=300`). Divide el periodo analizado en sub-segmentos continuos ajustados a N días y ejecuta el test sobre cada uno, de forma adicional e independiente al test principal. | `500,70,FULL,SegmentDuration=300` |
| **Debug** | *(ausente)* | Palabra clave opcional, mismas reglas de detección. Escribe un volcado de diagnóstico en `user/extend/Snippets/SQ/CustomAnalysis/MonkeyTest_ATR_v1_debug.log` (ver [sección 4](#volcado-de-diagnóstico-debug)). Sólo vuelca el **primer mono** de cada periodo, y nunca los sub-segmentos de `SegmentDuration`. | `500,70,OOS2,Debug` |

> **Argumentos que este test no acepta:** `ResultsPluginCache` (ver [sección 4](#por-qué-no-hay-caché-para-el-resultsplugin)), `replicationMode` (`SLTP` / `AvgBars` / `IndivBars`) y `shiftingMode` (`Constant` / `Random`). Los tres pertenecen a otros Monkey Test de este proyecto. Si reutilizas la configuración de una tarea que los pasaba, el snippet los detecta, los ignora y emite una advertencia en el log explicando que aquí no aplican.

*Ejemplo de Input Args:* `500,70,OOS2` ejecuta 500 monos sobre las operaciones de OOS2 con un umbral del 70%. Para probar la sensibilidad al periodo de ATR: `500,70,OOS2,ATRPeriod=200`.

> **Nota sobre la Precisión de Datos:** la opción de precisión seleccionada en la configuración general del backtest de SQX **no influye** en la fuente de datos que lee este Custom Analysis. Para forzar la lectura de los datos de 1 minuto es **imprescindible** incluir explícitamente la palabra clave `Precision=M1` o `M1`.

---

## 4. Salidas Esperadas

### Requisito: Instalar las Columnas de Databank

El snippet de Custom Analysis sólo escribe resultados en los metadatos de la estrategia. Para **mostrar** esos resultados como columnas en el databank de SQX, también debes instalar y activar los snippets complementarios de **Databank Column**:

| Fichero | Columna en SQX | Qué muestra |
| :--- | :--- | :--- |
| `SQ/Columns/Databanks/MonkeyTestColumn.java` | `Monkey Test` (Text) | Percentil alcanzado o estado de fallo |
| `SQ/Columns/Databanks/MonkeyTestZScoreColumn.java` | `Monkey Z-Score` (Decimal2) | Z-Score frente a la distribución de monos |
| `SQ/Columns/Databanks/MonkeyATRNormPipsProfit.java` | `Monkey ATR Normalized Pips Profit` (Decimal2) | La suma sobre la que se emite el veredicto |
| `SQ/Columns/Databanks/MonkeyATREdgePerTrade.java` | `Monkey ATR Edge Per Trade` (Decimal2) | Esa suma dividida entre el número de operaciones |

**Pasos de instalación:**

1. Asegúrate de que los ficheros de columna estén presentes en `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Reinicia SQX (o fuerza la recompilación de snippets) para que las columnas se registren.
3. En la vista de Databank, abre el selector de columnas y añade las columnas correspondientes.

> Sin las Databank Columns instaladas, el test se sigue ejecutando y filtra estrategias mediante la columna `FiltersResult`, pero los resultados individuales no serán visibles en la rejilla del databank.

### Por qué dos de las columnas tienen nombre genérico

Este proyecto incluye un segundo Monkey Test, `MonkeyTest_v2_00`, que mide lo mismo pero **en dinero** (ver [sección 6](#6-relación-con-el-test-monetario-complementario)). Las columnas `Monkey Test` y `Monkey Z-Score` **sirven a los dos**: el percentil y el Z-Score significan exactamente lo mismo en ambos —posición de la estrategia frente a la distribución de los monos—, y sólo cambia la magnitud subyacente que se comparó, así que duplicarlas no aportaría nada.

**Si has ejecutado los dos tests sobre la misma estrategia, manda el resultado de éste.** Ninguno de los dos borra las claves del otro, así que un databank puede arrastrar resultados antiguos del test monetario; con la precedencia inversa, un resultado recién calculado quedaría oculto tras uno rancio.

Existe además una tercera columna, `MonkeyMedianProfit`, que pertenece **sólo** al test monetario porque está en euros. Mostrará `N/A` si únicamente has ejecutado este test.

### Por qué hacen falta las dos columnas de edge

El número de operaciones varía mucho entre estrategias, y eso hace que las dos cifras cuenten historias distintas:

* **`Monkey ATR Normalized Pips Profit`** (la suma) es lo que mapea al dinero de la fase 2 — con dimensionamiento por ATR, el dinero total es proporcional a esta suma. Pero mezcla calidad del edge con frecuencia operativa: una regla mediocre con 233 operaciones puede superar en total a una excelente con 37.
* **`Monkey ATR Edge Per Trade`** (la media) aísla la calidad del edge por unidad de exposición, así que una buena regla de baja frecuencia no queda enterrada. Un `0,42` significa que cada operación capturó, de media, 0,42 veces la volatilidad típica vigente en el momento de su entrada.

Leídas juntas revelan de dónde viene el edge, que es justo lo que hace falta para decidir qué reglas pasan a la fase 2.

### Claves publicadas

Los resultados se almacenan **por periodo**, usando una clave por sufijo, de modo que varias ejecuciones sobre periodos distintos pueden coexistir en la misma estrategia sin sobrescribirse:

| Clave | Contenido |
| :--- | :--- |
| `MonkeyATRResult<sufijo>` | Resultado de ese periodo (ver lista de estados abajo). |
| `MonkeyATRPercentile<sufijo>` | Percentil de rango alcanzado frente a la distribución de monos, p. ej. `85.20%`. |
| `MonkeyATRZScore<sufijo>` | Z-Score del edge real frente a la media/desviación de los monos. |
| `MonkeyATRNormPipsProfit<sufijo>` | Suma de los desplazamientos normalizados por ATR — la magnitud del veredicto. |
| `MonkeyATREdgePerTrade<sufijo>` | Esa suma dividida entre el número de operaciones. |
| `MonkeyATRMedianNormPips<sufijo>` | Mediana del edge obtenido por los monos en ese periodo. |
| `MonkeyATRSpread<sufijo>` | Spread efectivamente aplicado, en points. |
| `MonkeyATRExposureRatio<sufijo>` | Exposición total de los monos frente a la de la estrategia original (debe estar muy cerca de 1). |
| `MonkeyATRPeriod<sufijo>` | Periodo de ATR usado en el cálculo. |
| `MonkeyTest_SegCount_<PERIODO>` | Metadato: número total de sub-segmentos creados para el periodo. |
| `MonkeyTest_SegDays_<PERIODO>` | Metadato: duración real promedio en días de cada sub-segmento. |
| `MonkeyTest_SegTargetDays` | Metadato: días de duración objetivo solicitados en Input Args. |

Sufijos válidos: `_IS`, `_OOS`, `_ISV`, `_OOS1`..`_OOS10`, `_ISV1`..`_ISV10`, `_Full`, así como los sufijos segmentados `_Seg_<LABEL>_<J>`.

Las columnas resuelven el sufijo automáticamente a partir del **selector de sample type del Databank**. La resolución es **estricta**: si un periodo no se ha evaluado, la columna muestra `N/A` en vez de caer al valor de otro periodo. Las claves numéricas sólo se escriben cuando el test se completó, de modo que un `LOW TRADES` muestra `N/A` y nunca un engañoso `0.00`.

> Con un único segmento OOS verás el mismo resultado duplicado bajo `_OOS` y `_OOS1`. **No es un error**: son el mismo tramo, y publicarlo bajo ambos sufijos es lo que evita que una de las dos columnas quede en `N/A` ([2.2](#22-filtra-por-periodo-muestral)).

> **Los valores absolutos no son comparables entre ejecuciones con `ATRPeriod` distinto**: un ATR más largo es típicamente mayor, así que el mismo desplazamiento produce menos "ATRs capturados". El percentil y el Z-Score sí siguen siendo comparables, porque ambos lados de la comparación usan el mismo denominador. Por eso el periodo empleado se publica como clave.

### Estados de la columna Monkey Test

* `PASSED`: el edge de la estrategia superó el percentil definido de las ejecuciones aleatorizadas.
* `FAILED`: la estrategia no superó el umbral de percentil.
* `LOW TRADES`: la estrategia tiene menos de 20 operaciones en ese periodo. También aparece cuando el periodo contiene cero operaciones, lo que normalmente significa que el backtest no se configuró con ese periodo muestral.
* `FAILED (INVALID PERIOD)`: se pidió un segmento numerado que no existe en la estrategia.
* `INSUFFICIENT SPACE`: las operaciones no caben en el periodo sin solaparse. Teóricamente inalcanzable si la estrategia original no solapa operaciones; delata datos corruptos.
* `FAILED (NO DATA)`: faltaba el fichero histórico `.dat` del símbolo/timeframe.
* `ERROR`: error inesperado, o datos degenerados — por ejemplo un ATR mediano en el suelo de un tick, que indica velas rellenadas o corruptas.

### Columna Filters Result

* Dibuja un **PASSED verde** si el test pasa (y ningún otro filtro falló).
* Dibuja un **FAILED rojo** si la estrategia falla.
* **El veredicto proviene únicamente del periodo pedido en Input Args.** Cuando `FULL` calcula también los demás periodos, esos resultados adicionales se publican para inspección pero nunca afectan al veredicto.

### Exclusión de estrategias (`AutoDiscard`)

Marcar una estrategia como FAILED es puramente visual — nunca elimina nada por sí mismo. Que el motor de SQX reciba realmente la orden de excluir una estrategia fallida depende de la palabra clave `AutoDiscard`:

* **`AutoDiscard` ausente (por defecto): ninguna estrategia se excluye nunca.** `filterStrategy` siempre devuelve `true` al motor de SQX. Cada estrategia procesada se queda donde la tarea la habría puesto de todos modos, marcada con su resultado real.
* **`AutoDiscard` presente:** `filterStrategy` devuelve el veredicto real, dejando que el motor de SQX actúe en consecuencia.

Esto importa porque SQX tiene **dos mecanismos independientes** que pueden excluir una estrategia, y sólo uno se ve afectado por `AutoDiscard`:

1. **Copiar entre dos databanks distintos**: el motor sólo copia al databank de salida las estrategias para las que `filterStrategy` devolvió `true`.
2. **El checkbox nativo "Filter by results of custom analysis"**: sólo relevante cuando el databank de entrada y el de salida son el **mismo**. Borra las estrategias fallidas de ese databank, pero **sólo si `AutoDiscard` también hace que `filterStrategy` devuelva `false`**.

### Invariantes del layout (siempre activas)

En cada mono se comprueban cuatro invariantes sobre el reparto de las entradas. **No hay que activarlas**: corren siempre, calladas, y sólo emiten un `WARN` en el log de SQX si alguna se viola.

| Invariante | Qué garantiza |
| :--- | :--- |
| **A1** | Cero solapamiento: `entrada[k] ≥ entrada[k-1] + duración[k-1]` |
| **A2** | Todo dentro de la ventana: la primera entrada no cae antes de `idxMin` y la última salida no pasa de `idxMax` |
| **A3** | El dithering repartió exactamente las barras planificadas **en cada dirección**: la suma de duraciones de las operaciones en largo coincide con lo planificado para los largos, e igual para los cortos |
| **A4** | La permutación conservó **cuántas** operaciones van en cada dirección |

**A3 y A4 son las que vigilan que el barajado no rompa nada**: A4 comprueba que sigue habiendo el mismo número de operaciones en cada sentido, y A3 que cada sentido sigue ocupando las mismas barras de mercado. Al comprobarse por dirección, A3 subsume la verificación global: si ambas bolsas cuadran, el total también.

Un `WARN` de A1 a A4 **siempre es un bug del algoritmo de layout, nunca una condición de mercado ni un dato raro**. Si aparece, los resultados de ese periodo no son fiables. Se reporta sólo la primera violación de cada periodo para no inundar el log.

Corren en todos los monos y no sólo con `Debug` activo a propósito: si sólo se comprobasen en modo diagnóstico, una violación en producción pasaría desapercibida, que es justo el escenario que interesa detectar.

### Volcado de diagnóstico (`Debug`)

Con la palabra clave `Debug` se escribe `user/extend/Snippets/SQ/CustomAnalysis/MonkeyTest_ATR_v1_02_debug.log`, en dos bloques por periodo:

1. **`ATR STATS`** — de dónde sale el edge y en qué régimen de volatilidad: número de operaciones, edge total y por operación, suma de valores absolutos, ATR mínimo/mediano/máximo en las entradas, **cuántas entradas tocaron el suelo de un tick** (distinto de cero significa velas rellenadas o corruptas), spread aplicado, y la **correlación entre el ATR de entrada y el desplazamiento normalizado** sobre las operaciones reales. Esa correlación es diagnóstica: un valor alto en valor absoluto avisa de que el edge se concentra en un régimen de volatilidad concreto, que es justo el caso en que extrapolar a la fase 2 es menos fiable.

   Incluye además **una línea `LONG` y otra `SHORT`** con el desglose de cada dirección: cuántas operaciones tiene, cuántas barras ocupaba realmente, la duración media y base, y su `exposureRatio` propio. Ese ratio debe salir muy cerca de `1.0000` en ambas: es la comprobación de que la exposición de cada sentido se replicó. Si una dirección aparece marcada como `[CLAMPED to 1 bar]`, su duración media caía por debajo de una barra y su exposición está inflada — la señal para plantearse `Precision=M1`.
2. **`LAYOUT (monkey #0)`** — el reparto completo del primer mono, una fila por operación con `k`, **`dir`** (`L`/`S`), entrada, duración, salida y **`gapToPrev`**. Se vuelcan todas las operaciones y no una muestra, porque el objetivo es auditar a mano dos cosas: el no-solapamiento (**cualquier `gapToPrev` negativo lo delata**) y la secuencia de direcciones, que debe salir distinta en cada ejecución. La cabecera resume los conteos y las barras de cada dirección —que deben coincidir con los de la estrategia real— e indica si A1 a A4 pasaron.

El volcado va a un fichero propio y no al log de SQX porque éste llega a cientos de MB al día y quedaría inservible. El escritor está sincronizado, ya que `Per Strategy Analysis` corre multihilo; cada línea lleva el nombre del hilo, que es el mismo identificador que aparece en el log de SQX y permite correlacionar ambos.

> El fichero se abre en modo **append** y no se rota ni se limpia solo. Conviene borrarlo entre ejecuciones para no mezclar diagnósticos de pasadas distintas.

### Por qué no hay caché para el ResultsPlugin

Este proyecto incluye un plugin de visualización, `DatabankMonkeyTest`, que dibuja la distribución de los monos y sus curvas a partir de unos ficheros de caché. **Este test no los escribe**, por dos razones:

1. Ese plugin vive en `user/extend/ResultsPlugins/`, fuera de la carpeta de Snippets y por tanto fuera del ámbito modificable que fija la Regla 1 del proyecto.
2. Está construido enteramente sobre conceptos monetarios: lee un balance inicial, dibuja curvas de equity y etiqueta los ejes como dinero. Alimentarlo con múltiplos de ATR mostraría gráficos activamente engañosos.

Si más adelante se quiere visualización para este test, haría falta un plugin nuevo — una tarea aparte con su propia autorización.

---

## 5. Decisiones de Diseño y Por Qué

Esta sección documenta por qué ciertas alternativas aparentemente mejores se descartaron a propósito. Sin estas razones es previsible que alguien las "arregle" en el futuro, rompiendo la metodología.

### 5.1. El ATR es causal: nunca el de la barra de entrada

Se usa el ATR de la última barra **completada** antes de la entrada, no el de la barra que contiene el instante de entrada. Esa barra incluye su propio máximo y mínimo, que no se conocen todavía en su apertura — usarla sería lookahead.

No es un detalle menor: el desplazamiento que la operación va a capturar está correlacionado con el rango de esa misma barra, así que normalizar por ella metería parte de la respuesta en el denominador. Y como el sesgo afectaría a la estrategia y a los monos de forma distinta (sus entradas caen en barras distintas), la comparación quedaría invalidada.

### 5.2. Se replica el algoritmo exacto del indicador ATR de SQX, no una fórmula de manual

Existen varias formas razonables de calcular un ATR con suavizado de Wilder, y todas difieren sólo en cómo arrancan, durante las primeras `P` velas, antes de que haya suficiente historial de True Range acumulado. Una media simple de las primeras `P` velas usada como semilla, por ejemplo, da un número parecido al que produce SQX pero no idéntico bar a bar durante ese arranque; la diferencia decae exponencialmente con cada vela nueva y se vuelve insignificante a los pocos cientos de barras, pero sigue siendo una diferencia real mientras dura.

Para eliminar esa duda de raíz en lugar de argumentar que converge, este Custom Analysis reimplementa exactamente el algoritmo del indicador ATR interno de SQX, incluyendo su forma particular de arrancar (ver [2.3](#23-calcula-el-atr)). El resultado es idéntico, barra a barra, al que SQX calcularía con ese mismo indicador sobre las mismas velas — no una aproximación que se acerca con el tiempo, sino el mismo cálculo desde la primera barra. Esa es una garantía más fuerte que "contrastable contra el gráfico de SQX tras unas pocas barras de calentamiento": es la certeza de que ambos números son, por construcción, el mismo número en cualquier barra, no sólo a partir de cierto punto.

### 5.3. El ATR siempre del timeframe principal, aunque se simule a M1

Es tentador calcular el ATR sobre las mismas velas que se usan para simular. **Sería un error.** Un ATR(14) sobre velas de un minuto mide la volatilidad de los últimos 14 minutos; una estrategia H4 no opera a esa escala, y normalizar por ese valor produciría números sin relación con el edge que se busca medir.

Además haría que la misma estrategia diera cifras incomparables según se hubiera lanzado el test con `Precision=M1` o sin ella, cuando esa opción debería afectar sólo a la resolución de las duraciones.

### 5.4. Suelo en un tick, y por qué NO se recortan los valores extremos

Que un ATR de 14 velas sea exactamente cero exigiría 14 velas consecutivas con máximo igual a mínimo y sin gaps — no ocurre en datos reales. El riesgo práctico es un ATR **absurdamente pequeño** por datos rellenados o corruptos (algunos proveedores tapan huecos con velas planas).

Importa porque **cada operación se divide por su propio ATR local**. A diferencia de un divisor global —donde un valor extremo aislado queda diluido entre todos los demás—, aquí un solo denominador degenerado puede dominar la suma del periodo entero.

La defensa tiene tres niveles: suelo duro en `tickSize` (por debajo de un tick eso no es volatilidad, es un artefacto), un contador visible de cuántas entradas lo tocaron, y `ERROR` del periodo si el ATR **mediano** está en el suelo, porque entonces el problema es sistémico.

> **Descartado a propósito: winsorizar o recortar los desplazamientos normalizados extremos.** Un ATR bajo seguido de un movimiento grande **no es un error, es información** — es justo el tipo de operación que el test debe premiar, porque captura un desplazamiento grande relativo a la volatilidad vigente. Recortarlo destruiría la señal que se busca medir. El suelo elimina sólo lo que no es volatilidad en absoluto.

### 5.5. El spread se aplica aunque el test no maneje dinero

Podría parecer que, al no manejar cuantías monetarias, el spread tampoco pinta nada aquí. **No es así**: el spread es un desplazamiento de precio, no una cuantía monetaria, y en unidades de ATR es perfectamente expresable.

Y hay que conservarlo por equidad: los precios de las órdenes reales de SQX ya incorporan el spread, mientras que las velas del `.dat` son crudas. Si no se restase a los monos, éstos operarían sin fricción mientras la estrategia real sí la pagó, sesgando el veredicto hacia FAILED de forma artificial.

**Las comisiones y los swaps, en cambio, quedan fuera del test**, y ahí la diferencia es real: son cuantías monetarias, no desplazamientos de precio, y no tienen ninguna representación en unidades de ATR.

### 5.6. El spread hay que aplicarlo explícitamente

Un Custom Analysis que instancie el `BacktestEngine` de SQX con un `ChartSetup` hereda la aplicación del spread gratis, porque la hace el motor. Este snippet no: ejecuta una simulación propia sobre las velas crudas del fichero `.dat`, y **nada aplica el spread si no lo aplica el propio código.** Conviene tenerlo presente para no acabar contándolo dos veces, o ninguna.

### 5.7. Spread fijo del XML, nunca el spread real por vela

Los ficheros de datos de SQX pueden almacenar el spread histórico barra a barra, y la estructura que el snippet ya utiliza para leer las velas lo expone. Sería un dato más rico.

**No se usa.** La estrategia original se backtesteó con el valor fijo configurado en el XML, que es el coste que realmente sufrió. Aplicar a los monos un spread distinto los pondría a operar bajo condiciones que la referencia nunca tuvo, rompiendo justamente la comparabilidad que este test necesita preservar.

### 5.8. Por qué ATR y no porcentaje

El porcentaje corrige el nivel de precio, que es la mitad del problema. Pero dos entradas al mismo precio pueden tener volatilidades locales muy distintas —una en consolidación, otra en expansión— y el porcentaje las trata igual pese a que el desplazamiento esperado no lo es.

El ATR captura ambas cosas a la vez, porque escala con el nivel de precio **y** con el régimen de volatilidad vigente. El porcentaje sería una aproximación; el ATR es la magnitud que de verdad determina cuánto movimiento cabe esperar.

### 5.9. El periodo de ATR se fija, no se ata al del SL futuro

En la fase 1 no existe todavía ningún SL, así que no hay "el periodo de esta estrategia" al que ajustarse. Y aunque lo hubiera, normalizar cada estrategia por su propio periodo haría que la métrica dejara de ser comparable entre estrategias del mismo databank — que es exactamente para lo que sirve.

El periodo se deja configurable mediante `ATRPeriod=N` no para el uso diario, sino para poder comprobar que el ranking se mantiene estable al cambiarlo, dado que el SL de la fase 2 usará uno distinto y desconocido de antemano.

### 5.10. El veredicto se emite sobre la suma, no sobre la media

Ambas son **matemáticamente equivalentes** para el veredicto, porque cada mono ejecuta exactamente el mismo número de operaciones que la estrategia: dividir todos los valores por el mismo `n` no altera ni el percentil ni el Z-Score.

Se usa la suma porque es la magnitud natural del test, y la media se publica sólo para mostrar. La distinción importa únicamente al comparar estrategias entre sí en el Databank, donde el número de operaciones sí varía.

### 5.11. La duración se mide como posición fraccionaria en el eje de barras

Hay dos formas erróneas de medir la duración de una operación, y ambas producen sesgos reales:

* **Tiempo de calendario dividido por la duración de la barra**: una operación H4 abierta el viernes a las 20:00 y cerrada el lunes a las 04:00 son 56 horas, que esa fórmula convierte en 14 barras cuando en el mercado sólo existen unas 2. Los fines de semana y festivos se cuentan como barras inexistentes e inflan la duración objetivo.
* **Diferencia de índices de barra enteros**: cuantiza cada operación por separado antes de promediar. Con una estrategia H4 backtesteada a precisión M1, una operación de 6h15m y otra de 7h50m darían ambas 1 barra; la media saldría 1,0 exacta y **el dithering no tendría nada que repartir**.

La cuantización debe ocurrir una sola vez, sobre la media, nunca sobre cada operación.

### 5.12. Los monos no cierran los viernes

La estrategia original puede tener un cierre forzoso de fin de semana, y sus operaciones vienen ya acortadas por él. Como la duración media objetivo se calcula sobre esas operaciones, ese efecto **ya está incorporado**. Volver a aplicar el corte a los monos los penalizaría por segunda vez.

Además, al medir en barras el fin de semana desaparece del cómputo: una operación de N barras son N barras de mercado, cruce o no el sábado en el calendario.

### 5.13. El dithering usa una permutación aleatoria con conteo fijo

Las barras extra se reparten al azar entre operaciones distintas en cada mono, en lugar de recaer siempre sobre las primeras de la lista. Asignarlas por índice concentraría invariablemente las operaciones de mayor duración en la misma zona, y de forma idéntica en todos los monos.

**No debe sustituirse por una probabilidad independiente por operación.** Parece más aleatorio, pero cuántas operaciones reciben la barra extra pasaría a seguir una distribución binomial y la exposición temporal total variaría de un mono a otro, perdiendo la propiedad que los hace comparables entre sí y con la estrategia.

> **Nota de terminología**: en toda esta documentación "largo" y "corto" se refieren **siempre a la dirección** de la operación. Para hablar de cuánto dura se dice "duración" — nunca "operación larga", que sería ambiguo.

### 5.14. La separación entre entradas es la duración real de cada operación

Es lo que garantiza matemáticamente que las N operaciones quepan en el periodo. Si la estrategia original no solapa operaciones, se cumple que la suma de sus duraciones es menor que la longitud del periodo — pero esa garantía sólo se hereda si la separación mínima no se redondea al alza de forma uniforme.

### 5.15. La secuencia de direcciones se baraja en cada mono

Un mono podría construirse conservando el orden en que la estrategia alternó largos y cortos, y aleatorizando sólo *cuándo* ocurre cada operación. **No se hace así, y la diferencia importa.**

Si el orden se conservara, los 500 monos compartirían exactamente la misma secuencia direccional —siempre "largo, largo, corto, largo…"— y sólo se diferenciarían en el espaciado. Eso los convierte en sorteos **no independientes** en la dimensión direccional: una fuente real de variación queda congelada, la distribución nula sale artificialmente estrecha, y tanto el percentil como el Z-Score resultan más benévolos de lo que deberían.

Barajar la secuencia en cada mono devuelve esa variación al experimento. El coste es nulo (una permutación es O(n)) y lo que se gana es que la distribución de referencia represente de verdad "lo que habría pasado sin ninguna habilidad", en vez de "lo que habría pasado conservando el patrón de alternancia de la estrategia".

### 5.16. La exposición se replica por dirección, no sólo en total

La duración media se calcula **por separado** para las operaciones en largo y para las que van en corto, y cada bolsa alimenta sólo a las operaciones de su sentido. Promediarlas juntas sería más simple, pero abre un falso positivo grave.

Considera una estrategia con 50 operaciones en largo de 5 barras (250 barras) y 50 en corto de 20 barras (1.000 barras), sobre un mercado que cayó durante el periodo. Con una media global de 12,5 barras, cada mono acabaría con 625 barras de exposición en cada sentido. La estrategia real capta la deriva bajista durante 1.000 barras de exposición corta; los monos, sólo durante 625. La estrategia sale con "edge" cuando en realidad **sólo estuvo corta más tiempo mientras el mercado caía**.

Eso es exactamente el tipo de ventaja dependiente del régimen de mercado —no del timing— que el Monkey Test declara existir para detectar. Replicando la exposición de cada dirección por separado, el mono hereda la misma asimetría direccional que tuvo la estrategia, y la comparación vuelve a aislar lo único que se quiere medir.

> Un efecto secundario que conviene conocer: al separar las bolsas, una dirección con operaciones muy breves puede saturar su duración base a una barra e inflar su exposición, aunque la media global no lo hiciera. El volcado `Debug` lo marca como `[CLAMPED to 1 bar]` en la línea de esa dirección.

### 5.17. La columna nunca cae a otro periodo; el alias OOS ≡ OOS1 es la única excepción, y no es un fallback

Cuando un periodo no se ha evaluado, la columna muestra `N/A` **a propósito**. Podría parecer más cómodo que enseñara el valor de otro periodo cercano, pero eso hacía que todas las columnas acabaran mostrando el mismo número sin que nada lo indicara — un resultado de IS presentado como si fuera de OOS. La resolución estricta es lo que garantiza que lo que ves en una columna se calculó sobre ese periodo y no sobre otro.

La única equivalencia admitida es distinta en naturaleza: **cuando la estrategia tiene un solo segmento OOS, el OOS agregado y OOS1 no son dos periodos parecidos, son literalmente el mismo tramo** — SQX copia las estadísticas de uno sobre el otro. Ahí no se está sustituyendo nada: se publica un mismo resultado bajo los dos nombres que ese mismo tramo recibe. Si la estrategia tiene varios segmentos, la equivalencia deja de ser cierta y el alias no se aplica.

**Por qué se aplica también al pedir el periodo directamente**: la comprobación vive donde se decide qué periodos calcular, no dentro de la rama de `FULL`. Antes sólo se hacía al pedir `FULL`, de modo que lanzar con `OOS` dejaba la columna de OOS1 en `N/A` pese a haber calculado exactamente ese tramo — un `N/A` que parecía un fallo del test cuando en realidad era una clave que nunca se escribió.

**Por qué no se extiende a ISV**: la familia `ISV` / `ISV1..10` tiene la misma forma, pero que SQX copie sus estadísticas igual que con OOS es una suposición que no se ha comprobado. Publicar un resultado bajo un sufijo apoyándose en una equivalencia sin verificar es exactamente lo que el párrafo anterior prohíbe, así que se deja fuera hasta poder confirmarlo sobre un proyecto con ISV real.

---

## 6. Relación con el test monetario complementario

Este proyecto incluye un segundo Monkey Test, `MonkeyTest_v2_00`, que responde a una pregunta distinta: **¿cuánto dinero habría ganado esta estrategia frente al azar, con el money management que realmente tiene?** Comparte con éste buena parte de la maquinaria de simulación —medición de duración, dithering, colocación de entradas sin solapamiento— aunque, además de medir en dinero, construye sus monos de forma más conservadora: promedia la duración sin separar por dirección y conserva la secuencia direccional de la estrategia, en vez de barajarla.

Los dos conviven y son complementarios: éste para la fase 1 (reglas puras, donde el lotaje es un artefacto técnico), el monetario para validar estrategias completas una vez tienen su gestión de riesgo real.

| Aspecto | Test monetario (`MonkeyTest_v2_00`) | Este test (`MonkeyTest_ATR_v1_02`) |
| :--- | :--- | :--- |
| Magnitud medida | Beneficio en dinero | Desplazamiento normalizado por ATR (adimensional) |
| Tratamiento del money management | Dos fórmulas distintas, según el lotaje sea fijo o variable | Una sola: el money management no interviene |
| Calibración | Ratio `K` (euros por unidad de desplazamiento) | Ninguna: la magnitud es directa |
| Sesgo de nivel de precio | Presente en activos con deriva | Corregido |
| Régimen de volatilidad | No se considera | Normalizado operación a operación |
| Comisiones y swaps | Se restan | No aplican (no hay dinero) |
| Spread | Se resta a cada operación simulada | Igual, en unidades de precio antes de normalizar |
| Caché del ResultsPlugin | Disponible con `ResultsPluginCache` | No existe |
| `ATRPeriod` | No aplica | Configurable, 14 por defecto |
| Columnas de percentil y Z-Score | Compartidas | Compartidas (la clave ATR tiene precedencia) |
| Columnas de magnitud | `MonkeyMedianProfit` (euros) | `Monkey ATR Normalized Pips Profit` y `Monkey ATR Edge Per Trade` |
| Duración de los monos | Una media global para todas las operaciones | Una media por dirección, que replica la exposición de cada sentido |
| Secuencia de direcciones de los monos | Se conserva la de la estrategia | Se baraja al azar en cada mono |
| Layout sin solapamiento e invariantes A1/A2 | Idénticos | Idénticos (más A3 por dirección y A4) |
