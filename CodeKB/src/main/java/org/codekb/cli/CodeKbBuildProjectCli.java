package org.codekb.cli;

import org.codekb.config.KbConfig;
import org.codekb.model.ProjectBuildSeed;
import org.codekb.service.KbBuildService;
import org.codekb.service.ProjectLayoutResolver;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class CodeKbBuildProjectCli {
    private static final String DIRECT_DB_FILE = "./data/kb/codekb.sqlite";
    private static final String DIRECT_PROJECT_NAME = "commons-lang3-3.17.0";
    private static final String DIRECT_PROJECT_ROOT =
        "E:\\PHD\\testJava\\Programs\\" + DIRECT_PROJECT_NAME + "\\" + DIRECT_PROJECT_NAME;

    private CodeKbBuildProjectCli() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length > 0 ? Paths.get(args[0]) : Paths.get("").toAbsolutePath();
        boolean rebuild = false;
        Path dbPath = null;
        Path projectRoot = null;
        String projectName = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--rebuild".equalsIgnoreCase(arg)) {
                rebuild = true;
            } else if ("--db".equalsIgnoreCase(arg) && i + 1 < args.length) {
                dbPath = workspaceRoot.resolve(Paths.get(args[++i])).normalize();
            } else if ("--projectRoot".equalsIgnoreCase(arg) && i + 1 < args.length) {
                projectRoot = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else if ("--project".equalsIgnoreCase(arg) && i + 1 < args.length) {
                projectName = args[++i];
            }
        }
        projectRoot = projectRoot == null
            ? Paths.get(DIRECT_PROJECT_ROOT).toAbsolutePath().normalize()
            : projectRoot;
        projectName = projectName == null || projectName.trim().isEmpty()
            ? DIRECT_PROJECT_NAME
            : projectName;
        if (projectName == null || projectName.trim().isEmpty()) {
            Path fileName = projectRoot.getFileName();
            projectName = fileName == null ? "unknown-project" : fileName.toString();
        }

        KbConfig defaults = KbConfig.defaults(workspaceRoot);
        KbConfig config = new KbConfig(
                defaults.getWorkspaceRoot(),
                dbPath == null ? workspaceRoot.resolve(Paths.get(DIRECT_DB_FILE)).normalize() : dbPath,
                defaults.getExcelPath(),
                rebuild);
        ProjectBuildSeed buildSeed = new ProjectLayoutResolver().resolveProjectRoot(projectName, projectRoot);
        KbBuildService.BuildSummary summary = new KbBuildService().rebuildProject(config, projectName, buildSeed);
        System.out.println("CodeKB initialized");
        System.out.println("project=" + projectName);
        System.out.println("project_root=" + buildSeed.getProjectRoot());
        System.out.println("source_module_home=" + buildSeed.getSourceModuleHome());
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
