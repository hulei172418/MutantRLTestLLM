package mujava.rl;

import org.json.JSONObject;

public final class RewardBreakdown {
    public double compileReward;
    public double originalPassReward;
    public double killReward;
    public double survivedPenalty;
    public double repairPenalty;
    public double promptCostPenalty;
    public double timePenalty;
    public double timeoutPenalty;
    public double compileFailurePenalty;
    public double originalFailurePenalty;
    public double totalReward;

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("compileReward", compileReward);
        obj.put("originalPassReward", originalPassReward);
        obj.put("killReward", killReward);
        obj.put("survivedPenalty", survivedPenalty);
        obj.put("repairPenalty", repairPenalty);
        obj.put("promptCostPenalty", promptCostPenalty);
        obj.put("timePenalty", timePenalty);
        obj.put("timeoutPenalty", timeoutPenalty);
        obj.put("compileFailurePenalty", compileFailurePenalty);
        obj.put("originalFailurePenalty", originalFailurePenalty);
        obj.put("totalReward", totalReward);
        return obj;
    }
}
