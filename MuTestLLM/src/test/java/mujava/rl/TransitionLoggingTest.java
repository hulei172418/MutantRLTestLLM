package mujava.rl;

import mujava.testgenerator.tools.Result;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransitionLoggingTest {
    @Test
    public void actionMaskKeepsDqnOutOfCompileFailureState() {
        assertTrue(ActionMasker.isAllowed(RLPhase.COMPILE_FAILED, RLAction.TERMINATE));
        assertFalse(ActionMasker.isAllowed(RLPhase.COMPILE_FAILED, RLAction.COMPILE_REPAIR));
        assertFalse(ActionMasker.isAllowed(RLPhase.COMPILE_FAILED, RLAction.CODEKB_KNOWLEDGE_REPAIR));
        assertFalse(ActionMasker.isAllowed(RLPhase.COMPILE_FAILED, RLAction.ASSERTION_STRENGTHEN));
    }

    @Test
    public void rewardIncreasesWhenLiveTestKillsMutant() {
        EvidenceState state = new EvidenceState();
        state.mutantId = "project::Class::method()::ROR_1";

        Result live = new Result();
        live.compiled = true;
        live.originalPassed = true;
        live.killed = false;
        live.targetStatus = Result.STATUS_SURVIVED;

        Result killed = new Result();
        killed.compiled = true;
        killed.originalPassed = true;
        killed.killed = true;
        killed.targetStatus = Result.STATUS_KILLED;

        RewardBreakdown reward = TransitionRewardCalculator.calculate(
                RLStateSnapshot.from(state, live, 0),
                RLStateSnapshot.from(state, killed, 1));

        assertTrue(reward.totalReward > 0.0d);
        assertTrue(reward.killReward > 0.0d);
    }
}
