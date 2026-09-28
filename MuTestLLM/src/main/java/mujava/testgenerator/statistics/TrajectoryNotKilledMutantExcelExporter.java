package mujava.testgenerator.statistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Export the latest killed / not-killed mutants from trajectory.jsonl and map
 * them back to the original Excel rows.
 *
 * Filter rule:
 * - use mutantId as the mutant identity
 * - if any record under the same mutantId is killed=true, classify it as killed
 * - otherwise classify it as not-killed
 * - export one deduplicated row for each mutantId into each bucket
 *
 * Output columns:
 * operator, line, method, class, class_f, mutation_statement, package, project,
 * file_path, original_graph_path, mutant_graph_path, equivalent
 */
public class TrajectoryNotKilledMutantExcelExporter {

    private static final String DEFAULT_PROJECT = "commons-lang3-3.17.0";
    private static final String DEFAULT_TRAJECTORY = "logs/auto-loop/20260802170727635/rl/trajectory.jsonl";
    private static final String DEFAULT_EXCEL = "data/" + DEFAULT_PROJECT + ".xlsx";
    private static final String DEFAULT_OUTPUT_DIR = "data/trajectory-not-killed-from-jsonl";
    private static final String DEFAULT_OUTPUT_BASE_NAME = DEFAULT_PROJECT + "-trajectory";

    private static final List<String> OUTPUT_COLUMNS = Arrays.asList(
            "operator",
            "line",
            "method",
            "class",
            "class_f",
            "mutation_statement",
            "package",
            "project",
            "file_path",
            "original_graph_path",
            "mutant_graph_path",
            "equivalent");

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        Path trajectoryPath = resolveInputPath(parsed.trajectoryPath, DEFAULT_TRAJECTORY);
        Path excelPath = resolveInputPath(parsed.excelPath, DEFAULT_EXCEL);
        Path outputDir = resolveOutputPath(parsed.outputDir, DEFAULT_OUTPUT_DIR);
        String outputBaseName = isBlank(parsed.outputName) ? DEFAULT_OUTPUT_BASE_NAME : parsed.outputName.trim();

        Files.createDirectories(outputDir);

        ExcelData excelData = loadExcel(excelPath);
        TrajectoryBuckets buckets = loadBucketsByMutantId(trajectoryPath, excelData);
        List<ExportRow> killedRows = buildExportRows(excelData, buckets.killedByMutantId);
        List<ExportRow> notKilledRows = buildExportRows(excelData, buckets.notKilledByMutantId);

        Path killedOutput = outputDir.resolve(outputBaseName + "-killed.xlsx").toAbsolutePath().normalize();
        Path notKilledOutput = outputDir.resolve(outputBaseName + "-not-killed.xlsx").toAbsolutePath().normalize();
        writeWorkbook(killedOutput, "killed", killedRows);
        writeWorkbook(notKilledOutput, "not_killed", notKilledRows);

        System.out.println("Trajectory: " + trajectoryPath.toAbsolutePath().normalize());
        System.out.println("Excel: " + excelPath.toAbsolutePath().normalize());
        System.out.println("Killed output: " + killedOutput);
        System.out.println("Not-killed output: " + notKilledOutput);
        System.out.println("Deduped killed mutants: " + killedRows.size());
        System.out.println("Deduped not-killed mutants: " + notKilledRows.size());
    }

    private static ExcelData loadExcel(Path excelPath) throws IOException {
        try (InputStream in = Files.newInputStream(excelPath);
                Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> it = sheet.rowIterator();
            if (!it.hasNext()) {
                throw new IOException("Excel has no header row: " + excelPath);
            }

            Row headerRow = it.next();
            List<String> header = new ArrayList<String>();
            Map<String, Integer> headerIndex = new LinkedHashMap<String, Integer>();
            for (Cell cell : headerRow) {
                String name = text(cell).trim();
                header.add(name);
                headerIndex.put(name, cell.getColumnIndex());
            }

            Map<Integer, ExcelRow> rowsByExcelRowNo = new LinkedHashMap<Integer, ExcelRow>();
            Map<MatchKey, ExcelRow> rowsByKey = new LinkedHashMap<MatchKey, ExcelRow>();
            while (it.hasNext()) {
                Row row = it.next();
                int excelRowNo = row.getRowNum() + 1;
                List<String> values = new ArrayList<String>();
                for (int i = 0; i < header.size(); i++) {
                    values.add(text(row.getCell(i)));
                }

                ExcelRow excelRow = new ExcelRow(excelRowNo, header, headerIndex, values);
                rowsByExcelRowNo.put(excelRowNo, excelRow);
                rowsByKey.put(excelRow.matchKey(), excelRow);
            }
            return new ExcelData(rowsByExcelRowNo, rowsByKey);
        }
    }

    private static TrajectoryBuckets loadBucketsByMutantId(Path trajectoryPath,
            ExcelData excelData) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, TrajectoryRecord> latestByMutantId = new LinkedHashMap<String, TrajectoryRecord>();
        Map<String, Boolean> killedByMutantId = new LinkedHashMap<String, Boolean>();
        int lineNo = 0;
        for (String line : Files.readAllLines(trajectoryPath)) {
            lineNo++;
            if (isBlank(line)) {
                continue;
            }
            JsonNode root = mapper.readTree(line);
            TrajectoryRecord record = TrajectoryRecord.fromJson(lineNo, root);
            if (isBlank(record.mutantId)) {
                continue;
            }
            ExcelRow excelRow = findExcelRow(excelData, record);
            if (excelRow == null) {
                continue;
            }
            latestByMutantId.put(record.mutantId, record);
            if (record.killed) {
                killedByMutantId.put(record.mutantId, Boolean.TRUE);
            } else if (!killedByMutantId.containsKey(record.mutantId)) {
                killedByMutantId.put(record.mutantId, Boolean.FALSE);
            }
        }

        Map<String, TrajectoryRecord> killed = new LinkedHashMap<String, TrajectoryRecord>();
        Map<String, TrajectoryRecord> notKilled = new LinkedHashMap<String, TrajectoryRecord>();
        for (Map.Entry<String, TrajectoryRecord> entry : latestByMutantId.entrySet()) {
            Boolean killedEver = killedByMutantId.get(entry.getKey());
            if (Boolean.TRUE.equals(killedEver)) {
                killed.put(entry.getKey(), entry.getValue());
            } else {
                notKilled.put(entry.getKey(), entry.getValue());
            }
        }
        return new TrajectoryBuckets(killed, notKilled);
    }

    private static List<ExportRow> buildExportRows(ExcelData excelData,
            Map<String, TrajectoryRecord> latestNotKilled) {
        List<ExportRow> rows = new ArrayList<ExportRow>();
        for (TrajectoryRecord record : latestNotKilled.values()) {
            ExcelRow excelRow = findExcelRow(excelData, record);
            if (excelRow == null) {
                continue;
            }

            ExportRow out = new ExportRow();
            out.values.put("operator", excelRow.value("operator"));
            out.values.put("line", excelRow.value("line"));
            out.values.put("method", excelRow.value("method"));
            out.values.put("class", excelRow.value("class"));
            out.values.put("class_f", excelRow.value("class_f"));
            out.values.put("mutation_statement", excelRow.value("mutation_statement"));
            out.values.put("package", excelRow.value("package"));
            out.values.put("project", excelRow.value("project"));
            out.values.put("file_path", excelRow.value("file_path"));
            out.values.put("original_graph_path", excelRow.value("original_graph_path"));
            out.values.put("mutant_graph_path", excelRow.value("mutant_graph_path"));

            out.values.put("equivalent", equivalent01(excelRow.value("equivalent"), record.equivalenceSuspicion));
            rows.add(out);
        }
        return rows;
    }

    private static ExcelRow findExcelRow(ExcelData excelData, TrajectoryRecord record) {
        ExcelRow keyed = excelData.rowsByKey.get(new MatchKey(
                safe(record.project),
                safe(record.className),
                safe(record.method),
                safe(record.operator)));
        if (keyed != null) {
            return keyed;
        }
        Integer rowNo = record.excelRowNo();
        if (rowNo != null) {
            ExcelRow exact = excelData.rowsByExcelRowNo.get(rowNo);
            if (exact != null
                    && Objects.equals(exact.value("project"), safe(record.project))
                    && Objects.equals(exact.value("package"), safe(record.className))
                    && Objects.equals(exact.value("method"), safe(record.method))
                    && Objects.equals(exact.value("operator"), safe(record.operator))) {
                return exact;
            }
        }
        return null;
    }

    private static void writeWorkbook(Path output, String sheetName, List<ExportRow> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int i = 0; i < OUTPUT_COLUMNS.size(); i++) {
                header.createCell(i).setCellValue(OUTPUT_COLUMNS.get(i));
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                ExportRow exportRow = rows.get(r);
                for (int c = 0; c < OUTPUT_COLUMNS.size(); c++) {
                    row.createCell(c).setCellValue(exportRow.values.get(OUTPUT_COLUMNS.get(c)));
                }
            }

            for (int i = 0; i < OUTPUT_COLUMNS.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }

    private static Path resolveInputPath(String configured, String fallback) {
        List<Path> candidates = new ArrayList<Path>();
        if (!isBlank(configured)) {
            candidates.add(Paths.get(configured.trim()));
        } else {
            candidates.add(Paths.get(fallback));
            candidates.add(Paths.get("..").resolve(fallback));
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalized)) {
                return normalized;
            }
        }
        return candidates.get(0).toAbsolutePath().normalize();
    }

    private static Path resolveOutputPath(String configured, String fallback) {
        Path path = Paths.get(isBlank(configured) ? fallback : configured.trim()).normalize();
        return path.isAbsolute() ? path : path.toAbsolutePath().normalize();
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return new DataFormatter().formatCellValue(cell);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String equivalent01(String excelEquivalent, boolean trajectoryEquivalent) {
        String normalized = safe(excelEquivalent).toLowerCase();
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
            return "1";
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
            return "0";
        }
        return trajectoryEquivalent ? "1" : "0";
    }

    private static final class Args {
        private String trajectoryPath;
        private String excelPath;
        private String outputDir;
        private String outputName;

        static Args parse(String[] args) {
            Args parsed = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--trajectory".equals(arg) && i + 1 < args.length) {
                    parsed.trajectoryPath = args[++i];
                } else if ("--excel".equals(arg) && i + 1 < args.length) {
                    parsed.excelPath = args[++i];
                } else if ("--outputDir".equals(arg) && i + 1 < args.length) {
                    parsed.outputDir = args[++i];
                } else if ("--outputName".equals(arg) && i + 1 < args.length) {
                    parsed.outputName = args[++i];
                }
            }
            return parsed;
        }
    }

    private static final class ExcelData {
        private final Map<Integer, ExcelRow> rowsByExcelRowNo;
        private final Map<MatchKey, ExcelRow> rowsByKey;

        private ExcelData(Map<Integer, ExcelRow> rowsByExcelRowNo,
                Map<MatchKey, ExcelRow> rowsByKey) {
            this.rowsByExcelRowNo = rowsByExcelRowNo;
            this.rowsByKey = rowsByKey;
        }
    }

    private static final class ExcelRow {
        private final int excelRowNo;
        private final List<String> header;
        private final Map<String, Integer> headerIndex;
        private final List<String> values;

        private ExcelRow(int excelRowNo,
                List<String> header,
                Map<String, Integer> headerIndex,
                List<String> values) {
            this.excelRowNo = excelRowNo;
            this.header = header;
            this.headerIndex = headerIndex;
            this.values = values;
        }

        private String value(String column) {
            Integer index = headerIndex.get(column);
            if (index == null || index < 0 || index >= values.size()) {
                return "";
            }
            return values.get(index).trim();
        }

        private MatchKey matchKey() {
            return new MatchKey(value("project"), value("package"), value("method"), value("operator"));
        }
    }

    private static final class TrajectoryRecord {
        private final int lineNo;
        private final String taskId;
        private final String mutantId;
        private final String project;
        private final String className;
        private final String method;
        private final String operator;
        private final boolean killed;
        private final boolean equivalenceSuspicion;

        private TrajectoryRecord(int lineNo,
                String taskId,
                String mutantId,
                String project,
                String className,
                String method,
                String operator,
                boolean killed,
                boolean equivalenceSuspicion) {
            this.lineNo = lineNo;
            this.taskId = taskId;
            this.mutantId = mutantId;
            this.project = project;
            this.className = className;
            this.method = method;
            this.operator = operator;
            this.killed = killed;
            this.equivalenceSuspicion = equivalenceSuspicion;
        }

        static TrajectoryRecord fromJson(int lineNo, JsonNode root) {
            return new TrajectoryRecord(
                    lineNo,
                    text(root, "taskId"),
                    text(root, "mutantId"),
                    text(root, "project"),
                    text(root, "className"),
                    text(root, "method"),
                    text(root, "operator"),
                    root.path("killed").asBoolean(false),
                    root.path("equivalenceSuspicion").asBoolean(false));
        }

        private Integer excelRowNo() {
            if (taskId == null) {
                return null;
            }
            String normalized = taskId.trim();
            if (!normalized.startsWith("row-")) {
                return null;
            }
            try {
                return Integer.parseInt(normalized.substring(4));
            } catch (NumberFormatException e) {
                return null;
            }
        }

    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private static final class MatchKey {
        private final String project;
        private final String packageName;
        private final String method;
        private final String operator;

        private MatchKey(String project, String packageName, String method, String operator) {
            this.project = safe(project);
            this.packageName = safe(packageName);
            this.method = safe(method);
            this.operator = safe(operator);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MatchKey)) {
                return false;
            }
            MatchKey matchKey = (MatchKey) o;
            return Objects.equals(project, matchKey.project)
                    && Objects.equals(packageName, matchKey.packageName)
                    && Objects.equals(method, matchKey.method)
                    && Objects.equals(operator, matchKey.operator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(project, packageName, method, operator);
        }
    }

    private static final class ExportRow {
        private final Map<String, String> values = new LinkedHashMap<String, String>();
    }

    private static final class TrajectoryBuckets {
        private final Map<String, TrajectoryRecord> killedByMutantId;
        private final Map<String, TrajectoryRecord> notKilledByMutantId;

        private TrajectoryBuckets(Map<String, TrajectoryRecord> killedByMutantId,
                Map<String, TrajectoryRecord> notKilledByMutantId) {
            this.killedByMutantId = killedByMutantId;
            this.notKilledByMutantId = notKilledByMutantId;
        }
    }
}
