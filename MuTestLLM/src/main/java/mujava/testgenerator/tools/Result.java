package mujava.testgenerator.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Output summary for one generation/compile task.
 *
 * targetStatus is the final unified outcome across generation, compilation,
 * and target-level execution stages.
 */
public final class Result {
    public static final String STATUS_SKIPPED_BY_EVIDENCE = "SKIPPED_BY_EVIDENCE";
    public static final String STATUS_LLM_OUTPUT_INVALID = "LLM_OUTPUT_INVALID";
    public static final String STATUS_LLM_NO_VISIBLE_CODE = "LLM_NO_VISIBLE_CODE";
    public static final String STATUS_COMPILE_FAILED_AFTER_GENERATION = "COMPILE_FAILED_AFTER_GENERATION";
    public static final String STATUS_COMPILE_FAILED_AFTER_REPAIR = "COMPILE_FAILED_AFTER_REPAIR";
    public static final String STATUS_KILLED = "KILLED";
    public static final String STATUS_SURVIVED = "SURVIVED";
    public static final String STATUS_EQUIVALENT_SUSPECTED = "EQUIVALENT_SUSPECTED";
    public static final String STATUS_ORIGINAL_FAILED = "ORIGINAL_FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_API_FAILED = "API_FAILED";
    public static final String STATUS_GENERATION_FAILED = "GENERATION_FAILED";
    public static final String STATUS_TASK_CRASHED = "TASK_CRASHED";
    public static final String STATUS_NOT_EXECUTED = "NOT_EXECUTED";

    public String taskId;
    public String targetClassName;
    public String methodSignature;
    public String mutantName;
    public String testSetName;
    public String testJavaFile;
    public String reportDir;
    public String resultJsonlFile;
    public boolean compiled;
    public int compileRounds;
    public String failureReason;
    public boolean skippedExistingCompiledTest;
    public int promptChars;
    public boolean originalPassed;
    public boolean killed;
    public String targetStatus;
    public boolean timedOut;
    public String generationStrategy;
    public boolean referenceGuided;
    public boolean equivalenceSuspicion;
    public String equivalenceReason;
    public String failureSymptom;
    public String failureStage;
    public String rootCauseType;
    public String primaryFixTarget;
    public String secondaryFixTarget;
    public String suggestedAction;
    public String attributionWhy;
    public double failureConfidence;
    public int referenceCount;
    public int regenerationRound;
    /** Semantic-generation calls: initial STRICT core + survivor-triggered semantic regenerations. Compile-repair LLM calls are excluded. */
    public int semanticGenerationCalls;
    /** STRICT_CORE, RELAX_ORACLE, or RELAX_OBSERVABLE_ORACLE for the selected result. */
    public String semanticStage;
    /** True when the normal three-stage semantic plan has been attempted without a kill. */
    public boolean semanticPlanExhausted;
    /** True when a failed/crashed regeneration candidate was rolled back to the best previously compiled test. */
    public boolean lastKnownGoodRestored;
    public boolean llmProducedVisibleCode;
    public String semanticWarning;
    public int semanticWarningCount;
    public String rlInitialEvidenceAction;
    public String rlRegenPolicyName;
    public String rlRegenStrategyPath;
    public double rlInitialReward;
    public double rlFinalReward;
    public double rlPolicyUpdateReward;
    public String rlPolicyUpdateSource;
    public boolean rlInitialCompiled;
    public boolean rlInitialOriginalPassed;
    public boolean rlInitialKilled;
    public boolean rlInitialTimedOut;
    public String rlInitialTargetStatus;
    public String rlInitialFailureReason;
    public int rlInitialPromptChars;
    public int rlInitialLlmCalls;
    public int rlInitialCompileCalls;
    public int rlInitialRepairRounds;
    public long rlInitialElapsedMillis;
    public String rlInitialGenerationStrategy;
    public int rlBestAttempt;
    public int rlLastAttempt;
    public String rlTerminationReason;
    public boolean rlLastCompiled;
    public boolean rlLastOriginalPassed;
    public boolean rlLastKilled;
    public boolean rlLastTimedOut;
    public String rlLastTargetStatus;
    public String rlLastFailureReason;
    public int rlLastPromptChars;
    public int rlLastLlmCalls;
    public int rlLastCompileCalls;
    public int rlLastRepairRounds;
    public long rlLastElapsedMillis;
    public String rlLastGenerationStrategy;
    public final List<String> junitTests = new ArrayList<String>();
    public final LinkedHashMap<String, String> originalResults = new LinkedHashMap<String, String>();
    public final LinkedHashMap<String, String> mutantResults = new LinkedHashMap<String, String>();
    public String originalLogFile;
    public String mutantLogFile;
    public final TaskTiming timing = new TaskTiming();
}
