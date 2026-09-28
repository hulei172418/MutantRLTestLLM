package org.codekb.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProjectBuildSeed {
    private final String projectName;
    private final Path projectRoot;
    private final Path sourceModuleHome;
    private final Path resultModuleHome;
    private final List<Path> moduleRoots;
    private final List<Path> sourceRoots;
    private final String buildSystem;

    public ProjectBuildSeed(String projectName,
                            Path projectRoot,
                            Path sourceModuleHome,
                            Path resultModuleHome,
                            List<Path> moduleRoots,
                            List<Path> sourceRoots,
                            String buildSystem) {
        this.projectName = projectName == null ? "" : projectName;
        this.projectRoot = projectRoot;
        this.sourceModuleHome = sourceModuleHome;
        this.resultModuleHome = resultModuleHome;
        this.moduleRoots = Collections.unmodifiableList(new ArrayList<Path>(moduleRoots));
        this.sourceRoots = Collections.unmodifiableList(new ArrayList<Path>(sourceRoots));
        this.buildSystem = buildSystem == null ? "" : buildSystem;
    }

    public String getProjectName() {
        return projectName;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public Path getSourceModuleHome() {
        return sourceModuleHome;
    }

    public Path getResultModuleHome() {
        return resultModuleHome;
    }

    public List<Path> getModuleRoots() {
        return moduleRoots;
    }

    public List<Path> getSourceRoots() {
        return sourceRoots;
    }

    public String getBuildSystem() {
        return buildSystem;
    }
}
