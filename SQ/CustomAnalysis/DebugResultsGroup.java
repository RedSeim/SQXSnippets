package SQ.CustomAnalysis;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugResultsGroup extends CustomAnalysisMethod {
    public static final Logger Log = LoggerFactory.getLogger(DebugResultsGroup.class);
    
    public DebugResultsGroup() {
        super("DebugResultsGroup", TYPE_PROCESS_DATABANK);
    }

    @Override
    public boolean filterStrategy(String project, String task, String databankName, ResultsGroup rg) throws Exception {
        return true;
    }

    @Override
    public ArrayList<ResultsGroup> processDatabank(String projectName, String task, String databankName, ArrayList<ResultsGroup> databankRG) throws Exception {
        Log.info("=== START DEBUG RESULTS GROUP ===");
        for (ResultsGroup rg : databankRG) {
            Log.info("Strategy Name: " + rg.getName());
            Log.info("  storesChartData(): " + rg.storesChartData());
            Log.info("  getChartCount(): " + rg.getChartCount());
        }
        Log.info("=== END DEBUG RESULTS GROUP ===");
        return databankRG;
    }
}
