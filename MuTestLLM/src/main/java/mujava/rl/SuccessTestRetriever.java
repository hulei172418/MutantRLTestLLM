package mujava.rl;

import mujava.MutationSystem;
import mujava.testgenerator.tools.FileTextUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads prior RL trajectories and exposes compact historical experience.
 *
 * <p>The latest eligible record for each mutant is used, so a mutant processed
 * in multiple rounds is not over-counted. When outer-loop round metadata is
 * available, only earlier rounds are considered. Legacy trajectory lines that
 * do not contain a round are still accepted for backward compatibility.</p>
 */
public final class SuccessTestRetriever {
    /**
     * Per-JVM cache for prior-round trajectory snapshots. BatchLoop launches each
     * outer-loop round in a fresh JVM, so a before-round snapshot is immutable
     * for the lifetime of that process and can safely be reused by all mutants.
     */
    private static final Map<String, Map<String, JSONObject>> PRIOR_ROUND_CACHE =
            new HashMap<String, Map<String, JSONObject>>();

    public static final String RELATION_SAME_SITE = "SAME_SITE";
    public static final String RELATION_SAME_METHOD = "SAME_METHOD";
    public static final String RELATION_SAME_CLASS = "SAME_CLASS";

    private SuccessTestRetriever() {
    }

    public static HistoricalExperienceSnapshot inspect(EvidenceState state,
                                                       String logPath,
                                                       String resultModuleHome,
                                                       int maxReferences) {
        HistoricalExperienceSnapshot snapshot = new HistoricalExperienceSnapshot();
        if (state == null || isBlank(logPath)) {
            return snapshot;
        }
        Path path = Paths.get(logPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return snapshot;
        }

        Map<String, JSONObject> latestByMutant = readLatestEligibleRecords(path, state.outerLoopRound);
        JSONObject previous = latestByMutant.get(normalize(state.mutantId));
        if (previous != null) {
            snapshot.hasPreviousResult = true;
            snapshot.previousCompileSuccess = previous.optBoolean("compileSuccess", false);
            snapshot.previousOriginalPassed = previous.optBoolean("originalPass", false);
            snapshot.previousKilled = previous.optBoolean("killed", false);
            snapshot.previousRepairRounds = previous.optInt("repairRounds", 0);
            snapshot.previousTargetStatus = previous.optString("targetStatus", "");
            snapshot.previousFailureReason = previous.optString("failureReason", "");
        }

        List<ReferenceCandidate> candidates = new ArrayList<ReferenceCandidate>();
        for (JSONObject obj : latestByMutant.values()) {
            if (obj == null) {
                continue;
            }
            String mutantId = normalize(obj.optString("mutantId", ""));
            if (sameKey(state.mutantId, mutantId)) {
                continue;
            }
            if (!sameKey(state.project, obj.optString("project", ""))
                    || !sameKey(state.className, obj.optString("className", ""))) {
                continue;
            }

            JSONObject stateJson = obj.optJSONObject("state");
            String method = firstNonBlank(obj.optString("method", ""),
                    stateJson == null ? "" : stateJson.optString("method", ""));
            String line = firstNonBlank(obj.optString("line", ""),
                    stateJson == null ? "" : stateJson.optString("line", ""));
            boolean sameMethod = sameKey(state.method, method);
            boolean sameSite = sameMethod && !isBlank(state.line) && sameKey(state.line, line);
            boolean compiled = obj.optBoolean("compileSuccess", false);
            boolean originalPass = obj.optBoolean("originalPass", false);
            boolean killed = obj.optBoolean("killed", false);
            boolean successfulKill = compiled && originalPass && killed;

            snapshot.sameClassProcessedCount++;
            if (compiled) {
                snapshot.sameClassCompileSuccessCount++;
            }
            if (successfulKill) {
                snapshot.sameClassSuccessfulCount++;
            }
            if (sameMethod) {
                snapshot.sameMethodProcessedCount++;
                if (compiled) {
                    snapshot.sameMethodCompileSuccessCount++;
                }
                if (successfulKill) {
                    snapshot.sameMethodSuccessfulCount++;
                }
            }
            if (sameSite) {
                snapshot.sameSiteProcessedCount++;
                if (compiled) {
                    snapshot.sameSiteCompileSuccessCount++;
                }
                if (successfulKill) {
                    snapshot.sameSiteSuccessfulCount++;
                }
            }

            if (successfulKill && maxReferences > 0) {
                ReferenceCandidate candidate = buildCandidate(obj, state, stateJson, sameSite, sameMethod);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        snapshot.sameSiteCompileSuccessRate = rate(snapshot.sameSiteCompileSuccessCount, snapshot.sameSiteProcessedCount);
        snapshot.sameSiteKillRate = rate(snapshot.sameSiteSuccessfulCount, snapshot.sameSiteProcessedCount);
        snapshot.sameMethodCompileSuccessRate = rate(snapshot.sameMethodCompileSuccessCount, snapshot.sameMethodProcessedCount);
        snapshot.sameMethodKillRate = rate(snapshot.sameMethodSuccessfulCount, snapshot.sameMethodProcessedCount);
        snapshot.sameClassCompileSuccessRate = rate(snapshot.sameClassCompileSuccessCount, snapshot.sameClassProcessedCount);
        snapshot.sameClassKillRate = rate(snapshot.sameClassSuccessfulCount, snapshot.sameClassProcessedCount);

        Collections.sort(candidates, new Comparator<ReferenceCandidate>() {
            @Override
            public int compare(ReferenceCandidate a, ReferenceCandidate b) {
                int cmp = Double.compare(b.reference.similarityScore, a.reference.similarityScore);
                if (cmp != 0) {
                    return cmp;
                }
                return Double.compare(b.reference.reward, a.reference.reward);
            }
        });

        Map<String, SuccessTestReference> unique = new LinkedHashMap<String, SuccessTestReference>();
        for (ReferenceCandidate candidate : candidates) {
            if (unique.size() >= maxReferences) {
                break;
            }
            SuccessTestReference ref = materializeReference(candidate.reference, resultModuleHome);
            if (ref == null || isBlank(ref.testName) || unique.containsKey(ref.testName)) {
                continue;
            }
            unique.put(ref.testName, ref);
        }
        for (SuccessTestReference ref : unique.values()) {
            snapshot.addReference(ref);
        }
        return snapshot;
    }

    /**
     * New general retrieval API used by initial generation and compile repair.
     * Same-site references rank first, then same-method, then same-class.
     */
    public static List<SuccessTestReference> findRelevantSuccessfulTests(EvidenceState state,
                                                                         String logPath,
                                                                         String resultModuleHome,
                                                                         int maxReferences) {
        return new ArrayList<SuccessTestReference>(
                inspect(state, logPath, resultModuleHome, maxReferences).getReferences());
    }

    /**
     * Backward-compatible API for target-kill regeneration. It intentionally
     * keeps only same-method references, including the stronger same-site case.
     */
    public static List<SuccessTestReference> findSameMethodSuccessfulTests(EvidenceState state,
                                                                           String logPath,
                                                                           String resultModuleHome,
                                                                           int maxReferences) {
        if (maxReferences <= 0) {
            return Collections.emptyList();
        }
        HistoricalExperienceSnapshot snapshot = inspect(
                state, logPath, resultModuleHome, Math.max(maxReferences * 3, maxReferences));
        List<SuccessTestReference> out = new ArrayList<SuccessTestReference>();
        for (SuccessTestReference ref : snapshot.getReferences()) {
            if (RELATION_SAME_SITE.equals(ref.relation) || RELATION_SAME_METHOD.equals(ref.relation)) {
                out.add(ref);
                if (out.size() >= maxReferences) {
                    break;
                }
            }
        }
        return out;
    }

    private static Map<String, JSONObject> readLatestEligibleRecords(Path path, int currentOuterLoopRound) {
        // For round > 0, the eligible history is fixed: only records before the
        // current round may be observed. Cache it once instead of re-reading a
        // growing trajectory file for every mutant. Round 0 intentionally is not
        // cached so legacy single-round workflows keep their original behavior.
        String cacheKey = null;
        if (currentOuterLoopRound > 0) {
            cacheKey = path.toAbsolutePath().normalize().toString() + "#beforeRound=" + currentOuterLoopRound;
            synchronized (PRIOR_ROUND_CACHE) {
                Map<String, JSONObject> cached = PRIOR_ROUND_CACHE.get(cacheKey);
                if (cached != null) {
                    return cached;
                }
            }
        }

        Map<String, JSONObject> latest = new LinkedHashMap<String, JSONObject>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (isBlank(line)) {
                    continue;
                }
                try {
                    JSONObject obj = new JSONObject(line);
                    int recordRound = recordOuterLoopRound(obj);
                    if (currentOuterLoopRound > 0 && recordRound > 0 && recordRound >= currentOuterLoopRound) {
                        continue;
                    }
                    String mutantId = normalize(obj.optString("mutantId", ""));
                    if (mutantId.isEmpty()) {
                        mutantId = fallbackMutantKey(obj);
                    }
                    if (!mutantId.isEmpty()) {
                        latest.put(mutantId, obj);
                    }
                } catch (Exception ignored) {
                    // A partially written or legacy malformed line must not break a run.
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyMap();
        }

        if (cacheKey != null) {
            Map<String, JSONObject> immutable = Collections.unmodifiableMap(
                    new LinkedHashMap<String, JSONObject>(latest));
            synchronized (PRIOR_ROUND_CACHE) {
                PRIOR_ROUND_CACHE.put(cacheKey, immutable);
            }
            return immutable;
        }
        return latest;
    }

    private static int recordOuterLoopRound(JSONObject obj) {
        if (obj == null) {
            return 0;
        }
        int round = obj.optInt("outerLoopRound", 0);
        if (round > 0) {
            return round;
        }
        JSONObject state = obj.optJSONObject("state");
        return state == null ? 0 : state.optInt("outerLoopRound", 0);
    }

    private static ReferenceCandidate buildCandidate(JSONObject obj,
                                                     EvidenceState target,
                                                     JSONObject stateJson,
                                                     boolean sameSite,
                                                     boolean sameMethod) {
        String testName = obj.optString("testName", "").trim();
        if (testName.isEmpty()) {
            return null;
        }
        SuccessTestReference ref = new SuccessTestReference();
        ref.mutantId = obj.optString("mutantId", "");
        ref.testName = testName;
        ref.action = obj.optString("action", "");
        ref.reward = obj.optDouble("reward", 0.0d);
        ref.operator = obj.optString("operator", "");
        ref.sourceClassName = obj.optString("className", "");
        ref.sourceMethod = firstNonBlank(obj.optString("method", ""),
                stateJson == null ? "" : stateJson.optString("method", ""));
        ref.line = firstNonBlank(obj.optString("line", ""),
                stateJson == null ? "" : stateJson.optString("line", ""));
        ref.observablePlanKind = stateJson == null ? "" : stateJson.optString("observablePlanKind", "");
        ref.testEntryKind = stateJson == null ? "" : stateJson.optString("testEntryKind", "");
        ref.entryInvocationKind = stateJson == null ? "" : stateJson.optString("entryInvocationKind", "");
        ref.relation = sameSite ? RELATION_SAME_SITE : (sameMethod ? RELATION_SAME_METHOD : RELATION_SAME_CLASS);
        ref.similarityScore = similarityScore(target, ref);
        return new ReferenceCandidate(ref);
    }

    private static SuccessTestReference materializeReference(SuccessTestReference ref, String resultModuleHome) {
        if (ref == null || isBlank(resultModuleHome) || isBlank(ref.testName)) {
            return null;
        }
        try {
            Path javaFile = Paths.get(resultModuleHome, MutationSystem.TESTSET_MODE_LLMS, "src")
                    .resolve(ref.testName.replace('.', java.io.File.separatorChar) + ".java")
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(javaFile)) {
                return null;
            }
            ref.testJavaFile = javaFile.toString();
            ref.code = clip(FileTextUtils.readUtf8(javaFile), 6000);
            return isBlank(ref.code) ? null : ref;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double similarityScore(EvidenceState state, SuccessTestReference ref) {
        if (state == null || ref == null) {
            return 0.0d;
        }
        double score;
        if (RELATION_SAME_SITE.equals(ref.relation)) {
            score = 100.0d;
        } else if (RELATION_SAME_METHOD.equals(ref.relation)) {
            score = 50.0d;
        } else {
            score = 10.0d;
        }
        if (sameOperatorFamily(state.operator, ref.operator)) {
            score += 8.0d;
        }
        if (sameKey(state.operator, ref.operator)) {
            score += 4.0d;
        }
        if (sameKey(state.observablePlanKind, ref.observablePlanKind)) {
            score += 5.0d;
        }
        if (sameKey(state.testEntryKind, ref.testEntryKind)) {
            score += 2.0d;
        }
        if (sameKey(state.entryInvocationKind, ref.entryInvocationKind)) {
            score += 2.0d;
        }
        score += Math.min(5.0d, Math.max(0.0d, ref.reward));
        return score;
    }

    private static boolean sameOperatorFamily(String a, String b) {
        return operatorFamily(a).equals(operatorFamily(b));
    }

    private static String operatorFamily(String value) {
        String text = normalize(value);
        int idx = text.indexOf('_');
        return idx > 0 ? text.substring(0, idx) : text;
    }

    private static String fallbackMutantKey(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        return normalize(obj.optString("project", "")) + "::"
                + normalize(obj.optString("className", "")) + "::"
                + normalize(obj.optString("method", "")) + "::"
                + normalize(obj.optString("operator", ""));
    }

    private static boolean sameKey(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a.trim() : (b == null ? "" : b.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String clip(String text, int maxChars) {
        String normalized = text == null ? "" : text;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars)) + "\n// ... truncated reference ...\n";
    }

    private static double rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, numerator * 1.0d / denominator));
    }

    private static final class ReferenceCandidate {
        final SuccessTestReference reference;

        ReferenceCandidate(SuccessTestReference reference) {
            this.reference = reference;
        }
    }
}
