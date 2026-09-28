package mujava.rl;

import mujava.testgenerator.tools.Result;
import org.json.JSONObject;

public final class EpisodeSummary {
    public String episodeId = "";
    public String canonicalMutantId = "";
    public int bestAttempt;
    public int lastAttempt;
    public boolean targetKilled;
    public String terminationReason = "";
    public int totalLlmCalls;
    public int totalCompileCalls;
    public long totalMillis;
    public String bestTargetStatus = "";
    public String lastTargetStatus = "";
    public String initialEvidenceAction = "";
    public String regenPolicyName = "";
    public String regenStrategyPath = "";
    public double initialReward;
    public double finalReward;
    public double policyUpdateReward;
    public String policyUpdateSource = "";
    public JSONObject initialResult = new JSONObject();
    public JSONObject bestResult = new JSONObject();
    public JSONObject lastResult = new JSONObject();

    public static EpisodeSummary from(String episodeId,
                                      String canonicalMutantId,
                                      Result best,
                                      Result last,
                                      int bestAttempt,
                                      int lastAttempt,
                                      String terminationReason) {
        EpisodeSummary out = new EpisodeSummary();
        out.episodeId = episodeId == null ? "" : episodeId;
        out.canonicalMutantId = canonicalMutantId == null ? "" : canonicalMutantId;
        out.bestAttempt = bestAttempt;
        out.lastAttempt = lastAttempt;
        out.targetKilled = best != null && best.killed;
        out.terminationReason = terminationReason == null ? "" : terminationReason;
        out.totalLlmCalls = last == null || last.timing == null ? 0 : last.timing.llmCalls;
        out.totalCompileCalls = last == null || last.timing == null ? 0 : last.timing.compileCalls;
        out.totalMillis = last == null || last.timing == null ? 0L : last.timing.totalMillis;
        out.bestTargetStatus = best == null ? "" : safe(best.targetStatus);
        out.lastTargetStatus = last == null ? "" : safe(last.targetStatus);
        out.initialEvidenceAction = best == null ? "" : safe(best.rlInitialEvidenceAction);
        out.regenPolicyName = best == null ? "" : safe(best.rlRegenPolicyName);
        out.regenStrategyPath = best == null ? "" : safe(best.rlRegenStrategyPath);
        out.initialReward = best == null ? 0.0d : best.rlInitialReward;
        out.finalReward = best == null ? 0.0d : best.rlFinalReward;
        out.policyUpdateReward = best == null ? 0.0d : best.rlPolicyUpdateReward;
        out.policyUpdateSource = best == null ? "" : safe(best.rlPolicyUpdateSource);
        out.initialResult = initialResultSummary(best);
        out.bestResult = resultSummary(best);
        out.lastResult = resultSummary(last);
        return out;
    }

    public static EpisodeSummary fromSelected(String episodeId,
                                              String canonicalMutantId,
                                              Result selected,
                                              int bestAttempt,
                                              int lastAttempt,
                                              String terminationReason) {
        EpisodeSummary out = from(episodeId, canonicalMutantId, selected, selected, bestAttempt, lastAttempt,
                terminationReason);
        if (selected != null && selected.rlLastAttempt > 0) {
            out.lastTargetStatus = safe(selected.rlLastTargetStatus);
            out.lastResult = lastResultSummary(selected);
            out.totalLlmCalls = selected.rlLastLlmCalls;
            out.totalCompileCalls = selected.rlLastCompileCalls;
            out.totalMillis = selected.rlLastElapsedMillis;
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("episodeId", episodeId);
        obj.put("canonicalMutantId", canonicalMutantId);
        obj.put("bestAttempt", bestAttempt);
        obj.put("lastAttempt", lastAttempt);
        obj.put("targetKilled", targetKilled);
        obj.put("terminationReason", terminationReason);
        obj.put("totalLlmCalls", totalLlmCalls);
        obj.put("totalCompileCalls", totalCompileCalls);
        obj.put("totalMillis", totalMillis);
        obj.put("bestTargetStatus", bestTargetStatus);
        obj.put("lastTargetStatus", lastTargetStatus);
        obj.put("initialEvidenceAction", initialEvidenceAction);
        obj.put("regenPolicyName", regenPolicyName);
        obj.put("regenStrategyPath", regenStrategyPath);
        obj.put("initialReward", initialReward);
        obj.put("finalReward", finalReward);
        obj.put("policyUpdateReward", policyUpdateReward);
        obj.put("policyUpdateSource", policyUpdateSource);
        obj.put("initialResult", initialResult == null ? new JSONObject() : initialResult);
        obj.put("bestResult", bestResult == null ? new JSONObject() : bestResult);
        obj.put("lastResult", lastResult == null ? new JSONObject() : lastResult);
        return obj;
    }

    private static JSONObject initialResultSummary(Result result) {
        JSONObject obj = new JSONObject();
        if (result == null) {
            return obj;
        }
        obj.put("compiled", result.rlInitialCompiled);
        obj.put("originalPassed", result.rlInitialOriginalPassed);
        obj.put("killed", result.rlInitialKilled);
        obj.put("targetStatus", safe(result.rlInitialTargetStatus));
        obj.put("failureReason", safe(result.rlInitialFailureReason));
        obj.put("timedOut", result.rlInitialTimedOut);
        obj.put("promptChars", result.rlInitialPromptChars);
        obj.put("llmCalls", result.rlInitialLlmCalls);
        obj.put("compileCalls", result.rlInitialCompileCalls);
        obj.put("repairRounds", result.rlInitialRepairRounds);
        obj.put("elapsedMillis", result.rlInitialElapsedMillis);
        obj.put("generationStrategy", safe(result.rlInitialGenerationStrategy));
        return obj;
    }

    private static JSONObject resultSummary(Result result) {
        JSONObject obj = new JSONObject();
        if (result == null) {
            return obj;
        }
        obj.put("taskId", safe(result.taskId));
        obj.put("testSetName", safe(result.testSetName));
        obj.put("compiled", result.compiled);
        obj.put("originalPassed", result.originalPassed);
        obj.put("killed", result.killed);
        obj.put("targetStatus", safe(result.targetStatus));
        obj.put("failureReason", safe(result.failureReason));
        obj.put("timedOut", result.timedOut);
        obj.put("promptChars", result.promptChars);
        obj.put("compileRounds", result.compileRounds);
        obj.put("llmCalls", result.timing == null ? 0 : result.timing.llmCalls);
        obj.put("compileCalls", result.timing == null ? 0 : result.timing.compileCalls);
        obj.put("repairRounds", result.timing == null ? 0 : result.timing.repairRounds);
        obj.put("elapsedMillis", result.timing == null ? 0L : result.timing.totalMillis);
        obj.put("generationStrategy", safe(result.generationStrategy));
        obj.put("referenceGuided", result.referenceGuided);
        obj.put("referenceCount", result.referenceCount);
        return obj;
    }

    private static JSONObject lastResultSummary(Result result) {
        JSONObject obj = new JSONObject();
        if (result == null) {
            return obj;
        }
        obj.put("compiled", result.rlLastCompiled);
        obj.put("originalPassed", result.rlLastOriginalPassed);
        obj.put("killed", result.rlLastKilled);
        obj.put("targetStatus", safe(result.rlLastTargetStatus));
        obj.put("failureReason", safe(result.rlLastFailureReason));
        obj.put("timedOut", result.rlLastTimedOut);
        obj.put("promptChars", result.rlLastPromptChars);
        obj.put("llmCalls", result.rlLastLlmCalls);
        obj.put("compileCalls", result.rlLastCompileCalls);
        obj.put("repairRounds", result.rlLastRepairRounds);
        obj.put("elapsedMillis", result.rlLastElapsedMillis);
        obj.put("generationStrategy", safe(result.rlLastGenerationStrategy));
        return obj;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
