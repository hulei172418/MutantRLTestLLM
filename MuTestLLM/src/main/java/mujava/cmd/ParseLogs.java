package mujava.cmd;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseLogs {
    private static final String SEP = "####";
    private static final String CLASS_SUFFIX = ".class";
    private static final Pattern KILLED_MUTANTS_PATTERN = Pattern.compile("killed_mutants\\s*=\\s*\\[(.*?)\\]\\s*;?",
            Pattern.DOTALL);
    private static final int EXCEL_CHUNK_SIZE = 500000;

    private final Path fileDirs;
    private final Path outputDir;
    private final String projectLabel;

    public ParseLogs(String fileDirs) {
        this.fileDirs = toLongPath(fileDirs);
        this.outputDir = Paths.get("./", "data").toAbsolutePath().normalize();
        this.projectLabel = resolveProjectLabel(this.fileDirs);
    }

    public static void main(String[] args) {
        // String fDirs = "../testJava/" + "Programs" + "/oot";
        String fDirs = "../testJava/" + "Programs" + "/commons-lang3-3.17.0";
        // String fDirs = "../testJava/" + "Programs" + "/commons-cli-1.11.0";
        // String fDirs = "../testJava/" + "Programs" + "/commons-math-4.0-beta1";
        // String fDirs = "../testJava/" + "Programs" + "/joda-time-2.14.0";
        // String fDirs = "../testJava/" + "Programs" + "/bcel-6.10.0";
        // String fDirs = "../testJava/" + "Programs" + "/ant-1.10.12";
        // String fDirs = "../testJava/" + "Programs" + "/jackson-core-2.9.9";
        // String fDirs = "../testJava/" + "Programs" + "/commons-math-4.0-beta1";
        // String fDirs = "../testJava/" + "Programs" + "/commons-codec-1.10";
        // String fDirs = "../testJava/" + "Programs" + "/commons-csv-1.2";
        // String fDirs = "../testJava/" + "Programs";

        try {
            new ParseLogs(fDirs).run();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() throws IOException {
        Files.createDirectories(outputDir);
        // deleteGraphs(false);
        ParseResult parseResult = parseLog();
        statisticAnalysis(parseResult.methodRows, parseResult.mutantRows);
        getGraphs(parseResult.mutantRows);
    }

    public ParseResult parseLog() throws IOException {
        List<Map<String, Object>> methodRows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> mutantRows = new ArrayList<Map<String, Object>>();

        Files.walkFileTree(fileDirs, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                DirectoryArtifacts artifacts = collectDirectoryArtifacts(dir);
                if (!artifacts.hasRelevantFiles()) {
                    return FileVisitResult.CONTINUE;
                }

                List<String> killedMutants = artifacts.killStatistic != null
                        ? extractKilledList(readText(artifacts.killStatistic))
                        : Collections.<String>emptyList();
                List<Map<String, Object>> pathRows = buildPathRows(dir, killedMutants);
                if (pathRows.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }

                if (artifacts.methodList != null) {
                    System.out.println(artifacts.methodList);
                    methodRows.addAll(cleanMethod(pathRows, readLogFile(artifacts.methodList,
                            new String[] { "method" })));
                }
                if (artifacts.mutationLog != null) {
                    System.out.println(artifacts.mutationLog);
                    mutantRows.addAll(cleanMutants(pathRows, readLogFile(artifacts.mutationLog,
                            new String[] { "operator", "line", "method", "class", "class_f", "mutation_statement" })));
                }
                if (artifacts.killStatistic != null) {
                    System.out.println(artifacts.killStatistic);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new ParseResult(methodRows, mutantRows);
    }

    private DirectoryArtifacts collectDirectoryArtifacts(Path dir) throws IOException {
        DirectoryArtifacts artifacts = new DirectoryArtifacts();
        try {
            Files.list(dir).forEach(path -> {
                String name = path.getFileName().toString();
                if ("method_list.txt".equals(name)) {
                    artifacts.methodList = path;
                } else if ("mutation_log.txt".equals(name)) {
                    artifacts.mutationLog = path;
                } else if ("kill_statistic.txt".equals(name)) {
                    artifacts.killStatistic = path;
                }
            });
        } catch (IOException e) {
            throw e;
        }
        return artifacts;
    }

    private List<Map<String, Object>> buildPathRows(Path root, List<String> killedMutants) throws IOException {
        List<Path> classFiles = findFiles(root, CLASS_SUFFIX);
        if (classFiles.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> killedSet = new LinkedHashSet<String>(killedMutants);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Path classFile : classFiles) {
            Path normalized = classFile.toAbsolutePath().normalize();
            String[] parts = normalized.toString().split(Pattern.quote("\\"));
            if (parts.length < 3) {
                continue;
            }

            int resultIndex = indexOf(parts, "result");
            if (resultIndex < 0 || resultIndex + 1 >= parts.length) {
                continue;
            }

            String method = parts[parts.length - 3];
            String operator = parts[parts.length - 2];
            String project = resultIndex > 0 ? parts[resultIndex - 1] : "";
            String packageName = parts[resultIndex + 1];
            String className = stripSuffix(parts[parts.length - 1], CLASS_SUFFIX);

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("operator", operator);
            row.put("method", method);
            row.put("class_f", className);
            row.put("package", packageName);
            row.put("project", project);
            row.put("file_path", normalized.toString());
            row.put("is_killed", killedSet.contains(operator) ? 1 : 0);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> cleanMethod(List<Map<String, Object>> pathRows,
            List<Map<String, String>> methodRows) {
        Map<String, Map<String, Object>> uniqueByMethod = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> row : pathRows) {
            String method = asString(row.get("method"));
            if (!uniqueByMethod.containsKey(method)) {
                uniqueByMethod.put(method, row);
            }
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, String> methodRow : methodRows) {
            Map<String, Object> pathRow = uniqueByMethod.get(asString(methodRow.get("method")));
            if (pathRow == null) {
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            merged.putAll(methodRow);
            merged.putAll(pathRow);
            result.add(merged);
        }
        return result;
    }

    private List<Map<String, Object>> cleanMutants(List<Map<String, Object>> pathRows,
            List<Map<String, String>> logRows) {
        Map<String, List<Map<String, Object>>> pathIndex = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Map<String, Object> row : pathRows) {
            String key = joinKey(asString(row.get("method")), asString(row.get("operator")),
                    asString(row.get("class_f")));
            List<Map<String, Object>> bucket = pathIndex.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Map<String, Object>>();
                pathIndex.put(key, bucket);
            }
            bucket.add(row);
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, String> logRow : logRows) {
            String key = joinKey(logRow.get("method"), logRow.get("operator"), logRow.get("class_f"));
            List<Map<String, Object>> matches = pathIndex.get(key);
            if (matches == null) {
                continue;
            }
            for (Map<String, Object> match : matches) {
                Map<String, Object> merged = new LinkedHashMap<String, Object>();
                merged.putAll(logRow);
                merged.putAll(match);
                result.add(merged);
            }
        }
        return result;
    }

    private List<Map<String, String>> readLogFile(Path file, String[] columns) throws IOException {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            String[] values = line.split(Pattern.quote(SEP), -1);
            Map<String, String> row = new LinkedHashMap<String, String>();
            for (int i = 0; i < columns.length; i++) {
                row.put(columns[i], i < values.length ? values[i] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<Path> findFiles(Path directory, String endsWith) throws IOException {
        List<Path> result = new ArrayList<Path>();
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(endsWith)) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private List<String> extractKilledList(String text) {
        Matcher matcher = KILLED_MUTANTS_PATTERN.matcher(text);
        if (matcher.find()) {
            return splitCsvTokens(matcher.group(1));
        }

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.contains("=")) {
                continue;
            }
            line = line.replace("];", "").replace("]", "").replace(";", "").trim();
            return splitCsvTokens(line);
        }
        return Collections.emptyList();
    }

    private List<String> splitCsvTokens(String source) {
        if (source == null || source.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String item : source.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private void statisticAnalysis(List<Map<String, Object>> methodRows,
            List<Map<String, Object>> mutantRows) throws IOException {
        if (mutantRows.isEmpty()) {
            System.out.println("目前没数据");
            return;
        }

        Set<String> classes = new LinkedHashSet<String>();
        Set<String> methods = new LinkedHashSet<String>();
        for (Map<String, Object> row : mutantRows) {
            classes.add(asString(row.get("class")));
            methods.add(asString(row.get("method")));
        }

        System.out.println(String.format(Locale.ROOT,
                "类总数：%s%n方法总数：%s%n变异体总数：%s%n",
                classes.size(), methods.size(), mutantRows.size()));

        exportChunkedExcel(methodRows, "method_statistic-" + projectLabel);
        exportChunkedExcel(mutantRows, "mutant_statistic-" + projectLabel);
    }

    private List<Map<String, Object>> getGraphs(List<Map<String, Object>> mutantRows) throws IOException {
        if (mutantRows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> graphRows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : mutantRows) {
            Map<String, Object> copied = new LinkedHashMap<String, Object>(row);
            String filePath = asString(row.get("file_path"));
            copied.put("original_graph_path", getParent(filePath, 4));
            copied.put("mutant_graph_path", Paths.get(filePath).getParent().resolve("graph").toString());

            boolean originalOk = validateGraphPath(asString(copied.get("original_graph_path")));
            boolean mutantOk = validateGraphPath(asString(copied.get("mutant_graph_path")));
            if (originalOk && mutantOk) {
                graphRows.add(copied);
            }
        }

        Set<String> originalGraphPaths = new LinkedHashSet<String>();
        Set<String> mutantGraphPaths = new LinkedHashSet<String>();
        Set<String> mutantFiles = new LinkedHashSet<String>();
        for (Map<String, Object> row : graphRows) {
            originalGraphPaths.add(asString(row.get("original_graph_path")));
            mutantGraphPaths.add(asString(row.get("mutant_graph_path")));
            mutantFiles.add(asString(row.get("file_path")));
        }

        System.out.println(String.format(Locale.ROOT,
                "变异体总数：%s%ngraph总数：%s%n源程序graph数：%s%n变异体graph数：%s%n",
                mutantFiles.size(),
                originalGraphPaths.size() + mutantGraphPaths.size(),
                originalGraphPaths.size(),
                graphRows.size()));

        exportChunkedExcel(graphRows, "graph");
        return graphRows;
    }

    public void deleteGraphs(boolean delete) throws IOException {
        if (!delete) {
            return;
        }
        Files.walkFileTree(fileDirs, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (!"graph".equals(dir.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }

                deleteGraphFiles(dir, Arrays.asList("ast.jsonl", "cfg.jsonl", "dfg.jsonl", "manifest.json"));
                deleteGraphFiles(dir,
                        Arrays.asList("ast_embedding.jsonl", "cfg_embedding.jsonl", "dfg_embedding.jsonl"));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteGraphFiles(Path graphDir, List<String> requiredFiles) throws IOException {
        boolean filesExist = true;
        for (String file : requiredFiles) {
            if (!Files.isRegularFile(graphDir.resolve(file))) {
                filesExist = false;
                break;
            }
        }
        if (!filesExist) {
            return;
        }

        for (String file : requiredFiles) {
            Path target = graphDir.resolve(file);
            try {
                Files.deleteIfExists(target);
                System.out.println("已删除文件 " + target);
            } catch (IOException e) {
                System.out.println("删除文件失败 " + target + ": " + e.getMessage());
            }
        }

        try {
            Files.deleteIfExists(graphDir);
            System.out.println("已删除文件夹: " + graphDir);
        } catch (IOException e) {
            System.out.println("删除文件夹失败 " + graphDir + ": " + e.getMessage());
        }
    }

    private boolean validateGraphPath(String graphPath) {
        Path path = Paths.get(graphPath);
        if (!"graph".equals(path.getFileName().toString())) {
            return false;
        }
        return Files.isRegularFile(path.resolve("ast.jsonl"))
                && Files.isRegularFile(path.resolve("cfg.jsonl"))
                && Files.isRegularFile(path.resolve("dfg.jsonl"))
                && Files.isRegularFile(path.resolve("manifest.json"));
    }

    private String getParent(String filePath, int level) {
        Path dir = Paths.get(filePath);
        for (int i = 0; i < level && dir != null; i++) {
            dir = dir.getParent();
        }
        if (dir == null) {
            return "";
        }
        String methodName = Paths.get(filePath).getParent() != null
                && Paths.get(filePath).getParent().getParent() != null
                        ? Paths.get(filePath).getParent().getParent().getFileName().toString()
                        : "";
        return dir.resolve("original").resolve(methodName).resolve("graph").toString();
    }

    private void exportChunkedExcel(List<Map<String, Object>> rows, String baseFilename) throws IOException {
        if (rows.isEmpty()) {
            return;
        }

        List<String> headers = new ArrayList<String>(rows.get(0).keySet());
        int totalRows = rows.size();
        int numChunks = (totalRows + EXCEL_CHUNK_SIZE - 1) / EXCEL_CHUNK_SIZE;

        for (int i = 0; i < numChunks; i++) {
            int start = i * EXCEL_CHUNK_SIZE;
            int end = Math.min(start + EXCEL_CHUNK_SIZE, totalRows);
            Path outputFile = outputDir.resolve(baseFilename + "_" + (i + 1) + ".xlsx");

            try (Workbook workbook = new XSSFWorkbook();
                    OutputStream out = Files.newOutputStream(outputFile)) {
                Sheet sheet = workbook.createSheet("data");
                writeHeader(sheet.createRow(0), headers);
                for (int rowIndex = start; rowIndex < end; rowIndex++) {
                    writeDataRow(sheet.createRow(rowIndex - start + 1), headers, rows.get(rowIndex));
                }
                workbook.write(out);
            }

            System.out.println("已导出 " + outputFile + "，行数：" + (end - start));
        }
    }

    private void writeHeader(Row row, List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            row.createCell(i).setCellValue(headers.get(i));
        }
    }

    private void writeDataRow(Row row, List<String> headers, Map<String, Object> data) {
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(i);
            Object value = data.get(headers.get(i));
            if (value == null) {
                cell.setCellValue("");
            } else if (value instanceof Number) {
                cell.setCellValue(((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                cell.setCellValue((Boolean) value);
            } else {
                cell.setCellValue(String.valueOf(value));
            }
        }
    }

    private String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Path toLongPath(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private String resolveProjectLabel(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "unknown";
        }
        String label = fileName.toString().trim();
        return label.isEmpty() ? "unknown" : label;
    }

    private int indexOf(String[] parts, String target) {
        for (int i = 0; i < parts.length; i++) {
            if (target.equals(parts[i])) {
                return i;
            }
        }
        return -1;
    }

    private String stripSuffix(String text, String suffix) {
        return text.endsWith(suffix) ? text.substring(0, text.length() - suffix.length()) : text;
    }

    private String joinKey(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('\u0000');
            }
            builder.append(parts[i] == null ? "" : parts[i]);
        }
        return builder.toString();
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static final class ParseResult {
        public final List<Map<String, Object>> methodRows;
        public final List<Map<String, Object>> mutantRows;

        public ParseResult(List<Map<String, Object>> methodRows, List<Map<String, Object>> mutantRows) {
            this.methodRows = methodRows;
            this.mutantRows = mutantRows;
        }
    }

    private static final class DirectoryArtifacts {
        private Path methodList;
        private Path mutationLog;
        private Path killStatistic;

        private boolean hasRelevantFiles() {
            return methodList != null || mutationLog != null || killStatistic != null;
        }
    }
}
