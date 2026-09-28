package mujava.rl;

import org.junit.Assert;
import org.junit.Test;

public class RewardBuilderTest {
    @Test
    public void shouldRewardKilledSuccessfulRun() {
        RewardBuilder.ResultContext ctx = new RewardBuilder.ResultContext();
        ctx.compileSuccess = true;
        ctx.originalPass = true;
        ctx.killed = true;
        ctx.repairRounds = 1;
        ctx.promptChars = 100;
        ctx.elapsedMillis = 2000L;

        double reward = RewardBuilder.build(ctx);
        Assert.assertEquals(6.788d, reward, 0.0001d);
        RewardBreakdown breakdown = RewardBuilder.buildBreakdown(ctx);
        Assert.assertEquals(1.0d, breakdown.compileReward, 0.0001d);
        Assert.assertEquals(1.0d, breakdown.originalPassReward, 0.0001d);
        Assert.assertEquals(5.0d, breakdown.killReward, 0.0001d);
        Assert.assertEquals(-0.2d, breakdown.repairPenalty, 0.0001d);
        Assert.assertEquals(-0.01d, breakdown.promptCostPenalty, 0.0001d);
        Assert.assertEquals(-0.002d, breakdown.timePenalty, 0.0001d);
        Assert.assertEquals(6.788d, breakdown.totalReward, 0.0001d);
    }

    @Test
    public void shouldPenalizeCompileFailureOriginalFailureAndTimeout() {
        RewardBuilder.ResultContext ctx = new RewardBuilder.ResultContext();
        ctx.compileSuccess = false;
        ctx.originalPass = false;
        ctx.killed = false;
        ctx.repairRounds = 2;
        ctx.promptChars = 500;
        ctx.elapsedMillis = 1000L;
        ctx.timeout = true;
        ctx.originalFailed = true;

        double reward = RewardBuilder.build(ctx);
        Assert.assertEquals(-8.451d, reward, 0.0001d);
    }
}
