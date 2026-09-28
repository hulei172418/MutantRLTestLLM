package org.codekb.cli;

import org.codekb.config.KbConfig;
import org.codekb.service.KbStatsService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class CodeKbStatsCli {
    private CodeKbStatsCli() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = args.length > 0 ? Paths.get(args[0]) : Paths.get("").toAbsolutePath();
        KbConfig config = KbConfig.defaults(workspaceRoot);
        KbStatsService.Stats stats = new KbStatsService().collect(config);
        print(stats);
    }

    private static void print(KbStatsService.Stats stats) {
        System.out.println("db=" + stats.getDbPath());
        System.out.println("db_bytes=" + stats.getDbBytes());
        System.out.println("db_mb=" + toMb(stats.getDbBytes()));
        System.out.println("page_count=" + stats.getPageCount());
        System.out.println("page_size=" + stats.getPageSize());
        System.out.println("freelist_count=" + stats.getFreelistCount());
        for (Map.Entry<String, Integer> entry : stats.getRowCounts().entrySet()) {
            System.out.println(entry.getKey() + "=" + entry.getValue());
        }
    }

    private static String toMb(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(java.util.Locale.ROOT, "%.3f", mb);
    }
}
