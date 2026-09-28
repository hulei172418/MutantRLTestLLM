package org.codekb.store;

import org.codekb.config.KbConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class SqliteConnectionFactory {
    public Connection open(KbConfig config) throws SQLException, IOException {
        if (config.getDbPath().getParent() != null) {
            Files.createDirectories(config.getDbPath().getParent());
        }
        String jdbcUrl = "jdbc:sqlite:" + config.getDbPath().toAbsolutePath()
            + "?journal_mode=OFF&synchronous=OFF&temp_store=MEMORY";
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA journal_mode = OFF");
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA synchronous = OFF");
        }
        return connection;
    }
}
