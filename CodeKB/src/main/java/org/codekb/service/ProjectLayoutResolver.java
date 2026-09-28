package org.codekb.service;

import org.codekb.model.MutantSeed;
import org.codekb.model.ProjectBuildSeed;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProjectLayoutResolver {
    private static final Pattern RESULT_PREFIX = Pattern.compile("(?i)^(.*?)(?=[\\\\/]+result(?:[\\\\/]+|$))");
    private static final String[] EXCLUDED_MODULE_NAMES = new String[] {
        "test", "tests", "llm", "evosuite"
    };

    public ProjectBuildSeed resolve(MutantSeed seed) throws IOException {
        Path rawFilePath = sanitizePath(seed.getFilePath());
        Path basePath = rawFilePath.getParent() == null ? rawFilePath : rawFilePath.getParent();
        String workDir = getCurr(basePath.toString());
        Path resultModuleHome = Paths.get(workDir).toAbsolutePath().normalize();
        Path outerRoot = resultModuleHome.getParent();

        Path sourceRootHome = resolveSourceRootHome(resultModuleHome, seed.getProject());
        Path sourceModuleHome = resolveSourceModuleHome(resultModuleHome, sourceRootHome);
        List<Path> moduleRoots = discoverModuleRoots(sourceRootHome, sourceModuleHome);
        List<Path> sourceRoots = discoverSourceRoots(moduleRoots);
        if (sourceRoots.isEmpty() && isUsableProjectContainer(sourceModuleHome)) {
            Path fallbackSourceRoot = sourceModuleHome.resolve(Paths.get("src", "main", "java")).normalize();
            if (Files.isDirectory(fallbackSourceRoot)) {
                sourceRoots.add(fallbackSourceRoot);
            }
        }

        Path projectRoot = resolveProjectRoot(sourceRootHome, sourceModuleHome, moduleRoots);
        if (projectRoot == null) {
            projectRoot = outerRoot;
        }
        String buildSystem = detectBuildSystem(projectRoot, moduleRoots);
        return new ProjectBuildSeed(
            seed.getProject(),
            projectRoot,
            sourceModuleHome,
            resultModuleHome,
            moduleRoots,
            sourceRoots,
            buildSystem
        );
    }

    public ProjectBuildSeed resolveProjectRoot(String projectName, Path projectRoot) throws IOException {
        Path normalizedProjectRoot = projectRoot == null
            ? Paths.get("").toAbsolutePath().normalize()
            : projectRoot.toAbsolutePath().normalize();
        Path sourceModuleHome = resolveExplicitSourceModuleHome(normalizedProjectRoot);
        List<Path> moduleRoots = discoverModuleRoots(normalizedProjectRoot, sourceModuleHome);
        List<Path> sourceRoots = discoverSourceRoots(moduleRoots);
        if (sourceRoots.isEmpty() && isUsableProjectContainer(sourceModuleHome)) {
            Path fallbackSourceRoot = sourceModuleHome.resolve(Paths.get("src", "main", "java")).normalize();
            if (Files.isDirectory(fallbackSourceRoot)) {
                sourceRoots.add(fallbackSourceRoot);
            }
        }
        Path resolvedProjectRoot = resolveProjectRoot(normalizedProjectRoot, sourceModuleHome, moduleRoots);
        String buildSystem = detectBuildSystem(resolvedProjectRoot, moduleRoots);
        return new ProjectBuildSeed(
            projectName,
            resolvedProjectRoot,
            sourceModuleHome,
            normalizedProjectRoot,
            moduleRoots,
            sourceRoots,
            buildSystem
        );
    }

    private Path sanitizePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        return Paths.get(raw.replace("\\\\?\\", "")).toAbsolutePath().normalize();
    }

    private String getCurr(String workDir) {
        Matcher matcher = RESULT_PREFIX.matcher(workDir);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Unable to resolve result root from path: " + workDir);
    }

    private Path resolveSourceRootHome(Path resultModuleHome, String projectName) {
        Path resultPath = resultModuleHome.normalize();
        Path outerRoot = resultPath.getParent();
        Path nestedProject = resultPath.resolve(projectName).normalize();
        if (isUsableProjectContainer(nestedProject)) {
            return nestedProject;
        }
        if (outerRoot != null) {
            Path siblingProject = outerRoot.resolve(projectName).normalize();
            if (isUsableProjectContainer(siblingProject)) {
                return siblingProject;
            }
        }
        if (isUsableProjectContainer(resultPath)) {
            return resultPath;
        }
        if (outerRoot != null && isUsableProjectContainer(outerRoot)) {
            return outerRoot;
        }
        return nestedProject;
    }

    private Path resolveSourceModuleHome(Path resultModuleHome, Path sourceRootHome) {
        Path resultPath = resultModuleHome.normalize();
        String moduleName = resultPath.getFileName() == null ? "" : resultPath.getFileName().toString();
        Path candidate = sourceRootHome.resolve(moduleName).normalize();
        if (isUsableModuleHome(candidate)) {
            return candidate;
        }
        if (isUsableModuleHome(sourceRootHome)) {
            return sourceRootHome;
        }
        return candidate;
    }

    private Path resolveExplicitSourceModuleHome(Path projectRoot) throws IOException {
        if (isUsableModuleHome(projectRoot) && Files.isDirectory(projectRoot.resolve(Paths.get("src", "main", "java")))) {
            return projectRoot.toAbsolutePath().normalize();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectRoot)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (isExcludedModuleRoot(child)) {
                    continue;
                }
                if (isUsableModuleHome(child) && Files.isDirectory(child.resolve(Paths.get("src", "main", "java")))) {
                    return child.toAbsolutePath().normalize();
                }
            }
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private List<Path> discoverModuleRoots(Path projectRoot, Path sourceModuleHome) throws IOException {
        LinkedHashSet<Path> modules = new LinkedHashSet<Path>();
        if (isUsableModuleHome(sourceModuleHome)) {
            modules.add(sourceModuleHome.toAbsolutePath().normalize());
        }
        if (isUsableProjectContainer(projectRoot)) {
            if (Files.isDirectory(projectRoot.resolve(Paths.get("src", "main", "java")))) {
                addModuleIfAllowed(modules, projectRoot);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectRoot)) {
                for (Path child : stream) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    if (isUsableModuleHome(child) && Files.isDirectory(child.resolve(Paths.get("src", "main", "java")))) {
                        addModuleIfAllowed(modules, child);
                    }
                }
            }
        }
        return new ArrayList<Path>(modules);
    }

    private Path resolveProjectRoot(Path sourceRootHome, Path sourceModuleHome, List<Path> moduleRoots) {
        if (isUsableModuleHome(sourceModuleHome)) {
            if (moduleRoots.size() <= 1) {
                return sourceModuleHome.toAbsolutePath().normalize();
            }
            Path parent = sourceModuleHome.getParent();
            if (parent != null && parent.equals(sourceRootHome != null ? sourceRootHome.toAbsolutePath().normalize() : null)) {
                return parent;
            }
            return sourceModuleHome.toAbsolutePath().normalize();
        }
        if (sourceRootHome != null && isUsableProjectContainer(sourceRootHome)) {
            return sourceRootHome.toAbsolutePath().normalize();
        }
        return null;
    }

    private List<Path> discoverSourceRoots(List<Path> moduleRoots) {
        LinkedHashSet<Path> roots = new LinkedHashSet<Path>();
        for (Path moduleRoot : moduleRoots) {
            Path sourceRoot = moduleRoot.resolve(Paths.get("src", "main", "java")).normalize();
            if (Files.isDirectory(sourceRoot)) {
                roots.add(sourceRoot.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<Path>(roots);
    }

    private void addModuleIfAllowed(Set<Path> modules, Path moduleRoot) {
        Path normalized = moduleRoot.toAbsolutePath().normalize();
        if (!isExcludedModuleRoot(normalized)) {
            modules.add(normalized);
        }
    }

    private String detectBuildSystem(Path projectRoot, List<Path> moduleRoots) {
        if (projectRoot != null && Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return "MAVEN";
        }
        for (Path moduleRoot : moduleRoots) {
            if (Files.isRegularFile(moduleRoot.resolve("pom.xml"))) {
                return "MAVEN";
            }
            if (Files.isRegularFile(moduleRoot.resolve("build.gradle"))
                    || Files.isRegularFile(moduleRoot.resolve("build.gradle.kts"))) {
                return "GRADLE";
            }
        }
        return "PLAIN";
    }

    private boolean isUsableProjectContainer(Path dir) {
        return dir != null
            && Files.isDirectory(dir)
            && (Files.exists(dir.resolve("pom.xml"))
            || Files.isDirectory(dir.resolve("src"))
            || Files.isDirectory(dir.resolve("target")));
    }

    private boolean isUsableModuleHome(Path dir) {
        return isUsableProjectContainer(dir);
    }

    private boolean isExcludedModuleRoot(Path dir) {
        if (dir == null) {
            return false;
        }
        Path fileName = dir.getFileName();
        if (fileName == null) {
            return false;
        }
        String lowered = fileName.toString().toLowerCase();
        for (String excluded : EXCLUDED_MODULE_NAMES) {
            if (excluded.equals(lowered)) {
                return true;
            }
        }
        return false;
    }
}
