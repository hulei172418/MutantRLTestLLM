package mujava.rl;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * File-backed action statistics for global/coarse/exact bucketed bandit policy.
 */
public final class PolicyStore {
    public static final class ActionStat {
        public long count;
        public double avgReward;

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("count", count);
            obj.put("avgReward", avgReward);
            return obj;
        }
    }

    private final Path file;
    private final EnumMap<EvidenceAction, ActionStat> globalStats = newStatsMap();
    private final Map<String, EnumMap<EvidenceAction, ActionStat>> coarseBuckets =
            new LinkedHashMap<String, EnumMap<EvidenceAction, ActionStat>>();
    private final Map<String, EnumMap<EvidenceAction, ActionStat>> exactBuckets =
            new LinkedHashMap<String, EnumMap<EvidenceAction, ActionStat>>();

    public PolicyStore(String filePath) {
        this.file = Paths.get(filePath).toAbsolutePath().normalize();
    }

    public synchronized void load() {
        reset();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            if (looksLikeLegacyFlatStore(root)) {
                loadStatsInto(globalStats, root);
                return;
            }
            loadStatsInto(globalStats, root.optJSONObject("global"));
            loadBucketMap(coarseBuckets, root.optJSONObject("coarseBuckets"));
            loadBucketMap(exactBuckets, root.optJSONObject("exactBuckets"));
        } catch (Throwable ignored) {
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JSONObject root = new JSONObject();
            root.put("global", toJson(globalStats));
            root.put("coarseBuckets", toJson(coarseBuckets));
            root.put("exactBuckets", toJson(exactBuckets));
            Files.write(file, root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save bandit policy store: " + file, e);
        }
    }

    public synchronized ActionStat get(EvidenceAction action) {
        return getGlobal(action);
    }

    public synchronized ActionStat getGlobal(EvidenceAction action) {
        return globalStats.get(action);
    }

    public synchronized ActionStat getCoarse(String bucketKey, EvidenceAction action) {
        return getBucketStats(coarseBuckets, bucketKey).get(action);
    }

    public synchronized ActionStat getExact(String bucketKey, EvidenceAction action) {
        return getBucketStats(exactBuckets, bucketKey).get(action);
    }

    public synchronized EnumMap<EvidenceAction, ActionStat> getGlobalStats() {
        return globalStats;
    }

    public synchronized EnumMap<EvidenceAction, ActionStat> getCoarseStats(String bucketKey) {
        return getBucketStats(coarseBuckets, bucketKey);
    }

    public synchronized EnumMap<EvidenceAction, ActionStat> getExactStats(String bucketKey) {
        return getBucketStats(exactBuckets, bucketKey);
    }

    public synchronized long getGlobalSampleCount() {
        return sampleCount(globalStats);
    }

    public synchronized long getCoarseSampleCount(String bucketKey) {
        return sampleCount(getBucketStats(coarseBuckets, bucketKey));
    }

    public synchronized long getExactSampleCount(String bucketKey) {
        return sampleCount(getBucketStats(exactBuckets, bucketKey));
    }

    public synchronized void updateGlobal(EvidenceAction action, double reward) {
        updateStat(globalStats.get(action), reward);
    }

    public synchronized void updateCoarse(String bucketKey, EvidenceAction action, double reward) {
        updateStat(getBucketStats(coarseBuckets, bucketKey).get(action), reward);
    }

    public synchronized void updateExact(String bucketKey, EvidenceAction action, double reward) {
        updateStat(getBucketStats(exactBuckets, bucketKey).get(action), reward);
    }

    private void reset() {
        globalStats.clear();
        globalStats.putAll(newStatsMap());
        coarseBuckets.clear();
        exactBuckets.clear();
    }

    private static boolean looksLikeLegacyFlatStore(JSONObject root) {
        if (root == null) {
            return false;
        }
        for (EvidenceAction action : EvidenceAction.values()) {
            if (root.has(action.name())) {
                return true;
            }
        }
        return false;
    }

    private static void loadBucketMap(Map<String, EnumMap<EvidenceAction, ActionStat>> target, JSONObject root) {
        if (root == null) {
            return;
        }
        for (String key : root.keySet()) {
            EnumMap<EvidenceAction, ActionStat> stats = newStatsMap();
            loadStatsInto(stats, root.optJSONObject(key));
            target.put(key, stats);
        }
    }

    private static void loadStatsInto(EnumMap<EvidenceAction, ActionStat> target, JSONObject root) {
        if (target == null || root == null) {
            return;
        }
        for (EvidenceAction action : EvidenceAction.values()) {
            JSONObject item = root.optJSONObject(action.name());
            if (item == null) {
                continue;
            }
            ActionStat stat = target.get(action);
            stat.count = item.optLong("count", 0L);
            stat.avgReward = item.optDouble("avgReward", 0.0d);
        }
    }

    private static JSONObject toJson(EnumMap<EvidenceAction, ActionStat> stats) {
        JSONObject root = new JSONObject();
        for (Map.Entry<EvidenceAction, ActionStat> entry : stats.entrySet()) {
            root.put(entry.getKey().name(), entry.getValue().toJson());
        }
        return root;
    }

    private static JSONObject toJson(Map<String, EnumMap<EvidenceAction, ActionStat>> buckets) {
        JSONObject root = new JSONObject();
        for (Map.Entry<String, EnumMap<EvidenceAction, ActionStat>> entry : buckets.entrySet()) {
            root.put(entry.getKey(), toJson(entry.getValue()));
        }
        return root;
    }

    private static EnumMap<EvidenceAction, ActionStat> newStatsMap() {
        EnumMap<EvidenceAction, ActionStat> map = new EnumMap<EvidenceAction, ActionStat>(EvidenceAction.class);
        for (EvidenceAction action : EvidenceAction.values()) {
            map.put(action, new ActionStat());
        }
        return map;
    }

    private static long sampleCount(EnumMap<EvidenceAction, ActionStat> stats) {
        long total = 0L;
        if (stats == null) {
            return total;
        }
        for (ActionStat stat : stats.values()) {
            total += stat.count;
        }
        return total;
    }

    private static void updateStat(ActionStat stat, double reward) {
        long newCount = stat.count + 1L;
        stat.avgReward = ((stat.avgReward * stat.count) + reward) / newCount;
        stat.count = newCount;
    }

    private static EnumMap<EvidenceAction, ActionStat> getBucketStats(Map<String, EnumMap<EvidenceAction, ActionStat>> buckets,
                                                                      String bucketKey) {
        String key = bucketKey == null ? "" : bucketKey.trim();
        EnumMap<EvidenceAction, ActionStat> stats = buckets.get(key);
        if (stats == null) {
            stats = newStatsMap();
            buckets.put(key, stats);
        }
        return stats;
    }
}
