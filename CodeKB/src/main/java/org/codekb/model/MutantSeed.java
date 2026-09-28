package org.codekb.model;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class MutantSeed {
    private final String operator;
    private final int line;
    private final String method;
    private final String className;
    private final String classF;
    private final String mutationStatement;
    private final String packageName;
    private final String project;
    private final String filePath;
    private final String originalGraphPath;
    private final String mutantGraphPath;
    private final String isKilled;

    public MutantSeed(
        String operator,
        int line,
        String method,
        String className,
        String classF,
        String mutationStatement,
        String packageName,
        String project,
        String filePath,
        String originalGraphPath,
        String mutantGraphPath,
        String isKilled
    ) {
        this.operator = operator;
        this.line = line;
        this.method = method;
        this.className = className;
        this.classF = classF;
        this.mutationStatement = mutationStatement;
        this.packageName = packageName;
        this.project = project;
        this.filePath = filePath;
        this.originalGraphPath = originalGraphPath;
        this.mutantGraphPath = mutantGraphPath;
        this.isKilled = isKilled;
    }

    public String getOperator() {
        return operator;
    }

    public int getLine() {
        return line;
    }

    public String getMethod() {
        return method;
    }

    public String getMethodSimpleName() {
        if (method == null || method.trim().isEmpty()) {
            return "";
        }
        String raw = method.trim();
        int paren = raw.indexOf('(');
        String head = paren >= 0 ? raw.substring(0, paren) : raw;
        int underscore = head.lastIndexOf('_');
        if (underscore >= 0 && underscore + 1 < head.length()) {
            return head.substring(underscore + 1);
        }
        int dot = head.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < head.length()) {
            return head.substring(dot + 1);
        }
        return head;
    }

    public String getClassName() {
        return className;
    }

    public String getClassF() {
        return classF;
    }

    public String getMutationStatement() {
        return mutationStatement;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getProject() {
        return project;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getOriginalGraphPath() {
        return originalGraphPath;
    }

    public String getMutantGraphPath() {
        return mutantGraphPath;
    }

    public String getIsKilled() {
        return isKilled;
    }

    public String getMutantId() {
        return CanonicalMutantId.build(
            getProject(),
            preferredTargetClass(),
            getMethod(),
            getLegacyMutantName()
        );
    }

    public String getLegacyMutantName() {
        return CanonicalMutantId.legacyMutantName(mutantGraphPath);
    }

    public Path resolveOriginalJavaPath() {
        return resolveJavaPath(originalGraphPath, className);
    }

    public Path resolveMutantJavaPath() {
        return resolveJavaPath(mutantGraphPath, className);
    }

    private static Path resolveJavaPath(String graphPath, String simpleClassName) {
        if (graphPath == null || graphPath.trim().isEmpty()) {
            return Paths.get("");
        }
        Path raw = Paths.get(graphPath.replace("\\\\?\\", ""));
        Path baseDir = raw;
        if (graphPath.endsWith(".json")) {
            baseDir = raw.getParent();
        }
        if (baseDir != null && baseDir.getFileName() != null && "graph".equalsIgnoreCase(baseDir.getFileName().toString())) {
            baseDir = baseDir.getParent();
        }
        if (baseDir == null) {
            return Paths.get("");
        }
        return baseDir.resolve(simpleClassName + ".java");
    }

    private String preferredTargetClass() {
        if (packageName != null && !packageName.trim().isEmpty()) {
            return packageName.trim();
        }
        if (classF != null && !classF.trim().isEmpty()) {
            return classF.trim();
        }
        return className == null ? "" : className.trim();
    }
}
