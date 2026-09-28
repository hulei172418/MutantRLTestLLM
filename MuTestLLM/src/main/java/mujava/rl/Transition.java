package mujava.rl;

import org.json.JSONObject;

public final class Transition {
    public String episodeId = "";
    public int step;
    public RLStateSnapshot stateBefore;
    public RLAction action;
    public EvidenceAction evidenceAction;
    public double reward;
    public RewardBreakdown rewardBreakdown;
    public RLStateSnapshot stateAfter;
    public boolean done;
    public String terminationReason = "";

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("episodeId", episodeId);
        obj.put("step", step);
        obj.put("stateBefore", stateBefore == null ? new JSONObject() : stateBefore.toJson());
        obj.put("action", action == null ? "" : action.name());
        obj.put("evidenceAction", evidenceAction == null ? "" : evidenceAction.name());
        obj.put("reward", reward);
        obj.put("rewardBreakdown", rewardBreakdown == null ? new JSONObject() : rewardBreakdown.toJson());
        obj.put("stateAfter", stateAfter == null ? new JSONObject() : stateAfter.toJson());
        obj.put("done", done);
        obj.put("terminationReason", terminationReason);
        return obj;
    }
}
