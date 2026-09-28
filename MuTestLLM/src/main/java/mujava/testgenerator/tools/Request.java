package mujava.testgenerator.tools;

/**
 * Input parameters for one LLM-based test-generation task.
 */
public final class Request {
    public String sourceModuleHome;
    public String resultModuleHome;
    public String projectName;
    public String targetClassName;
    public String methodSignature;
    public String mutantName;
    public String outputJsonPath;
    public String originJavaPath;
    public String mutatedJavaPath;
    public int timeoutMillis = GeneratorDefaults.DEFAULT_TIMEOUT_MILLIS;
    public int maxRepairRounds = GeneratorDefaults.DEFAULT_MAX_REPAIR_ROUNDS;

    /** Optional row/task label used only for logs and JSONL reporting. */
    public String taskId;
    public int excelRowIndex = -1;
    public String runId;
    public int outerLoopRound;
    public String evidenceHash;
    /** Semantic generation stage: STRICT_CORE, RELAX_ORACLE, or RELAX_OBSERVABLE_ORACLE. */
    public String semanticStage = "STRICT_CORE";
    public int functionMutantCount;
    public int siteMutantCount;
    public int functionProcessedCount;
    public int siteProcessedCount;

    /** Maximum HTTP attempts for one uncached LLM prompt. */
    public int maxLlmApiAttempts = GeneratorDefaults.DEFAULT_MAX_LLM_API_ATTEMPTS;

    /** When true, a task with an existing generated .java and .class is skipped. */
    public boolean skipIfCompiledExists = GeneratorDefaults.DEFAULT_SKIP_EXISTING_COMPILED_TESTS;
}
