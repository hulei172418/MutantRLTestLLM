package mujava.testgenerator.statistics;

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
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Export mutants that are still not killed from LLM execution reports and map
 * them back to the original mutant Excel rows.
 *
 * Two scopes are exported:
 * - target-not-killed: targetKilledEver != true
 * - suite-not-killed : suiteKillStatusFinal != KILLED
 *
 * Default output directory:
 *   data/trajectory-not-killed
 *
 * Usage:
 *   mvn -f MuTestLLM/pom.xml exec:java ^
 *     -Dexec.mainClass=mujava.testgenerator.statistics.LlmNotKilledMutantExcelExporter ^
 *     -Dexec.args="--programsRoot E:/PHD/testJava/Programs --excel data/commons-csv-1.2.xlsx"
 *
 *   Or scan exactly one execution-report batch:
 *   mvn -f MuTestLLM/pom.xml exec:java ^
 *     -Dexec.mainClass=mujava.testgenerator.statistics.LlmNotKilledMutantExcelExporter ^
 *     -Dexec.args="--programsRoot E:/PHD/testJava/Programs/commons-csv-1.2 --reportRoot E:/PHD/testJava/Programs/commons-csv-1.2/llm/execution-report/20260705160017 --excel data/commons-csv-1.2.xlsx"
 */
public class LlmNotKilledMutantExcelExporter {

    private static final String DEFAULT_PROGRAMS_ROOT = "E:/PHD/testJava/Programs";
    private static final String DEFAULT_PROJECT = "commons-csv-1.2";
    private static final String DEFAULT_EXCEL = "data/" + DEFAULT_PROJECT + ".xlsx";
    private static final String DEFAULT_OUTPUT_DIR = "data/trajectory-not-killed";
    private static final String DEFAULT_REPORT_BATCH = "20260705160017";

    private static final String LLM_DIR = "llm";
    private static final String EXECUTION_REPORT_DIR = "execution-report";
    private static final String SUITE_JSON = "llm_suite_kill_results.json";
    private static final String TARGET_CSV = "llm_target_kill_results.csv";

    private static final List<String> REQUIRED_COLUMNS = Arrays.asList(
            "operator", "method", "package", "project"
    );
    private static final List<String> EXTRA_COLUMNS = Arrays.asList(
            "execution_project",
            "testCompiledFinal",
            "targetKilledEver",
            "suiteKillStatusFinal",
            "killedByPrimaryTestEver",
            "killedByFallbackTestEver",
            "mappingStatuses",
            "targetStatuses",
            "targetFailureReasons",
            "reportCount",
            "reportDirs"
    );

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        Path programsRoot = resolveInputPath(parsed.programsRoot,
                DEFAULT_PROGRAMS_ROOT + "/" + DEFAULT_PROJECT);
        Path excelPath = resolveInputPath(parsed.excelPath, DEFAULT_EXCEL);
        Path outputDir = resolveOutputPath(parsed.outputDir, DEFAULT_OUTPUT_DIR);
        Path reportRoot = resolveOptionalInputPath(parsed.reportRoot);
        if (reportRoot == null && !isBlank(DEFAULT_REPORT_BATCH)) {
            reportRoot = programsRoot
                    .resolve(LLM_DIR)
                    .resolve(EXECUTION_REPORT_DIR)
                    .resolve(DEFAULT_REPORT_BATCH)
                    .toAbsolutePath()
                    .normalize();
        }
        Files.createDirectories(outputDir);

        ExcelData excelData = loadExcel(excelPath);
        scanExecutionReports(programsRoot, reportRoot, excelData);

        List<AggregateRecord> targetNotKilled = new ArrayList<AggregateRecord>();
        List<AggregateRecord> suiteNotKilled = new ArrayList<AggregateRecord>();
        for (AggregateRecord record : excelData.recordsByFullKey.values()) {
            if (!record.targetKilledEver) {
                targetNotKilled.add(record);
            }
            if (!"KILLED".equalsIgnoreCase(finalSuiteKillStatus(record))) {
                suiteNotKilled.add(record);
            }
        }

        String baseName = stripExtension(excelPath.getFileName().toString());
        Path targetOutput = outputDir.resolve(baseName + "-target-not-killed.xlsx");
        Path suiteOutput = outputDir.resolve(baseName + "-suite-not-killed.xlsx");
        Path targetPlainOutput = outputDir.resolve(baseName + "-target-not-killed-plain.xlsx");
        Path suitePlainOutput = outputDir.resolve(baseName + "-suite-not-killed-plain.xlsx");

        writeWorkbook(targetOutput, "target_not_killed", excelData.header, targetNotKilled,
                "Rows where targetKilledEver != true", true);
        writeWorkbook(suiteOutput, "suite_not_killed", excelData.header, suiteNotKilled,
                "Rows where suiteKillStatusFinal != KILLED", true);
        writeWorkbook(targetPlainOutput, "target_not_killed_plain", excelData.header, targetNotKilled,
                "Rows where targetKilledEver != true (plain Excel schema only)", false);
        writeWorkbook(suitePlainOutput, "suite_not_killed_plain", excelData.header, suiteNotKilled,
                "Rows where suiteKillStatusFinal != KILLED (plain Excel schema only)", false);

        System.out.println("Programs root: " + programsRoot.toAbsolutePath());
        System.out.println("Report root: " + (reportRoot == null ? "<auto-scan>" : reportRoot.toAbsolutePath()));
        System.out.println("Excel: " + excelPath.toAbsolutePath());
        System.out.println("Output directory: " + outputDir.toAbsolutePath());
        System.out.println("Excel rows indexed: " + excelData.recordsByFullKey.size());
        System.out.println("Target-not-killed rows: " + targetNotKilled.size());
        System.out.println("Suite-not-killed rows: " + suiteNotKilled.size());
        System.out.println("Target output: " + targetOutput.toAbsolutePath());
        System.out.println("Suite output: " + suiteOutput.toAbsolutePath());
        System.out.println("Target plain output: " + targetPlainOutput.toAbsolutePath());
        System.out.println("Suite plain output: " + suitePlainOutput.toAbsolutePath());
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

    private static Path resolveOptionalInputPath(String configured) {
        if (isBlank(configured)) {
            return null;
        }
        return Paths.get(configured.trim()).toAbsolutePath().normalize();
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
            Map<String, Integer> columnIndex = new LinkedHashMap<String, Integer>();
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

            Map<String, AggregateRecord> recordsByFullKey = new LinkedHashMap<String, AggregateRecord>();
            Map<String, List<AggregateRecord>> recordsByTripleKey = new LinkedHashMap<String, List<AggregateRecord>>();
            while (it.hasNext()) {
                Row row = it.next();
                List<String> values = new ArrayList<String>();
                for (int i = 0; i < header.size(); i++) {
                    values.add(text(row.getCell(i)));
                }

                AggregateRecord record = new AggregateRecord();
                record.sourceRowNo = row.getRowNum() + 1;
                record.excelValues = values;
                record.project = values.get(columnIndex.get("project")).trim();
                record.className = values.get(columnIndex.get("package")).trim();
                record.method = values.get(columnIndex.get("method")).trim();
                record.operator = values.get(columnIndex.get("operator")).trim();
                record.fullKey = fullKey(record.project, record.className, record.method, record.operator);
                record.tripleKey = tripleKey(record.className, record.method, record.operator);

                recordsByFullKey.put(record.fullKey, record);
                List<AggregateRecord> list = recordsByTripleKey.get(record.tripleKey);
                if (list == null) {
                    list = new ArrayList<AggregateRecord>();
                    recordsByTripleKey.put(record.tripleKey, list);
                }
                list.add(record);
            }
            return new ExcelData(header, recordsByFullKey, recordsByTripleKey);
        }
    }

    private static void scanExecutionReports(Path programsRoot,
                                             Path reportRoot,
                                             ExcelData excelData) throws IOException {
        Path scanRoot = reportRoot != null ? reportRoot : programsRoot;
        if (!Files.isDirectory(scanRoot)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(scanRoot)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path path = it.next();
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
                if (SUITE_JSON.equals(fileName)) {
                    collectSuiteJson(path, programsRoot, excelData);
                }
            }
        }
    }

    private static void collectSuiteJson(Path suiteJson,
                                         Path programsRoot,
                                         ExcelData excelData) {
        try {
            JSONObject suite = new JSONObject(readFileUtf8(suiteJson));
            String className = suite.optString("targetClassName", "");
            String method = suite.optString("methodSignature", "");
            String resultModuleHome = suite.optString("resultModuleHome", "");
            String project = deriveProjectName(suiteJson, programsRoot, resultModuleHome);

            Set<String> killedMutants = jsonArrayToSet(suite.optJSONArray("suiteKilledMutants"));
            Set<String> liveMutants = jsonArrayToSet(suite.optJSONArray("suiteLiveMutants"));
            JSONObject suiteMutantResults = suite.optJSONObject("suiteMutantResults");

            LinkedHashSet<String> allMutants = new LinkedHashSet<String>();
            allMutants.addAll(killedMutants);
            allMutants.addAll(liveMutants);
            if (suiteMutantResults != null) {
                Iterator<String> keys = suiteMutantResults.keys();
                while (keys.hasNext()) {
                    allMutants.add(keys.next());
                }
            }

            for (String mutant : allMutants) {
                AggregateRecord record = findRecord(excelData, project, className, method, mutant);
                if (record == null) {
                    continue;
                }
                record.reportCount++;
                record.reportDirs.add(suiteJson.getParent().toAbsolutePath().normalize().toString());
                if (killedMutants.contains(mutant)) {
                    record.suiteKilledEver = true;
                }
                if (liveMutants.contains(mutant)) {
                    record.suiteLiveEver = true;
                }
                String mutantResult = suiteMutantResults == null ? "" : suiteMutantResults.optString(mutant, "");
                if (!isBlank(mutantResult)) {
                    record.suiteStatuses.add(mutantResult);
                    if (mutantResult.contains(record.buildPrimaryTestName())) {
                        record.killedByPrimaryTestEver = true;
                    }
                    if (!record.killedByPrimaryTestEver && mutantResult.contains("_Test")) {
                        record.killedByFallbackTestEver = true;
                    }
                }
            }

            Path targetCsv = suiteJson.getParent().resolve(TARGET_CSV);
            if (Files.isRegularFile(targetCsv)) {
                collectTargetCsv(targetCsv, project, excelData);
            }
        } catch (Exception ignored) {
        }
    }

    private static void collectTargetCsv(Path targetCsv,
                                         String project,
                                         ExcelData excelData) {
        try (BufferedReader br = Files.newBufferedReader(targetCsv, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (isBlank(headerLine)) {
                return;
            }
            List<String> header = parseCsvLine(headerLine);
            Map<String, Integer> idx = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < header.size(); i++) {
                idx.put(header.get(i), i);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> cells = parseCsvLine(line);
                String className = get(cells, idx, "targetClassName");
                String method = get(cells, idx, "methodSignature");
                String mutant = get(cells, idx, "mutantName");
                AggregateRecord record = findRecord(excelData, project, className, method, mutant);
                if (record == null) {
                    continue;
                }
                record.testCompiledEver |= Boolean.parseBoolean(get(cells, idx, "compiled"));
                record.targetKilledEver |= Boolean.parseBoolean(get(cells, idx, "targetKilled"));
                record.originalPassedEver |= Boolean.parseBoolean(get(cells, idx, "originalPassed"));
                record.mutantExecutedEver |= Boolean.parseBoolean(get(cells, idx, "mutantExecuted"));
                addIfNotBlank(record.mappingStatuses, get(cells, idx, "mappingStatus"));
                addIfNotBlank(record.targetStatuses, get(cells, idx, "targetStatus"));
                addIfNotBlank(record.targetFailureReasons, get(cells, idx, "failureReason"));
            }
        } catch (Exception ignored) {
        }
    }

    private static AggregateRecord findRecord(ExcelData excelData,
                                              String project,
                                              String className,
                                              String method,
                                              String mutant) {
        AggregateRecord exact = excelData.recordsByFullKey.get(fullKey(project, className, method, mutant));
        if (exact != null) {
            return exact;
        }
        List<AggregateRecord> candidates = excelData.recordsByTripleKey.get(tripleKey(className, method, mutant));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        for (AggregateRecord candidate : candidates) {
            if (candidate.project.equals(project)) {
                return candidate;
            }
        }
        return null;
    }

    private static void writeWorkbook(Path output,
                                      String dataSheetName,
                                      List<String> excelHeader,
                                      List<AggregateRecord> records,
                                      String scopeLabel,
                                      boolean includeExtraColumns) throws IOException {
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
            titleCell.setCellValue("LLM not-killed mutant export summary");
            titleCell.setCellStyle(titleStyle);

            writeSummaryRow(summary, 1, "Scope", scopeLabel);
            writeSummaryRow(summary, 2, "Matched rows exported", String.valueOf(records.size()));
            writeSummaryRow(summary, 3, "Include extra execution columns", String.valueOf(includeExtraColumns));

            List<String> outputHeader = new ArrayList<String>(excelHeader);
            if (includeExtraColumns) {
                outputHeader.addAll(EXTRA_COLUMNS);
            }

            Row headerRow = data.createRow(0);
            for (int i = 0; i < outputHeader.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(outputHeader.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (int r = 0; r < records.size(); r++) {
                AggregateRecord record = records.get(r);
                Row row = data.createRow(r + 1);
                int c = 0;
                for (String value : record.excelValues) {
                    row.createCell(c++).setCellValue(value);
                }
                if (includeExtraColumns) {
                    row.createCell(c++).setCellValue(record.project);
                    row.createCell(c++).setCellValue(String.valueOf(record.testCompiledEver));
                    row.createCell(c++).setCellValue(String.valueOf(record.targetKilledEver));
                    row.createCell(c++).setCellValue(finalSuiteKillStatus(record));
                    row.createCell(c++).setCellValue(String.valueOf(record.killedByPrimaryTestEver));
                    row.createCell(c++).setCellValue(String.valueOf(record.killedByFallbackTestEver));
                    row.createCell(c++).setCellValue(join(record.mappingStatuses));
                    row.createCell(c++).setCellValue(join(record.targetStatuses));
                    row.createCell(c++).setCellValue(join(record.targetFailureReasons));
                    row.createCell(c++).setCellValue(record.reportCount);
                    row.createCell(c).setCellValue(join(record.reportDirs));
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

    private static void writeSummaryRow(Sheet sheet, int rowIndex, String key, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value);
    }

    private static String deriveProjectName(Path reportFile,
                                            Path programsRoot,
                                            String resultModuleHome) {
        try {
            Path normalizedProgramsRoot = programsRoot.toAbsolutePath().normalize();
            Path normalizedReportFile = reportFile.toAbsolutePath().normalize();
            if (normalizedReportFile.startsWith(normalizedProgramsRoot)) {
                Path rel = normalizedProgramsRoot.relativize(normalizedReportFile);
                if (rel.getNameCount() > 0) {
                    return rel.getName(0).toString();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Path resultModule = Paths.get(resultModuleHome).toAbsolutePath().normalize();
            Path name = resultModule.getFileName();
            return name == null ? "" : name.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<String>();
        if (line == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static String readFileUtf8(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String get(List<String> cells, Map<String, Integer> idx, String name) {
        Integer index = idx.get(name);
        if (index == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return cells.get(index).trim();
    }

    private static Set<String> jsonArrayToSet(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            String value = arr.optString(i, "").trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static void addIfNotBlank(LinkedHashSet<String> set, String value) {
        if (set != null && !isBlank(value)) {
            set.add(value.trim());
        }
    }

    private static String finalSuiteKillStatus(AggregateRecord record) {
        if (record.suiteKilledEver) {
            return "KILLED";
        }
        if (record.suiteLiveEver) {
            return "LIVE";
        }
        return "NOT_REPORTED";
    }

    private static String join(LinkedHashSet<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(" || ", values);
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return new DataFormatter().formatCellValue(cell);
    }

    private static String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private static String fullKey(String project, String className, String method, String operator) {
        return nullToEmpty(project) + "##" + nullToEmpty(className) + "##"
                + nullToEmpty(method) + "##" + nullToEmpty(operator);
    }

    private static String tripleKey(String className, String method, String operator) {
        return nullToEmpty(className) + "##" + nullToEmpty(method) + "##" + nullToEmpty(operator);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static final class Args {
        private String programsRoot;
        private String reportRoot;
        private String excelPath;
        private String outputDir;

        static Args parse(String[] args) {
            Args parsed = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--programsRoot".equals(arg) && i + 1 < args.length) {
                    parsed.programsRoot = args[++i];
                } else if ("--reportRoot".equals(arg) && i + 1 < args.length) {
                    parsed.reportRoot = args[++i];
                } else if ("--excel".equals(arg) && i + 1 < args.length) {
                    parsed.excelPath = args[++i];
                } else if ("--outputDir".equals(arg) && i + 1 < args.length) {
                    parsed.outputDir = args[++i];
                }
            }
            return parsed;
        }
    }

    private static final class ExcelData {
        private final List<String> header;
        private final Map<String, AggregateRecord> recordsByFullKey;
        private final Map<String, List<AggregateRecord>> recordsByTripleKey;

        private ExcelData(List<String> header,
                          Map<String, AggregateRecord> recordsByFullKey,
                          Map<String, List<AggregateRecord>> recordsByTripleKey) {
            this.header = header;
            this.recordsByFullKey = recordsByFullKey;
            this.recordsByTripleKey = recordsByTripleKey;
        }
    }

    private static final class AggregateRecord {
        private int sourceRowNo;
        private List<String> excelValues = Collections.emptyList();
        private String project = "";
        private String className = "";
        private String method = "";
        private String operator = "";
        private String fullKey = "";
        private String tripleKey = "";
        private boolean testCompiledEver;
        private boolean targetKilledEver;
        private boolean originalPassedEver;
        private boolean mutantExecutedEver;
        private boolean suiteKilledEver;
        private boolean suiteLiveEver;
        private boolean killedByPrimaryTestEver;
        private boolean killedByFallbackTestEver;
        private final LinkedHashSet<String> suiteStatuses = new LinkedHashSet<String>();
        private final LinkedHashSet<String> mappingStatuses = new LinkedHashSet<String>();
        private final LinkedHashSet<String> targetStatuses = new LinkedHashSet<String>();
        private final LinkedHashSet<String> targetFailureReasons = new LinkedHashSet<String>();
        private final LinkedHashSet<String> reportDirs = new LinkedHashSet<String>();
        private int reportCount;

        private String buildPrimaryTestName() {
            String normalized = className == null ? "" : className.trim();
            normalized = normalized.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "");
            return normalized + "_" + operator + "_Test";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AggregateRecord)) {
                return false;
            }
            AggregateRecord that = (AggregateRecord) o;
            return Objects.equals(fullKey, that.fullKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fullKey);
        }
    }
}
