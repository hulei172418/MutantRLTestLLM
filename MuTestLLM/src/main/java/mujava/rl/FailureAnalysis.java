package mujava.rl;

import org.json.JSONArray;
import org.json.JSONObject;

public final class FailureAnalysis {
    public String symptom = "";
    public FailureStage failureStage = FailureStage.UNKNOWN;
    public RootCauseType rootCauseType = RootCauseType.UNCLASSIFIED_FAILURE;
    public String primaryFixTarget = "MANUAL_REVIEW";
    public String secondaryFixTarget = "";
    public String suggestedAction = "";
    public String why = "";
    public double confidence = 0.0d;
    public boolean autoRepairable;
    public final SignalLedger signalLedger = new SignalLedger();
    public final JSONArray suspectedMissingImports = new JSONArray();
    public final JSONArray suspectedBetterEntries = new JSONArray();
    public final JSONArray suspectedObservableGaps = new JSONArray();
    public final JSONArray suspectedAssertionGaps = new JSONArray();
    public final JSONArray suspectedReachabilityGaps = new JSONArray();

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("symptom", symptom == null ? "" : symptom);
        obj.put("failureStage", failureStage == null ? "" : failureStage.name());
        obj.put("rootCauseType", rootCauseType == null ? "" : rootCauseType.name());
        obj.put("primaryFixTarget", primaryFixTarget == null ? "" : primaryFixTarget);
        obj.put("secondaryFixTarget", secondaryFixTarget == null ? "" : secondaryFixTarget);
        obj.put("suggestedAction", suggestedAction == null ? "" : suggestedAction);
        obj.put("why", why == null ? "" : why);
        obj.put("confidence", confidence);
        obj.put("autoRepairable", autoRepairable);
        obj.put("signalLedger", signalLedger.toJson());
        obj.put("suspectedMissingImports", suspectedMissingImports);
        obj.put("suspectedBetterEntries", suspectedBetterEntries);
        obj.put("suspectedObservableGaps", suspectedObservableGaps);
        obj.put("suspectedAssertionGaps", suspectedAssertionGaps);
        obj.put("suspectedReachabilityGaps", suspectedReachabilityGaps);
        return obj;
    }
}
