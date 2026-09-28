package org.codekb.service;

import org.codekb.model.ProjectBuildSeed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public final class ProjectSourceScanner {
    private static final String[] EXCLUDED_DIR_NAMES = new String[] {
        "test", "tests", "testdata", "test-data", "evosuite", "llm"
    };

    public List<Path> scanJavaFiles(ProjectBuildSeed seed) throws IOException {
        if (seed == null || seed.getSourceRoots().isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> out = new ArrayList<Path>();
        for (Path sourceRoot : seed.getSourceRoots()) {
            if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !isUnderExcludedDirectory(sourceRoot, path))
                    .forEach(path -> out.add(path.toAbsolutePath().normalize()));
            }
        }
        Collections.sort(out);
        return out;
    }

    private boolean isUnderExcludedDirectory(Path sourceRoot, Path javaFile) {
        Path relative;
        try {
            relative = sourceRoot.toAbsolutePath().normalize().relativize(javaFile.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        for (Path segment : relative) {
            String lowered = segment.toString().toLowerCase();
            for (String excluded : EXCLUDED_DIR_NAMES) {
                if (excluded.equals(lowered)) {
                    return true;
                }
            }
        }
        return false;
    }
}
