package SQ.Columns.Databanks;

import com.strategyquant.tradinglib.DatabankColumn;
import com.strategyquant.tradinglib.ResultsGroup;
import com.strategyquant.tradinglib.SampleTypes;
import com.strategyquant.tradinglib.ValueTypes;

/**
 * Suma de los desplazamientos de precio de todas las operaciones, cada uno normalizado por el ATR
 * vigente en su entrada. Es la magnitud EXACTA sobre la que MonkeyTest_ATR_v1_02 emite su veredicto:
 * el percentil y el Z-Score salen de comparar este valor contra el de los N monos.
 *
 * No es dinero. Un valor de 12,50 significa que la estrategia capturo, en total, 12,5 veces la
 * volatilidad tipica vigente en el momento de cada entrada.
 *
 * Se lee junto a "Monkey ATR Edge Per Trade": esta columna mezcla calidad del edge con frecuencia
 * operativa (una regla mediocre con 233 operaciones puede superar a una excelente con 37), mientras
 * que aquella aisla la calidad por unidad de exposicion. Juntas dicen de donde viene el edge.
 */
public class MonkeyATRNormPipsProfit extends DatabankColumn {

    public MonkeyATRNormPipsProfit() {
        super("Monkey ATR Normalized Pips Profit",
              DatabankColumn.Decimal2,
              ValueTypes.Maximize,
              0, 0, 0);
        setWidth(110);
        setTooltip("Sum of every trade's price displacement divided by the ATR at its entry, for the sample period selected in the Databank. This is the exact magnitude the ATR Monkey Test verdict is based on. Not money: 12.50 means the strategy captured 12.5 times the volatility prevailing at entry, in total.");
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
        return readDouble(rg, "MonkeyATRNormPipsProfit" + getSuffix(sampleType));
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
