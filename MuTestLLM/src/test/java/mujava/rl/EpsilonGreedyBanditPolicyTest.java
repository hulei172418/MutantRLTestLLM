package mujava.rl;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class EpsilonGreedyBanditPolicyTest {
    @Test
    public void shouldUseWarmupActionWhenNoHistoryExists() throws Exception {
        Path dir = Files.createTempDirectory("bandit-warmup");
        PolicyStore store = new PolicyStore(dir.resolve("policy.json").toString());
        EpsilonGreedyBanditPolicy policy =
                new EpsilonGreedyBanditPolicy(0.0d, store, EvidenceAction.BASELINE, new Random(1L));

        Assert.assertEquals(EvidenceAction.BASELINE, policy.select(new EvidenceState()));
    }

    @Test
    public void shouldPickBestKnownActionWhenEpsilonIsZero() throws Exception {
        Path dir = Files.createTempDirectory("bandit-greedy");
        PolicyStore store = new PolicyStore(dir.resolve("policy.json").toString());
        store.updateGlobal(EvidenceAction.COMPILE_HEAVY, 1.0d);
        store.updateGlobal(EvidenceAction.ENTRY_HEAVY, 3.0d);
        store.save();

        EpsilonGreedyBanditPolicy policy =
                new EpsilonGreedyBanditPolicy(0.0d, store, EvidenceAction.BASELINE, new Random(1L));

        Assert.assertEquals(EvidenceAction.ENTRY_HEAVY, policy.select(new EvidenceState()));
    }

    @Test
    public void shouldPersistUpdatedAverageReward() throws Exception {
        Path dir = Files.createTempDirectory("bandit-update");
        PolicyStore store = new PolicyStore(dir.resolve("policy.json").toString());
        EpsilonGreedyBanditPolicy policy =
                new EpsilonGreedyBanditPolicy(0.0d, store, EvidenceAction.BASELINE, new Random(1L));

        policy.update(new EvidenceState(), EvidenceAction.ASSERTION_HEAVY, 2.0d);
        policy.update(new EvidenceState(), EvidenceAction.ASSERTION_HEAVY, 4.0d);

        PolicyStore reloaded = new PolicyStore(dir.resolve("policy.json").toString());
        reloaded.load();
        Assert.assertEquals(2L, reloaded.getGlobal(EvidenceAction.ASSERTION_HEAVY).count);
        Assert.assertEquals(3.0d, reloaded.getGlobal(EvidenceAction.ASSERTION_HEAVY).avgReward, 0.0001d);
    }

    @Test
    public void shouldPreferExactBucketWhenEnoughSamplesExist() throws Exception {
        Path dir = Files.createTempDirectory("bandit-exact");
        PolicyStore store = new PolicyStore(dir.resolve("policy.json").toString());
        store.updateGlobal(EvidenceAction.COMPILE_HEAVY, 10.0d);
        store.updateExact("ROR|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL", EvidenceAction.ASSERTION_HEAVY, 4.0d);
        store.updateExact("ROR|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL", EvidenceAction.ASSERTION_HEAVY, 6.0d);
        store.save();

        EpsilonGreedyBanditPolicy policy =
                new EpsilonGreedyBanditPolicy(0.0d, store, EvidenceAction.BASELINE, new Random(1L), 2, 2);
        EvidenceState state = new EvidenceState();
        state.exactBucketKey = "ROR|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL";
        state.coarseBucketKey = "ROR|RETURN_VALUE";

        Assert.assertEquals(EvidenceAction.ASSERTION_HEAVY, policy.select(state));
        Assert.assertEquals(FallbackLevel.EXACT.name(), state.lastFallbackLevel);
    }

    @Test
    public void shouldFallbackToGlobalWhenBucketSamplesAreInsufficient() throws Exception {
        Path dir = Files.createTempDirectory("bandit-global");
        PolicyStore store = new PolicyStore(dir.resolve("policy.json").toString());
        store.updateGlobal(EvidenceAction.COMPILE_HEAVY, 1.0d);
        store.updateGlobal(EvidenceAction.ENTRY_HEAVY, 3.0d);
        store.updateExact("ROR|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL", EvidenceAction.ASSERTION_HEAVY, 10.0d);
        store.save();

        EpsilonGreedyBanditPolicy policy =
                new EpsilonGreedyBanditPolicy(0.0d, store, EvidenceAction.BASELINE, new Random(1L), 2, 2);
        EvidenceState state = new EvidenceState();
        state.exactBucketKey = "ROR|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL";
        state.coarseBucketKey = "ROR|RETURN_VALUE";

        Assert.assertEquals(EvidenceAction.ENTRY_HEAVY, policy.select(state));
        Assert.assertEquals(FallbackLevel.GLOBAL.name(), state.lastFallbackLevel);
    }
}
