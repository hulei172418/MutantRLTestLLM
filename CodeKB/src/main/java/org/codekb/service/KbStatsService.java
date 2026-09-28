package org.codekb.service;

import org.codekb.config.KbConfig;
import org.codekb.store.SqliteConnectionFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KbStatsService {
    private static final String[] TABLES = new String[] {
        "projects",
        "program_variants",
        "mutants",
        "mutant_artifacts",
        "files",
        "types",
        "methods",
        "fields",
        "method_calls",
        "field_accesses",
        "import_facts",
        "import_usage_links",
        "candidate_imports",
        "mutant_file_links",
        "mutant_method_links",
        "equivalent_suspicions",
        "schema_comments"
    };

    private final SqliteConnectionFactory connectionFactory;

    public KbStatsService() {
        this.connectionFactory = new SqliteConnectionFactory();
    }

    public Stats collect(KbConfig config) throws SQLException, IOException {
        long dbBytes = Files.exists(config.getDbPath()) ? Files.size(config.getDbPath()) : 0L;
        Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
        long pageCount = 0L;
        long pageSize = 0L;
        long freelistCount = 0L;
        try (Connection connection = connectionFactory.open(config)) {
            for (String table : TABLES) {
                rowCounts.put(table, Integer.valueOf(countRows(connection, table)));
            }
            pageCount = pragmaLong(connection, "page_count");
            pageSize = pragmaLong(connection, "page_size");
            freelistCount = pragmaLong(connection, "freelist_count");
        }
        return new Stats(config.getDbPath().toString(), dbBytes, pageCount, pageSize, freelistCount, rowCounts);
    }

    private int countRows(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private long pragmaLong(Connection connection, String pragmaName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("PRAGMA " + pragmaName);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public static final class Stats {
        private final String dbPath;
        private final long dbBytes;
        private final long pageCount;
        private final long pageSize;
        private final long freelistCount;
        private final Map<String, Integer> rowCounts;

        public Stats(String dbPath,
                     long dbBytes,
                     long pageCount,
                     long pageSize,
                     long freelistCount,
                     Map<String, Integer> rowCounts) {
            this.dbPath = dbPath;
            this.dbBytes = dbBytes;
            this.pageCount = pageCount;
            this.pageSize = pageSize;
            this.freelistCount = freelistCount;
            this.rowCounts = rowCounts;
        }

        public String getDbPath() {
            return dbPath;
        }

        public long getDbBytes() {
            return dbBytes;
        }

        public long getPageCount() {
            return pageCount;
        }

        public long getPageSize() {
            return pageSize;
        }

        public long getFreelistCount() {
            return freelistCount;
        }

        public Map<String, Integer> getRowCounts() {
            return rowCounts;
        }
    }
}
