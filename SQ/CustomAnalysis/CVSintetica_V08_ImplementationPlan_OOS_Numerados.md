# Soporte de periodos OOS numerados (OOS1..OOS10) en CVSintetica

## Contexto

Hoy el 2º input argument de `CVSintetica_V08` sólo acepta `IS`, `OOS`/`IIS`, `ISV` y `FULL`. Cuando un proyecto define **más de un periodo Out-of-Sample**, `OOS` se mapea siempre al `SampleTypes.OutOfSample` genérico, que en SQX es el **agregado** de todos los OOS. No hay forma de aislar el test sobre, por ejemplo, sólo el OOS2.

El motor de SQX sí soporta OOS numerados (verificado decompilando `internal\libs\SQTradingLib.jar`):

- `SampleTypes.OutOfSampleN == 20 + N` (OOS1..OOS10 = 21..30). `InSampleValidationN == 40 + N`.
- `OutOfSample.addRange(...)` y `setFromXML(...)` **auto-numeran** los rangos por orden de inserción: un rango genérico (20) se almacena como 21, 22, 23... Nunca queda un 20 almacenado.
- `BacktestEngine.recognizeOOS(...)` etiqueta cada `Order.SampleType` con el tipo numerado en tiempo de backtest, usando el OOS inyectado en `SettingsKeys.OutOfSample`. **El código actual ya hace esa inyección antes del backtest** ([CVSintetica_V08.java:566-580](SQ/CustomAnalysis/CVSintetica_V08.java#L566-L580)), así que el etiquetado numerado ya ocurre correctamente hoy — sólo que luego nunca se lee.
- `ResultsGroup.computeAllStats()` computa las stats de cada part numerado, siempre que el ajuste global `ComputeSeparateMetrics` esté activo (lo está, en `user\settings\settings.xml`).
- Las stats se guardan en un **mapa** con clave `(dir<<16)|(pl<<8)|sample`, no en un array: los tipos numerados son claves válidas, sin problema de límites. `Result.stats(...)` lanza `StatsDontExistException` si la combinación no existe; `Result.statsOrNull(byte,byte,byte)` devuelve `null` (retorna `com.strategyquant.tradinglib.SQStats`).

Además se han detectado **dos bugs preexistentes** que entran en el alcance porque bloquean el objetivo o la verificación:

- **Columnas del Databank silenciosamente erróneas.** Las 7 columnas `Synth*` comparten un `getSuffix(byte)` idéntico cuyo `default` devuelve `"_Full"`. SQX **sí** pasa sample types numerados a `DatabankColumn.getValue(...)` (lo demuestra el snippet interno `TotalDataDays.java:121-147`). Resultado: hoy, con el selector en OOS2, esas columnas muestran el valor de **Full Sample** presentándolo como si fuera OOS2.
- **El log de debug está muerto.** `logDebug` ([línea 1166](SQ/CustomAnalysis/CVSintetica_V08.java#L1166)) y `logTradesCompareBlock` ([línea 1275](SQ/CustomAnalysis/CVSintetica_V08.java#L1275)) escriben a la ruta **absoluta** hardcodeada `g:\Software\StrategyQuantX\144\...`, que dejó de existir al reubicarse la instalación (ahora `SQX_144_2938_win_20260520`). Ambos tragan la excepción, así que el argumento `Debug` no genera nada y no hay diagnóstico alguno.

### Resultado buscado

Poder ejecutar `<prefijo>, OOS2, 100` y obtener las métricas de robustez sintética calculadas **sólo** sobre el OOS2, visibles en el Databank; y que `FULL` produzca además `_OOS1.._OOSN` automáticamente sin backtests adicionales.

## Decisiones ya tomadas

- Modificar `CVSintetica_V08.java` **in place** (sin V09).
- `FULL` **auto-expande** a todos los parts OOS existentes.
- Sólo OOS numerados. **No** se añade ISV numerado como funcionalidad del CA (pero sí se corrige su mapeo en las columnas, ver Paso 5).
- `filterStrategy` sigue devolviendo `true` siempre.

---

## Paso 1 — Tabla de periodos dinámica

Sustituir los arrays fijos de longitud 4 ([líneas 109-136](SQ/CustomAnalysis/CVSintetica_V08.java#L109-L136)) por una lista construida en runtime. Con esto `activePeriodIndices` **desaparece**: toda entrada de la tabla está activa por construcción.

```java
private static class PeriodDef {
    final byte sampleType;
    final String suffix;
    final boolean exists;   // false sólo si se pidió un part OOS que no existe
    PeriodDef(byte st, String sfx, boolean ex) { sampleType=st; suffix=sfx; exists=ex; }
}
```

Enumerar los parts leyendo los tags realmente almacenados (más robusto que `getPartsCount`, cuyo argumento es un **selector de categoría**: pasarle `21` devuelve 0):

```java
private int[] enumerateOOSPartTags(OutOfSample oos) {
    TreeSet<Integer> tags = new TreeSet<Integer>();
    if (oos == null) return new int[0];
    for (int i = 0; i < oos.getRangesCount(); i++) {
        byte t = oos.getSampleType(i);
        if (t > SampleTypes.OutOfSample && t <= (byte)(SampleTypes.OutOfSample + 10)) tags.add((int) t);
    }
    ...
}
```

`buildPeriodTable(targetPeriod, oosPartTags)` devuelve:
- `OOS<n>` → una sola entrada `(20+n, "_OOS<n>", exists)`.
- `IS` / `OOS` / `IIS` / `ISV` → igual que hoy.
- `FULL` (y cualquier token no reconocido, como hoy) → las 4 entradas legacy **en el mismo orden** (`_IS`, `_OOS`, `_ISV`, `_Full`), más `_OOS1.._OOSN` **sólo si hay ≥2 parts**.

Mantener el orden legacy hace que `fullIdx == 3` en la práctica, de modo que el bloque de retrocompatibilidad ([líneas 344-363](SQ/CustomAnalysis/CVSintetica_V08.java#L344-L363)) queda numéricamente idéntico; aun así sustituir el `3` hardcodeado por `fullIdx` localizado por búsqueda de `SampleTypes.FullSample`.

**Con exactamente 1 part OOS no se emite `_OOS1`**: SQX copia las stats de OOS1 sobre OOS (`copyStats(21→20)`), así que serían columnas duplicadas byte a byte. Se registra en el log.

Todo lo aguas abajo (`originalProfits`, `originalTrades`, `periodProfits`, `periodSharpes`, `periodSuccessCounts`, `periodFailCounts`, `periodBadStrategyCounts`, `periodExceptionCounts`) se dimensiona por `periods.size()` y los bucles `for (int p : activePeriodIndices)` pasan a `for (int p = 0; p < nP; p++)`. **Los backtests sintéticos no se multiplican**: se ejecutan una vez y se muestrean por periodo.

## Paso 2 — Parser del argumento

`parseOOSPartNumber(String)` devuelve 1..10 para `OOS1`..`OOS10` (ya viene en mayúsculas por el `toUpperCase()` de la [línea 85](SQ/CustomAnalysis/CVSintetica_V08.java#L85)), tolerando un guion bajo inicial. Fuera de rango o no numérico (`OOS0`, `OOS11`, `OOSX`) cae al default `FULL` existente, **pero ahora se loguea** en lugar de fallar en silencio.

## Paso 3 — Part solicitado inexistente

Si se pide `OOS2` y la estrategia sólo tiene 1 OOS: **no se escribe ninguna clave de métrica** para ese sufijo, sólo diagnóstico. Así las columnas devuelven `NOT_AVAILABLE` en vez de fabricar un PassRate 0% / profit 0 que parecería un resultado real y malo.

```java
if (!pd.exists) { rg.specialValues().set("CA_SynthPartMissing" + pd.suffix, 1); continue; }
```

Diagnósticos incondicionales nuevos: `CA_SynthTargetPeriod`, `CA_SynthOOSPartsAvailable`, `CA_SynthOOSResolved`.

## Paso 4 — Resolver el OOS una sola vez

Hoy `resolveOOS` se llama dentro de `runBacktestWithInheritedSettings` ([línea 568](SQ/CustomAnalysis/CVSintetica_V08.java#L568)), o sea **1 + N veces** (101 por defecto), cada una parseando el XML completo y emitiendo ~8 líneas por `logDebug`, que es `static synchronized` y abre/cierra fichero por línea — un punto de serialización global entre los hilos del executor.

Resolverlo una vez en `filterStrategy` (se necesita ahí de todos modos para contar los parts) y pasarlo por una sobrecarga con parámetro `preResolvedOOS`, manteniendo la firma antigua como delegación para no romper nada.

**Thread-safety**: se comparte una instancia de `OutOfSample` entre los N backtests paralelos. No es regresión — hoy el camino habitual ya devuelve `source.getOOS()`, la misma instancia compartida, para todos los hilos. `recognizeOOS` sólo lee. **Nunca mutar ese objeto** (`addRange`/`setFromXML` renumerarían y corromperían todas las ejecuciones concurrentes).

## Paso 5 — `statsOrNull` en lugar de tragarse la excepción

Este es el núcleo de correctitud. Hoy `safeGetNetProfit`/`safeGetTradeCount`/`safeGetSharpeRatio` capturan la excepción y devuelven `0.0`/`0`, **indistinguible** de "el periodo existe y no operó". Aguas abajo (`pVal <= 0 || tVal == 0`, [línea 265](SQ/CustomAnalysis/CVSintetica_V08.java#L265)) eso cuenta como fallo, así que un part inexistente daría PassRate 0, mean 0, stdev 0. Sería el modo de fallo dominante de esta feature si se deja como está.

Sustituir los tres helpers por una lectura única que obtiene el objeto de stats una sola vez (además: 3× menos búsquedas en el mapa y sin construir excepciones en un bucle de 100×periodos):

```java
private PeriodSample readSample(ResultsGroup rg, byte sampleType) {
    PeriodSample s = new PeriodSample();
    SQStats st = rg.portfolio().statsOrNull(Directions.Both, PlTypes.Money, sampleType);
    if (st == null) return s;                 // exists == false
    s.exists = true;
    s.profit = st.getDouble(StatsKey.NET_PROFIT);
    s.trades = st.getInt(StatsKey.NUMBER_OF_TRADES);
    ...
}
```

Política de agregación:
- Sintético con `!exists` → **no** se añade a `periodProfits`/`periodSharpes`, **no** cuenta ni como éxito ni como fallo; incrementa `periodMissingStatsCounts[p]`. Un hueco de medición no se puede convertir en una pérdida.
- Sintético con `exists` → exactamente la lógica de hoy.
- Run de control con `!exists` → se loguea y se marca `CA_SynthOriginalStatsMissing<suffix>`; el OverfittingRatio de ese periodo no es fiable.

El denominador del PassRate pasa de la constante `syntheticCount` a los realmente evaluados. **En todo escenario que funciona hoy `missing == 0`, así que es compatible bit a bit**; sólo difiere justo cuando el número antiguo sería mentira. Si algún periodo con `exists==true` acaba con `missing > 0`, avisar de que la causa probable es `ComputeSeparateMetrics` desactivado y marcar `CA_SynthSeparateMetricsSuspect`.

Nuevas claves: `CA_SynthMissingStatsCount<suffix>`, `CA_SynthOriginalStatsMissing<suffix>`, `CA_SynthSeparateMetricsSuspect`.

Los overloads de un argumento `safeGetNetProfit(rg)` / `safeGetTradeCount(rg)` ([líneas 675-681](SQ/CustomAnalysis/CVSintetica_V08.java#L675-L681)) son código muerto: eliminarlos.

## Paso 6 — Ruta de logs relativa y a prueba de reubicación

Sustituir las dos rutas absolutas por **una ruta relativa**, que sobrevive a reinstalaciones, cambios de ubicación y a instalar SQX en otro ordenador.

**Base empírica**: el propio [MonkeyTest.java:593-597](SQ/CustomAnalysis/MonkeyTest.java#L593-L597) de este repo ya usa rutas relativas planas (`"user/data/History/..."`, `new File("user/data/History")`) y funciona en producción. Eso demuestra que **el working directory de la JVM de SQX es la raíz de la instalación**, así que una ruta relativa que arranque en `user/` resuelve correctamente sin ninguna API de paths ni reflexión.

Matiz a tener presente al implementar: en Java una ruta relativa se resuelve contra el *working directory del proceso*, **no** contra la ubicación del `.java`. El efecto neto es el buscado, pero por eso la ruta se escribe desde la raíz de SQX (`user/extend/...`) y no como un `../../../` relativo al snippet.

Destino elegido: `user/extend/ResultsPlugins/DatabankMonkeyTest/cache/` (la carpeta ya existe y está vacía).

```java
private static final String LOG_DIR = "user/extend/ResultsPlugins/DatabankMonkeyTest/cache";

private static java.io.File logFile(String name) {
    java.io.File dir = new java.io.File(LOG_DIR);
    if (!dir.exists()) dir.mkdirs();      // robusto si la carpeta se borra o es un equipo nuevo
    return new java.io.File(dir, name);
}
```

`logDebug` pasa a usar `logFile("CVSintetica_debug.log")` y `logTradesCompareBlock` `logFile("CVSintetica_trades_compare.log")`. Un único sitio (`LOG_DIR`) a tocar si algún día cambia el destino.

**Que no vuelva a fallar en silencio**: ambos escritores hacen `catch (Exception ignored) {}`, que es justo por lo que este bug pasó desapercibido. Añadir un flag `static volatile boolean logFailureReported` que, en el **primer** fallo, escriba el motivo por `System.err` (y no vuelva a hacerlo, para no inundar). El resto de fallos se siguen ignorando, de modo que un problema de logging jamás tumbe un análisis.

**Sin este paso no hay forma de verificar el resto del trabajo.**

## Paso 7 — Las 7 columnas del Databank

Reemplazo idéntico del método `getSuffix(byte)` en los 7 ficheros de [SQ/Columns/Databanks/](SQ/Columns/Databanks/) (`SynthCVProfit:61`, `SynthPassRate:61`, `SynthMeanProfit:61`, `SynthStdevProfit:61`, `SynthOverfittingRatio:61`, `SynthFailCount:69`, `SynthMeanSharpe:75`). Sólo cambia este método; los cuerpos de `getValue`/`getNumericValue` y sus cadenas de fallback se quedan igual.

```java
    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";

        // parts numerados: OutOfSample1..10 == 21..30, InSampleValidation1..10 == 41..50
        if (sampleType > SampleTypes.OutOfSample && sampleType <= (byte)(SampleTypes.OutOfSample + 10)) {
            return "_OOS" + (sampleType - SampleTypes.OutOfSample);
        }
        if (sampleType > SampleTypes.InSampleValidation && sampleType <= (byte)(SampleTypes.InSampleValidation + 10)) {
            return "_ISV" + (sampleType - SampleTypes.InSampleValidation);
        }

        return "_Full";
    }
```

El ISV numerado se mapea aunque el CA nunca escriba claves `_ISV<n>`: hoy esos tipos caen a `"_Full"` y la columna muestra números de Full Sample con el selector en ISV2. Tras el cambio la búsqueda falla y se muestra `NOT_AVAILABLE`, que es lo honesto.

Las cadenas de fallback sólo se disparan con `sampleType == FullSample`: **no** extenderlas a los tipos numerados.

## Paso 8 — Documentación (`CVSintetica.md`)

Regla 4 del proyecto: el cambio no está completo hasta que el `.md` sea coherente. Actualizar:
- Tabla del Argumento 2: fila nueva `OOS1`..`OOS10`; reescribir la fila `FULL` documentando la auto-expansión y que con 1 solo OOS no se emite `_OOS1` porque sería idéntico a `_OOS`.
- Sección nueva: comportamiento cuando el part pedido no existe.
- Sección nueva con los special values añadidos.
- Que el denominador del Pass Rate es ahora "simulaciones con stats computables" (= N en todos los casos normales).
- Que las 7 columnas `Synth*` deben recompilarse, y que para ver `_OOS2` hay que seleccionar el sample type OOS2 en el Databank.
- Que `ComputeSeparateMetrics` debe seguir activo.
- **Corregir la ubicación de los logs**: el §3 Argumento 4 dice hoy que `CVSintetica_trades_compare.log` se genera «en la misma carpeta que el snippet», lo cual pasa a ser falso. Ambos logs (`CVSintetica_debug.log` y `CVSintetica_trades_compare.log`) van ahora a `user/extend/ResultsPlugins/DatabankMonkeyTest/cache/`, ruta relativa a la raíz de la instalación de SQX y por tanto válida tras reinstalar, mover la instalación o clonarla en otro equipo.
- Ejemplos: `EURUSD_H1_ftmo_SYN_, OOS2, 100` y `EURUSD_H1_ftmo_SYN_, FULL, 100`.

---

## Verificación

1. **Compilar**: Code Editor → `CVSintetica_V08.java` → Compile; después los 7 `SQ/Columns/Databanks/Synth*.java`. Reiniciar SQX para que las columnas se re-registren.
2. **Confirmar** `ComputeSeparateMetrics = true` en `user\settings\settings.xml`.
3. **Proyecto de prueba** con **dos** rangos OOS definidos y unas pocas estrategias en el Databank. Usar un contador bajo (`, 5`) para iterar rápido.
4. **Cadenas a ejecutar**:
   - `<prefijo>, OOS2, 5` → sólo claves `_OOS2`.
   - `<prefijo>, OOS3, 5` → `CA_SynthPartMissing_OOS3 = 1`, columnas N/A, **ninguna estrategia eliminada** del Databank.
   - `<prefijo>, FULL, 5` → `_IS`, `_OOS`, `_ISV`, `_Full`, `_OOS1`, `_OOS2` + claves legacy sin sufijo.
   - `<prefijo>, OOS, 5` y `<prefijo>, IS, 5` → **regresión**: deben coincidir con una ejecución previa al cambio sobre el mismo proyecto.
   - `<prefijo>, OOS11, 5` y `<prefijo>, OOSX, 5` → se comportan como FULL y lo loguean.
5. **Columnas**: añadir las 7 y mover el selector de sample type entre OOS / OOS1 / OOS2 / IS / ISV / Full. Comprobar que OOS1 ≠ OOS2 y que OOS es el agregado. Chequeo de coherencia: en el run de control, profit original de `_OOS` ≈ `_OOS1` + `_OOS2`.
6. **Proyecto con un solo OOS**: en FULL, `_OOS1` debe estar **ausente**, `_OOS` presente, y el log debe contener la línea de "único part OOS".
7. **Log** (ya funcional tras el Paso 6). Comprobar primero que aparece `CVSintetica_debug.log` en `user/extend/ResultsPlugins/DatabankMonkeyTest/cache/` — si sigue vacío, nada de lo demás es verificable. Dentro debe verse: `OOS parts detected: 2`, una línea `ORIGINAL RESULT FOR PERIOD _OOS2 (type=22)`, líneas por sintético para `_OOS1`/`_OOS2` en los 5 primeros, y `[resolveOOS] Starting resolution` **una vez por estrategia**, no 101. Con el 4º argumento `Debug`, además `CVSintetica_trades_compare.log` en la misma carpeta.
8. **Prueba de reubicación** (valida el objetivo del Paso 6): renombrar temporalmente la carpeta `cache`, relanzar un análisis corto y confirmar que se recrea sola vía `mkdirs()` y que el log vuelve a escribirse.

## Riesgos y notas

- **`CA_SinteticNetProfits<suffix>`**: un CSV de N profits por periodo. Con FULL + muchos parts + 100 sims son varias cadenas de ~800 caracteres persistidas por estrategia. Considerar emitirlo sólo para los 4 periodos base.
- **Fallo del run de control**: si el retest original lanza ([líneas 166-177](SQ/CustomAnalysis/CVSintetica_V08.java#L166-L177)), `originalProfits[]` queda todo a cero y cada OverfittingRatio pasa a ser `-mean/stdev` en lugar de suprimirse. Preexistente; `CA_OriginalRetestFailed` lo marca pero las métricas se escriben igual. Documentarlo aunque no se arregle ahora.
- **`stdev == 0` fuerza los ratios a 0.0** ([líneas 310, 313](SQ/CustomAnalysis/CVSintetica_V08.java#L310-L313)): una estrategia perfectamente estable y una que falló siempre reportan lo mismo. Preexistente; sin cambios.
- **Aritmética de bytes**: `SampleTypes.OutOfSample + 10` promociona a `int`; los casts `(byte)` son necesarios. Todos los valores (21-30, 41-50) caben de sobra en byte positivo.
- **`logDebug` es `static synchronized` con apertura de fichero por línea**: cuello de botella global entre hilos. Mantener el guard `res.index <= 5`. El Paso 4 elimina ~800 líneas por estrategia de la peor contención.

## Nota de estado (post-implementación)

Este plan documenta el diseño original. Cambios respecto al diseño según se implementó realmente:

- **Ruta de logs**: la ruta finalmente usada **no** es `user/extend/ResultsPlugins/DatabankMonkeyTest/cache/` como se describe en el Paso 6, sino `user/extend/Snippets/SQ/CustomAnalysis/` (junto al propio `.java`), por corrección explícita del usuario ("debe quedarse justo al lado del archivo java del custom analysis"). La sección "Ubicación de los logs" de `CVSintetica.md` refleja la ruta real.
- **Fix adicional posterior**: se corrigió también un bug relacionado con el fallo del run de control (mencionado en "Riesgos y notas" arriba como pendiente): cuando el backtest de control falla por completo, `CA_OverfittingRatio<sufijo>` ahora **no se publica** (antes se publicaba un valor engañoso `-mean/stdev` con profit original ficticio a 0). Ver `CA_SynthOriginalStatsMissing<sufijo>` en `CVSintetica.md`.

Todo lo demás (Pasos 1-5, 7, 8) se implementó tal y como está descrito aquí.
