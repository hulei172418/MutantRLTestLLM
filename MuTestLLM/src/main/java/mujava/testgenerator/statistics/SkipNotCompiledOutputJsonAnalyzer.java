package mujava.testgenerator.statistics;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.json.JSONObject;

import mujava.testgenerator.tools.PromptEvidence;

/**
 * Analyzes non-executed mutants by reading output.json from each
 * mutant_graph_path and classifying whether the non-executed outcome is
 * explained by evidence-side skip rules.
 */
public final class SkipNotCompiledOutputJsonAnalyzer {

    private static final String DEFAULT_INPUT_XLSX =
            "data/trajectory-skip-not-compiled/commons-csv-1.2-skip-not-compiled-plain.xlsx";
    private static final String DEFAULT_OUTPUT_DIR =
            "data/trajectory-skip-not-compiled/output-json-analysis";
    private static final List<String> REQUIRED_COLUMNS = Arrays.asList(
            "operator", "method", "package", "project", "mutant_graph_path"
    );
    private static final List<String> ANALYSIS_COLUMNS = Arrays.asList(
            "analysis_non_executed_reason",
            "analysis_output_json_path",
            "analysis_output_json_exists",
            "analysis_parse_ok",
            "analysis_prompt_skip_test_generation",
            "analysis_skip_reason",
            "analysis_skip_category",
            "analysis_entry_invocation_kind",
            "analysis_need_entry_lifted_evidence",
            "analysis_observable_kind",
            "analysis_executable_plan_status",
            "analysis_executable_plan_reason",
            "analysis_branch_reachability_kind",
            "analysis_has_public_api_evidence",
            "analysis_has_observable_plan",
            "analysis_raw_top_level_keys"
    );

    private SkipNotCompiledOutputJsonAnalyzer() {
    }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        Path inputXlsx = resolvePath(parsed.inputXlsx, DEFAULT_INPUT_XLSX);
        Path outputDir = resolvePath(parsed.outputDir, DEFAULT_OUTPUT_DIR);
        Files.createDirectories(outputDir);

        SheetData inputData = loadInputSheet(inputXlsx);
        List<AnalyzedRow> analyzedRows = analyzeRows(inputData.rows, inputData.headerIndex);

        String baseName = stripExtension(inputXlsx.getFileName().toString());
        Path detailsOutput = outputDir.resolve(baseName + "-output-json-analysis.xlsx");
        Path summaryOutput = outputDir.resolve(baseName + "-output-json-summary.xlsx");

        writeDetails(detailsOutput, inputData.header, analyzedRows);
        writeSummary(summaryOutput, analyzedRows);

        System.out.println("Input: " + inputXlsx.toAbsolutePath());
        System.out.println("Rows analyzed: " + analyzedRows.size());
        System.out.println("Details output: " + detailsOutput.toAbsolutePath());
        System.out.println("Summary output: " + summaryOutput.toAbsolutePath());
    }

    private static Path resolvePath(String configured, String fallback) {
        Path raw = Paths.get(configured == null || configured.trim().isEmpty() ? fallback : configured.trim());
        if (Files.exists(raw)) {
            return raw.normalize();
        }
        Path parentRelative = Paths.get("..").resolve(raw).normalize();
        if (Files.exists(parentRelative)) {
            return parentRelative;
        }
        return raw.normalize();
    }

    private static SheetData loadInputSheet(Path inputXlsx) throws IOException {
        try (InputStream in = Files.newInputStream(inputXlsx);
             Workbook workbook = WorkbookFactory.create(in)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                Iterator<Row> iterator = sheet.rowIterator();
                if (!iterator.hasNext()) {
                    continue;
                }
                Row headerRow = iterator.next();
                List<String> header = new ArrayList<>();
                Map<String, Integer> headerIndex = new LinkedHashMap<>();
                DataFormatter formatter = new DataFormatter();
                for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                    String name = formatter.formatCellValue(headerRow.getCell(i)).trim();
                    header.add(name);
                    headerIndex.put(name, i);
                }
                if (!headerIndex.keySet().containsAll(REQUIRED_COLUMNS)) {
                    continue;
                }

                List<List<String>> rows = new ArrayList<>();
                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < header.size(); i++) {
                        values.add(formatter.formatCellValue(row.getCell(i)));
                    }
                    rows.add(values);
                }
                return new SheetData(header, headerIndex, rows);
            }
        }
        throw new IOException("No sheet containing required columns found in " + inputXlsx);
    }

    private static List<AnalyzedRow> analyzeRows(List<List<String>> rows,
                                                 Map<String, Integer> headerIndex) {
        List<AnalyzedRow> analyzed = new ArrayList<>();
        for (List<String> row : rows) {
            analyzed.add(new AnalyzedRow(row, analyzeRow(row, headerIndex)));
        }
        return analyzed;
    }

    private static AnalysisResult analyzeRow(List<String> row,
                                             Map<String, Integer> headerIndex) {
        String graphPathText = valueAt(row, headerIndex, "mutant_graph_path");
        Path graphDir = normalizeGraphPath(graphPathText);
        Path outputJson = graphDir.resolve("output.json");

        AnalysisResult result = new AnalysisResult();
        result.nonExecutedReason = inferNonExecutedReason(row, headerIndex);
        result.outputJsonPath = outputJson.toString();
        result.outputJsonExists = Files.isRegularFile(outputJson);
        if (!result.outputJsonExists) {
            result.skipCategory = "MISSING_OUTPUT_JSON";
            return result;
        }

        String text;
        try {
            byte[] bytes = Files.readAllBytes(outputJson);
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            result.skipCategory = "OUTPUT_JSON_READ_ERROR";
            result.skipReason = oneLine(e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        }

        JSONObject fullJson;
        try {
            fullJson = new JSONObject(text);
            result.parseOk = true;
            result.rawTopLevelKeys = joinKeys(fullJson);
        } catch (Exception e) {
            result.skipCategory = "INVALID_OUTPUT_JSON";
            result.skipReason = oneLine(e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        }

        PromptEvidence evidence;
        try {
            evidence = PromptEvidence.fromFullOutput(fullJson);
        } catch (Exception e) {
            result.skipCategory = "PROMPT_EVIDENCE_BUILD_ERROR";
            result.skipReason = oneLine(e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        }

        JSONObject compact = evidence.toJson();
        JSONObject entry = compact.optJSONObject("entry");
        JSONObject observablePlan = compact.optJSONObject("observablePlan");
        JSONObject executable = compact.optJSONObject("executableTestPlan");
        JSONObject branch = executable == null ? null : executable.optJSONObject("branchReachability");

        result.promptSkipTestGeneration = evidence.skipTestGeneration;
        result.skipReason = oneLine(evidence.skipReason);
        result.entryInvocationKind = entry == null ? "" : entry.optString("invocationKind", "");
        result.needEntryLiftedEvidence = entry != null && entry.optBoolean("needEntryLiftedEvidence", false);
        result.observableKind = observablePlan == null ? "" : observablePlan.optString("kind", "");
        result.executablePlanStatus = executable == null ? "" : executable.optString("status", "");
        result.executablePlanReason = executable == null ? "" : oneLine(executable.optString("reason", ""));
        result.branchReachabilityKind = branch == null ? "" : branch.optString("kind", "");
        result.hasPublicApiEvidence = compact.has("publicApiEvidence");
        result.hasObservablePlan = observablePlan != null && observablePlan.length() > 0;
        result.skipCategory = classify(result);

        return result;
    }

    private static String classify(AnalysisResult result) {
        if (!result.outputJsonExists) {
            return "MISSING_OUTPUT_JSON";
        }
        if (!result.parseOk) {
            return isBlank(result.skipCategory) ? "INVALID_OUTPUT_JSON" : result.skipCategory;
        }
        if (!result.promptSkipTestGeneration) {
            return "NOT_SKIPPED_BY_PROMPT_EVIDENCE";
        }

        String reason = result.skipReason == null ? "" : result.skipReason.trim();
        if (reason.isEmpty()) {
            return "SKIP_WITH_EMPTY_REASON";
        }

        int colon = reason.indexOf(':');
        String prefix = colon >= 0 ? reason.substring(0, colon).trim() : reason;
        if (!prefix.isEmpty() && prefix.equals(prefix.toUpperCase(Locale.ROOT))
                && prefix.matches("[A-Z0-9_]+")) {
            return prefix;
        }

        if (reason.contains("NO_PUBLIC_OBSERVABLE_FOR_CONSTRUCTOR_STATE_MUTATION")) {
            return "NO_PUBLIC_OBSERVABLE_FOR_CONSTRUCTOR_STATE_MUTATION";
        }
        if (reason.contains("NO_PUBLIC_OBSERVABLE")) {
            return "NO_PUBLIC_OBSERVABLE";
        }
        if (reason.contains("constructor mutates internal/inherited state")) {
            return "CONSTRUCTOR_STATE_MUTATION_NO_PUBLIC_OBSERVABLE";
        }
        if (reason.contains("reflection")) {
            return "REFLECTION_RELATED_SKIP";
        }
        return "OTHER_SKIP_REASON";
    }

    private static void writeDetails(Path output, List<String> inputHeader, List<AnalyzedRow> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            Sheet sheet = workbook.createSheet("output_json_analysis");

            List<String> header = new ArrayList<>(inputHeader);
            header.addAll(ANALYSIS_COLUMNS);
            writeHeader(sheet, headerStyle, header);

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                AnalyzedRow item = rows.get(r);
                int c = 0;
                for (String value : item.inputRow) {
                    row.createCell(c++).setCellValue(nullToEmpty(value));
                }
                AnalysisResult a = item.analysis;
                row.createCell(c++).setCellValue(a.nonExecutedReason);
                row.createCell(c++).setCellValue(a.outputJsonPath);
                row.createCell(c++).setCellValue(a.outputJsonExists);
                row.createCell(c++).setCellValue(a.parseOk);
                row.createCell(c++).setCellValue(a.promptSkipTestGeneration);
                row.createCell(c++).setCellValue(a.skipReason);
                row.createCell(c++).setCellValue(a.skipCategory);
                row.createCell(c++).setCellValue(a.entryInvocationKind);
                row.createCell(c++).setCellValue(a.needEntryLiftedEvidence);
                row.createCell(c++).setCellValue(a.observableKind);
                row.createCell(c++).setCellValue(a.executablePlanStatus);
                row.createCell(c++).setCellValue(a.executablePlanReason);
                row.createCell(c++).setCellValue(a.branchReachabilityKind);
                row.createCell(c++).setCellValue(a.hasPublicApiEvidence);
                row.createCell(c++).setCellValue(a.hasObservablePlan);
                row.createCell(c).setCellValue(a.rawTopLevelKeys);
            }

            finalizeSheet(sheet, header.size(), rows.size());

            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }

    private static void writeSummary(Path output, List<AnalyzedRow> rows) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AnalyzedRow row : rows) {
            counts.merge(row.analysis.skipCategory, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            Sheet sheet = workbook.createSheet("summary");
            writeHeader(sheet, headerStyle, Arrays.asList("skip_category", "count"));

            for (int i = 0; i < ordered.size(); i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(ordered.get(i).getKey());
                row.createCell(1).setCellValue(ordered.get(i).getValue());
            }

            finalizeSheet(sheet, 2, ordered.size());

            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }

    private static void writeHeader(Sheet sheet, CellStyle headerStyle, List<String> header) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < header.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(header.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    private static void finalizeSheet(Sheet sheet, int columnCount, int rowCount) {
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(1, rowCount), 0, Math.max(0, columnCount - 1)));
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > 12000) {
                sheet.setColumnWidth(i, 12000);
            }
        }
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setWrapText(true);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static String valueAt(List<String> row, Map<String, Integer> headerIndex, String key) {
        Integer idx = headerIndex.get(key);
        if (idx == null || idx < 0 || idx >= row.size()) {
            return "";
        }
        return nullToEmpty(row.get(idx));
    }

    private static Path normalizeGraphPath(String raw) {
        String cleaned = nullToEmpty(raw).replace("\\\\?\\", "").trim();
        return Paths.get(cleaned);
    }

    private static String inferNonExecutedReason(List<String> row, Map<String, Integer> headerIndex) {
        String status = valueAt(row, headerIndex, "trajectory_targetStatus");
        if (!isBlank(status)) {
            return status;
        }
        return "UNKNOWN_OR_LEGACY_INPUT";
    }

    private static String joinKeys(JSONObject obj) {
        List<String> keys = new ArrayList<>();
        for (String key : obj.keySet()) {
            keys.add(key);
        }
        Collections.sort(keys);
        return String.join(",", keys);
    }

    private static String oneLine(String value) {
        return nullToEmpty(value).replace("\r", " ").replace("\n", " ").trim();
    }

    private static String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Args {
        private String inputXlsx;
        private String outputDir;

        static Args parse(String[] args) {
            Args parsed = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--input".equals(arg) && i + 1 < args.length) {
                    parsed.inputXlsx = args[++i];
                } else if ("--outputDir".equals(arg) && i + 1 < args.length) {
                    parsed.outputDir = args[++i];
                }
            }
            return parsed;
        }
    }

    private static final class SheetData {
        private final List<String> header;
        private final Map<String, Integer> headerIndex;
        private final List<List<String>> rows;

        private SheetData(List<String> header, Map<String, Integer> headerIndex, List<List<String>> rows) {
            this.header = header;
            this.headerIndex = headerIndex;
            this.rows = rows;
        }
    }

    private static final class AnalyzedRow {
        private final List<String> inputRow;
        private final AnalysisResult analysis;

        private AnalyzedRow(List<String> inputRow, AnalysisResult analysis) {
            this.inputRow = inputRow;
            this.analysis = analysis;
        }
    }

    private static final class AnalysisResult {
        private String nonExecutedReason = "";
        private String outputJsonPath = "";
        private boolean outputJsonExists;
        private boolean parseOk;
        private boolean promptSkipTestGeneration;
        private String skipReason = "";
        private String skipCategory = "";
        private String entryInvocationKind = "";
        private boolean needEntryLiftedEvidence;
        private String observableKind = "";
        private String executablePlanStatus = "";
        private String executablePlanReason = "";
        private String branchReachabilityKind = "";
        private boolean hasPublicApiEvidence;
        private boolean hasObservablePlan;
        private String rawTopLevelKeys = "";
    }
}
