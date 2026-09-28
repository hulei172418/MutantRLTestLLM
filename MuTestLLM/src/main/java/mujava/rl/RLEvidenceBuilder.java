package mujava.rl;

import org.json.JSONArray;
import org.json.JSONObject;

import mujava.testgenerator.tools.PromptEvidence;

/**
 * Builds compact prompt evidence profiles from the existing compact baseline.
 */
public final class RLEvidenceBuilder {
    private RLEvidenceBuilder() {
    }

    public static PromptEvidence build(JSONObject fullJson, EvidenceAction action) {
        PromptEvidence baseline = PromptEvidence.fromFullOutput(fullJson);
        if (action == null || action == EvidenceAction.BASELINE) {
            return baseline;
        }

        JSONObject compact = baseline.toJson();
        EvidenceProfile profile = profileOf(action);
        applyProfile(compact, profile);
        return PromptEvidence.fromFullOutput(compact);
    }

    private static EvidenceProfile profileOf(EvidenceAction action) {
        switch (action) {
            case MUTATION_HEAVY:
                return new EvidenceProfile()
                        .maxChars(18000)
                        .includeEntryGraph(false)
                        .includeEntryEvidence(false)
                        .includeLiftedReachability(false)
                        .includePublicApi(false)
                        .codeKbEntryTopK(2)
                        .codeKbMethodTopK(4)
                        .codeKbFieldTopK(4)
                        .graphListTopK(4);
            case ENTRY_HEAVY:
                return new EvidenceProfile()
                        .maxChars(18000)
                        .includeMutationGraph(false)
                        .includeMutationEvidence(false)
                        .codeKbEntryTopK(3)
                        .codeKbMethodTopK(3)
                        .codeKbFieldTopK(2)
                        .graphListTopK(3);
            case COMPILE_HEAVY:
                return new EvidenceProfile()
                        .maxChars(14000)
                        .includeMutationGraph(false)
                        .includeEntryGraph(false)
                        .includeEntryEvidence(false)
                        .includeMutationEvidence(false)
                        .includeLiftedReachability(false)
                        .includeObservableSelection(false)
                        .includeInputDistinguish(false)
                        .includePropagation(false)
                        .codeKbEntryTopK(1)
                        .codeKbMethodTopK(2)
                        .codeKbFieldTopK(2)
                        .graphListTopK(0)
                        .keepOnly(
                                "mutation", "entry", "executableTestPlan", "invocation", "assertions",
                                "primaryChain", "alternativeChains", "compilationFacts", "entrySelection", "selectedEntry", "canonicalCore",
                                "publicApiEvidence", "observablePlan", "mutationKillPlan",
                                "observableDifferencePlan", "ripExecutionPlan", "entryChainPlan",
                                "reachabilityGuardsPlan", "distinguishingInputGuidance",
                                "indirectEntryTestPlan", "symbolicRipPlan", "symbolicFeasibility",
                                "assertionPlan", "entryParameterControlPlan", "killabilityPlan",
                                "evidenceQuality", "codeKbContext", "apiRoleFacts");
            case ASSERTION_HEAVY:
                return new EvidenceProfile()
                        .maxChars(16000)
                        .includeMutationGraph(false)
                        .includeEntryGraph(false)
                        .includeEntryEvidence(false)
                        .includePublicApi(false)
                        .codeKbEntryTopK(2)
                        .codeKbMethodTopK(3)
                        .codeKbFieldTopK(3)
                        .graphListTopK(0)
                        .keepOnly(
                                "mutation", "entry", "executableTestPlan", "invocation", "assertions",
                                "primaryChain", "alternativeChains", "compilationFacts", "entrySelection", "selectedEntry", "canonicalCore",
                                "observablePlan", "mutationKillPlan", "inputDistinguishPlan",
                                "propagationPlan", "assertionPlan", "ripExecutionPlan",
                                "observableDifferencePlan", "entryChainPlan", "reachabilityGuardsPlan",
                                "distinguishingInputGuidance", "indirectEntryTestPlan", "symbolicRipPlan",
                                "symbolicFeasibility", "observableSelectionPlan", "killabilityPlan", "evidenceQuality",
                                "codeKbContext", "apiRoleFacts", "mutationEvidence");
            case COMPACT:
                return new EvidenceProfile()
                        .maxChars(10000)
                        .includeMutationGraph(false)
                        .includeEntryGraph(false)
                        .includeEntryEvidence(false)
                        .includePublicApi(false)
                        .includeMutationEvidence(false)
                        .includeLiftedReachability(false)
                        .codeKbEntryTopK(1)
                        .codeKbMethodTopK(2)
                        .codeKbFieldTopK(2)
                        .graphListTopK(0)
                        .keepOnly(
                                "mutation", "entry", "executableTestPlan", "invocation", "assertions",
                                "primaryChain", "alternativeChains", "compilationFacts", "entrySelection", "selectedEntry", "canonicalCore",
                                "observablePlan", "mutationKillPlan", "inputDistinguishPlan",
                                "propagationPlan", "assertionPlan", "ripExecutionPlan",
                                "observableDifferencePlan", "entryChainPlan", "reachabilityGuardsPlan",
                                "distinguishingInputGuidance", "indirectEntryTestPlan", "symbolicRipPlan",
                                "symbolicFeasibility", "observableSelectionPlan", "entryParameterControlPlan",
                                "killabilityPlan", "evidenceQuality", "codeKbContext", "apiRoleFacts");
            case BASELINE:
            default:
                return new EvidenceProfile();
        }
    }

    private static void applyProfile(JSONObject compact, EvidenceProfile profile) {
        if (compact == null || profile == null) {
            return;
        }
        if (profile.keepOnly != null && profile.keepOnly.length > 0) {
            keepOnly(compact, profile.keepOnly);
        }
        if (!profile.includeMutationGraph) {
            compact.remove("mutationGraphEvidence");
        } else {
            trimGraphEvidence(compact.optJSONObject("mutationGraphEvidence"), profile.graphListTopK);
        }
        if (!profile.includeEntryGraph) {
            compact.remove("entryGraphEvidence");
        } else {
            trimGraphEvidence(compact.optJSONObject("entryGraphEvidence"), profile.graphListTopK);
        }
        if (!profile.includeEntryEvidence) {
            compact.remove("entryEvidence");
        }
        if (!profile.includeMutationEvidence) {
            compact.remove("mutationEvidence");
        } else {
            trimMutationEvidence(compact.optJSONObject("mutationEvidence"));
        }
        if (!profile.includePublicApi) {
            compact.remove("publicApiEvidence");
        } else {
            trimPublicApi(compact.optJSONObject("publicApiEvidence"), profile.graphListTopK);
        }
        if (!profile.includeObservableSelection) {
            compact.remove("observableSelectionPlan");
        }
        if (!profile.includeInputDistinguish) {
            compact.remove("inputDistinguishPlan");
        }
        if (!profile.includePropagation) {
            compact.remove("propagationPlan");
        }
        if (!profile.includeLiftedReachability) {
            compact.remove("liftedReachabilityPlan");
        }
        trimEntry(compact.optJSONObject("entry"));
        trimExecutablePlan(compact.optJSONObject("executableTestPlan"), profile.graphListTopK);
        trimInvocation(compact.optJSONObject("invocation"), profile.graphListTopK);
        trimAssertions(compact.optJSONObject("assertions"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("observablePlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("mutationKillPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("inputDistinguishPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("propagationPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("assertionPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("ripExecutionPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("primaryChain"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("entrySelection"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("selectedEntry"), Math.max(3, profile.graphListTopK));
        preserveCanonicalCore(compact);
        limitArray(compact, "alternativeChains", Math.max(0, Math.min(2, profile.graphListTopK)), 400);
        trimPlan(compact.optJSONObject("compilationFacts"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("observableDifferencePlan"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("entryChainPlan"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("reachabilityGuardsPlan"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("distinguishingInputGuidance"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("indirectEntryTestPlan"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("symbolicRipPlan"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("symbolicFeasibility"), Math.max(3, profile.graphListTopK));
        trimPlan(compact.optJSONObject("observableSelectionPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("entryParameterControlPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("killabilityPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("liftedReachabilityPlan"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("evidenceQuality"), profile.graphListTopK);
        trimPlan(compact.optJSONObject("apiRoleFacts"), profile.graphListTopK);
        trimCodeKbContext(compact.optJSONObject("codeKbContext"),
                profile.codeKbEntryTopK,
                profile.codeKbMethodTopK,
                profile.codeKbFieldTopK);
        enforceBudget(compact, profile);
    }

    private static void enforceBudget(JSONObject compact, EvidenceProfile profile) {
        if (compact == null || profile == null || profile.maxChars <= 0) {
            return;
        }
        if (compact.toString().length() <= profile.maxChars) {
            return;
        }

        trimCodeKbContext(compact.optJSONObject("codeKbContext"), 1, 1, 1);
        trimGraphEvidence(compact.optJSONObject("mutationGraphEvidence"), 1);
        trimGraphEvidence(compact.optJSONObject("entryGraphEvidence"), 1);
        trimPlan(compact.optJSONObject("mutationKillPlan"), 2);
        trimPlan(compact.optJSONObject("inputDistinguishPlan"), 2);
        trimPlan(compact.optJSONObject("propagationPlan"), 2);
        trimPlan(compact.optJSONObject("assertionPlan"), 2);
        trimPlan(compact.optJSONObject("ripExecutionPlan"), 2);
        trimPlan(compact.optJSONObject("primaryChain"), 3);
        trimPlan(compact.optJSONObject("entrySelection"), 3);
        trimPlan(compact.optJSONObject("selectedEntry"), 3);
        preserveCanonicalCore(compact);
        limitArray(compact, "alternativeChains", 1, 360);
        trimPlan(compact.optJSONObject("compilationFacts"), 3);
        trimPlan(compact.optJSONObject("observableDifferencePlan"), 3);
        trimPlan(compact.optJSONObject("entryChainPlan"), 3);
        trimPlan(compact.optJSONObject("reachabilityGuardsPlan"), 3);
        trimPlan(compact.optJSONObject("distinguishingInputGuidance"), 3);
        trimPlan(compact.optJSONObject("indirectEntryTestPlan"), 3);
        trimPlan(compact.optJSONObject("symbolicRipPlan"), 3);
        trimPlan(compact.optJSONObject("symbolicFeasibility"), 3);
        trimPlan(compact.optJSONObject("entryParameterControlPlan"), 2);
        trimExecutablePlan(compact.optJSONObject("executableTestPlan"), 2);
        trimInvocation(compact.optJSONObject("invocation"), 2);
        trimAssertions(compact.optJSONObject("assertions"), 2);

        if (compact.toString().length() > profile.maxChars) {
            compact.remove("entryGraphEvidence");
            compact.remove("mutationGraphEvidence");
        }
        if (compact.toString().length() > profile.maxChars) {
            compact.remove("publicApiEvidence");
            compact.remove("entryEvidence");
            compact.remove("mutationEvidence");
        }
        if (compact.toString().length() > profile.maxChars) {
            compact.remove("observableSelectionPlan");
            compact.remove("liftedReachabilityPlan");
        }
    }

    private static void trimEntry(JSONObject entry) {
        if (entry == null) {
            return;
        }
        limitArray(entry, "callChain", 4, 180);
        trimString(entry, "entryMethodSignature", 300);
        trimString(entry, "recommendedTarget", 160);
    }

    private static void preserveCanonicalCore(JSONObject compact) {
        // canonicalCore is E_core: RL actions may trim optional evidence, not B/R/I/C/O facts.
        if (compact == null) {
            return;
        }
        JSONObject core = compact.optJSONObject("canonicalCore");
        if (core == null || core.length() == 0) {
            return;
        }
        compact.put("canonicalCore", new JSONObject(core.toString()));
    }

    private static void trimExecutablePlan(JSONObject plan, int topK) {
        if (plan == null) {
            return;
        }
        limitArray(plan, "supportClasses", Math.max(1, topK), 800);
        limitArray(plan, "requiredSetup", Math.max(2, topK + 1), 240);
        limitArray(plan, "forbidden", Math.max(2, topK + 1), 180);
        trimNestedObject(plan.optJSONObject("branchReachability"), 4, 220);
        trimNestedObject(plan.optJSONObject("observable"), 4, 220);
        trimString(plan, "entryCall", 400);
    }

    private static void trimInvocation(JSONObject invocation, int topK) {
        if (invocation == null) {
            return;
        }
        limitArray(invocation, "imports", Math.max(4, topK + 3), 160);
        limitArray(invocation, "setup", Math.max(2, topK + 1), 220);
        trimString(invocation, "call", 400);
        trimNestedObject(invocation.optJSONObject("receiver"), 8, 260);
    }

    private static void trimAssertions(JSONObject assertions, int topK) {
        if (assertions == null) {
            return;
        }
        limitArray(assertions, "requiredToKill", Math.max(1, topK), 260);
        limitArray(assertions, "optionalSanityChecks", Math.max(1, topK), 200);
        limitArray(assertions, "mutationSensitiveObservables", Math.max(2, topK + 1), 200);
        limitArray(assertions, "auxiliaryObservables", Math.max(1, topK), 180);
        limitArray(assertions, "avoid", Math.max(2, topK + 1), 180);
    }

    private static void trimPublicApi(JSONObject publicApi, int topK) {
        if (publicApi == null) {
            return;
        }
        limitArray(publicApi, "availablePublicMethods", Math.max(2, topK + 1), 200);
        limitArray(publicApi, "availableSetupMethods", Math.max(2, topK + 1), 200);
        limitArray(publicApi, "stateSetupPlan", Math.max(2, topK + 1), 220);
        limitArray(publicApi, "antiPatterns", Math.max(2, topK + 1), 180);
        limitArray(publicApi, "compilationGuardrails", Math.max(2, topK + 1), 180);
        trimNestedObject(publicApi.optJSONObject("branchReachabilityPlan"), 4, 220);
    }

    private static void trimMutationEvidence(JSONObject mutationEvidence) {
        if (mutationEvidence == null) {
            return;
        }
        trimString(mutationEvidence, "originCode", 500);
        trimString(mutationEvidence, "mutantCode", 500);
        limitArray(mutationEvidence, "originAffected", 4, 160);
        limitArray(mutationEvidence, "mutantAffected", 4, 160);
        trimString(mutationEvidence, "propagationHint", 220);
    }

    private static void trimGraphEvidence(JSONObject graph, int topK) {
        if (graph == null) {
            return;
        }
        trimString(graph, "role", 220);
        trimString(graph, "path", 320);
        trimString(graph, "originPathSummary", 320);
        trimString(graph, "mutantPathSummary", 320);
        trimString(graph, "callSiteToMutation", 260);
        trimString(graph, "entryPathSummary", 320);
        limitArray(graph, "availableEvidenceKinds", Math.max(2, topK + 1), 120);
        trimNestedObject(graph.optJSONObject("originGraph"), topK, 220);
        trimNestedObject(graph.optJSONObject("mutantGraph"), topK, 220);
        trimNestedObject(graph.optJSONObject("originEntryGraph"), topK, 220);
    }

    private static void trimCodeKbContext(JSONObject codeKbContext,
                                          int entryTopK,
                                          int methodTopK,
                                          int fieldTopK) {
        if (codeKbContext == null) {
            return;
        }
        limitObjectArray(codeKbContext, "candidateEntries", Math.max(0, entryTopK), 5, 220);
        limitObjectArray(codeKbContext, "mutantMethodCalls", Math.max(0, methodTopK), 4, 180);
        limitObjectArray(codeKbContext, "mutantFieldAccesses", Math.max(0, fieldTopK), 4, 180);
        limitObjectArray(codeKbContext, "affectedFields", Math.max(0, fieldTopK), 6, 160);
        limitObjectArray(codeKbContext, "fieldPropagationCandidates", Math.max(0, entryTopK), 6, 220);
        trimString(codeKbContext, "summary", 260);
    }

    private static void trimPlan(JSONObject plan, int topK) {
        if (plan == null) {
            return;
        }
        trimNestedObject(plan, Math.max(2, topK), 220);
    }

    private static void trimNestedObject(JSONObject obj, int topK, int stringLimit) {
        if (obj == null) {
            return;
        }
        String[] names = JSONObject.getNames(obj);
        if (names == null) {
            return;
        }
        for (String name : names) {
            Object value = obj.opt(name);
            if (value instanceof JSONObject) {
                trimNestedObject((JSONObject) value, Math.max(1, topK - 1), stringLimit);
            } else if (value instanceof JSONArray) {
                limitArray(obj, name, Math.max(1, topK), stringLimit);
            } else if (value instanceof String) {
                trimString(obj, name, stringLimit);
            }
        }
    }

    private static void limitObjectArray(JSONObject obj, String key, int maxItems, int maxFields, int maxStringLength) {
        JSONArray arr = obj == null ? null : obj.optJSONArray(key);
        if (arr == null) {
            return;
        }
        JSONArray trimmed = new JSONArray();
        for (int i = 0; i < arr.length() && i < maxItems; i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject copy = new JSONObject();
            String[] names = JSONObject.getNames(item);
            if (names == null) {
                continue;
            }
            int kept = 0;
            for (String name : names) {
                if (kept >= maxFields) {
                    break;
                }
                Object value = item.opt(name);
                if (value instanceof String) {
                    copy.put(name, trimText((String) value, maxStringLength));
                } else {
                    copy.put(name, value);
                }
                kept++;
            }
            trimmed.put(copy);
        }
        obj.put(key, trimmed);
    }

    private static void limitArray(JSONObject obj, String key, int maxItems, int maxStringLength) {
        JSONArray arr = obj == null ? null : obj.optJSONArray(key);
        if (arr == null) {
            return;
        }
        JSONArray trimmed = new JSONArray();
        for (int i = 0; i < arr.length() && i < maxItems; i++) {
            Object value = arr.opt(i);
            if (value instanceof String) {
                trimmed.put(trimText((String) value, maxStringLength));
            } else {
                trimmed.put(value);
            }
        }
        obj.put(key, trimmed);
    }

    private static void trimString(JSONObject obj, String key, int maxLength) {
        if (obj == null || !obj.has(key)) {
            return;
        }
        obj.put(key, trimText(obj.optString(key, ""), maxLength));
    }

    private static String trimText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength || maxLength <= 0) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static void keepOnly(JSONObject obj, String... keys) {
        if (obj == null) {
            return;
        }
        JSONObject kept = new JSONObject();
        for (String key : keys) {
            if (obj.has(key)) {
                kept.put(key, obj.get(key));
            }
        }
        String[] originalKeys = JSONObject.getNames(obj);
        if (originalKeys != null) {
            for (String key : originalKeys) {
                obj.remove(key);
            }
        }
        String[] keptKeys = JSONObject.getNames(kept);
        if (keptKeys != null) {
            for (String key : keptKeys) {
                obj.put(key, kept.get(key));
            }
        }
    }

    private static void trimObject(JSONObject obj, String... keys) {
        if (obj == null) {
            return;
        }
        keepOnly(obj, keys);
    }

    private static final class EvidenceProfile {
        private boolean includeMutationGraph = true;
        private boolean includeEntryGraph = true;
        private boolean includeEntryEvidence = true;
        private boolean includeMutationEvidence = true;
        private boolean includePublicApi = true;
        private boolean includeObservableSelection = true;
        private boolean includeInputDistinguish = true;
        private boolean includePropagation = true;
        private boolean includeLiftedReachability = true;
        private int codeKbEntryTopK = 5;
        private int codeKbMethodTopK = 6;
        private int codeKbFieldTopK = 6;
        private int graphListTopK = 5;
        private int maxChars = 26000;
        private String[] keepOnly;

        private EvidenceProfile includeMutationGraph(boolean value) {
            this.includeMutationGraph = value;
            return this;
        }

        private EvidenceProfile includeEntryGraph(boolean value) {
            this.includeEntryGraph = value;
            return this;
        }

        private EvidenceProfile includeEntryEvidence(boolean value) {
            this.includeEntryEvidence = value;
            return this;
        }

        private EvidenceProfile includeMutationEvidence(boolean value) {
            this.includeMutationEvidence = value;
            return this;
        }

        private EvidenceProfile includePublicApi(boolean value) {
            this.includePublicApi = value;
            return this;
        }

        private EvidenceProfile includeObservableSelection(boolean value) {
            this.includeObservableSelection = value;
            return this;
        }

        private EvidenceProfile includeInputDistinguish(boolean value) {
            this.includeInputDistinguish = value;
            return this;
        }

        private EvidenceProfile includePropagation(boolean value) {
            this.includePropagation = value;
            return this;
        }

        private EvidenceProfile includeLiftedReachability(boolean value) {
            this.includeLiftedReachability = value;
            return this;
        }

        private EvidenceProfile codeKbEntryTopK(int value) {
            this.codeKbEntryTopK = value;
            return this;
        }

        private EvidenceProfile codeKbMethodTopK(int value) {
            this.codeKbMethodTopK = value;
            return this;
        }

        private EvidenceProfile codeKbFieldTopK(int value) {
            this.codeKbFieldTopK = value;
            return this;
        }

        private EvidenceProfile graphListTopK(int value) {
            this.graphListTopK = value;
            return this;
        }

        private EvidenceProfile maxChars(int value) {
            this.maxChars = value;
            return this;
        }

        private EvidenceProfile keepOnly(String... keys) {
            this.keepOnly = keys;
            return this;
        }
    }
}
