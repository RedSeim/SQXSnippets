# Documentación: Custom Analysis de Robustez Sintética (CVSintetica)

El script `CVSintetica_V07` es un Custom Analysis diseñado para **StrategyQuant X** (SQX) con el objetivo de evaluar la robustez y la ergodicidad de las estrategias de trading frente al ruido del mercado.

---

## 1. ¿Qué es este Custom Analysis?
Es una prueba de estrés estadística. Toma la lógica de tu estrategia y la somete a un backtest repetitivo en **variaciones de datos sintéticos** (que contienen el mismo comportamiento general pero con ruido aleatorio en el OHLC). Por defecto ejecuta **100 simulaciones**, pero este número es completamente configurable mediante los argumentos de la tarea.

El análisis calcula qué tan probable es que la estrategia sobreviva a este ruido (Tasa de Paso/Supervivencia) y evalúa si la estrategia está sobreajustada al histórico original o si sus resultados son consistentes a través de universos sintéticos paralelos.

---

## 2. Overview de Funcionamiento
El flujo de ejecución del Custom Analysis se compone de los siguientes pasos:

1.  **Resolución de Parámetros Originales:** Identifica el símbolo, timeframe, rango de fechas e histórico original de la estrategia que se está analizando.
2.  **Backtest de Control (Original):** Ejecuta un backtest en el símbolo original heredando toda la configuración original y segmentando los beneficios y cantidad de operaciones por periodos:
    *   **In-Sample (IS)** (Optimización)
    *   **Out-of-Sample (OOS)** (Validación)
    *   **In-Sample Validation (ISV)** (Validación cruzada)
    *   **Full Sample (Full)** (Historial completo)
3.  **Simulaciones Sintéticas:** Ejecuta **N backtests independientes** (100 por defecto) sustituyendo el símbolo original por los símbolos sintéticos numerados de forma secuencial (ej. si se configuran 100 simulaciones con el prefijo `EURUSD_H1_ftmo_SYN_`, buscará desde `001` hasta `100`).
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
3.  Navega a la carpeta `Snippets/SQ/CustomAnalysis/` y haz doble clic sobre `CVSintetica_V07.java`.
4.  Pulsa el botón **Compile** en la barra de herramientas superior para que SQX cargue la nueva lógica.

### Configuración de Argumentos en Tareas (Projects / Builder / Optimizer)
Para usar este Custom Analysis en tus flujos de optimización, debes añadir la tarea de análisis personalizado y configurar los argumentos de entrada bajo el siguiente formato:

```text
nombrededataausar, periodo, [cantidad_de_simulaciones]
```

#### Argumento 1: `nombrededataausar` (Prefijo de Data Sintética)
Es el prefijo del nombre de los símbolos sintéticos importados en tu base de datos de SQX.
*   *Por defecto:* `XAUUSD_Darwinex_sim` (si no se proporciona).
*   *Ejemplo:* `EURUSD_H1_ftmo_SYN_` (el script buscará los símbolos correspondientes secuencialmente).

#### Argumento 2: Periodo Objetivo
Indica en qué parte del histórico de datos se ejecutará el test. Este argumento soporta 4 opciones básicas:

| Opción | Descripción | Comportamiento en EspecialValues |
| :--- | :--- | :--- |
| **`FULL`** | Ejecuta el test en **todos** los periodos de forma simultánea e independiente (IS, ISV, OOS, Full). *Es la opción recomendada.* | Guarda datos independientes para todas las claves con sufijo (`_IS`, `_OOS`, `_ISV`, `_Full`) y variables globales. |
| **`IS`** | Ejecuta el test **únicamente** en el periodo **In-Sample** de la estrategia. | Guarda información únicamente para el periodo In-Sample (`_IS`). Los periodos `OOS` e `ISV` quedan limpios (vacíos/NaN). |
| **`OOS`** (o `IIS`) | Ejecuta el test **únicamente** en el periodo **Out-of-Sample**. | Guarda información únicamente para el periodo Out-of-Sample (`_OOS`). Los periodos `IS` e `ISV` quedan vacíos. |
| **`ISV`** | Ejecuta el test **únicamente** en el periodo **In-Sample Validation**. | Guarda información únicamente para el periodo In-Sample Validation (`_ISV`). Los periodos `IS` y `OOS` quedan vacíos. |

#### Argumento 3: Cantidad de Simulaciones (Opcional)
Define el número exacto de variaciones de datos sintéticos sobre las que se ejecutará el test.
*   *Por defecto:* `100` (si no se especifica o si se introduce un valor no numérico o menor o igual a cero).
*   *Ejemplo:* `150` (el bucle recorrerá desde el índice 1 hasta el 150).

### Ejemplos de Cadenas de Argumentos:
*   `EURUSD_H1_ftmo_SYN_, FULL` $\rightarrow$ Ejecuta el análisis en todos los periodos usando **100** simulaciones por defecto.
*   `EURUSD_H1_ftmo_SYN_, FULL, 150` $\rightarrow$ Ejecuta el análisis en todos los periodos usando exactamente **150** simulaciones.
*   `EURUSD_H1_ftmo_SYN_, IS, 50` $\rightarrow$ Analiza y guarda únicamente la robustez del periodo In-Sample usando **50** simulaciones.
*   `EURUSD_H1_ftmo_SYN_, OOS, 120` $\rightarrow$ Analiza y guarda únicamente la robustez del periodo Out-of-Sample usando **120** simulaciones.
