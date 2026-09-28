package mujava.testgenerator;

import mujava.MutationSystem;
import mujava.cmd.TestRunner9_MultiProcess_batched;
import mujava.rl.BanditPolicy;
import mujava.rl.EpsilonGreedyBanditPolicy;
import mujava.rl.EvidenceAction;
import mujava.rl.EpisodeLogger;
import mujava.rl.EpisodeSummary;
import mujava.rl.FailureAnalysis;
import mujava.rl.FailureAnalyzer;
import mujava.rl.FailureFeedbackLogger;
import mujava.rl.HistoricalExperienceSnapshot;
import mujava.rl.EvidenceState;
import mujava.rl.ExperienceLogger;
import mujava.rl.LinUCBPolicy;
import mujava.rl.PolicyStore;
import mujava.rl.RLAction;
import mujava.rl.RLStateSnapshot;
import mujava.rl.RLEvidenceBuilder;
import mujava.rl.RLPipelineConfig;
import mujava.rl.RewardBuilder;
import mujava.rl.RewardBreakdown;
import mujava.rl.StateBuilder;
import mujava.rl.SuccessTestReference;
import mujava.rl.SuccessTestRetriever;
import mujava.rl.Transition;
import mujava.rl.TransitionLogger;
import mujava.rl.TransitionRewardCalculator;
import mujava.testgenerator.tools.CachedLlmClient;
import mujava.testgenerator.tools.DeterministicScaffoldBuilder;
import mujava.testgenerator.tools.EvidenceAwarePromptBudgeter;
import mujava.testgenerator.tools.FileTextUtils;
import mujava.testgenerator.tools.GeneratedCodeExtractor;
import mujava.testgenerator.tools.GeneratedCodeEvidenceGuard;
import mujava.testgenerator.tools.GeneratedTestCompiler;
import mujava.testgenerator.tools.GenerationReportWriter;
import mujava.testgenerator.tools.GeneratorFeatureFlags;
import mujava.testgenerator.tools.HistoricalReferencePromptBuilder;
import mujava.testgenerator.tools.GeneratorDefaults;
import mujava.testgenerator.tools.InitialPromptBuilder;
import mujava.testgenerator.tools.LlmCallResult;
import mujava.testgenerator.tools.LlmClient;
import mujava.testgenerator.tools.LlmRuntimeConfig;
import mujava.testgenerator.tools.LlmRuntimeConfigLoader;
import mujava.testgenerator.tools.ModelConfig;
import mujava.testgenerator.tools.ModelConfigLoader;
import mujava.testgenerator.tools.ProjectClasspathCache;
import mujava.testgenerator.tools.PromptBudgetPolicy;
import mujava.testgenerator.tools.PromptBudgetProfile;
import mujava.testgenerator.tools.PromptEvidence;
import mujava.testgenerator.tools.RegenerationPromptBuilder;
import mujava.testgenerator.tools.RegenerationPromptBuilder.RegenerationStrategy;
import mujava.testgenerator.tools.RepairPromptBuilder;
import mujava.testgenerator.tools.Request;
import mujava.testgenerator.tools.RequestValidator;
import mujava.testgenerator.tools.Result;
import mujava.testgenerator.tools.SemanticTestRejector;
import mujava.util.MuJavaPathConfig;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static mujava.cmd.TestRunner9_MultiProcess_batched.bootstrapMuJavaConfigProperty;
import static mujava.cmd.TestRunner9_MultiProcess_batched.getCurr;
import static mujava.cmd.TestRunner9_MultiProcess_batched.readExcelFile;
import static mujava.cmd.TestRunner9_MultiProcess_batched.resolveSourceModuleHome;
import static mujava.cmd.TestRunner9_MultiProcess_batched.resolveSourceRootHome;
import static mujava.testgenerator.tools.CommonUtils.oneLine;
import static mujava.testgenerator.tools.FileTextUtils.writeText;
import static mujava.testgenerator.tools.TestNameUtils.buildGeneratedTestFqn;
import static mujava.testgenerator.tools.TestNameUtils.fileBaseName;
import static mujava.testgenerator.tools.TestNameUtils.toJavaFile;

/**
 * LLM-based JUnit4 generation batch entry.
 *
 * Baseline mode keeps the original generate+compile behavior.
 * RL mode adds a first-stage contextual bandit loop around evidence selection,
 * then executes the compiled test against its target mutant to compute reward.
 */
public final class LLMTestGeneratorBatch {
    private static final String EXECUTION_RUN_ID =
            firstNonBlank(
                    System.getProperty("llm.closed.loop.run.id"),
                    new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()));
    private static final String BATCH_EXECUTION_REPORT_DIR = "generation-execution-report";
    private static final String TARGET_RESULTS_JSON = "llm_target_kill_results.json";
    private static final String TARGET_RESULTS_CSV = "llm_target_kill_results.csv";
    private static final String SUITE_RESULTS_JSON = "llm_suite_kill_results.json";
    private static final String SUMMARY_TXT = "llm_execution_summary.txt";
    private static final String DIRECT_EXCEL_FILE = "./data/oot.xlsx";
    private static final Set<String> SUPPORTED_PROJECTS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(
                    "ant-1.10.12",
                    "bcel-6.10.0",
                    "commons-codec-1.10",
                    "commons-csv-1.2",
                    "commons-jxpath-1.3",
                    "commons-lang3-3.17.0",
                    "jackson-core-2.9.9",
                    "joda-time-2.14.0",
                    "commons-cli-1.11.0",
                    "oot",
                    "commons-math-core",
                    "commons-math-legacy-core",
                    "commons-math-legacy-exception",
                    "commons-math-neuralnet",
                    "commons-math-transform",
                    "commons-numbers-angle",
                    "commons-numbers-arrays",
                    "commons-numbers-combinatorics",
                    "commons-numbers-core",
                    "commons-numbers-gamma",
                    "commons-numbers-primes",
                    "commons-numbers-quaternion"
            )));

    private static final int DEFAULT_TIMEOUT_MILLIS = GeneratorDefaults.DEFAULT_TIMEOUT_MILLIS;
    private static final int DEFAULT_MAX_REPAIR_ROUNDS = GeneratorDefaults.DEFAULT_MAX_REPAIR_ROUNDS;

    private LLMTestGeneratorBatch() {
    }

    public static void main(String[] args) throws Exception {
        ensureMuJavaConfigProperty();
        BatchArgs batchArgs = BatchArgs.parse(args);
        String excelPath = batchArgs.excelPath;
        List<List<Object>> excelData = readExcelFile(excelPath);
        if (excelData.isEmpty()) {
            System.err.println("[ERROR] Excel data is empty: " + excelPath);
            return;
        }

        TestRunner9_MultiProcess_batched.setTestSetMode(MutationSystem.TESTSET_MODE_LLMS);
        bootstrapMuJavaConfigProperty(excelPath);

        ModelConfig modelConfig = ModelConfigLoader.load();
        LlmRuntimeConfig runtimeConfig = LlmRuntimeConfigLoader.load();
        RLPipelineConfig rlConfig = RLPipelineConfig.load();
        printConfig(modelConfig, runtimeConfig, rlConfig, excelPath, batchArgs.rowStart, batchArgs.rowEnd,
                batchArgs.rows);

        List<Request> requests = buildRequestsFromExcel(excelData, runtimeConfig, batchArgs.rowStart, batchArgs.rowEnd,
                batchArgs.rows);
        runRequestsConcurrently(requests, modelConfig, runtimeConfig, rlConfig);
    }

    private static void ensureMuJavaConfigProperty() {
        String resolved = MuJavaPathConfig.resolveMuJavaConfigPath();
        if (resolved != null && !resolved.trim().isEmpty()) {
            System.setProperty("mujava.config.path", resolved);
            System.out.println("[INFO ] mujava.config.path=" + resolved);
            String librariesPath = MuJavaPathConfig.resolveLibrariesPath(resolved);
            if (librariesPath != null && !librariesPath.trim().isEmpty()) {
                System.setProperty("mujava.libraries.path", librariesPath);
                System.out.println("[INFO ] mujava.libraries.path=" + librariesPath);
            }
            return;
        }
        System.err.println("[WARN ] mujava config paths not resolved; classpath resolution may be incomplete.");
    }

    private static void printConfig(ModelConfig modelConfig,
                                    LlmRuntimeConfig runtimeConfig,
                                    RLPipelineConfig rlConfig,
                                    String excelPath,
                                    int rowStart,
                                    int rowEnd,
                                    Set<Integer> selectedRows) {
        System.out.println("[LLM-CONFIG] excel=" + excelPath
                + ", rowStart=" + rowStart
                + ", rowEnd=" + rowEnd
                + ", rows=" + describeRows(selectedRows)
                + ", provider=" + modelConfig.getProvider()
                + ", model=" + modelConfig.getModel()
                + ", threads=" + runtimeConfig.getThreadCount()
                + ", pipelineEnabled=" + runtimeConfig.isStagedPipelineEnabled()
                + ", pipelineGenerationThreads=" + runtimeConfig.getGenerationThreadCount()
                + ", pipelinePostThreads=" + runtimeConfig.getPostProcessThreadCount()
                + ", suiteEvaluationEnabled=" + runtimeConfig.isSuiteEvaluationEnabled()
                + ", apiMaxAttempts=" + runtimeConfig.getMaxApiAttempts()
                + ", maxRepairRounds=" + runtimeConfig.getMaxRepairRounds()
                + ", skipExistingCompiled=" + runtimeConfig.isSkipExistingCompiledTests()
                + ", forceRegenerate=" + runtimeConfig.isForceRegenerate()
                + ", dynamicBudget=" + runtimeConfig.isDynamicBudgetEnabled()
                + ", initialBudget=" + runtimeConfig.getInitialMaxInputTokens() + "/" + runtimeConfig.getInitialMaxOutputTokens()
                + ", repair1Ratio=" + runtimeConfig.getRepair1InputRatio() + "/" + runtimeConfig.getRepair1OutputRatio()
                + ", repair2Ratio=" + runtimeConfig.getRepair2InputRatio() + "/" + runtimeConfig.getRepair2OutputRatio()
                + ", hardBudget=" + runtimeConfig.getHardMaxInputTokens() + "/" + runtimeConfig.getHardMaxOutputTokens()
                + ", oversizePolicy=" + runtimeConfig.getOversizePromptPolicy()
                + ", rlEnabled=" + rlConfig.isEnabled()
                + ", rlMode=" + rlConfig.getMode()
                + ", rlPolicy=" + rlConfig.getPolicy()
                + ", rlEpsilon=" + rlConfig.getEpsilon()
                + ", rlBucketMinExact=" + rlConfig.getBucketMinSamplesExact()
                + ", rlBucketMinCoarse=" + rlConfig.getBucketMinSamplesCoarse()
                + ", rlLinucbAlpha=" + rlConfig.getLinucbAlpha()
                + ", rlLinucbLambda=" + rlConfig.getLinucbLambda()
                + ", rlLinucbFunctionBalance=" + rlConfig.isLinucbFunctionBalance()
                + ", rlRegenerationEnabled=" + rlConfig.isRegenerationEnabled()
                + ", rlRegenerationMaxRounds=" + rlConfig.getRegenerationMaxRounds()
                + ", rlReferenceEnabled=" + rlConfig.isReferenceRegenerationEnabled()
                + ", rlReferenceMaxRefs=" + rlConfig.getReferenceRegenerationMaxReferences()
                + ", rlHistoryEnabled=" + rlConfig.isHistoryEnabled()
                + ", rlHistoryReferenceEnabled=" + rlConfig.isHistoryReferenceEnabled()
                + ", rlHistoryReferenceMaxRefs=" + rlConfig.getHistoryReferenceMaxReferences()
                + ", semanticPrecheckHardReject=" + GeneratorFeatureFlags.semanticPrecheckHardReject());
    }

    private static String resolveExcelPath(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            return args[0].trim();
        }
        String prop = System.getProperty("llm.excel.path");
        if (prop != null && !prop.trim().isEmpty()) {
            return prop.trim();
        }
        return DIRECT_EXCEL_FILE;
    }

    private static List<Request> buildRequestsFromExcel(List<List<Object>> excelData,
                                                        LlmRuntimeConfig runtimeConfig,
                                                        int rowStart,
                                                        int rowEnd,
                                                        Set<Integer> selectedRows) throws Exception {
        List<Request> requests = new ArrayList<Request>();
        // for (int i = 1578; i < excelData.size(); i++) {
        int start = Math.max(1, rowStart);
        int end = rowEnd <= 0 ? excelData.size() - 1 : Math.min(rowEnd, excelData.size() - 1);
        for (int i = start; i <= end; i++) {
            if (selectedRows != null && !selectedRows.isEmpty() && !selectedRows.contains(i)) {
                continue;
            }
            List<Object> row = excelData.get(i);
            String project = valueAt(row, 7);
            if (!isSupportedProject(project)) {
                continue;
            }

            String operator = valueAt(row, 0);
            String method = valueAt(row, 2);
            String classF = valueAt(row, 4);
            String packageName = valueAt(row, 6);
            String rawFilePath = valueAt(row, 8);
            String originalGraphPath = valueAt(row, 9);
            String mutantGraphPath = valueAt(row, 10);

            String originJavaPath = new File(originalGraphPath).getParent().replace("\\", "/").replace("//?/", "");
            String outputJsonPath = new File(mutantGraphPath).getParent().replace("\\", "/").replace("//?/", "");
            String mutantJavaPath = new File(mutantGraphPath).getParent().replace("\\", "/").replace("//?/", "");
            String filepath = new File(rawFilePath).getParent().replace("\\", "/").replace("//?/", "");
            String workDir = getCurr(filepath);

            String resultModuleHome = Paths.get(workDir).normalize().toString();
            Path resultPath = Paths.get(resultModuleHome).normalize();
            Path outerRoot;
            String pathStr = resultPath.toString().replace('\\', '/');
            if (pathStr.matches(".*(?:commons-math|commons-numbers).*")) {
                outerRoot = resultPath.getParent();
            } else {
                outerRoot = resultPath.getFileName();
            }
            String sourceRootHome = resolveSourceRootHome(resultModuleHome, outerRoot.getFileName().toString());
            String sourceModuleHome = resolveSourceModuleHome(resultModuleHome, sourceRootHome);

            Request request = new Request();
            request.taskId = "row-" + i;
            request.excelRowIndex = i;
            request.projectName = project;
            request.runId = EXECUTION_RUN_ID;
            request.outerLoopRound = Integer.getInteger("rl.outer.loop.round", 0);
            request.sourceModuleHome = sourceModuleHome;
            request.resultModuleHome = resultModuleHome;
            request.targetClassName = packageName;
            request.methodSignature = method;
            request.mutantName = operator;
            request.outputJsonPath = normalizePath(outputJsonPath + "/graph/output.json");
            request.originJavaPath = normalizePath(originJavaPath + "/../original/" + classF + ".java");
            request.mutatedJavaPath = normalizePath(mutantJavaPath + "/" + classF + ".java");
            request.timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
            request.maxRepairRounds = runtimeConfig == null
                    ? DEFAULT_MAX_REPAIR_ROUNDS
                    : runtimeConfig.getMaxRepairRounds();

            System.out.println("[LLM-TASK-BUILD] " + request.taskId + ": " + mutantGraphPath);
            requests.add(request);
        }

        attachBatchStatistics(requests);
        return requests;
    }

    private static void attachBatchStatistics(List<Request> requests) {
        Map<String, Integer> functionCounts = new HashMap<String, Integer>();
        Map<String, Integer> siteCounts = new HashMap<String, Integer>();
        if (requests == null) {
            return;
        }
        for (Request request : requests) {
            String functionKey = functionKey(request);
            String siteKey = siteKey(request);
            functionCounts.put(functionKey, functionCounts.containsKey(functionKey) ? functionCounts.get(functionKey) + 1 : 1);
            siteCounts.put(siteKey, siteCounts.containsKey(siteKey) ? siteCounts.get(siteKey) + 1 : 1);
        }
        Map<String, Integer> functionSeen = new HashMap<String, Integer>();
        Map<String, Integer> siteSeen = new HashMap<String, Integer>();
        for (Request request : requests) {
            String functionKey = functionKey(request);
            String siteKey = siteKey(request);
            int functionProcessed = functionSeen.containsKey(functionKey) ? functionSeen.get(functionKey) : 0;
            int siteProcessed = siteSeen.containsKey(siteKey) ? siteSeen.get(siteKey) : 0;
            request.functionMutantCount = functionCounts.containsKey(functionKey) ? functionCounts.get(functionKey) : 1;
            request.siteMutantCount = siteCounts.containsKey(siteKey) ? siteCounts.get(siteKey) : 1;
            request.functionProcessedCount = functionProcessed;
            request.siteProcessedCount = siteProcessed;
            functionSeen.put(functionKey, functionProcessed + 1);
            siteSeen.put(siteKey, siteProcessed + 1);
        }
    }

    private static String functionKey(Request request) {
        if (request == null) {
            return "";
        }
        return firstNonBlank(request.projectName, "UNKNOWN_PROJECT")
                + "::" + firstNonBlank(request.targetClassName, "UNKNOWN_CLASS")
                + "::" + firstNonBlank(request.methodSignature, "UNKNOWN_METHOD");
    }

    private static String siteKey(Request request) {
        return functionKey(request) + "::" + operatorFamily(request == null ? "" : request.mutantName);
    }

    private static boolean isSupportedProject(String project) {
        return SUPPORTED_PROJECTS.contains(project);
    }

    private static final class BatchArgs {
        final String excelPath;
        final int rowStart;
        final int rowEnd;
        final Set<Integer> rows;

        private BatchArgs(String excelPath, int rowStart, int rowEnd, Set<Integer> rows) {
            this.excelPath = excelPath;
            this.rowStart = rowStart;
            this.rowEnd = rowEnd;
            this.rows = rows == null ? Collections.<Integer>emptySet() : rows;
        }

        static BatchArgs parse(String[] args) {
            String excel = "";
            int rowStart = 1;
            int rowEnd = -1;
            Set<Integer> rows = new LinkedHashSet<Integer>();
            for (int i = 0; args != null && i < args.length; i++) {
                String key = args[i];
                String value = i + 1 < args.length ? args[i + 1] : null;
                if ("--excel".equalsIgnoreCase(key) && value != null) {
                    excel = value.trim();
                    i++;
                } else if ("--row".equalsIgnoreCase(key) && value != null) {
                    rowStart = parseInt(value, rowStart);
                    rowEnd = rowStart;
                    rows.clear();
                    rows.add(rowStart);
                    i++;
                } else if ("--rows".equalsIgnoreCase(key) && value != null) {
                    rows.addAll(parseRows(value));
                    i++;
                } else if ("--rows-file".equalsIgnoreCase(key) && value != null) {
                    rows.addAll(parseRowsFile(value));
                    i++;
                } else if ("--rowStart".equalsIgnoreCase(key) && value != null) {
                    rowStart = parseInt(value, rowStart);
                    i++;
                } else if ("--rowEnd".equalsIgnoreCase(key) && value != null) {
                    rowEnd = parseInt(value, rowEnd);
                    i++;
                } else if (!key.startsWith("--") && excel.isEmpty()) {
                    excel = key.trim();
                }
            }
            if (excel.isEmpty()) {
                excel = resolveExcelPath(args);
            }
            if (!rows.isEmpty()) {
                rowStart = Collections.min(rows);
                rowEnd = Collections.max(rows);
            }
            return new BatchArgs(excel, rowStart, rowEnd, rows);
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value.trim());
            } catch (Exception e) {
                return fallback;
            }
        }

        private static Set<Integer> parseRows(String value) {
            Set<Integer> rows = new LinkedHashSet<Integer>();
            if (value == null) {
                return rows;
            }
            String[] parts = value.split(",");
            for (String part : parts) {
                int row = parseInt(part, -1);
                if (row > 0) {
                    rows.add(row);
                }
            }
            return rows;
        }

        private static Set<Integer> parseRowsFile(String value) {
            Set<Integer> rows = new LinkedHashSet<Integer>();
            if (value == null || value.trim().isEmpty()) {
                return rows;
            }
            Path path = Paths.get(value.trim()).toAbsolutePath().normalize();
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line == null ? "" : line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    rows.addAll(parseRows(trimmed));
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read --rows-file: " + path, e);
            }
            return rows;
        }
    }

    private static String describeRows(Set<Integer> rows) {
        if (rows == null || rows.isEmpty()) {
            return "<range>";
        }
        return rows.toString();
    }

    private static String valueAt(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }

    private static void runRequestsConcurrently(List<Request> requests,
                                                final ModelConfig modelConfig,
                                                final LlmRuntimeConfig runtimeConfig,
                                                final RLPipelineConfig rlConfig) throws Exception {
        if (requests == null || requests.isEmpty()) {
            System.out.println("[LLM-TASK] no request to run.");
            return;
        }

        final ExperienceLogger experienceLogger = rlConfig.isEnabled()
                ? new ExperienceLogger(rlConfig.getLogPath())
                : null;
        final TransitionLogger transitionLogger = rlConfig.isEnabled()
                ? new TransitionLogger(rlConfig.getTransitionLogPath())
                : null;
        final EpisodeLogger episodeLogger = rlConfig.isEnabled()
                ? new EpisodeLogger(rlConfig.getEpisodeLogPath())
                : null;
        final FailureFeedbackLogger failureFeedbackLogger = new FailureFeedbackLogger(rlConfig.getFeedbackLogPath());
        final BanditPolicy banditPolicy = rlConfig.isEnabled()
                ? buildPolicy(rlConfig)
                : null;

        if (runtimeConfig.isStagedPipelineEnabled()) {
            runRequestsWithStagedPipeline(
                    requests,
                    modelConfig,
                    runtimeConfig,
                    rlConfig,
                    banditPolicy,
                    experienceLogger,
                    transitionLogger,
                    episodeLogger,
                    failureFeedbackLogger
            );
            return;
        }

        int threads = Math.max(1, runtimeConfig.getThreadCount());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<TaskOutcome> completion = new ExecutorCompletionService<TaskOutcome>(pool);

        int submitted = 0;
        int compiledSucceed = 0;
        List<TaskOutcome> completedOutcomes = Collections.synchronizedList(new ArrayList<TaskOutcome>());
        for (final Request request : requests) {
            request.maxLlmApiAttempts = runtimeConfig.getMaxApiAttempts();
            request.skipIfCompiledExists = runtimeConfig.isSkipExistingCompiledTests()
                    && !runtimeConfig.isForceRegenerate();
            completion.submit(new Callable<TaskOutcome>() {
                @Override
                public TaskOutcome call() {
                    return runOneTask(request, modelConfig, runtimeConfig, rlConfig, banditPolicy, experienceLogger,
                            transitionLogger, episodeLogger, failureFeedbackLogger);
                }
            });
            submitted++;
        }

        try {
            for (int done = 1; done <= submitted; done++) {
                Future<TaskOutcome> future = completion.take();
                TaskOutcome outcome = future.get();
                Result result = outcome.result;
                String status = result.compiled ? "compiled" : "failed";
                compiledSucceed += result.compiled ? 1 : 0;
                completedOutcomes.add(outcome);
                if (result.skippedExistingCompiledTest) {
                    status = "skipped-existing";
                }
                if (rlConfig.isEnabled()) {
                    status = status + ", action=" + outcome.action.name()
                            + ", reward=" + String.format(Locale.ROOT, "%.3f", outcome.reward)
                            + ", targetStatus=" + safe(result.targetStatus);
                }
                System.out.println("[LLM-TASK-DONE] done=" + done + "/" + submitted
                        + " taskId=" + result.taskId
                        + " status=" + status
                        + " test=" + result.testSetName
                        + " " + result.timing.toLogLine());
                if (result.failureReason != null && !result.failureReason.trim().isEmpty()) {
                    System.out.println("[LLM-TASK-FAILURE] taskId=" + result.taskId
                            + " reason=" + result.failureReason);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        List<SuiteLevelUpdate> suiteUpdates = runtimeConfig.isSuiteEvaluationEnabled()
                ? enrichSuiteLevelFields(requests)
                : Collections.<SuiteLevelUpdate>emptyList();
        printFinalBatchSummary(submitted, compiledSucceed, completedOutcomes, suiteUpdates, requests,
                runtimeConfig.isSuiteEvaluationEnabled());
    }

    private static void runRequestsWithStagedPipeline(List<Request> requests,
                                                      final ModelConfig modelConfig,
                                                      final LlmRuntimeConfig runtimeConfig,
                                                      final RLPipelineConfig rlConfig,
                                                      final BanditPolicy banditPolicy,
                                                      final ExperienceLogger experienceLogger,
                                                      final TransitionLogger transitionLogger,
                                                      final EpisodeLogger episodeLogger,
                                                      final FailureFeedbackLogger failureFeedbackLogger) throws Exception {
        int generationThreads = Math.max(1, runtimeConfig.getGenerationThreadCount());
        int postThreads = Math.max(1, runtimeConfig.getPostProcessThreadCount());
        ExecutorService generationPool = Executors.newFixedThreadPool(generationThreads);
        ExecutorService postPool = Executors.newFixedThreadPool(postThreads);
        CompletionService<PreparedTask> generationCompletion =
                new ExecutorCompletionService<PreparedTask>(generationPool);
        CompletionService<TaskOutcome> postCompletion =
                new ExecutorCompletionService<TaskOutcome>(postPool);

        int submitted = 0;
        int compiledSucceed = 0;
        int generatedDone = 0;
        int finished = 0;
        List<TaskOutcome> completedOutcomes = Collections.synchronizedList(new ArrayList<TaskOutcome>());
        for (final Request request : requests) {
            request.maxLlmApiAttempts = runtimeConfig.getMaxApiAttempts();
            request.skipIfCompiledExists = runtimeConfig.isSkipExistingCompiledTests()
                    && !runtimeConfig.isForceRegenerate();
            generationCompletion.submit(new Callable<PreparedTask>() {
                @Override
                public PreparedTask call() {
                    return prepareTask(request, modelConfig, runtimeConfig, rlConfig, banditPolicy);
                }
            });
            submitted++;
        }

        try {
            while (finished < submitted) {
                if (generatedDone < submitted) {
                    Future<PreparedTask> generated = generationCompletion.poll(200, TimeUnit.MILLISECONDS);
                    if (generated != null) {
                        final PreparedTask prepared = generated.get();
                        postCompletion.submit(new Callable<TaskOutcome>() {
                            @Override
                            public TaskOutcome call() {
                                return finishPreparedTask(
                                        prepared,
                                        modelConfig,
                                        runtimeConfig,
                                        rlConfig,
                                        banditPolicy,
                                        experienceLogger,
                                        transitionLogger,
                                        episodeLogger,
                                        failureFeedbackLogger
                                );
                            }
                        });
                        generatedDone++;
                    }
                }

                Future<TaskOutcome> completed = postCompletion.poll(200, TimeUnit.MILLISECONDS);
                if (completed == null) {
                    continue;
                }
                TaskOutcome outcome = completed.get();
                finished++;
                compiledSucceed += outcome.result.compiled ? 1 : 0;
                completedOutcomes.add(outcome);
                logTaskOutcome(finished, submitted, outcome, rlConfig);
            }
        } finally {
            generationPool.shutdownNow();
            postPool.shutdownNow();
        }

        List<SuiteLevelUpdate> suiteUpdates = runtimeConfig.isSuiteEvaluationEnabled()
                ? enrichSuiteLevelFields(requests)
                : Collections.<SuiteLevelUpdate>emptyList();
        printFinalBatchSummary(submitted, compiledSucceed, completedOutcomes, suiteUpdates, requests,
                runtimeConfig.isSuiteEvaluationEnabled());
    }

    private static void logTaskOutcome(int done,
                                       int submitted,
                                       TaskOutcome outcome,
                                       RLPipelineConfig rlConfig) {
        Result result = outcome.result;
        String status = result.compiled ? "compiled" : "failed";
        if (result.skippedExistingCompiledTest) {
            status = "skipped-existing";
        }
        if (rlConfig.isEnabled()) {
            status = status + ", action=" + outcome.action.name()
                    + ", reward=" + String.format(Locale.ROOT, "%.3f", outcome.reward)
                    + ", targetStatus=" + safe(result.targetStatus);
        }
        System.out.println("[LLM-TASK-DONE] done=" + done + "/" + submitted
                + " taskId=" + result.taskId
                + " status=" + status
                + " test=" + result.testSetName
                + " " + result.timing.toLogLine());
        if (result.failureReason != null && !result.failureReason.trim().isEmpty()) {
            System.out.println("[LLM-TASK-FAILURE] taskId=" + result.taskId
                    + " reason=" + result.failureReason);
        }
    }

    private static void printFinalBatchSummary(int submitted,
                                               int compiledSucceed,
                                               List<TaskOutcome> completedOutcomes,
                                               List<SuiteLevelUpdate> suiteUpdates,
                                               List<Request> requests,
                                               boolean suiteEvaluationEnabled) throws Exception {
        String compiledRateStr = String.format(Locale.ROOT, "%.2f%%",
                submitted == 0 ? 0.0 : compiledSucceed * 100.0 / submitted);
        int targetExecutedCount = 0;
        int targetKilledCount = 0;
        for (TaskOutcome outcome : completedOutcomes) {
            if (outcome == null || outcome.result == null) {
                continue;
            }
            Result result = outcome.result;
            if (isExecutedTargetStatus(result.targetStatus)) {
                targetExecutedCount++;
                if (result.killed) {
                    targetKilledCount++;
                }
            }
        }
        String targetKillRateStr = String.format(Locale.ROOT, "%.2f%%",
                targetExecutedCount == 0 ? 0.0 : targetKilledCount * 100.0 / targetExecutedCount);
        if (!suiteEvaluationEnabled) {
            System.out.println("Compiled Succeed: " + compiledSucceed + "/" + requests.size() + "(" + compiledRateStr + ")"
                    + ", Target Mutation Score: " + targetKilledCount + "/" + targetExecutedCount + "(" + targetKillRateStr + ")");
            return;
        }
        int suiteKilledCount = 0;
        int suiteLiveCount = 0;
        for (SuiteLevelUpdate update : suiteUpdates) {
            if (update == null) {
                continue;
            }
            if (update.suiteKilledByAnyTest) {
                suiteKilledCount++;
            } else if ("LIVE".equalsIgnoreCase(safe(update.suiteKillStatus))) {
                suiteLiveCount++;
            }
        }
        String suiteKillRateStr = String.format(Locale.ROOT, "%.2f%%",
                (suiteKilledCount + suiteLiveCount) == 0 ? 0.0
                        : suiteKilledCount * 100.0 / (suiteKilledCount + suiteLiveCount));
        writeExecutionReports(requests, completedOutcomes, suiteUpdates);
        System.out.println("Compiled Succeed: " + compiledSucceed + "/" + requests.size() + "(" + compiledRateStr + ")"
                + ", Target Mutation Score: " + targetKilledCount + "/" + targetExecutedCount + "(" + targetKillRateStr + ")"
                + ", Suite Mutation Score: " + suiteKilledCount + "/" + (suiteKilledCount + suiteLiveCount) + "(" + suiteKillRateStr + ")");
    }

    private static BanditPolicy buildPolicy(RLPipelineConfig rlConfig) {
        if ("linucb".equalsIgnoreCase(rlConfig.getPolicy())) {
            return new LinUCBPolicy(
                    rlConfig.getLinucbModelPath(),
                    rlConfig.getLinucbAlpha(),
                    rlConfig.getLinucbLambda(),
                    rlConfig.isLinucbFunctionBalance(),
                    rlConfig.getLinucbOperatorEncoding());
        }
        if (!"epsilon_greedy".equalsIgnoreCase(rlConfig.getPolicy())) {
            throw new IllegalArgumentException("Unsupported RL policy: " + rlConfig.getPolicy());
        }
        return new EpsilonGreedyBanditPolicy(
                rlConfig.getEpsilon(),
                new PolicyStore(rlConfig.getPolicyStorePath()),
                rlConfig.getWarmupAction(),
                rlConfig.getBucketMinSamplesExact(),
                rlConfig.getBucketMinSamplesCoarse());
    }

    private static TaskOutcome runOneTask(Request request,
                                          ModelConfig modelConfig,
                                          LlmRuntimeConfig runtimeConfig,
                                          RLPipelineConfig rlConfig,
                                          BanditPolicy banditPolicy,
                                          ExperienceLogger experienceLogger,
                                          TransitionLogger transitionLogger,
                                          EpisodeLogger episodeLogger,
                                          FailureFeedbackLogger failureFeedbackLogger) {
        PreparedTask prepared = prepareTask(request, modelConfig, runtimeConfig, rlConfig, banditPolicy);
        return finishPreparedTask(prepared, modelConfig, runtimeConfig, rlConfig, banditPolicy, experienceLogger,
                transitionLogger, episodeLogger, failureFeedbackLogger);
    }

    private static PreparedTask prepareTask(Request request,
                                            ModelConfig modelConfig,
                                            LlmRuntimeConfig runtimeConfig,
                                            RLPipelineConfig rlConfig,
                                            BanditPolicy banditPolicy) {
        EvidenceAction action = EvidenceAction.BASELINE;
        EvidenceState state = null;
        try {
            JSONObject fullJson = new JSONObject(FileTextUtils.readUtf8(Paths.get(request.outputJsonPath)));
            request.evidenceHash = sha256Hex(fullJson.toString());
            PromptEvidence baselineEvidence = PromptEvidence.fromFullOutput(fullJson);
            PromptEvidence selectedEvidence = baselineEvidence;
            HistoricalExperienceSnapshot history = new HistoricalExperienceSnapshot();
            List<SuccessTestReference> historyReferences = Collections.<SuccessTestReference>emptyList();
            if (rlConfig.isEnabled()) {
                state = StateBuilder.build(
                        request,
                        fullJson,
                        baselineEvidence,
                        null,
                        rlConfig.getLinucbOperatorEncoding());
                if (rlConfig.isHistoryEnabled()) {
                    int maxHistoryReferences = rlConfig.isHistoryReferenceEnabled()
                            ? Math.max(0, rlConfig.getHistoryReferenceMaxReferences())
                            : 0;
                    history = SuccessTestRetriever.inspect(
                            state,
                            rlConfig.getLogPath(),
                            request.resultModuleHome,
                            maxHistoryReferences);
                    history.applyTo(state);
                    historyReferences = new ArrayList<SuccessTestReference>(history.getReferences());
                    logHistoricalExperience(request, state, history);
                }
                action = banditPolicy.select(state);
                selectedEvidence = RLEvidenceBuilder.build(fullJson, action);
            }
            GenerationOptions initialOptions = GenerationOptions.initial(historyReferences);
            GenerationSeed generationSeed = prepareInitialGeneration(
                    request,
                    modelConfig,
                    runtimeConfig,
                    selectedEvidence,
                    initialOptions
            );
            return PreparedTask.ready(request, state, action, selectedEvidence, generationSeed, fullJson);
        } catch (Exception e) {
            Result result = new Result();
            result.taskId = request == null ? "" : request.taskId;
            result.targetClassName = request == null ? "" : request.targetClassName;
            result.methodSignature = request == null ? "" : request.methodSignature;
            result.mutantName = request == null ? "" : request.mutantName;
            result.compiled = false;
            result.compileRounds = 0;
            result.targetStatus = classifyGenerationExceptionStatus(e);
            result.failureReason = generationFailurePrefix(result.targetStatus) + oneLine(exceptionMessageChain(e));
            return PreparedTask.failed(request, state, action, result);
        }
    }

    private static TaskOutcome finishPreparedTask(PreparedTask prepared,
                                                  ModelConfig modelConfig,
                                                  LlmRuntimeConfig runtimeConfig,
                                                  RLPipelineConfig rlConfig,
                                                  BanditPolicy banditPolicy,
                                                  ExperienceLogger experienceLogger,
                                                  TransitionLogger transitionLogger,
                                                  EpisodeLogger episodeLogger,
                                                  FailureFeedbackLogger failureFeedbackLogger) {
        EvidenceAction action = prepared == null ? EvidenceAction.BASELINE : prepared.action;
        EvidenceState state = prepared == null ? null : prepared.state;
        Request request = prepared == null ? null : prepared.request;
        double reward = 0.0d;
        try {
            Result result;
            if (prepared != null && prepared.preparationFailure != null) {
                result = prepared.preparationFailure;
            } else {
                result = completeGenerationFromSeed(prepared.generationSeed, request, modelConfig, runtimeConfig,
                        prepared.selectedEvidence, prepared.generationSeed == null ? GenerationOptions.initial() : prepared.generationSeed.options);
            }
            if (rlConfig.isEnabled()) {
                result.rlInitialEvidenceAction = action == null ? "" : action.name();
                result.rlRegenPolicyName = "HEURISTIC_REGEN_POLICY";
                executeSingleTarget(request, result);
                markEquivalentSuspectedIfNeeded(result);
                RewardBreakdown initialReward = buildRewardBreakdown(result);
                annotateInitialRewardState(result, initialReward, "INITIAL_EVIDENCE_POLICY");
                if (banditPolicy != null && state != null && shouldUpdatePolicy(result)) {
                    banditPolicy.update(state, action, initialReward.totalReward);
                }
                appendTransition(
                        transitionLogger,
                        request,
                        state,
                        action,
                        RLAction.INITIAL_GENERATION,
                        null,
                        result,
                        0,
                        !shouldRegenerateForKill(rlConfig, result));
                result = maybeRegenerateForKill(request,
                        modelConfig,
                        runtimeConfig,
                        rlConfig,
                        state,
                        action,
                        prepared == null ? null : prepared.selectedEvidence,
                        result,
                        transitionLogger);
                if (state == null) {
                    state = new EvidenceState();
                    state.mutantId = StateBuilder.buildMutantId(request);
                }
                appendEpisodeSummary(episodeLogger, request, state, result);
                reward = buildAndRecordReward(state, action, result, null, experienceLogger);
            }
            annotateFailureAnalysis(request, state, action, result, prepared, failureFeedbackLogger);
            persistResultArtifacts(result);
            return new TaskOutcome(result, state, action, reward);
        } catch (Exception e) {
            Result result = new Result();
            result.taskId = request == null ? "" : request.taskId;
            result.targetClassName = request == null ? "" : request.targetClassName;
            result.methodSignature = request == null ? "" : request.methodSignature;
            result.mutantName = request == null ? "" : request.mutantName;
            result.compiled = false;
            result.compileRounds = 0;
            result.targetStatus = classifyGenerationExceptionStatus(e);
            result.failureReason = generationFailurePrefix(result.targetStatus) + oneLine(exceptionMessageChain(e));
            if (rlConfig.isEnabled()) {
                if (state == null) {
                    state = new EvidenceState();
                    state.mutantId = StateBuilder.buildMutantId(request);
                }
                result.rlBestAttempt = 0;
                result.rlLastAttempt = 0;
                result.rlTerminationReason = Result.STATUS_API_FAILED.equalsIgnoreCase(result.targetStatus)
                        ? "API_FAILED" : (Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(result.targetStatus)
                        ? "GENERATION_FAILED" : "TASK_CRASHED");
                result.rlInitialEvidenceAction = action == null ? "" : action.name();
                result.rlRegenPolicyName = "HEURISTIC_REGEN_POLICY";
                appendTransition(
                        transitionLogger,
                        request,
                        state,
                        action,
                        RLAction.INITIAL_GENERATION,
                        null,
                        result,
                        0,
                        true);
                appendEpisodeSummary(episodeLogger, request, state, result);
                RewardBreakdown crashReward = buildRewardBreakdown(result);
                annotateInitialRewardState(result, crashReward, "INITIAL_EVIDENCE_POLICY");
                reward = buildAndRecordReward(state, action, result, banditPolicy, experienceLogger);
            }
            annotateFailureAnalysis(request, state, action, result, prepared, failureFeedbackLogger);
            persistResultArtifacts(result);
            return new TaskOutcome(result, state, action, reward);
        }
    }

    private static void annotateFailureAnalysis(Request request,
                                                EvidenceState state,
                                                EvidenceAction action,
                                                Result result,
                                                PreparedTask prepared,
                                                FailureFeedbackLogger failureFeedbackLogger) {
        EvidenceState effectiveState = state;
        if (effectiveState == null) {
            effectiveState = new EvidenceState();
            effectiveState.mutantId = StateBuilder.buildMutantId(request);
            effectiveState.project = request == null ? "" : safe(request.sourceModuleHome);
            effectiveState.className = request == null ? "" : safe(request.targetClassName);
            effectiveState.method = request == null ? "" : safe(request.methodSignature);
            effectiveState.operator = request == null ? "" : safe(request.mutantName);
        }
        JSONObject fullJson = prepared == null ? null : prepared.fullJson;
        PromptEvidence evidence = prepared == null ? null : prepared.selectedEvidence;
        FailureAnalysis analysis = FailureAnalyzer.analyze(request, effectiveState, action, result, fullJson, evidence);
        result.failureSymptom = safe(analysis.symptom);
        result.failureStage = analysis.failureStage == null ? "" : analysis.failureStage.name();
        result.rootCauseType = analysis.rootCauseType == null ? "" : analysis.rootCauseType.name();
        result.primaryFixTarget = safe(analysis.primaryFixTarget);
        result.secondaryFixTarget = safe(analysis.secondaryFixTarget);
        result.suggestedAction = safe(analysis.suggestedAction);
        result.attributionWhy = safe(analysis.why);
        result.failureConfidence = analysis.confidence;
        if (failureFeedbackLogger != null) {
            failureFeedbackLogger.append(request, effectiveState, action, result, analysis);
        }
    }

    private static void appendTransition(TransitionLogger logger,
                                         Request request,
                                         EvidenceState state,
                                         EvidenceAction evidenceAction,
                                         RLAction action,
                                         Result before,
                                         Result after,
                                         int step,
                                         boolean done) {
        if (logger == null) {
            return;
        }
        EvidenceState effectiveState = state == null ? new EvidenceState() : state;
        RLStateSnapshot beforeState = RLStateSnapshot.from(effectiveState, before, Math.max(0, step - 1));
        RLStateSnapshot afterState = RLStateSnapshot.from(effectiveState, after, step);
        applyRunMetadata(request, beforeState);
        applyRunMetadata(request, afterState);
        RewardBreakdown reward = TransitionRewardCalculator.calculate(beforeState, afterState);
        Transition transition = new Transition();
        transition.episodeId = firstNonBlank(afterState.episodeInstanceId, beforeState.episodeInstanceId,
                afterState.episodeId, beforeState.episodeId, effectiveState.mutantId);
        transition.step = step;
        transition.stateBefore = beforeState;
        transition.action = action == null ? RLAction.TERMINATE : action;
        transition.evidenceAction = evidenceAction;
        transition.reward = reward.totalReward;
        transition.rewardBreakdown = reward;
        transition.stateAfter = afterState;
        transition.done = done;
        transition.terminationReason = done ? terminationReason(after, false) : "";
        logger.append(transition);
    }

    private static void appendEpisodeSummary(EpisodeLogger logger,
                                             Request request,
                                             EvidenceState state,
                                             Result result) {
        if (logger == null) {
            return;
        }
        String canonicalMutantId = firstNonBlank(
                state == null ? "" : state.mutantId,
                result == null ? "" : result.mutantName);
        String episodeId = buildEpisodeInstanceId(request, canonicalMutantId);
        int bestAttempt = result == null ? 0 : result.rlBestAttempt;
        int lastAttempt = result == null ? 0 : result.rlLastAttempt;
        String termination = result == null ? "TASK_CRASHED" : firstNonBlank(result.rlTerminationReason,
                terminationReason(result, false));
        logger.append(EpisodeSummary.fromSelected(episodeId, canonicalMutantId, result, bestAttempt, lastAttempt, termination));
    }

    private static void annotateEpisodeAttempts(Result result,
                                                int bestAttempt,
                                                int lastAttempt,
                                                String terminationReason) {
        annotateEpisodeAttempts(result, result, bestAttempt, lastAttempt, terminationReason);
    }

    private static void annotateEpisodeAttempts(Result result,
                                                Result lastResult,
                                                int bestAttempt,
                                                int lastAttempt,
                                                String terminationReason) {
        if (result == null) {
            return;
        }
        result.rlBestAttempt = Math.max(0, bestAttempt);
        result.rlLastAttempt = Math.max(0, lastAttempt);
        result.rlTerminationReason = safe(terminationReason);
        Result last = lastResult == null ? result : lastResult;
        result.rlLastCompiled = last.compiled;
        result.rlLastOriginalPassed = last.originalPassed;
        result.rlLastKilled = last.killed;
        result.rlLastTimedOut = last.timedOut;
        result.rlLastTargetStatus = safe(last.targetStatus);
        result.rlLastFailureReason = safe(last.failureReason);
        result.rlLastPromptChars = last.promptChars;
        result.rlLastLlmCalls = last.timing == null ? 0 : last.timing.llmCalls;
        result.rlLastCompileCalls = last.timing == null ? 0 : last.timing.compileCalls;
        result.rlLastRepairRounds = last.timing == null ? 0 : last.timing.repairRounds;
        result.rlLastElapsedMillis = last.timing == null ? 0L : last.timing.totalMillis;
        result.rlLastGenerationStrategy = safe(last.generationStrategy);
    }

    private static String terminationReason(Result result, boolean rollbackAccepted) {
        if (rollbackAccepted) {
            return "ROLLBACK_ACCEPTED";
        }
        if (result == null) {
            return "TASK_CRASHED";
        }
        if (result.killed || Result.STATUS_KILLED.equalsIgnoreCase(safe(result.targetStatus))) {
            return "TARGET_KILLED";
        }
        if (result.equivalenceSuspicion
                || Result.STATUS_EQUIVALENT_SUSPECTED.equalsIgnoreCase(safe(result.targetStatus))) {
            return "EQUIVALENT_SUSPECTED";
        }
        if (result.timedOut || Result.STATUS_TIMEOUT.equalsIgnoreCase(safe(result.targetStatus))) {
            return "TIME_BUDGET_EXCEEDED";
        }
        if (Result.STATUS_API_FAILED.equalsIgnoreCase(safe(result.targetStatus))) {
            return "API_FAILED";
        }
        if (Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(safe(result.targetStatus))) {
            return "GENERATION_FAILED";
        }
        if (Result.STATUS_TASK_CRASHED.equalsIgnoreCase(safe(result.targetStatus))) {
            return "TASK_CRASHED";
        }
        if (!result.compiled) {
            return "COMPILATION_UNRECOVERABLE";
        }
        if (Result.STATUS_ORIGINAL_FAILED.equalsIgnoreCase(safe(result.targetStatus)) || !result.originalPassed) {
            return "ORIGINAL_EXECUTION_FAILED";
        }
        return "MAX_RL_ROUNDS";
    }

    private static RLAction toRlAction(RegenerationStrategy strategy) {
        if (strategy == null) {
            return RLAction.INPUT_STRENGTHEN;
        }
        switch (strategy) {
            case ASSERTION_STRENGTHEN:
                return RLAction.ASSERTION_STRENGTHEN;
            case OBSERVABLE_SWITCH:
                return RLAction.OBSERVABLE_SWITCH;
            case EXCEPTION_ORACLE:
                return RLAction.EXCEPTION_ORACLE;
            case REFERENCE_GUIDED_TARGET_KILL:
                return RLAction.REFERENCE_GUIDED_KILL;
            case INPUT_STRENGTHEN:
            default:
                return RLAction.INPUT_STRENGTHEN;
        }
    }

    private static void applyRunMetadata(Request request, RLStateSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String canonicalMutantId = firstNonBlank(snapshot.canonicalMutantId, snapshot.mutantId);
        snapshot.canonicalMutantId = canonicalMutantId;
        snapshot.episodeId = canonicalMutantId;
        snapshot.runId = firstNonBlank(request == null ? "" : request.runId, EXECUTION_RUN_ID);
        snapshot.outerLoopRound = request == null ? 0 : request.outerLoopRound;
        snapshot.evidenceHash = request == null ? "" : safe(request.evidenceHash);
        snapshot.episodeInstanceId = buildEpisodeInstanceId(request, canonicalMutantId);
    }

    private static String buildEpisodeInstanceId(Request request, String canonicalMutantId) {
        String runId = firstNonBlank(request == null ? "" : request.runId, EXECUTION_RUN_ID);
        int outerLoopRound = request == null ? 0 : request.outerLoopRound;
        return runId + "::" + outerLoopRound + "::" + firstNonBlank(canonicalMutantId, "UNKNOWN_MUTANT");
    }

    private static double buildAndRecordReward(EvidenceState state,
                                               EvidenceAction action,
                                               Result result,
                                               BanditPolicy banditPolicy,
                                               ExperienceLogger experienceLogger) {
        RewardBreakdown rewardBreakdown = buildRewardBreakdown(result);
        double reward = rewardBreakdown.totalReward;
        if (result != null) {
            result.rlFinalReward = reward;
        }
        if (experienceLogger != null) {
            experienceLogger.append(state, action, result, rewardBreakdown);
        }
        if (banditPolicy != null && state != null && shouldUpdatePolicy(result)) {
            banditPolicy.update(state, action, reward);
        }
        return reward;
    }

    private static void annotateInitialRewardState(Result result,
                                                   RewardBreakdown initialReward,
                                                   String policyUpdateSource) {
        if (result == null) {
            return;
        }
        result.rlInitialReward = initialReward == null ? 0.0d : initialReward.totalReward;
        result.rlPolicyUpdateReward = result.rlInitialReward;
        result.rlPolicyUpdateSource = safe(policyUpdateSource);
        result.rlInitialCompiled = result.compiled;
        result.rlInitialOriginalPassed = result.originalPassed;
        result.rlInitialKilled = result.killed;
        result.rlInitialTimedOut = result.timedOut;
        result.rlInitialTargetStatus = safe(result.targetStatus);
        result.rlInitialFailureReason = safe(result.failureReason);
        result.rlInitialPromptChars = result.promptChars;
        result.rlInitialLlmCalls = result.timing == null ? 0 : result.timing.llmCalls;
        result.rlInitialCompileCalls = result.timing == null ? 0 : result.timing.compileCalls;
        result.rlInitialRepairRounds = result.timing == null ? 0 : result.timing.repairRounds;
        result.rlInitialElapsedMillis = result.timing == null ? 0L : result.timing.totalMillis;
        result.rlInitialGenerationStrategy = safe(result.generationStrategy);
    }

    private static String joinStrategyPath(List<String> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String strategy : strategies) {
            if (strategy == null || strategy.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(strategy.trim());
        }
        return sb.toString();
    }

    private static RewardBreakdown buildRewardBreakdown(Result result) {
        if (result != null
                && (Result.STATUS_API_FAILED.equalsIgnoreCase(safe(result.targetStatus))
                || Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(safe(result.targetStatus)))) {
            // Provider/transport and pre-javac generation failures are exogenous to
            // evidence quality. Record the event, but do not turn it into an RL penalty.
            return new RewardBreakdown();
        }
        RewardBuilder.ResultContext ctx = new RewardBuilder.ResultContext();
        if (result != null) {
            ctx.compileSuccess = result.compiled;
            ctx.originalPass = result.originalPassed;
            ctx.killed = result.killed;
            ctx.repairRounds = result.timing == null ? 0 : result.timing.repairRounds;
            ctx.promptChars = result.promptChars;
            ctx.elapsedMillis = result.timing == null ? 0L : result.timing.totalMillis;
            ctx.timeout = result.timedOut;
            ctx.originalFailed = !result.originalPassed
                    && Result.STATUS_ORIGINAL_FAILED.equalsIgnoreCase(safe(result.targetStatus));
        }
        return RewardBuilder.buildBreakdown(ctx);
    }

    private static boolean shouldUpdatePolicy(Result result) {
        if (result == null) {
            return false;
        }
        if (result.skippedExistingCompiledTest) {
            return false;
        }
        if ("deterministic_scaffold".equalsIgnoreCase(safe(result.generationStrategy))) {
            return false;
        }
        return result.llmProducedVisibleCode;
    }

    private static void persistResultArtifacts(Result result) {
        if (result == null) {
            return;
        }
        try {
            if (result.reportDir != null && result.testSetName != null
                    && !result.reportDir.trim().isEmpty() && !result.testSetName.trim().isEmpty()) {
                Path reportRoot = Paths.get(result.reportDir);
                GenerationReportWriter.writeSummary(
                        reportRoot.resolve(fileBaseName(result.testSetName) + "__generation_summary.txt"),
                        result
                );
            }
            if (result.resultJsonlFile != null && !result.resultJsonlFile.trim().isEmpty()) {
                GenerationReportWriter.appendResultJsonl(
                        Paths.get(result.resultJsonlFile),
                        result
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist final generation artifacts for task: "
                    + safe(result.taskId), e);
        }
    }

    private static void executeSingleTarget(Request request, Result result) {
        if (request == null || result == null) {
            return;
        }
        if (!result.compiled) {
            if (safe(result.targetStatus).isEmpty()) {
                result.targetStatus = deriveNonExecutedFinalStatus(result);
            }
            return;
        }
        try {
            TestRunner9_MultiProcess_batched.SingleMutantRunResult single =
                    TestRunner9_MultiProcess_batched.runSingleLlmMutantTest(
                            request.targetClassName,
                            result.testSetName,
                            request.methodSignature,
                            request.mutantName,
                            request.timeoutMillis,
                            request.sourceModuleHome,
                            request.resultModuleHome);
            result.originalPassed = single.originalPassed;
            result.killed = single.killed;
            result.targetStatus = normalizeExecutedFinalStatus(single.status, single.failureReason);
            result.junitTests.clear();
            result.junitTests.addAll(single.junitTests);
            result.originalResults.clear();
            result.originalResults.putAll(single.originalResults);
            result.mutantResults.clear();
            result.mutantResults.putAll(single.mutantResults);
            result.originalLogFile = single.originalLogFile;
            result.mutantLogFile = single.mutantLogFile;
            if (single.failureReason != null && !single.failureReason.trim().isEmpty()) {
                result.failureReason = oneLine(single.failureReason);
            }
            result.timedOut = containsTimeout(result.targetStatus) || containsTimeout(result.failureReason);
        } catch (Throwable t) {
            result.targetStatus = Result.STATUS_TASK_CRASHED;
            result.failureReason = oneLine(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
            result.timedOut = containsTimeout(result.failureReason);
        }
    }

    private static String classifyGenerationExceptionStatus(Throwable error) {
        String chain = exceptionMessageChain(error).toLowerCase(Locale.ROOT);
        if (chain.contains("llm api failed")
                || chain.contains("llm http error")
                || chain.contains("llm returned an empty http response")
                || chain.contains("retry llm api")
                || chain.contains("connection reset")
                || chain.contains("connect timed out")
                || chain.contains("read timed out")
                || chain.contains("http 429")
                || chain.contains("http 500")
                || chain.contains("http 502")
                || chain.contains("http 503")
                || chain.contains("http 504")) {
            return Result.STATUS_API_FAILED;
        }
        return Result.STATUS_GENERATION_FAILED;
    }

    private static String generationFailurePrefix(String status) {
        if (Result.STATUS_API_FAILED.equalsIgnoreCase(status)) {
            return "API failed: ";
        }
        if (Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(status)) {
            return "Generation failed: ";
        }
        return "Task crashed: ";
    }

    private static String exceptionMessageChain(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            if (depth > 0) {
                out.append(" | caused by: ");
            }
            String message = current.getMessage();
            out.append(message == null || message.trim().isEmpty()
                    ? current.getClass().getSimpleName() : message.trim());
            current = current.getCause();
            depth++;
        }
        return out.toString();
    }

    private static boolean containsTimeout(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("timeout") || normalized.contains("timed out");
    }

    private static Result generateAndCompileSafely(Request request,
                                                   ModelConfig modelConfig,
                                                   LlmRuntimeConfig runtimeConfig) {
        try {
            return generateAndCompile(request, modelConfig, runtimeConfig);
        } catch (Exception e) {
            Result result = new Result();
            result.taskId = request == null ? "" : request.taskId;
            result.targetClassName = request == null ? "" : request.targetClassName;
            result.methodSignature = request == null ? "" : request.methodSignature;
            result.mutantName = request == null ? "" : request.mutantName;
            result.compiled = false;
            result.compileRounds = 0;
            result.targetStatus = classifyGenerationExceptionStatus(e);
            result.failureReason = generationFailurePrefix(result.targetStatus) + oneLine(exceptionMessageChain(e));
            return result;
        }
    }

    public static Result generateAndCompile(Request request) throws Exception {
        return generateAndCompile(request, ModelConfigLoader.load(), LlmRuntimeConfigLoader.load());
    }

    public static Result generateAndCompile(Request request,
                                            ModelConfig modelConfig,
                                            LlmRuntimeConfig runtimeConfig) throws Exception {
        JSONObject fullJson = new JSONObject(FileTextUtils.readUtf8(Paths.get(request.outputJsonPath)));
        PromptEvidence evidence = PromptEvidence.fromFullOutput(fullJson);
        return generateAndCompile(request, modelConfig, runtimeConfig, evidence);
    }

    public static Result generateAndCompile(Request request,
                                            ModelConfig modelConfig,
                                            LlmRuntimeConfig runtimeConfig,
                                            PromptEvidence evidence) throws Exception {
        return generateAndCompile(request, modelConfig, runtimeConfig, evidence, GenerationOptions.initial());
    }

    public static Result generateAndCompile(Request request,
                                            ModelConfig modelConfig,
                                            LlmRuntimeConfig runtimeConfig,
                                            PromptEvidence evidence,
                                            GenerationOptions options) throws Exception {
        GenerationSeed seed = prepareInitialGeneration(request, modelConfig, runtimeConfig, evidence, options);
        return completeGenerationFromSeed(seed, request, modelConfig, runtimeConfig, evidence, options);
    }

    private static GenerationSeed prepareInitialGeneration(Request request,
                                                           ModelConfig modelConfig,
                                                           LlmRuntimeConfig runtimeConfig,
                                                           PromptEvidence evidence,
                                                           GenerationOptions options) throws Exception {
        RequestValidator.validate(request);
        if (modelConfig == null) {
            modelConfig = ModelConfigLoader.load();
        }
        if (runtimeConfig == null) {
            runtimeConfig = LlmRuntimeConfigLoader.load();
        }

        Result result = new Result();
        result.taskId = request.taskId;
        result.targetClassName = request.targetClassName;
        result.methodSignature = request.methodSignature;
        result.mutantName = request.mutantName;

        long t0 = System.nanoTime();
        if (evidence == null) {
            JSONObject fullJson = new JSONObject(FileTextUtils.readUtf8(Paths.get(request.outputJsonPath)));
            evidence = PromptEvidence.fromFullOutput(fullJson);
        }
        result.timing.evidenceMillis += elapsedMillis(t0);
        result.equivalenceSuspicion = evidence != null && evidence.hasEquivalenceSuspicion();
        result.equivalenceReason = evidence == null ? "" : safe(evidence.equivalenceSuspicionReason());

        String projectCp = ProjectClasspathCache.getProjectClasspath(request.sourceModuleHome);

        Path llmsRoot = Paths.get(request.resultModuleHome, MutationSystem.TESTSET_MODE_LLMS);
        Path testSrcRoot = llmsRoot.resolve("src");
        Path testClassesRoot = llmsRoot.resolve("classes");
        Path reportRoot = llmsRoot.resolve("report").resolve("logs");
        Path apiCacheRoot = reportRoot.resolve("llm_api_cache");
        Files.createDirectories(testSrcRoot);
        Files.createDirectories(testClassesRoot);
        Files.createDirectories(reportRoot);
        if (!runtimeConfig.isForceRegenerate()) {
            Files.createDirectories(apiCacheRoot);
        }

        result.reportDir = reportRoot.toAbsolutePath().normalize().toString();

        String testSetName = buildGeneratedTestFqn(request.targetClassName, request.mutantName);
        result.testSetName = testSetName;
        Path javaFile = toJavaFile(testSrcRoot, testSetName);
        result.testJavaFile = javaFile.toAbsolutePath().normalize().toString();

        String artifactBaseName = buildArtifactBaseName(testSetName, options);
        Path compactEvidenceFile = reportRoot.resolve(artifactBaseName + "__compact_evidence.json");
        Path promptFile = reportRoot.resolve(artifactBaseName + "__prompt.txt");
        Path resultJsonlFile = reportRoot.resolve("..").resolve("llm_generation_results.jsonl");
        result.resultJsonlFile = resultJsonlFile.toAbsolutePath().normalize().toString();
        if (options != null) {
            result.generationStrategy = safe(options.generationStrategy);
            result.referenceGuided = options.referenceGuided;
            result.referenceCount = options.referenceCount;
            result.regenerationRound = options.regenerationRound;
        }

        if (!runtimeConfig.isForceRegenerate()
                && request.skipIfCompiledExists
                && generatedTestAlreadyCompiled(javaFile, testClassesRoot, testSetName)) {
            result.compiled = true;
            result.compileRounds = 0;
            result.skippedExistingCompiledTest = true;
            result.timing.skippedExistingCompiled = true;
            result.failureReason = "";
            finishTiming(result);
            return GenerationSeed.terminal(result);
        }

        writeText(compactEvidenceFile, evidence.toJson().toString(2));
        System.out.println("[EVIDENCE-EQUIVALENCE] taskId=" + safe(result.taskId)
                + ", mutant=" + safe(result.mutantName)
                + ", suspicion=" + result.equivalenceSuspicion
                + (isBlank(result.equivalenceReason) ? "" : ", reason=" + oneLine(result.equivalenceReason)));

        if (evidence.skipTestGeneration) {
            result.compiled = false;
            result.compileRounds = 0;
            result.targetStatus = Result.STATUS_SKIPPED_BY_EVIDENCE;
            result.failureReason = "Skipped by output.json: " + oneLine(evidence.skipReason);
            finishTiming(result);
            return GenerationSeed.terminal(result);
        }

        String deterministicCode = runtimeConfig.isDeterministicScaffoldEnabled()
                ? DeterministicScaffoldBuilder.maybeBuild(request, testSetName)
                : null;
        if (!isBlank(deterministicCode)) {
            writeText(promptFile, "deterministic scaffold used for "
                    + safe(request.targetClassName) + " :: "
                    + safe(request.methodSignature) + " :: "
                    + safe(request.mutantName) + System.lineSeparator());
            result.promptChars = 0;
            result.llmProducedVisibleCode = true;
            result.generationStrategy = "deterministic_scaffold";

            GenerationSeed seed = new GenerationSeed();
            seed.result = result;
            seed.request = request;
            seed.evidence = evidence;
            seed.options = options;
            seed.projectCp = projectCp;
            seed.testSetName = testSetName;
            seed.javaFile = javaFile;
            seed.testClassesRoot = testClassesRoot;
            seed.reportRoot = reportRoot;
            seed.artifactBaseName = artifactBaseName;
            seed.rounds = 1;
            seed.candidateCode = deterministicCode;
            return seed;
        }

        CachedLlmClient client = new CachedLlmClient(
                new LlmClient(modelConfig),
                apiCacheRoot,
                modelConfig,
                Math.max(1, request.maxLlmApiAttempts)
        );

        t0 = System.nanoTime();
        PromptBudgetProfile initialProfile = PromptBudgetPolicy.resolve(
                runtimeConfig,
                0,
                "",
                null
        );
        String prompt = options != null && options.initialPromptOverride != null
                ? options.initialPromptOverride
                : InitialPromptBuilder.build(request, evidence, testSetName);
        if (options != null && options.initialPromptOverride == null) {
            prompt = HistoricalReferencePromptBuilder.appendForInitial(prompt, options.historicalReferences);
        }
        prompt = EvidenceAwarePromptBudgeter.enforce(
                prompt,
                initialProfile,
                result.taskId,
                options != null && options.promptPhase != null ? options.promptPhase : "initial"
        );
        result.timing.promptMillis += elapsedMillis(t0);
        result.promptChars = prompt.length();
        writeText(promptFile, prompt);

        GenerationSeed seed = new GenerationSeed();
        seed.result = result;
        seed.client = client;
        seed.request = request;
        seed.evidence = evidence;
        seed.options = options;
        seed.projectCp = projectCp;
        seed.testSetName = testSetName;
        seed.javaFile = javaFile;
        seed.testClassesRoot = testClassesRoot;
        seed.reportRoot = reportRoot;
        seed.artifactBaseName = artifactBaseName;
        seed.initialProfile = initialProfile;
        seed.rounds = 1;

        Path responseFile = reportRoot.resolve(artifactBaseName + "__response_0.json");
        LlmCallResult llmResult;
        if (options != null && options.forceFreshInitialCall) {
            llmResult = generateFreshLlmResponse(
                    client,
                    prompt,
                    responseFile,
                    initialProfile.getMaxOutputTokens()
            );
        } else {
            llmResult = generateOrReuseLlmResponse(
                    client,
                    prompt,
                    responseFile,
                    runtimeConfig.isForceRegenerate(),
                    initialProfile.getMaxOutputTokens()
            );
        }
        seed.lastLlmResult = llmResult;
        result.timing.llmMillis += llmResult.elapsedMillis;
        result.timing.llmCalls++;
        result.timing.llmCacheHit = result.timing.llmCacheHit || llmResult.cacheHit;

        String rawResponse = llmResult.response;
        String finishReason = GeneratedCodeExtractor.finishReason(rawResponse);
        if ("length".equalsIgnoreCase(finishReason)) {
            seed.lastCompileError = "LLM output truncated: finish_reason=length. Increase max_tokens or disable thinking.";
            writeText(reportRoot.resolve(artifactBaseName + "__compile_error_0.txt"), seed.lastCompileError);
            return seed;
        }

        String extractedCode = GeneratedCodeExtractor.extractJavaCode(rawResponse);
        if (extractedCode == null || extractedCode.trim().isEmpty()) {
            seed.lastCompileError = "LLM returned empty visible content. The response may contain reasoning_content only.";
            writeText(reportRoot.resolve(artifactBaseName + "__compile_error_0.txt"), seed.lastCompileError);
            return seed;
        }
        result.llmProducedVisibleCode = true;

        seed.candidateCode = GeneratedCodeExtractor.normalizeGeneratedTestCode(extractedCode, testSetName);
        seed.candidateCode = GeneratedCodeEvidenceGuard.normalizeAgainstEvidence(seed.candidateCode, evidence);
        if (!GeneratedCodeExtractor.containsExpectedTypeDeclaration(seed.candidateCode, testSetName)) {
            seed.lastCompileError = "LLM code does not contain expected test class: " + fileBaseName(testSetName);
            writeText(reportRoot.resolve(artifactBaseName + "__compile_error_0.txt"), seed.lastCompileError);
            seed.candidateCode = null;
        }
        return seed;
    }

    private static Result completeGenerationFromSeed(GenerationSeed seed,
                                                     Request request,
                                                     ModelConfig modelConfig,
                                                     LlmRuntimeConfig runtimeConfig,
                                                     PromptEvidence evidence,
                                                     GenerationOptions options) throws Exception {
        if (seed == null) {
            throw new IllegalArgumentException("Generation seed must not be null.");
        }
        if (seed.terminal) {
            return seed.result;
        }
        Result result = seed.result;
        String candidateCode = seed.candidateCode;
        String lastCompileError = seed.lastCompileError == null ? "" : seed.lastCompileError;
        LlmCallResult lastLlmResult = seed.lastLlmResult;
        int rounds = Math.max(1, seed.rounds);

        if (candidateCode != null && !candidateCode.trim().isEmpty()) {
            String semanticRejectReason = SemanticTestRejector.reject(request, evidence, candidateCode);
            if (!semanticRejectReason.isEmpty()) {
                recordSemanticWarning(result, seed.reportRoot, seed.artifactBaseName, 0, semanticRejectReason);
                if (GeneratorFeatureFlags.semanticPrecheckHardReject()) {
                    lastCompileError = semanticRejectReason;
                    writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_0.txt"), lastCompileError);
                }
            }
        }

        if (candidateCode != null && !candidateCode.trim().isEmpty() && lastCompileError.isEmpty()) {
            writeText(seed.javaFile, candidateCode);
            long t0 = System.nanoTime();
            try {
                GeneratedTestCompiler.compile(
                        request.sourceModuleHome,
                        seed.javaFile,
                        seed.testClassesRoot,
                        seed.projectCp
                );
                Path expectedClassFile = seed.testClassesRoot.resolve(
                        seed.testSetName.replace('.', File.separatorChar) + ".class"
                );
                if (!Files.isRegularFile(expectedClassFile)) {
                    throw new IllegalStateException(
                            "javac returned success but expected class file not found: "
                                    + expectedClassFile.toAbsolutePath().normalize()
                    );
                }
                result.timing.compileMillis += elapsedMillis(t0);
                result.timing.compileCalls++;
                result.compiled = true;
                result.failureReason = "";
            } catch (Exception compileFailure) {
                result.timing.compileMillis += elapsedMillis(t0);
                result.timing.compileCalls++;
                lastCompileError = String.valueOf(compileFailure.getMessage());
                writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_0.txt"), lastCompileError);
            }
        }

        for (int attempt = 1; !result.compiled && seed.client != null && attempt <= request.maxRepairRounds; attempt++) {
            rounds++;
            long t0 = System.nanoTime();
            PromptBudgetProfile repairProfile = PromptBudgetPolicy.resolve(
                    runtimeConfig,
                    attempt,
                    lastCompileError,
                    lastLlmResult
            );
            String repairPrompt = RepairPromptBuilder.build(
                    request,
                    evidence,
                    seed.testSetName,
                    candidateCode,
                    lastCompileError
            );
            repairPrompt = HistoricalReferencePromptBuilder.appendForRepair(
                    repairPrompt,
                    options == null ? Collections.<SuccessTestReference>emptyList() : options.historicalReferences);
            repairPrompt = EvidenceAwarePromptBudgeter.enforce(
                    repairPrompt,
                    repairProfile,
                    result.taskId,
                    buildRepairPhaseLabel(options, attempt)
            );
            result.timing.repairPromptMillis += elapsedMillis(t0);
            writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__repair_" + attempt + "_prompt.txt"), repairPrompt);

            Path responseFile = seed.reportRoot.resolve(seed.artifactBaseName + "__response_" + attempt + ".json");
            LlmCallResult llmResult = generateFreshLlmResponse(
                    seed.client,
                    repairPrompt,
                    responseFile,
                    repairProfile.getMaxOutputTokens()
            );
            result.timing.llmMillis += llmResult.elapsedMillis;
            result.timing.llmCalls++;
            result.timing.llmCacheHit = result.timing.llmCacheHit || llmResult.cacheHit;
            lastLlmResult = llmResult;

            String rawResponse = llmResult.response;
            String finishReason = GeneratedCodeExtractor.finishReason(rawResponse);
            if ("length".equalsIgnoreCase(finishReason)) {
                lastCompileError = "LLM output truncated: finish_reason=length. Increase max_tokens or disable thinking.";
                writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_" + attempt + ".txt"), lastCompileError);
                continue;
            }

            String extractedCode = GeneratedCodeExtractor.extractJavaCode(rawResponse);
            if (extractedCode == null || extractedCode.trim().isEmpty()) {
                lastCompileError = "LLM returned empty visible content. The response may contain reasoning_content only.";
                writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_" + attempt + ".txt"), lastCompileError);
                continue;
            }
            result.llmProducedVisibleCode = true;

            candidateCode = GeneratedCodeExtractor.normalizeGeneratedTestCode(extractedCode, seed.testSetName);
            candidateCode = GeneratedCodeEvidenceGuard.normalizeAgainstEvidence(candidateCode, evidence);
            if (!GeneratedCodeExtractor.containsExpectedTypeDeclaration(candidateCode, seed.testSetName)) {
                lastCompileError = "LLM code does not contain expected test class: " + fileBaseName(seed.testSetName);
                writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_" + attempt + ".txt"), lastCompileError);
                continue;
            }

            String semanticRejectReason = SemanticTestRejector.reject(request, evidence, candidateCode);
            if (!semanticRejectReason.isEmpty()) {
                recordSemanticWarning(result, seed.reportRoot, seed.artifactBaseName, attempt, semanticRejectReason);
                if (GeneratorFeatureFlags.semanticPrecheckHardReject()) {
                    lastCompileError = semanticRejectReason;
                    writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_" + attempt + ".txt"), lastCompileError);
                    continue;
                }
            }

            writeText(seed.javaFile, candidateCode);
            t0 = System.nanoTime();
            try {
                GeneratedTestCompiler.compile(
                        request.sourceModuleHome,
                        seed.javaFile,
                        seed.testClassesRoot,
                        seed.projectCp
                );
                Path expectedClassFile = seed.testClassesRoot.resolve(
                        seed.testSetName.replace('.', File.separatorChar) + ".class"
                );
                if (!Files.isRegularFile(expectedClassFile)) {
                    throw new IllegalStateException(
                            "javac returned success but expected class file not found: "
                                    + expectedClassFile.toAbsolutePath().normalize()
                    );
                }
                result.timing.compileMillis += elapsedMillis(t0);
                result.timing.compileCalls++;
                result.compiled = true;
                result.failureReason = "";
            } catch (Exception compileFailure) {
                result.timing.compileMillis += elapsedMillis(t0);
                result.timing.compileCalls++;
                lastCompileError = String.valueOf(compileFailure.getMessage());
                writeText(seed.reportRoot.resolve(seed.artifactBaseName + "__compile_error_" + attempt + ".txt"), lastCompileError);
            }
        }

        result.compileRounds = rounds;
        result.timing.repairRounds = Math.max(0, rounds - 1);
        finishTiming(result);
        if (!result.compiled) {
            result.failureReason = "Compilation failed after repair rounds. Last error: " + oneLine(lastCompileError);
            result.targetStatus = deriveNonExecutedFinalStatus(result);
        }
        return result;
    }

    private static Result maybeRegenerateForKill(Request request,
                                                 ModelConfig modelConfig,
                                                 LlmRuntimeConfig runtimeConfig,
                                                 RLPipelineConfig rlConfig,
                                                 EvidenceState state,
                                                 EvidenceAction action,
                                                 PromptEvidence evidence,
                                                 Result initialResult,
                                                 TransitionLogger transitionLogger) throws Exception {
        if (initialResult != null) {
            initialResult.semanticGenerationCalls = Math.max(1, initialResult.semanticGenerationCalls);
            initialResult.semanticStage = "STRICT_CORE";
        }
        if (!shouldRegenerateForKill(rlConfig, initialResult)) {
            if (initialResult != null) {
                initialResult.semanticPlanExhausted = false;
            }
            annotateEpisodeAttempts(initialResult, 0, 0, terminationReason(initialResult, false));
            return initialResult;
        }

        Result current = initialResult;
        Result best = initialResult;
        Result last = initialResult;
        int bestAttempt = 0;
        int lastAttempt = 0;
        boolean rollbackTriggered = false;
        List<String> regenStrategyPath = new ArrayList<String>();
        BestResultArtifacts bestArtifacts = BestResultArtifacts.capture(initialResult, request, "initial");

        // The normal semantic plan is intentionally capped at two survivor-triggered
        // regenerations: STRICT_CORE -> RELAX_ORACLE -> RELAX_OBSERVABLE_ORACLE.
        int maxSemanticRegenerationRounds = Math.min(2, Math.max(0, rlConfig.getRegenerationMaxRounds()));
        for (int round = 1; round <= maxSemanticRegenerationRounds; round++) {
            if (!shouldRegenerateForKill(rlConfig, current)) {
                break;
            }

            List<SuccessTestReference> refs = rlConfig.isReferenceRegenerationEnabled()
                    ? SuccessTestRetriever.findSameMethodSuccessfulTests(
                    state,
                    rlConfig.getLogPath(),
                    request.resultModuleHome,
                    rlConfig.getReferenceRegenerationMaxReferences())
                    : Collections.<SuccessTestReference>emptyList();

            // Do not let a heuristic skip semantic-relaxation levels. The first
            // survivor pass changes O only; the second may change C+O.
            RegenerationStrategy strategy = round == 1
                    ? RegenerationStrategy.ASSERTION_STRENGTHEN
                    : RegenerationStrategy.OBSERVABLE_SWITCH;
            String semanticStage = round == 1 ? "RELAX_ORACLE" : "RELAX_OBSERVABLE_ORACLE";
            regenStrategyPath.add(semanticStage + ":" + strategy.name());
            RLAction rlAction = toRlAction(strategy);

            System.out.println("[LLM-REGEN-START] taskId=" + safe(current.taskId)
                    + " round=" + round
                    + ", semanticStage=" + semanticStage
                    + ", previousTargetStatus=" + safe(current.targetStatus)
                    + ", previousTest=" + safe(current.testSetName)
                    + ", strategy=" + strategy.name()
                    + ", equivalenceSuspicion=" + current.equivalenceSuspicion
                    + ", referenceCount=" + (refs == null ? 0 : refs.size())
                    + ", previousTiming={" + current.timing.toLogLine() + "}");

            Result regenerated;
            try {
                String previousCode = readPreviousGeneratedCode(current.testJavaFile);
                String prompt = RegenerationPromptBuilder.build(
                        request,
                        evidence,
                        current.testSetName,
                        previousCode,
                        current.targetStatus,
                        current.failureReason,
                        action,
                        round,
                        strategy,
                        refs
                );
                Request regenRequest = cloneRequestForRegeneration(request);
                regenRequest.semanticStage = semanticStage;
                GenerationOptions options = GenerationOptions.regeneration(
                        round,
                        prompt,
                        refs != null ? refs.size() : 0,
                        refs != null && !refs.isEmpty(),
                        strategy.name().toLowerCase(Locale.ROOT),
                        refs
                );
                regenerated = generateAndCompile(
                        regenRequest,
                        modelConfig,
                        runtimeConfig,
                        evidence,
                        options
                );
                regenerated.semanticGenerationCalls = 1 + round;
                regenerated.semanticStage = semanticStage;
                executeSingleTarget(request, regenerated);
                markEquivalentSuspectedIfNeeded(regenerated);
            } catch (Exception regenerationFailure) {
                // A transient API/runtime failure must not destroy a test that already
                // compiled and ran. BestResultArtifacts is restored after the loop.
                lastAttempt = round;
                rollbackTriggered = true;
                System.out.println("[LLM-REGEN-ROLLBACK] taskId=" + safe(current.taskId)
                        + " round=" + round
                        + ", semanticStage=" + semanticStage
                        + ", reason=" + oneLine(String.valueOf(regenerationFailure.getMessage()))
                        + ", action=restore-last-known-good");
                break;
            }

            System.out.println("[LLM-REGEN-DONE] taskId=" + safe(regenerated.taskId)
                    + " round=" + round
                    + ", semanticStage=" + semanticStage
                    + ", strategy=" + strategy.name()
                    + ", status=" + (regenerated.compiled ? "compiled" : "failed")
                    + ", targetStatus=" + safe(regenerated.targetStatus)
                    + ", equivalenceSuspicion=" + regenerated.equivalenceSuspicion
                    + ", test=" + safe(regenerated.testSetName)
                    + ", generationStrategy=" + safe(regenerated.generationStrategy)
                    + ", referenceGuided=" + regenerated.referenceGuided
                    + ", referenceCount=" + regenerated.referenceCount
                    + ", " + regenerated.timing.toLogLine());
            if (!isBlank(regenerated.failureReason)) {
                System.out.println("[LLM-REGEN-FAILURE] taskId=" + safe(regenerated.taskId)
                        + " round=" + round
                        + ", semanticStage=" + semanticStage
                        + ", reason=" + regenerated.failureReason);
            }

            appendTransition(
                    transitionLogger,
                    request,
                    state,
                    action,
                    rlAction,
                    current,
                    regenerated,
                    round,
                    !shouldRegenerateForKill(rlConfig, regenerated)
                            || round >= maxSemanticRegenerationRounds);

            current = regenerated;
            last = regenerated;
            lastAttempt = round;
            if (isBetterKillResult(regenerated, best)) {
                best = regenerated;
                bestAttempt = round;
                bestArtifacts = BestResultArtifacts.capture(regenerated, request, "regen-" + round);
            }
        }

        if (best != null) {
            bestArtifacts.restore(best, request);
        }
        Result selected = best == null ? current : best;
        if (selected != null) {
            selected.rlRegenPolicyName = "PROGRESSIVE_SEMANTIC_RELAXATION";
            selected.rlRegenStrategyPath = joinStrategyPath(regenStrategyPath);
            selected.semanticGenerationCalls = Math.max(1, 1 + lastAttempt);
            selected.semanticStage = lastAttempt <= 0
                    ? "STRICT_CORE"
                    : (lastAttempt == 1 ? "RELAX_ORACLE" : "RELAX_OBSERVABLE_ORACLE");
            selected.semanticPlanExhausted = !selected.killed
                    && selected.compiled
                    && selected.originalPassed
                    && maxSemanticRegenerationRounds >= 2
                    && lastAttempt >= maxSemanticRegenerationRounds;
            selected.lastKnownGoodRestored = rollbackTriggered || (last != null && selected != last);
        }
        annotateEpisodeAttempts(selected, last, bestAttempt, lastAttempt,
                terminationReason(selected, lastAttempt > 0 && selected != last));
        return selected;
    }

    private static void logHistoricalExperience(Request request,
                                                EvidenceState state,
                                                HistoricalExperienceSnapshot history) {
        if (state == null || history == null) {
            return;
        }
        System.out.println("[RL-HISTORY] taskId=" + nullToTaskId(request)
                + ", outerLoopRound=" + state.outerLoopRound
                + ", previous={found=" + history.hasPreviousResult
                + ", compiled=" + history.previousCompileSuccess
                + ", originalPass=" + history.previousOriginalPassed
                + ", killed=" + history.previousKilled
                + ", repairRounds=" + history.previousRepairRounds + "}"
                + ", sameSite={processed=" + history.sameSiteProcessedCount
                + ", compileRate=" + String.format(Locale.ROOT, "%.3f", history.sameSiteCompileSuccessRate)
                + ", killRate=" + String.format(Locale.ROOT, "%.3f", history.sameSiteKillRate)
                + ", killed=" + history.sameSiteSuccessfulCount + "}"
                + ", sameMethod={processed=" + history.sameMethodProcessedCount
                + ", compileRate=" + String.format(Locale.ROOT, "%.3f", history.sameMethodCompileSuccessRate)
                + ", killRate=" + String.format(Locale.ROOT, "%.3f", history.sameMethodKillRate)
                + ", killed=" + history.sameMethodSuccessfulCount + "}"
                + ", refs=" + history.getReferences().size());
    }

    private static void recordSemanticWarning(Result result,
                                              Path reportRoot,
                                              String artifactBaseName,
                                              int attempt,
                                              String warning) throws IOException {
        if (result != null) {
            result.semanticWarningCount++;
            result.semanticWarning = oneLine(warning);
        }
        if (reportRoot != null && !isBlank(warning)) {
            writeText(reportRoot.resolve(artifactBaseName + "__semantic_warning_" + attempt + ".txt"), warning);
        }
        System.out.println("[SEMANTIC-PRECHECK-WARN] taskId=" + (result == null ? "" : safe(result.taskId))
                + ", attempt=" + attempt + ", reason=" + oneLine(warning));
    }

    private static boolean isBetterKillResult(Result candidate, Result best) {
        if (candidate == null) {
            return false;
        }
        if (best == null) {
            return true;
        }
        int candidateRank = killResultRank(candidate);
        int bestRank = killResultRank(best);
        if (candidateRank != bestRank) {
            return candidateRank > bestRank;
        }
        if (candidate.killed != best.killed) {
            return candidate.killed;
        }
        if (candidate.compiled != best.compiled) {
            return candidate.compiled;
        }
        if (candidate.originalPassed != best.originalPassed) {
            return candidate.originalPassed;
        }
        if (candidate.timedOut != best.timedOut) {
            return !candidate.timedOut;
        }
        int candidateRepairs = candidate.timing == null ? Integer.MAX_VALUE : candidate.timing.repairRounds;
        int bestRepairs = best.timing == null ? Integer.MAX_VALUE : best.timing.repairRounds;
        if (candidateRepairs != bestRepairs) {
            return candidateRepairs < bestRepairs;
        }
        long candidateTotal = candidate.timing == null ? Long.MAX_VALUE : candidate.timing.totalMillis;
        long bestTotal = best.timing == null ? Long.MAX_VALUE : best.timing.totalMillis;
        return candidateTotal < bestTotal;
    }

    private static int killResultRank(Result result) {
        if (result == null) {
            return 0;
        }
        String status = safe(result.targetStatus);
        if (result.killed || Result.STATUS_KILLED.equalsIgnoreCase(status)) {
            return 5;
        }
        if (result.compiled && result.originalPassed && !result.killed) {
            return 4;
        }
        if (result.compiled && !result.originalPassed) {
            return 3;
        }
        if (!result.compiled) {
            if (result.timedOut
                    || Result.STATUS_API_FAILED.equalsIgnoreCase(status)
                    || Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(status)
                    || Result.STATUS_TASK_CRASHED.equalsIgnoreCase(status)
                    || Result.STATUS_TIMEOUT.equalsIgnoreCase(status)) {
                return 1;
            }
            return 2;
        }
        if (result.timedOut
                || Result.STATUS_API_FAILED.equalsIgnoreCase(status)
                || Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(status)
                || Result.STATUS_TASK_CRASHED.equalsIgnoreCase(status)
                || Result.STATUS_TIMEOUT.equalsIgnoreCase(status)) {
            return 1;
        }
        return 2;
    }

    private static boolean shouldRegenerateForKill(RLPipelineConfig rlConfig, Result result) {
        return rlConfig != null
                && rlConfig.isEnabled()
                && rlConfig.isRegenerationEnabled()
                && result != null
                && result.compiled
                && result.originalPassed
                && !result.equivalenceSuspicion
                && !result.killed;
    }

    private static void markEquivalentSuspectedIfNeeded(Result result) {
        if (result == null) {
            return;
        }
        if (!result.equivalenceSuspicion) {
            return;
        }
        if (!result.compiled || !result.originalPassed || result.killed || result.timedOut) {
            return;
        }
        if (Result.STATUS_SURVIVED.equalsIgnoreCase(safe(result.targetStatus))
                || safe(result.targetStatus).isEmpty()) {
            result.targetStatus = Result.STATUS_EQUIVALENT_SUSPECTED;
            if (isBlank(result.failureReason) && !isBlank(result.equivalenceReason)) {
                result.failureReason = "Equivalent suspected: " + oneLine(result.equivalenceReason);
            }
        }
    }

    private static RegenerationStrategy selectRegenerationStrategy(EvidenceState state,
                                                                   PromptEvidence evidence,
                                                                   Result current,
                                                                   List<SuccessTestReference> refs) {
        if (evidence != null && evidence.hasSymbolicRipPlan()) {
            if (evidence.hasBoundarySensitiveInputGuidance()
                    || evidence.hasReachabilityGuards()
                    || evidence.symbolicSuggestsDeeperPath()) {
                return RegenerationStrategy.INPUT_STRENGTHEN;
            }
            if (hasCodeKbObservableSignal(state, evidence) || evidence.hasPromptObservableSignal()) {
                return RegenerationStrategy.OBSERVABLE_SWITCH;
            }
        }
        if (evidence != null && evidence.requiresRealEntryChain()) {
            if (evidence.hasBoundarySensitiveInputGuidance() || evidence.hasReachabilityGuards()) {
                return RegenerationStrategy.INPUT_STRENGTHEN;
            }
            if (hasCodeKbObservableSignal(state, evidence)) {
                return RegenerationStrategy.OBSERVABLE_SWITCH;
            }
        }
        if (evidence != null && evidence.isPureReturnLikeStaticEntry()) {
            if (hasWeakAssertionSignal(evidence, current)) {
                return RegenerationStrategy.ASSERTION_STRENGTHEN;
            }
            if (shouldUseReferenceGuidance(evidence, refs)) {
                return RegenerationStrategy.REFERENCE_GUIDED_TARGET_KILL;
            }
                return RegenerationStrategy.INPUT_STRENGTHEN;
        }
        if (evidence != null && evidence.prefersExceptionAssertion() && !evidence.hasFrontLoadedExceptionRisk()) {
            return RegenerationStrategy.EXCEPTION_ORACLE;
        }
        if (evidence != null && evidence.hasBoundarySensitiveInputGuidance()) {
            return RegenerationStrategy.INPUT_STRENGTHEN;
        }
        if (hasWeakAssertionSignal(evidence, current)) {
            return RegenerationStrategy.ASSERTION_STRENGTHEN;
        }
        if (hasCodeKbObservableSignal(state, evidence)) {
            return RegenerationStrategy.OBSERVABLE_SWITCH;
        }
        String observableKind = evidence == null ? "" : safe(evidence.observablePlanKind());
        if (observableKind.contains("REFLECTION_FIELD_READ_AFTER_CONSTRUCTION")
                || observableKind.contains("REFLECTION_FIELD_READ_AFTER_SETTER")
                || observableKind.contains("THROWABLE_MESSAGE")
                || observableKind.contains("CONSTRUCTOR_PUBLIC")
                || observableKind.contains("MUTABLE_PARAMETER_STATE")
                || observableKind.contains("APPENDABLE_CONTENT")
                || observableKind.contains("WRITER_CONTENT")
                || observableKind.contains("PUBLIC_METHOD_DEPENDS_ON_STATE")) {
            return RegenerationStrategy.OBSERVABLE_SWITCH;
        }
        if (shouldUseReferenceGuidance(evidence, refs)) {
            return RegenerationStrategy.REFERENCE_GUIDED_TARGET_KILL;
        }
        return RegenerationStrategy.INPUT_STRENGTHEN;
    }

    private static boolean shouldUseReferenceGuidance(PromptEvidence evidence,
                                                      List<SuccessTestReference> refs) {
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        if (evidence == null) {
            return true;
        }
        if (evidence.hasObservableOverride()) {
            return false;
        }
        if (evidence.hasPromptObservableSignal() || evidence.hasPromptAssertionSignal()) {
            return false;
        }
        return !evidence.hasPromptEntrySignal();
    }

    private static boolean hasWeakAssertionSignal(PromptEvidence evidence, Result current) {
        if (current != null && !isBlank(current.failureReason)) {
            String failure = current.failureReason.toLowerCase(Locale.ROOT);
            if (failure.contains("not killed") || failure.contains("live mutant")) {
                return true;
            }
        }
        return evidence != null && evidence.hasWeakAssertionSignal();
    }

    private static boolean hasCodeKbObservableSignal(EvidenceState state, PromptEvidence evidence) {
        if (state == null || !state.hasCodeKbContext) {
            return false;
        }
        if (state.codeKbFieldAccessCount > 0) {
            return true;
        }
        String reason = safe(state.codeKbTopEntryReason).toLowerCase(Locale.ROOT);
        if (reason.contains("directly calls mutated method")) {
            return true;
        }
        return evidence != null && evidence.codeKbMethodCallCount() > 0 && evidence.codeKbEntryCount() > 0;
    }

    private static Request cloneRequestForRegeneration(Request request) {
        Request copy = new Request();
        copy.sourceModuleHome = request.sourceModuleHome;
        copy.resultModuleHome = request.resultModuleHome;
        copy.projectName = request.projectName;
        copy.targetClassName = request.targetClassName;
        copy.methodSignature = request.methodSignature;
        copy.mutantName = request.mutantName;
        copy.outputJsonPath = request.outputJsonPath;
        copy.originJavaPath = request.originJavaPath;
        copy.mutatedJavaPath = request.mutatedJavaPath;
        copy.timeoutMillis = request.timeoutMillis;
        copy.maxRepairRounds = request.maxRepairRounds;
        copy.taskId = request.taskId;
        copy.excelRowIndex = request.excelRowIndex;
        copy.runId = request.runId;
        copy.outerLoopRound = request.outerLoopRound;
        copy.evidenceHash = request.evidenceHash;
        copy.semanticStage = request.semanticStage;
        copy.maxLlmApiAttempts = request.maxLlmApiAttempts;
        copy.skipIfCompiledExists = false;
        return copy;
    }

    private static final class BestResultArtifacts {
        private final byte[] javaBytes;
        private final byte[] classBytes;
        private final String sourceLabel;

        private BestResultArtifacts(byte[] javaBytes, byte[] classBytes, String sourceLabel) {
            this.javaBytes = javaBytes;
            this.classBytes = classBytes;
            this.sourceLabel = sourceLabel;
        }

        static BestResultArtifacts capture(Result result, Request request, String sourceLabel) throws IOException {
            Path javaPath = javaPath(result);
            Path classPath = classPath(result, request);
            byte[] javaBytes = (javaPath != null && Files.isRegularFile(javaPath)) ? Files.readAllBytes(javaPath) : null;
            byte[] classBytes = (classPath != null && Files.isRegularFile(classPath)) ? Files.readAllBytes(classPath) : null;
            return new BestResultArtifacts(javaBytes, classBytes, sourceLabel == null ? "" : sourceLabel);
        }

        void restore(Result result, Request request) throws IOException {
            Path javaPath = javaPath(result);
            if (javaPath != null && javaBytes != null) {
                Files.createDirectories(javaPath.getParent());
                Files.write(javaPath, javaBytes);
            }
            Path classPath = classPath(result, request);
            if (classPath != null && classBytes != null) {
                Files.createDirectories(classPath.getParent());
                Files.write(classPath, classBytes);
            }
            if (result != null && result.reportDir != null && result.testSetName != null) {
                Path marker = Paths.get(result.reportDir).resolve(fileBaseName(result.testSetName) + "__best_result_source.txt");
                writeText(marker, sourceLabel);
            }
        }

        private static Path javaPath(Result result) {
            if (result == null || isBlank(result.testJavaFile)) {
                return null;
            }
            return Paths.get(result.testJavaFile).toAbsolutePath().normalize();
        }

        private static Path classPath(Result result, Request request) {
            if (result == null || request == null || isBlank(result.testSetName) || isBlank(request.resultModuleHome)) {
                return null;
            }
            return Paths.get(buildGeneratedClassFilePath(request.resultModuleHome, result.testSetName))
                    .toAbsolutePath().normalize();
        }
    }

    private static String readPreviousGeneratedCode(String testJavaFile) {
        if (testJavaFile == null || testJavaFile.trim().isEmpty()) {
            return "";
        }
        try {
            Path path = Paths.get(testJavaFile).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                return "";
            }
            return FileTextUtils.readUtf8(path);
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildArtifactBaseName(String testSetName, GenerationOptions options) {
        String base = fileBaseName(testSetName);
        if (options == null || options.artifactSuffix == null || options.artifactSuffix.trim().isEmpty()) {
            return base;
        }
        return base + "__" + options.artifactSuffix.trim();
    }

    private static String buildRepairPhaseLabel(GenerationOptions options, int attempt) {
        String prefix = options != null && options.promptPhase != null ? options.promptPhase : "initial";
        return prefix + "-repair-" + attempt;
    }

    private static LlmCallResult generateFreshLlmResponse(CachedLlmClient client,
                                                          String prompt,
                                                          Path responseFile,
                                                          int maxOutputTokens) throws Exception {
        LlmCallResult result = client.generate(prompt, false, maxOutputTokens);
        writeText(responseFile, result.response);
        return result;
    }

    private static void finishTiming(Result result) {
        if (result == null || result.timing == null) {
            return;
        }
        result.timing.totalMillis = result.timing.evidenceMillis
                + result.timing.promptMillis
                + result.timing.llmMillis
                + result.timing.compileMillis
                + result.timing.repairPromptMillis;
    }

    private static LlmCallResult generateOrReuseLlmResponse(CachedLlmClient client,
                                                            String prompt,
                                                            Path responseFile,
                                                            boolean forceRegenerate,
                                                            int maxOutputTokens) throws Exception {
        if (!forceRegenerate && Files.isRegularFile(responseFile)) {
            LlmCallResult result = new LlmCallResult();
            result.response = FileTextUtils.readUtf8(responseFile);
            result.cacheHit = true;
            result.attempts = 0;
            result.elapsedMillis = 0L;
            return result;
        }

        LlmCallResult result = client.generate(prompt, !forceRegenerate, maxOutputTokens);
        writeText(responseFile, result.response);
        return result;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean generatedTestAlreadyCompiled(Path javaFile,
                                                        Path testClassesRoot,
                                                        String testSetName) {
        if (!Files.isRegularFile(javaFile)) {
            return false;
        }
        Path classFile = testClassesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class");
        return Files.isRegularFile(classFile);
    }

    private static String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return Paths.get(path.replace("//?/", "")).normalize().toString();
    }

    private static void writeExecutionReports(List<Request> requests,
                                              List<TaskOutcome> completedOutcomes,
                                              List<SuiteLevelUpdate> suiteUpdates) throws Exception {
        if ((requests == null || requests.isEmpty())
                && (completedOutcomes == null || completedOutcomes.isEmpty())) {
            return;
        }

        Map<String, Request> requestByMutantKey = new LinkedHashMap<String, Request>();
        if (requests != null) {
            for (Request request : requests) {
                requestByMutantKey.put(mutantKey(request.resultModuleHome,
                        request.targetClassName,
                        request.methodSignature,
                        request.mutantName), request);
            }
        }

        Map<String, TaskOutcome> outcomeByMutantKey = new LinkedHashMap<String, TaskOutcome>();
        if (completedOutcomes != null) {
            for (TaskOutcome outcome : completedOutcomes) {
                if (outcome == null || outcome.result == null) {
                    continue;
                }
                Result result = outcome.result;
                outcomeByMutantKey.put(mutantKey(resolveResultModuleHome(result, requestByMutantKey),
                        result.targetClassName,
                        result.methodSignature,
                        result.mutantName), outcome);
            }
        }

        Map<String, SuiteLevelUpdate> suiteUpdateByMutantKey = new LinkedHashMap<String, SuiteLevelUpdate>();
        if (suiteUpdates != null) {
            for (SuiteLevelUpdate update : suiteUpdates) {
                String resultModuleHome = resolveResultModuleHome(update, requestByMutantKey);
                suiteUpdateByMutantKey.put(mutantKey(resultModuleHome,
                        update.targetClassName,
                        update.methodSignature,
                        update.mutantName), update);
            }
        }

        LinkedHashMap<String, BatchExecutionReport> reports = new LinkedHashMap<String, BatchExecutionReport>();
        if (requests != null) {
            for (Request request : requests) {
                String reportKey = methodKey(request.resultModuleHome, request.targetClassName, request.methodSignature);
                BatchExecutionReport report = reports.get(reportKey);
                if (report == null) {
                    report = new BatchExecutionReport();
                    report.sourceModuleHome = request.sourceModuleHome;
                    report.resultModuleHome = request.resultModuleHome;
                    report.targetClassName = request.targetClassName;
                    report.methodSignature = request.methodSignature;
                    reports.put(reportKey, report);
                }
                report.methodCount = 1;
                report.timeoutMillis = request.timeoutMillis;
            }
        }

        for (BatchExecutionReport report : reports.values()) {
            populateReportEntries(report, requestByMutantKey, outcomeByMutantKey, suiteUpdateByMutantKey);
            writeMethodExecutionReport(report);
        }
        writeExecutionSummaryFiles(reports.values());
    }

    private static void populateReportEntries(BatchExecutionReport report,
                                              Map<String, Request> requestByMutantKey,
                                              Map<String, TaskOutcome> outcomeByMutantKey,
                                              Map<String, SuiteLevelUpdate> suiteUpdateByMutantKey) {
        if (report == null) {
            return;
        }

        LinkedHashSet<String> mutantKeys = new LinkedHashSet<String>();
        String methodPrefix = methodKey(report.resultModuleHome, report.targetClassName, report.methodSignature) + "##";
        for (String key : requestByMutantKey.keySet()) {
            if (key.startsWith(methodPrefix)) {
                mutantKeys.add(key);
            }
        }
        for (String key : suiteUpdateByMutantKey.keySet()) {
            if (key.startsWith(methodPrefix)) {
                mutantKeys.add(key);
            }
        }

        for (String key : mutantKeys) {
            Request request = requestByMutantKey.get(key);
            TaskOutcome outcome = outcomeByMutantKey.get(key);
            SuiteLevelUpdate update = suiteUpdateByMutantKey.get(key);
            Result result = outcome == null ? null : outcome.result;

            BatchTargetEntry entry = new BatchTargetEntry();
            entry.rowIndex = parseRowIndex(result == null ? nullToTaskId(request) : result.taskId);
            entry.targetClassName = request != null ? request.targetClassName : (update == null ? "" : update.targetClassName);
            entry.methodSignature = request != null ? request.methodSignature : (update == null ? "" : update.methodSignature);
            entry.mutantName = request != null ? request.mutantName : (update == null ? "" : update.mutantName);
            entry.testSetName = result == null ? buildGeneratedTestFqn(entry.targetClassName, entry.mutantName) : safe(result.testSetName);
            entry.compiled = result != null && result.compiled;
            entry.mappingStatus = entry.testSetName.isEmpty() ? "MISSING_TEST_NAME" : "OK";
            entry.originalPassed = result != null && result.originalPassed;
            entry.mutantExecuted = result != null && isExecutedTargetStatus(result.targetStatus);
            entry.targetKilled = result != null && result.killed;
            entry.targetStatus = result == null ? Result.STATUS_NOT_EXECUTED : safe(result.targetStatus);
            entry.targetFailureReason = result == null ? "" : safe(result.failureReason);
            entry.javaFile = result == null ? "" : safe(result.testJavaFile);
            entry.classFile = buildGeneratedClassFilePath(report.resultModuleHome, entry.testSetName);
            entry.excelIsKilled = "";
            report.testEntries.add(entry);

            report.generatedMutantCount++;
            report.generatedTestCount++;
            if (entry.compiled) {
                report.compiledTestCount++;
            }
            if (entry.compiled && entry.mutantExecuted && "OK".equalsIgnoreCase(entry.mappingStatus)) {
                report.executableCompiledTestCount++;
                report.targetExecutedTestCount++;
            }
            if (entry.targetKilled) {
                report.targetKilledCount++;
            }
            if (!entry.testSetName.isEmpty()) {
                report.suiteTestSetNames.add(entry.testSetName);
                report.suiteTestResults.put(entry.testSetName, entry.targetStatus);
            }
            if (entry.originalPassed) {
                report.suiteOriginalSuccessCount++;
            } else {
                report.suiteOriginalFailureCount++;
            }
            if (result != null && result.timedOut) {
                report.suiteTimeoutRuns++;
            }
            if (result != null && result.timing != null) {
                report.suiteElapsedMillis += result.timing.totalMillis;
            }

            if (update != null) {
                report.suiteMutantResults.put(update.mutantName, safe(update.suiteKillStatus));
                if (update.suiteKilledByAnyTest) {
                    report.suiteKilledCount++;
                    report.suiteKilledMutants.add(update.mutantName);
                } else if ("LIVE".equalsIgnoreCase(safe(update.suiteKillStatus))) {
                    report.suiteLiveCount++;
                    report.suiteLiveMutants.add(update.mutantName);
                }
                report.suitePrimaryRuns++;
                if (update.killedByFallbackTest) {
                    report.suiteFallbackRuns++;
                }
                if ("TIMEOUT".equalsIgnoreCase(entry.targetStatus)) {
                    report.suiteTimeoutMutants++;
                }
            }
        }

        report.targetKillScore = report.targetExecutedTestCount == 0 ? 0.0
                : report.targetKilledCount * 100.0 / report.targetExecutedTestCount;
        report.suiteMutantScore = (report.suiteKilledCount + report.suiteLiveCount) == 0 ? 0.0
                : report.suiteKilledCount * 100.0 / (report.suiteKilledCount + report.suiteLiveCount);
        report.suiteStatus = "OK";
        report.suiteFailureReason = "";
    }

    private static void writeMethodExecutionReport(BatchExecutionReport report) throws Exception {
        Path reportDir = getExecutionReportDir(report.resultModuleHome, report.targetClassName, report.methodSignature);
        Files.createDirectories(reportDir);
        writeText(reportDir.resolve(TARGET_RESULTS_JSON), report.toJson().toString(2));
        writeText(reportDir.resolve(SUITE_RESULTS_JSON), report.toSuiteJson().toString(2));
        writeText(reportDir.resolve(SUMMARY_TXT), report.toConsoleString());
        writeTargetCsv(reportDir.resolve(TARGET_RESULTS_CSV), report.testEntries);
    }

    private static void writeExecutionSummaryFiles(Iterable<BatchExecutionReport> reports) throws Exception {
        LinkedHashMap<String, List<BatchExecutionReport>> byModule = new LinkedHashMap<String, List<BatchExecutionReport>>();
        for (BatchExecutionReport report : reports) {
            List<BatchExecutionReport> list = byModule.get(report.resultModuleHome);
            if (list == null) {
                list = new ArrayList<BatchExecutionReport>();
                byModule.put(report.resultModuleHome, list);
            }
            list.add(report);
        }

        for (Map.Entry<String, List<BatchExecutionReport>> entry : byModule.entrySet()) {
            writeModuleExecutionSummary(entry.getKey(), entry.getValue());
        }
    }

    private static void writeModuleExecutionSummary(String resultModuleHome,
                                                    List<BatchExecutionReport> reports) throws Exception {
        if (isBlank(resultModuleHome) || reports == null || reports.isEmpty()) {
            return;
        }
        Path dir = Paths.get(resultModuleHome, MutationSystem.TESTSET_MODE_LLMS, BATCH_EXECUTION_REPORT_DIR, EXECUTION_RUN_ID)
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);

        Path csvFile = dir.resolve("all_method_summary.csv");
        try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            bw.write("targetClassName,methodSignature,methodCount,generatedMutantCount,generatedTestCount,compiledTestCount,compileRate,executableCompiledTestCount,targetExecutedTestCount,targetKilledCount,targetKillScore,suiteStatus,suiteKilledCount,suiteLiveCount,suiteMutantScore,suitePrimaryRuns,suiteFallbackRuns,suiteOriginalSuccessCount,suiteOriginalFailureCount,suiteMutantsWithoutMappedTest,suiteTimeoutRuns,suiteTimeoutMutants,suiteElapsedMillis");
            bw.newLine();
            for (BatchExecutionReport report : reports) {
                double compileRate = report.generatedTestCount == 0 ? 0.0
                        : report.compiledTestCount * 100.0 / report.generatedTestCount;
                bw.write(csv(report.targetClassName));
                bw.write(',');
                bw.write(csv(report.methodSignature));
                bw.write(',');
                bw.write(String.valueOf(report.methodCount));
                bw.write(',');
                bw.write(String.valueOf(report.generatedMutantCount));
                bw.write(',');
                bw.write(String.valueOf(report.generatedTestCount));
                bw.write(',');
                bw.write(String.valueOf(report.compiledTestCount));
                bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", compileRate));
                bw.write(',');
                bw.write(String.valueOf(report.executableCompiledTestCount));
                bw.write(',');
                bw.write(String.valueOf(report.targetExecutedTestCount));
                bw.write(',');
                bw.write(String.valueOf(report.targetKilledCount));
                bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", report.targetKillScore));
                bw.write(',');
                bw.write(csv(report.suiteStatus));
                bw.write(',');
                bw.write(String.valueOf(report.suiteKilledCount));
                bw.write(',');
                bw.write(String.valueOf(report.suiteLiveCount));
                bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", report.suiteMutantScore));
                bw.write(',');
                bw.write(String.valueOf(report.suitePrimaryRuns));
                bw.write(',');
                bw.write(String.valueOf(report.suiteFallbackRuns));
                bw.write(',');
                bw.write(String.valueOf(report.suiteOriginalSuccessCount));
                bw.write(',');
                bw.write(String.valueOf(report.suiteOriginalFailureCount));
                bw.write(',');
                bw.write(String.valueOf(report.suiteMutantsWithoutMappedTest));
                bw.write(',');
                bw.write(String.valueOf(report.suiteTimeoutRuns));
                bw.write(',');
                bw.write(String.valueOf(report.suiteTimeoutMutants));
                bw.write(',');
                bw.write(String.valueOf(report.suiteElapsedMillis));
                bw.newLine();
            }
        }

        JSONArray arr = new JSONArray();
        for (BatchExecutionReport report : reports) {
            arr.put(report.toSuiteJson());
        }
        writeText(dir.resolve("all_method_summary.json"), arr.toString(2));
        writeAggregatedClassSummary(dir, reports);
    }

    private static void writeAggregatedClassSummary(Path dir,
                                                    List<BatchExecutionReport> reports) throws Exception {
        LinkedHashMap<String, BatchExecutionReport> byClass = new LinkedHashMap<String, BatchExecutionReport>();
        for (BatchExecutionReport report : reports) {
            String key = report.resultModuleHome + "##" + report.targetClassName;
            BatchExecutionReport agg = byClass.get(key);
            if (agg == null) {
                agg = new BatchExecutionReport();
                agg.sourceModuleHome = report.sourceModuleHome;
                agg.resultModuleHome = report.resultModuleHome;
                agg.targetClassName = report.targetClassName;
                agg.methodSignature = "<ALL_METHODS>";
                agg.timeoutMillis = report.timeoutMillis;
                agg.suiteStatus = "OK";
                byClass.put(key, agg);
            }
            agg.methodCount += Math.max(1, report.methodCount);
            agg.generatedMutantCount += report.generatedMutantCount;
            agg.generatedTestCount += report.generatedTestCount;
            agg.compiledTestCount += report.compiledTestCount;
            agg.executableCompiledTestCount += report.executableCompiledTestCount;
            agg.targetExecutedTestCount += report.targetExecutedTestCount;
            agg.targetKilledCount += report.targetKilledCount;
            agg.suiteKilledCount += report.suiteKilledCount;
            agg.suiteLiveCount += report.suiteLiveCount;
            agg.suitePrimaryRuns += report.suitePrimaryRuns;
            agg.suiteFallbackRuns += report.suiteFallbackRuns;
            agg.suiteOriginalSuccessCount += report.suiteOriginalSuccessCount;
            agg.suiteOriginalFailureCount += report.suiteOriginalFailureCount;
            agg.suiteMutantsWithoutMappedTest += report.suiteMutantsWithoutMappedTest;
            agg.suiteTimeoutRuns += report.suiteTimeoutRuns;
            agg.suiteTimeoutMutants += report.suiteTimeoutMutants;
            agg.suiteElapsedMillis += report.suiteElapsedMillis;
            agg.suiteKilledMutants.addAll(report.suiteKilledMutants);
            agg.suiteLiveMutants.addAll(report.suiteLiveMutants);
            agg.suiteTestSetNames.addAll(report.suiteTestSetNames);
            agg.suiteMutantResults.putAll(report.suiteMutantResults);
            agg.suiteTestResults.putAll(report.suiteTestResults);
        }
        for (BatchExecutionReport agg : byClass.values()) {
            agg.targetKillScore = agg.targetExecutedTestCount == 0 ? 0.0
                    : agg.targetKilledCount * 100.0 / agg.targetExecutedTestCount;
            agg.suiteMutantScore = (agg.suiteKilledCount + agg.suiteLiveCount) == 0 ? 0.0
                    : agg.suiteKilledCount * 100.0 / (agg.suiteKilledCount + agg.suiteLiveCount);
        }

        Path csvFile = dir.resolve("all_class_summary.csv");
        try (BufferedWriter bw = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8)) {
            bw.write("targetClassName,methodSignature,methodCount,generatedMutantCount,generatedTestCount,compiledTestCount,compileRate,executableCompiledTestCount,targetExecutedTestCount,targetKilledCount,targetKillScore,suiteStatus,suiteKilledCount,suiteLiveCount,suiteMutantScore,suitePrimaryRuns,suiteFallbackRuns,suiteOriginalSuccessCount,suiteOriginalFailureCount,suiteMutantsWithoutMappedTest,suiteTimeoutRuns,suiteTimeoutMutants,suiteElapsedMillis");
            bw.newLine();
            for (BatchExecutionReport report : byClass.values()) {
                double compileRate = report.generatedTestCount == 0 ? 0.0
                        : report.compiledTestCount * 100.0 / report.generatedTestCount;
                bw.write(csv(report.targetClassName));
                bw.write(',');
                bw.write(csv(report.methodSignature));
                bw.write(',');
                bw.write(String.valueOf(report.methodCount));
                bw.write(',');
                bw.write(String.valueOf(report.generatedMutantCount));
                bw.write(',');
                bw.write(String.valueOf(report.generatedTestCount));
                bw.write(',');
                bw.write(String.valueOf(report.compiledTestCount));
                bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", compileRate));
                bw.write(',');
                bw.write(String.valueOf(report.executableCompiledTestCount));
                bw.write(',');
                bw.write(String.valueOf(report.targetExecutedTestCount));
                bw.write(',');
                bw.write(String.valueOf(report.targetKilledCount));
                bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", report.targetKillScore));
                bw.write(',');
                bw.write(csv(report.suiteStatus));
                bw.write(',');
                bw.write(String.valueOf(report.suiteKilledCount));
                bw.write(',');
                bw.write(String.valueOf(report.suiteLiveCount));
                bw.write(',');
                bw.write(String.format(Locale.ROOT, "%.2f", report.suiteMutantScore));
                bw.write(',');
                bw.write(String.valueOf(report.suitePrimaryRuns));
                bw.write(',');
                bw.write(String.valueOf(report.suiteFallbackRuns));
                bw.write(',');
                bw.write(String.valueOf(report.suiteOriginalSuccessCount));
                bw.write(',');
                bw.write(String.valueOf(report.suiteOriginalFailureCount));
                bw.write(',');
                bw.write(String.valueOf(report.suiteMutantsWithoutMappedTest));
                bw.write(',');
                bw.write(String.valueOf(report.suiteTimeoutRuns));
                bw.write(',');
                bw.write(String.valueOf(report.suiteTimeoutMutants));
                bw.write(',');
                bw.write(String.valueOf(report.suiteElapsedMillis));
                bw.newLine();
            }
        }
        JSONArray arr = new JSONArray();
        for (BatchExecutionReport report : byClass.values()) {
            arr.put(report.toSuiteJson());
        }
        writeText(dir.resolve("all_class_summary.json"), arr.toString(2));
    }

    private static void writeTargetCsv(Path file, List<BatchTargetEntry> entries) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            bw.write("rowIndex,targetClassName,methodSignature,mutantName,testSetName,compiled,mappingStatus,originalPassed,mutantExecuted,targetKilled,targetStatus,failureReason,javaFile,classFile,excelIsKilled");
            bw.newLine();
            for (BatchTargetEntry e : entries) {
                bw.write(String.valueOf(e.rowIndex));
                bw.write(',');
                bw.write(csv(e.targetClassName));
                bw.write(',');
                bw.write(csv(e.methodSignature));
                bw.write(',');
                bw.write(csv(e.mutantName));
                bw.write(',');
                bw.write(csv(e.testSetName));
                bw.write(',');
                bw.write(String.valueOf(e.compiled));
                bw.write(',');
                bw.write(csv(e.mappingStatus));
                bw.write(',');
                bw.write(String.valueOf(e.originalPassed));
                bw.write(',');
                bw.write(String.valueOf(e.mutantExecuted));
                bw.write(',');
                bw.write(String.valueOf(e.targetKilled));
                bw.write(',');
                bw.write(csv(e.targetStatus));
                bw.write(',');
                bw.write(csv(e.targetFailureReason));
                bw.write(',');
                bw.write(csv(e.javaFile));
                bw.write(',');
                bw.write(csv(e.classFile));
                bw.write(',');
                bw.write(csv(e.excelIsKilled));
                bw.newLine();
            }
        }
    }

    private static Path getExecutionReportDir(String resultModuleHome,
                                              String targetClassName,
                                              String methodSignature) {
        return Paths.get(resultModuleHome,
                MutationSystem.TESTSET_MODE_LLMS,
                BATCH_EXECUTION_REPORT_DIR,
                EXECUTION_RUN_ID,
                sanitizeFileName(targetClassName),
                safe(methodSignature)).toAbsolutePath().normalize();
    }

    private static String sanitizeFileName(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "_";
        }
        return s.replaceAll("[\\\\/:*?\"<>|()\\s]+", "_");
    }

    private static String buildGeneratedClassFilePath(String resultModuleHome, String testSetName) {
        if (isBlank(resultModuleHome) || isBlank(testSetName)) {
            return "";
        }
        Path cls = Paths.get(resultModuleHome,
                MutationSystem.TESTSET_MODE_LLMS,
                "classes",
                testSetName.replace('.', File.separatorChar) + ".class").toAbsolutePath().normalize();
        return cls.toString();
    }

    private static int parseRowIndex(String taskId) {
        String text = safe(taskId).trim();
        if (text.startsWith("row-")) {
            try {
                return Integer.parseInt(text.substring(4));
            } catch (Exception ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String nullToTaskId(Request request) {
        return request == null ? "" : safe(request.taskId);
    }

    private static String resolveResultModuleHome(Result result, Map<String, Request> requestByMutantKey) {
        if (result != null && !isBlank(result.resultJsonlFile)) {
            try {
                Path p = Paths.get(result.resultJsonlFile).toAbsolutePath().normalize();
                Path reportDir = p.getParent();
                if (reportDir != null && reportDir.getFileName() != null
                        && "report".equalsIgnoreCase(reportDir.getFileName().toString())) {
                    Path llmDir = reportDir.getParent();
                    if (llmDir != null) {
                        Path module = llmDir.getParent();
                        if (module != null) {
                            return module.toString();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String partialKey = "##" + safe(result.targetClassName) + "##" + safe(result.methodSignature) + "##" + safe(result.mutantName);
        for (Map.Entry<String, Request> entry : requestByMutantKey.entrySet()) {
            if (entry.getKey().endsWith(partialKey)) {
                return entry.getValue().resultModuleHome;
            }
        }
        return "";
    }

    private static String resolveResultModuleHome(SuiteLevelUpdate update, Map<String, Request> requestByMutantKey) {
        String partialKey = "##" + safe(update.targetClassName) + "##" + safe(update.methodSignature) + "##" + safe(update.mutantName);
        for (Map.Entry<String, Request> entry : requestByMutantKey.entrySet()) {
            if (entry.getKey().endsWith(partialKey)) {
                return entry.getValue().resultModuleHome;
            }
        }
        return "";
    }

    private static String mutantKey(String resultModuleHome,
                                    String targetClassName,
                                    String methodSignature,
                                    String mutantName) {
        return safe(resultModuleHome) + "##" + safe(targetClassName) + "##" + safe(methodSignature) + "##" + safe(mutantName);
    }

    private static String methodKey(String resultModuleHome,
                                    String targetClassName,
                                    String methodSignature) {
        return safe(resultModuleHome) + "##" + safe(targetClassName) + "##" + safe(methodSignature);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String csv(String s) {
        String value = s == null ? "" : s.replace("\r", " ").replace("\n", " ");
        if (value.contains(",") || value.contains("\"") || value.contains(" ")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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

    private static String operatorFamily(String operator) {
        String value = safe(operator).trim();
        int idx = value.indexOf('_');
        return idx > 0 ? value.substring(0, idx) : value;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<SuiteLevelUpdate> enrichSuiteLevelFields(List<Request> requests) throws Exception {
        if (requests == null || requests.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, List<Request>> groups = new LinkedHashMap<String, List<Request>>();
        for (Request request : requests) {
            if (request == null) {
                continue;
            }
            String key = safe(request.resultModuleHome) + "##"
                    + safe(request.targetClassName) + "##"
                    + safe(request.methodSignature);
            List<Request> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Request>();
                groups.put(key, bucket);
            }
            bucket.add(request);
        }

        LinkedHashMap<Path, List<SuiteLevelUpdate>> updatesByFile = new LinkedHashMap<Path, List<SuiteLevelUpdate>>();
        List<SuiteLevelUpdate> allUpdates = new ArrayList<SuiteLevelUpdate>();
        for (List<Request> group : groups.values()) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            Request sample = group.get(0);
            TestRunner9_MultiProcess_batched.LlmMappedSuiteResult suiteResult = runSuiteLevelForMethod(sample);
            Map<String, SuiteLevelUpdate> perMutant = buildSuiteLevelUpdates(sample, suiteResult);

            Path resultJsonl = Paths.get(sample.resultModuleHome, MutationSystem.TESTSET_MODE_LLMS, "report", "llm_generation_results.jsonl")
                    .toAbsolutePath().normalize();
            List<SuiteLevelUpdate> updates = updatesByFile.get(resultJsonl);
            if (updates == null) {
                updates = new ArrayList<SuiteLevelUpdate>();
                updatesByFile.put(resultJsonl, updates);
            }
            updates.addAll(perMutant.values());
            allUpdates.addAll(perMutant.values());
        }

        for (Map.Entry<Path, List<SuiteLevelUpdate>> entry : updatesByFile.entrySet()) {
            rewriteResultJsonlWithSuiteFields(entry.getKey(), entry.getValue());
        }
        return allUpdates;
    }

    private static boolean isExecutedTargetStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return Result.STATUS_KILLED.equalsIgnoreCase(normalized)
                || Result.STATUS_SURVIVED.equalsIgnoreCase(normalized)
                || Result.STATUS_EQUIVALENT_SUSPECTED.equalsIgnoreCase(normalized)
                || Result.STATUS_ORIGINAL_FAILED.equalsIgnoreCase(normalized)
                || Result.STATUS_TIMEOUT.equalsIgnoreCase(normalized);
    }

    private static String deriveNonExecutedFinalStatus(Result result) {
        if (result == null) {
            return Result.STATUS_NOT_EXECUTED;
        }
        String existing = safe(result.targetStatus).trim();
        if (!existing.isEmpty()) {
            return existing;
        }
        String reason = safe(result.failureReason).toLowerCase(Locale.ROOT);
        if (reason.startsWith("skipped by output.json:")) {
            return Result.STATUS_SKIPPED_BY_EVIDENCE;
        }
        if (reason.contains("empty visible content") || reason.contains("reasoning_content only")) {
            return Result.STATUS_LLM_NO_VISIBLE_CODE;
        }
        if (reason.contains("finish_reason=length")
                || reason.contains("does not contain expected test class")
                || reason.contains("llm output truncated")) {
            return Result.STATUS_LLM_OUTPUT_INVALID;
        }
        int repairRounds = result.timing == null ? 0 : result.timing.repairRounds;
        if (reason.contains("llm api failed")
                || reason.contains("llm http error")
                || reason.startsWith("api failed:")) {
            return Result.STATUS_API_FAILED;
        }
        if (reason.startsWith("generation failed:")) {
            return Result.STATUS_GENERATION_FAILED;
        }
        if (reason.contains("task crashed:")) {
            return Result.STATUS_TASK_CRASHED;
        }
        if (repairRounds > 0 || result.compileRounds > 1) {
            return Result.STATUS_COMPILE_FAILED_AFTER_REPAIR;
        }
        if (!reason.isEmpty()) {
            return Result.STATUS_COMPILE_FAILED_AFTER_GENERATION;
        }
        return Result.STATUS_NOT_EXECUTED;
    }

    private static String normalizeExecutedFinalStatus(String rawStatus, String failureReason) {
        String normalized = safe(rawStatus).trim();
        if (normalized.isEmpty()) {
            return Result.STATUS_TASK_CRASHED;
        }
        if (containsTimeout(normalized) || containsTimeout(failureReason)) {
            return Result.STATUS_TIMEOUT;
        }
        if ("LIVE".equalsIgnoreCase(normalized)) {
            return Result.STATUS_SURVIVED;
        }
        if ("KILLED".equalsIgnoreCase(normalized)) {
            return Result.STATUS_KILLED;
        }
        if ("ORIGINAL_FAILED".equalsIgnoreCase(normalized)) {
            return Result.STATUS_ORIGINAL_FAILED;
        }
        if ("ERROR".equalsIgnoreCase(normalized)) {
            return Result.STATUS_TASK_CRASHED;
        }
        return normalized;
    }

    private static TestRunner9_MultiProcess_batched.LlmMappedSuiteResult runSuiteLevelForMethod(Request sample) {
        List<TestRunner9_MultiProcess_batched.LlmMappedTest> mappedTests = buildMappedTestsForMethod(sample);
        TestRunner9_MultiProcess_batched.LlmMappedSuiteResult out;
        if (mappedTests.isEmpty()) {
            out = new TestRunner9_MultiProcess_batched.LlmMappedSuiteResult();
            out.status = "SKIP_NO_COMPILED_TESTS";
            return out;
        }
        try {
            TestRunner9_MultiProcess_batched.setTestSetMode(MutationSystem.TESTSET_MODE_LLMS);
            System.setProperty(MutationSystem.TESTSET_MODE_PROP, MutationSystem.TESTSET_MODE_LLMS);
            System.setProperty("mujava.result.module.home", sample.resultModuleHome);
            return TestRunner9_MultiProcess_batched.runLlmMappedSuite(
                    sample.targetClassName,
                    mappedTests,
                    true,
                    sample.timeoutMillis,
                    sample.sourceModuleHome,
                    sample.resultModuleHome,
                    sample.methodSignature
            );
        } catch (Throwable t) {
            out = new TestRunner9_MultiProcess_batched.LlmMappedSuiteResult();
            out.status = "ERROR";
            out.failureReason = oneLine(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
            return out;
        }
    }

    private static List<TestRunner9_MultiProcess_batched.LlmMappedTest> buildMappedTestsForMethod(Request sample) {
        if (sample == null) {
            return Collections.emptyList();
        }
        Path methodDir = Paths.get(
                sample.resultModuleHome,
                "result",
                sample.targetClassName,
                "traditional_mutants",
                sample.methodSignature
        ).toAbsolutePath().normalize();
        if (!Files.isDirectory(methodDir)) {
            return Collections.emptyList();
        }

        Path classesRoot = Paths.get(sample.resultModuleHome, MutationSystem.TESTSET_MODE_LLMS, "classes")
                .toAbsolutePath().normalize();
        List<TestRunner9_MultiProcess_batched.LlmMappedTest> mappedTests =
                new ArrayList<TestRunner9_MultiProcess_batched.LlmMappedTest>();
        try {
            List<Path> mutantDirs = new ArrayList<Path>();
            for (Path child : Files.newDirectoryStream(methodDir)) {
                if (Files.isDirectory(child)) {
                    mutantDirs.add(child);
                }
            }
            Collections.sort(mutantDirs, new Comparator<Path>() {
                @Override
                public int compare(Path a, Path b) {
                    return a.getFileName().toString().compareTo(b.getFileName().toString());
                }
            });
            for (Path mutantDir : mutantDirs) {
                String mutantName = mutantDir.getFileName().toString();
                String testSetName = buildGeneratedTestFqn(sample.targetClassName, mutantName);
                Path classFile = classesRoot.resolve(testSetName.replace('.', File.separatorChar) + ".class");
                if (!Files.isRegularFile(classFile)) {
                    continue;
                }
                TestRunner9_MultiProcess_batched.LlmMappedTest mapped =
                        new TestRunner9_MultiProcess_batched.LlmMappedTest();
                mapped.testSetName = testSetName;
                mapped.methodSignature = sample.methodSignature;
                mapped.mutantName = mutantName;
                mappedTests.add(mapped);
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return mappedTests;
    }

    private static Map<String, SuiteLevelUpdate> buildSuiteLevelUpdates(Request sample,
                                                                        TestRunner9_MultiProcess_batched.LlmMappedSuiteResult suiteResult) {
        Map<String, SuiteLevelUpdate> updates = new LinkedHashMap<String, SuiteLevelUpdate>();
        if (sample == null) {
            return updates;
        }

        Set<String> killedMutants = new LinkedHashSet<String>();
        Set<String> liveMutants = new LinkedHashSet<String>();
        Map<String, String> mutantResults = new HashMap<String, String>();
        if (suiteResult != null && suiteResult.testResult != null) {
            for (Object o : suiteResult.testResult.killed_mutants) {
                killedMutants.add(String.valueOf(o));
            }
            for (Object o : suiteResult.testResult.live_mutants) {
                liveMutants.add(String.valueOf(o));
            }
            if (suiteResult.testResult.mutant_results != null) {
                Iterator<?> keys = suiteResult.testResult.mutant_results.keySet().iterator();
                while (keys.hasNext()) {
                    Object key = keys.next();
                    Object value = suiteResult.testResult.mutant_results.get(key);
                    mutantResults.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
                }
            }
        }

        Path methodDir = Paths.get(
                sample.resultModuleHome,
                "result",
                sample.targetClassName,
                "traditional_mutants",
                sample.methodSignature
        ).toAbsolutePath().normalize();
        List<String> mutantNames = new ArrayList<String>();
        try {
            if (Files.isDirectory(methodDir)) {
                for (Path child : Files.newDirectoryStream(methodDir)) {
                    if (Files.isDirectory(child)) {
                        mutantNames.add(child.getFileName().toString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(mutantNames);

        for (String mutantName : mutantNames) {
            SuiteLevelUpdate update = new SuiteLevelUpdate();
            update.targetClassName = sample.targetClassName;
            update.methodSignature = sample.methodSignature;
            update.mutantName = mutantName;
            update.primaryTestSetName = buildGeneratedTestFqn(sample.targetClassName, mutantName);
            update.suiteKilledByAnyTest = killedMutants.contains(mutantName);
            boolean live = liveMutants.contains(mutantName);
            update.suiteKillStatus = update.suiteKilledByAnyTest ? "KILLED" : (live ? "LIVE" : "NOT_REPORTED");
            String tests = mutantResults.get(mutantName);
            update.killedByPrimaryTest = update.suiteKilledByAnyTest
                    && tests != null
                    && tests.contains(update.primaryTestSetName);
            update.killedByFallbackTest = update.suiteKilledByAnyTest
                    && !update.killedByPrimaryTest
                    && tests != null
                    && !tests.trim().isEmpty();
            updates.put(update.targetClassName + "##" + update.methodSignature + "##" + update.mutantName, update);
        }
        return updates;
    }

    private static void rewriteResultJsonlWithSuiteFields(Path jsonlFile,
                                                          List<SuiteLevelUpdate> updates) throws Exception {
        if (jsonlFile == null || updates == null || updates.isEmpty() || !Files.isRegularFile(jsonlFile)) {
            return;
        }
        Map<String, SuiteLevelUpdate> updateMap = new HashMap<String, SuiteLevelUpdate>();
        for (SuiteLevelUpdate update : updates) {
            updateMap.put(update.targetClassName + "##" + update.methodSignature + "##" + update.mutantName, update);
        }

        List<String> lines = Files.readAllLines(jsonlFile, StandardCharsets.UTF_8);
        List<String> rewritten = new ArrayList<String>(lines.size());
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            JSONObject obj = new JSONObject(line);
            String key = obj.optString("targetClassName", "") + "##"
                    + obj.optString("methodSignature", "") + "##"
                    + obj.optString("mutantName", "");
            SuiteLevelUpdate update = updateMap.get(key);
            if (update != null) {
                obj.put("suiteKillStatus", safe(update.suiteKillStatus));
                obj.put("suiteKilledByAnyTest", update.suiteKilledByAnyTest);
                obj.put("killedByPrimaryTest", update.killedByPrimaryTest);
                obj.put("killedByFallbackTest", update.killedByFallbackTest);
            }
            rewritten.add(obj.toString());
        }
        Files.write(jsonlFile,
                (String.join(System.lineSeparator(), rewritten) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    public static Result generateCompileAndRun(Request request) throws Exception {
        return generateAndCompile(request);
    }

    private static final class PreparedTask {
        final Request request;
        final EvidenceState state;
        final EvidenceAction action;
        final PromptEvidence selectedEvidence;
        final GenerationSeed generationSeed;
        final JSONObject fullJson;
        final Result preparationFailure;

        private PreparedTask(Request request,
                             EvidenceState state,
                             EvidenceAction action,
                             PromptEvidence selectedEvidence,
                             GenerationSeed generationSeed,
                             JSONObject fullJson,
                             Result preparationFailure) {
            this.request = request;
            this.state = state;
            this.action = action;
            this.selectedEvidence = selectedEvidence;
            this.generationSeed = generationSeed;
            this.fullJson = fullJson;
            this.preparationFailure = preparationFailure;
        }

        static PreparedTask ready(Request request,
                                  EvidenceState state,
                                  EvidenceAction action,
                                  PromptEvidence selectedEvidence,
                                  GenerationSeed generationSeed,
                                  JSONObject fullJson) {
            return new PreparedTask(request, state, action, selectedEvidence, generationSeed, fullJson, null);
        }

        static PreparedTask failed(Request request,
                                   EvidenceState state,
                                   EvidenceAction action,
                                   Result preparationFailure) {
            return new PreparedTask(request, state, action, null, null, null, preparationFailure);
        }
    }

    private static final class GenerationSeed {
        Result result;
        CachedLlmClient client;
        Request request;
        PromptEvidence evidence;
        GenerationOptions options;
        String projectCp;
        String testSetName;
        Path javaFile;
        Path testClassesRoot;
        Path reportRoot;
        String artifactBaseName;
        PromptBudgetProfile initialProfile;
        String candidateCode;
        String lastCompileError;
        LlmCallResult lastLlmResult;
        int rounds;
        boolean terminal;

        static GenerationSeed terminal(Result result) {
            GenerationSeed seed = new GenerationSeed();
            seed.result = result;
            seed.terminal = true;
            return seed;
        }
    }

    private static final class TaskOutcome {
        final Result result;
        final EvidenceState state;
        final EvidenceAction action;
        final double reward;

        TaskOutcome(Result result, EvidenceState state, EvidenceAction action, double reward) {
            this.result = result;
            this.state = state;
            this.action = action;
            this.reward = reward;
        }
    }

    private static final class SuiteLevelUpdate {
        String targetClassName;
        String methodSignature;
        String mutantName;
        String primaryTestSetName;
        String suiteKillStatus;
        boolean suiteKilledByAnyTest;
        boolean killedByPrimaryTest;
        boolean killedByFallbackTest;
    }

    private static final class BatchTargetEntry {
        int rowIndex = -1;
        String targetClassName = "";
        String methodSignature = "";
        String mutantName = "";
        String testSetName = "";
        boolean compiled;
        String mappingStatus = "";
        boolean originalPassed;
        boolean mutantExecuted;
        boolean targetKilled;
        String targetStatus = "";
        String targetFailureReason = "";
        String javaFile = "";
        String classFile = "";
        String excelIsKilled = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("rowIndex", rowIndex);
            o.put("targetClassName", safe(targetClassName));
            o.put("methodSignature", safe(methodSignature));
            o.put("mutantName", safe(mutantName));
            o.put("testSetName", safe(testSetName));
            o.put("compiled", compiled);
            o.put("mappingStatus", safe(mappingStatus));
            o.put("originalPassed", originalPassed);
            o.put("mutantExecuted", mutantExecuted);
            o.put("targetKilled", targetKilled);
            o.put("targetStatus", safe(targetStatus));
            o.put("failureReason", safe(targetFailureReason));
            o.put("javaFile", safe(javaFile));
            o.put("classFile", safe(classFile));
            o.put("excelIsKilled", safe(excelIsKilled));
            return o;
        }
    }

    private static final class BatchExecutionReport {
        String sourceModuleHome = "";
        String resultModuleHome = "";
        String targetClassName = "";
        String methodSignature = "";
        int timeoutMillis;

        int methodCount;
        int generatedMutantCount;
        int generatedTestCount;
        int compiledTestCount;
        int executableCompiledTestCount;
        int targetExecutedTestCount;
        int targetKilledCount;
        double targetKillScore;

        String suiteStatus = "OK";
        String suiteFailureReason = "";
        int suiteKilledCount;
        int suiteLiveCount;
        double suiteMutantScore;
        int suitePrimaryRuns;
        int suiteFallbackRuns;
        int suiteOriginalSuccessCount;
        int suiteOriginalFailureCount;
        int suiteMutantsWithoutMappedTest;
        long suiteElapsedMillis;
        int suiteTimeoutRuns;
        int suiteTimeoutMutants;

        final List<BatchTargetEntry> testEntries = new ArrayList<BatchTargetEntry>();
        final List<String> suiteTestSetNames = new ArrayList<String>();
        final List<String> suiteKilledMutants = new ArrayList<String>();
        final List<String> suiteLiveMutants = new ArrayList<String>();
        final LinkedHashMap<String, String> suiteTestResults = new LinkedHashMap<String, String>();
        final LinkedHashMap<String, String> suiteMutantResults = new LinkedHashMap<String, String>();

        JSONObject toJson() {
            JSONObject o = toSuiteJson();
            JSONArray arr = new JSONArray();
            for (BatchTargetEntry e : testEntries) {
                arr.put(e.toJson());
            }
            o.put("targetLevelEntries", arr);
            return o;
        }

        JSONObject toSuiteJson() {
            JSONObject o = new JSONObject();
            o.put("sourceModuleHome", safe(sourceModuleHome));
            o.put("resultModuleHome", safe(resultModuleHome));
            o.put("targetClassName", safe(targetClassName));
            o.put("methodSignature", safe(methodSignature));
            o.put("executionGranularity", "method");
            o.put("sameMethodFallback", true);
            o.put("timeoutMillis", timeoutMillis);
            o.put("methodCount", methodCount);
            o.put("generatedMutantCount", generatedMutantCount);
            o.put("generatedTestCount", generatedTestCount);
            o.put("compiledTestCount", compiledTestCount);
            o.put("executableCompiledTestCount", executableCompiledTestCount);
            o.put("targetExecutedTestCount", targetExecutedTestCount);
            o.put("targetKilledCount", targetKilledCount);
            o.put("targetKillScore", targetKillScore);
            o.put("suiteStatus", safe(suiteStatus));
            o.put("suiteFailureReason", safe(suiteFailureReason));
            o.put("suiteTestSetNames", new JSONArray(suiteTestSetNames));
            o.put("suiteKilledCount", suiteKilledCount);
            o.put("suiteLiveCount", suiteLiveCount);
            o.put("suiteMutantScore", suiteMutantScore);
            o.put("suiteKilledMutants", new JSONArray(suiteKilledMutants));
            o.put("suiteLiveMutants", new JSONArray(suiteLiveMutants));
            o.put("suiteTestResults", new JSONObject(suiteTestResults));
            o.put("suiteMutantResults", new JSONObject(suiteMutantResults));
            o.put("suitePrimaryRuns", suitePrimaryRuns);
            o.put("suiteFallbackRuns", suiteFallbackRuns);
            o.put("suiteOriginalSuccessCount", suiteOriginalSuccessCount);
            o.put("suiteOriginalFailureCount", suiteOriginalFailureCount);
            o.put("suiteMutantsWithoutMappedTest", suiteMutantsWithoutMappedTest);
            o.put("suiteElapsedMillis", suiteElapsedMillis);
            o.put("suiteTimeoutRuns", suiteTimeoutRuns);
            o.put("suiteTimeoutMutants", suiteTimeoutMutants);
            return o;
        }

        String toConsoleString() {
            StringBuilder sb = new StringBuilder();
            sb.append("==================================================\n");
            sb.append("[LLM EXECUTION SUMMARY]\n");
            sb.append("targetClassName             = ").append(targetClassName).append('\n');
            sb.append("methodSignature             = ").append(methodSignature).append('\n');
            sb.append("sourceModuleHome            = ").append(sourceModuleHome).append('\n');
            sb.append("resultModuleHome            = ").append(resultModuleHome).append('\n');
            sb.append("methodCount                 = ").append(methodCount).append('\n');
            sb.append("generatedMutantCount        = ").append(generatedMutantCount).append('\n');
            sb.append("generatedTestCount          = ").append(generatedTestCount).append('\n');
            sb.append("compiledTestCount           = ").append(compiledTestCount).append('\n');
            sb.append("executableCompiledTestCount = ").append(executableCompiledTestCount).append('\n');
            sb.append("targetExecutedTestCount     = ").append(targetExecutedTestCount).append('\n');
            sb.append("targetKilledCount           = ").append(targetKilledCount).append('\n');
            sb.append("targetKillScore             = ").append(String.format(Locale.ROOT, "%.2f", targetKillScore)).append("%\n");
            sb.append("suiteStatus                 = ").append(suiteStatus).append('\n');
            if (!isBlank(suiteFailureReason)) {
                sb.append("suiteFailureReason          = ").append(suiteFailureReason).append('\n');
            }
            sb.append("suiteKilledCount            = ").append(suiteKilledCount).append('\n');
            sb.append("suiteLiveCount              = ").append(suiteLiveCount).append('\n');
            sb.append("suiteMutantScore            = ").append(String.format(Locale.ROOT, "%.2f", suiteMutantScore)).append("%\n");
            sb.append("suitePrimaryRuns            = ").append(suitePrimaryRuns).append('\n');
            sb.append("suiteFallbackRuns           = ").append(suiteFallbackRuns).append('\n');
            sb.append("suiteOriginalSuccessCount   = ").append(suiteOriginalSuccessCount).append('\n');
            sb.append("suiteOriginalFailureCount   = ").append(suiteOriginalFailureCount).append('\n');
            sb.append("suiteMutantsWithoutMappedTest= ").append(suiteMutantsWithoutMappedTest).append('\n');
            sb.append("suiteTimeoutRuns            = ").append(suiteTimeoutRuns).append('\n');
            sb.append("suiteTimeoutMutants         = ").append(suiteTimeoutMutants).append('\n');
            sb.append("suiteElapsedMillis          = ").append(suiteElapsedMillis).append('\n');
            sb.append("reportDir                   = ").append(getExecutionReportDir(resultModuleHome, targetClassName, methodSignature)).append('\n');
            return sb.toString();
        }
    }

    public static final class GenerationOptions {
        final String artifactSuffix;
        final String promptPhase;
        final String initialPromptOverride;
        final boolean forceFreshInitialCall;
        final String generationStrategy;
        final boolean referenceGuided;
        final int referenceCount;
        final int regenerationRound;
        final List<SuccessTestReference> historicalReferences;

        private GenerationOptions(String artifactSuffix,
                                  String promptPhase,
                                  String initialPromptOverride,
                                  boolean forceFreshInitialCall,
                                  String generationStrategy,
                                  boolean referenceGuided,
                                  int referenceCount,
                                  int regenerationRound,
                                  List<SuccessTestReference> historicalReferences) {
            this.artifactSuffix = artifactSuffix;
            this.promptPhase = promptPhase;
            this.initialPromptOverride = initialPromptOverride;
            this.forceFreshInitialCall = forceFreshInitialCall;
            this.generationStrategy = generationStrategy;
            this.referenceGuided = referenceGuided;
            this.referenceCount = referenceCount;
            this.regenerationRound = regenerationRound;
            this.historicalReferences = historicalReferences == null
                    ? Collections.<SuccessTestReference>emptyList()
                    : Collections.unmodifiableList(new ArrayList<SuccessTestReference>(historicalReferences));
        }

        static GenerationOptions initial() {
            return initial(Collections.<SuccessTestReference>emptyList());
        }

        static GenerationOptions initial(List<SuccessTestReference> historicalReferences) {
            boolean guided = historicalReferences != null && !historicalReferences.isEmpty();
            return new GenerationOptions(
                    "",
                    "initial",
                    null,
                    false,
                    guided ? "history-guided-initial" : "initial",
                    guided,
                    guided ? historicalReferences.size() : 0,
                    0,
                    historicalReferences);
        }

        static GenerationOptions regeneration(int round,
                                              String prompt,
                                              int referenceCount,
                                              boolean referenceGuided,
                                              String strategyName,
                                              List<SuccessTestReference> historicalReferences) {
            String strategy = isBlank(strategyName)
                    ? (referenceGuided ? "reference-regeneration" : "exploration-regeneration")
                    : strategyName;
            return new GenerationOptions(
                    "regen_" + round,
                    "regeneration-" + round,
                    prompt,
                    true,
                    strategy,
                    referenceGuided,
                    referenceCount,
                    round,
                    historicalReferences
            );
        }
    }
}
