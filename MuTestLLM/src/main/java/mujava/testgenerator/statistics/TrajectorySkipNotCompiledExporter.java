package mujava.testgenerator.statistics;

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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Export non-executed trajectory records from RL trajectory.jsonl and match
 * them back to the original mutant Excel rows.
 *
 * Compatible with both the legacy SKIP_NOT_COMPILED status and the newer
 * unified final-status enums.
 *
 * Default output directory:
 * MuTestLLM/testgenerator-output/trajectory-skip-not-compiled
 *
 * Usage:
 * mvn -f MuTestLLM/pom.xml exec:java ^
 * -Dexec.mainClass=mujava.testgenerator.statistics.TrajectorySkipNotCompiledExporter
 * ^
 * -Dexec.args="--trajectory logs/rl/trajectory.jsonl --excel
 * data/commons-csv-1.2.xlsx"
 */
public class TrajectorySkipNotCompiledExporter {

    private static final String DEFAULT_TRAJECTORY = "logs/rl/trajectory.jsonl";
    private static final String DEFAULT_EXCEL = "data/oot-1.xlsx";
    private static final String DEFAULT_OUTPUT_DIR = "data/trajectory-status-analysis";

    private static final List<String> NON_EXECUTED_TARGET_STATUSES = Arrays.asList(
            "SKIP_NOT_COMPILED",
            "SKIPPED_BY_EVIDENCE",
            "LLM_OUTPUT_INVALID",
            "LLM_NO_VISIBLE_CODE",
            "COMPILE_FAILED_AFTER_GENERATION",
            "COMPILE_FAILED_AFTER_REPAIR",
            "API_FAILED",
            "GENERATION_FAILED",
            "TASK_CRASHED",
            "NOT_EXECUTED");
    private static final List<String> REQUIRED_COLUMNS = Arrays.asList(
            "operator", "method", "package", "project");
    private static final List<String> EXTRA_COLUMNS = Arrays.asList(
            "trajectory_line_no",
            "trajectory_targetStatus",
            "trajectory_action",
            "trajectory_compileSuccess",
            "trajectory_reward",
            "trajectory_failureReason",
            "trajectory_testName",
            "trajectory_fallbackLevel",
            "trajectory_generationStrategy",
            "trajectory_regenerationRound",
            "trajectory_exactBucketKey",
            "trajectory_coarseBucketKey");

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        Path trajectoryPath = resolveInputPath(parsed.trajectoryPath, DEFAULT_TRAJECTORY);
        Path excelPath = resolveInputPath(parsed.excelPath, DEFAULT_EXCEL);
        Path outputDir = resolveOutputPath(parsed.outputDir, DEFAULT_OUTPUT_DIR);

        Files.createDirectories(outputDir);

        ExcelData excelData = loadExcel(excelPath);
        TrajectoryData trajectoryData = loadTrajectory(trajectoryPath);

        List<MatchedRecord> expandedRecords = matchExpanded(excelData, trajectoryData.skipRecords);
        List<MatchedRecord> uniqueRecords = dedupeByMutant(expandedRecords);

        String baseName = stripExtension(excelPath.getFileName().toString());
        Path uniqueOutput = outputDir.resolve(baseName + "-skip-not-compiled-matched.xlsx");
        Path expandedOutput = outputDir.resolve(baseName + "-skip-not-compiled-expanded.xlsx");
        Path plainOutput = outputDir.resolve(baseName + "-skip-not-compiled-plain.xlsx");

        writeWorkbook(uniqueOutput, "skip_not_compiled_unique", excelData.header, uniqueRecords, true,
                trajectoryData.totalRecords, trajectoryData.skipRecordCount, trajectoryData.invalidJsonCount);
        writeWorkbook(expandedOutput, "skip_not_compiled_expanded", excelData.header, expandedRecords, true,
                trajectoryData.totalRecords, trajectoryData.skipRecordCount, trajectoryData.invalidJsonCount);
        writeWorkbook(plainOutput, "skip_not_compiled_plain", excelData.header, uniqueRecords, false,
                trajectoryData.totalRecords, trajectoryData.skipRecordCount, trajectoryData.invalidJsonCount);

        System.out.println("Trajectory: " + trajectoryPath.toAbsolutePath());
        System.out.println("Excel: " + excelPath.toAbsolutePath());
        System.out.println("Output directory: " + outputDir.toAbsolutePath());
        System.out.println("Total trajectory records: " + trajectoryData.totalRecords);
        System.out.println("Non-executed records: " + trajectoryData.skipRecordCount);
        System.out.println("Invalid JSON lines: " + trajectoryData.invalidJsonCount);
        System.out.println("Matched unique mutants: " + uniqueRecords.size());
        System.out.println("Matched expanded records: " + expandedRecords.size());
        System.out.println("Unique output: " + uniqueOutput.toAbsolutePath());
        System.out.println("Expanded output: " + expandedOutput.toAbsolutePath());
        System.out.println("Plain output: " + plainOutput.toAbsolutePath());
    }

    private static Path resolveInputPath(String configured, String fallback) {
        List<Path> candidates = new ArrayList<>();
        if (configured != null && !configured.trim().isEmpty()) {
            candidates.add(Paths.get(configured.trim()));
        } else {
            candidates.add(Paths.get(fallback));
        }
        if (configured == null || configured.trim().isEmpty()) {
            candidates.add(Paths.get("..").resolve(fallback));
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.exists(normalized)) {
                return normalized;
            }
        }
        return candidates.get(0).normalize();
    }

    private static Path resolveOutputPath(String configured, String fallback) {
        Path path = Paths.get((configured == null || configured.trim().isEmpty())
                ? fallback
                : configured.trim()).normalize();
        if (path.isAbsolute()) {
            return path;
        }
        return path;
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
            List<String> header = new ArrayList<>();
            Map<String, Integer> columnIndex = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                String name = text(cell).trim();
                header.add(name);
                columnIndex.put(name, cell.getColumnIndex());
            }
            for (String required : REQUIRED_COLUMNS) {
                if (!columnIndex.containsKey(required)) {
                    throw new IOException("Missing required Excel column: " + required);
                }
            }

            Map<MatchKey, ExcelRowData> rowMap = new LinkedHashMap<>();
            int sourceRowNo = 1;
            while (it.hasNext()) {
                Row row = it.next();
                sourceRowNo = row.getRowNum() + 1;
                List<String> values = new ArrayList<>();
                for (int i = 0; i < header.size(); i++) {
                    values.add(text(row.getCell(i)));
                }
                MatchKey key = new MatchKey(
                        values.get(columnIndex.get("project")).trim(),
                        values.get(columnIndex.get("package")).trim(),
                        values.get(columnIndex.get("method")).trim(),
                        values.get(columnIndex.get("operator")).trim());
                rowMap.put(key, new ExcelRowData(sourceRowNo, values));
            }
            return new ExcelData(header, rowMap);
        }
    }

    private static TrajectoryData loadTrajectory(Path trajectoryPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<TrajectoryRecord> skipRecords = new ArrayList<>();
        int total = 0;
        int invalid = 0;
        int skipCount = 0;

        for (String line : Files.readAllLines(trajectoryPath)) {
            total++;
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(line);
                if (!isNonExecutedTargetStatus(root.path("targetStatus").asText(""))) {
                    continue;
                }
                skipCount++;
                skipRecords.add(TrajectoryRecord.fromJson(total, root));
            } catch (Exception e) {
                invalid++;
            }
        }
        return new TrajectoryData(total, invalid, skipCount, skipRecords);
    }

    private static List<MatchedRecord> matchExpanded(ExcelData excelData, List<TrajectoryRecord> skipRecords) {
        List<MatchedRecord> matched = new ArrayList<>();
        for (TrajectoryRecord record : skipRecords) {
            MatchKey key = new MatchKey(record.project, record.className, record.method, record.operator);
            ExcelRowData excelRow = excelData.rowsByKey.get(key);
            if (excelRow != null) {
                matched.add(new MatchedRecord(key, excelRow, record));
            }
        }
        return matched;
    }

    private static List<MatchedRecord> dedupeByMutant(List<MatchedRecord> expandedRecords) {
        Map<MatchKey, MatchedRecord> unique = new LinkedHashMap<>();
        for (MatchedRecord record : expandedRecords) {
            unique.putIfAbsent(record.key, record);
        }
        return new ArrayList<>(unique.values());
    }

    private static void writeWorkbook(Path output,
            String dataSheetName,
            List<String> excelHeader,
            List<MatchedRecord> records,
            boolean includeTrajectoryColumns,
            int totalTrajectoryRecords,
            int skipRecordCount,
            int invalidJsonCount) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet summary = workbook.createSheet("Summary");
            Sheet data = workbook.createSheet(dataSheetName);

            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setWrapText(true);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row title = summary.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue("Non-executed trajectory export summary");
            titleCell.setCellStyle(titleStyle);

            writeSummaryRow(summary, 1, "Total trajectory records", totalTrajectoryRecords);
            writeSummaryRow(summary, 2, "Non-executed records", skipRecordCount);
            writeSummaryRow(summary, 3, "Invalid JSON lines", invalidJsonCount);
            writeSummaryRow(summary, 4, "Matched rows exported", records.size());

            List<String> outputHeader = new ArrayList<>(excelHeader);
            if (includeTrajectoryColumns) {
                outputHeader.addAll(EXTRA_COLUMNS);
            }
            Row headerRow = data.createRow(0);
            for (int i = 0; i < outputHeader.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(outputHeader.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (int r = 0; r < records.size(); r++) {
                MatchedRecord record = records.get(r);
                Row row = data.createRow(r + 1);
                int c = 0;
                for (String value : record.excelRow.values) {
                    row.createCell(c++).setCellValue(value);
                }
                if (includeTrajectoryColumns) {
                    row.createCell(c++).setCellValue(record.trajectoryRecord.lineNo);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.targetStatus);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.action);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.compileSuccess);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.reward);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.failureReason);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.testName);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.fallbackLevel);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.generationStrategy);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.regenerationRound);
                    row.createCell(c++).setCellValue(record.trajectoryRecord.exactBucketKey);
                    row.createCell(c).setCellValue(record.trajectoryRecord.coarseBucketKey);
                }
            }

            data.createFreezePane(0, 1);
            data.setAutoFilter(new CellRangeAddress(0, Math.max(1, records.size()),
                    0, Math.max(0, outputHeader.size() - 1)));
            for (int i = 0; i < outputHeader.size(); i++) {
                data.autoSizeColumn(i);
                if (data.getColumnWidth(i) > 12000) {
                    data.setColumnWidth(i, 12000);
                }
            }

            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }

    private static void writeSummaryRow(Sheet sheet, int rowIndex, String key, int value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value);
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell);
    }

    private static String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private static boolean isNonExecutedTargetStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        for (String candidate : NON_EXECUTED_TARGET_STATUSES) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static final class Args {
        private String trajectoryPath;
        private String excelPath;
        private String outputDir;

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
                }
            }
            return parsed;
        }
    }

    private static final class MatchKey {
        private final String project;
        private final String className;
        private final String method;
        private final String operator;

        private MatchKey(String project, String className, String method, String operator) {
            this.project = nullToEmpty(project);
            this.className = nullToEmpty(className);
            this.method = nullToEmpty(method);
            this.operator = nullToEmpty(operator);
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
                    && Objects.equals(className, matchKey.className)
                    && Objects.equals(method, matchKey.method)
                    && Objects.equals(operator, matchKey.operator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(project, className, method, operator);
        }
    }

    private static final class ExcelData {
        private final List<String> header;
        private final Map<MatchKey, ExcelRowData> rowsByKey;

        private ExcelData(List<String> header, Map<MatchKey, ExcelRowData> rowsByKey) {
            this.header = header;
            this.rowsByKey = rowsByKey;
        }
    }

    private static final class ExcelRowData {
        private final int sourceRowNo;
        private final List<String> values;

        private ExcelRowData(int sourceRowNo, List<String> values) {
            this.sourceRowNo = sourceRowNo;
            this.values = values;
        }
    }

    private static final class TrajectoryData {
        private final int totalRecords;
        private final int invalidJsonCount;
        private final int skipRecordCount;
        private final List<TrajectoryRecord> skipRecords;

        private TrajectoryData(int totalRecords,
                int invalidJsonCount,
                int skipRecordCount,
                List<TrajectoryRecord> skipRecords) {
            this.totalRecords = totalRecords;
            this.invalidJsonCount = invalidJsonCount;
            this.skipRecordCount = skipRecordCount;
            this.skipRecords = skipRecords;
        }
    }

    private static final class TrajectoryRecord {
        private final int lineNo;
        private final String project;
        private final String className;
        private final String method;
        private final String operator;
        private final String targetStatus;
        private final String action;
        private final boolean compileSuccess;
        private final double reward;
        private final String failureReason;
        private final String testName;
        private final String fallbackLevel;
        private final String generationStrategy;
        private final int regenerationRound;
        private final String exactBucketKey;
        private final String coarseBucketKey;

        private TrajectoryRecord(int lineNo,
                String project,
                String className,
                String method,
                String operator,
                String targetStatus,
                String action,
                boolean compileSuccess,
                double reward,
                String failureReason,
                String testName,
                String fallbackLevel,
                String generationStrategy,
                int regenerationRound,
                String exactBucketKey,
                String coarseBucketKey) {
            this.lineNo = lineNo;
            this.project = project;
            this.className = className;
            this.method = method;
            this.operator = operator;
            this.targetStatus = targetStatus;
            this.action = action;
            this.compileSuccess = compileSuccess;
            this.reward = reward;
            this.failureReason = failureReason;
            this.testName = testName;
            this.fallbackLevel = fallbackLevel;
            this.generationStrategy = generationStrategy;
            this.regenerationRound = regenerationRound;
            this.exactBucketKey = exactBucketKey;
            this.coarseBucketKey = coarseBucketKey;
        }

        static TrajectoryRecord fromJson(int lineNo, JsonNode root) {
            return new TrajectoryRecord(
                    lineNo,
                    text(root, "project"),
                    text(root, "className"),
                    text(root, "method"),
                    text(root, "operator"),
                    text(root, "targetStatus"),
                    text(root, "action"),
                    root.path("compileSuccess").asBoolean(false),
                    root.path("reward").asDouble(0.0d),
                    text(root, "failureReason"),
                    text(root, "testName"),
                    text(root, "fallbackLevel"),
                    text(root, "generationStrategy"),
                    root.path("regenerationRound").asInt(0),
                    text(root, "exactBucketKey"),
                    text(root, "coarseBucketKey"));
        }

        private static String text(JsonNode root, String field) {
            JsonNode node = root.path(field);
            return node.isMissingNode() || node.isNull() ? "" : node.asText("");
        }
    }

    private static final class MatchedRecord {
        private final MatchKey key;
        private final ExcelRowData excelRow;
        private final TrajectoryRecord trajectoryRecord;

        private MatchedRecord(MatchKey key, ExcelRowData excelRow, TrajectoryRecord trajectoryRecord) {
            this.key = key;
            this.excelRow = excelRow;
            this.trajectoryRecord = trajectoryRecord;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
