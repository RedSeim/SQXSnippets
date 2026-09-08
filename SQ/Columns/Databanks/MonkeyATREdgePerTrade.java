package SQ.Columns.Databanks;

import com.strategyquant.tradinglib.DatabankColumn;
import com.strategyquant.tradinglib.ResultsGroup;
import com.strategyquant.tradinglib.SampleTypes;
import com.strategyquant.tradinglib.ValueTypes;

/**
 * ATRs capturados de media por operacion: la suma de "Monkey ATR Normalized Pips Profit" dividida
 * entre el numero de operaciones del periodo. Un 0,42 significa que cada operacion capturo, de
 * media, 0,42 veces la volatilidad tipica vigente en el momento de su entrada.
 *
 * NO es la magnitud del veredicto -- ese sale de la suma. Pero como el numero de operaciones varia
 * mucho entre estrategias, la suma mezcla calidad del edge con frecuencia operativa; esta columna
 * aisla la calidad por unidad de exposicion, de modo que una buena regla de baja frecuencia no queda
 * enterrada bajo una mediocre de alta frecuencia.
 */
public class MonkeyATREdgePerTrade extends DatabankColumn {

    public MonkeyATREdgePerTrade() {
        super("Monkey ATR Edge Per Trade",
              DatabankColumn.Decimal2,
              ValueTypes.Maximize,
              0, 0, 0);
        setWidth(100);
        setTooltip("Average ATRs captured per trade for the sample period selected in the Databank: the ATR-normalized pips profit divided by the number of trades. 0.42 means each trade captured, on average, 0.42 times the volatility prevailing at its entry. Comparable across strategies with very different trade counts.");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Double v = resolve(rg, sampleType);
        return (v == null) ? NOT_AVAILABLE : formatDouble(v.doubleValue(), 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Double v = resolve(rg, sampleType);
        return (v == null) ? 0.0 : v.doubleValue();
    }

    /**
     * Resolucion estricta: si el periodo seleccionado no se ha evaluado se devuelve null (columna en
     * N/A) en lugar de caer al valor de otro periodo, igual que hacen las demas columnas de Monkey
     * Test. No hay clave legacy que contemplar: esta columna nace con el test ATR.
     */
    private Double resolve(ResultsGroup rg, byte sampleType) {
        return readDouble(rg, "MonkeyATREdgePerTrade" + getSuffix(sampleType));
    }

    private Double readDouble(ResultsGroup rg, String key) {
        try {
            Object v = rg.specialValues().get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Number) {
                return Double.valueOf(((Number) v).doubleValue());
            }
            String s = v.toString().trim();
            if (s.isEmpty() || "N/A".equals(s)) {
                return null;
            }
            return Double.valueOf(Double.parseDouble(s));
        } catch (Exception e) {
            return null;
        }
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";

        // Partes numeradas: OutOfSample1..10 == 21..30, InSampleValidation1..10 == 41..50.
        if (sampleType > SampleTypes.OutOfSample && sampleType <= (byte) (SampleTypes.OutOfSample + 10)) {
            return "_OOS" + (sampleType - SampleTypes.OutOfSample);
        }
        if (sampleType > SampleTypes.InSampleValidation && sampleType <= (byte) (SampleTypes.InSampleValidation + 10)) {
            return "_ISV" + (sampleType - SampleTypes.InSampleValidation);
        }

        return "_Full";
    }

    private String formatDouble(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
