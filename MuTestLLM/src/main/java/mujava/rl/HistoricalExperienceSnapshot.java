package mujava.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated experience from earlier outer-loop rounds for the current mutant.
 *
 * <p>This object deliberately contains only compact statistics plus a small
 * number of successful sibling tests. It is Java 8 compatible and can be used
 * both as LinUCB context and as LLM prompt-side reference evidence.</p>
 */
public final class HistoricalExperienceSnapshot {
    public boolean hasPreviousResult;
    public boolean previousCompileSuccess;
    public boolean previousOriginalPassed;
    public boolean previousKilled;
    public int previousRepairRounds;
    public String previousTargetStatus = "";
    public String previousFailureReason = "";

    public int sameSiteProcessedCount;
    public int sameSiteCompileSuccessCount;
    public int sameSiteSuccessfulCount;
    public double sameSiteCompileSuccessRate;
    public double sameSiteKillRate;

    public int sameMethodProcessedCount;
    public int sameMethodCompileSuccessCount;
    public int sameMethodSuccessfulCount;
    public double sameMethodCompileSuccessRate;
    public double sameMethodKillRate;

    public int sameClassProcessedCount;
    public int sameClassCompileSuccessCount;
    public int sameClassSuccessfulCount;
    public double sameClassCompileSuccessRate;
    public double sameClassKillRate;

    public double bestReferenceSimilarity;

    private final List<SuccessTestReference> references = new ArrayList<SuccessTestReference>();

    public List<SuccessTestReference> getReferences() {
        return Collections.unmodifiableList(references);
    }

    void addReference(SuccessTestReference reference) {
        if (reference != null) {
            references.add(reference);
            bestReferenceSimilarity = Math.max(bestReferenceSimilarity, reference.similarityScore);
        }
    }

    public void applyTo(EvidenceState state) {
        if (state == null) {
            return;
        }
        state.hasPreviousResult = hasPreviousResult;
        state.previousCompileSuccess = previousCompileSuccess;
        state.previousOriginalPassed = previousOriginalPassed;
        state.previousKilled = previousKilled;
        state.previousRepairRounds = previousRepairRounds;
        state.previousTargetStatus = previousTargetStatus == null ? "" : previousTargetStatus;
        state.previousFailureReason = previousFailureReason == null ? "" : previousFailureReason;

        state.sameSiteProcessedHistoryCount = sameSiteProcessedCount;
        state.sameSiteCompileSuccessCount = sameSiteCompileSuccessCount;
        state.sameSiteSuccessfulCount = sameSiteSuccessfulCount;
        state.sameSiteCompileSuccessRate = sameSiteCompileSuccessRate;
        state.sameSiteKillRate = sameSiteKillRate;

        state.sameMethodProcessedHistoryCount = sameMethodProcessedCount;
        state.sameMethodCompileSuccessCount = sameMethodCompileSuccessCount;
        state.sameMethodSuccessfulCount = sameMethodSuccessfulCount;
        state.sameMethodCompileSuccessRate = sameMethodCompileSuccessRate;
        state.sameMethodKillRate = sameMethodKillRate;

        state.sameClassProcessedHistoryCount = sameClassProcessedCount;
        state.sameClassCompileSuccessCount = sameClassCompileSuccessCount;
        state.sameClassSuccessfulCount = sameClassSuccessfulCount;
        state.sameClassCompileSuccessRate = sameClassCompileSuccessRate;
        state.sameClassKillRate = sameClassKillRate;

        state.hasSuccessfulReference = !references.isEmpty();
        state.successfulReferenceCount = references.size();
        state.bestSuccessfulReferenceSimilarity = bestReferenceSimilarity;
    }
}
