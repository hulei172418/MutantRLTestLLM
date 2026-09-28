package mujava.rl;

import mujava.testgenerator.tools.Request;
import mujava.testgenerator.tools.Result;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class FailureFeedbackLogger {
    private final Path logFile;

    public FailureFeedbackLogger(String logPath) {
        this.logFile = Paths.get(logPath).toAbsolutePath().normalize();
    }

    public synchronized void append(Request request,
                                    EvidenceState state,
                                    EvidenceAction action,
                                    Result result,
                                    FailureAnalysis analysis) {
        if (result == null || result.killed || analysis == null) {
            return;
        }
        JSONObject obj = new JSONObject();
        obj.put("generatedAt", Instant.now().toString());
        obj.put("taskId", request == null ? "" : safe(request.taskId));
        obj.put("mutantId", state == null ? "" : safe(state.mutantId));
        obj.put("project", state == null ? "" : safe(state.project));
        obj.put("className", state == null ? "" : safe(state.className));
        obj.put("method", state == null ? "" : safe(state.method));
        obj.put("outputJsonPath", request == null ? "" : safe(request.outputJsonPath));
        obj.put("sourceModuleHome", request == null ? "" : safe(request.sourceModuleHome));
        obj.put("resultModuleHome", request == null ? "" : safe(request.resultModuleHome));
        obj.put("action", action == null ? "" : action.name());
        obj.put("compiled", result.compiled);
        obj.put("originalPassed", result.originalPassed);
        obj.put("killed", result.killed);
        obj.put("targetStatus", safe(result.targetStatus));
        obj.put("regenerationRound", result.regenerationRound);
        obj.put("semanticGenerationCalls", result.semanticGenerationCalls);
        obj.put("semanticStage", safe(result.semanticStage));
        obj.put("semanticPlanExhausted", result.semanticPlanExhausted);
        obj.put("lastKnownGoodRestored", result.lastKnownGoodRestored);
        obj.put("rlLastAttempt", result.rlLastAttempt);
        obj.put("rlBestAttempt", result.rlBestAttempt);
        obj.put("rlRegenStrategyPath", safe(result.rlRegenStrategyPath));
        obj.put("failureReason", safe(result.failureReason));
        obj.put("failureSymptom", safe(result.failureSymptom));
        obj.put("failureStage", safe(result.failureStage));
        obj.put("rootCauseType", safe(result.rootCauseType));
        obj.put("primaryFixTarget", safe(result.primaryFixTarget));
        obj.put("secondaryFixTarget", safe(result.secondaryFixTarget));
        obj.put("suggestedAction", safe(result.suggestedAction));
        obj.put("attributionWhy", safe(result.attributionWhy));
        obj.put("failureConfidence", result.failureConfidence);
        obj.put("signalLedger", analysis.signalLedger.toJson());
        obj.put("failureAnalysis", analysis.toJson());
        try {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile,
                (obj.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append failure feedback log: " + logFile, e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
