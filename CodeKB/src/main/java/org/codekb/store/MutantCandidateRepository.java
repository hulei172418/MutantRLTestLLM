package org.codekb.store;

import org.codekb.model.MutantContextView;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public final class MutantCandidateRepository {
    public void replaceEntryCandidates(Connection connection, long mutantRowId,
                                       List<MutantContextView.MethodLink> entries) throws SQLException {
        deleteByMutant(connection, "DELETE FROM entry_candidates WHERE mutant_row_id = ?", mutantRowId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO entry_candidates(mutant_row_id, method_id, entry_kind, access_level_score, " +
                "returns_observable_value, is_preferred_entry, priority, reason_summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < entries.size(); i++) {
                MutantContextView.MethodLink entry = entries.get(i);
                stmt.setLong(1, mutantRowId);
                stmt.setLong(2, entry.getMethodId());
                stmt.setString(3, entry.isConstructor() ? "PUBLIC_CONSTRUCTOR"
                    : entry.isStatic() ? "STATIC_FACTORY" : "PUBLIC_METHOD");
                stmt.setDouble(4, accessLevelScore(entry.getVisibility()));
                stmt.setInt(5, returnsObservableValue(entry) ? 1 : 0);
                stmt.setInt(6, i == 0 ? 1 : 0);
                stmt.setDouble(7, entry.getScore());
                stmt.setString(8, entry.getReason());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceObservableCandidates(Connection connection, long mutantRowId,
                                            List<MutantContextView.ObservableCandidateView> observables) throws SQLException {
        deleteByMutant(connection, "DELETE FROM observable_candidates WHERE mutant_row_id = ?", mutantRowId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO observable_candidates(mutant_row_id, method_id, observable_kind, expression, priority, is_primary, reason_summary) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (MutantContextView.ObservableCandidateView observable : observables) {
                stmt.setLong(1, mutantRowId);
                if (observable.getMethodId() == null) {
                    stmt.setNull(2, java.sql.Types.BIGINT);
                } else {
                    stmt.setLong(2, observable.getMethodId().longValue());
                }
                stmt.setString(3, observable.getObservableKind());
                stmt.setString(4, observable.getExpression());
                stmt.setDouble(5, observable.getPriority());
                stmt.setInt(6, observable.isPrimary() ? 1 : 0);
                stmt.setString(7, observable.getReason());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceFieldObserverLinks(Connection connection, long mutantRowId,
                                          List<MutantContextView.FieldObserverLinkView> links) throws SQLException {
        deleteByMutant(connection, "DELETE FROM field_observer_links WHERE mutant_row_id = ?", mutantRowId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO field_observer_links(mutant_row_id, field_id, observer_method_id, observer_kind, distance, priority, reason_summary) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (MutantContextView.FieldObserverLinkView link : links) {
                stmt.setLong(1, mutantRowId);
                stmt.setLong(2, link.getFieldId());
                stmt.setLong(3, link.getObserverMethodId());
                stmt.setString(4, link.getObserverKind());
                stmt.setInt(5, link.getDistance());
                stmt.setDouble(6, link.getPriority());
                stmt.setString(7, link.getReason());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void replaceWitnessCandidates(Connection connection, long mutantRowId,
                                         List<MutantContextView.WitnessCandidateView> witnesses) throws SQLException {
        deleteByMutant(connection, "DELETE FROM mutant_witness_candidates WHERE mutant_row_id = ?", mutantRowId);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO mutant_witness_candidates(mutant_row_id, entry_method_id, observable_method_id, witness_rank, " +
                "receiver_setup_json, argument_setup_json, predicate_chain_json, expected_original_outcome, " +
                "expected_mutant_outcome, outcome_kind, assertion_sketch, reason_summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (MutantContextView.WitnessCandidateView witness : witnesses) {
                stmt.setLong(1, mutantRowId);
                if (witness.getEntryMethodId() == null) {
                    stmt.setNull(2, java.sql.Types.BIGINT);
                } else {
                    stmt.setLong(2, witness.getEntryMethodId().longValue());
                }
                if (witness.getObservableMethodId() == null) {
                    stmt.setNull(3, java.sql.Types.BIGINT);
                } else {
                    stmt.setLong(3, witness.getObservableMethodId().longValue());
                }
                stmt.setInt(4, witness.getWitnessRank());
                stmt.setString(5, witness.getReceiverSetupJson());
                stmt.setString(6, witness.getArgumentSetupJson());
                stmt.setString(7, witness.getPredicateChainJson());
                stmt.setString(8, witness.getExpectedOriginalOutcome());
                stmt.setString(9, witness.getExpectedMutantOutcome());
                stmt.setString(10, witness.getOutcomeKind());
                stmt.setString(11, witness.getAssertionSketch());
                stmt.setString(12, witness.getReason());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void deleteByMutant(Connection connection, String sql, long mutantRowId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, mutantRowId);
            stmt.executeUpdate();
        }
    }

    private boolean returnsObservableValue(MutantContextView.MethodLink entry) {
        String returnType = entry.getReturnType();
        return returnType != null && !returnType.trim().isEmpty() && !"void".equalsIgnoreCase(returnType.trim());
    }

    private double accessLevelScore(String visibility) {
        if ("public".equalsIgnoreCase(visibility)) {
            return 100.0;
        }
        if ("protected".equalsIgnoreCase(visibility)) {
            return 75.0;
        }
        return 50.0;
    }
}
