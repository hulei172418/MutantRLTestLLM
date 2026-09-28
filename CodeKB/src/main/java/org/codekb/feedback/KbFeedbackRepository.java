package org.codekb.feedback;

import org.codekb.model.MutantContextView;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class KbFeedbackRepository {
    public long insertEvent(Connection connection,
                            Long mutantRowId,
                            String mutantId,
                            String failureStage,
                            String rootCauseType,
                            String primaryFixTarget,
                            String secondaryFixTarget,
                            String symptom,
                            boolean autoRepairable,
                            String attributionWhy,
                            String signalLedgerJson,
                            double confidence,
                            String evidenceAction,
                            String failureReason,
                            String sourceRunId,
                            String createdAt) throws SQLException {
        String normalizedMutantId = safe(mutantId);
        String normalizedFailureStage = safe(failureStage);
        String normalizedRootCauseType = safe(rootCauseType);
        String normalizedPrimaryFixTarget = safe(primaryFixTarget);
        String normalizedSecondaryFixTarget = safe(secondaryFixTarget);
        String normalizedSymptom = safe(symptom);
        String normalizedAttributionWhy = safe(attributionWhy);
        String normalizedSignalLedgerJson = safe(signalLedgerJson);
        String normalizedEvidenceAction = safe(evidenceAction);
        String normalizedFailureReason = safe(failureReason);
        String normalizedSourceRunId = safe(sourceRunId);
        String normalizedCreatedAt = safe(createdAt);
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO kb_feedback_events (" +
                "mutant_row_id, mutant_id, failure_stage, root_cause_type, primary_fix_target, secondary_fix_target, " +
                "symptom, auto_repairable, attribution_why, signal_ledger_json, " +
                "confidence, evidence_action, failure_reason, source_run_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(mutant_id, failure_stage, root_cause_type, primary_fix_target, secondary_fix_target, " +
                "symptom, auto_repairable, attribution_why, signal_ledger_json, evidence_action, failure_reason, source_run_id) " +
                "DO UPDATE SET " +
                "mutant_row_id = COALESCE(excluded.mutant_row_id, kb_feedback_events.mutant_row_id), " +
                "confidence = CASE WHEN excluded.confidence > kb_feedback_events.confidence THEN excluded.confidence ELSE kb_feedback_events.confidence END, " +
                "created_at = CASE " +
                "WHEN kb_feedback_events.created_at IS NULL OR kb_feedback_events.created_at = '' THEN excluded.created_at " +
                "ELSE kb_feedback_events.created_at END")) {
            if (mutantRowId == null) {
                stmt.setNull(1, java.sql.Types.BIGINT);
            } else {
                stmt.setLong(1, mutantRowId.longValue());
            }
            stmt.setString(2, normalizedMutantId);
            stmt.setString(3, normalizedFailureStage);
            stmt.setString(4, normalizedRootCauseType);
            stmt.setString(5, normalizedPrimaryFixTarget);
            stmt.setString(6, normalizedSecondaryFixTarget);
            stmt.setString(7, normalizedSymptom);
            stmt.setInt(8, autoRepairable ? 1 : 0);
            stmt.setString(9, normalizedAttributionWhy);
            stmt.setString(10, normalizedSignalLedgerJson);
            stmt.setDouble(11, confidence);
            stmt.setString(12, normalizedEvidenceAction);
            stmt.setString(13, normalizedFailureReason);
            stmt.setString(14, normalizedSourceRunId);
            stmt.setString(15, normalizedCreatedAt);
            stmt.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT id FROM kb_feedback_events WHERE mutant_id = ? AND failure_stage = ? AND root_cause_type = ? " +
                "AND primary_fix_target = ? AND secondary_fix_target = ? AND symptom = ? AND auto_repairable = ? " +
                "AND attribution_why = ? AND signal_ledger_json = ? AND evidence_action = ? AND failure_reason = ? AND source_run_id = ?")) {
            query.setString(1, normalizedMutantId);
            query.setString(2, normalizedFailureStage);
            query.setString(3, normalizedRootCauseType);
            query.setString(4, normalizedPrimaryFixTarget);
            query.setString(5, normalizedSecondaryFixTarget);
            query.setString(6, normalizedSymptom);
            query.setInt(7, autoRepairable ? 1 : 0);
            query.setString(8, normalizedAttributionWhy);
            query.setString(9, normalizedSignalLedgerJson);
            query.setString(10, normalizedEvidenceAction);
            query.setString(11, normalizedFailureReason);
            query.setString(12, normalizedSourceRunId);
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to insert kb_feedback_event for mutant: " + mutantId);
    }

    public void insertCandidate(Connection connection,
                                long feedbackEventId,
                                String candidateKind,
                                String candidateValue,
                                String sourceKind,
                                String usageContext,
                                int priority,
                                String scopeType,
                                String scopeKey,
                                int supportCount,
                                boolean accepted,
                                String acceptedAt) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO kb_feedback_candidates (" +
                "feedback_event_id, candidate_kind, candidate_value, source_kind, usage_context, priority, " +
                "scope_type, scope_key, support_count, accepted, accepted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(feedback_event_id, candidate_kind, candidate_value, source_kind, usage_context, scope_type, scope_key) " +
                "DO UPDATE SET " +
                "priority = CASE WHEN excluded.priority > kb_feedback_candidates.priority THEN excluded.priority ELSE kb_feedback_candidates.priority END, " +
                "support_count = CASE WHEN excluded.support_count > kb_feedback_candidates.support_count THEN excluded.support_count ELSE kb_feedback_candidates.support_count END, " +
                "accepted = CASE WHEN excluded.accepted > kb_feedback_candidates.accepted THEN excluded.accepted ELSE kb_feedback_candidates.accepted END, " +
                "accepted_at = CASE " +
                "WHEN excluded.accepted = 1 AND (kb_feedback_candidates.accepted_at IS NULL OR kb_feedback_candidates.accepted_at = '') THEN excluded.accepted_at " +
                "ELSE kb_feedback_candidates.accepted_at END")) {
            stmt.setLong(1, feedbackEventId);
            stmt.setString(2, safe(candidateKind));
            stmt.setString(3, safe(candidateValue));
            stmt.setString(4, safe(sourceKind));
            stmt.setString(5, safe(usageContext));
            stmt.setInt(6, priority);
            stmt.setString(7, safe(scopeType));
            stmt.setString(8, safe(scopeKey));
            stmt.setInt(9, supportCount);
            stmt.setInt(10, accepted ? 1 : 0);
            stmt.setString(11, safe(acceptedAt));
            stmt.executeUpdate();
        }
    }

    public List<MutantContextView.CandidateImportView> loadAcceptedImportCandidates(Connection connection,
                                                                                    String mutantId)
        throws SQLException {
        List<MutantContextView.CandidateImportView> out = new ArrayList<MutantContextView.CandidateImportView>();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT c.candidate_value, COALESCE(c.source_kind, 'FEEDBACK_IMPORT'), COALESCE(c.usage_context, ''), " +
                "c.priority, COALESCE(c.scope_type, 'MUTANT'), COALESCE(c.scope_key, ''), e.confidence " +
                "FROM kb_feedback_candidates c " +
                "JOIN kb_feedback_events e ON c.feedback_event_id = e.id " +
                "WHERE e.mutant_id = ? AND c.candidate_kind = 'IMPORT' AND c.accepted = 1 " +
                "ORDER BY c.priority DESC, e.confidence DESC, c.candidate_value")) {
            stmt.setString(1, mutantId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int priority = rs.getInt(4);
                    double confidence = rs.getDouble(7);
                    out.add(new MutantContextView.CandidateImportView(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        priority > 0 ? priority : (int) Math.round(confidence * 100.0d),
                        rs.getString(5),
                        rs.getString(6),
                        "FEEDBACK_KB"
                    ));
                }
            }
        }
        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
