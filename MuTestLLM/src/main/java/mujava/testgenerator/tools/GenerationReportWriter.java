package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static mujava.testgenerator.tools.CommonUtils.nullToEmpty;

/**
 * Writes generation summaries and JSONL records.
 */
public final class GenerationReportWriter {
    private GenerationReportWriter() {
    }

    public static void writeSummary(Path path, Result result) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("generatedAt=").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append('\n');
        sb.append("taskId=").append(nullToEmpty(result.taskId)).append('\n');
        sb.append("targetClassName=").append(nullToEmpty(result.targetClassName)).append('\n');
        sb.append("methodSignature=").append(nullToEmpty(result.methodSignature)).append('\n');
        sb.append("mutantName=").append(nullToEmpty(result.mutantName)).append('\n');
        sb.append("testSetName=").append(nullToEmpty(result.testSetName)).append('\n');
        sb.append("testJavaFile=").append(nullToEmpty(result.testJavaFile)).append('\n');
        sb.append("reportDir=").append(nullToEmpty(result.reportDir)).append('\n');
        sb.append("resultJsonlFile=").append(nullToEmpty(result.resultJsonlFile)).append('\n');
        sb.append("compiled=").append(result.compiled).append('\n');
        sb.append("compileRounds=").append(result.compileRounds).append('\n');
        sb.append("failureReason=").append(nullToEmpty(result.failureReason)).append('\n');
        sb.append("skippedExistingCompiledTest=").append(result.skippedExistingCompiledTest).append('\n');
        sb.append("promptChars=").append(result.promptChars).append('\n');
        sb.append("originalPassed=").append(result.originalPassed).append('\n');
        sb.append("killed=").append(result.killed).append('\n');
        sb.append("targetStatus=").append(nullToEmpty(result.targetStatus)).append('\n');
        sb.append("timedOut=").append(result.timedOut).append('\n');
        sb.append("generationStrategy=").append(nullToEmpty(result.generationStrategy)).append('\n');
        sb.append("referenceGuided=").append(result.referenceGuided).append('\n');
        sb.append("equivalenceSuspicion=").append(result.equivalenceSuspicion).append('\n');
        sb.append("equivalenceReason=").append(nullToEmpty(result.equivalenceReason)).append('\n');
        sb.append("failureSymptom=").append(nullToEmpty(result.failureSymptom)).append('\n');
        sb.append("failureStage=").append(nullToEmpty(result.failureStage)).append('\n');
        sb.append("rootCauseType=").append(nullToEmpty(result.rootCauseType)).append('\n');
        sb.append("primaryFixTarget=").append(nullToEmpty(result.primaryFixTarget)).append('\n');
        sb.append("secondaryFixTarget=").append(nullToEmpty(result.secondaryFixTarget)).append('\n');
        sb.append("suggestedAction=").append(nullToEmpty(result.suggestedAction)).append('\n');
        sb.append("attributionWhy=").append(nullToEmpty(result.attributionWhy)).append('\n');
        sb.append("failureConfidence=").append(result.failureConfidence).append('\n');
        sb.append("referenceCount=").append(result.referenceCount).append('\n');
        sb.append("regenerationRound=").append(result.regenerationRound).append('\n');
        sb.append("semanticGenerationCalls=").append(result.semanticGenerationCalls).append('\n');
        sb.append("semanticStage=").append(nullToEmpty(result.semanticStage)).append('\n');
        sb.append("semanticPlanExhausted=").append(result.semanticPlanExhausted).append('\n');
        sb.append("lastKnownGoodRestored=").append(result.lastKnownGoodRestored).append('\n');
        sb.append("llmProducedVisibleCode=").append(result.llmProducedVisibleCode).append('\n');
        sb.append("semanticWarningCount=").append(result.semanticWarningCount).append('\n');
        sb.append("semanticWarning=").append(nullToEmpty(result.semanticWarning)).append('\n');
        sb.append("rlInitialEvidenceAction=").append(nullToEmpty(result.rlInitialEvidenceAction)).append('\n');
        sb.append("rlRegenPolicyName=").append(nullToEmpty(result.rlRegenPolicyName)).append('\n');
        sb.append("rlRegenStrategyPath=").append(nullToEmpty(result.rlRegenStrategyPath)).append('\n');
        sb.append("rlInitialReward=").append(result.rlInitialReward).append('\n');
        sb.append("rlFinalReward=").append(result.rlFinalReward).append('\n');
        sb.append("rlPolicyUpdateReward=").append(result.rlPolicyUpdateReward).append('\n');
        sb.append("rlPolicyUpdateSource=").append(nullToEmpty(result.rlPolicyUpdateSource)).append('\n');
        sb.append("originalLogFile=").append(nullToEmpty(result.originalLogFile)).append('\n');
        sb.append("mutantLogFile=").append(nullToEmpty(result.mutantLogFile)).append('\n');
        sb.append("junitTests=").append(new JSONArray(result.junitTests).toString()).append('\n');
        sb.append("originalResults=").append(new JSONObject(result.originalResults).toString()).append('\n');
        sb.append("mutantResults=").append(new JSONObject(result.mutantResults).toString()).append('\n');
        if (result.timing != null) {
            sb.append("evidenceMillis=").append(result.timing.evidenceMillis).append('\n');
            sb.append("promptMillis=").append(result.timing.promptMillis).append('\n');
            sb.append("llmMillis=").append(result.timing.llmMillis).append('\n');
            sb.append("compileMillis=").append(result.timing.compileMillis).append('\n');
            sb.append("repairPromptMillis=").append(result.timing.repairPromptMillis).append('\n');
            sb.append("totalMillis=").append(result.timing.totalMillis).append('\n');
            sb.append("llmCalls=").append(result.timing.llmCalls).append('\n');
            sb.append("compileCalls=").append(result.timing.compileCalls).append('\n');
            sb.append("repairRounds=").append(result.timing.repairRounds).append('\n');
            sb.append("llmCacheHit=").append(result.timing.llmCacheHit).append('\n');
            sb.append("skippedExistingCompiled=").append(result.timing.skippedExistingCompiled).append('\n');
        }
        FileTextUtils.writeText(path, sb.toString());
    }

    public static synchronized void appendResultJsonl(Path path, Result result) throws IOException {
        Files.createDirectories(path.getParent());
        JSONObject obj = new JSONObject();
        obj.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        obj.put("taskId", nullToEmpty(result.taskId));
        obj.put("targetClassName", nullToEmpty(result.targetClassName));
        obj.put("methodSignature", nullToEmpty(result.methodSignature));
        obj.put("mutantName", nullToEmpty(result.mutantName));
        obj.put("testSetName", nullToEmpty(result.testSetName));
        obj.put("testJavaFile", nullToEmpty(result.testJavaFile));
        obj.put("reportDir", nullToEmpty(result.reportDir));
        obj.put("compiled", result.compiled);
        obj.put("compileRounds", result.compileRounds);
        obj.put("failureReason", nullToEmpty(result.failureReason));
        obj.put("skippedExistingCompiledTest", result.skippedExistingCompiledTest);
        obj.put("promptChars", result.promptChars);
        obj.put("originalPassed", result.originalPassed);
        obj.put("killed", result.killed);
        obj.put("targetStatus", nullToEmpty(result.targetStatus));
        obj.put("timedOut", result.timedOut);
        obj.put("generationStrategy", nullToEmpty(result.generationStrategy));
        obj.put("referenceGuided", result.referenceGuided);
        obj.put("equivalenceSuspicion", result.equivalenceSuspicion);
        obj.put("equivalenceReason", nullToEmpty(result.equivalenceReason));
        obj.put("failureSymptom", nullToEmpty(result.failureSymptom));
        obj.put("failureStage", nullToEmpty(result.failureStage));
        obj.put("rootCauseType", nullToEmpty(result.rootCauseType));
        obj.put("primaryFixTarget", nullToEmpty(result.primaryFixTarget));
        obj.put("secondaryFixTarget", nullToEmpty(result.secondaryFixTarget));
        obj.put("suggestedAction", nullToEmpty(result.suggestedAction));
        obj.put("attributionWhy", nullToEmpty(result.attributionWhy));
        obj.put("failureConfidence", result.failureConfidence);
        obj.put("referenceCount", result.referenceCount);
        obj.put("regenerationRound", result.regenerationRound);
        obj.put("semanticGenerationCalls", result.semanticGenerationCalls);
        obj.put("semanticStage", nullToEmpty(result.semanticStage));
        obj.put("semanticPlanExhausted", result.semanticPlanExhausted);
        obj.put("lastKnownGoodRestored", result.lastKnownGoodRestored);
        obj.put("llmProducedVisibleCode", result.llmProducedVisibleCode);
        obj.put("semanticWarningCount", result.semanticWarningCount);
        obj.put("semanticWarning", nullToEmpty(result.semanticWarning));
        obj.put("rlInitialEvidenceAction", nullToEmpty(result.rlInitialEvidenceAction));
        obj.put("rlRegenPolicyName", nullToEmpty(result.rlRegenPolicyName));
        obj.put("rlRegenStrategyPath", nullToEmpty(result.rlRegenStrategyPath));
        obj.put("rlInitialReward", result.rlInitialReward);
        obj.put("rlFinalReward", result.rlFinalReward);
        obj.put("rlPolicyUpdateReward", result.rlPolicyUpdateReward);
        obj.put("rlPolicyUpdateSource", nullToEmpty(result.rlPolicyUpdateSource));
        obj.put("originalLogFile", nullToEmpty(result.originalLogFile));
        obj.put("mutantLogFile", nullToEmpty(result.mutantLogFile));
        obj.put("junitTests", new JSONArray(result.junitTests));
        obj.put("originalResults", new JSONObject(result.originalResults));
        obj.put("mutantResults", new JSONObject(result.mutantResults));
        if (result.timing != null) {
            obj.put("evidenceMillis", result.timing.evidenceMillis);
            obj.put("promptMillis", result.timing.promptMillis);
            obj.put("llmMillis", result.timing.llmMillis);
            obj.put("compileMillis", result.timing.compileMillis);
            obj.put("repairPromptMillis", result.timing.repairPromptMillis);
            obj.put("totalMillis", result.timing.totalMillis);
            obj.put("llmCalls", result.timing.llmCalls);
            obj.put("compileCalls", result.timing.compileCalls);
            obj.put("repairRounds", result.timing.repairRounds);
            obj.put("llmCacheHit", result.timing.llmCacheHit);
            obj.put("skippedExistingCompiled", result.timing.skippedExistingCompiled);
        }
        String line = obj.toString() + System.lineSeparator();
        Files.write(path, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
