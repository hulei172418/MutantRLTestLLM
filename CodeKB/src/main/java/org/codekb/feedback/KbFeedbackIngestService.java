package org.codekb.feedback;

import org.codekb.config.KbConfig;
import org.codekb.model.CanonicalMutantId;
import org.codekb.query.KnowledgeQueryService;
import org.codekb.store.SchemaInitializer;
import org.codekb.store.SqliteConnectionFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public final class KbFeedbackIngestService {
    private final SqliteConnectionFactory connectionFactory = new SqliteConnectionFactory();
    private final KbFeedbackRepository repository = new KbFeedbackRepository();

    public Summary ingest(KbConfig config, Path feedbackJsonl, boolean acceptAll) throws SQLException, IOException {
        return ingest(config, feedbackJsonl, acceptAll, 0.0d, false);
    }

    public Summary ingest(KbConfig config,
                          Path feedbackJsonl,
                          boolean acceptAll,
                          double minConfidence,
                          boolean autoRepairableOnly) throws SQLException, IOException {
        Summary summary = new Summary();
        try (Connection connection = connectionFactory.open(config)) {
            new SchemaInitializer().initialize(connection);
            List<String> lines = Files.readAllLines(feedbackJsonl, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JSONObject root = new JSONObject(trimmed);
                JSONObject analysis = root.optJSONObject("failureAnalysis");
                if (analysis == null) {
                    continue;
                }
                String primaryFixTarget = analysis.optString("primaryFixTarget", "");
                if (!"CODEKB".equalsIgnoreCase(primaryFixTarget)) {
                    summary.skippedNonCodeKb++;
                    continue;
                }
                double confidence = analysis.optDouble("confidence", 0.0d);
                boolean autoRepairable = analysis.optBoolean("autoRepairable", false);
                if (confidence < minConfidence || (autoRepairableOnly && !autoRepairable)) {
                    summary.skippedLowConfidence++;
                    continue;
                }
                String mutantId = CanonicalMutantId.normalize(root.optString("mutantId", ""));
                JSONObject signalLedger = analysis.optJSONObject("signalLedger");
                Long mutantRowId = new KnowledgeQueryService().findMutantRowId(connection, mutantId);
                long eventId = repository.insertEvent(
                    connection,
                    mutantRowId,
                    mutantId,
                    analysis.optString("failureStage", ""),
                    analysis.optString("rootCauseType", ""),
                    primaryFixTarget,
                    analysis.optString("secondaryFixTarget", ""),
                    analysis.optString("symptom", root.optString("failureSymptom", "")),
                    autoRepairable,
                    analysis.optString("why", root.optString("attributionWhy", "")),
                    signalLedger == null ? "" : signalLedger.toString(),
                    confidence,
                    root.optString("action", ""),
                    root.optString("failureReason", ""),
                    root.optString("taskId", ""),
                    root.optString("generatedAt", Instant.now().toString())
                );
                summary.eventCount++;
                summary.candidateCount += insertImportCandidates(
                    connection,
                    eventId,
                    analysis.optJSONArray("suspectedMissingImports"),
                    acceptAll
                );
            }
        }
        return summary;
    }

    private int insertImportCandidates(Connection connection,
                                       long eventId,
                                       JSONArray imports,
                                       boolean acceptAll) throws SQLException {
        if (imports == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < imports.length(); i++) {
            String importValue = String.valueOf(imports.opt(i)).trim();
            if (importValue.isEmpty()) {
                continue;
            }
            repository.insertCandidate(
                connection,
                eventId,
                "IMPORT",
                importValue,
                "FEEDBACK_IMPORT",
                "Recovered from failure-feedback.jsonl compile diagnosis",
                95,
                "MUTANT",
                "",
                1,
                acceptAll,
                acceptAll ? Instant.now().toString() : ""
            );
            count++;
        }
        return count;
    }

    public static final class Summary {
        private int eventCount;
        private int candidateCount;
        private int skippedNonCodeKb;
        private int skippedLowConfidence;

        public int getEventCount() {
            return eventCount;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public int getSkippedNonCodeKb() {
            return skippedNonCodeKb;
        }

        public int getSkippedLowConfidence() {
            return skippedLowConfidence;
        }
    }
}
