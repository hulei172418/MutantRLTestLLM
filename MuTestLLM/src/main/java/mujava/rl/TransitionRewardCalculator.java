package mujava.rl;

/**
 * Step reward based on RIP-like progress plus execution cost.
 */
public final class TransitionRewardCalculator {
    private TransitionRewardCalculator() {
    }

    public static RewardBreakdown calculate(RLStateSnapshot before, RLStateSnapshot after) {
        RewardBreakdown out = new RewardBreakdown();
        if (after != null
                && (mujava.testgenerator.tools.Result.STATUS_API_FAILED.equalsIgnoreCase(after.targetStatus)
                || mujava.testgenerator.tools.Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(after.targetStatus))) {
            // Provider/transport and pre-javac generation failures are exogenous to
            // evidence quality. Keep the transition for diagnostics, but make only
            // these failures reward-neutral; a real TASK_CRASHED remains penalizable.
            return out;
        }
        int beforeProgress = progress(before);
        int afterProgress = progress(after);
        double progressDelta = afterProgress - beforeProgress;

        if (after != null && after.compiled) {
            out.compileReward = afterProgress > beforeProgress ? Math.min(0.5d, Math.max(0.0d, progressDelta * 0.25d)) : 0.0d;
        }
        if (after != null && after.originalPassed && afterProgress >= 3 && beforeProgress < 3) {
            out.originalPassReward = 0.5d;
        }
        if (after != null && after.killed) {
            out.killReward = 7.0d;
        }
        if (after != null && after.phase == RLPhase.TARGET_LIVE && !after.killed) {
            out.survivedPenalty = -2.5d;
        }
        int repairRounds = after == null ? 0 : Math.max(0, after.repairRounds);
        int promptChars = after == null ? 0 : Math.max(0, after.promptChars);
        long elapsedMillis = after == null ? 0L : Math.max(0L, after.elapsedMillis);
        out.repairPenalty = -0.2d * repairRounds;
        out.promptCostPenalty = -0.0001d * promptChars;
        out.timePenalty = -0.001d * elapsedMillis / 1000.0d;
        if (after != null && !after.compiled) {
            out.compileFailurePenalty = -2.0d;
        }
        if (after != null && after.phase == RLPhase.ORIGINAL_FAILED) {
            out.originalFailurePenalty = -3.0d;
        }
        if (after != null && after.timedOut) {
            out.timeoutPenalty = -3.0d;
        }
        if (afterProgress < beforeProgress) {
            out.originalFailurePenalty += -1.0d;
        }
        out.totalReward = progressDelta
                + out.compileReward
                + out.originalPassReward
                + out.killReward
                + out.survivedPenalty
                + out.repairPenalty
                + out.promptCostPenalty
                + out.timePenalty
                + out.timeoutPenalty
                + out.compileFailurePenalty
                + out.originalFailurePenalty;
        return out;
    }

    private static int progress(RLStateSnapshot state) {
        if (state == null || state.phase == null) {
            return 0;
        }
        switch (state.phase) {
            case TARGET_KILLED:
                return 7;
            case TARGET_LIVE:
                return 2;
            case ORIGINAL_PASSED:
                return 3;
            case COMPILED:
                return 2;
            case GENERATED:
                return 1;
            case COMPILE_FAILED:
            case ORIGINAL_FAILED:
            case TIMEOUT:
            case CRASHED:
            case EQUIVALENT_SUSPECTED:
            case NOT_GENERATED:
            default:
                return 0;
        }
    }
}
