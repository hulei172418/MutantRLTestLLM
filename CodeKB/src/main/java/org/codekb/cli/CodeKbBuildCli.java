package org.codekb.cli;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.codekb.config.KbConfig;
import org.codekb.service.KbBuildService;

public final class CodeKbBuildCli {
    private static final String DIRECT_DB_FILE = "./data/kb/codekb.sqlite";
    private static final String DIRECT_EXCEL_FILE = "./data/trajectory-not-killed/commons-csv-1.2-suite-not-killed-plain-1.xlsx";

    private CodeKbBuildCli() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length > 0 ? Paths.get(args[0]) : Paths.get("").toAbsolutePath();
        boolean rebuild = false;
        Path dbPath = null;
        Path excelPath = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--rebuild".equalsIgnoreCase(arg)) {
                rebuild = true;
            } else if ("--db".equalsIgnoreCase(arg) && i + 1 < args.length) {
                dbPath = workspaceRoot.resolve(Paths.get(args[++i])).normalize();
            } else if ("--excel".equalsIgnoreCase(arg) && i + 1 < args.length) {
                excelPath = workspaceRoot.resolve(Paths.get(args[++i])).normalize();
            }
        }
        KbConfig defaults = KbConfig.defaults(workspaceRoot);
        KbConfig config = new KbConfig(
            defaults.getWorkspaceRoot(),
            dbPath == null ? workspaceRoot.resolve(Paths.get(DIRECT_DB_FILE)).normalize() : dbPath,
            excelPath == null ? workspaceRoot.resolve(Paths.get(DIRECT_EXCEL_FILE)).normalize() : excelPath,
            rebuild
        );
        KbBuildService.BuildSummary summary = new KbBuildService().rebuild(config);
        System.out.println("CodeKB initialized");
        System.out.println("excel=" + config.getExcelPath());
        System.out.println("db=" + summary.getDbPath());
        System.out.println("projects=" + summary.getProjectCount());
        System.out.println("mutants=" + summary.getMutantCount());
        System.out.println("modules=" + summary.getModuleCount());
        System.out.println("source_roots=" + summary.getSourceRootCount());
        System.out.println("files=" + summary.getFileCount());
        System.out.println("types=" + summary.getTypeCount());
        System.out.println("methods=" + summary.getMethodCount());
        System.out.println("method_parameters=" + summary.getMethodParameterCount());
        System.out.println("method_throws=" + summary.getMethodThrowsCount());
        System.out.println("type_hierarchy=" + summary.getTypeHierarchyCount());
        System.out.println("method_calls=" + summary.getMethodCallCount());
        System.out.println("field_accesses=" + summary.getFieldAccessCount());
        System.out.println("import_facts=" + summary.getImportFactCount());
        System.out.println("import_usage_links=" + summary.getImportUsageCount());
        System.out.println("candidate_imports_view=" + summary.getCandidateImportCount());
    }
}
