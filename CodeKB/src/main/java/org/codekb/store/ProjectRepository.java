package org.codekb.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ProjectRepository {
    public long upsertProject(Connection connection, String projectName) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO projects(name) VALUES (?) ON CONFLICT(name) DO NOTHING")) {
            insert.setString(1, projectName);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM projects WHERE name = ?")) {
            query.setString(1, projectName);
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve project id for " + projectName);
    }

    public long upsertVariant(Connection connection, long projectId, String variantKind, String variantName, String sourcePath)
        throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO program_variants(project_id, variant_kind, variant_name, source_path) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(project_id, variant_kind, variant_name) DO UPDATE SET source_path = excluded.source_path")) {
            insert.setLong(1, projectId);
            insert.setString(2, variantKind);
            insert.setString(3, variantName);
            insert.setString(4, sourcePath);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM program_variants WHERE project_id = ? AND variant_kind = ? AND variant_name = ?")) {
            query.setLong(1, projectId);
            query.setString(2, variantKind);
            query.setString(3, variantName);
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve variant id for " + variantKind + ":" + variantName);
    }

    public long upsertModule(Connection connection, long projectId, String moduleName, String moduleRoot, String buildSystem)
        throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO modules(project_id, module_name, module_root, build_system) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(project_id, module_root) DO UPDATE SET module_name = excluded.module_name, " +
                "build_system = excluded.build_system")) {
            insert.setLong(1, projectId);
            insert.setString(2, moduleName);
            insert.setString(3, moduleRoot);
            insert.setString(4, buildSystem);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM modules WHERE project_id = ? AND module_root = ?")) {
            query.setLong(1, projectId);
            query.setString(2, moduleRoot);
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve module id for " + moduleRoot);
    }

    public long upsertSourceRoot(Connection connection, long projectId, Long moduleId, String rootPath, String rootKind)
        throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO source_roots(project_id, module_id, root_path, root_kind) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(project_id, root_path) DO UPDATE SET module_id = excluded.module_id, " +
                "root_kind = excluded.root_kind")) {
            insert.setLong(1, projectId);
            if (moduleId == null || moduleId.longValue() == 0L) {
                insert.setNull(2, java.sql.Types.BIGINT);
            } else {
                insert.setLong(2, moduleId.longValue());
            }
            insert.setString(3, rootPath);
            insert.setString(4, rootKind);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM source_roots WHERE project_id = ? AND root_path = ?")) {
            query.setLong(1, projectId);
            query.setString(2, rootPath);
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve source root id for " + rootPath);
    }
}
