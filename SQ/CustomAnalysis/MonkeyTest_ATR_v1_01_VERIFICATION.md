# MonkeyTest_ATR v1.01 — Verification Checklist

Estado de las verificaciones del snippet. Lo marcado como **hecho** ya se ejecutó y pasó; lo
marcado como **pendiente** requiere lanzar SQX.

> Las verificaciones que dependen de comparar contra ejecuciones anteriores deben tener en cuenta
> que **los resultados de v1.01 no son comparables con los de v1.00**: los monos cambiaron
> (barajan direcciones y replican la exposición por dirección), así que percentiles y Z-Scores
> serán distintos por diseño.

---

## Hecho

### ✅ Cálculo del ATR idéntico al indicador de SQX

En vez de comparar visualmente contra un gráfico, el snippet **reimplementa el algoritmo exacto**
del indicador ATR interno de SQX (semilla `TR[0]`, denominador `min(i+1, P)` durante el
calentamiento, Wilder puro después).

Verificado con un test aislado que compara la función del snippet contra una traducción
independiente del algoritmo fuente, sobre 2.000 velas sintéticas, con periodos 1/2/14/50/200 y
tamaños de histórico 5/50/300/2000, más los casos límite `n=0`, `n=1`, `period > n`:
**coincidencia bit a bit en las 28 combinaciones** (tolerancia 1e-12, diferencia máxima 0.0).

### ✅ Barajado y exposición por dirección

Test aislado sobre las propiedades que el diseño garantiza, con miles de repeticiones por
escenario (simétrico, asimétrico, 330 operaciones, desbalanceado 90/10, y los límites: sólo
largos, sólo cortos, `n=1`, `n=2`, `n=0`):

- el número de operaciones en cada dirección se conserva siempre;
- las barras totales de cada dirección coinciden exactamente con lo planificado;
- el barajado es uniforme (desviación relativa máxima por posición < 7%, umbral 10%).

### ✅ Compilación

Compila limpio contra los jars reales de SQX con el `javac` de la propia instalación, sin
errores ni avisos.

---

## Pendiente (requiere SQX)

### 1. Ejecución con el nuevo build

`EURUSD H4 - Iterator Edge` / `SynthTestFiltered - OOS`, argumentos `500,70,OOS2,AutoDiscard,Debug`.

En `MonkeyTest_ATR_v1_01_debug.log` comprobar:

- **A1 · A2 · A3 · A4 PASS** en la cabecera del bloque `LAYOUT`, sin ningún `WARN` de invariante
  en el log de SQX.
- **`exposureRatio` de las líneas `LONG` y `SHORT`** muy cerca de `1.0000` — es la comprobación
  directa de que la exposición de cada dirección se replicó.
- **Conteos por dirección** en la cabecera del `LAYOUT` coincidentes con los de la estrategia real.
- **`atrFloorHits: realTrades=0`**, como en ejecuciones anteriores.
- Ninguna dirección marcada `[CLAMPED to 1 bar]` (si aparece, su exposición está inflada y
  conviene valorar `Precision=M1`).

### 2. Regresión: el edge real no debe cambiar

El `edge` de cada estrategia debe salir **idéntico** al de v1.00 sobre el mismo databank y
periodo. Los cambios afectan sólo al lado de los monos; si el edge real cambiara, sería señal de
que el array de direcciones reales se mutó por error.

### 3. Cambio esperado: percentiles y Z-Scores

**Deben** cambiar respecto a v1.00 — es el objetivo del rediseño. Con la distribución nula más
ancha, lo normal es que algunas estrategias que pasaban ahora fallen. Que no cambiara nada sería
sospechoso.

### 4. El barajado se nota entre ejecuciones

Lanzar dos veces seguidas y comparar la columna `dir` del bloque `LAYOUT`: la secuencia de `L`/`S`
debe ser distinta, mientras que los conteos y las barras por dirección se mantienen.

### 5. Comprobación con `Precision=M1`

Repetir sobre un databank con `Precision=M1` y confirmar que el ATR sigue en la escala del
timeframe principal (mismo rango que sin `Precision=M1`) y que los `exposureRatio` por dirección
siguen en 1.0000.

### 6. Sensibilidad al periodo de ATR

Lanzar con `ATRPeriod=50`, `200` y `350` y comparar el **ranking** de las estrategias entre las
cuatro pasadas. Los valores absolutos cambiarán; el orden debería mantenerse sustancialmente
estable. Es una validación metodológica de una sola vez, no algo del uso diario.

### 7. Validación del sesgo corregido donde sí aplica

Repetir sobre un instrumento con deriva secular (oro, un índice a largo plazo) y comprobar que
ahí sí aparecen divergencias sistemáticas de ranking frente al test monetario, concentradas en
estrategias cuyas operaciones se agrupan en el tramo de precios altos.

### 8. CVSintetica intacta

Su `PassRateAgainstMonkeys` debe seguir leyendo las claves del test monetario sin verse afectada.
No se tocó ese fichero, así que el riesgo es nulo, pero conviene confirmarlo.

---

## Nota sobre el cambio de nombre

Al pasar a v1.01, SQX deja de mostrar `MonkeyTest_ATR_v1_00` en el desplegable de Custom
Analysis: hay que **volver a seleccionar `MonkeyTest_ATR_v1_01`** en la configuración de la
tarea. Si siguiera apareciendo la entrada antigua, reiniciar SQX para forzar la recompilación.
