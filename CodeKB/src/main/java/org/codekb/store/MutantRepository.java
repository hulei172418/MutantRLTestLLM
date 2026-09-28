package org.codekb.store;

import org.codekb.model.MutantSeed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class MutantRepository {
    public long upsertMutant(Connection connection, long projectId, MutantSeed seed) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO mutants(" +
                "project_id, mutant_id, operator, line_number, method_name, class_name, class_f, package_name, " +
                "mutation_statement" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(project_id, mutant_id) DO UPDATE SET " +
                "operator = excluded.operator, " +
                "line_number = excluded.line_number, " +
                "method_name = excluded.method_name, " +
                "class_name = excluded.class_name, " +
                "class_f = excluded.class_f, " +
                "package_name = excluded.package_name, " +
                "mutation_statement = excluded.mutation_statement")) {
            stmt.setLong(1, projectId);
            stmt.setString(2, seed.getMutantId());
            stmt.setString(3, seed.getOperator());
            stmt.setInt(4, seed.getLine());
            stmt.setString(5, seed.getMethod());
            stmt.setString(6, seed.getClassName());
            stmt.setString(7, seed.getClassF());
            stmt.setString(8, seed.getPackageName());
            stmt.setString(9, seed.getMutationStatement());
            stmt.executeUpdate();
        }
        long rowId = 0L;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM mutants WHERE project_id = ? AND mutant_id = ?")) {
            query.setLong(1, projectId);
            query.setString(2, seed.getMutantId());
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    rowId = rs.getLong(1);
                }
            }
        }
        if (rowId == 0L) {
            throw new SQLException("Unable to resolve mutant row for " + seed.getMutantId());
        }
        upsertMutantArtifacts(connection, rowId, seed);
        return rowId;
    }

    public void upsertMutantArtifacts(Connection connection, long mutantRowId, MutantSeed seed) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO mutant_artifacts(mutant_row_id, file_path, original_graph_path, mutant_graph_path, is_killed) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(mutant_row_id) DO UPDATE SET " +
                "file_path = excluded.file_path, " +
                "original_graph_path = excluded.original_graph_path, " +
                "mutant_graph_path = excluded.mutant_graph_path, " +
                "is_killed = excluded.is_killed")) {
            stmt.setLong(1, mutantRowId);
            stmt.setString(2, seed.getFilePath());
            stmt.setString(3, seed.getOriginalGraphPath());
            stmt.setString(4, seed.getMutantGraphPath());
            stmt.setString(5, seed.getIsKilled());
            stmt.executeUpdate();
        }
    }

    public void upsertMutantFileLinks(Connection connection, long mutantRowId, long originalFileId, long mutantFileId)
        throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO mutant_file_links(mutant_row_id, original_file_id, mutant_file_id) VALUES (?, ?, ?) " +
                "ON CONFLICT(mutant_row_id) DO UPDATE SET original_file_id = excluded.original_file_id, " +
                "mutant_file_id = excluded.mutant_file_id")) {
            stmt.setLong(1, mutantRowId);
            stmt.setLong(2, originalFileId);
            stmt.setLong(3, mutantFileId);
            stmt.executeUpdate();
        }
    }

    public void upsertMutantMethodLinks(Connection connection, long mutantRowId, long originalMethodId, long mutantMethodId)
        throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO mutant_method_links(mutant_row_id, original_method_id, mutant_method_id) VALUES (?, ?, ?) " +
                "ON CONFLICT(mutant_row_id) DO UPDATE SET original_method_id = excluded.original_method_id, " +
                "mutant_method_id = excluded.mutant_method_id")) {
            stmt.setLong(1, mutantRowId);
            stmt.setLong(2, originalMethodId);
            stmt.setLong(3, mutantMethodId);
            stmt.executeUpdate();
        }
    }
}
