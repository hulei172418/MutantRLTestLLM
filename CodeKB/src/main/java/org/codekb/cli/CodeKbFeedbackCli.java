package org.codekb.cli;

import org.codekb.config.KbConfig;
import org.codekb.feedback.KbFeedbackIngestService;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class CodeKbFeedbackCli {
    private CodeKbFeedbackCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: CodeKbFeedbackCli <workspaceRoot> <feedbackJsonl> [--db relative/path] [--accept-all false] [--min-confidence 0.85] [--auto-repairable-only true]");
            return;
        }
        Path workspaceRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        Path feedbackJsonl = workspaceRoot.resolve(Paths.get(args[1])).normalize();
        Path dbPath = null;
        boolean acceptAll = true;
        double minConfidence = 0.0d;
        boolean autoRepairableOnly = false;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if ("--db".equalsIgnoreCase(arg) && i + 1 < args.length) {
                dbPath = workspaceRoot.resolve(Paths.get(args[++i])).normalize();
            } else if ("--accept-all".equalsIgnoreCase(arg) && i + 1 < args.length) {
                acceptAll = Boolean.parseBoolean(args[++i]);
            } else if ("--min-confidence".equalsIgnoreCase(arg) && i + 1 < args.length) {
                minConfidence = Double.parseDouble(args[++i]);
            } else if ("--auto-repairable-only".equalsIgnoreCase(arg) && i + 1 < args.length) {
                autoRepairableOnly = Boolean.parseBoolean(args[++i]);
            }
        }
        KbConfig defaults = KbConfig.defaults(workspaceRoot);
        KbConfig config = new KbConfig(
            defaults.getWorkspaceRoot(),
            dbPath == null ? defaults.getDbPath() : dbPath,
            defaults.getExcelPath(),
            false
        );
        KbFeedbackIngestService.Summary summary = new KbFeedbackIngestService()
            .ingest(config, feedbackJsonl, acceptAll, minConfidence, autoRepairableOnly);
        System.out.println("CodeKB feedback imported");
        System.out.println("db=" + config.getDbPath());
        System.out.println("feedbackJsonl=" + feedbackJsonl);
        System.out.println("events=" + summary.getEventCount());
        System.out.println("candidates=" + summary.getCandidateCount());
        System.out.println("skipped_non_codekb=" + summary.getSkippedNonCodeKb());
        System.out.println("skipped_low_confidence=" + summary.getSkippedLowConfidence());
    }
}
