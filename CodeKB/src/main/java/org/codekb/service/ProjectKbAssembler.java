package org.codekb.service;

import org.codekb.model.ProjectBuildSeed;
import org.codekb.store.ProjectRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProjectKbAssembler {
    private final ProjectRepository projectRepository;
    private final ProjectSourceScanner sourceScanner;
    private final MutantKbAssembler mutantKbAssembler;

    public ProjectKbAssembler(ProjectRepository projectRepository,
                              ProjectSourceScanner sourceScanner,
                              MutantKbAssembler mutantKbAssembler) {
        this.projectRepository = projectRepository;
        this.sourceScanner = sourceScanner;
        this.mutantKbAssembler = mutantKbAssembler;
    }

    public void assembleProject(Connection connection, long projectId, long originalVariantId, ProjectBuildSeed seed)
        throws IOException, SQLException {
        Map<String, Long> moduleIdsByRoot = new HashMap<String, Long>();
        for (Path moduleRoot : seed.getModuleRoots()) {
            String normalizedModuleRoot = normalize(moduleRoot);
            long moduleId = projectRepository.upsertModule(
                connection,
                projectId,
                moduleRoot.getFileName() == null ? normalizedModuleRoot : moduleRoot.getFileName().toString(),
                normalizedModuleRoot,
                seed.getBuildSystem()
            );
            moduleIdsByRoot.put(normalizedModuleRoot, Long.valueOf(moduleId));
        }
        for (Path sourceRoot : seed.getSourceRoots()) {
            Long moduleId = nearestModuleId(sourceRoot, moduleIdsByRoot);
            projectRepository.upsertSourceRoot(
                connection,
                projectId,
                moduleId,
                normalize(sourceRoot),
                "MAIN_JAVA"
            );
        }
        List<Path> javaFiles = sourceScanner.scanJavaFiles(seed);
        for (Path javaFile : javaFiles) {
            mutantKbAssembler.ingestSourceFile(connection, originalVariantId, javaFile);
        }
    }

    private Long nearestModuleId(Path sourceRoot, Map<String, Long> moduleIdsByRoot) {
        String normalizedSourceRoot = normalize(sourceRoot);
        Long best = null;
        int bestLen = -1;
        for (Map.Entry<String, Long> entry : moduleIdsByRoot.entrySet()) {
            String moduleRoot = entry.getKey();
            if (normalizedSourceRoot.startsWith(moduleRoot) && moduleRoot.length() > bestLen) {
                best = entry.getValue();
                bestLen = moduleRoot.length();
            }
        }
        return best;
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
