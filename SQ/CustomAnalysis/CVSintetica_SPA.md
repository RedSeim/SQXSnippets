# Documentación: Custom Analysis de Robustez Sintética (CVSintetica)

El script `CVSintetica_V08` (y su predecesor `CVSintetica_V07`) es un Custom Analysis diseñado para **StrategyQuant X** (SQX) con el objetivo de evaluar la robustez y la ergodicidad de las estrategias de trading frente a variaciones de los datos de mercado (data sintética).

La versión **`CVSintetica_V08`** incluye soporte de ejecución multihilo en paralelo, reduciendo drásticamente los tiempos de procesamiento en procesadores con múltiples núcleos.

Además soporta **periodos Out-of-Sample numerados**: si tu proyecto define más de un OOS, puedes ejecutar el test de forma aislada sobre uno concreto (`OOS2`, por ejemplo), y el modo `FULL` genera automáticamente las métricas de cada parte OOS por separado.

---

## 1. ¿Qué es este Custom Analysis?
Es una prueba de estrés estadística. Toma la lógica de tu estrategia y la somete a un backtest repetitivo en **variaciones de datos sintéticos**: variaciones de los datos originales, diseñados para simular el comportamiento de la estrategia en una cantidad determinada de universos paralelos, generadas en cumplimiento de una serie de criterios establecidos por el experto en la materia Alan Tomillero — un tema que no se trata en profundidad en esta documentación. Por defecto ejecuta **100 simulaciones**, pero este número es completamente configurable mediante los argumentos de la tarea.

El análisis calcula qué tan probable es que la estrategia sobreviva a estas variaciones (Tasa de Paso/Supervivencia) y evalúa si la estrategia está sobreajustada al histórico original o si sus resultados son consistentes a través de universos sintéticos paralelos.

---

## 2. Overview de Funcionamiento
El flujo de ejecución del Custom Analysis se compone de los siguientes pasos:

1.  **Resolución de Parámetros Originales:** Identifica el símbolo, timeframe, rango de fechas e histórico original de la estrategia que se está analizando.
2.  **Backtest de Control (Original):** Ejecuta un backtest en el símbolo original heredando toda la configuración original y segmentando los beneficios y cantidad de operaciones por periodos:
    *   **In-Sample (IS)** (Optimización)
    *   **Out-of-Sample (OOS)** (Validación). Si el proyecto define varios OOS, este periodo es el **agregado** de todos ellos.
    *   **Partes OOS numeradas (OOS1, OOS2, ... hasta OOS10)**, cada una por separado, cuando el proyecto define más de un periodo Out-of-Sample.
    *   **In-Sample Validation (ISV)** (Validación cruzada)
    *   **Full Sample (Full)** (Historial completo)

    Este backtest de control hereda del backtest original:
    *   Money Management, Trading Options (incl. Realistic Gaps), Commissions y Swap, leyendo siempre el `<Setup>` **principal** del XML de configuración de la estrategia (no cualquier `<Setup>` que aparezca primero en el árbol — si el proyecto tiene Setups adicionales de Retest/Cross-Check con comisión, swap u opciones distintas, no se confunden con el principal).
    *   Fechas, timeframe, sesión, spread, slippage, distancia mínima y precisión de test.
    *   El mismo motor/engine que la estrategia original (MetaTrader5 Hedged/Netted, MetaTrader4, Tradestation, NinjaTrader, JForex o Stockpicker), resuelto individualmente para cada estrategia desde su propio XML.

    Para los motores **MetaTrader5 (Hedged y Netted)**, el simulador reproduce el backtest original con fidelidad **trade a trade** (verificado empíricamente contra el Databank, sin diferencias de precio ni de operaciones), corrigiendo un parámetro interno del simulador que antes hacía que un pequeño número de operaciones límite (entradas que "rozan" el nivel sin disparar claramente) no se reprodujeran en el retest. Esto no afecta a otros motores (MT4, Tradestation, NinjaTrader, JForex, Stockpicker), que no tienen ese parámetro.
3.  **Simulaciones Sintéticas Paralelas (V08):** Ejecuta **N backtests concurrentes** (100 por defecto) sustituyendo el símbolo original por los símbolos sintéticos de manera paralela utilizando todos los hilos libres de tu CPU para acelerar el procesamiento.
4.  **Cálculo de Métricas:** Para cada simulación y periodo activo se extrae el beneficio neto y número de operaciones. Finalmente, se calculan las siguientes métricas estadísticas clave:
    *   **Pass Rate (Tasa de Supervivencia):** El porcentaje de simulaciones sintéticas donde la estrategia terminó con ganancias estrictas y operó al menos una vez:
        $$\text{Pass Rate} = \frac{\text{Simulaciones Sintéticas Ganadoras y con Operaciones}}{\text{Total de Simulaciones (N)}}$$
    *   **Synthetic Ratio (Ratio de Ergodicidad):** Evalúa la estabilidad y consistencia de los retornos medios sintéticos relativos a la volatilidad o dispersión entre los mismos:
        $$\text{Synthetic Ratio} = \frac{\text{Media de Beneficio Sintético}}{\text{Desviación Estándar de Beneficio Sintético}}$$
    *   **Overfitting Ratio (Z-Score con Signo):** Mide cuántas desviaciones estándar de distancia hay entre el beneficio original y la media de los beneficios sintéticos. Un Z-Score extremadamente alto (> 2.0) sugiere un posible sobreajuste:
        $$\text{Overfitting Ratio} = \frac{\text{Profit Original} - \text{Media Sintética}}{\text{Desviación Estándar Sintética}}$$
5.  **Guardado:** Los resultados se graban directamente en el mapa de variables de la estrategia (`SpecialValuesMap`) para que puedan leerse desde las columnas del Databank de SQX o desde herramientas de análisis externas.

---

## 3. Instrucciones de Uso

### Configuración en el Code Editor
1.  Abre **StrategyQuant X**.
2.  Ve al menú **Code Editor**.
3.  Navega a la carpeta `Snippets/SQ/CustomAnalysis/` y haz doble clic sobre `CVSintetica_V08.java`.
4.  Pulsa el botón **Compile** en la barra de herramientas superior para que SQX cargue la nueva lógica.

### Configuración de Argumentos en Tareas (Projects / Builder / Optimizer)
Para usar este Custom Analysis en tus flujos de optimización, debes añadir la tarea de análisis personalizado y configurar los argumentos de entrada bajo el siguiente formato:

```text
nombrededataausar, periodo, [cantidad_de_simulaciones], [Debug]
```

#### Argumento 1: `nombrededataausar` (Prefijo de Data Sintética)
Es el prefijo del nombre de los símbolos sintéticos importados en tu base de datos de SQX.
*   *Por defecto:* `XAUUSD_Darwinex_sim` (si no se proporciona).
*   *Ejemplo:* `EURUSD_H1_ftmo_SYN_` (el script buscará los símbolos correspondientes secuencialmente).

#### Argumento 2: Periodo Objetivo
Indica en qué parte del histórico de datos se ejecutará el test. Este argumento soporta 4 opciones básicas:

| Opción | Descripción | Comportamiento en EspecialValues |
| :--- | :--- | :--- |
| **`FULL`** | Ejecuta el test en **todos** los periodos de forma simultánea e independiente (IS, ISV, OOS, Full) **más cada parte OOS numerada** que exista. *Es la opción recomendada.* | Guarda datos independientes para todas las claves con sufijo (`_IS`, `_OOS`, `_ISV`, `_Full`, y `_OOS1`...`_OOSN`) y variables globales. |
| **`IS`** | Ejecuta el test **únicamente** en el periodo **In-Sample** de la estrategia. | Guarda información únicamente para el periodo In-Sample (`_IS`). Los periodos `OOS` e `ISV` quedan limpios (vacíos/NaN). |
| **`OOS`** (o `IIS`) | Ejecuta el test **únicamente** en el periodo **Out-of-Sample**. Si hay varios OOS, es el **agregado** de todos. | Guarda información únicamente para el periodo Out-of-Sample (`_OOS`). Los periodos `IS` e `ISV` quedan vacíos. |
| **`OOS1`** ... **`OOS10`** | Ejecuta el test **únicamente** sobre una parte Out-of-Sample **concreta**. Es la forma de aislar, por ejemplo, sólo el OOS2 cuando el proyecto define varios OOS. | Guarda información únicamente para esa parte (`_OOS2`, etc.). El resto de periodos quedan vacíos. |
| **`ISV`** | Ejecuta el test **únicamente** en el periodo **In-Sample Validation**. | Guarda información únicamente para el periodo In-Sample Validation (`_ISV`). Los periodos `IS` y `OOS` quedan vacíos. |

> **Numeración de las partes OOS.** SQX numera los periodos Out-of-Sample **por orden cronológico de definición**: el primer rango OOS del proyecto es `OOS1`, el segundo `OOS2`, y así sucesivamente (hasta un máximo de 10). Ese es exactamente el número que debes usar en el argumento.

> **Con un único periodo OOS**, `FULL` **no** emite `_OOS1`: SQX hace que las estadísticas de OOS1 y del OOS agregado sean idénticas, de modo que serían columnas duplicadas. En ese caso usa simplemente `_OOS`.

> **Valores fuera de rango** (`OOS0`, `OOS11`, `OOSX`...) no producen un error: se comportan como `FULL`, igual que cualquier otro token no reconocido, y quedan anotados en el log de debug.

#### Argumento 3: Cantidad de Simulaciones (Opcional)
Define el número exacto de variaciones de datos sintéticos sobre las que se ejecutará el test.
*   *Por defecto:* `100` (si no se especifica o si se introduce un valor no numérico o menor o igual a cero).
*   *Ejemplo:* `150` (el bucle recorrerá desde el índice 1 hasta el 150).

#### Argumento 4: `Debug` (Opcional)
Activa el volcado detallado trade a trade para diagnosticar diferencias de Net Profit entre el backtest original del Databank y su reejecución (retest) dentro del Custom Analysis.

*   Si el 4º argumento es literalmente la palabra `Debug` (no distingue mayúsculas/minúsculas), se genera/actualiza el fichero `CVSintetica_trades_compare.log` (ver *Ubicación de los logs* más abajo) con un bloque por cada backtest realizado (el de control sobre el símbolo original y cada una de las simulaciones sintéticas), delimitado por `--- START COMPARE FOR <estrategia> [symbol=<símbolo>, control=<true|false>] ---` / `--- END COMPARE FOR <estrategia> ---`. Dentro de cada bloque, una línea por operación con el formato `ORIGINAL|RETEST;estrategia;símbolo;Trade#N;LONG|SHORT;OpenTime=...;CloseTime=...;OpenPrice=...;ClosePrice=...;Size=...;GrossPL=...;CommSwap=...;NetPL=...;CloseType=...`, más una línea `=== TOTAL TRADES ... ===` con el recuento. El bloque `ORIGINAL` solo se incluye en la ejecución de control (`control=true`), no se repite en cada sintético. Cada bloque se escribe de una sola vez (operación sincronizada atómica), por lo que no se entrelaza con los de otros hilos aunque se ejecuten en paralelo.
*   *Por defecto* (si se omite este argumento o se pone cualquier otra cosa): **no se genera dicho log**, para no consumir espacio en disco innecesariamente en corridas con muchas estrategias.
*   *Ejemplo:* `EURUSD_H1_ftmo_SYN_, OOS, 100, Debug`.

### Ejemplos de Cadenas de Argumentos:
*   `EURUSD_H1_ftmo_SYN_, FULL` $\rightarrow$ Ejecuta el análisis en todos los periodos usando **100** simulaciones por defecto.
*   `EURUSD_H1_ftmo_SYN_, FULL, 150` $\rightarrow$ Ejecuta el análisis en todos los periodos usando exactamente **150** simulaciones (ejecutadas en paralelo).
*   `EURUSD_H1_ftmo_SYN_, IS, 50` $\rightarrow$ Analiza y guarda únicamente la robustez del periodo In-Sample usando **50** simulaciones.
*   `EURUSD_H1_ftmo_SYN_, OOS, 120` $\rightarrow$ Analiza y guarda únicamente la robustez del periodo Out-of-Sample (agregado) usando **120** simulaciones.
*   `EURUSD_H1_ftmo_SYN_, OOS2, 100` $\rightarrow$ Analiza y guarda únicamente la robustez del **segundo** periodo Out-of-Sample, de forma aislada, usando **100** simulaciones.
*   `EURUSD_H1_ftmo_SYN_, OOS, 100, Debug` $\rightarrow$ Igual que el anterior con **100** simulaciones, y además genera `CVSintetica_trades_compare.log` con el detalle trade a trade de la ejecución de control y de cada simulación sintética.

---

## 4. Requisitos y Comportamientos a Tener en Cuenta

### Ubicación de los logs
Ambos ficheros de diagnóstico (`CVSintetica_debug.log` y, con el argumento `Debug`, `CVSintetica_trades_compare.log`) se escriben **junto al propio snippet**, es decir en:

```text
user/extend/Snippets/SQ/CustomAnalysis/
```

Es una **ruta relativa a la raíz de la instalación de SQX**, de modo que sigue siendo válida si reinstalas SQX, mueves la carpeta de instalación o clonas la configuración en otro ordenador. La carpeta se crea automáticamente si no existiera.

### `ComputeSeparateMetrics` debe estar activo
Para que SQX calcule las estadísticas de cada parte OOS por separado, el ajuste global **"Compute metrics separate for every data part of the same type"** (`ComputeSeparateMetrics`, en el diálogo de Performance) debe estar **activado** — lo está por defecto. Si se desactiva, las partes numeradas dejan de computarse: el Custom Analysis lo detecta, lo anota en el log y marca `CA_SynthSeparateMetricsSuspect = 1`.

### Si la parte OOS solicitada no existe
Si pides `OOS2` sobre una estrategia que sólo tiene un periodo OOS, el análisis **no inventa valores**: no publica ninguna métrica para ese sufijo (las columnas del Databank muestran *N/A* en lugar de un Pass Rate del 0% que parecería un resultado real y malo) y marca `CA_SynthPartMissing_OOS2 = 1`. La estrategia **nunca se elimina** del Databank por este motivo.

### Denominador del Pass Rate
El Pass Rate se calcula sobre las simulaciones **realmente evaluadas**, es decir, aquellas cuyas estadísticas SQX pudo computar para ese periodo. Una simulación cuyas estadísticas no existen se excluye de la muestra en lugar de contarse como pérdida, porque un hueco de medición no es un fracaso de la estrategia. En condiciones normales ese descarte es cero y el valor coincide exactamente con el de versiones anteriores; el número de descartes queda registrado en `CA_SynthMissingStatsCount<sufijo>`.

### Columnas del Databank
Las 7 columnas `Synth*` (`SynthPassRate`, `SynthMeanProfit`, `SynthStdevProfit`, `SynthCVProfit`, `SynthOverfittingRatio`, `SynthMeanSharpe`, `SynthFailCount`) resuelven automáticamente el periodo a partir del **selector de sample type del Databank**. Para ver los valores de `_OOS2` basta con seleccionar **OOS2** en ese selector; no hacen falta columnas nuevas.

> **Importante:** estas 7 columnas deben **recompilarse** tras actualizar el Custom Analysis. Antes de esta versión no reconocían los sample types numerados y mostraban los valores de *Full Sample* como si fueran los de la parte seleccionada.

### Variables de diagnóstico
| Clave | Significado |
| :--- | :--- |
| `CA_SynthTargetPeriod` | El periodo objetivo tal y como se interpretó del argumento. |
| `CA_SynthOOSPartsAvailable` | Número de partes OOS detectadas en la estrategia. |
| `CA_SynthOOSResolved` | `1` si se pudo resolver la configuración OOS, `0` si no. |
| `CA_SynthSeparateMetricsSuspect` | `1` si faltaron estadísticas, lo que sugiere `ComputeSeparateMetrics` desactivado. |
| `CA_SynthPartMissing<sufijo>` | `1` si se pidió una parte OOS que la estrategia no tiene. |
| `CA_SynthMissingStatsCount<sufijo>` | Simulaciones excluidas por no tener estadísticas computables. |
| `CA_SynthOriginalStatsMissing<sufijo>` | `1` si el backtest de control no tuvo estadísticas fiables para ese periodo (bien porque el run de control falló por completo, bien porque ese periodo concreto no tuvo estadísticas computables). El Overfitting Ratio de ese sufijo no se publica. |
| `CA_SynthNoData<sufijo>` | `1` si **ninguna** de las simulaciones sintéticas de ese periodo produjo estadísticas computables (p. ej. el prefijo/nombre de la data sintética no existe). `CA_SynthMeanProfit`, `CA_SynthStdevProfit`, `CA_SyntheticRatio`, `CA_SynthMeanSharpe`, `CA_PassRate` y `CA_OverfittingRatio` de ese sufijo no se publican. |

### Limitación conocida
Hay dos escenarios distintos en los que una parte de las métricas no se publica, cada uno con su propia señal de diagnóstico:

- **Falla el backtest de control** (`CA_OriginalRetestFailed = 1`) o el símbolo original no se pudo resolver: no hay un beneficio original fiable para ningún periodo. En ese caso `CA_OverfittingRatio<sufijo>` (y su variante retrocompat `CA_OverfittingRatio` sin sufijo) **no se publican** — la columna `SynthOverfittingRatio` muestra `N/A` en vez de un Z-Score calculado sobre un profit original ficticio de 0. El resto de métricas del periodo (`CA_SynthMeanProfit`, `CA_SynthStdevProfit`, `CA_PassRate`, etc.) no dependen del run de control y se siguen publicando con normalidad. `CA_SynthOriginalStatsMissing<sufijo>` marca por periodo cuándo ocurre esto; conviene revisar también `CA_OriginalRetestFailed` para diagnosticar la causa raíz.
- **Ninguna simulación sintética pudo ejecutarse para un periodo** (p. ej. prefijo o nombre de data sintética mal configurado/inexistente): a diferencia del caso anterior, aquí el problema está en el lado sintético, no en el run de control. `CA_SynthMeanProfit`, `CA_SynthStdevProfit`, `CA_SyntheticRatio`, `CA_SynthMeanSharpe`, `CA_PassRate` y `CA_OverfittingRatio` de ese sufijo **no se publican** (las columnas muestran `N/A`, incluida `SynthCVProfit` al depender de `CA_SynthMeanProfit`/`CA_SynthStdevProfit`), en vez de publicar `0`/`0%` como si las simulaciones se hubieran ejecutado y hubieran dado ese resultado. `CA_SynthFailCount<sufijo>` sigue mostrando el número real de fallos (p. ej. igual al número de simulaciones solicitadas) para diagnosticar la causa. `CA_SynthNoData<sufijo>` marca por periodo cuándo ocurre esto.

### Persistencia entre ejecuciones y limpieza de datos obsoletos
`specialValues()` está asociado a la **estrategia**, no al run: sus claves persisten entre distintas ejecuciones del Custom Analysis sobre la misma estrategia. Para evitar que un periodo cuyo test falla en la ejecución actual siga mostrando los valores de un test anterior exitoso (como si fueran del test actual), el Custom Analysis **limpia explícitamente** todas las claves `CA_Synth*`/`CA_OverfittingRatio`/`CA_PassRate`/`CA_OriginalProfit`/`CA_OriginalTrades` (por sufijo, y las variantes retrocompat sin sufijo) de cada periodo **incluido en el alcance del run actual**, antes de recalcularlas. Sólo se vuelven a publicar las que resultan fiables en esa ejecución; el resto queda sin valor (columna en `N/A`).

Esto **no afecta** a periodos que no forman parte del `targetPeriod` solicitado en el run actual: por ejemplo, si un run anterior testeó `FULL` y el run actual sólo pide `OOS1`, los valores de `IS`/`ISV`/`Full` no se tocan y siguen mostrando su último resultado conocido — es el comportamiento incremental esperado, no un dato obsoleto. Para saber qué periodos cubrió realmente la última ejecución, consultar `CA_SynthTargetPeriod`.

### Marca visual de "no se pudo evaluar" (columna "Filters result")
Cuando el test no pudo evaluarse de forma fiable en el run actual, la estrategia se marca como **FAILED** en la columna nativa **"Filters result"** de SQX, con un tooltip en inglés que explica la causa concreta (más específico cuanto más se pueda diagnosticar). Si el test se evalúa con normalidad, se marca **PASSED** explícitamente (para no dejar "pegado" un FAILED de un run anterior). Orden de prioridad (el primer caso que aplique es el que se muestra):

1. **El periodo solicitado no existe en esta estrategia** (p. ej. se pidió `OOS5` y la estrategia sólo tiene 2 partes OOS): ni siquiera se llegó a evaluar nada. Mensaje: *"the requested period '...' does not exist for this strategy."*
2. **Falla el backtest de control** (`CA_OriginalRetestFailed = 1`), con tres sub-causas distintas en el mensaje:
   - Símbolo original no resuelto: *"the original symbol could not be resolved."*
   - `BadStrategyException` en el control run: *"...threw a BadStrategyException (...). Check strategy/symbol compatibility."*
   - Excepción genérica: *"...control backtest on the original symbol failed (...)."*
3. **Ninguna simulación sintética de ningún periodo testeado produjo datos** (todos los periodos con `CA_SynthNoData<sufijo> = 1`), con la causa dominante entre las N simulaciones:
   - Todas ejecutaron pero sin stats por periodo → probable `ComputeSeparateMetrics` desactivado.
   - Todas fallaron por "too many trades on the same bar".
   - Todas fallaron con `BadStrategyException`.
   - Todas lanzaron la misma excepción genérica (incluye el texto de la excepción y el prefijo de data sintética usado).
   - Mezcla sin causa dominante: mensaje genérico sugiriendo revisar el nombre de la data sintética.

Esto se hace escribiendo directamente en la clave estándar de SQX `SpecialValues.FiltersResultFailedReason` (la misma que usa la columna `SQ.Columns.Databanks.FiltersResult`), **no** en una clave `CA_*` propia. Es sólo un indicador visual: `filterStrategy` sigue devolviendo `true` siempre, la estrategia nunca se excluye del databank por esta causa — son fallos de configuración/infraestructura del test, no un juicio sobre la calidad de la estrategia. Fallos parciales (algunas sintéticas fallan, la mayoría no), `CA_SynthSeparateMetricsSuspect` de forma aislada y `CA_SynthOOSResolved = 0` quedan fuera de este mecanismo binario PASSED/FAILED.

**Importante:** si el mismo proyecto/databank tiene también **Filters nativos de SQX** configurados (pestaña "Filters", con condiciones tipo `NetProfit > 0`, etc.), ambos mecanismos escriben en la misma clave `FiltersResultFailedReason` — el que se ejecute último "gana". No se ha podido confirmar el orden de ejecución entre el Custom Analysis y los Filters nativos; si usas ambos en el mismo proyecto, verifica que el veredicto final sea el esperado.
