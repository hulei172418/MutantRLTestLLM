package mujava.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tracks only processes started by this JVM and optionally cleans a tagged
 * process group. This avoids unsafe "kill all java.exe" cleanup.
 */
public final class ManagedProcessCleanup {
    public static final String PROCESS_GROUP_PROPERTY = "mutestllm.process.group";
    public static final String PROCESS_GROUP_ROOT_PID_PROPERTY = "mutestllm.process.group.rootPid";
    public static final String PROCESS_GROUP_ANCHOR_PID_PROPERTY = "mutestllm.process.group.anchorPid";
    public static final String PROCESS_GROUP_STOP_FILE_PROPERTY = "mutestllm.process.group.stopFile";

    private static final long DEFAULT_WAIT_MILLIS = 2000L;
    private static final Set<Process> ACTIVE_PROCESSES =
            Collections.synchronizedSet(new LinkedHashSet<Process>());
    private static volatile boolean shutdownHookInstalled;
    private static volatile boolean windowsWatchdogInstalled;
    private static volatile boolean stdinWatchdogInstalled;

    private ManagedProcessCleanup() {
    }

    public static void addProcessGroupProperty(List<String> command) {
        String groupId = currentProcessGroupId();
        if (command == null || groupId.isEmpty()) {
            return;
        }
        String rootPidProperty = PROCESS_GROUP_ROOT_PID_PROPERTY + "=" + currentProcessGroupRootPid();
        String anchorPidProperty = PROCESS_GROUP_ANCHOR_PID_PROPERTY + "=" + currentProcessGroupAnchorPid();
        String stopFile = currentProcessGroupStopFile();
        if (looksLikeJavaCommand(command)) {
            insertJavaProperty(command, "-D" + PROCESS_GROUP_PROPERTY + "=" + groupId);
            insertJavaProperty(command, "-D" + rootPidProperty);
            insertJavaProperty(command, "-D" + anchorPidProperty);
            if (!stopFile.isEmpty()) {
                insertJavaProperty(command, "-D" + PROCESS_GROUP_STOP_FILE_PROPERTY + "=" + stopFile);
            }
        } else if (looksLikeJavacCommand(command)) {
            insertJavacProperty(command, "-J-D" + PROCESS_GROUP_PROPERTY + "=" + groupId);
            insertJavacProperty(command, "-J-D" + rootPidProperty);
            insertJavacProperty(command, "-J-D" + anchorPidProperty);
            if (!stopFile.isEmpty()) {
                insertJavacProperty(command, "-J-D" + PROCESS_GROUP_STOP_FILE_PROPERTY + "=" + stopFile);
            }
        } else if (looksLikeMavenCommand(command)) {
            command.add("-D" + PROCESS_GROUP_PROPERTY + "=" + groupId);
            command.add("-D" + rootPidProperty);
            command.add("-D" + anchorPidProperty);
            if (!stopFile.isEmpty()) {
                command.add("-D" + PROCESS_GROUP_STOP_FILE_PROPERTY + "=" + stopFile);
            }
        }
    }

    public static String currentProcessGroupId() {
        String value = System.getProperty(PROCESS_GROUP_PROPERTY, "");
        return value == null ? "" : value.trim();
    }

    public static long currentProcessGroupRootPid() {
        String value = System.getProperty(PROCESS_GROUP_ROOT_PID_PROPERTY, "").trim();
        if (!value.isEmpty()) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0L) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        return currentPid();
    }

    public static long currentProcessGroupAnchorPid() {
        String value = System.getProperty(PROCESS_GROUP_ANCHOR_PID_PROPERTY, "").trim();
        if (!value.isEmpty()) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0L) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        return currentPid();
    }

    public static String currentProcessGroupStopFile() {
        String value = System.getProperty(PROCESS_GROUP_STOP_FILE_PROPERTY, "");
        return value == null ? "" : value.trim();
    }

    public static long currentProcessPid() {
        return currentPid();
    }

    public static Process register(Process process) {
        if (process == null) {
            return null;
        }
        installShutdownHookIfNeeded();
        ACTIVE_PROCESSES.add(process);
        recordProcessPid(process);
        return process;
    }

    public static void unregister(Process process) {
        if (process != null) {
            ACTIVE_PROCESSES.remove(process);
        }
    }

    public static void destroy(Process process) {
        destroy(process, DEFAULT_WAIT_MILLIS);
    }

    public static void destroy(Process process, long waitMillis) {
        if (process == null) {
            return;
        }
        ACTIVE_PROCESSES.remove(process);
        if (!isAlive(process)) {
            return;
        }
        try {
            process.destroy();
            process.waitFor(Math.max(250L, waitMillis), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Thread.currentThread().interrupt();
        }
        if (isAlive(process)) {
            try {
                process.destroyForcibly();
                process.waitFor(Math.max(250L, waitMillis), TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cleanupCurrentProcessGroup(String reason) {
        String groupId = currentProcessGroupId();
        if (groupId.isEmpty() || !isWindows() || !isProcessGroupOwner()) {
            return;
        }
        cleanupWindowsProcessGroup(groupId, reason);
    }

    public static void requestStopAndCleanupCurrentProcessGroup(String reason) {
        writeStopMarker(reason);
        cleanupCurrentProcessGroup(reason);
    }

    private static boolean isProcessGroupOwner() {
        long rootPid = currentProcessGroupRootPid();
        long selfPid = currentPid();
        return rootPid > 0L && selfPid > 0L && rootPid == selfPid;
    }

    private static void installShutdownHookIfNeeded() {
        if (shutdownHookInstalled) {
            return;
        }
        synchronized (ManagedProcessCleanup.class) {
            if (shutdownHookInstalled) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    List<Process> snapshot;
                    synchronized (ACTIVE_PROCESSES) {
                        snapshot = new ArrayList<Process>(ACTIVE_PROCESSES);
                    }
                    for (Process process : snapshot) {
                        destroy(process, DEFAULT_WAIT_MILLIS);
                    }
                    cleanupCurrentProcessGroup("shutdown-hook");
                }
            }, "managed-process-cleanup"));
            ensureWindowsProcessGroupWatchdog();
            ensureStdinWatchdog();
            shutdownHookInstalled = true;
        }
    }

    private static void ensureStdinWatchdog() {
        if (stdinWatchdogInstalled) {
            return;
        }
        if (!shouldEnableStdinWatchdog()) {
            return;
        }
        synchronized (ManagedProcessCleanup.class) {
            if (stdinWatchdogInstalled) {
                return;
            }
            if (!shouldEnableStdinWatchdog()) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    watchStdinForClosure();
                }
            }, "managed-process-stdin-watchdog");
            t.setDaemon(true);
            try {
                t.start();
                stdinWatchdogInstalled = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean shouldEnableStdinWatchdog() {
        String configured = System.getProperty("mutestllm.stdin.watchdog.enabled", "").trim();
        if (!configured.isEmpty()) {
            return Boolean.parseBoolean(configured);
        }
        if (isWindows()) {
            return false;
        }
        return System.console() != null;
    }

    private static void watchStdinForClosure() {
        try {
            InputStream in = System.in;
            if (in == null) {
                return;
            }
            while (true) {
                int value = in.read();
                if (value == -1) {
                    requestStopAndCleanupCurrentProcessGroup("stdin-closed");
                    Runtime.getRuntime().halt(0);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureWindowsProcessGroupWatchdog() {
        if (windowsWatchdogInstalled || !isWindows() || !isProcessGroupOwner()) {
            return;
        }
        synchronized (ManagedProcessCleanup.class) {
            if (windowsWatchdogInstalled || !isWindows() || !isProcessGroupOwner()) {
                return;
            }
            String groupId = currentProcessGroupId();
            long rootPid = currentPid();
            long parentPid = currentParentPid();
            long anchorPid = currentProcessGroupAnchorPid();
            String stopFile = currentProcessGroupStopFile();
            if (groupId.isEmpty() || rootPid <= 0L) {
                return;
            }
            List<String> cmd = new ArrayList<String>();
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-WindowStyle");
            cmd.add("Hidden");
            cmd.add("-Command");
            cmd.add(buildWindowsWatchdogScript(rootPid, parentPid, anchorPid, groupId, stopFile));
            try {
                new ProcessBuilder(cmd).redirectErrorStream(true).start();
                windowsWatchdogInstalled = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void cleanupWindowsProcessGroup(String groupId, String reason) {
        List<Long> pids = readRecordedProcessPids(groupId);
        if (pids.isEmpty()) {
            pids = findTaggedWindowsPids("-D" + PROCESS_GROUP_PROPERTY + "=" + groupId);
        }
        long selfPid = currentPid();
        for (Long pid : pids) {
            if (pid == null || pid.longValue() <= 0L || pid.longValue() == selfPid) {
                continue;
            }
            taskkill(pid.longValue(), reason);
        }
        deleteRecordedProcessPids(groupId);
    }

    private static void recordProcessPid(Process process) {
        String groupId = currentProcessGroupId();
        long pid = processPid(process);
        if (groupId.isEmpty() || pid <= 0L) {
            return;
        }
        Path file = processRegistryFile(groupId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file,
                    (String.valueOf(pid) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static List<Long> readRecordedProcessPids(String groupId) {
        Path file = processRegistryFile(groupId);
        if (!Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> out = new LinkedHashSet<Long>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    out.add(Long.valueOf(trimmed));
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return new ArrayList<Long>(out);
    }

    private static void deleteRecordedProcessPids(String groupId) {
        Path file = processRegistryFile(groupId);
        try {
            Files.deleteIfExists(file);
        } catch (Throwable ignored) {
        }
    }

    private static Path processRegistryFile(String groupId) {
        String safeGroupId = groupId == null ? "" : groupId.replaceAll("[^A-Za-z0-9._-]", "_");
        return Paths.get(System.getProperty("java.io.tmpdir"), "mutestllm-process-groups", safeGroupId + ".pids")
                .toAbsolutePath().normalize();
    }

    private static long processPid(Process process) {
        if (process == null) {
            return -1L;
        }
        try {
            Method pidMethod = Process.class.getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Long) {
                return ((Long) value).longValue();
            }
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static List<Long> findTaggedWindowsPids(String marker) {
        List<Long> out = new ArrayList<Long>();
        List<String> cmd = new ArrayList<String>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-Command");
        cmd.add("$tag='" + escapePowerShellSingleQuoted(marker) + "'; "
                + "Get-CimInstance Win32_Process | "
                + "Where-Object { $_.CommandLine -like ('*' + $tag + '*') } | "
                + "ForEach-Object { $_.ProcessId }");
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    out.add(Long.valueOf(line.trim()));
                } catch (Exception ignored) {
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        } finally {
            if (process != null && isAlive(process)) {
                destroy(process, 500L);
            }
        }
        return out;
    }

    private static void taskkill(long pid, String reason) {
        List<String> cmd = new ArrayList<String>();
        cmd.add("taskkill.exe");
        cmd.add("/T");
        cmd.add("/F");
        cmd.add("/PID");
        cmd.add(String.valueOf(pid));
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            System.out.println("[PROCESS-CLEANUP] killed pid=" + pid + " reason=" + safe(reason));
        } catch (Throwable ignored) {
        }
    }

    private static boolean looksLikeJavaCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String exe = basename(command.get(0)).toLowerCase(Locale.ROOT);
        return exe.equals("java") || exe.equals("java.exe");
    }

    private static boolean looksLikeMavenCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String exe = basename(command.get(0)).toLowerCase(Locale.ROOT);
        return exe.equals("mvn") || exe.equals("mvn.cmd") || exe.equals("mvn.bat");
    }

    private static boolean looksLikeJavacCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String exe = basename(command.get(0)).toLowerCase(Locale.ROOT);
        return exe.equals("javac") || exe.equals("javac.exe");
    }

    private static void insertJavaProperty(List<String> command, String property) {
        int index = 1;
        while (index < command.size()) {
            String arg = command.get(index);
            if ("-cp".equals(arg) || "-classpath".equals(arg)) {
                break;
            }
            if (arg != null && !arg.startsWith("-")) {
                break;
            }
            index++;
        }
        command.add(index, property);
    }

    private static void insertJavacProperty(List<String> command, String property) {
        int index = 1;
        while (index < command.size()) {
            String arg = command.get(index);
            if (arg == null || arg.isEmpty()) {
                break;
            }
            if (!arg.startsWith("-") || arg.endsWith(".java")) {
                break;
            }
            index++;
        }
        command.add(index, property);
    }

    private static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long currentParentPid() {
        if (!isWindows()) {
            return -1L;
        }
        long pid = currentPid();
        if (pid <= 0L) {
            return -1L;
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-Command");
        cmd.add("Get-CimInstance Win32_Process | "
                + "Where-Object { $_.ProcessId -eq " + pid + " } | "
                + "Select-Object -ExpandProperty ParentProcessId");
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String value = null;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    value = trimmed;
                    break;
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
            if (value == null) {
                return -1L;
            }
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (process != null && isAlive(process)) {
                destroy(process, 500L);
            }
        }
    }

    private static boolean isAlive(Process process) {
        if (process == null) {
            return false;
        }
        try {
            Method isAlive = Process.class.getMethod("isAlive");
            return Boolean.TRUE.equals(isAlive.invoke(process));
        } catch (Throwable ignored) {
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException alive) {
                return true;
            }
        }
    }

    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String buildWindowsWatchdogScript(long rootPid,
            long parentPid,
            long anchorPid,
            String groupId,
            String stopFile) {
        String escapedGroup = escapePowerShellSingleQuoted(groupId);
        String escapedStopFile = escapePowerShellSingleQuoted(stopFile == null ? "" : stopFile);
        return "$rootPid=" + rootPid + "; "
                + "$parentPid=" + parentPid + "; "
                + "$anchorPid=" + anchorPid + "; "
                + "$stopFile='" + escapedStopFile + "'; "
                + "$tag='-D" + PROCESS_GROUP_PROPERTY + "=" + escapedGroup + "'; "
                + "while (Get-Process -Id $rootPid -ErrorAction SilentlyContinue) { "
                + "if ($anchorPid -gt 0 -and $anchorPid -ne $rootPid -and -not (Get-Process -Id $anchorPid -ErrorAction SilentlyContinue)) { break }; "
                + "if ($parentPid -gt 0 -and -not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) { break }; "
                + "Start-Sleep -Milliseconds 750 "
                + "}; "
                + "if ($stopFile -ne '') { "
                + "try { New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($stopFile)) | Out-Null } catch {} "
                + "try { Set-Content -Path $stopFile -Value 'watchdog-stop' -Encoding UTF8 -Force } catch {} "
                + "}; "
                + "Get-CimInstance Win32_Process | "
                + "Where-Object { $_.CommandLine -like ('*' + $tag + '*') } | "
                + "ForEach-Object { try { taskkill.exe /T /F /PID $_.ProcessId | Out-Null } catch {} }";
    }

    private static void writeStopMarker(String reason) {
        String stopFile = currentProcessGroupStopFile();
        if (stopFile.isEmpty()) {
            return;
        }
        try {
            Path path = Paths.get(stopFile).toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String value = safe(reason);
            if (value.isEmpty()) {
                value = "stop-requested";
            }
            Files.write(path,
                    (value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    private static String escapePowerShellSingleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
