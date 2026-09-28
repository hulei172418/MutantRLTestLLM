package mujava.testgenerator;

import mujava.util.ManagedProcessCleanup;
import mujava.util.ExcelUtils;
import mujava.testgenerator.tools.Result;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class LLMTestGeneratorBatchLoop {

    private static final String STABLE_RUNTIME_FLAG = "mutestllm.loop.stableRuntime";
    private static final String STOP_MARKER_FILE = "stop.requested";
    private static final String DEFAULT_RESUME_RUN_DIR = "logs/auto-loop/20260802120348447";
    private static final int DEFAULT_RESUME_FROM_ROUND = 2;
    private static final int DEFAULT_RESUME_FEEDBACK_ROUND = 1;
    private static final boolean DEFAULT_RESUME_MODE = false;
    private static final int ROWS_ARG_INLINE_LIMIT = 20;
    private static final String LOOP_PROPERTIES_RESOURCE = "llm.properties";

    private static final String DEFAULT_EVIDENCE_JAVA_HOME = "D:\\software\\java\\java-17\\jdk";
    private static final String DEFAULT_EXCEL = "./data/oot-2.xlsx";
    private static final DateTimeFormatter AUTO_LOOP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Set<String> COMPILED_MODULES = new HashSet<String>();
    private static final Map<String, String> RUNTIME_CLASSPATH_CACHE = new HashMap<String, String>();
    private static final Map<String, List<String>> WORKSPACE_RUNTIME_DEPENDENCIES = buildWorkspaceRuntimeDependencies();

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
                    "commons-numbers-quaternion")));

    private LLMTestGeneratorBatchLoop() {
    }

    public static void main(String[] args) throws Exception {
        Args bootstrapArgs = Args.parse(args);
        if (relaunchWithStableRuntimeIfNeeded(args, bootstrapArgs)) {
            return;
        }
        verifyCriticalLoopInternals();
        Args a = bootstrapArgs;
        if (ManagedProcessCleanup.currentProcessGroupId().isEmpty()) {
            System.setProperty(ManagedProcessCleanup.PROCESS_GROUP_PROPERTY, a.processGroupId);
        }
        Files.createDirectories(a.runDir);
        configureStopControl(a, !a.internalRoundRunner);
        Path report = a.runDir.resolve("closed-loop-report.jsonl");
        try {
            if (a.internalRoundRunner) {
                runRoundRunner(a, report);
                ManagedProcessCleanup.cleanupCurrentProcessGroup("loop-round-runner-done");
                System.out.println("[AUTO-LOOP] report=" + report.toAbsolutePath());
                return;
            }
            runIsolatedLauncher(a, report);
            ManagedProcessCleanup.cleanupCurrentProcessGroup("loop-launcher-done");
            System.out.println("[AUTO-LOOP] report=" + report.toAbsolutePath());
        } catch (StopRequestedException stop) {
            logReport(report, "STOP", stop.round,
                    "Stop requested: " + safeMessage(stop.getMessage()));
            ManagedProcessCleanup.requestStopAndCleanupCurrentProcessGroup("loop-stop-requested");
            System.out.println("[AUTO-LOOP] stop requested, cleanup triggered. report=" + report.toAbsolutePath());
        }
    }

    private static void runIsolatedLauncher(Args a, Path report) throws Exception {
        if (a.resumeMode) {
            runResumeLauncher(a, report);
            return;
        }
        logReport(report, "START", 0, "runDir=" + a.runDir + ", excel=" + a.excel
                + ", rowStart=" + a.rowStart + ", rowEnd=" + a.rowEnd
                + ", evidenceJavaHome=" + a.evidenceJavaHome
                + ", processGroupId=" + ManagedProcessCleanup.currentProcessGroupId());

        ensureCodeKbReady(a, report);

        if (a.generateEvidenceFirst) {
            runEvidence(a, report, 0, null);
        }

        List<Integer> activeRows = null;
        boolean pendingFinalVerification = false;
        for (int round = 1; round <= a.maxRounds; round++) {
            boolean verificationOnly = pendingFinalVerification && round == a.maxRounds;
            launchRoundProcess(a, report, round, activeRows, false, verificationOnly);
            RoundCheckpoint checkpoint = ensureRoundCheckpointRecovered(a, report, round);
            FeedbackStats stats = checkpoint.toFeedbackStats();
            if (!stats.hasAnyActionableLoopWork()) {
                logReport(report, "STOP", round,
                        "No actionable import/kill-oriented feedback. Cross-stage loop stops.");
                break;
            }

            if (verificationOnly) {
                logReport(report, "STOP", round,
                        "Reached configured final verification round after prior CodeKB update.");
                break;
            }

            activeRows = stats.prioritizedRows();
            pendingFinalVerification = a.verifyLastRepair
                    && round == a.maxRounds - 1
                    && checkpoint.actionableCodeKbEvents > 0;

            if (round == a.maxRounds) {
                logReport(report, "STOP", round, "Reached configured maxRounds.");
                break;
            }
        }

        logReport(report, "DONE", 0, "changed=" + hasAnyRoundCheckpointWithIngest(a.runDir, a.maxRounds));
        logLoopStats(a, report, 0, "FINAL_STATS");
    }

    private static boolean relaunchWithStableRuntimeIfNeeded(String[] args, Args bootstrapArgs) throws Exception {
        if ("1".equals(System.getProperty(STABLE_RUNTIME_FLAG))) {
            return false;
        }
        String classPath = System.getProperty("java.class.path", "");
        if (!looksLikeEphemeralIdeClasspath(classPath)) {
            return false;
        }
        List<String> command = moduleMavenBase("MuTestLLM");
        command.add("-Dexec.mainClass=mujava.testgenerator.LLMTestGeneratorBatchLoop");
        command.add("-Dexec.args=" + joinExecArgsWithResolvedRunDir(args, bootstrapArgs));
        command.add("-D" + STABLE_RUNTIME_FLAG + "=1");
        command.add("-D" + ManagedProcessCleanup.PROCESS_GROUP_ANCHOR_PID_PROPERTY + "="
                + ManagedProcessCleanup.currentProcessPid());
        command.add("-D" + ManagedProcessCleanup.PROCESS_GROUP_STOP_FILE_PROPERTY + "="
                + normalizeForProperty(stopMarkerPath(bootstrapArgs.runDir)));
        command.add("exec:java");
        int exit = runForeground(command);
        if (exit != 0) {
            throw new IllegalStateException(
                    "Stable runtime relaunch failed with exit code " + exit
                            + ". Original classpath looked like an IDE temp launch: " + classPath);
        }
        return true;
    }

    private static boolean looksLikeEphemeralIdeClasspath(String classPath) {
        if (classPath == null || classPath.trim().isEmpty()) {
            return false;
        }
        String normalized = classPath.toLowerCase(Locale.ROOT).replace('/', '\\');
        return normalized.contains("\\appdata\\local\\temp\\cp_")
                || normalized.contains("\\vscode\\")
                || normalized.contains("\\redhat.java\\")
                || normalized.contains("org.eclipse.jdt")
                || normalized.contains("jdt.ls");
    }

    private static String joinExecArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteExecArg(args[i]));
        }
        return sb.toString();
    }

    private static String joinExecArgsWithResolvedRunDir(String[] args, Args bootstrapArgs) {
        String joined = joinExecArgs(args);
        if (containsOption(args, "--run-dir") || containsOption(args, "--resume-run-dir")) {
            return joined;
        }
        String resolvedRunDir = quoteExecArg(bootstrapArgs.runDir.toString());
        if (joined.isEmpty()) {
            return "--run-dir " + resolvedRunDir;
        }
        return joined + " --run-dir " + resolvedRunDir;
    }

    private static boolean containsOption(String[] args, String option) {
        if (args == null || option == null || option.trim().isEmpty()) {
            return false;
        }
        for (String arg : args) {
            if (option.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String quoteExecArg(String arg) {
        String value = arg == null ? "" : arg;
        if (value.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || ch == '"' || ch == '\'') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int runForeground(List<String> command) throws Exception {
        ManagedProcessCleanup.addProcessGroupProperty(command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Paths.get("").toAbsolutePath().toFile());
        pb.redirectErrorStream(true);
        Process process = ManagedProcessCleanup.register(pb.start());
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            return process.waitFor();
        } finally {
            ManagedProcessCleanup.unregister(process);
            ManagedProcessCleanup.destroy(process, 250L);
        }
    }

    private static void configureStopControl(Args a, boolean resetMarker) throws IOException {
        Path stopMarker = stopMarkerPath(a.runDir);
        Path parent = stopMarker.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        System.setProperty(ManagedProcessCleanup.PROCESS_GROUP_STOP_FILE_PROPERTY,
                normalizeForProperty(stopMarker));
        if (resetMarker) {
            Files.deleteIfExists(stopMarker);
        }
    }

    private static Path stopMarkerPath(Path runDir) {
        return runDir.resolve(STOP_MARKER_FILE).toAbsolutePath().normalize();
    }

    private static boolean isStopRequested(Path runDir) {
        return runDir != null && Files.isRegularFile(stopMarkerPath(runDir));
    }

    private static void throwIfStopRequested(Path runDir, Path report, int round, String stage) throws IOException, StopRequestedException {
        if (!isStopRequested(runDir)) {
            return;
        }
        if (report != null) {
            logReport(report, "STOP_REQUESTED", round,
                    stage + " observed " + stopMarkerPath(runDir).toAbsolutePath());
        }
        throw new StopRequestedException(round, "stop marker observed before " + stage);
    }

    private static void verifyCriticalLoopInternals() {
        try {
            FeedbackStats stats = new FeedbackStats();
            if (!stats.prioritizedRows().isEmpty()) {
                throw new IllegalStateException("FeedbackStats self-check produced unexpected rows.");
            }
            LoopStats loopStats = new LoopStats();
            loopStats.summaryLine();
        } catch (Error error) {
            throw new IllegalStateException(
                    "Critical loop internals failed self-check before round-1. "
                            + "This usually means the IDE launched a stale or partially compiled class.",
                    error);
        }
    }

    private static void runResumeLauncher(Args a, Path report) throws Exception {
        throwIfStopRequested(a.runDir, report, 0, "resume-launcher");
        ResumeContext context = determineResumeContext(a);
        logReport(report, "RESUME_START", context.round,
                "runDir=" + a.runDir
                        + ", excel=" + a.excel
                        + ", mode=" + context.mode
                        + ", round=" + context.round
                        + ", activeRows=" + context.activeRows.size()
                        + ", skipEvidence=" + a.resumeSkipEvidence);
        if (context.seedStats != null) {
            logReport(report, "RESUME_FEEDBACK", context.round, context.seedStats.toJson().toString());
        }
        if (context.stopImmediately) {
            logReport(report, "STOP", context.round, context.stopReason);
            logLoopStats(a, report, 0, "FINAL_STATS");
            return;
        }
        List<Integer> activeRows = new ArrayList<Integer>(context.activeRows);
        int currentRound = context.round;
        boolean resumeNeedsPriorEvidence = context.resumeNeedsPriorEvidence;
        while (currentRound <= a.maxRounds + 1) {
            boolean verificationOnly = currentRound > a.maxRounds;
            launchRoundProcess(a, report, currentRound, activeRows, resumeNeedsPriorEvidence, verificationOnly);
            RoundCheckpoint checkpoint = ensureRoundCheckpointRecovered(a, report, currentRound);
            FeedbackStats stats = checkpoint.toFeedbackStats();
            if (!stats.hasAnyActionableLoopWork()) {
                logReport(report, "STOP", currentRound,
                        "No actionable import/kill-oriented feedback. Resume loop stops.");
                break;
            }

            if (verificationOnly) {
                logReport(report, "STOP", currentRound,
                        "Final verification still has actionable rows; resume maxRounds reached.");
                break;
            }

            activeRows = stats.prioritizedRows();
            boolean codeKbUpdatedThisRound = checkpoint.actionableCodeKbEvents > 0;

            if (currentRound == a.maxRounds) {
                if (a.verifyLastRepair && codeKbUpdatedThisRound) {
                    resumeNeedsPriorEvidence = false;
                    currentRound++;
                    continue;
                } else if (a.verifyLastRepair) {
                    logReport(report, "STAGE_SKIP", currentRound,
                            "final-verification skipped because this round produced no CodeKB update");
                    logReport(report, "STOP", currentRound,
                            "Reached maxRounds without a final CodeKB update during resume.");
                    break;
                }
                logReport(report, "STOP", currentRound, "Reached maxRounds during resume.");
                break;
            }
            resumeNeedsPriorEvidence = false;
            currentRound++;
        }

        logReport(report, "DONE", 0, "resume=true, changed=" + hasAnyRoundCheckpointWithIngest(a.runDir, a.maxRounds));
        logLoopStats(a, report, 0, "FINAL_STATS");
    }

    private static void runFinalVerification(Args a,
            Path report,
            int verificationRound,
            List<Integer> activeRows) throws Exception {
        Path roundDir = a.runDir.resolve("round-" + verificationRound);
        Files.createDirectories(roundDir);
        Path feedback = roundDir.resolve("failure-feedback.jsonl");
        Files.deleteIfExists(feedback);

        logReport(report, "FINAL_VERIFY_START", verificationRound,
                "Running one extra LLM verification pass after the last CodeKB ingest.");
        int llmExit = runLlm(a, feedback, report, verificationRound, activeRows);
        if (llmExit != 0 && !a.continueOnLlmFailure) {
            throw new IllegalStateException("Final verification LLM run failed with exit code " + llmExit);
        }
        FeedbackStats stats = analyzeFeedback(feedback, a.minConfidence);
        RoundCheckpoint checkpoint = loadRoundCheckpoint(a.runDir, verificationRound);
        checkpoint.llmDone = true;
        applyStatsToCheckpoint(checkpoint, stats);
        checkpoint.feedbackAnalyzed = true;
        saveRoundCheckpoint(a.runDir, checkpoint);
        logReport(report, "FINAL_VERIFY_FEEDBACK", verificationRound, stats.toJson().toString());
        logLoopStats(a, report, verificationRound, "FINAL_VERIFY_STATS");
    }

    private static void runRoundRunner(Args a, Path report) throws Exception {
        throwIfStopRequested(a.runDir, report, a.internalTargetRound > 0 ? a.internalTargetRound : 0, "round-runner");
        if (a.internalTargetRound <= 0) {
            throw new IllegalArgumentException("internal round runner requires --internal-target-round");
        }
        List<Integer> activeRows = a.internalUseActiveRows
                ? readRowsFile(a.internalActiveRowsFile)
                : null;
        if (a.internalVerificationOnly) {
            runFinalVerification(a, report, a.internalTargetRound, activeRows);
            return;
        }

        int round = a.internalTargetRound;
        Path roundDir = a.runDir.resolve("round-" + round);
        Files.createDirectories(roundDir);
        Path feedback = roundDir.resolve("failure-feedback.jsonl");
        RoundCheckpoint checkpoint = loadRoundCheckpoint(a.runDir, round);

        if (a.internalResumeNeedsPriorEvidence && round > 1 && !a.resumeSkipEvidence) {
            int priorRound = round - 1;
            RoundCheckpoint priorCheckpoint = loadRoundCheckpoint(a.runDir, priorRound);
            if (!priorCheckpoint.evidenceDone) {
                runEvidence(a, report, priorRound, activeRows);
                priorCheckpoint.evidenceDone = true;
                saveRoundCheckpoint(a.runDir, priorCheckpoint);
            }
        }

        if (!checkpoint.llmDone) {
            Files.deleteIfExists(feedback);
            int llmExit = runLlm(a, feedback, report, round, activeRows);
            checkpoint.llmDone = true;
            saveRoundCheckpoint(a.runDir, checkpoint);
            if (llmExit != 0 && !a.continueOnLlmFailure) {
                checkpoint.stopReason = "MuTestLLM exited with code " + llmExit;
                saveRoundCheckpoint(a.runDir, checkpoint);
                logReport(report, "STOP", round, "MuTestLLM exited with code " + llmExit);
                return;
            }
        }

        FeedbackStats stats = checkpoint.feedbackAnalyzed && checkpoint.hasStats()
                ? checkpoint.toFeedbackStats()
                : analyzeFeedback(feedback, a.minConfidence);
        if (!checkpoint.feedbackAnalyzed) {
            applyStatsToCheckpoint(checkpoint, stats);
            checkpoint.feedbackAnalyzed = true;
            saveRoundCheckpoint(a.runDir, checkpoint);
            logReport(report, "FEEDBACK", round, stats.toJson().toString());
            logLoopStats(a, report, round, "ROUND_STATS");
        }
        if (!stats.hasAnyActionableLoopWork()) {
            checkpoint.stopReason = "No actionable import/kill-oriented feedback. Cross-stage loop stops.";
            saveRoundCheckpoint(a.runDir, checkpoint);
            return;
        }

        boolean codeKbUpdatedThisRound = stats.actionableCodeKbEvents > 0;
        if (codeKbUpdatedThisRound && !checkpoint.feedbackIngestDone) {
            runFeedbackIngest(a, feedback, report, round);
            checkpoint.feedbackIngestDone = true;
            saveRoundCheckpoint(a.runDir, checkpoint);
        }
        List<Integer> prioritizedRows = stats.prioritizedRows();
        if (round == a.maxRounds) {
            if (a.verifyLastRepair && codeKbUpdatedThisRound && !checkpoint.evidenceDone) {
                runEvidence(a, report, round, prioritizedRows);
                checkpoint.evidenceDone = true;
                saveRoundCheckpoint(a.runDir, checkpoint);
            } else if (!checkpoint.evidenceDone) {
                checkpoint.evidenceDone = true;
                saveRoundCheckpoint(a.runDir, checkpoint);
            }
            return;
        }
        if (codeKbUpdatedThisRound) {
            if (!checkpoint.evidenceDone) {
                runEvidence(a, report, round, prioritizedRows);
                checkpoint.evidenceDone = true;
                saveRoundCheckpoint(a.runDir, checkpoint);
            }
        } else if (!checkpoint.evidenceDone) {
            checkpoint.evidenceDone = true;
            saveRoundCheckpoint(a.runDir, checkpoint);
            logReport(report, "STAGE_SKIP", round,
                    "evidence-parse skipped because this round produced no CodeKB update");
        }
    }

    private static void launchRoundProcess(Args a,
            Path report,
            int round,
            List<Integer> activeRows,
            boolean resumeNeedsPriorEvidence,
            boolean verificationOnly) throws Exception {
        throwIfStopRequested(a.runDir, report, round, verificationOnly ? "final-verification-launch" : "round-launch");
        ensureModuleCompiled("MuTestLLM", report, round);
        String runtimeClasspath = buildRuntimeClasspath("MuTestLLM", report, round);
        boolean useActiveRows = activeRows != null;
        Path rowsFile = useActiveRows ? writeLauncherRowsFile(a.runDir, round, activeRows) : null;
        String execArgs = buildRoundRunnerArgs(a, round, rowsFile, useActiveRows, resumeNeedsPriorEvidence,
                verificationOnly);
        List<String> cmd = javaCommand(runtimeClasspath, "mujava.testgenerator.LLMTestGeneratorBatchLoop", execArgs);
        propagateLoopLauncherProperties(cmd);
        int exit = runStage(verificationOnly ? "loop-final-verify-runner" : "loop-round-runner",
                cmd,
                a.runDir.resolve("round-" + round).resolve(
                        verificationOnly ? "loop-final-verify-runner.log" : "loop-round-runner.log"),
                report,
                round,
                !a.continueOnLlmFailure);
        if (exit != 0 && a.continueOnLlmFailure) {
            Path roundDir = a.runDir.resolve("round-" + round);
            if (Files.isRegularFile(roundDir.resolve("failure-feedback.jsonl"))
                    || Files.isRegularFile(roundDir.resolve("round-checkpoint.json"))
                    || Files.isRegularFile(roundDir.resolve("mutestllm.log"))
                    || Files.isRegularFile(roundDir.resolve("mutestllm-rows.log"))) {
                logReport(report, "STAGE_TOLERATED", round,
                        (verificationOnly ? "loop-final-verify-runner" : "loop-round-runner")
                                + " exited=" + exit
                                + " but produced round artifacts; continuing analysis.");
                return;
            }
            throw new IllegalStateException("Round runner failed with exit code " + exit + " for round " + round);
        }
    }

    private static void propagateLoopLauncherProperties(List<String> cmd) {
        propagateSystemProperty(cmd, STABLE_RUNTIME_FLAG);
        propagateSystemProperty(cmd, "mujava.config.path");
        propagateSystemProperty(cmd, "mujava.libraries.path");
        propagateSystemProperty(cmd, "loop.evidence.java.home");
        propagateSystemProperty(cmd, "llm.threads");
        propagateSystemProperty(cmd, "llm.pipeline.enabled");
        propagateSystemProperty(cmd, "llm.pipeline.generation.threads");
        propagateSystemProperty(cmd, "llm.pipeline.post.threads");
        propagateSystemProperty(cmd, "llm.max.repair.rounds");
        propagateSystemProperty(cmd, "llm.api.max.attempts");
        propagateSystemProperty(cmd, "mujava.max.workers");
        propagateSystemProperty(cmd, "mujava.max.inflight.multiplier");
        propagateSystemProperty(cmd, "mujava.worker.xms");
        propagateSystemProperty(cmd, "mujava.worker.xmx");
    }

    private static Path writeLauncherRowsFile(Path runDir, int round, List<Integer> activeRows) throws IOException {
        Path rowsFile = runDir.resolve("round-" + round).resolve("launcher-active-rows.txt");
        writeRowsFile(rowsFile, activeRows == null ? Collections.<Integer>emptyList() : activeRows);
        return rowsFile;
    }

    private static String buildRoundRunnerArgs(Args a,
            int round,
            Path rowsFile,
            boolean useActiveRows,
            boolean resumeNeedsPriorEvidence,
            boolean verificationOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append("--workspace ").append(quoteArg(a.workspaceRoot.toString()));
        sb.append(" --excel ").append(quoteArg(a.excel.toString()));
        sb.append(" --run-dir ").append(quoteArg(a.runDir.toString()));
        sb.append(" --max-rounds ").append(a.maxRounds);
        sb.append(" --min-confidence ").append(a.minConfidence);
        sb.append(" --row-start ").append(a.rowStart);
        sb.append(" --row-end ").append(a.rowEnd);
        sb.append(" --build-kb-first ").append(a.buildKbFirst);
        sb.append(" --rebuild-kb ").append(a.rebuildKb);
        sb.append(" --verify-last-repair ").append(a.verifyLastRepair);
        sb.append(" --generate-evidence-first ").append(a.generateEvidenceFirst);
        sb.append(" --continue-on-llm-failure ").append(a.continueOnLlmFailure);
        sb.append(" --resume-mode ").append(a.resumeMode);
        sb.append(" --resume-skip-evidence ").append(a.resumeSkipEvidence);
        sb.append(" --resume-from-round ").append(a.resumeFromRound);
        sb.append(" --resume-feedback-round ").append(a.resumeFeedbackRound);
        sb.append(" --evidence-mode ").append(quoteArg(a.evidenceMode));
        sb.append(" --evidence-workers ").append(a.evidenceWorkers);
        if (a.evidenceJavaHome != null) {
            sb.append(" --evidence-java-home ").append(quoteArg(a.evidenceJavaHome.toString()));
        }
        sb.append(" --internal-round-runner true");
        sb.append(" --internal-target-round ").append(round);
        sb.append(" --internal-use-active-rows ").append(useActiveRows);
        if (useActiveRows && rowsFile != null) {
            sb.append(" --internal-active-rows-file ").append(quoteArg(rowsFile.toString()));
        }
        sb.append(" --internal-resume-needs-prior-evidence ").append(resumeNeedsPriorEvidence);
        sb.append(" --internal-verification-only ").append(verificationOnly);
        return sb.toString();
    }

    private static List<Integer> readRowsFile(Path rowsFile) throws IOException {
        List<Integer> rows = new ArrayList<Integer>();
        if (rowsFile == null || !Files.isRegularFile(rowsFile)) {
            return rows;
        }
        List<String> lines = Files.readAllLines(rowsFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            rows.add(Integer.valueOf(Integer.parseInt(trimmed)));
        }
        return rows;
    }

    private static boolean hasAnyRoundCheckpointWithIngest(Path runDir, int maxRounds) throws IOException {
        for (int round = 1; round <= maxRounds; round++) {
            if (loadRoundCheckpoint(runDir, round).feedbackIngestDone) {
                return true;
            }
        }
        return false;
    }

    private static ResumeContext determineResumeContext(Args a) throws Exception {
        for (int round = 1; round <= a.maxRounds; round++) {
            RoundCheckpoint checkpoint = loadRoundCheckpoint(a.runDir, round);
            if (!checkpoint.hasAnySignal()) {
                continue;
            }
            if (!checkpoint.llmDone) {
                return ResumeContext.resumeRound(round, checkpoint.prioritizedRows(), null, false, "round-stage");
            }
            if (!checkpoint.feedbackAnalyzed) {
                return ResumeContext.resumeRound(round, checkpoint.prioritizedRows(), null, false, "round-stage");
            }
            FeedbackStats stats = checkpoint.toFeedbackStats();
            if (!stats.hasAnyActionableLoopWork()) {
                return ResumeContext.stop(round,
                        firstNonBlank(checkpoint.stopReason,
                                "Resume target round already reached a terminal non-actionable state."));
            }
            if (checkpoint.actionableCodeKbEvents > 0 && !checkpoint.feedbackIngestDone) {
                return ResumeContext.resumeRound(round, stats.prioritizedRows(), stats, false, "round-stage");
            }
            if (round == a.maxRounds) {
                if (a.verifyLastRepair && checkpoint.actionableCodeKbEvents > 0 && !checkpoint.evidenceDone) {
                    return ResumeContext.resumeRound(round, stats.prioritizedRows(), stats, false, "round-stage");
                }
                if (a.verifyLastRepair && checkpoint.actionableCodeKbEvents > 0) {
                    RoundCheckpoint finalVerify = loadRoundCheckpoint(a.runDir, round + 1);
                    if (!finalVerify.hasAnySignal() || !finalVerify.feedbackAnalyzed) {
                        return ResumeContext.resumeRound(round + 1, stats.prioritizedRows(), stats, false,
                                "final-verify");
                    }
                }
                continue;
            }
            if (checkpoint.actionableCodeKbEvents > 0 && !checkpoint.evidenceDone) {
                return ResumeContext.resumeRound(round, stats.prioritizedRows(), stats, false, "round-stage");
            }
        }

        int feedbackRound = Math.max(1, a.resumeFeedbackRound);
        int round = Math.max(1, a.resumeFromRound);
        Path sourceFeedback = a.runDir.resolve("round-" + feedbackRound).resolve("failure-feedback.jsonl");
        FeedbackStats previousStats = analyzeFeedback(sourceFeedback, a.minConfidence);
        List<Integer> activeRows = previousStats.prioritizedRows();
        if (activeRows.isEmpty()) {
            return ResumeContext.stop(round, "Resume feedback has no actionable rows: " + sourceFeedback);
        }
        return ResumeContext.resumeRound(round, activeRows, previousStats, true, "feedback-fallback");
    }

    private static void applyStatsToCheckpoint(RoundCheckpoint checkpoint, FeedbackStats stats) {
        if (checkpoint == null || stats == null) {
            return;
        }
        checkpoint.totalEvents = stats.totalEvents;
        checkpoint.codeKbEvents = stats.codeKbEvents;
        checkpoint.actionableCodeKbEvents = stats.actionableCodeKbEvents;
        checkpoint.actionableCodeKbImportEvents = stats.actionableCodeKbImportEvents;
        checkpoint.actionableCodeKbNonImportEvents = stats.actionableCodeKbNonImportEvents;
        checkpoint.actionableKillEvents = stats.actionableKillEvents;
        checkpoint.retryableFailureEvents = stats.retryableFailureEvents;
        checkpoint.evidenceParseEvents = stats.evidenceParseEvents;
        checkpoint.muTestLlmEvents = stats.muTestLlmEvents;
        checkpoint.otherEvents = stats.otherEvents;
        checkpoint.actionableRows = new ArrayList<Integer>(stats.actionableRows);
        checkpoint.killOrientedRows = new ArrayList<Integer>(stats.killOrientedRows);
        checkpoint.retryRows = new ArrayList<Integer>(stats.retryRows);
    }

    private static RoundCheckpoint ensureRoundCheckpointRecovered(Args a, Path report, int round) throws IOException {
        RoundCheckpoint checkpoint = loadRoundCheckpoint(a.runDir, round);
        if (checkpoint.feedbackAnalyzed && checkpoint.hasStats()) {
            return checkpoint;
        }
        Path feedback = a.runDir.resolve("round-" + round).resolve("failure-feedback.jsonl");
        if (!Files.isRegularFile(feedback)) {
            return checkpoint;
        }
        FeedbackStats stats = analyzeFeedback(feedback, a.minConfidence);
        applyStatsToCheckpoint(checkpoint, stats);
        checkpoint.feedbackAnalyzed = true;
        checkpoint.llmDone = true;
        saveRoundCheckpoint(a.runDir, checkpoint);
        logReport(report, "FEEDBACK_RECOVERED", round,
                "Recovered feedback from " + feedback.toAbsolutePath() + " after missing/incomplete checkpoint.");
        logReport(report, "FEEDBACK", round, stats.toJson().toString());
        logLoopStats(a, report, round, "ROUND_STATS");
        return checkpoint;
    }

    private static Path roundCheckpointPath(Path runDir, int round) {
        return runDir.resolve("round-" + round).resolve("round-checkpoint.json");
    }

    private static RoundCheckpoint loadRoundCheckpoint(Path runDir, int round) throws IOException {
        Path checkpointPath = roundCheckpointPath(runDir, round);
        if (Files.isRegularFile(checkpointPath)) {
            String text = new String(Files.readAllBytes(checkpointPath), StandardCharsets.UTF_8);
            if (!text.trim().isEmpty()) {
                return RoundCheckpoint.fromJson(new JSONObject(text));
            }
        }
        RoundCheckpoint checkpoint = new RoundCheckpoint(round);
        Path roundDir = runDir.resolve("round-" + round);
        Path feedback = roundDir.resolve("failure-feedback.jsonl");
        checkpoint.llmDone = Files.isRegularFile(roundDir.resolve("mutestllm.log"))
                || Files.isRegularFile(roundDir.resolve("mutestllm-rows.log"))
                || Files.isRegularFile(feedback);
        checkpoint.feedbackIngestDone = Files.isRegularFile(roundDir.resolve("codekb-feedback.log"));
        checkpoint.evidenceDone = Files.isRegularFile(runDir.resolve("evidence-round-" + round + ".log"))
                || Files.isRegularFile(runDir.resolve("evidence-round-" + round + "-rows.log"));
        return checkpoint;
    }

    private static void saveRoundCheckpoint(Path runDir, RoundCheckpoint checkpoint) throws IOException {
        if (checkpoint == null) {
            return;
        }
        Path path = roundCheckpointPath(runDir, checkpoint.round);
        Files.createDirectories(path.getParent());
        Files.write(path,
                checkpoint.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static void ensureCodeKbReady(Args a, Path report) throws Exception {
        throwIfStopRequested(a.runDir, report, 0, "codekb-ready");
        Set<String> expectedProjects = loadExpectedProjects(a);
        boolean forceBuild = a.buildKbFirst || a.rebuildKb;
        String verifyArgs = buildCodeKbVerifyArgs(a.workspaceRoot, a.excel, expectedProjects);
        Path dbPath = a.workspaceRoot.resolve(Paths.get("data", "kb", "codekb.sqlite")).normalize();
        ensureModuleCompiled("CodeKB", report, 0);
        String codeKbClasspath = buildRuntimeClasspath("CodeKB", report, 0);

        if (!forceBuild) {
            if (!Files.isRegularFile(dbPath)) {
                logReport(report, "CODEKB_AUTO_BUILD", 0,
                        "CodeKB db not found at " + dbPath.toAbsolutePath().normalize()
                                + ". Creating a new database now.");
            } else {
                int verifyExit = runStage("codekb-verify",
                        javaCommand(codeKbClasspath, "org.codekb.cli.CodeKbVerifyCli", verifyArgs),
                        a.runDir.resolve("codekb-verify.log"),
                        report,
                        0,
                        false);
                if (verifyExit == 0) {
                    logReport(report, "CODEKB_READY", 0,
                            "Verified existing CodeKB for projects=" + expectedProjects);
                    return;
                }
                logReport(report, "CODEKB_AUTO_BUILD", 0,
                        "CodeKB verification failed. Auto-build will run. See "
                                + a.runDir.resolve("codekb-verify.log").toAbsolutePath());
            }
        } else {
            logReport(report, "CODEKB_AUTO_BUILD", 0,
                    "CodeKB build forced by flags: buildKbFirst=" + a.buildKbFirst + ", rebuildKb=" + a.rebuildKb);
        }

        runStage("codekb-build",
                javaCommand(codeKbClasspath, "org.codekb.cli.CodeKbBuildCli",
                        quoteArg(a.workspaceRoot.toString())
                                + " --excel " + quoteArg(a.excel.toString())
                                + (a.rebuildKb ? " --rebuild" : "")),
                a.runDir.resolve("codekb-build.log"),
                report,
                0,
                true);

        runStage("codekb-verify-post-build",
                javaCommand(codeKbClasspath, "org.codekb.cli.CodeKbVerifyCli", verifyArgs),
                a.runDir.resolve("codekb-verify-post-build.log"),
                report,
                0,
                true);
    }

    private static int runLlm(Args a, Path feedback, Path report, int round, List<Integer> rows) throws Exception {
        throwIfStopRequested(a.runDir, report, round, "mutestllm");
        if (rows != null && rows.isEmpty()) {
            logReport(report, "STAGE_SKIP", round, "mutestllm no active rows");
            return 0;
        }
        ensureModuleCompiled("MuTestLLM", report, round);
        String runtimeClasspath = buildRuntimeClasspath("MuTestLLM", report, round);
        if (rows != null) {
            List<String> cmd = javaCommand(runtimeClasspath, "mujava.testgenerator.LLMTestGeneratorBatch",
                    quoteArg(a.excel.toString()) + rowsSelectorArgs(a, round, rows, "llm"));
            addRunScopedProperties(cmd, a, round, feedback);
            return runStage("mutestllm-rows",
                    cmd,
                    a.runDir.resolve("round-" + round).resolve("mutestllm-rows.log"),
                    report,
                    round,
                    false);
        }
        List<String> cmd = javaCommand(runtimeClasspath, "mujava.testgenerator.LLMTestGeneratorBatch",
                quoteArg(a.excel.toString()) + rowArgsForLlm(a));
        addRunScopedProperties(cmd, a, round, feedback);
        return runStage("mutestllm", cmd, a.runDir.resolve("round-" + round).resolve("mutestllm.log"), report, round,
                false);
    }

    private static void ensureModuleCompiled(String module, Path report, int round) throws Exception {
        throwIfStopRequested(runDirFromReport(report), report, round, module.toLowerCase(Locale.ROOT) + "-compile");
        String key = moduleKey(module);
        if (COMPILED_MODULES.contains(key)) {
            logReport(report, "STAGE_SKIP", round, module.toLowerCase(Locale.ROOT) + "-compile cached");
            return;
        }
        int exit = runStage(module.toLowerCase(Locale.ROOT) + "-compile",
                mavenGoal(module, compileGoal(module)),
                runDirFromReport(report).resolve("module-compile-" + module + ".log"),
                report,
                round,
                true);
        if (exit != 0) {
            throw new IllegalStateException(module + " compile failed with exit code " + exit);
        }
        COMPILED_MODULES.add(key);
    }

    private static String buildRuntimeClasspath(String module, Path report, int round) throws Exception {
        throwIfStopRequested(runDirFromReport(report), report, round, module.toLowerCase(Locale.ROOT) + "-classpath");
        String key = moduleKey(module);
        String cached = RUNTIME_CLASSPATH_CACHE.get(key);
        if (cached != null && !cached.trim().isEmpty()) {
            logReport(report, "STAGE_SKIP", round, module.toLowerCase(Locale.ROOT) + "-classpath cached");
            return cached;
        }
        Path moduleDir = Paths.get(module).toAbsolutePath().normalize();
        Path cpFile = moduleDir.resolve("target").resolve("runtime.cp.txt");
        Files.createDirectories(cpFile.getParent());
        List<String> cmd = mavenBase(module);
        cmd.add("dependency:build-classpath");
        cmd.add("-Dmdep.outputFile=" + cpFile.toAbsolutePath().normalize());
        cmd.add("-Dmdep.pathSeparator=" + pathSeparator());
        if (!workspaceRuntimeDependencies(module).isEmpty()) {
            cmd.add("-Dmdep.excludeGroupIds=org.rip.evidence");
        }
        int exit = runStage(module.toLowerCase(Locale.ROOT) + "-classpath",
                cmd,
                runDirFromReport(report).resolve("module-classpath-" + module + ".log"),
                report,
                round,
                false);
        if (exit != 0 || !Files.isRegularFile(cpFile)) {
            String fallbackClasspath = fallbackRuntimeClasspath(module, moduleDir, report, round);
            if (fallbackClasspath != null && !fallbackClasspath.trim().isEmpty()) {
                RUNTIME_CLASSPATH_CACHE.put(key, fallbackClasspath);
                logReport(report, "STAGE_FALLBACK", round,
                        module.toLowerCase(Locale.ROOT) + "-classpath fallback=worker.cp.txt");
                return fallbackClasspath;
            }
            throw new IllegalStateException("Failed to build runtime classpath for " + module + ": " + cpFile);
        }
        String dependencyCp = new String(Files.readAllBytes(cpFile), StandardCharsets.UTF_8).trim();
        String classes = moduleDir.resolve("target").resolve("classes").toAbsolutePath().normalize().toString();
        String normalizedDependencyCp = normalizeDependencyClasspath(module, dependencyCp);
        if (normalizedDependencyCp.isEmpty()) {
            RUNTIME_CLASSPATH_CACHE.put(key, classes);
            return classes;
        }
        String runtimeClasspath = classes + pathSeparator() + normalizedDependencyCp;
        RUNTIME_CLASSPATH_CACHE.put(key, runtimeClasspath);
        return runtimeClasspath;
    }

    private static String fallbackRuntimeClasspath(String module, Path moduleDir, Path report, int round) throws IOException {
        Path workerCpFile = moduleDir.resolve("logs").resolve("worker-classpath").resolve("worker.cp.txt");
        if (!Files.isRegularFile(workerCpFile)) {
            logReport(report, "STAGE_FALLBACK_MISS", round,
                    module.toLowerCase(Locale.ROOT) + "-classpath worker.cp.txt missing: " + workerCpFile);
            return "";
        }
        String dependencyCp = new String(Files.readAllBytes(workerCpFile), StandardCharsets.UTF_8).trim();
        String classes = moduleDir.resolve("target").resolve("classes").toAbsolutePath().normalize().toString();
        String normalizedDependencyCp = normalizeDependencyClasspath(module, dependencyCp);
        if (normalizedDependencyCp.isEmpty()) {
            return classes;
        }
        return classes + pathSeparator() + normalizedDependencyCp;
    }

    private static String normalizeDependencyClasspath(String module, String dependencyCp) {
        List<String> entries = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String workspaceModule : workspaceRuntimeDependencies(module)) {
            String classesDir = Paths.get(workspaceModule, "target", "classes")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            if (Files.isDirectory(Paths.get(classesDir)) && seen.add(classesDir)) {
                entries.add(classesDir);
            }
        }
        if (dependencyCp != null && !dependencyCp.trim().isEmpty()) {
            String[] parts = dependencyCp.split(java.util.regex.Pattern.quote(pathSeparator()));
            for (String raw : parts) {
                String part = raw == null ? "" : raw.trim();
                if (part.isEmpty()) {
                    continue;
                }
                if (shouldSkipWorkspaceArtifact(module, part)) {
                    continue;
                }
                String normalized = Paths.get(part).toAbsolutePath().normalize().toString();
                if (seen.add(normalized)) {
                    entries.add(normalized);
                }
            }
        }
        return String.join(pathSeparator(), entries);
    }

    private static boolean shouldSkipWorkspaceArtifact(String module, String classpathEntry) {
        String normalized = classpathEntry.toLowerCase(Locale.ROOT).replace('/', '\\');
        for (String workspaceModule : workspaceRuntimeDependencies(module)) {
            String artifact = workspaceModule + "-1.0.jar";
            if (normalized.endsWith("\\" + artifact.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> workspaceRuntimeDependencies(String module) {
        List<String> deps = WORKSPACE_RUNTIME_DEPENDENCIES.get(module);
        return deps == null ? Collections.<String>emptyList() : deps;
    }

    private static Map<String, List<String>> buildWorkspaceRuntimeDependencies() {
        Map<String, List<String>> out = new HashMap<String, List<String>>();
        out.put("EvidenceParse", Collections.singletonList("CodeKB"));
        return out;
    }

    private static String moduleKey(String module) {
        return Paths.get(module == null ? "" : module).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> javaCommand(String classpath, String mainClass, String argsLine) {
        return javaCommand(classpath, mainClass, argsLine, null);
    }

    private static List<String> javaCommand(String classpath, String mainClass, String argsLine, Path javaHomeOverride) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaExecutable(javaHomeOverride));
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        addArgs(cmd, argsLine);
        return cmd;
    }

    private static void addArgs(List<String> cmd, String argsLine) {
        if (argsLine == null || argsLine.trim().isEmpty()) {
            return;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < argsLine.length(); i++) {
            char ch = argsLine.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                if (cur.length() > 0) {
                    cmd.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            cmd.add(cur.toString());
        }
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        return Paths.get(home, "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static String javaExecutable(Path javaHomeOverride) {
        if (javaHomeOverride == null) {
            return javaExecutable();
        }
        return javaHomeOverride.resolve("bin").resolve(isWindows() ? "java.exe" : "java").toString();
    }

    private static int runEvidence(Args a, Path report, int round, List<Integer> rows) throws Exception {
        throwIfStopRequested(a.runDir, report, round, "evidence-parse");
        if (rows != null && rows.isEmpty()) {
            logReport(report, "STAGE_SKIP", round, "evidence-parse no active rows");
            return 0;
        }
        ensureModuleCompiled("CodeKB", report, round);
        ensureModuleCompiled("EvidenceParse", report, round);
        String runtimeClasspath = buildRuntimeClasspath("EvidenceParse", report, round);
        if (rows != null) {
            String evidenceArgs = "--excel " + quoteArg(a.excel.toString())
                    + " --mode " + a.evidenceMode
                    + " --workers " + a.evidenceWorkers
                    + " --rewrite true"
                    + rowsSelectorArgs(a, round, rows, "evidence")
                    + " --runDir " + quoteArg(a.runDir.resolve("evidence-round-" + round + "-rows").toString());
            List<String> cmd = javaCommand(runtimeClasspath, "org.OutputJsonBatchRunner", evidenceArgs, a.evidenceJavaHome);
            propagateEvidenceProperties(cmd);
            return runStage("evidence-parse-rows",
                    cmd,
                    a.runDir.resolve("evidence-round-" + round + "-rows.log"),
                    report,
                    round,
                    true);
        }
        String evidenceArgs = "--excel " + quoteArg(a.excel.toString())
                + " --mode " + a.evidenceMode
                + " --workers " + a.evidenceWorkers
                + " --rewrite true"
                + rowArgsForEvidence(a)
                + " --runDir " + quoteArg(a.runDir.resolve("evidence-round-" + round).toString());
        List<String> cmd = javaCommand(runtimeClasspath, "org.OutputJsonBatchRunner", evidenceArgs, a.evidenceJavaHome);
        propagateEvidenceProperties(cmd);
        return runStage("evidence-parse",
                cmd,
                a.runDir.resolve("evidence-round-" + round + ".log"),
                report,
                round,
                true);
    }

    private static void propagateEvidenceProperties(List<String> cmd) {
        String enabled = firstNonBlank(
                System.getProperty("llm.evidence.legacy.enabled"),
                loadLoopProperties().getProperty("llm.evidence.legacy.enabled"),
                "false");
        if (enabled != null && !enabled.trim().isEmpty()) {
            cmd.add(1, "-Dllm.evidence.legacy.enabled=" + enabled.trim());
        }
    }

    private static int runFeedbackIngest(Args a, Path feedback, Path report, int round) throws Exception {
        throwIfStopRequested(a.runDir, report, round, "codekb-feedback");
        ensureModuleCompiled("CodeKB", report, round);
        String codeKbClasspath = buildRuntimeClasspath("CodeKB", report, round);
        String ingestArgs = quoteArg(a.workspaceRoot.toString())
                + " " + quoteArg(feedback.toAbsolutePath().normalize().toString())
                + " --min-confidence " + a.minConfidence
                + " --auto-repairable-only true"
                + " --accept-all true";
        return runStage("codekb-feedback",
                javaCommand(codeKbClasspath, "org.codekb.cli.CodeKbFeedbackCli", ingestArgs),
                a.runDir.resolve("round-" + round).resolve("codekb-feedback.log"),
                report,
                round,
                true);
    }

    private static List<String> command(String module, String mainClass, String execArgs) {
        List<String> cmd = moduleMavenBase(module);
        cmd.add("-Dexec.mainClass=" + mainClass);
        cmd.add("-Dexec.args=" + execArgs);
        cmd.add("exec:java");
        return cmd;
    }

    private static List<String> moduleMavenBase(String module) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(isWindows() ? "mvn.cmd" : "mvn");
        cmd.add("-q");
        cmd.add("-f");
        cmd.add(module + "/pom.xml");
        cmd.add("-DskipTests");
        return cmd;
    }

    private static List<String> moduleMavenBase(String module, String goal) {
        List<String> cmd = moduleMavenBase(module);
        cmd.add(goal);
        return cmd;
    }

    private static List<String> mavenGoal(String module, String goal) {
        List<String> cmd = mavenBase(module);
        cmd.add(goal);
        return cmd;
    }

    private static String compileGoal(String module) {
        if ("CodeKB".equalsIgnoreCase(module)) {
            return "package";
        }
        return "compile";
    }

    private static List<String> mavenBase(String module) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(isWindows() ? "mvn.cmd" : "mvn");
        cmd.add("-q");
        cmd.add("-pl");
        cmd.add(module);
        cmd.add("-am");
        cmd.add("-DskipTests");
        return cmd;
    }

    private static int runStage(String name,
            List<String> command,
            Path logFile,
            Path report,
            int round,
            boolean failFast) throws Exception {
        return runStage(name, command, logFile, report, round, failFast, null);
    }

    private static int runStage(String name,
            List<String> command,
            Path logFile,
            Path report,
            int round,
            boolean failFast,
            Path javaHome) throws Exception {
        Files.createDirectories(logFile.getParent());
        Path runDir = runDirFromReport(report);
        throwIfStopRequested(runDir, report, round, name);
        ManagedProcessCleanup.addProcessGroupProperty(command);
        logReport(report, "STAGE_START", round, name + " " + command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Paths.get("").toAbsolutePath().toFile());
        pb.redirectErrorStream(true);
        applyJavaHome(pb, javaHome);
        Process process = ManagedProcessCleanup.register(pb.start());
        int exit;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                Thread pump = startStageOutputPump(name, reader, writer);
                try {
                    while (true) {
                        if (process.waitFor(250L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            exit = process.exitValue();
                            break;
                        }
                        if (isStopRequested(runDir)) {
                            logReport(report, "STAGE_ABORT", round,
                                    name + " detected stop marker and is terminating the process tree.");
                            ManagedProcessCleanup.destroy(process, 500L);
                            ManagedProcessCleanup.requestStopAndCleanupCurrentProcessGroup("stop-requested:" + name);
                            throw new StopRequestedException(round, "stop requested during " + name);
                        }
                    }
                } finally {
                    pump.join(1000L);
                    writer.flush();
                }
            }
        } finally {
            ManagedProcessCleanup.unregister(process);
            ManagedProcessCleanup.destroy(process, 250L);
        }
        logReport(report, "STAGE_DONE", round, name + " exit=" + exit + ", log=" + logFile.toAbsolutePath());
        if (exit != 0 && failFast) {
            throw new IllegalStateException(name + " failed with exit code " + exit + ". See " + logFile);
        }
        return exit;
    }

    private static Thread startStageOutputPump(final String name,
            final BufferedReader reader,
            final BufferedWriter writer) {
        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        System.out.println("[" + name + "] " + line);
                    }
                } catch (IOException ignored) {
                }
            }
        }, "loop-stage-output-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    private static String safeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static void applyJavaHome(ProcessBuilder pb, Path javaHome) {
        if (javaHome == null || !Files.isDirectory(javaHome)) {
            return;
        }
        String home = javaHome.toAbsolutePath().normalize().toString();
        pb.environment().put("JAVA_HOME", home);
        String pathKey = environmentKey(pb, "PATH");
        String path = pb.environment().get(pathKey);
        String bin = javaHome.resolve("bin").toAbsolutePath().normalize().toString();
        String sep = isWindows() ? ";" : ":";
        pb.environment().put(pathKey, bin + sep + (path == null ? "" : path));
    }

    private static String environmentKey(ProcessBuilder pb, String preferred) {
        for (String key : pb.environment().keySet()) {
            if (key != null && key.equalsIgnoreCase(preferred)) {
                return key;
            }
        }
        return preferred;
    }

    private static FeedbackStats analyzeFeedback(Path feedback, double minConfidence) throws IOException {
        FeedbackStats stats = new FeedbackStats();
        if (!Files.isRegularFile(feedback)) {
            return stats;
        }
        List<String> lines = Files.readAllLines(feedback, StandardCharsets.UTF_8);
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            stats.totalEvents++;
            JSONObject root = new JSONObject(trimmed);
            JSONObject analysis = root.optJSONObject("failureAnalysis");
            int row = parseRow(root.optString("taskId", ""));
            boolean compiled = root.optBoolean("compiled", false);
            boolean originalPassed = root.optBoolean("originalPassed", false);
            boolean killed = root.optBoolean("killed", false);
            String targetStatus = root.optString("targetStatus", "");
            String failureSymptom = root.optString("failureSymptom", "");
            boolean semanticPlanExhausted = root.optBoolean("semanticPlanExhausted", false);
            if (analysis == null) {
                if (!compiled && isRetryableGenerationFailure(targetStatus, failureSymptom)) {
                    stats.retryableFailureEvents++;
                    stats.addRetryRow(row);
                }
                if (compiled && originalPassed && !killed
                        && !semanticPlanExhausted
                        && !safeEqualsIgnoreCase(targetStatus, "EQUIVALENT_SUSPECTED")) {
                    stats.actionableKillEvents++;
                    stats.addKillOrientedRow(row);
                }
                continue;
            }
            String target = analysis.optString("primaryFixTarget", "");
            String stage = analysis.optString("failureStage", "");
            boolean autoRepairable = analysis.optBoolean("autoRepairable", false);
            double confidence = analysis.optDouble("confidence", 0.0d);
            String cause = analysis.optString("rootCauseType", "");

            if ("CODEKB".equalsIgnoreCase(target)) {
                stats.codeKbEvents++;
                if (autoRepairable && confidence >= minConfidence) {
                    stats.actionableCodeKbEvents++;
                    org.json.JSONArray imports = analysis.optJSONArray("suspectedMissingImports");
                    if ("MISSING_IMPORT_KNOWLEDGE".equalsIgnoreCase(cause) && imports != null && imports.length() > 0) {
                        stats.actionableCodeKbImportEvents++;
                        stats.addActionableRow(parseRow(root.optString("taskId", "")));
                    }
                    if (isKillOrientedCodeKbCause(cause)) {
                        stats.actionableCodeKbNonImportEvents++;
                        stats.addKillOrientedRow(row);
                    }
                }
            } else if ("EVIDENCE_PARSE".equalsIgnoreCase(target) || "EVIDENCE_PARSE".equalsIgnoreCase(stage)) {
                stats.evidenceParseEvents++;
            } else if ("MUTESTLLM".equalsIgnoreCase(target) || "MUTESTLLM".equalsIgnoreCase(stage)) {
                stats.muTestLlmEvents++;
            } else {
                stats.otherEvents++;
            }

            if (!compiled && isRetryableGenerationFailure(targetStatus, failureSymptom)) {
                stats.retryableFailureEvents++;
                stats.addRetryRow(row);
            }

            if (isKillOrientedActionable(root, analysis, compiled, originalPassed, killed, targetStatus, confidence, semanticPlanExhausted)) {
                stats.actionableKillEvents++;
                stats.addKillOrientedRow(row);
            }
        }
        return stats;
    }

    private static boolean isRetryableGenerationFailure(String targetStatus, String failureSymptom) {
        return safeEqualsIgnoreCase(targetStatus, "COMPILE_FAILED_AFTER_REPAIR")
                || safeEqualsIgnoreCase(targetStatus, "COMPILE_FAILED_AFTER_GENERATION")
                || safeEqualsIgnoreCase(targetStatus, "COMPILE_FAILED")
                || safeEqualsIgnoreCase(targetStatus, "API_FAILED")
                || safeEqualsIgnoreCase(targetStatus, "GENERATION_FAILED")
                || safeEqualsIgnoreCase(targetStatus, "TASK_CRASHED")
                || safeEqualsIgnoreCase(failureSymptom, "compile_failed")
                || safeEqualsIgnoreCase(failureSymptom, "api_failed")
                || safeEqualsIgnoreCase(failureSymptom, "generation_failed");
    }

    private static boolean isKillOrientedActionable(JSONObject root,
            JSONObject analysis,
            boolean compiled,
            boolean originalPassed,
            boolean killed,
            String targetStatus,
            double confidence,
            boolean semanticPlanExhausted) {
        if (analysis == null) {
            return false;
        }
        String cause = analysis.optString("rootCauseType", "");
        String target = analysis.optString("primaryFixTarget", "");
        String stage = analysis.optString("failureStage", "");
        boolean autoRepairable = analysis.optBoolean("autoRepairable", false);
        boolean evidenceCanChange = "CODEKB".equalsIgnoreCase(target)
                || "EVIDENCE_PARSE".equalsIgnoreCase(target)
                || "EVIDENCE_PARSE".equalsIgnoreCase(stage);
        if (semanticPlanExhausted && !evidenceCanChange) {
            // The normal STRICT -> O -> C+O semantic plan is already exhausted.
            // Do not start another identical LLM-only outer-loop cycle. A new cycle
            // is justified only when CodeKB/EvidenceParse can actually change evidence.
            return false;
        }
        if (compiled && originalPassed && !killed
                && !safeEqualsIgnoreCase(targetStatus, "EQUIVALENT_SUSPECTED")
                && isKillOrientedCause(cause)) {
            return true;
        }
        if (compiled && originalPassed && !killed
                && !safeEqualsIgnoreCase(targetStatus, "EQUIVALENT_SUSPECTED")
                && ("MUTESTLLM".equalsIgnoreCase(target)
                        || "MUTESTLLM".equalsIgnoreCase(stage)
                        || "EVIDENCE_PARSE".equalsIgnoreCase(target)
                        || "EVIDENCE_PARSE".equalsIgnoreCase(stage))) {
            return confidence >= 0.60d || autoRepairable;
        }
        return false;
    }

    private static boolean isKillOrientedCause(String cause) {
        String normalized = cause == null ? "" : cause.trim().toUpperCase(Locale.ROOT);
        return "ASSERTION_GENERATION_WEAK".equals(normalized)
                || "OBSERVABLE_PLAN_MISSING".equals(normalized)
                || "OBSERVABLE_NOT_USED".equals(normalized)
                || "MISSING_OBSERVABLE_CANDIDATE".equals(normalized)
                || "MISSING_ENTRY_CANDIDATE".equals(normalized);
    }

    private static boolean isKillOrientedCodeKbCause(String cause) {
        String normalized = cause == null ? "" : cause.trim().toUpperCase(Locale.ROOT);
        return "MISSING_ENTRY_CANDIDATE".equals(normalized)
                || "MISSING_RECEIVER_CONSTRUCTION".equals(normalized);
    }

    private static boolean safeEqualsIgnoreCase(String left, String right) {
        return (left == null ? "" : left.trim()).equalsIgnoreCase(right == null ? "" : right.trim());
    }

    private static void logReport(Path report, String event, int round, String message) throws IOException {
        Files.createDirectories(report.getParent());
        JSONObject obj = new JSONObject();
        obj.put("time", Instant.now().toString());
        obj.put("event", event);
        obj.put("round", round);
        obj.put("message", message == null ? "" : message);
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            writer.write(obj.toString());
            writer.newLine();
        }
    }

    private static void logLoopStats(Args a, Path report, int round, String event) throws IOException {
        LoopStats stats = loadLoopStats(a.runDir.resolve("rl").resolve("trajectory.jsonl"));
        if (stats.totalMutants == 0) {
            return;
        }
        System.out.println("[AUTO-LOOP] " + event + ": " + stats.summaryLine());
        logReport(report, event, round, stats.toJson().toString());
    }

    private static LoopStats loadLoopStats(Path trajectory) throws IOException {
        LoopStats stats = new LoopStats();
        if (!Files.isRegularFile(trajectory)) {
            return stats;
        }

        Map<String, JSONObject> latestByMutant = new LinkedHashMap<String, JSONObject>();
        List<String> lines = Files.readAllLines(trajectory, StandardCharsets.UTF_8);
        int fallbackIndex = 0;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JSONObject obj = new JSONObject(trimmed);
            String key = firstNonBlank(
                    obj.optString("taskId", ""),
                    obj.optString("mutantId", ""),
                    obj.optString("exactBucketKey", ""),
                    obj.optString("operator", ""));
            if (key.isEmpty()) {
                key = "entry-" + (++fallbackIndex);
            }
            latestByMutant.put(key, obj);
        }

        stats.totalMutants = latestByMutant.size();
        for (JSONObject obj : latestByMutant.values()) {
            if (obj.optBoolean("compileSuccess", false)) {
                stats.compiledSucceed++;
            }
            String targetStatus = obj.optString("targetStatus", "");
            if (isExecutedTargetStatus(targetStatus)) {
                stats.targetExecuted++;
                if (obj.optBoolean("killed", false)) {
                    stats.targetKilled++;
                } else if (Result.STATUS_SURVIVED.equalsIgnoreCase(targetStatus)) {
                    stats.targetSurvived++;
                } else if (Result.STATUS_EQUIVALENT_SUSPECTED.equalsIgnoreCase(targetStatus)) {
                    stats.equivalentSuspected++;
                }
            } else {
                stats.notExecuted++;
            }
        }
        return stats;
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String trimmed = value == null ? "" : value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String normalizeForProperty(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static Path runDirFromReport(Path report) {
        Path parent = report == null ? null : report.toAbsolutePath().normalize().getParent();
        return parent == null ? Paths.get("").toAbsolutePath().normalize() : parent;
    }

    private static void addRunScopedProperties(List<String> cmd, Args a, int round, Path feedback) {
        Path rlDir = a.runDir.resolve("rl");
        propagateSystemProperty(cmd, "llm.threads");
        propagateSystemProperty(cmd, "llm.pipeline.enabled");
        propagateSystemProperty(cmd, "llm.pipeline.generation.threads");
        propagateSystemProperty(cmd, "llm.pipeline.post.threads");
        propagateSystemProperty(cmd, "llm.max.repair.rounds");
        propagateSystemProperty(cmd, "llm.api.max.attempts");
        propagateSystemProperty(cmd, "mujava.max.workers");
        propagateSystemProperty(cmd, "mujava.max.inflight.multiplier");
        propagateSystemProperty(cmd, "mujava.worker.xms");
        propagateSystemProperty(cmd, "mujava.worker.xmx");
        cmd.add(1, "-Drl.feedback.log.path=" + normalizeForProperty(feedback));
        cmd.add(1, "-Drl.log.path=" + normalizeForProperty(rlDir.resolve("trajectory.jsonl")));
        cmd.add(1, "-Drl.transition.log.path=" + normalizeForProperty(rlDir.resolve("transitions.jsonl")));
        cmd.add(1, "-Drl.episode.log.path=" + normalizeForProperty(rlDir.resolve("episodes.jsonl")));
        cmd.add(1, "-Drl.policy.store.path=" + normalizeForProperty(rlDir.resolve("policy-store.json")));
        cmd.add(1, "-Drl.linucb.model.path=" + normalizeForProperty(rlDir.resolve("linucb-policy.json")));
        cmd.add(1, "-Drl.outer.loop.round=" + round);
        cmd.add(1, "-Dllm.closed.loop.run.id=" + runIdFromRunDir(a.runDir));
        cmd.add(1, "-Dmutestllm.run.dir=" + normalizeForProperty(a.runDir.resolve("round-" + round)));
    }

    private static void propagateSystemProperty(List<String> cmd, String key) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        cmd.add(1, "-D" + key + "=" + value.trim());
    }

    private static String runIdFromRunDir(Path runDir) {
        if (runDir == null) {
            return "";
        }
        Path fileName = runDir.toAbsolutePath().normalize().getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static String buildCodeKbVerifyArgs(Path workspaceRoot, Path excel, Set<String> expectedProjects) {
        StringBuilder sb = new StringBuilder();
        sb.append(quoteArg(workspaceRoot.toString()));
        if (excel != null) {
            sb.append(" --excel ").append(quoteArg(excel.toString()));
        }
        for (String project : expectedProjects) {
            sb.append(" --project ").append(quoteArg(project));
        }
        return sb.toString();
    }

    private static Set<String> loadExpectedProjects(Args a) throws IOException {
        Set<String> projects = new LinkedHashSet<String>();
        List<List<Object>> excelData = ExcelUtils.readExcel(a.excel.toString(), 0);
        if (excelData == null || excelData.isEmpty()) {
            return projects;
        }
        int startIndex = Math.max(1, a.rowStart);
        int endIndex = a.rowEnd > 0 ? Math.min(a.rowEnd, excelData.size() - 1) : excelData.size() - 1;
        for (int i = startIndex; i <= endIndex; i++) {
            if (i < 0 || i >= excelData.size()) {
                continue;
            }

            List<Object> row = excelData.get(i);
            if (row == null || row.size() <= 7) {
                continue;
            }
            String project = cell(row, 7);
            if (!isSupportedProject(project)) {
                continue;
            }
            if (!project.isEmpty()) {
                projects.add(project);
            }
        }
        return projects;
    }

    private static boolean isSupportedProject(String project) {
        return SUPPORTED_PROJECTS.contains(project);
    }

    private static String cell(List<Object> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        Object value = row.get(index);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String quoteArg(String value) {
        String v = value == null ? "" : value;
        if (v.indexOf(' ') >= 0) {
            return "\"" + v.replace("\"", "\\\"") + "\"";
        }
        return v;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String pathSeparator() {
        return isWindows() ? ";" : ":";
    }

    private static final class ResumeContext {
        final int round;
        final List<Integer> activeRows;
        final FeedbackStats seedStats;
        final boolean resumeNeedsPriorEvidence;
        final String mode;
        final boolean stopImmediately;
        final String stopReason;

        private ResumeContext(int round,
                List<Integer> activeRows,
                FeedbackStats seedStats,
                boolean resumeNeedsPriorEvidence,
                String mode,
                boolean stopImmediately,
                String stopReason) {
            this.round = round;
            this.activeRows = activeRows == null ? Collections.<Integer>emptyList() : activeRows;
            this.seedStats = seedStats;
            this.resumeNeedsPriorEvidence = resumeNeedsPriorEvidence;
            this.mode = mode == null ? "" : mode;
            this.stopImmediately = stopImmediately;
            this.stopReason = stopReason == null ? "" : stopReason;
        }

        static ResumeContext resumeRound(int round,
                List<Integer> activeRows,
                FeedbackStats seedStats,
                boolean resumeNeedsPriorEvidence,
                String mode) {
            return new ResumeContext(round,
                    new ArrayList<Integer>(activeRows == null ? Collections.<Integer>emptyList() : activeRows),
                    seedStats,
                    resumeNeedsPriorEvidence,
                    mode,
                    false,
                    "");
        }

        static ResumeContext stop(int round, String stopReason) {
            return new ResumeContext(round, Collections.<Integer>emptyList(), null, false, "terminal", true,
                    stopReason);
        }
    }

    private static final class StopRequestedException extends Exception {
        final int round;

        StopRequestedException(int round, String message) {
            super(message);
            this.round = Math.max(0, round);
        }
    }

    private static final class RoundCheckpoint {
        final int round;
        boolean llmDone;
        boolean feedbackAnalyzed;
        boolean feedbackIngestDone;
        boolean evidenceDone;
        int totalEvents;
        int codeKbEvents;
        int actionableCodeKbEvents;
        int actionableCodeKbImportEvents;
        int actionableCodeKbNonImportEvents;
        int actionableKillEvents;
        int retryableFailureEvents;
        int evidenceParseEvents;
        int muTestLlmEvents;
        int otherEvents;
        List<Integer> actionableRows = new ArrayList<Integer>();
        List<Integer> killOrientedRows = new ArrayList<Integer>();
        List<Integer> retryRows = new ArrayList<Integer>();
        String stopReason = "";

        RoundCheckpoint(int round) {
            this.round = round;
        }

        boolean hasStats() {
            return totalEvents > 0
                    || codeKbEvents > 0
                    || actionableCodeKbEvents > 0
                    || actionableKillEvents > 0
                    || retryableFailureEvents > 0
                    || !actionableRows.isEmpty()
                    || !killOrientedRows.isEmpty()
                    || !retryRows.isEmpty();
        }

        boolean hasAnySignal() {
            return llmDone || feedbackAnalyzed || feedbackIngestDone || evidenceDone || hasStats()
                    || (stopReason != null && !stopReason.trim().isEmpty());
        }

        List<Integer> prioritizedRows() {
            LinkedHashSet<Integer> merged = new LinkedHashSet<Integer>();
            merged.addAll(actionableRows);
            merged.addAll(killOrientedRows);
            merged.addAll(retryRows);
            return new ArrayList<Integer>(merged);
        }

        FeedbackStats toFeedbackStats() {
            FeedbackStats stats = new FeedbackStats();
            stats.totalEvents = totalEvents;
            stats.codeKbEvents = codeKbEvents;
            stats.actionableCodeKbEvents = actionableCodeKbEvents;
            stats.actionableCodeKbImportEvents = actionableCodeKbImportEvents;
            stats.actionableCodeKbNonImportEvents = actionableCodeKbNonImportEvents;
            stats.actionableKillEvents = actionableKillEvents;
            stats.retryableFailureEvents = retryableFailureEvents;
            stats.evidenceParseEvents = evidenceParseEvents;
            stats.muTestLlmEvents = muTestLlmEvents;
            stats.otherEvents = otherEvents;
            for (Integer row : actionableRows) {
                if (row != null) {
                    stats.addActionableRow(row.intValue());
                }
            }
            for (Integer row : killOrientedRows) {
                if (row != null) {
                    stats.addKillOrientedRow(row.intValue());
                }
            }
            for (Integer row : retryRows) {
                if (row != null) {
                    stats.addRetryRow(row.intValue());
                }
            }
            return stats;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("round", round);
            obj.put("llmDone", llmDone);
            obj.put("feedbackAnalyzed", feedbackAnalyzed);
            obj.put("feedbackIngestDone", feedbackIngestDone);
            obj.put("evidenceDone", evidenceDone);
            obj.put("totalEvents", totalEvents);
            obj.put("codeKbEvents", codeKbEvents);
            obj.put("actionableCodeKbEvents", actionableCodeKbEvents);
            obj.put("actionableCodeKbImportEvents", actionableCodeKbImportEvents);
            obj.put("actionableCodeKbNonImportEvents", actionableCodeKbNonImportEvents);
            obj.put("actionableKillEvents", actionableKillEvents);
            obj.put("retryableFailureEvents", retryableFailureEvents);
            obj.put("evidenceParseEvents", evidenceParseEvents);
            obj.put("muTestLlmEvents", muTestLlmEvents);
            obj.put("otherEvents", otherEvents);
            obj.put("actionableRows", new org.json.JSONArray(actionableRows));
            obj.put("killOrientedRows", new org.json.JSONArray(killOrientedRows));
            obj.put("retryRows", new org.json.JSONArray(retryRows));
            obj.put("stopReason", stopReason == null ? "" : stopReason);
            return obj;
        }

        static RoundCheckpoint fromJson(JSONObject obj) {
            RoundCheckpoint checkpoint = new RoundCheckpoint(obj.optInt("round", 0));
            checkpoint.llmDone = obj.optBoolean("llmDone", false);
            checkpoint.feedbackAnalyzed = obj.optBoolean("feedbackAnalyzed", false);
            checkpoint.feedbackIngestDone = obj.optBoolean("feedbackIngestDone", false);
            checkpoint.evidenceDone = obj.optBoolean("evidenceDone", false);
            checkpoint.totalEvents = obj.optInt("totalEvents", 0);
            checkpoint.codeKbEvents = obj.optInt("codeKbEvents", 0);
            checkpoint.actionableCodeKbEvents = obj.optInt("actionableCodeKbEvents", 0);
            checkpoint.actionableCodeKbImportEvents = obj.optInt("actionableCodeKbImportEvents", 0);
            checkpoint.actionableCodeKbNonImportEvents = obj.optInt("actionableCodeKbNonImportEvents", 0);
            checkpoint.actionableKillEvents = obj.optInt("actionableKillEvents", 0);
            checkpoint.retryableFailureEvents = obj.optInt("retryableFailureEvents", 0);
            checkpoint.evidenceParseEvents = obj.optInt("evidenceParseEvents", 0);
            checkpoint.muTestLlmEvents = obj.optInt("muTestLlmEvents", 0);
            checkpoint.otherEvents = obj.optInt("otherEvents", 0);
            checkpoint.actionableRows = jsonArrayToIntList(obj.optJSONArray("actionableRows"));
            checkpoint.killOrientedRows = jsonArrayToIntList(obj.optJSONArray("killOrientedRows"));
            checkpoint.retryRows = jsonArrayToIntList(obj.optJSONArray("retryRows"));
            checkpoint.stopReason = obj.optString("stopReason", "");
            return checkpoint;
        }
    }

    private static List<Integer> jsonArrayToIntList(org.json.JSONArray array) {
        List<Integer> values = new ArrayList<Integer>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length(); i++) {
            int value = array.optInt(i, -1);
            if (value > 0) {
                values.add(Integer.valueOf(value));
            }
        }
        return values;
    }

    private static final class FeedbackStats {
        int totalEvents;
        int codeKbEvents;
        int actionableCodeKbEvents;
        int actionableCodeKbImportEvents;
        int actionableCodeKbNonImportEvents;
        int actionableKillEvents;
        int retryableFailureEvents;
        int evidenceParseEvents;
        int muTestLlmEvents;
        int otherEvents;
        final List<Integer> actionableRows = new ArrayList<Integer>();
        final List<Integer> killOrientedRows = new ArrayList<Integer>();
        final List<Integer> retryRows = new ArrayList<Integer>();
        private final Set<Integer> actionableRowSet = new LinkedHashSet<Integer>();
        private final Set<Integer> killOrientedRowSet = new LinkedHashSet<Integer>();
        private final Set<Integer> retryRowSet = new LinkedHashSet<Integer>();

        FeedbackStats() {
        }

        void addActionableRow(int row) {
            if (row > 0 && actionableRowSet.add(row)) {
                actionableRows.add(row);
            }
        }

        void addKillOrientedRow(int row) {
            if (row > 0 && killOrientedRowSet.add(row)) {
                killOrientedRows.add(row);
            }
        }

        void addRetryRow(int row) {
            if (row > 0 && retryRowSet.add(row)) {
                retryRows.add(row);
            }
        }

        boolean hasAnyActionableLoopWork() {
            return actionableCodeKbImportEvents > 0
                    || actionableCodeKbNonImportEvents > 0
                    || actionableKillEvents > 0
                    || retryableFailureEvents > 0;
        }

        List<Integer> prioritizedRows() {
            List<Integer> rows = new ArrayList<Integer>();
            LinkedHashSet<Integer> merged = new LinkedHashSet<Integer>();
            merged.addAll(actionableRows);
            merged.addAll(killOrientedRows);
            merged.addAll(retryRows);
            rows.addAll(merged);
            return rows;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("totalEvents", totalEvents);
            obj.put("codeKbEvents", codeKbEvents);
            obj.put("actionableCodeKbEvents", actionableCodeKbEvents);
            obj.put("actionableCodeKbImportEvents", actionableCodeKbImportEvents);
            obj.put("actionableCodeKbNonImportEvents", actionableCodeKbNonImportEvents);
            obj.put("actionableKillEvents", actionableKillEvents);
            obj.put("retryableFailureEvents", retryableFailureEvents);
            obj.put("evidenceParseEvents", evidenceParseEvents);
            obj.put("muTestLlmEvents", muTestLlmEvents);
            obj.put("otherEvents", otherEvents);
            obj.put("actionableRows", new org.json.JSONArray(actionableRows));
            obj.put("killOrientedRows", new org.json.JSONArray(killOrientedRows));
            obj.put("retryRows", new org.json.JSONArray(retryRows));
            obj.put("prioritizedRows", new org.json.JSONArray(prioritizedRows()));
            return obj;
        }
    }

    private static final class LoopStats {
        int totalMutants;
        int compiledSucceed;
        int targetExecuted;
        int targetKilled;
        int targetSurvived;
        int equivalentSuspected;
        int notExecuted;

        String summaryLine() {
            String compiledRate = String.format(Locale.ROOT, "%.2f%%",
                    totalMutants == 0 ? 0.0d : compiledSucceed * 100.0d / totalMutants);
            String targetKillRate = String.format(Locale.ROOT, "%.2f%%",
                    targetExecuted == 0 ? 0.0d : targetKilled * 100.0d / targetExecuted);
            return "Compiled Succeed: " + compiledSucceed + "/" + totalMutants + "(" + compiledRate + ")"
                    + ", Target Mutation Score: " + targetKilled + "/" + targetExecuted
                    + "(" + targetKillRate + ")";
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("totalMutants", totalMutants);
            obj.put("compiledSucceed", compiledSucceed);
            obj.put("compiledRate", totalMutants == 0 ? 0.0d : compiledSucceed * 100.0d / totalMutants);
            obj.put("targetExecuted", targetExecuted);
            obj.put("targetKilled", targetKilled);
            obj.put("targetMutationScore", targetExecuted == 0 ? 0.0d : targetKilled * 100.0d / targetExecuted);
            obj.put("targetSurvived", targetSurvived);
            obj.put("equivalentSuspected", equivalentSuspected);
            obj.put("notExecuted", notExecuted);
            obj.put("summary", summaryLine());
            return obj;
        }
    }

    private static int parseRow(String taskId) {
        String value = taskId == null ? "" : taskId.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("row-")) {
            value = value.substring(4);
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String autoLoopTimestamp() {
        return LocalDateTime.now().format(AUTO_LOOP_TIMESTAMP);
    }

    private static final class Args {
        Path workspaceRoot = Paths.get("").toAbsolutePath().normalize();
        Path excel = workspaceRoot.resolve(DEFAULT_EXCEL).normalize();
        Path runDir = workspaceRoot.resolve(Paths.get("logs", "auto-loop",
                autoLoopTimestamp())).normalize();
        int maxRounds = 2;
        double minConfidence = 0.85d;
        boolean buildKbFirst;
        boolean rebuildKb;
        boolean verifyLastRepair = true;
        boolean generateEvidenceFirst = true;
        boolean continueOnLlmFailure = true;
        boolean resumeMode = DEFAULT_RESUME_MODE;
        boolean resumeSkipEvidence;
        int resumeFromRound = DEFAULT_RESUME_FROM_ROUND;
        int resumeFeedbackRound = DEFAULT_RESUME_FEEDBACK_ROUND;
        String evidenceMode = "process";
        int evidenceWorkers = 6;
        Path evidenceJavaHome = defaultEvidenceJavaHome();
        int rowStart = 1;
        int rowEnd = -1;
        String processGroupId = "mutestllm-loop-" + Long.toHexString(System.currentTimeMillis());
        boolean internalRoundRunner;
        int internalTargetRound = -1;
        Path internalActiveRowsFile;
        boolean internalUseActiveRows;
        boolean internalResumeNeedsPriorEvidence;
        boolean internalVerificationOnly;

        static Args parse(String[] args) {
            Args a = new Args();
            applyConfigDefaults(a, loadLoopProperties());
            boolean excelProvided = false;
            boolean runDirProvided = false;
            for (int i = 0; args != null && i < args.length; i++) {
                String key = args[i];
                String value = i + 1 < args.length ? args[i + 1] : null;
                if ("--workspace".equalsIgnoreCase(key) && value != null) {
                    a.workspaceRoot = Paths.get(value).toAbsolutePath().normalize();
                    i++;
                } else if ("--excel".equalsIgnoreCase(key) && value != null) {
                    a.excel = a.workspaceRoot.resolve(Paths.get(value)).normalize();
                    excelProvided = true;
                    i++;
                } else if ("--run-dir".equalsIgnoreCase(key) && value != null) {
                    a.runDir = a.workspaceRoot.resolve(Paths.get(value)).normalize();
                    runDirProvided = true;
                    i++;
                } else if ("--max-rounds".equalsIgnoreCase(key) && value != null) {
                    a.maxRounds = Math.max(1, Integer.parseInt(value));
                    i++;
                } else if ("--min-confidence".equalsIgnoreCase(key) && value != null) {
                    a.minConfidence = Double.parseDouble(value);
                    i++;
                } else if ("--build-kb-first".equalsIgnoreCase(key) && value != null) {
                    a.buildKbFirst = Boolean.parseBoolean(value);
                    i++;
                } else if ("--rebuild-kb".equalsIgnoreCase(key) && value != null) {
                    a.rebuildKb = Boolean.parseBoolean(value);
                    i++;
                } else if ("--verify-last-repair".equalsIgnoreCase(key) && value != null) {
                    a.verifyLastRepair = Boolean.parseBoolean(value);
                    i++;
                } else if ("--generate-evidence-first".equalsIgnoreCase(key) && value != null) {
                    a.generateEvidenceFirst = Boolean.parseBoolean(value);
                    i++;
                } else if ("--continue-on-llm-failure".equalsIgnoreCase(key) && value != null) {
                    a.continueOnLlmFailure = Boolean.parseBoolean(value);
                    i++;
                } else if ("--resume-mode".equalsIgnoreCase(key) && value != null) {
                    a.resumeMode = Boolean.parseBoolean(value);
                    i++;
                } else if ("--resume-run-dir".equalsIgnoreCase(key) && value != null) {
                    a.runDir = a.workspaceRoot.resolve(Paths.get(value)).normalize();
                    runDirProvided = true;
                    i++;
                } else if ("--resume-from-round".equalsIgnoreCase(key) && value != null) {
                    a.resumeFromRound = Math.max(1, Integer.parseInt(value));
                    i++;
                } else if ("--resume-feedback-round".equalsIgnoreCase(key) && value != null) {
                    a.resumeFeedbackRound = Math.max(1, Integer.parseInt(value));
                    i++;
                } else if ("--resume-skip-evidence".equalsIgnoreCase(key) && value != null) {
                    a.resumeSkipEvidence = Boolean.parseBoolean(value);
                    i++;
                } else if ("--evidence-mode".equalsIgnoreCase(key) && value != null) {
                    a.evidenceMode = value.toLowerCase(Locale.ROOT);
                    i++;
                } else if ("--evidence-workers".equalsIgnoreCase(key) && value != null) {
                    a.evidenceWorkers = Math.max(1, Integer.parseInt(value));
                    i++;
                } else if ("--evidence-java-home".equalsIgnoreCase(key) && value != null) {
                    a.evidenceJavaHome = Paths.get(value).toAbsolutePath().normalize();
                    i++;
                } else if ("--row".equalsIgnoreCase(key) && value != null) {
                    a.rowStart = Math.max(1, Integer.parseInt(value));
                    a.rowEnd = a.rowStart;
                    i++;
                } else if ("--row-start".equalsIgnoreCase(key) && value != null) {
                    a.rowStart = Math.max(1, Integer.parseInt(value));
                    i++;
                } else if ("--row-end".equalsIgnoreCase(key) && value != null) {
                    a.rowEnd = Integer.parseInt(value);
                    i++;
                } else if ("--internal-round-runner".equalsIgnoreCase(key) && value != null) {
                    a.internalRoundRunner = Boolean.parseBoolean(value);
                    i++;
                } else if ("--internal-target-round".equalsIgnoreCase(key) && value != null) {
                    a.internalTargetRound = Integer.parseInt(value);
                    i++;
                } else if ("--internal-active-rows-file".equalsIgnoreCase(key) && value != null) {
                    a.internalActiveRowsFile = Paths.get(value).toAbsolutePath().normalize();
                    i++;
                } else if ("--internal-use-active-rows".equalsIgnoreCase(key) && value != null) {
                    a.internalUseActiveRows = Boolean.parseBoolean(value);
                    i++;
                } else if ("--internal-resume-needs-prior-evidence".equalsIgnoreCase(key) && value != null) {
                    a.internalResumeNeedsPriorEvidence = Boolean.parseBoolean(value);
                    i++;
                } else if ("--internal-verification-only".equalsIgnoreCase(key) && value != null) {
                    a.internalVerificationOnly = Boolean.parseBoolean(value);
                    i++;
                }
            }
            if (!excelProvided) {
                a.excel = a.workspaceRoot.resolve(DEFAULT_EXCEL).normalize();
            }
            if (a.resumeMode && !runDirProvided) {
                a.runDir = a.workspaceRoot.resolve(DEFAULT_RESUME_RUN_DIR).normalize();
            } else if (!runDirProvided) {
                a.runDir = a.workspaceRoot.resolve(Paths.get("logs", "auto-loop",
                        autoLoopTimestamp())).normalize();
            }
            if (!a.excel.isAbsolute()) {
                a.excel = a.workspaceRoot.resolve(a.excel).normalize();
            }
            if (!a.runDir.isAbsolute()) {
                a.runDir = a.workspaceRoot.resolve(a.runDir).normalize();
            }
            if (!a.internalRoundRunner) {
                a.maxRounds = Math.max(2, a.maxRounds);
            }
            return a;
        }

        private static Path defaultEvidenceJavaHome() {
            String configured = firstNonBlank(
                    System.getProperty("loop.evidence.java.home"),
                    System.getenv("EVIDENCE_JAVA_HOME"),
                    DEFAULT_EVIDENCE_JAVA_HOME);
            if (configured.isEmpty()) {
                return null;
            }
            Path path = Paths.get(configured).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : null;
        }

        private static void applyConfigDefaults(Args a, Properties props) {
            if (a == null) {
                return;
            }
            String configuredMaxRounds = firstNonBlank(
                    System.getProperty("loop.max.rounds"),
                    props == null ? "" : props.getProperty("loop.max.rounds"));
            if (!configuredMaxRounds.isEmpty()) {
                a.maxRounds = Math.max(1, parseIntOrDefault(configuredMaxRounds, a.maxRounds));
            }
            String configuredVerifyLastRepair = firstNonBlank(
                    System.getProperty("loop.verify.last.repair"),
                    props == null ? "" : props.getProperty("loop.verify.last.repair"));
            if (!configuredVerifyLastRepair.isEmpty()) {
                a.verifyLastRepair = Boolean.parseBoolean(configuredVerifyLastRepair.trim());
            }
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
    }

    private static Properties loadLoopProperties() {
        Properties props = new Properties();
        try (InputStream in = LLMTestGeneratorBatchLoop.class.getClassLoader()
                .getResourceAsStream(LOOP_PROPERTIES_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
        }
        return props;
    }

    private static int parseIntOrDefault(String text, int fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String rowArgsForLlm(Args a) {
        if (a.rowEnd > 0 && a.rowStart == a.rowEnd) {
            return " --row " + a.rowStart;
        }
        return " --rowStart " + a.rowStart + " --rowEnd " + a.rowEnd;
    }

    private static String rowArgsForEvidence(Args a) {
        return " --rowStart " + a.rowStart + " --rowEnd " + a.rowEnd;
    }

    private static String rowsSelectorArgs(Args a, int round, List<Integer> rows, String stage) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        if (rows.size() <= ROWS_ARG_INLINE_LIMIT) {
            return " --rows " + rowsArg(rows);
        }
        Path rowsFile = a.runDir.resolve("round-" + round).resolve(stage + "-active-rows.txt");
        writeRowsFile(rowsFile, rows);
        return " --rows-file " + quoteArg(rowsFile.toAbsolutePath().normalize().toString());
    }

    private static void writeRowsFile(Path rowsFile, List<Integer> rows) throws IOException {
        Files.createDirectories(rowsFile.getParent());
        List<String> lines = new ArrayList<String>();
        for (Integer row : rows) {
            if (row != null && row > 0) {
                lines.add(String.valueOf(row));
            }
        }
        Files.write(rowsFile, lines, StandardCharsets.UTF_8);
    }

    private static String rowsArg(List<Integer> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer row : rows) {
            if (row == null || row <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(row);
        }
        return sb.toString();
    }
}
