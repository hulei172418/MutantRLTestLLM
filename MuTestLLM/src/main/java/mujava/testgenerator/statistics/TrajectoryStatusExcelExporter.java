package mujava.testgenerator.statistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将 trajectory.jsonl 中的强化学习轨迹记录整理为一个完整的变异体状态分析工作簿。
 *
 * <p>导出的工作表与“commons-lang3-3.17.0_变异体状态分析.xlsx”保持一致：</p>
 * <ol>
 *   <li>分析汇总</li>
 *   <li>全部明细</li>
 *   <li>已杀死</li>
 *   <li>存活</li>
 *   <li>编译失败</li>
 *   <li>操作符统计</li>
 *   <li>类级统计</li>
 *   <li>编译错误统计</li>
 *   <li>轨迹统计</li>
 *   <li>原始轨迹</li>
 * </ol>
 *
 * <p>核心口径：</p>
 * <ul>
 *   <li>mutantId 是变异体唯一标识。</li>
 *   <li>同一 mutantId 在 JSONL 中可能出现多次，按文件中最后一条记录作为最终状态。</li>
 *   <li>最终 compileSuccess=false 的变异体归入“编译失败”。</li>
 *   <li>在编译成功的前提下，根据 targetStatus=KILLED/SURVIVED 区分“杀死/存活”。</li>
 *   <li>源 Excel 仅用于补充 line、mutation_statement、图路径等字段；未匹配到的轨迹不会被丢弃。</li>
 * </ul>
 *
 * <p>兼容两种 JSONL 结构：</p>
 * <ul>
 *   <li>当前顶层结构：targetStatus、compileSuccess、killed、project、className 等字段位于根节点。</li>
 *   <li>旧版嵌套结构：状态位于 stateAfter，证据字段位于 stateAfter.evidenceState。</li>
 * </ul>
 */
public class TrajectoryStatusExcelExporter {

    private static final String DEFAULT_PROJECT = "oot";
    private static final String DEFAULT_TRAJECTORY =
            "logs/auto-loop/20260809154602620/rl/trajectory.jsonl";
    private static final String DEFAULT_OUTPUT_DIR = "trajectory-status-analysis";
    private static final String SURVIVOR_ARTIFACT_DIR = "survivor-artifacts";
    private static final String FAILURE_FEEDBACK_FILE = "failure-feedback.jsonl";

    private static final String STATUS_KILLED = "KILLED";
    private static final String STATUS_SURVIVED = "SURVIVED";
    private static final String STATUS_COMPILE_FAILED = "COMPILE_FAILED_AFTER_REPAIR";
    private static final String STATUS_COMPILE_FAILED_AFTER_GENERATION = "COMPILE_FAILED_AFTER_GENERATION";

    private static final String CATEGORY_KILLED = "杀死";
    private static final String CATEGORY_SURVIVED = "存活";
    private static final String CATEGORY_COMPILE_FAILED = "编译失败";
    private static final String CATEGORY_OTHER = "其他";

    private static final int EXCEL_TEXT_LIMIT = 32767;

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

    private static final List<String> NON_EXECUTED_EXTRA_COLUMNS = Arrays.asList(
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

    private static final List<String> DETAIL_COLUMNS = Arrays.asList(
            "序号",
            "mutantId",
            "project",
            "package",
            "class",
            "method",
            "operator",
            "operatorFamily",
            "line",
            "mutation_statement",
            "targetStatus",
            "分类",
            "compileSuccess",
            "killed",
            "轨迹次数",
            "状态轨迹",
            "首次轮次",
            "最终轮次",
            "最终runId",
            "repairRounds",
            "compileCalls",
            "llmCalls",
            "mutantExecuted",
            "originalPassed",
            "timedOut",
            "编译错误类别",
            "编译错误摘要",
            "完整failureReason",
            "Excel匹配",
            "Excel原行号",
            "file_path",
            "original_graph_path",
            "mutant_graph_path",
            "equivalent");

    private static final List<String> RAW_COLUMNS = Arrays.asList(
            "JSONL行号",
            "mutantId",
            "outerLoopRound",
            "runId",
            "targetStatus",
            "compileSuccess",
            "killed",
            "mutantExecuted",
            "originalPassed",
            "timedOut",
            "repairRounds",
            "compileCalls",
            "llmCalls",
            "elapsedMillis",
            "reward",
            "promptChars",
            "phase",
            "action",
            "evidenceAction",
            "terminationReason",
            "failureReason");

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);

        Path trajectoryPath = resolveTrajectoryPath(parsed.trajectoryPath);
        Path excelPath = resolveExcelPath(parsed.excelPath);
        Path outputPath = resolveOutputPath(parsed, trajectoryPath);

        if (!Files.exists(trajectoryPath)) {
            throw new IOException("trajectory.jsonl does not exist: " + trajectoryPath);
        }
        if (!Files.exists(excelPath)) {
            throw new IOException("Source Excel does not exist: " + excelPath);
        }

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ExcelData excelData = loadExcel(excelPath);
        TrajectoryData trajectoryData = loadTrajectory(trajectoryPath);
        AnalysisResult result = analyze(excelData, trajectoryData);
        writeWorkbook(outputPath, result);
        ArtifactSummary artifactSummary = collectSurvivorArtifacts(
                runDirFromTrajectory(trajectoryPath),
                outputPath.getParent(),
                result);

        System.out.println("Trajectory: " + trajectoryPath.toAbsolutePath().normalize());
        System.out.println("Source Excel: " + excelPath.toAbsolutePath().normalize());
        System.out.println("Output: " + outputPath.toAbsolutePath().normalize());
        System.out.println("Survivor artifacts: " + artifactSummary.destination.toAbsolutePath().normalize());
        System.out.println("Survivor artifact rows: " + artifactSummary.survivorCount);
        System.out.println("Survivor output.json copied: " + artifactSummary.outputCopiedCount);
        System.out.println("Survivor test source copied: " + artifactSummary.testCopiedCount);
        System.out.println("JSONL records: " + trajectoryData.records.size());
        System.out.println("Total compile calls: " + result.totalRawCompileCalls);
        System.out.println("Total LLM calls: " + result.totalRawLlmCalls);
        System.out.println("Unique mutants: " + result.allRows.size());
        System.out.println("Killed: " + result.killedRows.size());
        System.out.println("Survived: " + result.survivedRows.size());
        System.out.println("Compile failed: " + result.compileFailedRows.size());
        System.out.println("Non-executed expanded rows: " + result.nonExecutedExpandedRows.size());
        System.out.println("Non-executed unique rows: " + result.nonExecutedUniqueRows.size());
        System.out.println("Excel matched: " + result.excelMatchedCount);
        System.out.println("JSONL-only mutants: " + result.jsonOnlyCount);
    }

    private static Path resolveTrajectoryPath(String configured) {
        List<Path> candidates = new ArrayList<Path>();
        if (!isBlank(configured)) {
            candidates.add(Paths.get(configured.trim()));
        } else {
            candidates.add(Paths.get(DEFAULT_TRAJECTORY));
            candidates.add(Paths.get("..").resolve(DEFAULT_TRAJECTORY));
            candidates.add(Paths.get("trajectory.jsonl"));
        }
        return firstExistingOrFirst(candidates);
    }

    private static Path resolveExcelPath(String configured) {
        List<Path> candidates = new ArrayList<Path>();
        if (!isBlank(configured)) {
            candidates.add(Paths.get(configured.trim()));
        } else {
            candidates.add(Paths.get("data", DEFAULT_PROJECT + "-2.xlsx"));
            candidates.add(Paths.get("data", DEFAULT_PROJECT + ".xlsx"));
            candidates.add(Paths.get(DEFAULT_PROJECT + "-2.xlsx"));
            candidates.add(Paths.get(DEFAULT_PROJECT + ".xlsx"));
            candidates.add(Paths.get("..").resolve(Paths.get("data", DEFAULT_PROJECT + "-2.xlsx")));
            candidates.add(Paths.get("..").resolve(Paths.get("data", DEFAULT_PROJECT + ".xlsx")));
        }
        return firstExistingOrFirst(candidates);
    }

    private static Path resolveOutputPath(Args parsed, Path trajectoryPath) {
        if (!isBlank(parsed.outputPath)) {
            return Paths.get(parsed.outputPath.trim()).toAbsolutePath().normalize();
        }

        String outputName = isBlank(parsed.outputName)
                ? DEFAULT_PROJECT + "_变异体状态分析.xlsx"
                : ensureXlsxSuffix(parsed.outputName.trim());
        Path outputDir;
        if (isBlank(parsed.outputDir)) {
            outputDir = defaultOutputDirNearTrajectory(trajectoryPath);
        } else {
            outputDir = Paths.get(parsed.outputDir.trim());
            if (!outputDir.isAbsolute()) {
                outputDir = outputDir.toAbsolutePath().normalize();
            }
        }
        return outputDir.resolve(outputName).normalize();
    }

    private static Path defaultOutputDirNearTrajectory(Path trajectoryPath) {
        Path normalized = trajectoryPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            return Paths.get(DEFAULT_OUTPUT_DIR).toAbsolutePath().normalize();
        }
        if (parent.getFileName() != null
                && "rl".equalsIgnoreCase(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent().resolve(DEFAULT_OUTPUT_DIR).toAbsolutePath().normalize();
        }
        return parent.resolve(DEFAULT_OUTPUT_DIR).toAbsolutePath().normalize();
    }

    private static Path firstExistingOrFirst(List<Path> candidates) {
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalized)) {
                return normalized;
            }
        }
        return candidates.get(0).toAbsolutePath().normalize();
    }

    private static String ensureXlsxSuffix(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? name : name + ".xlsx";
    }

    private static ExcelData loadExcel(Path excelPath) throws IOException {
        try (InputStream in = Files.newInputStream(excelPath);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheet("not_killed");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }

            Iterator<Row> iterator = sheet.rowIterator();
            if (!iterator.hasNext()) {
                throw new IOException("Excel has no header row: " + excelPath);
            }

            Row headerRow = iterator.next();
            int lastCell = Math.max(0, headerRow.getLastCellNum());
            List<String> headers = new ArrayList<String>();
            Map<String, Integer> headerIndex = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < lastCell; i++) {
                String name = cellText(headerRow.getCell(i)).trim();
                headers.add(name);
                if (!isBlank(name)) {
                    headerIndex.put(name, i);
                }
            }

            Map<Integer, ExcelRow> byExcelRowNo = new LinkedHashMap<Integer, ExcelRow>();
            Map<MatchKey, ExcelRow> byKey = new LinkedHashMap<MatchKey, ExcelRow>();
            Map<String, ExcelRow> byMutantId = new LinkedHashMap<String, ExcelRow>();

            while (iterator.hasNext()) {
                Row row = iterator.next();
                List<String> values = new ArrayList<String>();
                boolean hasValue = false;
                for (int i = 0; i < headers.size(); i++) {
                    String value = cellText(row.getCell(i));
                    values.add(value);
                    if (!isBlank(value)) {
                        hasValue = true;
                    }
                }
                if (!hasValue) {
                    continue;
                }

                ExcelRow excelRow = new ExcelRow(
                        row.getRowNum() + 1,
                        headerIndex,
                        values);
                byExcelRowNo.put(excelRow.excelRowNo, excelRow);
                if (!byKey.containsKey(excelRow.matchKey())) {
                    byKey.put(excelRow.matchKey(), excelRow);
                }
                if (!byMutantId.containsKey(excelRow.mutantId())) {
                    byMutantId.put(excelRow.mutantId(), excelRow);
                }
            }

            return new ExcelData(headers, byExcelRowNo, byKey, byMutantId);
        }
    }

    private static TrajectoryData loadTrajectory(Path trajectoryPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<TrajectoryRecord> records = new ArrayList<TrajectoryRecord>();
        Map<String, List<TrajectoryRecord>> recordsByMutantId =
                new LinkedHashMap<String, List<TrajectoryRecord>>();
        Map<String, Integer> occurrenceCounter = new LinkedHashMap<String, Integer>();

        try (BufferedReader reader = Files.newBufferedReader(trajectoryPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (isBlank(line)) {
                    continue;
                }

                JsonNode root;
                try {
                    root = mapper.readTree(line);
                } catch (Exception e) {
                    throw new IOException("Invalid JSON at line " + lineNo + ": " + e.getMessage(), e);
                }

                TrajectoryRecord record = TrajectoryRecord.fromJson(lineNo, root);
                if (isBlank(record.mutantId)) {
                    continue;
                }

                Integer count = occurrenceCounter.get(record.mutantId);
                int occurrence = count == null ? 1 : count + 1;
                occurrenceCounter.put(record.mutantId, occurrence);
                record.occurrenceIndex = occurrence;

                records.add(record);
                List<TrajectoryRecord> list = recordsByMutantId.get(record.mutantId);
                if (list == null) {
                    list = new ArrayList<TrajectoryRecord>();
                    recordsByMutantId.put(record.mutantId, list);
                }
                list.add(record);
            }
        }

        return new TrajectoryData(records, recordsByMutantId);
    }

    private static AnalysisResult analyze(ExcelData excelData, TrajectoryData trajectoryData) {
        List<DetailRow> allRows = new ArrayList<DetailRow>();
        int excelMatchedCount = 0;
        List<NonExecutedRow> nonExecutedExpandedRows = new ArrayList<NonExecutedRow>();
        Map<String, NonExecutedRow> nonExecutedUniqueByMutant = new LinkedHashMap<String, NonExecutedRow>();

        for (Map.Entry<String, List<TrajectoryRecord>> entry
                : trajectoryData.recordsByMutantId.entrySet()) {
            List<TrajectoryRecord> history = entry.getValue();
            if (history.isEmpty()) {
                continue;
            }

            TrajectoryRecord finalRecord = history.get(history.size() - 1);
            ExcelRow excelRow = findExcelRow(excelData, finalRecord);
            if (excelRow != null) {
                excelMatchedCount++;
            }
            allRows.add(buildDetailRow(finalRecord, history, excelRow));

            for (TrajectoryRecord record : history) {
                if (!isNonExecutedTargetStatus(record.targetStatus)) {
                    continue;
                }
                ExcelRow matchedExcel = findExcelRow(excelData, record);
                if (matchedExcel == null) {
                    continue;
                }
                NonExecutedRow nonExecutedRow = new NonExecutedRow(matchedExcel, record);
                nonExecutedExpandedRows.add(nonExecutedRow);
                if (!nonExecutedUniqueByMutant.containsKey(record.mutantId)) {
                    nonExecutedUniqueByMutant.put(record.mutantId, nonExecutedRow);
                }
            }
        }

        Collections.sort(allRows, new Comparator<DetailRow>() {
            @Override
            public int compare(DetailRow left, DetailRow right) {
                int categoryCompare = Integer.compare(
                        categoryOrder(left.category),
                        categoryOrder(right.category));
                if (categoryCompare != 0) {
                    return categoryCompare;
                }
                int packageCompare = safe(left.packageName).compareTo(safe(right.packageName));
                if (packageCompare != 0) {
                    return packageCompare;
                }
                int methodCompare = safe(left.method).compareTo(safe(right.method));
                if (methodCompare != 0) {
                    return methodCompare;
                }
                return safe(left.operator).compareTo(safe(right.operator));
            }
        });

        List<DetailRow> killedRows = new ArrayList<DetailRow>();
        List<DetailRow> survivedRows = new ArrayList<DetailRow>();
        List<DetailRow> compileFailedRows = new ArrayList<DetailRow>();
        List<DetailRow> otherRows = new ArrayList<DetailRow>();

        for (DetailRow row : allRows) {
            if (CATEGORY_KILLED.equals(row.category)) {
                killedRows.add(row);
            } else if (CATEGORY_SURVIVED.equals(row.category)) {
                survivedRows.add(row);
            } else if (CATEGORY_COMPILE_FAILED.equals(row.category)) {
                compileFailedRows.add(row);
            } else {
                otherRows.add(row);
            }
        }

        Set<String> everCompileFailed = new LinkedHashSet<String>();
        for (Map.Entry<String, List<TrajectoryRecord>> entry
                : trajectoryData.recordsByMutantId.entrySet()) {
            for (TrajectoryRecord record : entry.getValue()) {
                if (isCompileFailedRecord(record)) {
                    everCompileFailed.add(entry.getKey());
                    break;
                }
            }
        }

        int recoveredFromCompileFailure = 0;
        for (String mutantId : everCompileFailed) {
            List<TrajectoryRecord> history = trajectoryData.recordsByMutantId.get(mutantId);
            if (history != null && !history.isEmpty()) {
                TrajectoryRecord finalRecord = history.get(history.size() - 1);
                if (Boolean.TRUE.equals(finalRecord.compileSuccess)) {
                    recoveredFromCompileFailure++;
                }
            }
        }
        long totalRawRepairRounds = sumRawRepairRounds(trajectoryData.records);
        long totalRawCompileCalls = sumRawCompileCalls(trajectoryData.records);
        long totalRawLlmCalls = sumRawLlmCalls(trajectoryData.records);

        return new AnalysisResult(
                trajectoryData,
                excelData.header,
                allRows,
                killedRows,
                survivedRows,
                compileFailedRows,
                otherRows,
                nonExecutedExpandedRows,
                new ArrayList<NonExecutedRow>(nonExecutedUniqueByMutant.values()),
                excelMatchedCount,
                allRows.size() - excelMatchedCount,
                everCompileFailed.size(),
                recoveredFromCompileFailure,
                totalRawRepairRounds,
                totalRawCompileCalls,
                totalRawLlmCalls);
    }

    private static long sumRawRepairRounds(List<TrajectoryRecord> records) {
        long total = 0L;
        if (records == null) {
            return total;
        }
        for (TrajectoryRecord record : records) {
            if (record != null && record.repairRounds != null) {
                total += record.repairRounds.longValue();
            }
        }
        return total;
    }

    private static long sumRawCompileCalls(List<TrajectoryRecord> records) {
        long total = 0L;
        if (records == null) {
            return total;
        }
        for (TrajectoryRecord record : records) {
            if (record != null && record.compileCalls != null) {
                total += record.compileCalls.longValue();
            }
        }
        return total;
    }

    private static long sumRawLlmCalls(List<TrajectoryRecord> records) {
        long total = 0L;
        if (records == null) {
            return total;
        }
        for (TrajectoryRecord record : records) {
            if (record != null && record.llmCalls != null) {
                total += record.llmCalls.longValue();
            }
        }
        return total;
    }

    private static DetailRow buildDetailRow(
            TrajectoryRecord finalRecord,
            List<TrajectoryRecord> history,
            ExcelRow excelRow) {

        MutantIdParts parts = MutantIdParts.parse(finalRecord.mutantId);

        DetailRow row = new DetailRow();
        row.mutantId = finalRecord.mutantId;
        row.project = firstNonBlank(
                excelRow == null ? "" : excelRow.value("project"),
                finalRecord.project,
                parts.project);
        row.packageName = firstNonBlank(
                excelRow == null ? "" : excelRow.value("package"),
                finalRecord.className,
                parts.packageName);
        row.className = firstNonBlank(
                excelRow == null ? "" : excelRow.value("class"),
                simpleClassName(row.packageName));
        row.method = firstNonBlank(
                excelRow == null ? "" : excelRow.value("method"),
                finalRecord.method,
                parts.method);
        row.operator = firstNonBlank(
                excelRow == null ? "" : excelRow.value("operator"),
                finalRecord.operator,
                parts.operator);
        row.operatorFamily = firstNonBlank(
                finalRecord.operatorFamily,
                operatorFamily(row.operator));
        row.line = excelRow == null ? "" : excelRow.value("line");
        row.mutationStatement = excelRow == null ? "" : excelRow.value("mutation_statement");
        row.targetStatus = finalRecord.targetStatus;
        row.category = finalCategory(finalRecord);
        row.compileSuccess = finalRecord.compileSuccess;
        row.killed = finalRecord.killed;
        row.trajectoryCount = history.size();
        row.statusPath = buildStatusPath(history);
        row.firstRound = displayRound(history.get(0));
        row.finalRound = displayRound(history.get(history.size() - 1));
        row.finalRunId = finalRecord.runId;
        row.repairRounds = sumRawRepairRounds(history);
        row.compileCalls = sumRawCompileCalls(history);
        row.llmCalls = sumRawLlmCalls(history);
        row.mutantExecuted = finalRecord.mutantExecuted;
        row.originalPassed = finalRecord.originalPassed;
        row.timedOut = finalRecord.timedOut;
        row.failureReason = finalRecord.failureReason;
        row.compileErrorSummary = CATEGORY_COMPILE_FAILED.equals(row.category)
                ? extractCompileErrorSummary(finalRecord.failureReason)
                : "";
        row.compileErrorCategory = CATEGORY_COMPILE_FAILED.equals(row.category)
                ? categorizeCompileError(row.compileErrorSummary)
                : "";
        row.excelMatched = excelRow != null;
        row.excelRowNo = excelRow == null ? null : excelRow.excelRowNo;
        row.filePath = excelRow == null ? "" : excelRow.value("file_path");
        row.originalGraphPath = excelRow == null ? "" : excelRow.value("original_graph_path");
        row.mutantGraphPath = excelRow == null ? "" : excelRow.value("mutant_graph_path");
        row.equivalent = equivalent01(
                excelRow == null ? "" : excelRow.value("equivalent"),
                finalRecord.equivalenceSuspicion);
        return row;
    }

    private static ExcelRow findExcelRow(ExcelData excelData, TrajectoryRecord record) {
        ExcelRow direct = excelData.byMutantId.get(record.mutantId);
        if (direct != null) {
            return direct;
        }

        MutantIdParts parts = MutantIdParts.parse(record.mutantId);
        MatchKey key = new MatchKey(
                firstNonBlank(record.project, parts.project),
                firstNonBlank(record.className, parts.packageName),
                firstNonBlank(record.method, parts.method),
                firstNonBlank(record.operator, parts.operator));
        ExcelRow keyed = excelData.byKey.get(key);
        if (keyed != null) {
            return keyed;
        }

        Integer excelRowNo = record.excelRowNo();
        if (excelRowNo != null) {
            ExcelRow byRow = excelData.byExcelRowNo.get(excelRowNo);
            if (byRow != null && byRow.matchKey().equals(key)) {
                return byRow;
            }
        }
        return null;
    }

    private static String finalCategory(TrajectoryRecord record) {
        if (STATUS_KILLED.equals(record.targetStatus)
                || Boolean.TRUE.equals(record.killed)) {
            return CATEGORY_KILLED;
        }
        if (isCompileFailureStatus(record.targetStatus)
                || (Boolean.FALSE.equals(record.compileSuccess) && isBlank(record.targetStatus))) {
            return CATEGORY_COMPILE_FAILED;
        }
        if (STATUS_SURVIVED.equals(record.targetStatus)
                || Boolean.TRUE.equals(record.compileSuccess)) {
            return CATEGORY_SURVIVED;
        }
        return CATEGORY_OTHER;
    }

    private static boolean isCompileFailureStatus(String status) {
        return STATUS_COMPILE_FAILED.equalsIgnoreCase(safe(status))
                || STATUS_COMPILE_FAILED_AFTER_GENERATION.equalsIgnoreCase(safe(status));
    }

    private static boolean isNonExecutedTargetStatus(String status) {
        String normalized = safe(status);
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

    private static boolean isCompileFailedRecord(TrajectoryRecord record) {
        return isCompileFailureStatus(record.targetStatus)
                || (Boolean.FALSE.equals(record.compileSuccess) && isBlank(record.targetStatus));
    }

    private static int categoryOrder(String category) {
        if (CATEGORY_KILLED.equals(category)) {
            return 0;
        }
        if (CATEGORY_SURVIVED.equals(category)) {
            return 1;
        }
        if (CATEGORY_COMPILE_FAILED.equals(category)) {
            return 2;
        }
        return 3;
    }

    private static String buildStatusPath(List<TrajectoryRecord> history) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                builder.append(" → ");
            }
            TrajectoryRecord record = history.get(i);
            builder.append("R")
                    .append(displayRound(record))
                    .append(":")
                    .append(safe(record.targetStatus));
        }
        return builder.toString();
    }

    private static int displayRound(TrajectoryRecord record) {
        if (record.outerLoopRound != null && record.outerLoopRound.intValue() > 0) {
            return record.outerLoopRound.intValue();
        }
        return record.occurrenceIndex;
    }

    private static void writeWorkbook(Path outputPath, AnalysisResult result) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = Styles.create(workbook);

            writeSummarySheet(workbook, styles, result);
            writeDetailSheet(workbook, styles, "全部明细", result.allRows);
            writeDetailSheet(workbook, styles, "已杀死", result.killedRows);
            writeDetailSheet(workbook, styles, "存活", result.survivedRows);
            writeDetailSheet(workbook, styles, "编译失败", result.compileFailedRows);
            writeOperatorStatsSheet(workbook, styles, result.allRows);
            writeClassStatsSheet(workbook, styles, result.allRows);
            writeCompileErrorStatsSheet(workbook, styles, result.compileFailedRows);
            writeTrajectoryStatsSheet(workbook, styles, result.trajectoryData);
            writeRawTrajectorySheet(workbook, styles, result.trajectoryData.records);
            writeNonExecutedSheet(workbook, styles, "非执行明细",
                    result.excelHeader, result.nonExecutedExpandedRows, true);
            writeNonExecutedSheet(workbook, styles, "非执行唯一",
                    result.excelHeader, result.nonExecutedUniqueRows, true);
            writeNonExecutedSheet(workbook, styles, "非执行Plain",
                    result.excelHeader, result.nonExecutedUniqueRows, false);

            try (OutputStream out = Files.newOutputStream(outputPath)) {
                workbook.write(out);
            }
        }
    }

    private static ArtifactSummary collectSurvivorArtifacts(
            Path runDir,
            Path outputParent,
            AnalysisResult result) throws IOException {

        Path destinationRoot = outputParent == null
                ? runDir.resolve(SURVIVOR_ARTIFACT_DIR).toAbsolutePath().normalize()
                : outputParent.resolve(SURVIVOR_ARTIFACT_DIR).toAbsolutePath().normalize();
        Files.createDirectories(destinationRoot);

        List<ArtifactRow> rows = loadSurvivorArtifactRows(runDir, result);
        int outputCopiedCount = 0;
        int testCopiedCount = 0;
        for (ArtifactRow row : rows) {
            if (copyArtifactRow(row, destinationRoot)) {
                if (row.outputCopied) {
                    outputCopiedCount++;
                }
                if (row.testCopied) {
                    testCopiedCount++;
                }
            }
        }

        Path manifestCsv = destinationRoot.resolve("manifest.csv");
        Path manifestJson = destinationRoot.resolve("manifest.json");
        writeArtifactCsv(manifestCsv, rows);
        writeArtifactJson(manifestJson, rows);

        return new ArtifactSummary(destinationRoot, rows.size(), outputCopiedCount, testCopiedCount);
    }

    private static List<ArtifactRow> loadSurvivorArtifactRows(Path runDir, AnalysisResult result) throws IOException {
        List<JsonNode> survivors = readSurvivorFailureFeedback(runDir);
        List<ArtifactRow> rows = new ArrayList<ArtifactRow>();
        for (JsonNode record : survivors) {
            rows.add(ArtifactRow.fromSurvivorRecord(record));
        }
        if (!rows.isEmpty()) {
            return rows;
        }
        for (DetailRow detail : result.survivedRows) {
            rows.add(ArtifactRow.fromDetailRow(detail));
        }
        return rows;
    }

    private static List<JsonNode> readSurvivorFailureFeedback(Path runDir) throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (runDir != null && Files.isDirectory(runDir)) {
            Files.walkFileTree(runDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName() != null
                            && FAILURE_FEEDBACK_FILE.equals(file.getFileName().toString())) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (files.isEmpty()) {
            return Collections.emptyList();
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, SurvivorEntry> dedup = new LinkedHashMap<String, SurvivorEntry>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (isBlank(line)) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                boolean killed = node.path("killed").asBoolean(false);
                String status = node.path("targetStatus").asText("");
                if (killed || !"SURVIVED".equalsIgnoreCase(status)) {
                    continue;
                }
                String key = firstNonBlank(
                        node.path("outputJsonPath").asText(null),
                        node.path("mutantId").asText(null));
                if (isBlank(key)) {
                    continue;
                }
                long timestamp = parseInstantMillis(node.path("generatedAt").asText(null));
                SurvivorEntry existing = dedup.get(key);
                if (existing == null || timestamp >= existing.generatedAtMillis) {
                    dedup.put(key, new SurvivorEntry(node, timestamp));
                }
            }
        }

        List<JsonNode> survivors = new ArrayList<JsonNode>();
        for (SurvivorEntry entry : dedup.values()) {
            survivors.add(entry.node);
        }
        return survivors;
    }

    private static boolean copyArtifactRow(ArtifactRow row, Path destinationRoot) throws IOException {
        String simpleClass = simpleClassName(row.className);
        String safeFolder = safeName(row.taskId + "__" + simpleClass + "__" + row.variantId);
        Path artifactRoot = destinationRoot.resolve(safeFolder);
        Files.createDirectories(artifactRoot);

        boolean outputCopied = false;
        if (!isBlank(row.outputJsonPath)) {
            outputCopied = copyIfExists(row.outputJsonPath, artifactRoot.resolve("output.json"));
        }

        boolean testCopied = false;
        Path testSourcePath = resolveTestSourcePath(row.outputJsonPath, row.className, row.variantId);
        if (testSourcePath != null && Files.isRegularFile(testSourcePath)) {
            String packageName = packageNameOf(row.className);
            Path testSourceDest = artifactRoot.resolve("test-src")
                    .resolve(packageName.replace('.', '/'))
                    .resolve(testSourcePath.getFileName().toString());
            testCopied = copyIfExists(testSourcePath.toString(), testSourceDest);
        }

        row.artifactFolder = artifactRoot.toString();
        row.outputCopied = outputCopied;
        row.testCopied = testCopied;
        row.testSourcePath = testSourcePath == null ? "" : testSourcePath.toString();
        return outputCopied || testCopied;
    }

    private static void writeArtifactCsv(Path path, List<ArtifactRow> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("mutantId,taskId,className,method,targetStatus,killed,outputJsonPath,outputCopied,testSourcePath,testCopied,artifactFolder");
            writer.newLine();
            for (ArtifactRow row : rows) {
                writer.write(csv(row.mutantId)); writer.write(',');
                writer.write(csv(row.taskId)); writer.write(',');
                writer.write(csv(row.className)); writer.write(',');
                writer.write(csv(row.method)); writer.write(',');
                writer.write(csv(row.targetStatus)); writer.write(',');
                writer.write(Boolean.toString(row.killed)); writer.write(',');
                writer.write(csv(row.outputJsonPath)); writer.write(',');
                writer.write(Boolean.toString(row.outputCopied)); writer.write(',');
                writer.write(csv(row.testSourcePath)); writer.write(',');
                writer.write(Boolean.toString(row.testCopied)); writer.write(',');
                writer.write(csv(row.artifactFolder));
                writer.newLine();
            }
        }
    }

    private static void writeArtifactJson(Path path, List<ArtifactRow> rows) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> jsonRows = new ArrayList<Map<String, Object>>();
        for (ArtifactRow row : rows) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("mutantId", row.mutantId);
            map.put("taskId", row.taskId);
            map.put("className", row.className);
            map.put("method", row.method);
            map.put("targetStatus", row.targetStatus);
            map.put("killed", row.killed);
            map.put("outputJsonPath", row.outputJsonPath);
            map.put("outputCopied", row.outputCopied);
            map.put("testSourcePath", row.testSourcePath);
            map.put("testCopied", row.testCopied);
            map.put("artifactFolder", row.artifactFolder);
            jsonRows.add(map);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), jsonRows);
    }

    private static Path runDirFromTrajectory(Path trajectoryPath) {
        Path current = trajectoryPath.toAbsolutePath().normalize();
        Path parent = current.getParent();
        if (parent == null) {
            return current;
        }
        if (parent.getFileName() != null && "rl".equalsIgnoreCase(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent();
        }
        return parent;
    }

    private static Path resolveTestSourcePath(String outputJsonPath, String className, String variantId) throws IOException {
        Path projectRoot = deriveProjectRoot(outputJsonPath);
        if (projectRoot == null || isBlank(className) || isBlank(variantId)) {
            return null;
        }

        String normalizedClass = normalizeClassName(className);
        String simpleClass = simpleClassName(normalizedClass);
        String packagePath = packageNameOf(normalizedClass).replace('.', '/');
        Path candidate = projectRoot.resolve("llm").resolve("src")
                .resolve(packagePath)
                .resolve(simpleClass + "_" + variantId + "_Test.java");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }

        final String targetName = simpleClass + "_" + variantId + "_Test.java";
        final Path srcRoot = projectRoot.resolve("llm").resolve("src");
        if (!Files.isDirectory(srcRoot)) {
            return candidate;
        }
        final Path[] found = new Path[1];
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName() != null && targetName.equals(file.getFileName().toString())) {
                    found[0] = file;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0] != null ? found[0] : candidate;
    }

    private static Path deriveProjectRoot(String outputJsonPath) {
        if (isBlank(outputJsonPath)) {
            return null;
        }
        String normalized = outputJsonPath.replace('/', '\\');
        int idx = normalized.indexOf("\\result\\");
        if (idx > 0) {
            return Paths.get(normalized.substring(0, idx));
        }
        return null;
    }

    private static String deriveVariantId(String mutantId, String outputJsonPath) {
        if (!isBlank(mutantId)) {
            String[] parts = mutantId.split("::");
            if (parts.length > 0) {
                return parts[parts.length - 1];
            }
        }
        if (!isBlank(outputJsonPath)) {
            String normalized = outputJsonPath.replace('/', '\\');
            int last = normalized.lastIndexOf("\\graph\\output.json");
            if (last > 0) {
                String before = normalized.substring(0, last);
                int mutantDir = before.lastIndexOf('\\');
                if (mutantDir > 0) {
                    return before.substring(mutantDir + 1);
                }
            }
        }
        return "unknown";
    }

    private static boolean copyIfExists(String source, Path dest) throws IOException {
        if (isBlank(source)) {
            return false;
        }
        Path sourcePath = Paths.get(source);
        if (!Files.isRegularFile(sourcePath)) {
            return false;
        }
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(sourcePath, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static String deriveClassNameFromOutputJson(String outputJsonPath) {
        if (isBlank(outputJsonPath)) {
            return "";
        }
        String normalized = outputJsonPath.replace('/', '\\');
        String marker = "\\result\\";
        int idx = normalized.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        String rest = normalized.substring(idx + marker.length());
        int classEnd = rest.indexOf("\\traditional_mutants\\");
        if (classEnd < 0) {
            return "";
        }
        return rest.substring(0, classEnd).replace('\\', '.');
    }

    private static String normalizeClassName(String className) {
        String normalized = safe(className).replace('/', '.');
        String prefix = "main.java.";
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }
        return normalized;
    }

    private static String packageNameOf(String className) {
        String normalized = normalizeClassName(className);
        int idx = normalized.lastIndexOf('.');
        return idx >= 0 ? normalized.substring(0, idx) : "";
    }

    private static long parseInstantMillis(String text) {
        if (isBlank(text)) {
            return Long.MIN_VALUE;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        boolean quote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        if (!quote) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String safeName(String value) {
        String text = safe(value);
        if (text.isEmpty()) {
            return "_unknown";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '<' || ch == '>' || ch == ':' || ch == '"' || ch == '/' || ch == '\\'
                    || ch == '|' || ch == '?' || ch == '*') {
                sb.append('_');
            } else if (Character.isWhitespace(ch)) {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        String normalized = sb.toString().trim();
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        return normalized.isEmpty() ? "_unknown" : normalized;
    }

    private static final class SurvivorEntry {
        private final JsonNode node;
        private final long generatedAtMillis;

        private SurvivorEntry(JsonNode node, long generatedAtMillis) {
            this.node = node;
            this.generatedAtMillis = generatedAtMillis;
        }
    }

    private static final class ArtifactRow {
        private final String mutantId;
        private final String taskId;
        private final String className;
        private final String method;
        private final String targetStatus;
        private final boolean killed;
        private String outputJsonPath;
        private boolean outputCopied;
        private String testSourcePath;
        private boolean testCopied;
        private String artifactFolder;
        private String variantId;

        private ArtifactRow(String mutantId,
                            String taskId,
                            String className,
                            String method,
                            String targetStatus,
                            boolean killed,
                            String outputJsonPath,
                            String variantId) {
            this.mutantId = mutantId;
            this.taskId = taskId;
            this.className = className;
            this.method = method;
            this.targetStatus = targetStatus;
            this.killed = killed;
            this.outputJsonPath = outputJsonPath;
            this.variantId = variantId;
        }

        private static ArtifactRow fromSurvivorRecord(JsonNode record) {
            String outputJsonPath = record.path("outputJsonPath").asText("");
            return new ArtifactRow(
                    record.path("mutantId").asText(""),
                    record.path("taskId").asText(""),
                    firstNonBlank(record.path("className").asText(null), deriveClassNameFromOutputJson(outputJsonPath)),
                    record.path("method").asText(""),
                    record.path("targetStatus").asText(""),
                    record.path("killed").asBoolean(false),
                    outputJsonPath,
                    firstNonBlank(deriveVariantId(record.path("mutantId").asText(""), outputJsonPath), "unknown"));
        }

        private static ArtifactRow fromDetailRow(DetailRow detail) {
            String outputJsonPath = isBlank(detail.mutantGraphPath)
                    ? ""
                    : Paths.get(detail.mutantGraphPath, "graph", "output.json").toString();
            return new ArtifactRow(
                    detail.mutantId,
                    detail.excelRowNo == null ? "" : "row-" + detail.excelRowNo,
                    detail.className,
                    detail.method,
                    detail.targetStatus,
                    Boolean.TRUE.equals(detail.killed),
                    outputJsonPath,
                    deriveVariantId(detail.mutantId, outputJsonPath));
        }
    }

    private static final class ArtifactSummary {
        private final Path destination;
        private final int survivorCount;
        private final int outputCopiedCount;
        private final int testCopiedCount;

        private ArtifactSummary(Path destination, int survivorCount, int outputCopiedCount, int testCopiedCount) {
            this.destination = destination;
            this.survivorCount = survivorCount;
            this.outputCopiedCount = outputCopiedCount;
            this.testCopiedCount = testCopiedCount;
        }
    }

    private static void writeSummarySheet(
            XSSFWorkbook workbook,
            Styles styles,
            AnalysisResult result) {

        Sheet sheet = workbook.createSheet("分析汇总");
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(28);
        Cell title = titleRow.createCell(0);
        title.setCellValue(inferProject(result) + " 变异体状态分析");
        title.setCellStyle(styles.title);
        applyMergedStyle(sheet, styles.title, 0, 0, 0, 5);

        writeHeaderRow(sheet, 2, 0, Arrays.asList("统计指标", "结果"), styles.header);
        List<Object[]> metrics = new ArrayList<Object[]>();
        metrics.add(new Object[]{"JSONL轨迹记录数", result.trajectoryData.records.size()});
        metrics.add(new Object[]{"修复轮次总数", result.totalRawRepairRounds});
        metrics.add(new Object[]{"编译调用总次数", result.totalRawCompileCalls});
        metrics.add(new Object[]{"大模型调用总次数", result.totalRawLlmCalls});
        metrics.add(new Object[]{"唯一变异体数", result.allRows.size()});
        metrics.add(new Object[]{"与源Excel匹配数", result.excelMatchedCount});
        metrics.add(new Object[]{"JSONL额外变异体数", result.jsonOnlyCount});
        metrics.add(new Object[]{"最终杀死数", result.killedRows.size()});
        metrics.add(new Object[]{"最终存活数", result.survivedRows.size()});
        metrics.add(new Object[]{"最终编译失败数", result.compileFailedRows.size()});
        metrics.add(new Object[]{"非执行轨迹记录数", result.nonExecutedExpandedRows.size()});
        metrics.add(new Object[]{"非执行唯一变异体数", result.nonExecutedUniqueRows.size()});
        metrics.add(new Object[]{"最终编译成功数", countCompileSuccess(result.allRows)});
        metrics.add(new Object[]{"曾发生编译失败的变异体数", result.everCompileFailedCount});
        metrics.add(new Object[]{"编译失败后最终恢复数", result.recoveredFromCompileFailureCount});
        writeObjectRows(sheet, 3, 0, metrics, styles.body);

        writeHeaderRow(sheet, 20, 0, Arrays.asList("比率指标", "结果"), styles.header);
        int total = result.allRows.size();
        int compiled = countCompileSuccess(result.allRows);
        int killed = result.killedRows.size();
        List<Object[]> rates = new ArrayList<Object[]>();
        rates.add(new Object[]{"编译成功率", ratio(compiled, total)});
        rates.add(new Object[]{"总体杀死率", ratio(killed, total)});
        rates.add(new Object[]{"编译成功变异体中的杀死率", ratio(killed, compiled)});
        rates.add(new Object[]{"曾编译失败变异体的恢复率",
                ratio(result.recoveredFromCompileFailureCount, result.everCompileFailedCount)});
        writeObjectRows(sheet, 21, 0, rates, styles.body);
        for (int row = 21; row <= 24; row++) {
            sheet.getRow(row).getCell(1).setCellStyle(styles.percent);
        }

        writeHeaderRow(sheet, 2, 3, Arrays.asList("最终分类", "数量"), styles.header);
        List<Object[]> categories = new ArrayList<Object[]>();
        categories.add(new Object[]{CATEGORY_KILLED, result.killedRows.size()});
        categories.add(new Object[]{CATEGORY_SURVIVED, result.survivedRows.size()});
        categories.add(new Object[]{CATEGORY_COMPILE_FAILED, result.compileFailedRows.size()});
        if (!result.otherRows.isEmpty()) {
            categories.add(new Object[]{CATEGORY_OTHER, result.otherRows.size()});
        }
        writeObjectRows(sheet, 3, 3, categories, styles.body);

        Map<Integer, Integer> killRoundCounts = new LinkedHashMap<Integer, Integer>();
        for (DetailRow row : result.killedRows) {
            Integer old = killRoundCounts.get(row.finalRound);
            killRoundCounts.put(row.finalRound, old == null ? 1 : old + 1);
        }
        writeHeaderRow(sheet, 7, 3, Arrays.asList("杀死发生轮次", "数量"), styles.header);
        int killRow = 8;
        for (Map.Entry<Integer, Integer> entry : killRoundCounts.entrySet()) {
            writeObjectRow(sheet, killRow++, 3,
                    new Object[]{"第" + entry.getKey() + "轮", entry.getValue()}, styles.body);
        }

        sheet.addMergedRegion(new CellRangeAddress(27, 27, 0, 5));
        Row sectionRow = sheet.createRow(27);
        Cell section = sectionRow.createCell(0);
        section.setCellValue("关键结论与口径说明");
        section.setCellStyle(styles.section);
        applyMergedStyle(sheet, styles.section, 27, 27, 0, 5);

        List<String> notes = Arrays.asList(
                "1. 最终状态按每个 mutantId 在 trajectory.jsonl 中最后出现的记录判定。",
                "2. compileSuccess 优先读取根节点 compileSuccess；旧版 JSONL 自动回退到 stateAfter.compileSuccess/stateAfter.compiled。",
                "3. 源 Excel 仅补充变异位置、变异语句和图路径；未匹配的 JSONL 变异体仍会导出。",
                "4. 当前 JSONL 若没有 outerLoopRound，则按同一 mutantId 在文件中的第1/第2/第3次出现推导轨迹轮次。",
                "5. 共 " + result.everCompileFailedCount + " 个变异体至少一次发生编译失败，其中 "
                        + result.recoveredFromCompileFailureCount + " 个最终恢复为编译成功。"
        );
        for (int i = 0; i < notes.size(); i++) {
            int rowIndex = 28 + i;
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 5));
            Row row = sheet.createRow(rowIndex);
            row.setHeightInPoints(28);
            Cell cell = row.createCell(0);
            cell.setCellValue(notes.get(i));
            cell.setCellStyle(styles.wrapBody);
            applyMergedStyle(sheet, styles.wrapBody, rowIndex, rowIndex, 0, 5);
        }

        setColumnWidths(sheet, new int[]{31, 16, 3, 18, 12, 3});
        sheet.createFreezePane(0, 2);
    }

    private static void writeDetailSheet(
            XSSFWorkbook workbook,
            Styles styles,
            String sheetName,
            List<DetailRow> rows) {

        Sheet sheet = workbook.createSheet(sheetName);
        writeHeaderRow(sheet, 0, 0, DETAIL_COLUMNS, styles.header);

        for (int i = 0; i < rows.size(); i++) {
            DetailRow detail = rows.get(i);
            Row row = sheet.createRow(i + 1);
            int column = 0;
            writeCell(row, column++, i + 1, styles.body);
            writeCell(row, column++, detail.mutantId, styles.body);
            writeCell(row, column++, detail.project, styles.body);
            writeCell(row, column++, detail.packageName, styles.body);
            writeCell(row, column++, detail.className, styles.body);
            writeCell(row, column++, detail.method, styles.body);
            writeCell(row, column++, detail.operator, styles.body);
            writeCell(row, column++, detail.operatorFamily, styles.body);
            writeCell(row, column++, detail.line, styles.body);
            writeCell(row, column++, detail.mutationStatement, styles.wrapBody);
            writeCell(row, column++, detail.targetStatus, styles.body);
            writeCell(row, column++, detail.category, styles.categoryStyle(detail.category));
            writeCell(row, column++, detail.compileSuccess, styles.body);
            writeCell(row, column++, detail.killed, styles.body);
            writeCell(row, column++, detail.trajectoryCount, styles.body);
            writeCell(row, column++, detail.statusPath, styles.wrapBody);
            writeCell(row, column++, detail.firstRound, styles.body);
            writeCell(row, column++, detail.finalRound, styles.body);
            writeCell(row, column++, detail.finalRunId, styles.body);
            writeCell(row, column++, detail.repairRounds, styles.body);
            writeCell(row, column++, detail.compileCalls, styles.body);
            writeCell(row, column++, detail.llmCalls, styles.body);
            writeCell(row, column++, detail.mutantExecuted, styles.body);
            writeCell(row, column++, detail.originalPassed, styles.body);
            writeCell(row, column++, detail.timedOut, styles.body);
            writeCell(row, column++, detail.compileErrorCategory, styles.body);
            writeCell(row, column++, detail.compileErrorSummary, styles.wrapBody);
            writeCell(row, column++, detail.failureReason, styles.wrapBody);
            writeCell(row, column++, detail.excelMatched, styles.body);
            writeCell(row, column++, detail.excelRowNo, styles.body);
            writeCell(row, column++, detail.filePath, styles.body);
            writeCell(row, column++, detail.originalGraphPath, styles.body);
            writeCell(row, column++, detail.mutantGraphPath, styles.body);
            writeCell(row, column, detail.equivalent, styles.body);
        }

        int lastRow = Math.max(0, rows.size());
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, DETAIL_COLUMNS.size() - 1));
        setDetailColumnWidths(sheet);
    }

    private static void writeNonExecutedSheet(
            XSSFWorkbook workbook,
            Styles styles,
            String sheetName,
            List<String> excelHeader,
            List<NonExecutedRow> rows,
            boolean includeTrajectoryColumns) {

        Sheet sheet = workbook.createSheet(sheetName);
        List<String> headers = new ArrayList<String>();
        headers.addAll(excelHeader == null ? Collections.<String>emptyList() : excelHeader);
        if (includeTrajectoryColumns) {
            headers.addAll(NON_EXECUTED_EXTRA_COLUMNS);
        }
        writeHeaderRow(sheet, 0, 0, headers, styles.header);

        for (int i = 0; i < rows.size(); i++) {
            NonExecutedRow item = rows.get(i);
            Row row = sheet.createRow(i + 1);
            int column = 0;
            for (String value : item.excelRow.values) {
                writeCell(row, column++, value, styles.body);
            }
            if (includeTrajectoryColumns) {
                TrajectoryRecord record = item.trajectoryRecord;
                writeCell(row, column++, record.lineNo, styles.body);
                writeCell(row, column++, record.targetStatus, styles.body);
                writeCell(row, column++, record.action, styles.body);
                writeCell(row, column++, record.compileSuccess, styles.body);
                writeCell(row, column++, record.reward, styles.body);
                writeCell(row, column++, record.failureReason, styles.wrapBody);
                writeCell(row, column++, record.testName, styles.body);
                writeCell(row, column++, record.fallbackLevel, styles.body);
                writeCell(row, column++, record.generationStrategy, styles.body);
                writeCell(row, column++, record.regenerationRound, styles.body);
                writeCell(row, column++, record.exactBucketKey, styles.body);
                writeCell(row, column, record.coarseBucketKey, styles.body);
            }
        }

        int lastRow = Math.max(0, rows.size());
        int lastColumn = Math.max(0, headers.size() - 1);
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, lastColumn));
        for (int i = 0; i < headers.size(); i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > 12000) {
                sheet.setColumnWidth(i, 12000);
            }
        }
    }

    private static void writeOperatorStatsSheet(
            XSSFWorkbook workbook,
            Styles styles,
            List<DetailRow> rows) {

        Sheet sheet = workbook.createSheet("操作符统计");
        List<String> headers = Arrays.asList(
                "操作符族", "总数", "杀死", "存活", "编译失败",
                "编译成功率", "总体杀死率", "编译成功后杀死率");
        writeHeaderRow(sheet, 0, 0, headers, styles.header);

        Map<String, StatusCount> stats = new LinkedHashMap<String, StatusCount>();
        for (DetailRow row : rows) {
            String key = safe(row.operatorFamily);
            StatusCount count = stats.get(key);
            if (count == null) {
                count = new StatusCount();
                stats.put(key, count);
            }
            count.add(row.category);
        }

        List<Map.Entry<String, StatusCount>> entries =
                new ArrayList<Map.Entry<String, StatusCount>>(stats.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, StatusCount>>() {
            @Override
            public int compare(Map.Entry<String, StatusCount> a,
                    Map.Entry<String, StatusCount> b) {
                return Integer.compare(b.getValue().total, a.getValue().total);
            }
        });

        int rowIndex = 1;
        for (Map.Entry<String, StatusCount> entry : entries) {
            StatusCount count = entry.getValue();
            int compiled = count.killed + count.survived;
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, entry.getKey(), styles.body);
            writeCell(row, 1, count.total, styles.body);
            writeCell(row, 2, count.killed, styles.body);
            writeCell(row, 3, count.survived, styles.body);
            writeCell(row, 4, count.compileFailed, styles.body);
            writeCell(row, 5, ratio(compiled, count.total), styles.percent);
            writeCell(row, 6, ratio(count.killed, count.total), styles.percent);
            writeCell(row, 7, ratio(count.killed, compiled), styles.percent);
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.size() - 1));
        setColumnWidths(sheet, new int[]{16, 12, 12, 12, 12, 19, 19, 22});
    }

    private static void writeClassStatsSheet(
            XSSFWorkbook workbook,
            Styles styles,
            List<DetailRow> rows) {

        Sheet sheet = workbook.createSheet("类级统计");
        List<String> headers = Arrays.asList(
                "类", "总数", "杀死", "存活", "编译失败", "编译成功率", "总体杀死率");
        writeHeaderRow(sheet, 0, 0, headers, styles.header);

        Map<String, StatusCount> stats = new LinkedHashMap<String, StatusCount>();
        for (DetailRow row : rows) {
            String key = safe(row.className);
            StatusCount count = stats.get(key);
            if (count == null) {
                count = new StatusCount();
                stats.put(key, count);
            }
            count.add(row.category);
        }

        List<Map.Entry<String, StatusCount>> entries =
                new ArrayList<Map.Entry<String, StatusCount>>(stats.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, StatusCount>>() {
            @Override
            public int compare(Map.Entry<String, StatusCount> a,
                    Map.Entry<String, StatusCount> b) {
                return Integer.compare(b.getValue().total, a.getValue().total);
            }
        });

        int rowIndex = 1;
        for (Map.Entry<String, StatusCount> entry : entries) {
            StatusCount count = entry.getValue();
            int compiled = count.killed + count.survived;
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, entry.getKey(), styles.body);
            writeCell(row, 1, count.total, styles.body);
            writeCell(row, 2, count.killed, styles.body);
            writeCell(row, 3, count.survived, styles.body);
            writeCell(row, 4, count.compileFailed, styles.body);
            writeCell(row, 5, ratio(compiled, count.total), styles.percent);
            writeCell(row, 6, ratio(count.killed, count.total), styles.percent);
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.size() - 1));
        setColumnWidths(sheet, new int[]{36, 14, 14, 14, 14, 17, 17});
    }

    private static void writeCompileErrorStatsSheet(
            XSSFWorkbook workbook,
            Styles styles,
            List<DetailRow> compileFailedRows) {

        Sheet sheet = workbook.createSheet("编译错误统计");
        List<String> headers = Arrays.asList(
                "编译错误类别", "数量", "占最终编译失败比例", "示例mutantId", "示例错误摘要");
        writeHeaderRow(sheet, 0, 0, headers, styles.header);

        Map<String, List<DetailRow>> grouped = new LinkedHashMap<String, List<DetailRow>>();
        for (DetailRow detail : compileFailedRows) {
            String key = safe(detail.compileErrorCategory);
            List<DetailRow> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<DetailRow>();
                grouped.put(key, list);
            }
            list.add(detail);
        }

        List<Map.Entry<String, List<DetailRow>>> entries =
                new ArrayList<Map.Entry<String, List<DetailRow>>>(grouped.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, List<DetailRow>>>() {
            @Override
            public int compare(Map.Entry<String, List<DetailRow>> a,
                    Map.Entry<String, List<DetailRow>> b) {
                return Integer.compare(b.getValue().size(), a.getValue().size());
            }
        });

        int rowIndex = 1;
        for (Map.Entry<String, List<DetailRow>> entry : entries) {
            DetailRow example = entry.getValue().get(0);
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, entry.getKey(), styles.body);
            writeCell(row, 1, entry.getValue().size(), styles.body);
            writeCell(row, 2, ratio(entry.getValue().size(), compileFailedRows.size()), styles.percent);
            writeCell(row, 3, example.mutantId, styles.wrapBody);
            writeCell(row, 4, example.compileErrorSummary, styles.wrapBody);
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.size() - 1));
        setColumnWidths(sheet, new int[]{26, 12, 22, 80, 110});
    }

    private static void writeTrajectoryStatsSheet(
            XSSFWorkbook workbook,
            Styles styles,
            TrajectoryData trajectoryData) {

        Sheet sheet = workbook.createSheet("轨迹统计");
        writeHeaderRow(sheet, 0, 0, Arrays.asList("最终轨迹模式", "变异体数"), styles.header);

        Map<String, Integer> patternCounts = new LinkedHashMap<String, Integer>();
        Map<Integer, Integer> trajectoryCountCounts = new LinkedHashMap<Integer, Integer>();

        for (List<TrajectoryRecord> history : trajectoryData.recordsByMutantId.values()) {
            String pattern = buildRawPattern(history);
            Integer oldPattern = patternCounts.get(pattern);
            patternCounts.put(pattern, oldPattern == null ? 1 : oldPattern + 1);

            int count = history.size();
            Integer oldCount = trajectoryCountCounts.get(count);
            trajectoryCountCounts.put(count, oldCount == null ? 1 : oldCount + 1);
        }

        List<Map.Entry<String, Integer>> patterns =
                new ArrayList<Map.Entry<String, Integer>>(patternCounts.entrySet());
        Collections.sort(patterns, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });

        int rowIndex = 1;
        for (Map.Entry<String, Integer> entry : patterns) {
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, entry.getKey(), styles.wrapBody);
            writeCell(row, 1, entry.getValue(), styles.body);
        }

        int secondHeader = rowIndex + 2;
        writeHeaderRow(sheet, secondHeader, 0, Arrays.asList("轨迹记录次数", "变异体数"), styles.header);
        rowIndex = secondHeader + 1;

        List<Integer> counts = new ArrayList<Integer>(trajectoryCountCounts.keySet());
        Collections.sort(counts);
        for (Integer count : counts) {
            Row row = sheet.createRow(rowIndex++);
            writeCell(row, 0, count, styles.body);
            writeCell(row, 1, trajectoryCountCounts.get(count), styles.body);
        }

        sheet.createFreezePane(0, 1);
        setColumnWidths(sheet, new int[]{70, 14});
    }

    private static void writeRawTrajectorySheet(
            XSSFWorkbook workbook,
            Styles styles,
            List<TrajectoryRecord> records) {

        Sheet sheet = workbook.createSheet("原始轨迹");
        writeHeaderRow(sheet, 0, 0, RAW_COLUMNS, styles.header);

        for (int i = 0; i < records.size(); i++) {
            TrajectoryRecord record = records.get(i);
            Row row = sheet.createRow(i + 1);
            int column = 0;
            writeCell(row, column++, record.lineNo, styles.body);
            writeCell(row, column++, record.mutantId, styles.body);
            writeCell(row, column++, displayRound(record), styles.body);
            writeCell(row, column++, record.runId, styles.body);
            writeCell(row, column++, record.targetStatus, styles.body);
            writeCell(row, column++, record.compileSuccess, styles.body);
            writeCell(row, column++, record.killed, styles.body);
            writeCell(row, column++, record.mutantExecuted, styles.body);
            writeCell(row, column++, record.originalPassed, styles.body);
            writeCell(row, column++, record.timedOut, styles.body);
            writeCell(row, column++, record.repairRounds, styles.body);
            writeCell(row, column++, record.compileCalls, styles.body);
            writeCell(row, column++, record.llmCalls, styles.body);
            writeCell(row, column++, record.elapsedMillis, styles.body);
            writeCell(row, column++, record.reward, styles.body);
            writeCell(row, column++, record.promptChars, styles.body);
            writeCell(row, column++, record.phase, styles.body);
            writeCell(row, column++, record.action, styles.body);
            writeCell(row, column++, record.evidenceAction, styles.body);
            writeCell(row, column++, record.terminationReason, styles.body);
            writeCell(row, column, record.failureReason, styles.wrapBody);
        }

        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(
                0,
                Math.max(0, records.size()),
                0,
                RAW_COLUMNS.size() - 1));
        setColumnWidths(sheet, new int[]{
                11, 82, 15, 23, 28, 15, 10, 16, 14, 10,
                12, 12, 10, 14, 14, 13, 18, 22, 18, 22, 110
        });
    }

    private static int countCompileSuccess(List<DetailRow> rows) {
        int count = 0;
        for (DetailRow row : rows) {
            if (Boolean.TRUE.equals(row.compileSuccess)) {
                count++;
            }
        }
        return count;
    }

    private static String inferProject(AnalysisResult result) {
        for (DetailRow row : result.allRows) {
            if (!isBlank(row.project)) {
                return row.project;
            }
        }
        return DEFAULT_PROJECT;
    }

    private static String buildRawPattern(List<TrajectoryRecord> history) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                builder.append(" → ");
            }
            builder.append(safe(history.get(i).targetStatus));
        }
        return builder.toString();
    }

    private static String extractCompileErrorSummary(String failureReason) {
        if (isBlank(failureReason)) {
            return "";
        }
        String text = failureReason;
        int marker = text.indexOf("[javac output]");
        if (marker >= 0) {
            text = text.substring(marker + "[javac output]".length());
        }
        return collapseWhitespace(text);
    }

    private static String categorizeCompileError(String summary) {
        String lower = safe(summary).toLowerCase(Locale.ROOT);
        if (lower.contains("reference to") && lower.contains("is ambiguous")) {
            return "方法调用歧义";
        }
        if (lower.contains("cannot be applied to given types")
                || lower.contains("actual and formal argument lists differ")) {
            return "参数数量/类型不匹配";
        }
        if (lower.contains("has private access")) {
            return "访问私有成员";
        }
        if (lower.contains("is not public") && lower.contains("cannot be accessed")) {
            return "非public成员不可访问";
        }
        if (lower.contains("cannot find symbol")) {
            return "找不到符号/方法";
        }
        if (lower.contains("incompatible types")) {
            return "类型不兼容";
        }
        if (lower.contains("no suitable method found")) {
            return "无匹配方法重载";
        }
        if (lower.contains("unreported exception")) {
            return "未处理受检异常";
        }
        if (lower.contains("cannot infer type arguments")) {
            return "泛型类型推断失败";
        }
        if (lower.contains("method does not override or implement")) {
            return "Override签名不匹配";
        }
        if (lower.contains("error:")) {
            return "其他编译错误";
        }
        return isBlank(summary) ? "无错误文本" : "其他失败";
    }

    private static String collapseWhitespace(String text) {
        return safe(text).replaceAll("\\s+", " ").trim();
    }

    private static String simpleClassName(String packageOrClassName) {
        String text = safe(packageOrClassName);
        int index = text.lastIndexOf('.');
        return index >= 0 ? text.substring(index + 1) : text;
    }

    private static String operatorFamily(String operator) {
        String text = safe(operator);
        int index = text.indexOf('_');
        return index > 0 ? text.substring(0, index) : text;
    }

    private static String equivalent01(String excelEquivalent, boolean trajectoryEquivalent) {
        String normalized = safe(excelEquivalent).toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
            return "1";
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
            return "0";
        }
        return trajectoryEquivalent ? "1" : "0";
    }

    private static double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0d : ((double) numerator) / denominator;
    }

    private static void writeHeaderRow(
            Sheet sheet,
            int rowIndex,
            int startColumn,
            List<String> headers,
            CellStyle style) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        row.setHeightInPoints(24);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(startColumn + i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(style);
        }
    }

    private static void writeObjectRows(
            Sheet sheet,
            int startRow,
            int startColumn,
            List<Object[]> values,
            CellStyle style) {
        for (int i = 0; i < values.size(); i++) {
            writeObjectRow(sheet, startRow + i, startColumn, values.get(i), style);
        }
    }

    private static void writeObjectRow(
            Sheet sheet,
            int rowIndex,
            int startColumn,
            Object[] values,
            CellStyle style) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        for (int i = 0; i < values.length; i++) {
            writeCell(row, startColumn + i, values[i], style);
        }
    }

    private static void writeCell(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value instanceof Boolean) {
            cell.setCellValue(((Boolean) value).booleanValue());
        } else if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value != null) {
            cell.setCellValue(excelSafe(String.valueOf(value)));
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void applyMergedStyle(
            Sheet sheet,
            CellStyle style,
            int firstRow,
            int lastRow,
            int firstColumn,
            int lastColumn) {
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                row = sheet.createRow(r);
            }
            for (int c = firstColumn; c <= lastColumn; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    cell = row.createCell(c);
                }
                cell.setCellStyle(style);
            }
        }
    }

    private static void setDetailColumnWidths(Sheet sheet) {
        int[] widths = new int[]{
                7, 78, 23, 48, 28, 52, 14, 15, 9, 55, 28, 12,
                15, 10, 10, 55, 10, 10, 23, 12, 12, 10, 15, 14,
                10, 22, 80, 100, 12, 12, 75, 75, 75, 10
        };
        setColumnWidths(sheet, widths);
    }

    private static void setColumnWidths(Sheet sheet, int[] characterWidths) {
        for (int i = 0; i < characterWidths.length; i++) {
            int width = Math.min(255, Math.max(1, characterWidths[i]));
            sheet.setColumnWidth(i, width * 256);
        }
    }

    private static String excelSafe(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= EXCEL_TEXT_LIMIT) {
            return text;
        }
        String suffix = " ...[truncated]";
        return text.substring(0, EXCEL_TEXT_LIMIT - suffix.length()) + suffix;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        return new DataFormatter().formatCellValue(cell);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String firstText(FieldRef... refs) {
        if (refs == null) {
            return "";
        }
        for (FieldRef ref : refs) {
            if (ref == null) {
                continue;
            }
            String value = text(ref.node, ref.field);
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static Boolean firstBoolean(FieldRef... refs) {
        if (refs == null) {
            return null;
        }
        for (FieldRef ref : refs) {
            if (ref == null || ref.node == null || ref.node.isMissingNode() || ref.node.isNull()) {
                continue;
            }
            JsonNode value = ref.node.path(ref.field);
            if (value.isBoolean()) {
                return Boolean.valueOf(value.asBoolean());
            }
            if (value.isTextual()) {
                String text = value.asText("").trim();
                if ("true".equalsIgnoreCase(text)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(text)) {
                    return Boolean.FALSE;
                }
            }
        }
        return null;
    }

    private static Integer firstInteger(FieldRef... refs) {
        Long value = firstLong(refs);
        if (value == null) {
            return null;
        }
        if (value.longValue() > Integer.MAX_VALUE || value.longValue() < Integer.MIN_VALUE) {
            return null;
        }
        return Integer.valueOf(value.intValue());
    }

    private static Long firstLong(FieldRef... refs) {
        if (refs == null) {
            return null;
        }
        for (FieldRef ref : refs) {
            if (ref == null || ref.node == null || ref.node.isMissingNode() || ref.node.isNull()) {
                continue;
            }
            JsonNode value = ref.node.path(ref.field);
            if (value.isIntegralNumber()) {
                return Long.valueOf(value.asLong());
            }
            if (value.isTextual()) {
                try {
                    return Long.valueOf(value.asText("").trim());
                } catch (NumberFormatException ignored) {
                    // try next candidate
                }
            }
        }
        return null;
    }

    private static Double firstDouble(FieldRef... refs) {
        if (refs == null) {
            return null;
        }
        for (FieldRef ref : refs) {
            if (ref == null || ref.node == null || ref.node.isMissingNode() || ref.node.isNull()) {
                continue;
            }
            JsonNode value = ref.node.path(ref.field);
            if (value.isNumber()) {
                return Double.valueOf(value.asDouble());
            }
            if (value.isTextual()) {
                try {
                    return Double.valueOf(value.asText("").trim());
                } catch (NumberFormatException ignored) {
                    // try next candidate
                }
            }
        }
        return null;
    }

    private static final class Args {
        private String trajectoryPath;
        private String excelPath;
        private String outputPath;
        private String outputDir;
        private String outputName;

        private static Args parse(String[] args) {
            Args parsed = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--trajectory".equals(arg) && i + 1 < args.length) {
                    parsed.trajectoryPath = args[++i];
                } else if ("--excel".equals(arg) && i + 1 < args.length) {
                    parsed.excelPath = args[++i];
                } else if ("--output".equals(arg) && i + 1 < args.length) {
                    parsed.outputPath = args[++i];
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
        private final List<String> header;
        private final Map<Integer, ExcelRow> byExcelRowNo;
        private final Map<MatchKey, ExcelRow> byKey;
        private final Map<String, ExcelRow> byMutantId;

        private ExcelData(
                List<String> header,
                Map<Integer, ExcelRow> byExcelRowNo,
                Map<MatchKey, ExcelRow> byKey,
                Map<String, ExcelRow> byMutantId) {
            this.header = header == null ? Collections.<String>emptyList() : header;
            this.byExcelRowNo = byExcelRowNo;
            this.byKey = byKey;
            this.byMutantId = byMutantId;
        }
    }

    private static final class ExcelRow {
        private final int excelRowNo;
        private final Map<String, Integer> headerIndex;
        private final List<String> values;

        private ExcelRow(
                int excelRowNo,
                Map<String, Integer> headerIndex,
                List<String> values) {
            this.excelRowNo = excelRowNo;
            this.headerIndex = headerIndex;
            this.values = values;
        }

        private String value(String column) {
            Integer index = headerIndex.get(column);
            if (index == null || index.intValue() < 0 || index.intValue() >= values.size()) {
                return "";
            }
            return safe(values.get(index.intValue()));
        }

        private MatchKey matchKey() {
            return new MatchKey(
                    value("project"),
                    value("package"),
                    value("method"),
                    value("operator"));
        }

        private String mutantId() {
            return value("project") + "::"
                    + value("package") + "::"
                    + value("method") + "::"
                    + value("operator");
        }
    }

    private static final class TrajectoryData {
        private final List<TrajectoryRecord> records;
        private final Map<String, List<TrajectoryRecord>> recordsByMutantId;

        private TrajectoryData(
                List<TrajectoryRecord> records,
                Map<String, List<TrajectoryRecord>> recordsByMutantId) {
            this.records = records;
            this.recordsByMutantId = recordsByMutantId;
        }
    }

    private static final class TrajectoryRecord {
        private final int lineNo;
        private int occurrenceIndex;
        private final String taskId;
        private final String mutantId;
        private final String project;
        private final String className;
        private final String method;
        private final String operator;
        private final String operatorFamily;
        private final String targetStatus;
        private final Boolean compileSuccess;
        private final Boolean killed;
        private final Boolean mutantExecuted;
        private final Boolean originalPassed;
        private final Boolean timedOut;
        private final Integer repairRounds;
        private final Integer compileCalls;
        private final Integer llmCalls;
        private final Long elapsedMillis;
        private final Double reward;
        private final Long promptChars;
        private final Integer outerLoopRound;
        private final String runId;
        private final String phase;
        private final String action;
        private final String testName;
        private final String fallbackLevel;
        private final String generationStrategy;
        private final Integer regenerationRound;
        private final String exactBucketKey;
        private final String coarseBucketKey;
        private final String evidenceAction;
        private final String terminationReason;
        private final String failureReason;
        private final boolean equivalenceSuspicion;

        private TrajectoryRecord(
                int lineNo,
                String taskId,
                String mutantId,
                String project,
                String className,
                String method,
                String operator,
                String operatorFamily,
                String targetStatus,
                Boolean compileSuccess,
                Boolean killed,
                Boolean mutantExecuted,
                Boolean originalPassed,
                Boolean timedOut,
                Integer repairRounds,
                Integer compileCalls,
                Integer llmCalls,
                Long elapsedMillis,
                Double reward,
                Long promptChars,
                Integer outerLoopRound,
                String runId,
                String phase,
                String action,
                String testName,
                String fallbackLevel,
                String generationStrategy,
                Integer regenerationRound,
                String exactBucketKey,
                String coarseBucketKey,
                String evidenceAction,
                String terminationReason,
                String failureReason,
                boolean equivalenceSuspicion) {
            this.lineNo = lineNo;
            this.taskId = taskId;
            this.mutantId = mutantId;
            this.project = project;
            this.className = className;
            this.method = method;
            this.operator = operator;
            this.operatorFamily = operatorFamily;
            this.targetStatus = targetStatus;
            this.compileSuccess = compileSuccess;
            this.killed = killed;
            this.mutantExecuted = mutantExecuted;
            this.originalPassed = originalPassed;
            this.timedOut = timedOut;
            this.repairRounds = repairRounds;
            this.compileCalls = compileCalls;
            this.llmCalls = llmCalls;
            this.elapsedMillis = elapsedMillis;
            this.reward = reward;
            this.promptChars = promptChars;
            this.outerLoopRound = outerLoopRound;
            this.runId = runId;
            this.phase = phase;
            this.action = action;
            this.testName = testName;
            this.fallbackLevel = fallbackLevel;
            this.generationStrategy = generationStrategy;
            this.regenerationRound = regenerationRound;
            this.exactBucketKey = exactBucketKey;
            this.coarseBucketKey = coarseBucketKey;
            this.evidenceAction = evidenceAction;
            this.terminationReason = terminationReason;
            this.failureReason = failureReason;
            this.equivalenceSuspicion = equivalenceSuspicion;
        }

        private static TrajectoryRecord fromJson(int lineNo, JsonNode root) {
            JsonNode stateAfter = root.path("stateAfter");
            JsonNode evidenceState = stateAfter.path("evidenceState");
            JsonNode state = root.path("state");

            String mutantId = firstText(
                    new FieldRef(root, "mutantId"),
                    new FieldRef(stateAfter, "mutantId"),
                    new FieldRef(stateAfter, "canonicalMutantId"),
                    new FieldRef(evidenceState, "mutantId"),
                    new FieldRef(state, "mutantId"));

            String project = firstText(
                    new FieldRef(root, "project"),
                    new FieldRef(stateAfter, "projectId"),
                    new FieldRef(evidenceState, "project"),
                    new FieldRef(state, "project"));
            String className = firstText(
                    new FieldRef(root, "className"),
                    new FieldRef(stateAfter, "className"),
                    new FieldRef(evidenceState, "className"),
                    new FieldRef(state, "className"));
            String method = firstText(
                    new FieldRef(root, "method"),
                    new FieldRef(stateAfter, "method"),
                    new FieldRef(evidenceState, "method"),
                    new FieldRef(state, "method"));
            String operator = firstText(
                    new FieldRef(root, "operator"),
                    new FieldRef(stateAfter, "operator"),
                    new FieldRef(evidenceState, "operator"),
                    new FieldRef(state, "operator"));

            if (isBlank(mutantId)
                    && !isBlank(project)
                    && !isBlank(className)
                    && !isBlank(method)
                    && !isBlank(operator)) {
                mutantId = project + "::" + className + "::" + method + "::" + operator;
            }

            Boolean compileSuccess = firstBoolean(
                    new FieldRef(root, "compileSuccess"),
                    new FieldRef(stateAfter, "compileSuccess"),
                    new FieldRef(stateAfter, "compiled"));
            Boolean killed = firstBoolean(
                    new FieldRef(root, "killed"),
                    new FieldRef(stateAfter, "killed"));
            String targetStatus = firstText(
                    new FieldRef(root, "targetStatus"),
                    new FieldRef(stateAfter, "targetStatus"));

            if (isBlank(targetStatus)) {
                if (Boolean.TRUE.equals(killed)) {
                    targetStatus = STATUS_KILLED;
                } else if (Boolean.FALSE.equals(compileSuccess)) {
                    targetStatus = STATUS_COMPILE_FAILED;
                } else if (Boolean.TRUE.equals(compileSuccess)) {
                    targetStatus = STATUS_SURVIVED;
                }
            }

            Boolean equivalent = firstBoolean(
                    new FieldRef(root, "equivalenceSuspicion"),
                    new FieldRef(stateAfter, "equivalenceSuspicion"));

            return new TrajectoryRecord(
                    lineNo,
                    firstText(new FieldRef(root, "taskId"), new FieldRef(stateAfter, "taskId")),
                    safe(mutantId),
                    safe(project),
                    safe(className),
                    safe(method),
                    safe(operator),
                    firstText(
                            new FieldRef(root, "operatorFamily"),
                            new FieldRef(stateAfter, "operatorFamily"),
                            new FieldRef(evidenceState, "operatorFamily"),
                            new FieldRef(state, "operatorFamily")),
                    safe(targetStatus),
                    compileSuccess,
                    killed,
                    firstBoolean(
                            new FieldRef(root, "mutantExecuted"),
                            new FieldRef(stateAfter, "mutantExecuted")),
                    firstBoolean(
                            new FieldRef(root, "originalPassed"),
                            new FieldRef(root, "originalPass"),
                            new FieldRef(stateAfter, "originalPassed")),
                    firstBoolean(
                            new FieldRef(root, "timedOut"),
                            new FieldRef(stateAfter, "timedOut")),
                    firstInteger(
                            new FieldRef(root, "repairRounds"),
                            new FieldRef(stateAfter, "repairRounds")),
                    firstInteger(
                            new FieldRef(root, "compileCalls"),
                            new FieldRef(stateAfter, "compileCalls"),
                            new FieldRef(root, "initialCompileCalls")),
                    firstInteger(
                            new FieldRef(root, "llmCalls"),
                            new FieldRef(stateAfter, "llmCalls"),
                            new FieldRef(root, "initialLlmCalls")),
                    firstLong(
                            new FieldRef(root, "elapsedMillis"),
                            new FieldRef(root, "elapsedMs"),
                            new FieldRef(stateAfter, "elapsedMillis")),
                    firstDouble(
                            new FieldRef(root, "reward"),
                            new FieldRef(root, "finalReward"),
                            new FieldRef(stateAfter, "reward")),
                    firstLong(
                            new FieldRef(root, "promptChars"),
                            new FieldRef(root, "promptTokensOrChars"),
                            new FieldRef(root, "initialPromptChars"),
                            new FieldRef(stateAfter, "promptChars")),
                    firstInteger(
                            new FieldRef(root, "outerLoopRound"),
                            new FieldRef(stateAfter, "outerLoopRound")),
                    firstText(new FieldRef(root, "runId"), new FieldRef(stateAfter, "runId")),
                    firstText(
                            new FieldRef(root, "phase"),
                            new FieldRef(stateAfter, "phase"),
                            new FieldRef(root, "generationStrategy")),
                    firstText(new FieldRef(root, "action"), new FieldRef(stateAfter, "action")),
                    firstText(new FieldRef(root, "testName"), new FieldRef(stateAfter, "testName")),
                    firstText(new FieldRef(root, "fallbackLevel"), new FieldRef(stateAfter, "fallbackLevel")),
                    firstText(new FieldRef(root, "generationStrategy"), new FieldRef(stateAfter, "generationStrategy")),
                    firstInteger(new FieldRef(root, "regenerationRound"), new FieldRef(stateAfter, "regenerationRound")),
                    firstText(new FieldRef(root, "exactBucketKey"), new FieldRef(stateAfter, "exactBucketKey")),
                    firstText(new FieldRef(root, "coarseBucketKey"), new FieldRef(stateAfter, "coarseBucketKey")),
                    firstText(
                            new FieldRef(root, "evidenceAction"),
                            new FieldRef(root, "initialEvidenceAction"),
                            new FieldRef(stateAfter, "evidenceAction")),
                    firstText(
                            new FieldRef(root, "terminationReason"),
                            new FieldRef(stateAfter, "terminationReason")),
                    firstText(
                            new FieldRef(root, "failureReason"),
                            new FieldRef(stateAfter, "failureReason")),
                    Boolean.TRUE.equals(equivalent));
        }

        private Integer excelRowNo() {
            if (isBlank(taskId)) {
                return null;
            }
            String normalized = taskId.trim();
            if (!normalized.startsWith("row-")) {
                return null;
            }
            try {
                return Integer.valueOf(normalized.substring(4));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static final class DetailRow {
        private String mutantId;
        private String project;
        private String packageName;
        private String className;
        private String method;
        private String operator;
        private String operatorFamily;
        private String line;
        private String mutationStatement;
        private String targetStatus;
        private String category;
        private Boolean compileSuccess;
        private Boolean killed;
        private int trajectoryCount;
        private String statusPath;
        private int firstRound;
        private int finalRound;
        private String finalRunId;
        private Long repairRounds;
        private Long compileCalls;
        private Long llmCalls;
        private Boolean mutantExecuted;
        private Boolean originalPassed;
        private Boolean timedOut;
        private String compileErrorCategory;
        private String compileErrorSummary;
        private String failureReason;
        private boolean excelMatched;
        private Integer excelRowNo;
        private String filePath;
        private String originalGraphPath;
        private String mutantGraphPath;
        private String equivalent;
    }

    private static final class NonExecutedRow {
        private final ExcelRow excelRow;
        private final TrajectoryRecord trajectoryRecord;

        private NonExecutedRow(ExcelRow excelRow, TrajectoryRecord trajectoryRecord) {
            this.excelRow = excelRow;
            this.trajectoryRecord = trajectoryRecord;
        }
    }

    private static final class AnalysisResult {
        private final TrajectoryData trajectoryData;
        private final List<String> excelHeader;
        private final List<DetailRow> allRows;
        private final List<DetailRow> killedRows;
        private final List<DetailRow> survivedRows;
        private final List<DetailRow> compileFailedRows;
        private final List<DetailRow> otherRows;
        private final List<NonExecutedRow> nonExecutedExpandedRows;
        private final List<NonExecutedRow> nonExecutedUniqueRows;
        private final int excelMatchedCount;
        private final int jsonOnlyCount;
        private final int everCompileFailedCount;
        private final int recoveredFromCompileFailureCount;
        private final long totalRawRepairRounds;
        private final long totalRawCompileCalls;
        private final long totalRawLlmCalls;

        private AnalysisResult(
                TrajectoryData trajectoryData,
                List<String> excelHeader,
                List<DetailRow> allRows,
                List<DetailRow> killedRows,
                List<DetailRow> survivedRows,
                List<DetailRow> compileFailedRows,
                List<DetailRow> otherRows,
                List<NonExecutedRow> nonExecutedExpandedRows,
                List<NonExecutedRow> nonExecutedUniqueRows,
                int excelMatchedCount,
                int jsonOnlyCount,
                int everCompileFailedCount,
                int recoveredFromCompileFailureCount,
                long totalRawRepairRounds,
                long totalRawCompileCalls,
                long totalRawLlmCalls) {
            this.trajectoryData = trajectoryData;
            this.excelHeader = excelHeader == null ? Collections.<String>emptyList() : excelHeader;
            this.allRows = allRows;
            this.killedRows = killedRows;
            this.survivedRows = survivedRows;
            this.compileFailedRows = compileFailedRows;
            this.otherRows = otherRows;
            this.nonExecutedExpandedRows = nonExecutedExpandedRows;
            this.nonExecutedUniqueRows = nonExecutedUniqueRows;
            this.excelMatchedCount = excelMatchedCount;
            this.jsonOnlyCount = jsonOnlyCount;
            this.everCompileFailedCount = everCompileFailedCount;
            this.recoveredFromCompileFailureCount = recoveredFromCompileFailureCount;
            this.totalRawRepairRounds = totalRawRepairRounds;
            this.totalRawCompileCalls = totalRawCompileCalls;
            this.totalRawLlmCalls = totalRawLlmCalls;
        }
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
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MatchKey)) {
                return false;
            }
            MatchKey that = (MatchKey) other;
            return Objects.equals(project, that.project)
                    && Objects.equals(packageName, that.packageName)
                    && Objects.equals(method, that.method)
                    && Objects.equals(operator, that.operator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(project, packageName, method, operator);
        }
    }

    private static final class MutantIdParts {
        private final String project;
        private final String packageName;
        private final String method;
        private final String operator;

        private MutantIdParts(String project, String packageName, String method, String operator) {
            this.project = project;
            this.packageName = packageName;
            this.method = method;
            this.operator = operator;
        }

        private static MutantIdParts parse(String mutantId) {
            String[] parts = safe(mutantId).split("::", 4);
            return new MutantIdParts(
                    parts.length > 0 ? parts[0] : "",
                    parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "",
                    parts.length > 3 ? parts[3] : "");
        }
    }

    private static final class StatusCount {
        private int total;
        private int killed;
        private int survived;
        private int compileFailed;
        private int other;

        private void add(String category) {
            total++;
            if (CATEGORY_KILLED.equals(category)) {
                killed++;
            } else if (CATEGORY_SURVIVED.equals(category)) {
                survived++;
            } else if (CATEGORY_COMPILE_FAILED.equals(category)) {
                compileFailed++;
            } else {
                other++;
            }
        }
    }

    private static final class FieldRef {
        private final JsonNode node;
        private final String field;

        private FieldRef(JsonNode node, String field) {
            this.node = node;
            this.field = field;
        }
    }

    private static final class Styles {
        private final CellStyle title;
        private final CellStyle section;
        private final CellStyle header;
        private final CellStyle body;
        private final CellStyle wrapBody;
        private final CellStyle percent;
        private final CellStyle killed;
        private final CellStyle survived;
        private final CellStyle compileFailed;

        private Styles(
                CellStyle title,
                CellStyle section,
                CellStyle header,
                CellStyle body,
                CellStyle wrapBody,
                CellStyle percent,
                CellStyle killed,
                CellStyle survived,
                CellStyle compileFailed) {
            this.title = title;
            this.section = section;
            this.header = header;
            this.body = body;
            this.wrapBody = wrapBody;
            this.percent = percent;
            this.killed = killed;
            this.survived = survived;
            this.compileFailed = compileFailed;
        }

        private CellStyle categoryStyle(String category) {
            if (CATEGORY_KILLED.equals(category)) {
                return killed;
            }
            if (CATEGORY_SURVIVED.equals(category)) {
                return survived;
            }
            if (CATEGORY_COMPILE_FAILED.equals(category)) {
                return compileFailed;
            }
            return body;
        }

        private static Styles create(XSSFWorkbook workbook) {
            CellStyle title = workbook.createCellStyle();
            title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setAlignment(HorizontalAlignment.LEFT);
            title.setVerticalAlignment(VerticalAlignment.CENTER);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setColor(IndexedColors.WHITE.getIndex());
            titleFont.setFontHeightInPoints((short) 16);
            title.setFont(titleFont);

            CellStyle section = workbook.createCellStyle();
            section.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            section.setAlignment(HorizontalAlignment.LEFT);
            section.setVerticalAlignment(VerticalAlignment.CENTER);
            Font sectionFont = workbook.createFont();
            sectionFont.setBold(true);
            sectionFont.setColor(IndexedColors.DARK_BLUE.getIndex());
            section.setFont(sectionFont);
            applyBorders(section);

            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(headerFont);
            applyBorders(header);

            CellStyle body = workbook.createCellStyle();
            body.setVerticalAlignment(VerticalAlignment.TOP);
            applyBorders(body);

            CellStyle wrapBody = workbook.createCellStyle();
            wrapBody.cloneStyleFrom(body);
            wrapBody.setWrapText(true);

            CellStyle percent = workbook.createCellStyle();
            percent.cloneStyleFrom(body);
            DataFormat dataFormat = workbook.createDataFormat();
            percent.setDataFormat(dataFormat.getFormat("0.00%"));

            CellStyle killed = workbook.createCellStyle();
            killed.cloneStyleFrom(body);
            killed.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            killed.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font killedFont = workbook.createFont();
            killedFont.setBold(true);
            killedFont.setColor(IndexedColors.DARK_GREEN.getIndex());
            killed.setFont(killedFont);

            CellStyle survived = workbook.createCellStyle();
            survived.cloneStyleFrom(body);
            survived.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            survived.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font survivedFont = workbook.createFont();
            survivedFont.setBold(true);
            survivedFont.setColor(IndexedColors.BROWN.getIndex());
            survived.setFont(survivedFont);

            CellStyle compileFailed = workbook.createCellStyle();
            compileFailed.cloneStyleFrom(body);
            compileFailed.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            compileFailed.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font compileFailedFont = workbook.createFont();
            compileFailedFont.setBold(true);
            compileFailedFont.setColor(IndexedColors.DARK_RED.getIndex());
            compileFailed.setFont(compileFailedFont);

            return new Styles(
                    title,
                    section,
                    header,
                    body,
                    wrapBody,
                    percent,
                    killed,
                    survived,
                    compileFailed);
        }

        private static void applyBorders(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }
}
