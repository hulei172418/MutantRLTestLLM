package org.codekb.query;

import org.codekb.config.KbConfig;
import org.codekb.feedback.KbFeedbackRepository;
import org.codekb.model.CanonicalMutantId;
import org.codekb.model.MutantContextView;
import org.codekb.store.SqliteConnectionFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

public final class KnowledgeQueryService {
    private final SqliteConnectionFactory connectionFactory;
    private final KbFeedbackRepository feedbackRepository;

    public KnowledgeQueryService() {
        this.connectionFactory = new SqliteConnectionFactory();
        this.feedbackRepository = new KbFeedbackRepository();
    }

    public MutantContextView loadMutantContext(KbConfig config, String mutantId) throws SQLException, IOException {
        try (Connection connection = connectionFactory.open(config)) {
            MutantRow mutant = findMutant(connection, mutantId);
            if (mutant == null) {
                return null;
            }
            MutantContextView.FileLink originalFile = findFileLink(connection, mutant.rowId, true);
            MutantContextView.FileLink mutantFile = findFileLink(connection, mutant.rowId, false);
            MutantContextView.MethodLink originalMethod = findMethodLink(connection, mutant.rowId, true);
            MutantContextView.MethodLink mutantMethod = findMethodLink(connection, mutant.rowId, false);
            List<MutantContextView.MethodCallView> methodCalls = mutantMethod == null
                ? new ArrayList<MutantContextView.MethodCallView>()
                : findMethodCalls(connection, mutantMethod.getMethodId());
            List<MutantContextView.FieldAccessView> fieldAccesses = mutantMethod == null
                ? new ArrayList<MutantContextView.FieldAccessView>()
                : findFieldAccesses(connection, mutantMethod.getMethodId());
            List<MutantContextView.FieldFactView> affectedFields = mutantMethod == null
                ? new ArrayList<MutantContextView.FieldFactView>()
                : findAffectedFields(connection, mutantMethod.getMethodId());
            List<MutantContextView.MethodLink> entries = originalMethod == null
                ? new ArrayList<MutantContextView.MethodLink>()
                : findCandidateEntries(connection, originalMethod.getTypeId(), originalMethod.getMethodId(),
                    originalMethod.getMethodName(), affectedFields);
            MutantContextView.TypeView ownerType = originalMethod == null
                ? null
                : findTypeView(connection, originalMethod.getTypeId());
            List<MutantContextView.MethodFactView> ownerMethods = originalMethod == null
                ? new ArrayList<MutantContextView.MethodFactView>()
                : findOwnerMethods(connection, originalMethod.getTypeId());
            List<MutantContextView.FieldFactView> ownerFields = originalMethod == null
                ? new ArrayList<MutantContextView.FieldFactView>()
                : findOwnerFields(connection, originalMethod.getTypeId());
            List<MutantContextView.FieldPropagationView> fieldPropagationCandidates = originalMethod == null
                ? new ArrayList<MutantContextView.FieldPropagationView>()
                : findFieldPropagationCandidates(connection, originalMethod.getTypeId(), originalMethod.getMethodId(),
                    affectedFields);
            List<MutantContextView.MethodLink> persistedEntries = findPersistedEntryCandidates(connection, mutant.rowId);
            if (!persistedEntries.isEmpty()) {
                entries = persistedEntries;
            }
            List<MutantContextView.FieldObserverLinkView> fieldObserverLinks = findFieldObserverLinks(connection, mutant.rowId);
            List<MutantContextView.ObservableCandidateView> observableCandidates = findObservableCandidates(connection, mutant.rowId);
            if (observableCandidates.isEmpty()) {
                observableCandidates = deriveObservableCandidatesFallback(originalMethod, fieldPropagationCandidates);
            }
            List<MutantContextView.WitnessCandidateView> witnessCandidates = findWitnessCandidates(connection, mutant.rowId);
            if (witnessCandidates.isEmpty()) {
                witnessCandidates = deriveWitnessFallback(entries, observableCandidates);
            }
            List<MutantContextView.CandidateImportView> candidateImports = findCandidateImports(
                connection,
                mutant.mutantId,
                originalFile,
                originalMethod,
                entries
            );
            return new MutantContextView(
                new MutantContextView.MutantInfo(
                    mutant.rowId,
                    mutant.mutantId,
                    mutant.project,
                    mutant.operator,
                    mutant.lineNumber,
                    mutant.className,
                    mutant.methodName,
                    mutant.mutationStatement
                ),
                originalFile,
                mutantFile,
                originalMethod,
                mutantMethod,
                methodCalls,
                fieldAccesses,
                entries,
                candidateImports,
                ownerType,
                ownerMethods,
                ownerFields,
                affectedFields,
                fieldPropagationCandidates,
                observableCandidates,
                fieldObserverLinks,
                witnessCandidates
            );
        }
    }

    public Long findMutantRowId(Connection connection, String mutantId) throws SQLException {
        MutantRow mutant = findMutant(connection, mutantId);
        return mutant == null ? null : Long.valueOf(mutant.rowId);
    }

    private MutantRow findMutant(Connection connection, String mutantId) throws SQLException {
        String normalizedId = CanonicalMutantId.normalize(mutantId);
        MutantRow exact = findMutantByExactId(connection, normalizedId);
        if (exact != null) {
            return exact;
        }
        String legacyMutantName = CanonicalMutantId.legacyMutantName(normalizedId);
        if (legacyMutantName.isEmpty() || legacyMutantName.equals(normalizedId)) {
            return null;
        }
        String project = CanonicalMutantId.projectFromNormalized(normalizedId);
        String targetClass = CanonicalMutantId.targetClassFromNormalized(normalizedId);
        String methodSignature = CanonicalMutantId.methodSignatureFromNormalized(normalizedId);
        MutantRow byScopedLegacy = findMutantByLegacyScope(connection, project, targetClass, methodSignature, legacyMutantName);
        if (byScopedLegacy != null) {
            return byScopedLegacy;
        }
        return findMutantByExactId(connection, legacyMutantName);
    }

    private MutantRow findMutantByExactId(Connection connection, String mutantId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, m.mutant_id, p.name, m.operator, m.line_number, m.class_name, m.method_name, m.mutation_statement " +
                "FROM mutants m JOIN projects p ON m.project_id = p.id WHERE m.mutant_id = ?")) {
            stmt.setString(1, mutantId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MutantRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8)
                    );
                }
            }
        }
        return null;
    }

    private MutantRow findMutantByLegacyScope(Connection connection,
                                              String project,
                                              String targetClass,
                                              String methodSignature,
                                              String legacyMutantName) throws SQLException {
        if (project.isEmpty() || targetClass.isEmpty() || methodSignature.isEmpty() || legacyMutantName.isEmpty()) {
            return null;
        }
        String simpleTargetClass = simpleName(targetClass);
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, m.mutant_id, p.name, m.operator, m.line_number, m.class_name, m.method_name, m.mutation_statement " +
                "FROM mutants m JOIN projects p ON m.project_id = p.id " +
                "WHERE p.name = ? AND (m.class_name = ? OR m.class_name = ?) AND m.method_name = ? " +
                "AND (m.mutant_id = ? OR m.operator = ?)")) {
            stmt.setString(1, project);
            stmt.setString(2, targetClass);
            stmt.setString(3, simpleTargetClass);
            stmt.setString(4, methodSignature);
            stmt.setString(5, legacyMutantName);
            stmt.setString(6, legacyMutantName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MutantRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8)
                    );
                }
            }
        }
        return null;
    }

    private static String simpleName(String className) {
        String value = className == null ? "" : className.trim();
        if (value.isEmpty()) {
            return "";
        }
        int dollar = value.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < value.length()) {
            value = value.substring(dollar + 1);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length() ? value.substring(dot + 1) : value;
    }

    private MutantContextView.FileLink findFileLink(Connection connection, long mutantRowId, boolean original) throws SQLException {
        String column = original ? "original_file_id" : "mutant_file_id";
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT f.id, f.absolute_path, f.package_name " +
                "FROM mutant_file_links l JOIN files f ON l." + column + " = f.id WHERE l.mutant_row_id = ?")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MutantContextView.FileLink(rs.getLong(1), rs.getString(2), rs.getString(3));
                }
            }
        }
        return null;
    }

    private MutantContextView.MethodLink findMethodLink(Connection connection, long mutantRowId, boolean original) throws SQLException {
        String column = original ? "original_method_id" : "mutant_method_id";
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, t.id, t.qualified_name, m.name, m.signature, m.return_type, m.visibility, " +
                "m.is_static, m.is_final, m.is_abstract, m.is_constructor, m.is_public, m.begin_line, m.end_line " +
                "FROM mutant_method_links l " +
                "JOIN methods m ON l." + column + " = m.id " +
                "JOIN types t ON m.type_id = t.id WHERE l.mutant_row_id = ?")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MutantContextView.MethodLink(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getInt(8) != 0,
                        rs.getInt(9) != 0,
                        rs.getInt(10) != 0,
                        rs.getInt(11) != 0,
                        rs.getInt(12) != 0,
                        rs.getInt(13),
                        rs.getInt(14),
                        1.0,
                        original ? "original mutated method binding" : "mutant mutated method binding"
                    );
                }
            }
        }
        return null;
    }

    private List<MutantContextView.MethodCallView> findMethodCalls(Connection connection, long methodId) throws SQLException {
        List<MutantContextView.MethodCallView> calls = new ArrayList<MutantContextView.MethodCallView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT COALESCE(t.qualified_name, c.callee_owner), " +
                "COALESCE(m.name, c.callee_method_name), " +
                "COALESCE(m.signature, c.callee_signature), " +
                "c.line_number " +
                "FROM method_calls c " +
                "LEFT JOIN methods m ON c.callee_method_id = m.id " +
                "LEFT JOIN types t ON m.type_id = t.id " +
                "WHERE c.caller_method_id = ? ORDER BY c.line_number, c.id")) {
            stmt.setLong(1, methodId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    calls.add(new MutantContextView.MethodCallView(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4)
                    ));
                }
            }
        }
        return calls;
    }

    private List<MutantContextView.FieldAccessView> findFieldAccesses(Connection connection, long methodId) throws SQLException {
        List<MutantContextView.FieldAccessView> accesses = new ArrayList<MutantContextView.FieldAccessView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT COALESCE(t.qualified_name, a.owner_type), " +
                "COALESCE(f.name, a.field_name), " +
                "a.access_kind, " +
                "a.line_number " +
                "FROM field_accesses a " +
                "LEFT JOIN fields f ON a.field_id = f.id " +
                "LEFT JOIN types t ON f.type_id = t.id " +
                "WHERE a.method_id = ? ORDER BY a.line_number, a.id")) {
            stmt.setLong(1, methodId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    accesses.add(new MutantContextView.FieldAccessView(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4)
                    ));
                }
            }
        }
        return accesses;
    }

    private List<MutantContextView.MethodLink> findCandidateEntries(
        Connection connection, long typeId, long excludeMethodId, String targetMethodName,
        List<MutantContextView.FieldFactView> affectedFields)
        throws SQLException {
        List<MutantContextView.MethodLink> methods = new ArrayList<MutantContextView.MethodLink>();
        Set<Long> directCallers = findDirectCallers(connection, typeId, targetMethodName, excludeMethodId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, t.id, t.qualified_name, m.name, m.signature, m.return_type, m.visibility, " +
                "m.is_static, m.is_final, m.is_abstract, m.is_constructor, m.is_public, m.begin_line, m.end_line " +
                "FROM methods m JOIN types t ON m.type_id = t.id " +
                "WHERE t.id = ? AND m.id <> ? AND m.visibility <> 'private' " +
                "ORDER BY m.begin_line, m.id")) {
            stmt.setLong(1, typeId);
            stmt.setLong(2, excludeMethodId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long methodId = rs.getLong(1);
                    String methodName = rs.getString(4);
                    String signature = rs.getString(5);
                    double score = visibilityScore(rs.getString(7));
                    List<String> reasons = new ArrayList<String>();
                    reasons.add(rs.getString(7) + " method in same class");
                    if (directCallers.contains(methodId)) {
                        score += 120.0;
                        reasons.add("directly calls mutated method");
                    }
                    if (rs.getInt(11) != 0) {
                        score += 30.0;
                        reasons.add("constructor can shape receiver state before the mutation method");
                    } else if ("parse".equals(methodName)) {
                        score += 25.0;
                        reasons.add("parse-like front door may reach the mutation method");
                    }
                    methods.add(new MutantContextView.MethodLink(
                        methodId,
                        rs.getLong(2),
                        rs.getString(3),
                        methodName,
                        signature,
                        rs.getString(6),
                        rs.getString(7),
                        rs.getInt(8) != 0,
                        rs.getInt(9) != 0,
                        rs.getInt(10) != 0,
                        rs.getInt(11) != 0,
                        rs.getInt(12) != 0,
                        rs.getInt(13),
                        rs.getInt(14),
                        score,
                        joinReasons(reasons)
                    ));
                }
            }
        }
        methods.sort((a, b) -> {
            int byScore = Double.compare(b.getScore(), a.getScore());
            if (byScore != 0) {
                return byScore;
            }
            int byLine = Integer.compare(a.getBeginLine(), b.getBeginLine());
            if (byLine != 0) {
                return byLine;
            }
            return Long.compare(a.getMethodId(), b.getMethodId());
        });
        return methods;
    }

    private List<MutantContextView.FieldFactView> findAffectedFields(Connection connection, long methodId) throws SQLException {
        Map<Long, MutantContextView.FieldFactView> fields = new LinkedHashMap<Long, MutantContextView.FieldFactView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT DISTINCT f.id, f.name, f.field_type, f.visibility, f.is_static, f.is_final " +
                "FROM field_accesses a " +
                "JOIN fields f ON a.field_id = f.id " +
                "WHERE a.method_id = ? ORDER BY f.id")) {
            stmt.setLong(1, methodId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long fieldId = rs.getLong(1);
                    fields.put(fieldId, new MutantContextView.FieldFactView(
                        fieldId,
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5) != 0,
                        rs.getInt(6) != 0
                    ));
                }
            }
        }
        return new ArrayList<MutantContextView.FieldFactView>(fields.values());
    }

    private MutantContextView.TypeView findTypeView(Connection connection, long typeId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT id, qualified_name, simple_name, kind, super_class, visibility, is_static, is_final, is_abstract " +
                "FROM types WHERE id = ?")) {
            stmt.setLong(1, typeId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MutantContextView.TypeView(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getInt(7) != 0,
                        rs.getInt(8) != 0,
                        rs.getInt(9) != 0
                    );
                }
            }
        }
        return null;
    }

    private List<MutantContextView.MethodFactView> findOwnerMethods(Connection connection, long typeId) throws SQLException {
        List<MutantContextView.MethodFactView> methods = new ArrayList<MutantContextView.MethodFactView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT id, name, signature, return_type, visibility, is_static, is_final, is_abstract, is_constructor " +
                "FROM methods WHERE type_id = ? ORDER BY begin_line, id")) {
            stmt.setLong(1, typeId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    methods.add(new MutantContextView.MethodFactView(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6) != 0,
                        rs.getInt(7) != 0,
                        rs.getInt(8) != 0,
                        rs.getInt(9) != 0
                    ));
                }
            }
        }
        return methods;
    }

    private List<MutantContextView.FieldFactView> findOwnerFields(Connection connection, long typeId) throws SQLException {
        List<MutantContextView.FieldFactView> fields = new ArrayList<MutantContextView.FieldFactView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT id, name, field_type, visibility, is_static, is_final FROM fields WHERE type_id = ? ORDER BY id")) {
            stmt.setLong(1, typeId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    fields.add(new MutantContextView.FieldFactView(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5) != 0,
                        rs.getInt(6) != 0
                    ));
                }
            }
        }
        return fields;
    }

    private Set<Long> findDirectCallers(Connection connection, long typeId, String targetMethodName, long targetMethodId)
        throws SQLException {
        Set<Long> ids = new HashSet<Long>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT DISTINCT m.id " +
                "FROM methods m " +
                "JOIN method_calls c ON c.caller_method_id = m.id " +
                "LEFT JOIN methods cm ON c.callee_method_id = cm.id " +
                "WHERE m.type_id = ? AND (c.callee_method_id = ? OR cm.name = ? OR c.callee_method_name = ?)")) {
            stmt.setLong(1, typeId);
            stmt.setLong(2, targetMethodId);
            stmt.setString(3, targetMethodName);
            stmt.setString(4, targetMethodName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private Map<Long, List<String>> findFieldReaderReasons(Connection connection,
                                                           long typeId,
                                                           long excludeMethodId,
                                                           List<MutantContextView.FieldFactView> affectedFields)
        throws SQLException {
        Map<Long, List<String>> byMethod = new LinkedHashMap<Long, List<String>>();
        for (MutantContextView.FieldFactView field : affectedFields) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT DISTINCT m.id, m.name, m.signature, a.access_kind " +
                    "FROM field_accesses a " +
                    "JOIN methods m ON a.method_id = m.id " +
                    "WHERE m.type_id = ? AND m.id <> ? AND m.visibility <> 'private' AND a.field_id = ? " +
                    "ORDER BY m.id")) {
                stmt.setLong(1, typeId);
                stmt.setLong(2, excludeMethodId);
                stmt.setLong(3, field.getFieldId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long methodId = rs.getLong(1);
                        String accessKind = safe(rs.getString(4));
                        String reason = "uses affected field '" + field.getName() + "' as " + accessKind.toLowerCase();
                        appendReason(byMethod, methodId, reason);
                    }
                }
            }
        }
        return byMethod;
    }

    private List<MutantContextView.FieldPropagationView> findFieldPropagationCandidates(Connection connection,
                                                                                        long typeId,
                                                                                        long excludeMethodId,
                                                                                        List<MutantContextView.FieldFactView> affectedFields)
        throws SQLException {
        List<MutantContextView.FieldPropagationView> out = new ArrayList<MutantContextView.FieldPropagationView>();
        for (MutantContextView.FieldFactView field : affectedFields) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT DISTINCT m.id, t.id, t.qualified_name, m.name, m.signature, m.return_type, m.visibility, " +
                    "m.is_static, m.is_final, m.is_abstract, m.is_constructor, m.is_public, m.begin_line, m.end_line, " +
                    "a.access_kind, a.line_number " +
                    "FROM field_accesses a " +
                    "JOIN methods m ON a.method_id = m.id " +
                    "JOIN types t ON m.type_id = t.id " +
                    "WHERE m.type_id = ? AND m.id <> ? AND m.visibility <> 'private' AND a.field_id = ? " +
                    "ORDER BY m.begin_line, m.id, a.line_number")) {
                stmt.setLong(1, typeId);
                stmt.setLong(2, excludeMethodId);
                stmt.setLong(3, field.getFieldId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        MutantContextView.MethodLink reader = new MutantContextView.MethodLink(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getString(7),
                            rs.getInt(8) != 0,
                            rs.getInt(9) != 0,
                            rs.getInt(10) != 0,
                            rs.getInt(11) != 0,
                            rs.getInt(12) != 0,
                            rs.getInt(13),
                            rs.getInt(14),
                            0.0,
                            ""
                        );
                        String accessKind = safe(rs.getString(15));
                        double score = visibilityScore(reader.getVisibility()) + ("READ".equalsIgnoreCase(accessKind) ? 80.0 : 35.0);
                        if (!"void".equalsIgnoreCase(safe(reader.getReturnType()))) {
                            score += 15.0;
                        }
                        out.add(new MutantContextView.FieldPropagationView(
                            field,
                            reader,
                            accessKind,
                            rs.getInt(16),
                            score,
                            buildFieldPropagationReason(field, reader, accessKind)
                        ));
                    }
                }
            }
        }
        out.sort((a, b) -> {
            int byScore = Double.compare(b.getScore(), a.getScore());
            if (byScore != 0) {
                return byScore;
            }
            int byField = a.getField().getName().compareTo(b.getField().getName());
            if (byField != 0) {
                return byField;
            }
            int byLine = Integer.compare(a.getReaderMethod().getBeginLine(), b.getReaderMethod().getBeginLine());
            if (byLine != 0) {
                return byLine;
            }
            return Long.compare(a.getReaderMethod().getMethodId(), b.getReaderMethod().getMethodId());
        });
        return out;
    }

    private List<MutantContextView.MethodLink> findPersistedEntryCandidates(Connection connection, long mutantRowId)
        throws SQLException {
        List<MutantContextView.MethodLink> out = new ArrayList<MutantContextView.MethodLink>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, t.id, t.qualified_name, m.name, m.signature, m.return_type, m.visibility, m.is_static, " +
                "m.is_final, m.is_abstract, m.is_constructor, m.is_public, m.begin_line, m.end_line, c.priority, c.reason_summary " +
                "FROM entry_candidates c JOIN methods m ON c.method_id = m.id JOIN types t ON m.type_id = t.id " +
                "WHERE c.mutant_row_id = ? ORDER BY c.priority DESC, m.begin_line, m.id")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new MutantContextView.MethodLink(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getInt(8) != 0, rs.getInt(9) != 0, rs.getInt(10) != 0, rs.getInt(11) != 0,
                        rs.getInt(12) != 0, rs.getInt(13), rs.getInt(14), rs.getDouble(15), rs.getString(16)
                    ));
                }
            }
        }
        return out;
    }

    private List<MutantContextView.ObservableCandidateView> findObservableCandidates(Connection connection, long mutantRowId)
        throws SQLException {
        List<MutantContextView.ObservableCandidateView> out = new ArrayList<MutantContextView.ObservableCandidateView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT c.method_id, m.signature, c.observable_kind, c.expression, c.priority, c.is_primary, c.reason_summary " +
                "FROM observable_candidates c LEFT JOIN methods m ON c.method_id = m.id " +
                "WHERE c.mutant_row_id = ? ORDER BY c.priority DESC, c.id")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Long methodId = rs.getObject(1) == null ? null : Long.valueOf(rs.getLong(1));
                    out.add(new MutantContextView.ObservableCandidateView(
                        methodId, rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getInt(6) != 0,
                        rs.getString(7)
                    ));
                }
            }
        }
        return out;
    }

    private List<MutantContextView.FieldObserverLinkView> findFieldObserverLinks(Connection connection, long mutantRowId)
        throws SQLException {
        List<MutantContextView.FieldObserverLinkView> out = new ArrayList<MutantContextView.FieldObserverLinkView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT l.field_id, f.name, l.observer_method_id, m.signature, l.observer_kind, l.distance, l.priority, l.reason_summary " +
                "FROM field_observer_links l JOIN fields f ON l.field_id = f.id JOIN methods m ON l.observer_method_id = m.id " +
                "WHERE l.mutant_row_id = ? ORDER BY l.priority DESC, l.id")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new MutantContextView.FieldObserverLinkView(
                        rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getDouble(7), rs.getString(8)
                    ));
                }
            }
        }
        return out;
    }

    private List<MutantContextView.WitnessCandidateView> findWitnessCandidates(Connection connection, long mutantRowId)
        throws SQLException {
        List<MutantContextView.WitnessCandidateView> out = new ArrayList<MutantContextView.WitnessCandidateView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT w.entry_method_id, em.signature, w.observable_method_id, om.signature, w.witness_rank, " +
                "w.receiver_setup_json, w.argument_setup_json, w.predicate_chain_json, w.expected_original_outcome, " +
                "w.expected_mutant_outcome, w.outcome_kind, w.assertion_sketch, w.reason_summary " +
                "FROM mutant_witness_candidates w " +
                "LEFT JOIN methods em ON w.entry_method_id = em.id " +
                "LEFT JOIN methods om ON w.observable_method_id = om.id " +
                "WHERE w.mutant_row_id = ? ORDER BY w.witness_rank, w.id")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Long entryMethodId = rs.getObject(1) == null ? null : Long.valueOf(rs.getLong(1));
                    Long observableMethodId = rs.getObject(3) == null ? null : Long.valueOf(rs.getLong(3));
                    out.add(new MutantContextView.WitnessCandidateView(
                        entryMethodId, rs.getString(2), observableMethodId, rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getString(13)
                    ));
                }
            }
        }
        return out;
    }

    private List<MutantContextView.ObservableCandidateView> deriveObservableCandidatesFallback(
        MutantContextView.MethodLink originalMethod,
        List<MutantContextView.FieldPropagationView> fieldPropagationCandidates) {
        List<MutantContextView.ObservableCandidateView> out = new ArrayList<MutantContextView.ObservableCandidateView>();
        if (originalMethod != null && !"void".equalsIgnoreCase(safe(originalMethod.getReturnType()))) {
            out.add(new MutantContextView.ObservableCandidateView(
                Long.valueOf(originalMethod.getMethodId()),
                originalMethod.getSignature(),
                "RETURN_VALUE",
                "result",
                220.0,
                true,
                "mutated method returns a value directly observable by the test"
            ));
        }
        Set<Long> seen = new HashSet<Long>();
        for (MutantContextView.FieldPropagationView view : fieldPropagationCandidates) {
            if (!seen.add(Long.valueOf(view.getReaderMethod().getMethodId()))) {
                continue;
            }
            out.add(new MutantContextView.ObservableCandidateView(
                Long.valueOf(view.getReaderMethod().getMethodId()),
                view.getReaderMethod().getSignature(),
                "PUBLIC_METHOD_DEPENDS_ON_MUTATED_STATE",
                view.getReaderMethod().getSignature(),
                view.getScore(),
                out.isEmpty(),
                view.getReason()
            ));
        }
        return out;
    }

    private List<MutantContextView.WitnessCandidateView> deriveWitnessFallback(
        List<MutantContextView.MethodLink> entries,
        List<MutantContextView.ObservableCandidateView> observables) {
        List<MutantContextView.WitnessCandidateView> out = new ArrayList<MutantContextView.WitnessCandidateView>();
        int limit = Math.min(Math.min(entries.size(), observables.size()), 2);
        for (int i = 0; i < limit; i++) {
            MutantContextView.MethodLink entry = entries.get(i);
            MutantContextView.ObservableCandidateView observable = observables.get(i);
            List<String> predicates = new ArrayList<String>();
            if (observable != null && observable.getReason() != null && !observable.getReason().trim().isEmpty()) {
                predicates.add(observable.getReason());
            }
            out.add(new MutantContextView.WitnessCandidateView(
                Long.valueOf(entry.getMethodId()),
                entry.getSignature(),
                observable.getMethodId(),
                observable.getMethodSignature(),
                i + 1,
                buildWitnessReceiverSetup(entry, observable, predicates),
                buildWitnessArgumentSetup(entry, observable, predicates),
                buildWitnessPredicateChain(predicates),
                safe(observable.getObservableKind()) + " original outcome",
                "different mutant outcome",
                observable.getObservableKind(),
                buildWitnessAssertionSketch(entry, observable),
                "fallback witness built from ranked entry/observable candidates"
            ));
        }
        return out;
    }

    private String buildWitnessReceiverSetup(MutantContextView.MethodLink entry,
                                             MutantContextView.ObservableCandidateView observable,
                                             List<String> predicates) {
        List<String> steps = new ArrayList<String>();
        String owner = simpleTypeName(entry.getTypeName());
        if (entry.isStatic()) {
            steps.add("No receiver object is required for static entry " + entry.getSignature() + ".");
        } else if (entry.isConstructor()) {
            steps.add(owner + " subject = new " + owner + "(/* constructor args */);");
        } else {
            steps.add(owner + " subject = /* construct a live receiver for " + entry.getSignature() + " */;");
        }
        if (predicates.isEmpty()) {
            steps.add("Shape the receiver so the mutation-sensitive branch can execute.");
        } else {
            steps.add("Satisfy the path predicates before the mutation-sensitive call.");
        }
        if (observable != null) {
            steps.add("Observe the " + safe(observable.getObservableKind()).toLowerCase(Locale.ROOT)
                + " sink after invoking " + invocationExpression(entry) + ".");
        }
        return toJsonArray(steps);
    }

    private String buildWitnessArgumentSetup(MutantContextView.MethodLink entry,
                                             MutantContextView.ObservableCandidateView observable,
                                             List<String> predicates) {
        List<String> steps = new ArrayList<String>();
        List<String> parameters = parseParameterTypes(entry.getSignature());
        if (parameters.isEmpty()) {
            steps.add("No call arguments are required for " + entry.getSignature() + "; the witness depends on receiver state.");
        } else {
            for (int i = 0; i < parameters.size(); i++) {
                String type = parameters.get(i);
                steps.add(type + " arg" + (i + 1) + " = /* type-compatible value that preserves the path predicates */;");
            }
        }
        if (!predicates.isEmpty()) {
            steps.add("Arguments should preserve the relevant conditions rather than short-circuiting the target branch.");
        }
        if (observable != null && "RETURN_VALUE".equalsIgnoreCase(observable.getObservableKind())) {
            steps.add("Choose argument values that make the returned value distinguish original from mutant.");
        }
        return toJsonArray(steps);
    }

    private String buildWitnessPredicateChain(List<String> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return "[]";
        }
        return toJsonArray(predicates);
    }

    private String buildWitnessAssertionSketch(MutantContextView.MethodLink entry,
                                               MutantContextView.ObservableCandidateView observable) {
        String call = invocationExpression(entry);
        if (observable != null && "RETURN_VALUE".equalsIgnoreCase(observable.getObservableKind())) {
            return "assertNotEquals(\"mutant should alter the returned value\", /* originalExpected */, " + call + ");";
        }
        return "// Invoke " + call + " and assert the observable " + safe(observable == null ? "" : observable.getExpression())
            + " with a mutation-sensitive expectation.";
    }

    private List<String> parseParameterTypes(String signature) {
        List<String> out = new ArrayList<String>();
        String sig = safe(signature);
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close < open) {
            return out;
        }
        String inside = sig.substring(open + 1, close).trim();
        if (inside.isEmpty()) {
            return out;
        }
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == ',' && depth == 0) {
                String value = current.toString().trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
                current.setLength(0);
                continue;
            }
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                if (depth > 0) {
                    depth--;
                }
            }
            current.append(ch);
        }
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            out.add(value);
        }
        return out;
    }

    private String invocationExpression(MutantContextView.MethodLink entry) {
        String owner = simpleTypeName(entry.getTypeName());
        return entry.isConstructor() ? "new " + owner + "(/* args */)"
            : (entry.isStatic() ? owner + "." + entry.getMethodName() + "(/* args */)"
                : "subject." + entry.getMethodName() + "(/* args */)");
    }

    private static String buildFieldPropagationReason(MutantContextView.FieldFactView field,
                                                      MutantContextView.MethodLink reader,
                                                      String accessKind) {
        List<String> reasons = new ArrayList<String>();
        reasons.add("method " + reader.getSignature() + " " + accessKind.toLowerCase() + "s affected field '" + field.getName() + "'");
        if (!"void".equalsIgnoreCase(safe(reader.getReturnType()))) {
            reasons.add("non-void return can expose propagated state");
        }
        reasons.add(reader.getVisibility() + " visibility makes it a better test entry than private helpers");
        return joinReasons(reasons);
    }

    private static void appendReason(Map<Long, List<String>> byMethod, long methodId, String reason) {
        List<String> reasons = byMethod.get(Long.valueOf(methodId));
        if (reasons == null) {
            reasons = new ArrayList<String>();
            byMethod.put(Long.valueOf(methodId), reasons);
        }
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private static double visibilityScore(String visibility) {
        if ("public".equalsIgnoreCase(visibility)) {
            return 60.0;
        }
        if ("protected".equalsIgnoreCase(visibility)) {
            return 45.0;
        }
        if ("package-private".equalsIgnoreCase(visibility)) {
            return 35.0;
        }
        return 0.0;
    }

    private static String joinReasons(List<String> reasons) {
        List<String> normalized = new ArrayList<String>();
        for (String reason : reasons) {
            String value = safe(reason);
            if (!value.isEmpty() && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return String.join("; ", normalized);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private List<MutantContextView.CandidateImportView> findCandidateImports(
        Connection connection,
        String mutantId,
        MutantContextView.FileLink originalFile,
        MutantContextView.MethodLink originalMethod,
        List<MutantContextView.MethodLink> candidateEntries
    ) throws SQLException {
        Map<String, MutantContextView.CandidateImportView> merged = new LinkedHashMap<String, MutantContextView.CandidateImportView>();
        if (originalFile != null) {
            mergeCandidateImports(merged, loadFileCandidateImports(connection, originalFile.getFileId()));
        }
        if (originalMethod != null) {
            mergeCandidateImports(merged, loadMethodCandidateImports(connection, originalMethod.getMethodId(), "MUTATION_METHOD",
                originalMethod.getSignature()));
        }
        for (MutantContextView.MethodLink entry : candidateEntries) {
            mergeCandidateImports(merged, loadMethodCandidateImports(connection, entry.getMethodId(), "ENTRY_METHOD",
                entry.getSignature()));
        }
        if (mutantId != null && !mutantId.trim().isEmpty()) {
            String normalizedId = CanonicalMutantId.normalize(mutantId);
            mergeCandidateImports(merged, feedbackRepository.loadAcceptedImportCandidates(connection, normalizedId));
            String legacyMutantName = CanonicalMutantId.legacyMutantName(normalizedId);
            if (!legacyMutantName.isEmpty() && !legacyMutantName.equals(normalizedId)) {
                mergeCandidateImports(merged, feedbackRepository.loadAcceptedImportCandidates(connection, legacyMutantName));
            }
        }
        List<MutantContextView.CandidateImportView> out = new ArrayList<MutantContextView.CandidateImportView>(merged.values());
        out.sort(Comparator
            .comparingInt(MutantContextView.CandidateImportView::getPriority).reversed()
            .thenComparing(MutantContextView.CandidateImportView::getScope)
            .thenComparing(MutantContextView.CandidateImportView::getImportValue));
        return out;
    }

    private void mergeCandidateImports(
        Map<String, MutantContextView.CandidateImportView> merged,
        List<MutantContextView.CandidateImportView> imports
    ) {
        for (MutantContextView.CandidateImportView candidateImport : imports) {
            String key = candidateImport.getScope() + "|" + candidateImport.getOwnerMethodSignature() + "|"
                + candidateImport.getImportValue() + "|" + candidateImport.getSourceKind();
            MutantContextView.CandidateImportView existing = merged.get(key);
            if (existing == null || candidateImport.getPriority() > existing.getPriority()) {
                merged.put(key, candidateImport);
            }
        }
    }

    private List<MutantContextView.CandidateImportView> loadFileCandidateImports(Connection connection, long fileId)
        throws SQLException {
        List<MutantContextView.CandidateImportView> imports = new ArrayList<MutantContextView.CandidateImportView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT import_value, source_kind, usage_context, priority " +
                "FROM candidate_imports WHERE file_id = ? AND method_id IS NULL ORDER BY priority DESC, import_value")) {
            stmt.setLong(1, fileId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    imports.add(new MutantContextView.CandidateImportView(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        "SOURCE_FILE",
                        "",
                        "BASE_KB"
                    ));
                }
            }
        }
        return imports;
    }

    private List<MutantContextView.CandidateImportView> loadMethodCandidateImports(
        Connection connection,
        long methodId,
        String scope,
        String ownerMethodSignature
    ) throws SQLException {
        List<MutantContextView.CandidateImportView> imports = new ArrayList<MutantContextView.CandidateImportView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT import_value, source_kind, usage_context, priority " +
                "FROM candidate_imports WHERE method_id = ? ORDER BY priority DESC, import_value")) {
            stmt.setLong(1, methodId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    imports.add(new MutantContextView.CandidateImportView(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        scope,
                        ownerMethodSignature == null ? "" : ownerMethodSignature,
                        "BASE_KB"
                    ));
                }
            }
        }
        return imports;
    }

    private String simpleTypeName(String qualifiedTypeName) {
        if (qualifiedTypeName == null || qualifiedTypeName.isEmpty()) {
            return "";
        }
        int dot = qualifiedTypeName.lastIndexOf('.');
        return dot >= 0 ? qualifiedTypeName.substring(dot + 1) : qualifiedTypeName;
    }

    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private String escapeJson(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class MutantRow {
        private final long rowId;
        private final String mutantId;
        private final String project;
        private final String operator;
        private final int lineNumber;
        private final String className;
        private final String methodName;
        private final String mutationStatement;

        private MutantRow(
            long rowId,
            String mutantId,
            String project,
            String operator,
            int lineNumber,
            String className,
            String methodName,
            String mutationStatement
        ) {
            this.rowId = rowId;
            this.mutantId = mutantId;
            this.project = project;
            this.operator = operator;
            this.lineNumber = lineNumber;
            this.className = className;
            this.methodName = methodName;
            this.mutationStatement = mutationStatement;
        }
    }
}
