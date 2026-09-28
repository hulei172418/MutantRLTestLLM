package org.codekb.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.codekb.model.MutantSeed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExcelMutantIndexLoader {
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public List<MutantSeed> load(Path excelPath) throws IOException {
        try (InputStream in = Files.newInputStream(excelPath);
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> headerIndex = readHeader(sheet.getRow(sheet.getFirstRowNum()));
            List<MutantSeed> seeds = new ArrayList<MutantSeed>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isEmpty(row)) {
                    continue;
                }
                seeds.add(mapRow(row, headerIndex));
            }
            return seeds;
        }
    }

    public MutantSeed mapRow(Row row, Map<String, Integer> headerIndex) {
        return new MutantSeed(
            text(row, headerIndex, "operator"),
            integer(row, headerIndex, "line"),
            text(row, headerIndex, "method"),
            text(row, headerIndex, "class"),
            text(row, headerIndex, "class_f"),
            text(row, headerIndex, "mutation_statement"),
            text(row, headerIndex, "package"),
            text(row, headerIndex, "project"),
            text(row, headerIndex, "file_path"),
            text(row, headerIndex, "original_graph_path"),
            text(row, headerIndex, "mutant_graph_path"),
            text(row, headerIndex, "is_kille")
        );
    }

    private Map<String, Integer> readHeader(Row headerRow) {
        Map<String, Integer> index = new HashMap<String, Integer>();
        for (Cell cell : headerRow) {
            index.put(formatter.formatCellValue(cell).trim(), cell.getColumnIndex());
        }
        return index;
    }

    private boolean isEmpty(Row row) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String text(Row row, Map<String, Integer> headerIndex, String key) {
        Integer column = headerIndex.get(key);
        if (column == null) {
            return "";
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private int integer(Row row, Map<String, Integer> headerIndex, String key) {
        String raw = text(row, headerIndex, key);
        if (raw.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
