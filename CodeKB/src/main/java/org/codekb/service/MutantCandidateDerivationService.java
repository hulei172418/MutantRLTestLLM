package org.codekb.service;

import org.codekb.model.MutantContextView;
import org.codekb.store.MutantCandidateRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.ArrayDeque;

public final class MutantCandidateDerivationService {
    private final MutantCandidateRepository repository;

    public MutantCandidateDerivationService() {
        this.repository = new MutantCandidateRepository();
    }

    public void refreshForMutant(Connection connection, long mutantRowId) throws SQLException {
        MethodAnchor originalMethod = findMethodAnchor(connection, mutantRowId, true);
        if (originalMethod == null) {
            return;
        }
        List<MutantContextView.FieldFactView> affectedFields = findAffectedFields(connection, originalMethod.methodId);
        Map<Long, Integer> distanceToMutated = computeIntraTypeDistances(connection, originalMethod.typeId, originalMethod.methodId);
        List<MutantContextView.MethodLink> entries = deriveEntryCandidates(
            connection, originalMethod.typeId, originalMethod.methodId, originalMethod.methodName, originalMethod.signature,
            affectedFields, distanceToMutated);
        List<MutantContextView.FieldObserverLinkView> fieldObservers = deriveFieldObservers(
            connection, mutantRowId, originalMethod.typeId, originalMethod.methodId, affectedFields);
        List<MutantContextView.ObservableCandidateView> observables = deriveObservableCandidates(
            connection, originalMethod, fieldObservers);
        List<MutantContextView.WitnessCandidateView> witnesses = deriveWitnesses(
            originalMethod, entries, observables, fieldObservers, distanceToMutated);
        repository.replaceEntryCandidates(connection, mutantRowId, entries);
        repository.replaceFieldObserverLinks(connection, mutantRowId, fieldObservers);
        repository.replaceObservableCandidates(connection, mutantRowId, observables);
        repository.replaceWitnessCandidates(connection, mutantRowId, witnesses);
    }

    private MethodAnchor findMethodAnchor(Connection connection, long mutantRowId, boolean original) throws SQLException {
        String column = original ? "original_method_id" : "mutant_method_id";
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, m.type_id, m.name, m.signature, m.return_type, m.visibility, m.is_static, m.is_constructor, m.begin_line, m.end_line, t.qualified_name " +
                "FROM mutant_method_links l JOIN methods m ON l." + column + " = m.id " +
                "JOIN types t ON m.type_id = t.id WHERE l.mutant_row_id = ?")) {
            stmt.setLong(1, mutantRowId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MethodAnchor(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getInt(7) != 0,
                        rs.getInt(8) != 0,
                        rs.getInt(9),
                        rs.getInt(10),
                        rs.getString(11)
                    );
                }
            }
        }
        return null;
    }

    private List<MutantContextView.FieldFactView> findAffectedFields(Connection connection, long methodId) throws SQLException {
        List<MutantContextView.FieldFactView> fields = new ArrayList<MutantContextView.FieldFactView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT DISTINCT f.id, f.name, f.field_type, f.visibility, f.is_static, f.is_final " +
                "FROM field_accesses a JOIN fields f ON a.field_id = f.id WHERE a.method_id = ? ORDER BY f.id")) {
            stmt.setLong(1, methodId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    fields.add(new MutantContextView.FieldFactView(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) != 0, rs.getInt(6) != 0));
                }
            }
        }
        return fields;
    }

    private List<MutantContextView.MethodLink> deriveEntryCandidates(Connection connection,
                                                                     long typeId,
                                                                     long targetMethodId,
                                                                     String targetMethodName,
                                                                     String targetSignature,
                                                                     List<MutantContextView.FieldFactView> affectedFields,
                                                                     Map<Long, Integer> distanceToMutated) throws SQLException {
        List<MutantContextView.MethodLink> methods = new ArrayList<MutantContextView.MethodLink>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, t.id, t.qualified_name, m.name, m.signature, m.return_type, m.visibility, m.is_static, m.is_final, " +
                "m.is_abstract, m.is_constructor, m.is_public, m.begin_line, m.end_line " +
                "FROM methods m JOIN types t ON m.type_id = t.id WHERE t.id = ? AND m.visibility <> 'private' ORDER BY m.begin_line, m.id")) {
            stmt.setLong(1, typeId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long methodId = rs.getLong(1);
                    String methodName = rs.getString(4);
                    String signature = rs.getString(5);
                    double score = visibilityScore(rs.getString(7));
                    List<String> reasons = new ArrayList<String>();
                    if (methodId == targetMethodId && !"private".equalsIgnoreCase(rs.getString(7))) {
                        score += 220.0;
                        reasons.add("mutated method itself is directly callable");
                    } else {
                        reasons.add(rs.getString(7) + " method in same class");
                    }
                    Integer distance = distanceToMutated.get(Long.valueOf(methodId));
                    if (distance != null && distance.intValue() > 0) {
                        if (distance.intValue() == 1) {
                            score += 120.0;
                            reasons.add("directly calls mutated method");
                        } else {
                            score += Math.max(25.0, 105.0 - (distance.intValue() * 18.0));
                            reasons.add("reaches mutated method through " + distance + " intra-class call hops");
                        }
                    }
                    if (rs.getInt(11) != 0) {
                        score += 30.0;
                        reasons.add("constructor can shape receiver state before the mutation method");
                    } else if ("parse".equals(methodName)) {
                        score += 25.0;
                        reasons.add("parse-like front door may reach the mutation method");
                    }
                    if (signature != null && signature.equals(targetSignature)) {
                        score += 25.0;
                    }
                    methods.add(new MutantContextView.MethodLink(
                        methodId, rs.getLong(2), rs.getString(3), methodName, signature, rs.getString(6), rs.getString(7),
                        rs.getInt(8) != 0, rs.getInt(9) != 0, rs.getInt(10) != 0, rs.getInt(11) != 0, rs.getInt(12) != 0,
                        rs.getInt(13), rs.getInt(14), score, joinReasons(reasons)));
                }
            }
        }
        methods.sort(Comparator.comparingDouble(MutantContextView.MethodLink::getScore).reversed()
            .thenComparingInt(MutantContextView.MethodLink::getBeginLine)
            .thenComparingLong(MutantContextView.MethodLink::getMethodId));
        return methods;
    }

    private List<MutantContextView.FieldObserverLinkView> deriveFieldObservers(Connection connection,
                                                                               long mutantRowId,
                                                                               long typeId,
                                                                               long targetMethodId,
                                                                               List<MutantContextView.FieldFactView> affectedFields) throws SQLException {
        List<MutantContextView.FieldObserverLinkView> links = new ArrayList<MutantContextView.FieldObserverLinkView>();
        for (MutantContextView.FieldFactView field : affectedFields) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT DISTINCT m.id, m.signature, m.visibility, m.return_type, a.access_kind " +
                    "FROM field_accesses a JOIN methods m ON a.method_id = m.id " +
                    "WHERE m.type_id = ? AND m.id <> ? AND m.visibility <> 'private' AND a.field_id = ? ORDER BY m.id")) {
                stmt.setLong(1, typeId);
                stmt.setLong(2, targetMethodId);
                stmt.setLong(3, field.getFieldId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String visibility = safe(rs.getString(3));
                        String accessKind = safe(rs.getString(5));
                        String observerKind = toObserverKind(accessKind, rs.getString(4));
                        double priority = visibilityScore(visibility) + ("READ".equalsIgnoreCase(accessKind) ? 80.0 : 35.0);
                        List<String> reasons = new ArrayList<String>();
                        reasons.add("reads affected field '" + field.getName() + "'");
                        if (!"void".equalsIgnoreCase(safe(rs.getString(4)))) {
                            priority += 20.0;
                            reasons.add("returns value after reading affected field");
                        }
                        links.add(new MutantContextView.FieldObserverLinkView(
                            field.getFieldId(),
                            field.getName(),
                            rs.getLong(1),
                            rs.getString(2),
                            observerKind,
                            1,
                            priority,
                            joinReasons(reasons)
                        ));
                    }
                }
            }
        }
        links.sort(Comparator.comparingDouble(MutantContextView.FieldObserverLinkView::getPriority).reversed()
            .thenComparingLong(MutantContextView.FieldObserverLinkView::getObserverMethodId));
        return links;
    }

    private List<MutantContextView.ObservableCandidateView> deriveObservableCandidates(Connection connection,
                                                                                       MethodAnchor originalMethod,
                                                                                       List<MutantContextView.FieldObserverLinkView> fieldObservers) throws SQLException {
        List<MutantContextView.ObservableCandidateView> out = new ArrayList<MutantContextView.ObservableCandidateView>();
        if (!originalMethod.isConstructor && !"void".equalsIgnoreCase(safe(originalMethod.returnType))) {
            out.add(new MutantContextView.ObservableCandidateView(
                Long.valueOf(originalMethod.methodId),
                originalMethod.signature,
                "RETURN_VALUE",
                "result",
                220.0,
                true,
                "mutated method returns a value directly observable by the test"
            ));
        }
        Set<Long> seenMethods = new HashSet<Long>();
        for (MutantContextView.FieldObserverLinkView link : fieldObservers) {
            if (!seenMethods.add(Long.valueOf(link.getObserverMethodId()))) {
                continue;
            }
            out.add(new MutantContextView.ObservableCandidateView(
                Long.valueOf(link.getObserverMethodId()),
                link.getObserverMethodSignature(),
                "PUBLIC_METHOD_DEPENDS_ON_MUTATED_STATE",
                link.getObserverMethodSignature(),
                link.getPriority(),
                out.isEmpty(),
                link.getReason()
            ));
        }
        if (out.isEmpty()) {
            out.add(new MutantContextView.ObservableCandidateView(
                Long.valueOf(originalMethod.methodId),
                originalMethod.signature,
                "METHOD_COMPLETION",
                originalMethod.signature,
                50.0,
                true,
                "no stronger observable was derived; fallback to behavior reachable from the callable entry"
            ));
        }
        out.sort(Comparator.comparingDouble(MutantContextView.ObservableCandidateView::getPriority).reversed());
        if (!out.isEmpty()) {
            MutantContextView.ObservableCandidateView first = out.get(0);
            out.set(0, new MutantContextView.ObservableCandidateView(
                first.getMethodId(), first.getMethodSignature(), first.getObservableKind(), first.getExpression(),
                first.getPriority(), true, first.getReason()));
        }
        return out;
    }

    private List<MutantContextView.WitnessCandidateView> deriveWitnesses(MethodAnchor originalMethod,
                                                                         List<MutantContextView.MethodLink> entries,
                                                                         List<MutantContextView.ObservableCandidateView> observables,
                                                                         List<MutantContextView.FieldObserverLinkView> fieldObservers,
                                                                         Map<Long, Integer> distanceToMutated) {
        List<MutantContextView.WitnessCandidateView> out = new ArrayList<MutantContextView.WitnessCandidateView>();
        Map<Long, MutantContextView.FieldObserverLinkView> bestObserverByMethod = bestFieldObserverByMethod(fieldObservers);
        List<ChainCandidate> chains = new ArrayList<ChainCandidate>();
        for (MutantContextView.MethodLink entry : entries) {
            Integer entryDistance = distanceToMutated.get(Long.valueOf(entry.getMethodId()));
            if (entryDistance == null) {
                continue;
            }
            for (MutantContextView.ObservableCandidateView observable : observables) {
                ChainCandidate chain = scoreChain(originalMethod, entry, observable, bestObserverByMethod, entryDistance.intValue());
                if (chain != null) {
                    chains.add(chain);
                }
            }
        }
        chains.sort(Comparator.comparingDouble(ChainCandidate::getScore).reversed()
            .thenComparingInt(ChainCandidate::getEntryDistance)
            .thenComparingInt(ChainCandidate::getObservableDistance)
            .thenComparingInt(ChainCandidate::getRankHint));
        int rank = 1;
        Set<String> seen = new HashSet<String>();
        for (ChainCandidate chain : chains) {
            String dedupeKey = chain.entry.getMethodId() + "->" + safe(chain.observable.getMethodSignature())
                + "#" + safe(chain.observable.getObservableKind());
            if (!seen.add(dedupeKey)) {
                continue;
            }
            out.add(new MutantContextView.WitnessCandidateView(
                Long.valueOf(chain.entry.getMethodId()),
                chain.entry.getSignature(),
                chain.observable.getMethodId(),
                chain.observable.getMethodSignature(),
                rank,
                buildReceiverSetupJson(originalMethod, chain.entry, chain.observable, chain.predicates),
                buildArgumentSetupJson(chain.entry, chain.observable, chain.predicates),
                toJsonArray(chain.predicates),
                chain.expectedOriginal,
                chain.expectedMutant,
                chain.observable.getObservableKind(),
                chain.assertionSketch,
                joinReasons(chain.reasons)
            ));
            rank++;
            if (rank > 5) {
                break;
            }
        }
        return out;
    }

    private Set<Long> findDirectCallers(Connection connection, long typeId, String targetMethodName, long targetMethodId)
        throws SQLException {
        Set<Long> ids = new HashSet<Long>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT DISTINCT m.id FROM methods m JOIN method_calls c ON c.caller_method_id = m.id " +
                "LEFT JOIN methods cm ON c.callee_method_id = cm.id " +
                "WHERE m.type_id = ? AND (c.callee_method_id = ? OR cm.name = ? OR c.callee_method_name = ?)")) {
            stmt.setLong(1, typeId);
            stmt.setLong(2, targetMethodId);
            stmt.setString(3, targetMethodName);
            stmt.setString(4, targetMethodName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(Long.valueOf(rs.getLong(1)));
                }
            }
        }
        return ids;
    }

    private Map<Long, Integer> computeIntraTypeDistances(Connection connection, long typeId, long targetMethodId) throws SQLException {
        Map<Long, Set<Long>> reverseCalls = new HashMap<Long, Set<Long>>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT m.id, COALESCE(c.callee_method_id, 0), COALESCE(cm.id, 0) " +
                "FROM methods m JOIN method_calls c ON c.caller_method_id = m.id " +
                "LEFT JOIN methods cm ON c.callee_method_id = cm.id " +
                "WHERE m.type_id = ?")) {
            stmt.setLong(1, typeId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long callerId = rs.getLong(1);
                    long calleeId = rs.getLong(2);
                    long resolvedCalleeId = rs.getLong(3);
                    long effectiveCalleeId = resolvedCalleeId > 0 ? resolvedCalleeId : calleeId;
                    if (effectiveCalleeId <= 0) {
                        continue;
                    }
                    Set<Long> callers = reverseCalls.get(Long.valueOf(effectiveCalleeId));
                    if (callers == null) {
                        callers = new HashSet<Long>();
                        reverseCalls.put(Long.valueOf(effectiveCalleeId), callers);
                    }
                    callers.add(Long.valueOf(callerId));
                }
            }
        }
        Map<Long, Integer> distance = new HashMap<Long, Integer>();
        Queue<Long> queue = new ArrayDeque<Long>();
        distance.put(Long.valueOf(targetMethodId), Integer.valueOf(0));
        queue.add(Long.valueOf(targetMethodId));
        while (!queue.isEmpty()) {
            Long current = queue.remove();
            int base = distance.get(current).intValue();
            Set<Long> callers = reverseCalls.get(current);
            if (callers == null) {
                continue;
            }
            for (Long caller : callers) {
                if (distance.containsKey(caller)) {
                    continue;
                }
                distance.put(caller, Integer.valueOf(base + 1));
                if (base + 1 < 6) {
                    queue.add(caller);
                }
            }
        }
        return distance;
    }

    private Map<Long, List<String>> findFieldReaderReasons(Connection connection, long typeId, long excludeMethodId,
                                                           List<MutantContextView.FieldFactView> affectedFields) throws SQLException {
        Map<Long, List<String>> byMethod = new LinkedHashMap<Long, List<String>>();
        for (MutantContextView.FieldFactView field : affectedFields) {
            try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT DISTINCT m.id, a.access_kind FROM field_accesses a JOIN methods m ON a.method_id = m.id " +
                    "WHERE m.type_id = ? AND m.id <> ? AND m.visibility <> 'private' AND a.field_id = ? ORDER BY m.id")) {
                stmt.setLong(1, typeId);
                stmt.setLong(2, excludeMethodId);
                stmt.setLong(3, field.getFieldId());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long methodId = rs.getLong(1);
                        List<String> reasons = byMethod.get(Long.valueOf(methodId));
                        if (reasons == null) {
                            reasons = new ArrayList<String>();
                            byMethod.put(Long.valueOf(methodId), reasons);
                        }
                        reasons.add(("READ".equalsIgnoreCase(safe(rs.getString(2))) ? "reads " : "writes ")
                            + "affected field '" + field.getName() + "'");
                    }
                }
            }
        }
        return byMethod;
    }

    private double visibilityScore(String visibility) {
        if ("public".equalsIgnoreCase(visibility)) {
            return 100.0;
        }
        if ("protected".equalsIgnoreCase(visibility)) {
            return 70.0;
        }
        if ("package-private".equalsIgnoreCase(visibility)) {
            return 50.0;
        }
        return 10.0;
    }

    private String joinReasons(List<String> reasons) {
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        for (String reason : reasons) {
            String normalized = safe(reason);
            if (!normalized.isEmpty()) {
                seen.put(normalized, Boolean.TRUE);
            }
        }
        return String.join("; ", seen.keySet());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String toObserverKind(String accessKind, String returnType) {
        if (!"void".equalsIgnoreCase(safe(returnType))) {
            return "DIRECT_RETURN";
        }
        if ("READ".equalsIgnoreCase(accessKind)) {
            return "INDIRECT_RETURN";
        }
        return "STATE_EFFECT";
    }

    private Map<Long, MutantContextView.FieldObserverLinkView> bestFieldObserverByMethod(
        List<MutantContextView.FieldObserverLinkView> fieldObservers) {
        Map<Long, MutantContextView.FieldObserverLinkView> out = new HashMap<Long, MutantContextView.FieldObserverLinkView>();
        for (MutantContextView.FieldObserverLinkView link : fieldObservers) {
            MutantContextView.FieldObserverLinkView existing = out.get(Long.valueOf(link.getObserverMethodId()));
            if (existing == null || link.getPriority() > existing.getPriority()) {
                out.put(Long.valueOf(link.getObserverMethodId()), link);
            }
        }
        return out;
    }

    private ChainCandidate scoreChain(MethodAnchor originalMethod,
                                      MutantContextView.MethodLink entry,
                                      MutantContextView.ObservableCandidateView observable,
                                      Map<Long, MutantContextView.FieldObserverLinkView> bestObserverByMethod,
                                      int entryDistance) {
        double score = entry.getScore() + observable.getPriority();
        int observableDistance = 1;
        List<String> reasons = new ArrayList<String>();
        List<String> predicates = new ArrayList<String>();
        reasons.add("entry '" + entry.getSignature() + "' reaches mutated method in " + entryDistance + " hop(s)");
        if (entryDistance == 0) {
            score += 140.0;
            reasons.add("entry is the mutated method itself");
        } else if (entryDistance == 1) {
            score += 90.0;
            reasons.add("entry directly calls the mutated method");
        } else {
            score += Math.max(10.0, 60.0 - (entryDistance * 8.0));
        }

        String expectedOriginal = "";
        String expectedMutant = "";
        if (observable.getMethodId() != null && observable.getMethodId().longValue() == originalMethod.methodId) {
            observableDistance = 0;
            score += 160.0;
            reasons.add("observable is the mutated method return itself");
            if ("boolean".equalsIgnoreCase(safe(originalMethod.returnType))) {
                expectedOriginal = "true or false (mutation-sensitive boolean oracle)";
                expectedMutant = "opposite boolean outcome on the distinguishing input";
            } else if (!safe(originalMethod.returnType).isEmpty() && !"void".equalsIgnoreCase(safe(originalMethod.returnType))) {
                expectedOriginal = "mutation-sensitive original return value";
                expectedMutant = "different mutant return value";
            }
        } else if (observable.getMethodId() != null) {
            MutantContextView.FieldObserverLinkView observer = bestObserverByMethod.get(observable.getMethodId());
            if (observer != null) {
                observableDistance = observer.getDistance();
                score += 100.0;
                reasons.add("observable method reads affected field '" + observer.getFieldName() + "'");
                predicates.add("mutated method perturbs field '" + observer.getFieldName() + "'");
                predicates.add("observer '" + observer.getObserverMethodSignature() + "' reads the affected field");
                expectedOriginal = "original observable state/value";
                expectedMutant = "different observable state/value after propagation";
            } else if (observable.getMethodId().longValue() == entry.getMethodId()) {
                score += 70.0;
                reasons.add("entry method is also the chosen observable");
            } else {
                score += 25.0;
                reasons.add("observable is a secondary callable method on the same owner type");
            }
        } else {
            score += 10.0;
            reasons.add("observable is expression-based fallback");
        }

        if (entry.getMethodId() == originalMethod.methodId
            && observable.getMethodId() != null
            && observable.getMethodId().longValue() == originalMethod.methodId) {
            score += 80.0;
            reasons.add("forms a direct B=A=C chain with no extra indirection");
        } else if (observable.getMethodId() != null && observable.getMethodId().longValue() == entry.getMethodId()) {
            score += 45.0;
            reasons.add("entry exposes the propagated state directly");
        }

        String assertion = buildAssertionSketch(entry, observable);
        return new ChainCandidate(entry, observable, predicates, reasons, assertion,
            expectedOriginal, expectedMutant, score, entryDistance, observableDistance, chainRankHint(entry, observable));
    }

    private int chainRankHint(MutantContextView.MethodLink entry, MutantContextView.ObservableCandidateView observable) {
        if (observable.getMethodId() != null && observable.getMethodId().longValue() == entry.getMethodId()) {
            return 0;
        }
        if ("RETURN_VALUE".equalsIgnoreCase(observable.getObservableKind())) {
            return 1;
        }
        if (observable.isPrimary()) {
            return 2;
        }
        return 3;
    }

    private String buildAssertionSketch(MutantContextView.MethodLink entry,
                                        MutantContextView.ObservableCandidateView observable) {
        if ("RETURN_VALUE".equalsIgnoreCase(observable.getObservableKind())) {
            return "assertNotEquals(\"mutant should alter the returned value\", /* originalExpected */, "
                + invocationExpression(entry) + ");";
        }
        return "// Call " + invocationExpression(entry) + " first, then assert the observable "
            + observable.getExpression() + " with a mutation-sensitive expectation.";
    }

    private String buildReceiverSetupJson(MethodAnchor originalMethod,
                                          MutantContextView.MethodLink entry,
                                          MutantContextView.ObservableCandidateView observable,
                                          List<String> predicates) {
        List<String> steps = new ArrayList<String>();
        String owner = simpleName(entry.getTypeName());
        if (entry.isStatic()) {
            steps.add("No receiver object is required for static entry " + entry.getSignature() + ".");
        } else if (entry.isConstructor()) {
            steps.add(owner + " subject = new " + owner + "(/* constructor args */);");
        } else {
            steps.add(owner + " subject = /* construct a live receiver for " + entry.getSignature() + " */;");
        }
        if (!predicates.isEmpty()) {
            steps.add("Satisfy the path predicates before the mutation-sensitive call: " + joinSimple(predicates) + ".");
        } else {
            steps.add("Keep the receiver state compatible with the mutation-sensitive branch before invocation.");
        }
        if (observable != null) {
            steps.add("Observe the " + safe(observable.getObservableKind()).toLowerCase(Locale.ROOT)
                + " sink after invoking " + invocationExpression(entry) + ".");
        }
        if (originalMethod != null && originalMethod.qualifiedTypeName != null && !originalMethod.qualifiedTypeName.isEmpty()) {
            steps.add("Receiver belongs to " + originalMethod.qualifiedTypeName + " and must be a live instance.");
        }
        return toJsonArray(steps);
    }

    private String buildArgumentSetupJson(MutantContextView.MethodLink entry,
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
            steps.add("Keep the arguments type-compatible and avoid front-door guard shortcuts.");
        }
        if (!predicates.isEmpty()) {
            steps.add("Arguments should not invalidate the required predicates: " + joinSimple(predicates) + ".");
        }
        if (observable != null) {
            if ("RETURN_VALUE".equalsIgnoreCase(observable.getObservableKind())) {
                steps.add("Use arguments that let the return-value oracle distinguish original from mutant.");
            } else if (observable.getObservableKind() != null && observable.getObservableKind().contains("STATE")) {
                steps.add("Use arguments that make the post-state observable through " + observable.getExpression() + ".");
            } else if ("EXCEPTION".equalsIgnoreCase(observable.getObservableKind())) {
                steps.add("Use arguments that reach the expected exception path without triggering unrelated front-door guards.");
            }
        }
        return toJsonArray(steps);
    }

    private String invocationExpression(MutantContextView.MethodLink entry) {
        String owner = simpleName(entry.getTypeName());
        return entry.isConstructor() ? "new " + owner + "(/* args */)" :
            (entry.isStatic() ? owner + "." + entry.getMethodName() + "(/* args */)"
                : "subject." + entry.getMethodName() + "(/* args */)");
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

    private String joinSimple(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static final class ChainCandidate {
        private final MutantContextView.MethodLink entry;
        private final MutantContextView.ObservableCandidateView observable;
        private final List<String> predicates;
        private final List<String> reasons;
        private final String assertionSketch;
        private final String expectedOriginal;
        private final String expectedMutant;
        private final double score;
        private final int entryDistance;
        private final int observableDistance;
        private final int rankHint;

        private ChainCandidate(MutantContextView.MethodLink entry,
                               MutantContextView.ObservableCandidateView observable,
                               List<String> predicates,
                               List<String> reasons,
                               String assertionSketch,
                               String expectedOriginal,
                               String expectedMutant,
                               double score,
                               int entryDistance,
                               int observableDistance,
                               int rankHint) {
            this.entry = entry;
            this.observable = observable;
            this.predicates = predicates;
            this.reasons = reasons;
            this.assertionSketch = assertionSketch;
            this.expectedOriginal = expectedOriginal;
            this.expectedMutant = expectedMutant;
            this.score = score;
            this.entryDistance = entryDistance;
            this.observableDistance = observableDistance;
            this.rankHint = rankHint;
        }

        private double getScore() {
            return score;
        }

        private int getEntryDistance() {
            return entryDistance;
        }

        private int getObservableDistance() {
            return observableDistance;
        }

        private int getRankHint() {
            return rankHint;
        }
    }

    private String simpleName(String qualifiedName) {
        String value = safe(qualifiedName);
        int dollar = value.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < value.length()) {
            value = value.substring(dollar + 1);
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length() ? value.substring(dot + 1) : value;
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

    private static final class MethodAnchor {
        private final long methodId;
        private final long typeId;
        private final String methodName;
        private final String signature;
        private final String returnType;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isConstructor;
        private final int beginLine;
        private final int endLine;
        private final String qualifiedTypeName;

        private MethodAnchor(long methodId, long typeId, String methodName, String signature, String returnType,
                             String visibility, boolean isStatic, boolean isConstructor, int beginLine, int endLine,
                             String qualifiedTypeName) {
            this.methodId = methodId;
            this.typeId = typeId;
            this.methodName = methodName;
            this.signature = signature;
            this.returnType = returnType;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isConstructor = isConstructor;
            this.beginLine = beginLine;
            this.endLine = endLine;
            this.qualifiedTypeName = qualifiedTypeName;
        }
    }
}
