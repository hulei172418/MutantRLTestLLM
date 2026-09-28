package org;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.model.MutationConfig;
import static org.utils.ExcelUtils.readExcel;
import org.utils.JavaTargetMetadata;
import org.utils.PathSanitizer;

public final class OutputJsonBatchRunner {

    private static final List<Process> RUNNING_WORKER_PROCESSES = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean SHUTDOWN_REQUESTED = false;
    private static volatile Path ACTIVE_STOP_FILE = null;
    private static volatile Path ACTIVE_PID_FILE = null;
    private static volatile Path ACTIVE_RUN_LOG_FILE = null;
    private static final Map<String, String> WORKER_ECLIPSE_VERSIONS = workerEclipseVersions();

    /*
     * Direct-run configuration.
     *
     * Edit these constants, then run OutputJsonBatchRunner.main() directly from the
     * IDE,
     * or run: java -cp <full-classpath> org.OutputJsonBatchRunner
     *
     * Command-line arguments are still supported. If args are provided, they
     * override
     * this direct-run configuration.
     */
    private static final String DIRECT_EXCEL_FILE = "./data/commons-lang3-3.17.0-4.xlsx";
    private static final String DIRECT_MODE = "process"; // single | thread | process
    private static final int DIRECT_WORKERS = 8;
    private static final boolean DIRECT_REWRITE = true;
    private static final int DIRECT_ROW_START = 1;
    private static final int DIRECT_ROW_END = -1; // -1 means run to the last row
    private static final String DIRECT_RUN_DIR = ""; // blank means auto: logs/output_json_parallel/...
    private static final String DIRECT_WORKER_XMX = ""; // e.g., "6g"; blank means use JVM default
    private static final boolean DIRECT_PRINT_ROW_PATHS = true; // print: "<row> th: <mutant path>"
    private static final boolean DIRECT_PRINT_PROGRESS = true; // print START/DONE progress in console

    private OutputJsonBatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        Args a = (args == null || args.length == 0) ? Args.fromDirectConfig() : Args.parse(args);
        if (a.excel == null || a.excel.trim().isEmpty() || "E:/PHD/testJava/Programs/xxx.xlsx".equals(a.excel)) {
            System.err.println(
                    "Please edit DIRECT_EXCEL_FILE in OutputJsonBatchRunner.java, or pass --excel <xlsx> from the command line.");
            System.err.println("Current excel=" + a.excel);
            System.exit(64);
        }
        if (DIRECT_WORKER_XMX != null && !DIRECT_WORKER_XMX.trim().isEmpty()
                && System.getProperty("outputjson.worker.xmx") == null) {
            System.setProperty("outputjson.worker.xmx", DIRECT_WORKER_XMX.trim());
        }

        printConfig(a);

        List<Task> tasks = loadTasks(a);
        if (tasks.isEmpty()) {
            System.out.println("No tasks to run.");
            return;
        }

        Path runDir = PathSanitizer.path(a.runDir == null ? defaultRunDir(a.excel) : a.runDir);
        Files.createDirectories(runDir);
        Path summary = runDir.resolve("summary.tsv");
        Path stopFile = runDir.resolve("STOP");
        Path pidFile = runDir.resolve("pids.txt");
        Path runLog = runDir.resolve("output_json_batch.log");
        Files.deleteIfExists(stopFile);
        Files.deleteIfExists(pidFile);
        Files.deleteIfExists(runLog);
        ACTIVE_STOP_FILE = stopFile;
        ACTIVE_PID_FILE = pidFile;
        ACTIVE_RUN_LOG_FILE = runLog;
        installShutdownHookOnce();

        System.out.println("mode=" + a.mode + ", workers=" + a.workers + ", tasks=" + tasks.size());
        System.out.println("summary=" + summary.toAbsolutePath());
        System.out.println("stopFile=" + stopFile.toAbsolutePath() + "  (create this file to request a graceful stop)");
        System.out
                .println("pidFile=" + pidFile.toAbsolutePath() + "  (contains parent/worker PIDs for manual taskkill)");
        System.out.println("logFile=" + runLog.toAbsolutePath() + "  (single combined log for all workers)");
        appendPidLine("parent", currentPid(), "OutputJsonBatchRunner");

        List<Result> results;
        switch (a.mode) {
            case "single":
                results = runSingle(tasks);
                break;
            case "thread":
                // Safe but not faster for Soot. OutputJsonWorker serializes the Soot section.
                results = runThread(tasks, Math.max(1, a.workers));
                break;
            case "process":
                results = runProcess(tasks, Math.max(1, a.workers), runDir, stopFile, pidFile, runLog);
                break;
            default:
                throw new IllegalArgumentException("Unknown mode: " + a.mode);
        }

        writeSummary(summary, results);
        long ok = results.stream().filter(r -> r.exitCode == 0).count();
        System.out.println("done. ok=" + ok + ", failed=" + (results.size() - ok));
    }

    private static void printConfig(Args a) {
        System.out.println("========== OutputJsonBatchRunner ==========");
        System.out.println("excel      = " + a.excel);
        System.out.println("mode       = " + a.mode);
        System.out.println("workers    = " + a.workers);
        System.out.println("rewrite    = " + a.rewrite);
        System.out.println("rowStart   = " + a.rowStart);
        System.out.println("rowEnd     = " + a.rowEnd);
        System.out.println("rows       = " + (a.rows == null || a.rows.isEmpty() ? "<range>" : a.rows));
        System.out.println("runDir     = " + (a.runDir == null || a.runDir.trim().isEmpty() ? "<auto>" : a.runDir));
        String xmx = System.getProperty("outputjson.worker.xmx", "");
        System.out.println("workerXmx  = " + (xmx.trim().isEmpty() ? "<default>" : xmx));
        System.out.println("printRows  = " + DIRECT_PRINT_ROW_PATHS);
        System.out.println("progress   = " + DIRECT_PRINT_PROGRESS);
        System.out.println("===========================================");
    }

    private static List<Task> loadTasks(Args a) throws IOException {
        List<List<Object>> rows = readExcel(a.excel, 0);
        if (rows == null || rows.size() <= 1) {
            return Collections.emptyList();
        }
        List<Task> tasks = new ArrayList<>();
        int start = Math.max(1, a.rowStart);
        int end = a.rowEnd <= 0 ? rows.size() - 1 : Math.min(a.rowEnd, rows.size() - 1);
        for (int i = start; i <= end; i++) {
            if (a.rows != null && !a.rows.isEmpty() && !a.rows.contains(i)) {
                continue;
            }
            MutationConfig c = configFromRow(rows.get(i));
            if (!a.rewrite && Files.isRegularFile(outputJsonPath(c))) {
                if (DIRECT_PRINT_ROW_PATHS) {
                    printRowPath(i, c, "SKIP existing output.json");
                }
                continue;
            }
            if (DIRECT_PRINT_ROW_PATHS) {
                printRowPath(i, c, null);
            }
            tasks.add(new Task(i, c));
        }
        return tasks;
    }

    private static MutationConfig configFromRow(List<Object> row) {
        MutationConfig config = new MutationConfig();
        config.operator = asString(row, 0);
        config.lineNo = lineNo(row.size() > 1 ? row.get(1) : null);
        config.methodName = asString(row, 2);
        config.className = asString(row, 3);
        config.classNameF = asString(row, 4);
        config.mutationStatement = asString(row, 5);
        config.packageName = asString(row, 6);
        config.rawTargetClassId = config.packageName;
        config.projectName = asString(row, 7);
        String sourcePath = PathSanitizer.clean(asString(row, 8));
        JavaTargetMetadata.applyDeclaredPackage(config, sourcePath);
        String parent = new File(sourcePath).getParent();
        if (parent == null || parent.trim().isEmpty()) {
            parent = sourcePath;
        }
        config.filepath = normalizeMutantDirectory(parent, config.operator);
        return config;
    }

    private static String normalizeMutantDirectory(String dir, String operator) {
        if (dir == null || dir.trim().isEmpty()) {
            return "";
        }
        Path base = PathSanitizer.path(dir);
        if (base.getFileName() != null && base.getFileName().toString().endsWith(".java")) {
            base = base.getParent();
        }
        String op = operator == null ? "" : operator.trim();
        if (!op.isEmpty() && (base.getFileName() == null || !op.equals(base.getFileName().toString()))) {
            Path byOperator = base.resolve(op);
            if (Files.isDirectory(byOperator)) {
                base = byOperator;
            }
        }
        return PathSanitizer.normalizeForJson(base);
    }

    private static String asString(List<Object> row, int idx) {
        if (idx < 0 || idx >= row.size() || row.get(idx) == null) {
            return "";
        }
        return String.valueOf(row.get(idx));
    }

    private static String lineNo(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return (d % 1 == 0) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private static Path outputJsonPath(MutationConfig c) {
        return PathSanitizer.path(c.filepath, "graph", "output.json");
    }

    private static List<Result> runSingle(List<Task> tasks) {
        List<Result> out = new ArrayList<>();
        for (Task t : tasks) {
            printProgress("START", t, -1, null);
            long start = System.nanoTime();
            int code = OutputJsonWorker.runOne(t.rowIndex, t.config, true);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            printProgress(code == 0 ? "DONE" : "FAIL", t, ms, null);
            out.add(Result.of(t, code, ms, ""));
        }
        return out;
    }

    private static List<Result> runThread(List<Task> tasks, int workers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Task t : tasks) {
                futures.add(pool.submit(() -> {
                    printProgress("START", t, -1, null);
                    long start = System.nanoTime();
                    int code = OutputJsonWorker.runOne(t.rowIndex, t.config, true);
                    long ms = (System.nanoTime() - start) / 1_000_000L;
                    printProgress(code == 0 ? "DONE" : "FAIL", t, ms, null);
                    return Result.of(t, code, ms, "");
                }));
            }
            List<Result> out = new ArrayList<>();
            for (Future<Result> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Result> runProcess(List<Task> tasks, int workers, Path runDir, Path stopFile, Path pidFile,
            Path runLog) throws Exception {
        /*
         * Persistent-process mode:
         * v19 started one JVM per row. That is safe but often slower than single mode
         * because
         * every row pays JVM/Soot/classpath cold-start cost and loses per-JVM caches.
         * v20 starts at most <workers> JVMs. Each JVM processes a chunk of rows
         * sequentially.
         * This preserves process isolation across workers while amortizing startup cost
         * and
         * allowing per-worker origin/source caches to be reused.
         */
        int n = Math.max(1, Math.min(workers, tasks.size()));
        String workerClasspath = buildWorkerClasspath(runDir, runLog);
        List<List<Task>> chunks = splitRoundRobin(tasks, n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<List<Result>>> futures = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                List<Task> chunk = chunks.get(i);
                if (chunk.isEmpty()) {
                    continue;
                }
                futures.add(pool.submit(processChunkCallable(i, chunk, workerClasspath, stopFile, pidFile, runLog)));
            }
            List<Result> out = new ArrayList<>();
            for (Future<List<Result>> f : futures) {
                out.addAll(f.get());
            }
            out.sort((a, b) -> Integer.compare(a.task.rowIndex, b.task.rowIndex));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<List<Task>> splitRoundRobin(List<Task> tasks, int workers) {
        List<List<Task>> chunks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            chunks.add(new ArrayList<>());
        }
        for (int i = 0; i < tasks.size(); i++) {
            chunks.get(i % workers).add(tasks.get(i));
        }
        return chunks;
    }

    private static Callable<List<Result>> processChunkCallable(int workerIndex, List<Task> chunk, String workerClasspath,
            Path stopFile, Path pidFile, Path runLog) {
        return () -> {
            Path log = runLog;

            for (Task task : chunk) {
                printProgress("QUEUED", task, -1, log.toAbsolutePath().toString());
            }

            if (SHUTDOWN_REQUESTED || Files.exists(stopFile)) {
                List<Result> stopped = new ArrayList<>();
                for (Task task : chunk) {
                    stopped.add(Result.of(task, 130, 0, log.toAbsolutePath().toString()));
                }
                return stopped;
            }

            long start = System.nanoTime();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExecutable());
            String xmx = System.getProperty("outputjson.worker.xmx", System.getenv("OUTPUT_JSON_WORKER_XMX"));
            if (xmx != null && !xmx.trim().isEmpty()) {
                cmd.add("-Xmx" + xmx.trim());
            }
            cmd.add("-cp");
            cmd.add(workerClasspath);
            cmd.add("org.OutputJsonWorker");
            cmd.add("--stdin");
            cmd.add("--stop");
            cmd.add(stopFile.toAbsolutePath().toString());
            cmd.add("--parentPid");
            cmd.add(String.valueOf(currentPid()));

            int processCode;
            Process p = null;
            List<Result> parsed = new ArrayList<>();
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                p = pb.start();
                registerWorkerProcess(workerIndex, p, log, pidFile);

                final Process workerProcess = p;
                Thread stdinWriter = new Thread(() -> {
                    try (BufferedWriter bw = new BufferedWriter(
                            new OutputStreamWriter(workerProcess.getOutputStream(), StandardCharsets.UTF_8))) {
                        for (Task task : chunk) {
                            if (SHUTDOWN_REQUESTED || Files.exists(stopFile)) {
                                break;
                            }
                            bw.write(MutationConfigCodec.encodeLine(task.config, task.rowIndex));
                            bw.newLine();
                        }
                    } catch (Throwable e) {
                        appendRunLogLine(log, "[WORKER-" + workerIndex + "] Failed to send tasks over stdin: " + e);
                    }
                }, "output-json-worker-" + workerIndex + "-stdin-writer");
                stdinWriter.setDaemon(true);
                stdinWriter.start();

                // Stream worker output in real time. The previous version wrote all
                // worker output only to per-worker log files and printed DONE/FAIL only after
                // the
                // whole persistent worker exited, so the main console looked idle.
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        appendRunLogLine(log, "[WORKER-" + workerIndex + "] " + line);
                        Result r = parseWorkerResultLine(line, chunk, log);
                        if (r != null) {
                            parsed.add(r);
                            printProgress(r.exitCode == 0 ? "DONE" : "FAIL", r.task, r.durationMs,
                                    log.toAbsolutePath().toString());
                        } else if (DIRECT_PRINT_PROGRESS && isImportantWorkerLine(line)) {
                            synchronized (System.out) {
                                System.out.println("[WORKER-" + workerIndex + "] " + line);
                            }
                        }
                    }
                }
                processCode = p.waitFor();
                try {
                    stdinWriter.join(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } catch (Throwable e) {
                processCode = 3;
                appendRunLogLine(log, "[WORKER-" + workerIndex + "] Failed to launch persistent worker: " + e);
            } finally {
                if (p != null) {
                    RUNNING_WORKER_PROCESSES.remove(p);
                }
            }
            long totalMs = (System.nanoTime() - start) / 1_000_000L;

            if (parsed.isEmpty() || parsed.size() < chunk.size()) {
                List<Result> fallback = new ArrayList<>();
                for (Task task : chunk) {
                    fallback.add(Result.of(task, processCode == 0 ? 2 : processCode, totalMs,
                            log.toAbsolutePath().toString()));
                }
                List<Result> merged = mergeParsedWithFallback(parsed, fallback);
                for (Result r : merged) {
                    if (!containsRow(parsed, r.task.rowIndex)) {
                        printProgress(r.exitCode == 0 ? "DONE" : "FAIL", r.task, r.durationMs,
                                log.toAbsolutePath().toString());
                    }
                }
                parsed = merged;
            }
            return parsed;
        };
    }

    private static Result parseWorkerResultLine(String line, List<Task> chunk, Path log) {
        if (line == null || !line.startsWith("ROW_RESULT\t")) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 4) {
            return null;
        }
        int row = parseInt(parts[1], -1);
        int code = parseInt(parts[2], 2);
        long ms = parseLong(parts[3], -1L);
        Task task = findTask(chunk, row);
        if (task == null) {
            return null;
        }
        return Result.of(task, code, ms, log == null ? "" : log.toAbsolutePath().toString());
    }

    private static boolean containsRow(List<Result> results, int rowIndex) {
        if (results == null) {
            return false;
        }
        for (Result r : results) {
            if (r != null && r.task != null && r.task.rowIndex == rowIndex) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImportantWorkerLine(String line) {
        if (line == null) {
            return false;
        }
        return line.startsWith("Analysis failed")
                || line.startsWith("Exception")
                || line.startsWith("java.")
                || line.startsWith("Caused by:")
                || line.contains("Method not found")
                || line.contains("OutOfMemoryError");
    }

    private static List<Result> parseWorkerResults(Path log, List<Task> chunk) {
        List<Result> results = new ArrayList<>();
        if (log == null || !Files.isRegularFile(log)) {
            return results;
        }
        try {
            List<String> lines = Files.readAllLines(log);
            for (String line : lines) {
                if (line == null || !line.startsWith("ROW_RESULT\t")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 4) {
                    continue;
                }
                int row = parseInt(parts[1], -1);
                int code = parseInt(parts[2], 2);
                long ms = parseLong(parts[3], -1L);
                Task task = findTask(chunk, row);
                if (task != null) {
                    results.add(Result.of(task, code, ms, log.toAbsolutePath().toString()));
                }
            }
        } catch (IOException ignored) {
        }
        return results;
    }

    private static List<Result> mergeParsedWithFallback(List<Result> parsed, List<Result> fallback) {
        List<Result> out = new ArrayList<>(parsed);
        for (Result fb : fallback) {
            boolean seen = false;
            for (Result r : parsed) {
                if (r.task.rowIndex == fb.task.rowIndex) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                out.add(fb);
            }
        }
        out.sort((a, b) -> Integer.compare(a.task.rowIndex, b.task.rowIndex));
        return out;
    }

    private static Task findTask(List<Task> tasks, int rowIndex) {
        for (Task t : tasks) {
            if (t.rowIndex == rowIndex) {
                return t;
            }
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void printRowPath(int rowIndex, MutationConfig c, String suffix) {
        synchronized (System.out) {
            System.out.print(rowIndex + " th: ");
            System.out.println(c == null ? "" : c.filepath);
            if (suffix != null && !suffix.trim().isEmpty()) {
                System.out.println("    " + suffix);
            }
        }
    }

    private static void printProgress(String phase, Task t, long durationMs, String logFile) {
        if (!DIRECT_PRINT_PROGRESS || t == null) {
            return;
        }
        synchronized (System.out) {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(phase).append("] ");
            sb.append(t.rowIndex).append(" th: ");
            sb.append(t.config == null ? "" : t.config.filepath);
            if (durationMs >= 0) {
                sb.append(" | ").append(durationMs).append(" ms");
            }
            if (logFile != null && !logFile.trim().isEmpty()) {
                sb.append(" | log=").append(logFile);
            }
            System.out.println(sb.toString());
        }
    }

    private static void appendRunLogLine(Path logFile, String line) {
        if (logFile == null) {
            return;
        }
        synchronized (OutputJsonBatchRunner.class) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.write(logFile, ((line == null ? "" : line) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installShutdownHookOnce() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SHUTDOWN_REQUESTED = true;
            try {
                Path stop = ACTIVE_STOP_FILE;
                if (stop != null) {
                    Files.write(stop,
                            ("stop requested by OutputJsonBatchRunner shutdown hook at " + Instant.now()
                                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            destroyAllWorkerProcesses("shutdown-hook");
        }, "output-json-batch-shutdown"));
    }

    private static void registerWorkerProcess(int workerIndex, Process process, Path log, Path pidFile) {
        if (process == null) {
            return;
        }
        RUNNING_WORKER_PROCESSES.add(process);
        long pid = -1L;
        try {
            pid = processPid(process);
        } catch (Throwable ignored) {
        }
        appendPidLine("worker-" + workerIndex, pid,
                log == null ? "" : log.toAbsolutePath().toString());
        if (DIRECT_PRINT_PROGRESS) {
            synchronized (System.out) {
                System.out.println("[SPAWN] worker=" + workerIndex + ", pid=" + pid
                        + ", log=" + (log == null ? "" : log.toAbsolutePath()));
            }
        }
    }

    private static void appendPidLine(String role, long pid, String note) {
        Path f = ACTIVE_PID_FILE;
        if (f == null) {
            return;
        }
        synchronized (OutputJsonBatchRunner.class) {
            try {
                Files.createDirectories(f.getParent());
                String line = role + "\t" + pid + "\t" + (note == null ? "" : note)
                        + "\t" + Instant.now() + System.lineSeparator();
                Files.write(f, line.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
            }
        }
    }

    private static long processPid(Process process) {
        if (process == null) {
            return -1L;
        }
        try {
            java.lang.reflect.Method pidMethod = process.getClass().getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name == null ? -1 : name.indexOf('@');
            String pid = at > 0 ? name.substring(0, at) : name;
            return Long.parseLong(pid);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static void destroyAllWorkerProcesses(String reason) {
        List<Process> copy;
        synchronized (RUNNING_WORKER_PROCESSES) {
            copy = new ArrayList<>(RUNNING_WORKER_PROCESSES);
        }
        if (DIRECT_PRINT_PROGRESS) {
            synchronized (System.out) {
                System.out.println("[SHUTDOWN] reason=" + reason + ", runningWorkers=" + copy.size());
            }
        }
        for (Process p : copy) {
            destroyProcessTree(p, reason);
        }
    }

    private static void destroyProcessTree(Process p, String reason) {
        if (p == null) {
            return;
        }
        try {
            p.destroy();
            Thread.sleep(800L);
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return PathSanitizer.path(home, "bin", win ? "java.exe" : "java").toString();
    }

    private static String buildWorkerClasspath(Path runDir, Path logFile) {
        List<String> preferredParts = loadPreferredWorkerClasspathParts(logFile);
        String raw = String.join(File.pathSeparator, preferredParts);
        if (raw.trim().isEmpty()) {
            raw = System.getProperty("java.class.path", "");
        }
        if (raw.trim().isEmpty()) {
            return raw;
        }
        String[] parts = raw.split(java.util.regex.Pattern.quote(File.pathSeparator));
        Map<String, Path> selectedEclipseJars = selectWorkerEclipseJars(parts);
        Set<String> emittedEclipse = new LinkedHashSet<>();
        Set<String> emittedPaths = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>();
        Path unsignedDir = runDir.resolve("worker-classpath").resolve("unsigned");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            Path path = toExistingPath(part);
            if (path == null) {
                continue;
            }
            String artifact = eclipseArtifactId(path);
            if (artifact == null) {
                String normalizedPath = path.toAbsolutePath().normalize().toString();
                if (emittedPaths.add(normalizedPath)) {
                    normalized.add(normalizedPath);
                }
                continue;
            }
            Path chosen = selectedEclipseJars.get(artifact);
            if (chosen == null || !samePath(chosen, path) || !emittedEclipse.add(artifact)) {
                continue;
            }
            Path unsigned = prepareUnsignedJar(chosen, unsignedDir, logFile);
            Path finalPath = unsigned == null ? chosen : unsigned;
            String normalizedPath = finalPath.toAbsolutePath().normalize().toString();
            if (emittedPaths.add(normalizedPath)) {
                normalized.add(normalizedPath);
            }
        }
        maybeLogWorkerClasspath(logFile, normalized, selectedEclipseJars);
        return normalized.isEmpty() ? raw : String.join(File.pathSeparator, normalized);
    }

    private static List<String> loadPreferredWorkerClasspathParts(Path logFile) {
        List<String> parts = new ArrayList<>();
        try {
            Path classesDir = locateCurrentClassesDir();
            Path moduleDir = locateEvidenceParseModuleDir(classesDir);
            if (classesDir == null || moduleDir == null) {
                return parts;
            }
            parts.add(classesDir.toAbsolutePath().normalize().toString());
            addWorkspaceRuntimeDependencyParts(moduleDir, parts, logFile);
            Path cpFile = moduleDir.resolve("logs").resolve("worker-classpath").resolve("worker.cp.txt");
            if (!Files.isRegularFile(cpFile)) {
                appendRunLogLine(logFile, "[WORKER-CP] worker.cp.txt not found, trying to generate: " + cpFile);
                generateWorkerClasspathFile(moduleDir, cpFile, logFile);
            }
            if (!Files.isRegularFile(cpFile)) {
                appendRunLogLine(logFile,
                        "[WORKER-CP] worker.cp.txt still missing; using classes dir only before final fallback: " + cpFile);
                return parts;
            }
            String cpText = new String(Files.readAllBytes(cpFile), StandardCharsets.UTF_8).trim();
            if (!cpText.trim().isEmpty()) {
                String[] cpParts = cpText.split(java.util.regex.Pattern.quote(File.pathSeparator));
                for (String cpPart : cpParts) {
                    if (cpPart != null && !cpPart.trim().isEmpty()) {
                        String trimmed = cpPart.trim();
                        if (shouldSkipConflictingWorkspaceDependency(trimmed)) {
                            appendRunLogLine(logFile, "[WORKER-CP] skipped conflicting dependency from worker.cp.txt: " + trimmed);
                            continue;
                        }
                        parts.add(trimmed);
                    }
                }
            }
            appendRunLogLine(logFile, "[WORKER-CP] using module classpath file: " + cpFile.toAbsolutePath());
        } catch (Throwable t) {
            appendRunLogLine(logFile, "[WORKER-CP] failed to load module classpath file, fallback to java.class.path: " + t);
            parts.clear();
        }
        return parts;
    }

    private static void addWorkspaceRuntimeDependencyParts(Path moduleDir, List<String> parts, Path logFile) {
        if (moduleDir == null || parts == null) {
            return;
        }
        Path workspaceRoot = moduleDir.getParent();
        if (workspaceRoot == null) {
            return;
        }
        addIfExists(parts, workspaceRoot.resolve("CodeKB").resolve("target").resolve("classes"), logFile,
                "[WORKER-CP] added workspace dependency classes: ");
        addIfExists(parts, workspaceRoot.resolve("CodeKB").resolve("target").resolve("CodeKB-1.0.jar"), logFile,
                "[WORKER-CP] added workspace dependency jar: ");
    }

    private static void addIfExists(List<String> parts, Path candidate, Path logFile, String logPrefix) {
        if (candidate == null || parts == null || !Files.exists(candidate)) {
            return;
        }
        String normalized = candidate.toAbsolutePath().normalize().toString();
        if (parts.contains(normalized)) {
            return;
        }
        parts.add(normalized);
        appendRunLogLine(logFile, logPrefix + normalized);
    }

    private static boolean shouldSkipConflictingWorkspaceDependency(String cpPart) {
        if (cpPart == null || cpPart.trim().isEmpty()) {
            return false;
        }
        String normalized = cpPart.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/org/rip/evidence/codekb/1.0/codekb-1.0.jar");
    }

    private static void generateWorkerClasspathFile(Path moduleDir, Path cpFile, Path logFile) {
        try {
            Files.createDirectories(cpFile.getParent());
            List<String> cmd = new ArrayList<>();
            cmd.add(mavenExecutable());
            cmd.add("-q");
            cmd.add("-f");
            cmd.add(moduleDir.resolve("pom.xml").toAbsolutePath().normalize().toString());
            cmd.add("-DskipTests");
            cmd.add("dependency:build-classpath");
            cmd.add("-Dmdep.outputFile=" + cpFile.toAbsolutePath().normalize());
            cmd.add("-Dmdep.pathSeparator=" + File.pathSeparator);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(moduleDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        appendRunLogLine(logFile, "[WORKER-CP-MVN] " + line);
                    }
                }
            }
            int exit = p.waitFor();
            appendRunLogLine(logFile, "[WORKER-CP] dependency classpath generation exitCode=" + exit);
        } catch (Throwable t) {
            appendRunLogLine(logFile, "[WORKER-CP] failed to generate worker classpath file: " + t);
        }
    }

    private static String mavenExecutable() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return win ? "mvn.cmd" : "mvn";
    }

    private static Path locateCurrentClassesDir() {
        try {
            java.net.URI uri = OutputJsonBatchRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path locateEvidenceParseModuleDir(Path classesDir) {
        List<Path> candidates = new ArrayList<>();
        Path userDir = toExistingPath(System.getProperty("user.dir", ""));
        if (userDir != null) {
            candidates.add(userDir);
            Path child = userDir.resolve("EvidenceParse");
            if (Files.isDirectory(child)) {
                candidates.add(child);
            }
        }
        if (classesDir != null) {
            Path p = classesDir;
            for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
                candidates.add(p);
                Path child = p.resolve("EvidenceParse");
                if (Files.isDirectory(child)) {
                    candidates.add(child);
                }
            }
        }
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("src").resolve("main").resolve("java").resolve("org")
                            .resolve("OutputJsonBatchRunner.java"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path toExistingPath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            Path p = Paths.get(raw).toAbsolutePath().normalize();
            return Files.exists(p) ? p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, Path> selectWorkerEclipseJars(String[] parts) {
        Map<String, Path> selected = new LinkedHashMap<>();
        for (String part : parts) {
            Path path = toExistingPath(part);
            if (path == null) {
                continue;
            }
            String artifact = eclipseArtifactId(path);
            if (artifact == null) {
                continue;
            }
            String preferredVersion = WORKER_ECLIPSE_VERSIONS.get(artifact);
            String candidateVersion = jarVersion(path);
            Path current = selected.get(artifact);
            if (current == null) {
                selected.put(artifact, path);
                continue;
            }
            if (preferredVersion != null) {
                boolean candidatePreferred = preferredVersion.equals(candidateVersion);
                boolean currentPreferred = preferredVersion.equals(jarVersion(current));
                if (candidatePreferred && !currentPreferred) {
                    selected.put(artifact, path);
                }
            }
        }
        return selected;
    }

    private static String eclipseArtifactId(Path path) {
        if (path == null) {
            return null;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!fileName.endsWith(".jar")) {
            return null;
        }
        int dash = fileName.lastIndexOf('-');
        if (dash <= 0) {
            return null;
        }
        String artifact = fileName.substring(0, dash);
        if (artifact.startsWith("org.eclipse.") || "org.eclipse.jdt.core".equals(artifact)) {
            return artifact;
        }
        return null;
    }

    private static String jarVersion(Path path) {
        if (path == null) {
            return "";
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!fileName.endsWith(".jar")) {
            return "";
        }
        int dash = fileName.lastIndexOf('-');
        int dot = fileName.lastIndexOf(".jar");
        if (dash < 0 || dot < 0 || dash >= dot) {
            return "";
        }
        return fileName.substring(dash + 1, dot);
    }

    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Path prepareUnsignedJar(Path sourceJar, Path unsignedDir, Path logFile) {
        if (sourceJar == null) {
            return null;
        }
        try {
            Files.createDirectories(unsignedDir);
            String fileName = sourceJar.getFileName().toString();
            String unsignedName = fileName.endsWith(".jar")
                    ? fileName.substring(0, fileName.length() - 4) + "-unsigned.jar"
                    : fileName + "-unsigned";
            Path target = unsignedDir.resolve(unsignedName).toAbsolutePath().normalize();
            if (Files.isRegularFile(target)) {
                return target;
            }
            synchronized (("unsigned-jar:" + target).intern()) {
                if (Files.isRegularFile(target)) {
                    return target;
                }
                Path temp = Files.createTempFile(unsignedDir, unsignedName + ".", ".tmp").toAbsolutePath().normalize();
                try (JarFile jarFile = new JarFile(sourceJar.toFile());
                        OutputStream fileOut = Files.newOutputStream(temp);
                        JarOutputStream jarOut = new JarOutputStream(fileOut)) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (isSignatureEntry(entry.getName())) {
                            continue;
                        }
                        JarEntry outEntry = new JarEntry(entry.getName());
                        outEntry.setTime(entry.getTime());
                        jarOut.putNextEntry(outEntry);
                        if (!entry.isDirectory()) {
                            try (InputStream in = jarFile.getInputStream(entry)) {
                                copyStream(in, jarOut);
                            }
                        }
                        jarOut.closeEntry();
                    }
                }
                try {
                    Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailure) {
                    Files.copy(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    try {
                        if (Files.exists(temp)) {
                            Files.deleteIfExists(temp);
                        }
                    } catch (IOException cleanupFailure) {
                        appendRunLogLine(logFile, "[WORKER-CP] temporary unsigned jar cleanup skipped: " + temp
                                + " : " + cleanupFailure);
                    }
                }
            }
            appendRunLogLine(logFile, "[WORKER-CP] unsigned eclipse jar: " + sourceJar + " -> " + target);
            return target;
        } catch (Throwable t) {
            appendRunLogLine(logFile, "[WORKER-CP] failed to create unsigned jar for " + sourceJar + ": " + t);
            return sourceJar.toAbsolutePath().normalize();
        }
    }

    private static boolean isSignatureEntry(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    private static void maybeLogWorkerClasspath(Path logFile, List<String> normalized, Map<String, Path> selectedEclipseJars) {
        appendRunLogLine(logFile, "[WORKER-CP] normalized entries=" + normalized.size());
        for (String entry : normalized) {
            appendRunLogLine(logFile, "[WORKER-CP] " + entry);
        }
        List<String> artifacts = new ArrayList<>(selectedEclipseJars.keySet());
        artifacts.sort(Comparator.naturalOrder());
        for (String artifact : artifacts) {
            appendRunLogLine(logFile, "[WORKER-CP] selected " + artifact + "=" + selectedEclipseJars.get(artifact));
        }
    }

    private static Map<String, String> workerEclipseVersions() {
        Map<String, String> versions = new HashMap<>();
        versions.put("org.eclipse.jdt.core", "3.12.3");
        versions.put("org.eclipse.core.resources", "3.12.0");
        versions.put("org.eclipse.core.runtime", "3.13.0");
        versions.put("org.eclipse.core.filesystem", "1.10.0");
        versions.put("org.eclipse.text", "3.12.0");
        versions.put("org.eclipse.core.expressions", "3.6.0");
        versions.put("org.eclipse.osgi", "3.12.0");
        versions.put("org.eclipse.equinox.common", "3.10.0");
        versions.put("org.eclipse.core.jobs", "3.10.0");
        versions.put("org.eclipse.equinox.registry", "3.10.0");
        versions.put("org.eclipse.equinox.preferences", "3.10.1");
        versions.put("org.eclipse.core.contenttype", "3.6.0");
        versions.put("org.eclipse.equinox.app", "1.3.400");
        versions.put("org.eclipse.core.commands", "3.10.0");
        return versions;
    }

    private static void writeSummary(Path summary, List<Result> results) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(summary)) {
            w.write("rowIndex\texitCode\tdurationMs\toperator\tlineNo\tprojectName\tclassName\tmethodName\tmutantPath\toutputJson\tlogFile");
            w.newLine();
            for (Result r : results) {
                MutationConfig c = r.task.config;
                w.write(joinTsv(
                        String.valueOf(r.task.rowIndex),
                        String.valueOf(r.exitCode),
                        String.valueOf(r.durationMs),
                        c.operator,
                        c.lineNo,
                        c.projectName,
                        c.className,
                        c.methodName,
                        c.filepath,
                        outputJsonPath(c).toString(),
                        r.logFile));
                w.newLine();
            }
        }
    }

    private static String joinTsv(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(tsv(parts[i]));
        }
        return sb.toString();
    }

    private static String tsv(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String defaultRunDir(String excel) {
        String name = new File(excel).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return "logs/output_json_parallel/" + name + "_" + Instant.now().toString().replace(':', '-');
    }

    private static final class Task {
        final int rowIndex;
        final MutationConfig config;

        Task(int rowIndex, MutationConfig config) {
            this.rowIndex = rowIndex;
            this.config = config;
        }
    }

    private static final class Result {
        final Task task;
        final int exitCode;
        final long durationMs;
        final String logFile;

        private Result(Task task, int exitCode, long durationMs, String logFile) {
            this.task = task;
            this.exitCode = exitCode;
            this.durationMs = durationMs;
            this.logFile = logFile == null ? "" : logFile;
        }

        static Result of(Task task, int exitCode, long durationMs, String logFile) {
            return new Result(task, exitCode, durationMs, logFile);
        }
    }

    private static final class Args {
        String excel;
        String mode = "process";
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        boolean rewrite = true;
        int rowStart = 1;
        int rowEnd = -1;
        Set<Integer> rows = Collections.emptySet();
        String runDir;

        static Args fromDirectConfig() {
            Args a = new Args();
            a.excel = DIRECT_EXCEL_FILE;
            a.mode = DIRECT_MODE == null ? "process" : DIRECT_MODE.toLowerCase(Locale.ROOT);
            a.workers = Math.max(1, DIRECT_WORKERS);
            a.rewrite = DIRECT_REWRITE;
            a.rowStart = Math.max(1, DIRECT_ROW_START);
            a.rowEnd = DIRECT_ROW_END;
            a.rows = Collections.emptySet();
            a.runDir = (DIRECT_RUN_DIR == null || DIRECT_RUN_DIR.trim().isEmpty()) ? null : DIRECT_RUN_DIR;
            return a;
        }

        static Args parse(String[] args) {
            Args a = fromDirectConfig();
            for (int i = 0; i < args.length; i++) {
                String k = args[i];
                String v = (i + 1 < args.length) ? args[i + 1] : null;
                if ("--excel".equals(k) && v != null) {
                    a.excel = v;
                    i++;
                } else if ("--mode".equals(k) && v != null) {
                    a.mode = v.toLowerCase(Locale.ROOT);
                    i++;
                } else if ("--workers".equals(k) && v != null) {
                    a.workers = Integer.parseInt(v);
                    i++;
                } else if ("--rewrite".equals(k) && v != null) {
                    a.rewrite = Boolean.parseBoolean(v);
                    i++;
                } else if (("--rowStart".equals(k) || "--row-start".equals(k)) && v != null) {
                    a.rowStart = Integer.parseInt(v);
                    i++;
                } else if (("--rowEnd".equals(k) || "--row-end".equals(k)) && v != null) {
                    a.rowEnd = Integer.parseInt(v);
                    i++;
                } else if ("--rows".equals(k) && v != null) {
                    a.rows = parseRows(v);
                    if (!a.rows.isEmpty()) {
                        a.rowStart = Collections.min(a.rows);
                        a.rowEnd = Collections.max(a.rows);
                    }
                    i++;
                } else if ("--rows-file".equals(k) && v != null) {
                    a.rows = parseRowsFile(v);
                    if (!a.rows.isEmpty()) {
                        a.rowStart = Collections.min(a.rows);
                        a.rowEnd = Collections.max(a.rows);
                    }
                    i++;
                } else if (("--runDir".equals(k) || "--run-dir".equals(k)) && v != null) {
                    a.runDir = v;
                    i++;
                } else if (!k.startsWith("--") && a.excel == null) {
                    a.excel = k;
                }
            }
            return a;
        }

        private static Set<Integer> parseRows(String value) {
            Set<Integer> parsed = new LinkedHashSet<>();
            if (value == null || value.trim().isEmpty()) {
                return parsed;
            }
            for (String part : value.split(",")) {
                try {
                    int row = Integer.parseInt(part.trim());
                    if (row > 0) {
                        parsed.add(row);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed row tokens so one typo does not abort the whole batch.
                }
            }
            return parsed;
        }

        private static Set<Integer> parseRowsFile(String value) {
            Set<Integer> parsed = new LinkedHashSet<>();
            if (value == null || value.trim().isEmpty()) {
                return parsed;
            }
            Path path = Paths.get(value.trim()).toAbsolutePath().normalize();
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line == null ? "" : line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    parsed.addAll(parseRows(trimmed));
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read --rows-file: " + path, e);
            }
            return parsed;
        }
    }
}
