package org.codekb.service;

import org.codekb.config.KbConfig;
import org.codekb.excel.ExcelMutantIndexLoader;
import org.codekb.model.MutantSeed;
import org.codekb.model.ProjectBuildSeed;
import org.codekb.parser.JavaStructureExtractor;
import org.codekb.store.MutantRepository;
import org.codekb.store.ProjectRepository;
import org.codekb.store.SchemaInitializer;
import org.codekb.store.SqliteConnectionFactory;
import org.codekb.store.StructureRepository;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KbBuildService {
    private final SqliteConnectionFactory connectionFactory;
    private final SchemaInitializer schemaInitializer;
    private final ExcelMutantIndexLoader excelLoader;
    private final ProjectRepository projectRepository;
    private final MutantRepository mutantRepository;
    private final StructureRepository structureRepository;
    private final MutantKbAssembler mutantKbAssembler;
    private final ProjectLayoutResolver projectLayoutResolver;
    private final ProjectSourceScanner projectSourceScanner;
    private final ProjectKbAssembler projectKbAssembler;
    private final MutantCandidateDerivationService mutantCandidateDerivationService;

    public KbBuildService() {
        this.connectionFactory = new SqliteConnectionFactory();
        this.schemaInitializer = new SchemaInitializer();
        this.excelLoader = new ExcelMutantIndexLoader();
        this.projectRepository = new ProjectRepository();
        this.mutantRepository = new MutantRepository();
        this.structureRepository = new StructureRepository();
        this.mutantKbAssembler = new MutantKbAssembler(new JavaStructureExtractor(), structureRepository, mutantRepository);
        this.projectLayoutResolver = new ProjectLayoutResolver();
        this.projectSourceScanner = new ProjectSourceScanner();
        this.projectKbAssembler = new ProjectKbAssembler(projectRepository, projectSourceScanner, mutantKbAssembler);
        this.mutantCandidateDerivationService = new MutantCandidateDerivationService();
    }

    public BuildSummary rebuild(KbConfig config) throws IOException, SQLException {
        List<MutantSeed> seeds = excelLoader.load(config.getExcelPath());
        try (Connection connection = connectionFactory.open(config)) {
            connection.setAutoCommit(false);
            try {
                if (config.isRebuildIfExists()) {
                    resetSchema(connection);
                }
                schemaInitializer.initialize(connection);
                Set<String> projects = new LinkedHashSet<String>();
                Map<String, ProjectBuildSeed> projectSeeds = deriveProjectSeeds(seeds);
                Map<String, Long> originalVariantIds = new LinkedHashMap<String, Long>();
                for (ProjectBuildSeed projectSeed : projectSeeds.values()) {
                    String projectName = projectSeed.getProjectName().isEmpty() ? "unknown-project" : projectSeed.getProjectName();
                    projects.add(projectName);
                    long projectId = projectRepository.upsertProject(connection, projectName);
                    long originalVariantId = projectRepository.upsertVariant(
                        connection, projectId, "ORIGINAL", "original", projectSeed.getProjectRoot().toString());
                    originalVariantIds.put(projectName, Long.valueOf(originalVariantId));
                    projectKbAssembler.assembleProject(connection, projectId, originalVariantId, projectSeed);
                }
                for (MutantSeed seed : seeds) {
                    String projectName = seed.getProject().isEmpty() ? "unknown-project" : seed.getProject();
                    projects.add(projectName);
                    long projectId = projectRepository.upsertProject(connection, projectName);
                    long originalVariantId = originalVariantIds.containsKey(projectName)
                        ? originalVariantIds.get(projectName).longValue()
                        : projectRepository.upsertVariant(connection, projectId, "ORIGINAL", "original", seed.resolveOriginalJavaPath().toString());
                    long mutantVariantId = projectRepository.upsertVariant(
                        connection, projectId, "MUTANT", seed.getMutantId(), seed.resolveMutantJavaPath().toString());
                    long mutantRowId = mutantRepository.upsertMutant(connection, projectId, seed);
                    mutantKbAssembler.assemble(connection, mutantRowId, originalVariantId, mutantVariantId, seed);
                    mutantCandidateDerivationService.refreshForMutant(connection, mutantRowId);
                }
                connection.commit();
                return new BuildSummary(
                    config.getDbPath().toString(),
                    projects.size(),
                    seeds.size(),
                    countRows(connection, "modules"),
                    countRows(connection, "source_roots"),
                    countRows(connection, "files"),
                    countRows(connection, "types"),
                    countRows(connection, "methods"),
                    countRows(connection, "method_parameters"),
                    countRows(connection, "method_throws"),
                    countRows(connection, "type_hierarchy"),
                    countRows(connection, "method_calls"),
                    countRows(connection, "field_accesses"),
                    countRows(connection, "import_facts"),
                    countRows(connection, "import_usage_links"),
                    countRows(connection, "candidate_imports")
                );
            } catch (Exception ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            }
        }
    }

    public BuildSummary rebuildProject(KbConfig config, String projectName, ProjectBuildSeed projectSeed)
        throws IOException, SQLException {
        try (Connection connection = connectionFactory.open(config)) {
            connection.setAutoCommit(false);
            try {
                if (config.isRebuildIfExists()) {
                    resetSchema(connection);
                }
                schemaInitializer.initialize(connection);
                String resolvedProjectName = projectName == null || projectName.trim().isEmpty()
                    ? "unknown-project"
                    : projectName.trim();
                long projectId = projectRepository.upsertProject(connection, resolvedProjectName);
                long originalVariantId = projectRepository.upsertVariant(
                    connection,
                    projectId,
                    "ORIGINAL",
                    "original",
                    projectSeed.getProjectRoot().toString()
                );
                projectKbAssembler.assembleProject(connection, projectId, originalVariantId, projectSeed);
                connection.commit();
                return new BuildSummary(
                    config.getDbPath().toString(),
                    1,
                    0,
                    countRows(connection, "modules"),
                    countRows(connection, "source_roots"),
                    countRows(connection, "files"),
                    countRows(connection, "types"),
                    countRows(connection, "methods"),
                    countRows(connection, "method_parameters"),
                    countRows(connection, "method_throws"),
                    countRows(connection, "type_hierarchy"),
                    countRows(connection, "method_calls"),
                    countRows(connection, "field_accesses"),
                    countRows(connection, "import_facts"),
                    countRows(connection, "import_usage_links"),
                    countRows(connection, "candidate_imports")
                );
            } catch (Exception ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            }
        }
    }

    private Map<String, ProjectBuildSeed> deriveProjectSeeds(List<MutantSeed> seeds) throws IOException {
        Map<String, ProjectBuildSeed> projects = new LinkedHashMap<String, ProjectBuildSeed>();
        for (MutantSeed seed : seeds) {
            String projectName = seed.getProject().isEmpty() ? "unknown-project" : seed.getProject();
            if (!projects.containsKey(projectName)) {
                projects.put(projectName, projectLayoutResolver.resolve(seed));
            }
        }
        return projects;
    }

    private void resetSchema(Connection connection) throws SQLException {
        String[] statements = new String[] {
            "DROP VIEW IF EXISTS candidate_imports",
            "DROP TABLE IF EXISTS candidate_imports",
            "DROP TABLE IF EXISTS schema_comments",
            "DROP TABLE IF EXISTS equivalent_suspicions",
            "DROP TABLE IF EXISTS mutant_witness_candidates",
            "DROP TABLE IF EXISTS field_observer_links",
            "DROP TABLE IF EXISTS observable_candidates",
            "DROP TABLE IF EXISTS entry_candidates",
            "DROP TABLE IF EXISTS type_hierarchy",
            "DROP TABLE IF EXISTS method_throws",
            "DROP TABLE IF EXISTS method_parameters",
            "DROP TABLE IF EXISTS mutant_method_links",
            "DROP TABLE IF EXISTS mutant_file_links",
            "DROP TABLE IF EXISTS import_usage_links",
            "DROP TABLE IF EXISTS import_facts",
            "DROP TABLE IF EXISTS field_accesses",
            "DROP TABLE IF EXISTS method_calls",
            "DROP TABLE IF EXISTS fields",
            "DROP TABLE IF EXISTS methods",
            "DROP TABLE IF EXISTS types",
            "DROP TABLE IF EXISTS files",
            "DROP TABLE IF EXISTS source_roots",
            "DROP TABLE IF EXISTS modules",
            "DROP TABLE IF EXISTS mutant_artifacts",
            "DROP TABLE IF EXISTS mutants",
            "DROP TABLE IF EXISTS program_variants",
            "DROP TABLE IF EXISTS projects"
        };
        for (String sql : statements) {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.executeUpdate();
            } catch (SQLException ignored) {
                // Legacy databases may define candidate_imports as a table while the
                // rebuilt schema exposes it as a compatibility view. Try both forms
                // and keep going so in-place rebuilds can succeed under file locks.
            }
        }
    }

    private int countRows(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    public static final class BuildSummary {
        private final String dbPath;
        private final int projectCount;
        private final int mutantCount;
        private final int moduleCount;
        private final int sourceRootCount;
        private final int fileCount;
        private final int typeCount;
        private final int methodCount;
        private final int methodParameterCount;
        private final int methodThrowsCount;
        private final int typeHierarchyCount;
        private final int methodCallCount;
        private final int fieldAccessCount;
        private final int importFactCount;
        private final int importUsageCount;
        private final int candidateImportCount;

        public BuildSummary(
            String dbPath,
            int projectCount,
            int mutantCount,
            int moduleCount,
            int sourceRootCount,
            int fileCount,
            int typeCount,
            int methodCount,
            int methodParameterCount,
            int methodThrowsCount,
            int typeHierarchyCount,
            int methodCallCount,
            int fieldAccessCount,
            int importFactCount,
            int importUsageCount,
            int candidateImportCount
        ) {
            this.dbPath = dbPath;
            this.projectCount = projectCount;
            this.mutantCount = mutantCount;
            this.moduleCount = moduleCount;
            this.sourceRootCount = sourceRootCount;
            this.fileCount = fileCount;
            this.typeCount = typeCount;
            this.methodCount = methodCount;
            this.methodParameterCount = methodParameterCount;
            this.methodThrowsCount = methodThrowsCount;
            this.typeHierarchyCount = typeHierarchyCount;
            this.methodCallCount = methodCallCount;
            this.fieldAccessCount = fieldAccessCount;
            this.importFactCount = importFactCount;
            this.importUsageCount = importUsageCount;
            this.candidateImportCount = candidateImportCount;
        }

        public String getDbPath() {
            return dbPath;
        }

        public int getProjectCount() {
            return projectCount;
        }

        public int getMutantCount() {
            return mutantCount;
        }

        public int getModuleCount() {
            return moduleCount;
        }

        public int getSourceRootCount() {
            return sourceRootCount;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getTypeCount() {
            return typeCount;
        }

        public int getMethodCount() {
            return methodCount;
        }

        public int getMethodParameterCount() {
            return methodParameterCount;
        }

        public int getMethodThrowsCount() {
            return methodThrowsCount;
        }

        public int getTypeHierarchyCount() {
            return typeHierarchyCount;
        }

        public int getMethodCallCount() {
            return methodCallCount;
        }

        public int getFieldAccessCount() {
            return fieldAccessCount;
        }

        public int getImportFactCount() {
            return importFactCount;
        }

        public int getImportUsageCount() {
            return importUsageCount;
        }

        public int getCandidateImportCount() {
            return candidateImportCount;
        }
    }
}
