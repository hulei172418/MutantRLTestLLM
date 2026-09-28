package org.codekb.cli;

import org.codekb.config.KbConfig;
import org.codekb.excel.ExcelMutantIndexLoader;
import org.codekb.model.MutantSeed;
import org.codekb.store.SqliteConnectionFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CodeKbVerifyCli {
    private static final String[] REQUIRED_TABLES = new String[] {
        "projects",
        "modules",
        "source_roots",
        "mutants",
        "mutant_artifacts",
        "files",
        "types",
        "methods",
        "method_parameters",
        "method_throws",
        "type_hierarchy",
        "kb_feedback_events",
        "kb_feedback_candidates"
    };
    private static final String[] REQUIRED_VIEWS_OR_TABLES = new String[] {
        "candidate_imports"
    };

    private CodeKbVerifyCli() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length > 0 ? Paths.get(args[0]) : Paths.get("").toAbsolutePath();
        Set<String> expectedProjects = new LinkedHashSet<String>();
        Path excelPath = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--project".equalsIgnoreCase(arg) && i + 1 < args.length) {
                String value = args[++i];
                if (value != null && !value.trim().isEmpty()) {
                    expectedProjects.add(value.trim());
                }
            } else if ("--excel".equalsIgnoreCase(arg) && i + 1 < args.length) {
                String value = args[++i];
                if (value != null && !value.trim().isEmpty()) {
                    excelPath = Paths.get(value.trim());
                }
            }
        }

        KbConfig config = KbConfig.defaults(workspaceRoot);
        Path dbPath = config.getDbPath();
        if (!Files.isRegularFile(dbPath)) {
            throw new IllegalStateException("CodeKB db missing: " + dbPath.toAbsolutePath().normalize());
        }
        long size = Files.size(dbPath);
        if (size <= 0L) {
            throw new IllegalStateException("CodeKB db is empty: " + dbPath.toAbsolutePath().normalize());
        }

        SqliteConnectionFactory factory = new SqliteConnectionFactory();
        try (Connection connection = factory.open(config)) {
            for (String table : REQUIRED_TABLES) {
                if (!tableExists(connection, table)) {
                    throw new IllegalStateException("CodeKB missing required table: " + table);
                }
            }
            for (String objectName : REQUIRED_VIEWS_OR_TABLES) {
                if (!tableOrViewExists(connection, objectName)) {
                    throw new IllegalStateException("CodeKB missing required table/view: " + objectName);
                }
            }
            int mutants = countRows(connection, "mutants");
            int modules = countRows(connection, "modules");
            int sourceRoots = countRows(connection, "source_roots");
            int files = countRows(connection, "files");
            int methods = countRows(connection, "methods");
            int methodParameters = countRows(connection, "method_parameters");
            int methodThrows = countRows(connection, "method_throws");
            int typeHierarchy = countRows(connection, "type_hierarchy");
            if (mutants <= 0 || files <= 0 || methods <= 0 || modules <= 0 || sourceRoots <= 0) {
                throw new IllegalStateException("CodeKB core tables are empty: mutants=" + mutants
                        + ", modules=" + modules + ", sourceRoots=" + sourceRoots
                        + ", files=" + files + ", methods=" + methods);
            }

            Set<String> actualProjects = loadProjects(connection);
            Set<String> missingProjects = new LinkedHashSet<String>();
            for (String project : expectedProjects) {
                if (!actualProjects.contains(project)) {
                    missingProjects.add(project);
                }
            }
            if (!missingProjects.isEmpty()) {
                throw new IllegalStateException("CodeKB missing expected projects: " + missingProjects);
            }

            int expectedMutants = 0;
            if (excelPath != null) {
                List<MutantSeed> seeds = new ExcelMutantIndexLoader().load(excelPath);
                expectedMutants = seeds.size();
                List<String> missingMutants = findMissingMutants(connection, seeds);
                if (!missingMutants.isEmpty()) {
                    throw new IllegalStateException("CodeKB missing mutants from excel: count="
                            + missingMutants.size() + ", samples=" + sample(missingMutants, 10));
                }
            }

            System.out.println("status=READY");
            System.out.println("db=" + dbPath.toAbsolutePath().normalize());
            System.out.println("db_bytes=" + size);
            System.out.println("projects=" + actualProjects.size());
            System.out.println("mutants=" + mutants);
            System.out.println("modules=" + modules);
            System.out.println("source_roots=" + sourceRoots);
            System.out.println("files=" + files);
            System.out.println("methods=" + methods);
            System.out.println("method_parameters=" + methodParameters);
            System.out.println("method_throws=" + methodThrows);
            System.out.println("type_hierarchy=" + typeHierarchy);
            if (!expectedProjects.isEmpty()) {
                System.out.println("expected_projects=" + expectedProjects.size());
                System.out.println("verified_projects=" + String.join(",", expectedProjects));
            }
            if (excelPath != null) {
                System.out.println("expected_mutants=" + expectedMutants);
                System.out.println("verified_excel=" + excelPath.toAbsolutePath().normalize());
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND lower(name) = ?")) {
            stmt.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean tableOrViewExists(Connection connection, String objectName) throws Exception {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table', 'view') AND lower(name) = ?")) {
            stmt.setString(1, objectName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Set<String> loadProjects(Connection connection) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        try (PreparedStatement stmt = connection.prepareStatement("SELECT name FROM projects");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.trim().isEmpty()) {
                    out.add(name.trim());
                }
            }
        }
        return out;
    }

    private static List<String> findMissingMutants(Connection connection, List<MutantSeed> seeds) throws Exception {
        List<String> missing = new ArrayList<String>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM mutants WHERE mutant_id = ?")) {
            for (MutantSeed seed : seeds) {
                String mutantId = seed.getMutantId();
                if (mutantId == null || mutantId.trim().isEmpty()) {
                    continue;
                }
                stmt.setString(1, mutantId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) <= 0) {
                        missing.add(mutantId);
                    }
                }
            }
        }
        return missing;
    }

    private static String sample(List<String> values, int limit) {
        if (values.size() <= limit) {
            return values.toString();
        }
        return values.subList(0, limit).toString() + "...";
    }
}
