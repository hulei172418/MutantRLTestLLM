package mujava.rl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;

/**
 * Global epsilon-greedy policy over discrete evidence profiles.
 */
public final class EpsilonGreedyBanditPolicy implements BanditPolicy {
    private final double epsilon;
    private final PolicyStore store;
    private final EvidenceAction warmupAction;
    private final Random random;
    private final int minSamplesExact;
    private final int minSamplesCoarse;

    public EpsilonGreedyBanditPolicy(double epsilon, PolicyStore store, EvidenceAction warmupAction) {
        this(epsilon, store, warmupAction, new Random(), 1, 1);
    }

    EpsilonGreedyBanditPolicy(double epsilon, PolicyStore store, EvidenceAction warmupAction, Random random) {
        this(epsilon, store, warmupAction, random, 1, 1);
    }

    public EpsilonGreedyBanditPolicy(double epsilon,
                                     PolicyStore store,
                                     EvidenceAction warmupAction,
                                     int minSamplesExact,
                                     int minSamplesCoarse) {
        this(epsilon, store, warmupAction, new Random(), minSamplesExact, minSamplesCoarse);
    }

    EpsilonGreedyBanditPolicy(double epsilon,
                              PolicyStore store,
                              EvidenceAction warmupAction,
                              Random random,
                              int minSamplesExact,
                              int minSamplesCoarse) {
        if (epsilon < 0.0d || epsilon > 1.0d) {
            throw new IllegalArgumentException("epsilon must be in [0,1]");
        }
        this.epsilon = epsilon;
        this.store = store;
        this.warmupAction = warmupAction == null ? EvidenceAction.BASELINE : warmupAction;
        this.random = random == null ? new Random() : random;
        this.minSamplesExact = Math.max(1, minSamplesExact);
        this.minSamplesCoarse = Math.max(1, minSamplesCoarse);
        this.store.load();
    }

    @Override
    public synchronized EvidenceAction select(EvidenceState state) {
        if (allUnseen()) {
            markFallback(state, FallbackLevel.WARMUP);
            return warmupAction;
        }
        EnumMap<EvidenceAction, PolicyStore.ActionStat> stats = resolveStats(state);
        if (random.nextDouble() < epsilon) {
            EvidenceAction[] actions = EvidenceAction.values();
            return actions[random.nextInt(actions.length)];
        }
        return bestAction(stats);
    }

    @Override
    public synchronized void update(EvidenceState state, EvidenceAction action, double reward) {
        store.updateGlobal(action, reward);
        if (state != null && state.coarseBucketKey != null && !state.coarseBucketKey.trim().isEmpty()) {
            store.updateCoarse(state.coarseBucketKey, action, reward);
        }
        if (state != null && state.exactBucketKey != null && !state.exactBucketKey.trim().isEmpty()) {
            store.updateExact(state.exactBucketKey, action, reward);
        }
        store.save();
    }

    synchronized List<EvidenceAction> seenActions() {
        List<EvidenceAction> seen = new ArrayList<EvidenceAction>();
        for (EvidenceAction action : EvidenceAction.values()) {
            if (store.getGlobal(action).count > 0L) {
                seen.add(action);
            }
        }
        return seen;
    }

    private boolean allUnseen() {
        return seenActions().isEmpty();
    }

    private EnumMap<EvidenceAction, PolicyStore.ActionStat> resolveStats(EvidenceState state) {
        if (state != null) {
            String exactKey = state.exactBucketKey == null ? "" : state.exactBucketKey.trim();
            if (!exactKey.isEmpty() && store.getExactSampleCount(exactKey) >= minSamplesExact) {
                markFallback(state, FallbackLevel.EXACT);
                return store.getExactStats(exactKey);
            }
            String coarseKey = state.coarseBucketKey == null ? "" : state.coarseBucketKey.trim();
            if (!coarseKey.isEmpty() && store.getCoarseSampleCount(coarseKey) >= minSamplesCoarse) {
                markFallback(state, FallbackLevel.COARSE);
                return store.getCoarseStats(coarseKey);
            }
        }
        markFallback(state, FallbackLevel.GLOBAL);
        return store.getGlobalStats();
    }

    private static EvidenceAction bestAction(EnumMap<EvidenceAction, PolicyStore.ActionStat> stats) {
        EvidenceAction bestAction = EvidenceAction.BASELINE;
        double bestReward = Double.NEGATIVE_INFINITY;
        for (EvidenceAction action : EvidenceAction.values()) {
            PolicyStore.ActionStat stat = stats.get(action);
            if (stat == null || stat.count <= 0L) {
                continue;
            }
            if (stat.avgReward > bestReward) {
                bestReward = stat.avgReward;
                bestAction = action;
            }
        }
        return bestReward == Double.NEGATIVE_INFINITY ? EvidenceAction.BASELINE : bestAction;
    }

    private static void markFallback(EvidenceState state, FallbackLevel level) {
        if (state != null && level != null) {
            state.lastFallbackLevel = level.name();
        }
    }
}
