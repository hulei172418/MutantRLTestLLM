package mujava.rl;

import mujava.testgenerator.tools.Result;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * Serializable state used by transition logs. It combines static evidence
 * features with the latest generation/execution outcome.
 */
public final class RLStateSnapshot {
    public String episodeId = "";
    public String episodeInstanceId = "";
    public String canonicalMutantId = "";
    public String runId = "";
    public int outerLoopRound;
    public String evidenceHash = "";
    public String projectId = "";
    public String functionId = "";
    public String mutationSiteId = "";
    public String mutationClusterId = "";
    public String mutantId = "";
    public int step;
    public RLPhase phase = RLPhase.NOT_GENERATED;
    public EvidenceState evidenceState;
    public boolean generatedJava;
    public boolean compiled;
    public boolean originalPassed;
    public boolean mutantExecuted;
    public boolean killed;
    public boolean timedOut;
    public boolean equivalenceSuspicion;
    public String targetStatus = "";
    public String failureReason = "";
    public int promptChars;
    public int llmCalls;
    public int compileCalls;
    public int repairRounds;
    public long elapsedMillis;

    public static RLStateSnapshot from(EvidenceState state, Result result, int step) {
        RLStateSnapshot out = new RLStateSnapshot();
        out.evidenceState = state;
        out.step = step;
        out.mutantId = firstNonBlank(state == null ? "" : state.mutantId, result == null ? "" : result.mutantName);
        out.canonicalMutantId = out.mutantId;
        out.projectId = state == null ? "" : safe(state.project);
        out.functionId = buildFunctionId(out.projectId, state);
        out.mutationSiteId = buildMutationSiteId(out.functionId, state);
        out.mutationClusterId = buildMutationClusterId(out.mutationSiteId, state);
        out.episodeId = out.canonicalMutantId;
        out.episodeInstanceId = out.episodeId;
        if (result != null) {
            out.generatedJava = result.llmProducedVisibleCode || result.compiled || result.testJavaFile != null;
            out.compiled = result.compiled;
            out.originalPassed = result.originalPassed;
            out.mutantExecuted = !result.mutantResults.isEmpty();
            out.killed = result.killed;
            out.timedOut = result.timedOut;
            out.equivalenceSuspicion = result.equivalenceSuspicion;
            out.targetStatus = safe(result.targetStatus);
            out.failureReason = safe(result.failureReason);
            out.promptChars = result.promptChars;
            out.llmCalls = result.timing == null ? 0 : result.timing.llmCalls;
            out.compileCalls = result.timing == null ? 0 : result.timing.compileCalls;
            out.repairRounds = result.timing == null ? 0 : result.timing.repairRounds;
            out.elapsedMillis = result.timing == null ? 0L : result.timing.totalMillis;
        }
        out.phase = derivePhase(result);
        return out;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("episodeId", episodeId);
        obj.put("episodeInstanceId", episodeInstanceId);
        obj.put("canonicalMutantId", canonicalMutantId);
        obj.put("runId", runId);
        obj.put("outerLoopRound", outerLoopRound);
        obj.put("evidenceHash", evidenceHash);
        obj.put("projectId", projectId);
        obj.put("functionId", functionId);
        obj.put("mutationSiteId", mutationSiteId);
        obj.put("mutationClusterId", mutationClusterId);
        obj.put("mutantId", mutantId);
        obj.put("step", step);
        obj.put("phase", phase == null ? "" : phase.name());
        obj.put("evidenceState", evidenceState == null ? new JSONObject() : evidenceState.toJson());
        obj.put("generatedJava", generatedJava);
        obj.put("compiled", compiled);
        obj.put("originalPassed", originalPassed);
        obj.put("mutantExecuted", mutantExecuted);
        obj.put("killed", killed);
        obj.put("timedOut", timedOut);
        obj.put("equivalenceSuspicion", equivalenceSuspicion);
        obj.put("targetStatus", targetStatus);
        obj.put("failureReason", failureReason);
        obj.put("promptChars", promptChars);
        obj.put("llmCalls", llmCalls);
        obj.put("compileCalls", compileCalls);
        obj.put("repairRounds", repairRounds);
        obj.put("elapsedMillis", elapsedMillis);
        obj.put("allowedActions", actionsToJson(ActionMasker.allowed(phase)));
        return obj;
    }

    public static RLPhase derivePhase(Result result) {
        if (result == null) {
            return RLPhase.NOT_GENERATED;
        }
        String status = safe(result.targetStatus);
        if (result.equivalenceSuspicion
                && result.compiled
                && result.originalPassed
                && !result.killed) {
            return RLPhase.EQUIVALENT_SUSPECTED;
        }
        if (result.killed || Result.STATUS_KILLED.equalsIgnoreCase(status)) {
            return RLPhase.TARGET_KILLED;
        }
        if (result.timedOut || Result.STATUS_TIMEOUT.equalsIgnoreCase(status)) {
            return RLPhase.TIMEOUT;
        }
        if (Result.STATUS_API_FAILED.equalsIgnoreCase(status)
                || Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(status)
                || Result.STATUS_TASK_CRASHED.equalsIgnoreCase(status)) {
            return RLPhase.CRASHED;
        }
        if (!result.compiled) {
            return RLPhase.COMPILE_FAILED;
        }
        if (Result.STATUS_ORIGINAL_FAILED.equalsIgnoreCase(status) || !result.originalPassed) {
            return RLPhase.ORIGINAL_FAILED;
        }
        if (result.originalPassed && !result.killed) {
            return RLPhase.TARGET_LIVE;
        }
        return result.compiled ? RLPhase.COMPILED : RLPhase.GENERATED;
    }

    private static JSONArray actionsToJson(Set<RLAction> actions) {
        JSONArray arr = new JSONArray();
        if (actions != null) {
            for (RLAction action : actions) {
                arr.put(action.name());
            }
        }
        return arr;
    }

    private static String buildFunctionId(String projectId, EvidenceState state) {
        if (state == null) {
            return "";
        }
        return firstNonBlank(projectId, "UNKNOWN_PROJECT")
                + "::" + firstNonBlank(state.className, "UNKNOWN_CLASS")
                + "::" + firstNonBlank(state.method, "UNKNOWN_METHOD");
    }

    private static String buildMutationSiteId(String functionId, EvidenceState state) {
        String line = state == null ? "" : safe(state.line);
        if (line.isEmpty()) {
            line = "UNKNOWN_SITE";
        }
        return firstNonBlank(functionId, "UNKNOWN_FUNCTION") + "::" + line;
    }

    private static String buildMutationClusterId(String mutationSiteId, EvidenceState state) {
        String opFamily = state == null ? "" : safe(state.operatorFamily);
        if (opFamily.isEmpty()) {
            opFamily = "UNKNOWN_OPERATOR";
        }
        return firstNonBlank(mutationSiteId, "UNKNOWN_SITE") + "::" + opFamily;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
