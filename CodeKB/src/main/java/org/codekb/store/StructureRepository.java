package org.codekb.store;

import org.codekb.model.ParsedSourceFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class StructureRepository {
    public static final class ResolvedMethodCall {
        private final Long calleeMethodId;
        private final String calleeOwner;
        private final String calleeMethodName;
        private final String calleeSignature;
        private final int lineNumber;

        public ResolvedMethodCall(Long calleeMethodId, String calleeOwner, String calleeMethodName, String calleeSignature, int lineNumber) {
            this.calleeMethodId = calleeMethodId;
            this.calleeOwner = calleeOwner;
            this.calleeMethodName = calleeMethodName;
            this.calleeSignature = calleeSignature;
            this.lineNumber = lineNumber;
        }

        public Long getCalleeMethodId() {
            return calleeMethodId;
        }

        public String getCalleeOwner() {
            return calleeOwner;
        }

        public String getCalleeMethodName() {
            return calleeMethodName;
        }

        public String getCalleeSignature() {
            return calleeSignature;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    public static final class ResolvedFieldAccess {
        private final Long fieldId;
        private final String ownerType;
        private final String fieldName;
        private final String accessKind;
        private final int lineNumber;

        public ResolvedFieldAccess(Long fieldId, String ownerType, String fieldName, String accessKind, int lineNumber) {
            this.fieldId = fieldId;
            this.ownerType = ownerType;
            this.fieldName = fieldName;
            this.accessKind = accessKind;
            this.lineNumber = lineNumber;
        }

        public Long getFieldId() {
            return fieldId;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getAccessKind() {
            return accessKind;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    public static final class CandidateImportRecord {
        private final Long methodId;
        private final String importValue;
        private final String sourceKind;
        private final String usageContext;
        private final int priority;

        public CandidateImportRecord(Long methodId, String importValue, String sourceKind, String usageContext, int priority) {
            this.methodId = methodId;
            this.importValue = importValue;
            this.sourceKind = sourceKind;
            this.usageContext = usageContext;
            this.priority = priority;
        }

        public Long getMethodId() {
            return methodId;
        }

        public String getImportValue() {
            return importValue;
        }

        public String getSourceKind() {
            return sourceKind;
        }

        public String getUsageContext() {
            return usageContext;
        }

        public int getPriority() {
            return priority;
        }
    }

    public long upsertFile(Connection connection, long variantId, Path absolutePath, String packageName) throws SQLException {
        String normalizedPath = normalizePath(absolutePath);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO files(variant_id, absolute_path, relative_path, package_name) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(variant_id, absolute_path) DO UPDATE SET relative_path = excluded.relative_path, " +
                "package_name = excluded.package_name")) {
            stmt.setLong(1, variantId);
            stmt.setString(2, normalizedPath);
            stmt.setString(3, absolutePath.getFileName() == null ? normalizedPath : absolutePath.getFileName().toString());
            stmt.setString(4, packageName);
            stmt.executeUpdate();
        }
        return queryId(connection, "SELECT id FROM files WHERE variant_id = ? AND absolute_path = ?", variantId, normalizedPath);
    }

    public long upsertType(Connection connection, long fileId, ParsedSourceFile.TypeInfo type) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO types(file_id, qualified_name, simple_name, kind, super_class, visibility, is_static, is_final, is_abstract) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(file_id, qualified_name) DO UPDATE SET simple_name = excluded.simple_name, kind = excluded.kind, " +
                "super_class = excluded.super_class, visibility = excluded.visibility, is_static = excluded.is_static, " +
                "is_final = excluded.is_final, is_abstract = excluded.is_abstract")) {
            stmt.setLong(1, fileId);
            stmt.setString(2, type.getQualifiedName());
            stmt.setString(3, type.getSimpleName());
            stmt.setString(4, type.getKind());
            stmt.setString(5, type.getSuperClass());
            stmt.setString(6, type.getVisibility());
            stmt.setInt(7, type.isStatic() ? 1 : 0);
            stmt.setInt(8, type.isFinal() ? 1 : 0);
            stmt.setInt(9, type.isAbstract() ? 1 : 0);
            stmt.executeUpdate();
        }
        return queryId(connection, "SELECT id FROM types WHERE file_id = ? AND qualified_name = ?", fileId, type.getQualifiedName());
    }

    public long upsertField(Connection connection, long typeId, ParsedSourceFile.FieldInfo field) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO fields(type_id, name, field_type, visibility, is_static, is_final, is_public) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(type_id, name) DO UPDATE SET field_type = excluded.field_type, visibility = excluded.visibility, " +
                "is_static = excluded.is_static, is_final = excluded.is_final, is_public = excluded.is_public")) {
            stmt.setLong(1, typeId);
            stmt.setString(2, field.getName());
            stmt.setString(3, field.getFieldType());
            stmt.setString(4, field.getVisibility());
            stmt.setInt(5, field.isStatic() ? 1 : 0);
            stmt.setInt(6, field.isFinal() ? 1 : 0);
            stmt.setInt(7, field.isPublic() ? 1 : 0);
            stmt.executeUpdate();
        }
        return queryId(connection, "SELECT id FROM fields WHERE type_id = ? AND name = ?", typeId, field.getName());
    }

    public long upsertMethod(Connection connection, long typeId, ParsedSourceFile.MethodInfo method) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO methods(type_id, name, signature, return_type, is_constructor, visibility, is_static, is_final, is_abstract, is_public, begin_line, end_line) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(type_id, signature) DO UPDATE SET name = excluded.name, return_type = excluded.return_type, " +
                "is_constructor = excluded.is_constructor, visibility = excluded.visibility, is_static = excluded.is_static, " +
                "is_final = excluded.is_final, is_abstract = excluded.is_abstract, is_public = excluded.is_public, " +
                "begin_line = excluded.begin_line, end_line = excluded.end_line")) {
            stmt.setLong(1, typeId);
            stmt.setString(2, method.getName());
            stmt.setString(3, method.getSignature());
            stmt.setString(4, method.getReturnType());
            stmt.setInt(5, method.isConstructor() ? 1 : 0);
            stmt.setString(6, method.getVisibility());
            stmt.setInt(7, method.isStatic() ? 1 : 0);
            stmt.setInt(8, method.isFinal() ? 1 : 0);
            stmt.setInt(9, method.isAbstract() ? 1 : 0);
            stmt.setInt(10, method.isPublic() ? 1 : 0);
            stmt.setInt(11, method.getBeginLine());
            stmt.setInt(12, method.getEndLine());
            stmt.executeUpdate();
        }
        return queryId(connection, "SELECT id FROM methods WHERE type_id = ? AND signature = ?", typeId, method.getSignature());
    }

    public void replaceMethodParameters(Connection connection, long methodId,
                                        java.util.List<ParsedSourceFile.ParameterInfo> parameters) throws SQLException {
        deleteByMethodId(connection, "DELETE FROM method_parameters WHERE method_id = ?", methodId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO method_parameters(method_id, position, name, param_type, is_varargs) VALUES (?, ?, ?, ?, ?)")) {
            for (ParsedSourceFile.ParameterInfo parameter : parameters) {
                stmt.setLong(1, methodId);
                stmt.setInt(2, parameter.getPosition());
                stmt.setString(3, parameter.getName());
                stmt.setString(4, parameter.getType());
                stmt.setInt(5, parameter.isVarArgs() ? 1 : 0);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceMethodThrows(Connection connection, long methodId,
                                    java.util.List<String> thrownExceptions) throws SQLException {
        deleteByMethodId(connection, "DELETE FROM method_throws WHERE method_id = ?", methodId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO method_throws(method_id, position, exception_type) VALUES (?, ?, ?)")) {
            for (int i = 0; i < thrownExceptions.size(); i++) {
                stmt.setLong(1, methodId);
                stmt.setInt(2, i);
                stmt.setString(3, thrownExceptions.get(i));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceTypeHierarchy(Connection connection, long childTypeId, ParsedSourceFile.TypeInfo type) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM type_hierarchy WHERE child_type_id = ?")) {
            delete.setLong(1, childTypeId);
            delete.executeUpdate();
        }
        if (type.getSuperClass() != null && !type.getSuperClass().trim().isEmpty()) {
            insertTypeHierarchy(connection, childTypeId, type.getSuperClass().trim(), "EXTENDS", 1);
        }
        for (String implemented : type.getImplementedTypes()) {
            if (implemented == null || implemented.trim().isEmpty()) {
                continue;
            }
            insertTypeHierarchy(connection, childTypeId, implemented.trim(), "IMPLEMENTS", 1);
        }
        if (type.getEnclosingQualifiedName() != null && !type.getEnclosingQualifiedName().trim().isEmpty()) {
            insertTypeHierarchy(connection, childTypeId, type.getEnclosingQualifiedName().trim(), "ENCLOSED_BY", 1);
        }
    }

    private void insertTypeHierarchy(Connection connection, long childTypeId, String parentQualifiedName,
                                     String relationKind, int distance) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO type_hierarchy(child_type_id, parent_type_id, parent_qualified_name, relation_kind, distance) " +
                "VALUES (?, NULL, ?, ?, ?)")) {
            stmt.setLong(1, childTypeId);
            stmt.setString(2, parentQualifiedName);
            stmt.setString(3, relationKind);
            stmt.setInt(4, distance);
            stmt.executeUpdate();
        }
    }

    public void replaceMethodCalls(Connection connection, long methodId, java.util.List<ResolvedMethodCall> calls) throws SQLException {
        deleteByMethodId(connection, "DELETE FROM method_calls WHERE caller_method_id = ?", methodId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO method_calls(caller_method_id, callee_method_id, callee_owner, callee_method_name, callee_signature, line_number) " +
                "VALUES (?, ?, ?, ?, ?, ?)")) {
            for (ResolvedMethodCall call : calls) {
                stmt.setLong(1, methodId);
                if (call.getCalleeMethodId() == null || call.getCalleeMethodId().longValue() == 0L) {
                    stmt.setNull(2, java.sql.Types.BIGINT);
                } else {
                    stmt.setLong(2, call.getCalleeMethodId().longValue());
                }
                stmt.setString(3, call.getCalleeOwner());
                stmt.setString(4, call.getCalleeMethodName());
                stmt.setString(5, call.getCalleeSignature());
                stmt.setInt(6, call.getLineNumber());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceFieldAccesses(Connection connection, long methodId, java.util.List<ResolvedFieldAccess> accesses) throws SQLException {
        deleteByMethodId(connection, "DELETE FROM field_accesses WHERE method_id = ?", methodId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO field_accesses(method_id, field_id, owner_type, field_name, access_kind, line_number) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (ResolvedFieldAccess access : accesses) {
                stmt.setLong(1, methodId);
                if (access.getFieldId() == null || access.getFieldId().longValue() == 0L) {
                    stmt.setNull(2, java.sql.Types.BIGINT);
                } else {
                    stmt.setLong(2, access.getFieldId().longValue());
                }
                stmt.setString(3, access.getOwnerType());
                stmt.setString(4, access.getFieldName());
                stmt.setString(5, access.getAccessKind());
                stmt.setInt(6, access.getLineNumber());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceFileCandidateImports(Connection connection, long fileId, java.util.List<String> imports) throws SQLException {
        try (PreparedStatement deleteStmt = connection.prepareStatement(
            "DELETE FROM import_usage_links WHERE file_id = ? AND method_id IS NULL")) {
            deleteStmt.setLong(1, fileId);
            deleteStmt.executeUpdate();
        }
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT OR IGNORE INTO import_usage_links(file_id, method_id, import_fact_id, usage_context, priority) " +
                "VALUES (?, NULL, ?, ?, ?)")) {
            for (String importValue : imports) {
                long importFactId = upsertImportFact(connection, importValue, "SOURCE_IMPORT");
                stmt.setLong(1, fileId);
                stmt.setLong(2, importFactId);
                stmt.setString(3, "explicit import in source file");
                stmt.setInt(4, 40);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceMethodCandidateImports(Connection connection, long fileId, long methodId,
                                              java.util.List<ParsedSourceFile.ImportHintInfo> imports) throws SQLException {
        deleteByMethodId(connection, "DELETE FROM import_usage_links WHERE method_id = ?", methodId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT OR IGNORE INTO import_usage_links(file_id, method_id, import_fact_id, usage_context, priority) " +
                "VALUES (?, ?, ?, ?, ?)")) {
            for (ParsedSourceFile.ImportHintInfo importHint : imports) {
                long importFactId = upsertImportFact(connection, importHint.getImportValue(), importHint.getSourceKind());
                stmt.setLong(1, fileId);
                stmt.setLong(2, methodId);
                stmt.setLong(3, importFactId);
                stmt.setString(4, importHint.getUsageContext());
                stmt.setInt(5, importHint.getPriority());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private long upsertImportFact(Connection connection, String importValue, String sourceKind) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO import_facts(import_value, source_kind) VALUES (?, ?) " +
                "ON CONFLICT(import_value, source_kind) DO NOTHING")) {
            stmt.setString(1, importValue);
            stmt.setString(2, sourceKind);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT id FROM import_facts WHERE import_value = ? AND source_kind = ?")) {
            stmt.setString(1, importValue);
            stmt.setString(2, sourceKind);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve import fact id for: " + importValue + " / " + sourceKind);
    }

    public long findMethodId(Connection connection, long variantId, String qualifiedTypeName, String signature) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id " +
                "FROM methods m " +
                "JOIN types t ON m.type_id = t.id " +
                "JOIN files f ON t.file_id = f.id " +
                "WHERE f.variant_id = ? AND t.qualified_name = ? AND m.signature = ?")) {
            stmt.setLong(1, variantId);
            stmt.setString(2, qualifiedTypeName);
            stmt.setString(3, signature);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public long findConstructorIdByOwnerAndArity(Connection connection, long variantId, String ownerType, int arity)
        throws SQLException {
        if (ownerType == null || ownerType.trim().isEmpty() || arity < 0) {
            return 0L;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id " +
                "FROM methods m " +
                "JOIN types t ON m.type_id = t.id " +
                "JOIN files f ON t.file_id = f.id " +
                "LEFT JOIN method_parameters p ON p.method_id = m.id " +
                "WHERE f.variant_id = ? AND m.is_constructor = 1 " +
                "AND (t.qualified_name = ? OR t.simple_name = ?) " +
                "GROUP BY m.id, t.qualified_name, t.simple_name " +
                "HAVING COUNT(p.id) = ? " +
                "ORDER BY CASE WHEN t.qualified_name = ? THEN 0 ELSE 1 END, m.id " +
                "LIMIT 1")) {
            stmt.setLong(1, variantId);
            stmt.setString(2, ownerType);
            stmt.setString(3, simpleType(ownerType));
            stmt.setInt(4, arity);
            stmt.setString(5, ownerType);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    public long findMethodIdByNameAndLine(Connection connection, long variantId, String simpleTypeName, String methodName, int lineNumber)
        throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id " +
                "FROM methods m " +
                "JOIN types t ON m.type_id = t.id " +
                "JOIN files f ON t.file_id = f.id " +
                "WHERE f.variant_id = ? AND t.simple_name = ? AND m.name = ? AND ? BETWEEN m.begin_line AND m.end_line " +
                "ORDER BY (m.end_line - m.begin_line) ASC LIMIT 1")) {
            stmt.setLong(1, variantId);
            stmt.setString(2, simpleTypeName);
            stmt.setString(3, methodName);
            stmt.setInt(4, lineNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private static String simpleType(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        int generic = text.indexOf('<');
        if (generic >= 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    public long findFieldId(Connection connection, long variantId, String qualifiedTypeName, String fieldName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT fld.id " +
                "FROM fields fld " +
                "JOIN types t ON fld.type_id = t.id " +
                "JOIN files f ON t.file_id = f.id " +
                "WHERE f.variant_id = ? AND t.qualified_name = ? AND fld.name = ?")) {
            stmt.setLong(1, variantId);
            stmt.setString(2, qualifiedTypeName);
            stmt.setString(3, fieldName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private long queryId(Connection connection, String sql, long leftId, String rightValue) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, leftId);
            stmt.setString(2, rightValue);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to resolve id for query: " + sql);
    }

    private void deleteByMethodId(Connection connection, String sql, long methodId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, methodId);
            stmt.executeUpdate();
        }
    }

    private String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
