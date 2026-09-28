package mujava.rl;

/**
 * Maps generation and execution feedback into one scalar reward.
 */
public final class RewardBuilder {
    private RewardBuilder() {
    }

    public static final class ResultContext {
        public boolean compileSuccess;
        public boolean originalPass;
        public boolean killed;
        public int repairRounds;
        public int promptChars;
        public long elapsedMillis;
        public boolean timeout;
        public boolean originalFailed;
    }

    public static double build(ResultContext ctx) {
        return buildBreakdown(ctx).totalReward;
    }

    public static RewardBreakdown buildBreakdown(ResultContext ctx) {
        RewardBreakdown breakdown = new RewardBreakdown();
        if (ctx == null) {
            return breakdown;
        }
        if (ctx.killed) {
            breakdown.killReward = 7.0d;
        }
        if (ctx.compileSuccess) {
            breakdown.compileReward = 0.5d;
        }
        if (ctx.originalPass) {
            breakdown.originalPassReward = 0.5d;
        }
        if (ctx.compileSuccess && ctx.originalPass && !ctx.killed && !ctx.timeout) {
            breakdown.survivedPenalty = -2.5d;
        }
        breakdown.repairPenalty = -0.2d * Math.max(0, ctx.repairRounds);
        breakdown.promptCostPenalty = -0.0001d * Math.max(0, ctx.promptChars);
        breakdown.timePenalty = -0.001d * Math.max(0L, ctx.elapsedMillis) / 1000.0d;

        if (!ctx.compileSuccess) {
            breakdown.compileFailurePenalty = -4.0d;
        }
        if (ctx.originalFailed) {
            breakdown.originalFailurePenalty = -4.0d;
        }
        if (ctx.timeout) {
            breakdown.timeoutPenalty = -3.0d;
        }
        breakdown.totalReward = breakdown.compileReward
                + breakdown.originalPassReward
                + breakdown.killReward
                + breakdown.survivedPenalty
                + breakdown.repairPenalty
                + breakdown.promptCostPenalty
                + breakdown.timePenalty
                + breakdown.timeoutPenalty
                + breakdown.compileFailurePenalty
                + breakdown.originalFailurePenalty;
        return breakdown;
    }
}
