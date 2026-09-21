package players.pruningMCTS;

import players.mcts.MCTSParams;
import java.util.ArrayList;
import java.util.Map;

public class PruningMCTSParams extends MCTSParams {

    public int VISIT_THRESHOLD = 20;

    // Parameters for remainingNodes = max(alpha * log(n), T)
    public double ALPHA = 1.5;
    public int T_MIN = 5;

    public boolean PRUNE_MOVES = true;

    public boolean PROGBIAS = true; // True = Progressive Widening, False = Permanent Hard Pruning
    public double PW_ALPHA = 20.0;
    public double PW_BETA = 1.7;
    public int N_INIT = 5;

    public String getStatsLabel() {
        String pruningName = PROGBIAS ? "pw" : "hard pruning";
        return PRUNE_MOVES ? pruningName + " & move pruning" : pruningName;
    }

    @Override
    public void setParameterValue(String param, Object value) {
        switch (param) {
            case "VISIT_THRESHOLD": VISIT_THRESHOLD = (int) value; break;
            case "ALPHA": ALPHA = (double) value; break;
            case "T_MIN": T_MIN = (int) value; break;
            case "PRUNE_MOVES": PRUNE_MOVES = (boolean) value; break;
            case "PROGBIAS": PROGBIAS = (boolean) value; break;
            case "PW_ALPHA": PW_ALPHA = (double) value; break;
            case "PW_BETA": PW_BETA = (double) value; break;
            case "N_INIT": N_INIT = (int) value; break;
            default: super.setParameterValue(param, value); break;
        }
    }

    @Override
    public Object getParameterValue(String param) {
        switch (param) {
            case "VISIT_THRESHOLD": return VISIT_THRESHOLD;
            case "ALPHA": return ALPHA;
            case "T_MIN": return T_MIN;
            case "PRUNE_MOVES": return PRUNE_MOVES;
            case "PROGBIAS": return PROGBIAS;
            case "PW_ALPHA": return PW_ALPHA;
            case "PW_BETA": return PW_BETA;
            case "N_INIT": return N_INIT;
            default: return super.getParameterValue(param);
        }
    }

    @Override
    public ArrayList<String> getParameters() {
        ArrayList<String> paramList = super.getParameters();
        paramList.add("VISIT_THRESHOLD");
        paramList.add("ALPHA");
        paramList.add("T_MIN");
        paramList.add("PRUNE_MOVES");
        paramList.add("PROGBIAS");
        paramList.add("PW_ALPHA");
        paramList.add("PW_BETA");
        paramList.add("N_INIT");
        return paramList;
    }

    @Override
    public Map<String, Object[]> getParameterValues() {
        Map<String, Object[]> parameterValues = super.getParameterValues();
        parameterValues.put("VISIT_THRESHOLD", new Integer[]{10, 20, 30});
        parameterValues.put("ALPHA", new Double[]{0.5, 1.0, 1.5, 2.0});
        parameterValues.put("T_MIN", new Integer[]{3, 5, 8});
        parameterValues.put("PRUNE_MOVES", new Boolean[]{true, false});
        parameterValues.put("PROGBIAS", new Boolean[]{true, false});
        parameterValues.put("PW_ALPHA", new Double[]{10.0, 20.0, 30.0});
        parameterValues.put("PW_BETA", new Double[]{1.3, 1.7, 2.0});
        parameterValues.put("N_INIT", new Integer[]{3, 5, 7});
        return parameterValues;
    }
}
