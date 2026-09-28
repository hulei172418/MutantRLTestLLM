package org.codekb.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class KbConfig {
    private final Path workspaceRoot;
    private final Path dbPath;
    private final Path excelPath;
    private final boolean rebuildIfExists;

    public KbConfig(Path workspaceRoot, Path dbPath, Path excelPath, boolean rebuildIfExists) {
        this.workspaceRoot = workspaceRoot;
        this.dbPath = dbPath;
        this.excelPath = excelPath;
        this.rebuildIfExists = rebuildIfExists;
    }

    public static KbConfig defaults(Path workspaceRoot) {
        return new KbConfig(
            workspaceRoot,
            workspaceRoot.resolve(Paths.get("data", "kb", "codekb.sqlite")),
            workspaceRoot.resolve(Paths.get("data", "trajectory-not-killed", "commons-csv-1.2-suite-not-killed-plain-1.xlsx")),
            false
        );
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path getDbPath() {
        return dbPath;
    }

    public Path getExcelPath() {
        return excelPath;
    }

    public boolean isRebuildIfExists() {
        return rebuildIfExists;
    }
}
