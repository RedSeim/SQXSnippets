package SQ.Columns.Databanks;

import com.strategyquant.tradinglib.DatabankColumn;
import com.strategyquant.tradinglib.ResultsGroup;
import com.strategyquant.tradinglib.SampleTypes;
import com.strategyquant.tradinglib.ValueTypes;

public class MonkeyTestColumn extends DatabankColumn {

    public MonkeyTestColumn() {
        super("Monkey Test",
              DatabankColumn.Text,
              ValueTypes.Minimize,
              0, 0, 0);
        setWidth(100);
        setTooltip("Monkey Test result for the sample period selected in the Databank: percentile score achieved (e.g. 85.20%) or failure status (LOW TRADES, ERROR, etc.)");
    }

    @Override
    public String getValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        return resolve(results, sampleType);
    }

    @Override
    public String exportValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        return resolve(results, sampleType);
    }

    /**
     * Resuelve el resultado del periodo seleccionado en el Databank. La resolución es estricta:
     * si ese periodo no se ha calculado, se devuelve N/A en lugar de caer al valor de otro
     * periodo — precisamente ese fallback era lo que hacía que todas las columnas mostrasen el
     * mismo número. La única excepción es Full Sample, que acepta las claves legacy sin sufijo
     * escritas por versiones anteriores del Custom Analysis.
     */
    private String resolve(ResultsGroup results, byte sampleType) {
        String suffix = getSuffix(sampleType);

        String pct = readString(results, "MonkeyTestPercentile" + suffix);
        if (pct != null && !"N/A".equals(pct)) {
            return pct;
        }
        String status = readString(results, "MonkeyTestResult" + suffix);
        if (status != null) {
            return status;
        }

        if (sampleType == SampleTypes.FullSample) {
            pct = readString(results, "MonkeyTestPercentile");
            if (pct != null && !"N/A".equals(pct)) {
                return pct;
            }
            status = readString(results, "MonkeyTestResult");
            if (status != null) {
                return status;
            }
        }

        return "N/A";
    }

    private String readString(ResultsGroup results, String key) {
        Object v = results.specialValues().get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";

        // Partes numeradas: OutOfSample1..10 == 21..30, InSampleValidation1..10 == 41..50.
        // Sin estas ramas caerían en el "_Full" final y la columna mostraría los valores de
        // Full Sample como si fueran los de la parte seleccionada.
        if (sampleType > SampleTypes.OutOfSample && sampleType <= (byte) (SampleTypes.OutOfSample + 10)) {
            return "_OOS" + (sampleType - SampleTypes.OutOfSample);
        }
        if (sampleType > SampleTypes.InSampleValidation && sampleType <= (byte) (SampleTypes.InSampleValidation + 10)) {
            return "_ISV" + (sampleType - SampleTypes.InSampleValidation);
        }

        return "_Full";
    }
}
