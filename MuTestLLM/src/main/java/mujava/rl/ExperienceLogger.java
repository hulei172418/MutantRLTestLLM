package mujava.rl;

import mujava.testgenerator.tools.Result;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Appends one RL experience record per processed mutant.
 */
public final class ExperienceLogger {
    private final Path logFile;

    public ExperienceLogger(String logPath) {
        this.logFile = Paths.get(logPath).toAbsolutePath().normalize();
    }

    public synchronized void append(EvidenceState state,
                                    EvidenceAction action,
                                    Result result,
                                    RewardBreakdown rewardBreakdown) {
        JSONObject obj = new JSONObject();
        obj.put("taskId", result == null ? "" : safe(result.taskId));
        obj.put("mutantId", state == null ? "" : state.mutantId);
        obj.put("project", state == null ? "" : state.project);
        obj.put("operator", state == null ? "" : state.operator);
        obj.put("className", state == null ? "" : state.className);
        obj.put("method", state == null ? "" : state.method);
        obj.put("line", state == null ? "" : state.line);
        obj.put("outerLoopRound", state == null ? 0 : state.outerLoopRound);
        obj.put("state", state == null ? new JSONObject() : state.toJson());
        obj.put("action", action == null ? "" : action.name());
        obj.put("testName", result == null ? "" : safe(result.testSetName));
        obj.put("promptTokensOrChars", result == null ? 0 : result.promptChars);
        obj.put("compileSuccess", result != null && result.compiled);
        obj.put("originalPass", result != null && result.originalPassed);
        obj.put("killed", result != null && result.killed);
        obj.put("repairRounds", result == null || result.timing == null ? 0 : result.timing.repairRounds);
        obj.put("elapsedMs", result == null || result.timing == null ? 0L : result.timing.totalMillis);
        obj.put("reward", rewardBreakdown == null ? 0.0d : rewardBreakdown.totalReward);
        obj.put("rewardBreakdown", rewardBreakdown == null ? new JSONObject() : rewardBreakdown.toJson());
        obj.put("initialEvidenceAction", result == null ? "" : safe(result.rlInitialEvidenceAction));
        obj.put("regenPolicyName", result == null ? "" : safe(result.rlRegenPolicyName));
        obj.put("regenStrategyPath", result == null ? "" : safe(result.rlRegenStrategyPath));
        obj.put("initialReward", result == null ? 0.0d : result.rlInitialReward);
        obj.put("finalReward", result == null ? 0.0d : result.rlFinalReward);
        obj.put("policyUpdateReward", result == null ? 0.0d : result.rlPolicyUpdateReward);
        obj.put("policyUpdateSource", result == null ? "" : safe(result.rlPolicyUpdateSource));
        obj.put("initialCompiled", result != null && result.rlInitialCompiled);
        obj.put("initialOriginalPass", result != null && result.rlInitialOriginalPassed);
        obj.put("initialKilled", result != null && result.rlInitialKilled);
        obj.put("initialTimedOut", result != null && result.rlInitialTimedOut);
        obj.put("initialTargetStatus", result == null ? "" : safe(result.rlInitialTargetStatus));
        obj.put("initialFailureReason", result == null ? "" : safe(result.rlInitialFailureReason));
        obj.put("initialPromptChars", result == null ? 0 : result.rlInitialPromptChars);
        obj.put("initialLlmCalls", result == null ? 0 : result.rlInitialLlmCalls);
        obj.put("initialCompileCalls", result == null ? 0 : result.rlInitialCompileCalls);
        obj.put("initialRepairRounds", result == null ? 0 : result.rlInitialRepairRounds);
        obj.put("initialElapsedMs", result == null ? 0L : result.rlInitialElapsedMillis);
        obj.put("initialGenerationStrategy", result == null ? "" : safe(result.rlInitialGenerationStrategy));
        obj.put("fallbackLevel", state == null ? "" : safe(state.lastFallbackLevel));
        obj.put("operatorFamily", state == null ? "" : safe(state.operatorFamily));
        obj.put("promptSizeBucket", state == null ? "" : safe(state.promptSizeBucket));
        obj.put("exactBucketKey", state == null ? "" : safe(state.exactBucketKey));
        obj.put("coarseBucketKey", state == null ? "" : safe(state.coarseBucketKey));
        obj.put("hasSuccessfulReference", state != null && state.hasSuccessfulReference);
        obj.put("successfulReferenceCount", state == null ? 0 : state.successfulReferenceCount);
        obj.put("hasPreviousResult", state != null && state.hasPreviousResult);
        obj.put("previousCompileSuccess", state != null && state.previousCompileSuccess);
        obj.put("previousOriginalPassed", state != null && state.previousOriginalPassed);
        obj.put("previousKilled", state != null && state.previousKilled);
        obj.put("previousRepairRounds", state == null ? 0 : state.previousRepairRounds);
        obj.put("previousTargetStatus", state == null ? "" : safe(state.previousTargetStatus));
        obj.put("sameSiteSuccessfulCount", state == null ? 0 : state.sameSiteSuccessfulCount);
        obj.put("sameMethodSuccessfulCount", state == null ? 0 : state.sameMethodSuccessfulCount);
        obj.put("sameClassSuccessfulCount", state == null ? 0 : state.sameClassSuccessfulCount);
        obj.put("sameSiteCompileSuccessRate", state == null ? 0.0d : state.sameSiteCompileSuccessRate);
        obj.put("sameSiteKillRate", state == null ? 0.0d : state.sameSiteKillRate);
        obj.put("sameMethodCompileSuccessRate", state == null ? 0.0d : state.sameMethodCompileSuccessRate);
        obj.put("sameMethodKillRate", state == null ? 0.0d : state.sameMethodKillRate);
        obj.put("bestSuccessfulReferenceSimilarity", state == null ? 0.0d : state.bestSuccessfulReferenceSimilarity);
        obj.put("hasCodeKbContext", state != null && state.hasCodeKbContext);
        obj.put("codeKbEntryCount", state == null ? 0 : state.codeKbEntryCount);
        obj.put("codeKbMethodCallCount", state == null ? 0 : state.codeKbMethodCallCount);
        obj.put("codeKbFieldAccessCount", state == null ? 0 : state.codeKbFieldAccessCount);
        obj.put("codeKbTopEntrySignature", state == null ? "" : safe(state.codeKbTopEntrySignature));
        obj.put("codeKbTopEntryReason", state == null ? "" : safe(state.codeKbTopEntryReason));
        obj.put("preferredAssertionMode", state == null ? "" : safe(state.preferredAssertionMode));
        obj.put("mutationSemanticKind", state == null ? "" : safe(state.mutationSemanticKind));
        obj.put("forcedBranchMutation", state != null && state.forcedBranchMutation);
        obj.put("hasCanonicalObservable", state != null && state.hasCanonicalObservable);
        obj.put("hasMandatoryInfectionConstraints", state != null && state.hasMandatoryInfectionConstraints);
        obj.put("hasRequiredNonNullSubjects", state != null && state.hasRequiredNonNullSubjects);
        obj.put("hasExpectedOriginalExecutable", state != null && state.hasExpectedOriginalExecutable);
        obj.put("entryControllabilityCoverage", state == null ? 0.0d : state.entryControllabilityCoverage);
        obj.put("entryIndependentCoverage", state == null ? 0.0d : state.entryIndependentCoverage);
        obj.put("entryConstraintStatus", state == null ? "" : safe(state.entryConstraintStatus));
        obj.put("mandatoryGuardCount", state == null ? 0 : state.mandatoryGuardCount);
        obj.put("hasFalsePolarityGuard", state != null && state.hasFalsePolarityGuard);
        obj.put("mandatoryCofactorCount", state == null ? 0 : state.mandatoryCofactorCount);
        obj.put("observableDirectness", state == null ? "" : safe(state.observableDirectness));
        obj.put("failureReason", result == null ? "" : safe(result.failureReason));
        obj.put("targetStatus", result == null ? "" : safe(result.targetStatus));
        obj.put("timedOut", result != null && result.timedOut);
        obj.put("generationStrategy", result == null ? "" : safe(result.generationStrategy));
        obj.put("referenceGuided", result != null && result.referenceGuided);
        obj.put("equivalenceSuspicion", result != null && result.equivalenceSuspicion);
        obj.put("equivalenceReason", result == null ? "" : safe(result.equivalenceReason));
        obj.put("failureSymptom", result == null ? "" : safe(result.failureSymptom));
        obj.put("failureStage", result == null ? "" : safe(result.failureStage));
        obj.put("rootCauseType", result == null ? "" : safe(result.rootCauseType));
        obj.put("primaryFixTarget", result == null ? "" : safe(result.primaryFixTarget));
        obj.put("secondaryFixTarget", result == null ? "" : safe(result.secondaryFixTarget));
        obj.put("suggestedAction", result == null ? "" : safe(result.suggestedAction));
        obj.put("attributionWhy", result == null ? "" : safe(result.attributionWhy));
        obj.put("failureConfidence", result == null ? 0.0d : result.failureConfidence);
        obj.put("referenceCount", result == null ? 0 : result.referenceCount);
        obj.put("regenerationRound", result == null ? 0 : result.regenerationRound);
        obj.put("llmProducedVisibleCode", result != null && result.llmProducedVisibleCode);
        obj.put("semanticWarningCount", result == null ? 0 : result.semanticWarningCount);
        obj.put("semanticWarning", result == null ? "" : safe(result.semanticWarning));

        try {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile,
                    (obj.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append RL experience log: " + logFile, e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
