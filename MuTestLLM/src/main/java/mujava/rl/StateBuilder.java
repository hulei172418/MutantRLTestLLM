package mujava.rl;

import mujava.testgenerator.tools.PromptEvidence;
import mujava.testgenerator.tools.Request;
import mujava.testgenerator.tools.Result;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Extracts first-stage RL state from request metadata and evidence JSON.
 */
public final class StateBuilder {
    private StateBuilder() {
    }

    public static EvidenceState build(Request request, JSONObject fullJson, PromptEvidence baselineEvidence) {
        return build(request, fullJson, baselineEvidence, null, LinUCBContextVectorizer.DEFAULT_ENCODING);
    }

    public static EvidenceState build(Request request,
                                      JSONObject fullJson,
                                      PromptEvidence baselineEvidence,
                                      Result previousResult) {
        return build(request, fullJson, baselineEvidence, previousResult, LinUCBContextVectorizer.DEFAULT_ENCODING);
    }

    public static EvidenceState build(Request request,
                                      JSONObject fullJson,
                                      PromptEvidence baselineEvidence,
                                      Result previousResult,
                                      LinUCBOperatorEncoding operatorEncoding) {
        EvidenceState state = new EvidenceState();
        state.project = safe(firstNonBlank(
                request == null ? "" : request.projectName,
                fieldString(fullJson, "projectName"),
                inferProjectFromRequest(request)
        ));
        state.operator = request == null ? "" : safe(request.mutantName);
        state.className = request == null ? "" : safe(request.targetClassName);
        state.method = request == null ? "" : safe(request.methodSignature);
        state.line = safe(firstNonBlank(
                mutationLocationString(fullJson, "line"),
                fieldString(fullJson, "lineNo"),
                fieldString(fullJson, "line")
        ));
        state.outerLoopRound = request == null ? 0 : request.outerLoopRound;
        state.mutantId = buildMutantId(request);

        JSONObject entry = baselineEvidence == null ? new JSONObject() : baselineEvidence.toJson().optJSONObject("entry");
        JSONObject invocation = baselineEvidence == null ? new JSONObject() : baselineEvidence.toJson().optJSONObject("invocation");
        JSONObject observable = baselineEvidence == null ? new JSONObject() : baselineEvidence.toJson().optJSONObject("observablePlan");

        state.testEntryKind = optString(entry, "testEntryKind");
        state.useReflectionFallback = entry != null && entry.optBoolean("useReflectionFallback", false);
        state.entryInvocationKind = firstNonBlank(
                optString(entry, "invocationKind"),
                optString(invocation, "invocationKind")
        );
        JSONObject receiver = invocation == null ? new JSONObject() : invocation.optJSONObject("receiver");
        state.testReceiverStrategy = optString(receiver, "strategy");
        state.testEntryOwnerKind = optString(receiver, "ownerKind");
        state.observablePlanKind = firstNonBlank(
                baselineEvidence == null ? "" : baselineEvidence.canonicalObservableKind(),
                optString(observable, "kind"));
        state.observableDifferenceKind = baselineEvidence == null ? "" : baselineEvidence.observableDifferenceKind();
        state.indirectEntryControlType = baselineEvidence == null ? "" : baselineEvidence.indirectEntryControlType();
        state.requiresStateShaping = baselineEvidence != null && baselineEvidence.requiresStateShaping();
        state.requiresInvocationSequence = baselineEvidence != null && baselineEvidence.requiresInvocationSequence();
        state.hasReachabilityGuards = baselineEvidence != null && baselineEvidence.hasReachabilityGuards();
        state.hasBoundarySensitiveInputs = baselineEvidence != null && baselineEvidence.hasBoundarySensitiveInputGuidance();
        state.requiresRealEntryChain = baselineEvidence != null && baselineEvidence.requiresRealEntryChain();
        state.hasSymbolicRipPlan = baselineEvidence != null && baselineEvidence.hasSymbolicRipPlan();
        state.symbolicPathConstraintCount = baselineEvidence == null ? 0 : baselineEvidence.symbolicPathConstraintCount();

        JSONObject executable = baselineEvidence == null ? new JSONObject() : baselineEvidence.toJson().optJSONObject("executableTestPlan");
        JSONObject branch = executable == null ? new JSONObject() : executable.optJSONObject("branchReachability");
        state.branchReachabilityKind = optString(branch, "kind");
        state.skipTestGeneration = baselineEvidence != null && baselineEvidence.skipTestGeneration;
        state.needEntryLiftedEvidence = entry != null && entry.optBoolean("needEntryLiftedEvidence", false);
        state.estimatedPromptSize = baselineEvidence == null ? 0 : baselineEvidence.toJson().toString().length();
        state.operatorFamily = operatorFamily(state.operator);
        state.promptSizeBucket = bucketPromptSize(state.estimatedPromptSize).name();
        state.hasCodeKbContext = baselineEvidence != null && baselineEvidence.hasCodeKbContext();
        state.codeKbEntryCount = baselineEvidence == null ? 0 : baselineEvidence.codeKbEntryCount();
        state.codeKbMethodCallCount = baselineEvidence == null ? 0 : baselineEvidence.codeKbMethodCallCount();
        state.codeKbFieldAccessCount = baselineEvidence == null ? 0 : baselineEvidence.codeKbFieldAccessCount();
        state.codeKbTopEntrySignature = baselineEvidence == null ? "" : baselineEvidence.codeKbTopEntrySignature();
        state.codeKbTopEntryReason = baselineEvidence == null ? "" : baselineEvidence.codeKbTopEntryReason();
        JSONObject compilableFacts = baselineEvidence == null
                ? new JSONObject()
                : baselineEvidence.codeKbCompilableApiFacts();
        state.codeKbOwnerKind = optString(compilableFacts, "ownerKind");
        state.codeKbOwnerVisibility = optString(compilableFacts, "ownerVisibility");
        state.codeKbOwnerInstantiable = compilableFacts.optBoolean("ownerInstantiable", false);
        state.codeKbHasAccessibleConstructor = arrayLength(compilableFacts, "allowedConstructors") > 0;
        state.codeKbHasStaticFactory = arrayLength(compilableFacts, "allowedStaticFactories") > 0;
        state.codeKbHasStaticMethod = arrayLength(compilableFacts, "allowedStaticMethods") > 0;
        state.codeKbHasInstanceMethod = arrayLength(compilableFacts, "allowedInstanceMethods") > 0;
        state.codeKbHasOnlyFactoryConstruction = state.codeKbHasStaticFactory
                && !state.codeKbHasAccessibleConstructor
                && !state.codeKbOwnerInstantiable;
        state.codeKbExactCallableSignatureCount = arrayLength(compilableFacts, "exactCallableSignatures");
        state.codeKbForbiddenCallCount = arrayLength(compilableFacts, "forbiddenCalls");
        state.preferredAssertionMode = baselineEvidence == null ? "" : baselineEvidence.preferredAssertionMode();
        state.mutationSemanticKind = baselineEvidence == null ? "" : baselineEvidence.mutationSemanticKind();
        state.forcedBranchMutation = baselineEvidence != null && baselineEvidence.isForcedBranchMutation();
        state.hasCanonicalObservable = baselineEvidence != null && baselineEvidence.hasCanonicalObservable();
        state.hasMandatoryInfectionConstraints = baselineEvidence != null && baselineEvidence.hasMandatoryInfectionConstraints();
        state.hasRequiredNonNullSubjects = baselineEvidence != null && baselineEvidence.hasRequiredNonNullSubjects();
        state.hasExpectedOriginalExecutable = baselineEvidence != null && baselineEvidence.hasExpectedOriginalExecutable();
        state.entryControllabilityCoverage = baselineEvidence == null ? 0.0d : baselineEvidence.canonicalEntryControllabilityCoverage();
        state.entryIndependentCoverage = baselineEvidence == null ? 0.0d : baselineEvidence.canonicalEntryIndependentCoverage();
        state.entryConstraintStatus = baselineEvidence == null ? "" : baselineEvidence.canonicalEntryConstraintStatus();
        state.mandatoryGuardCount = baselineEvidence == null ? 0 : baselineEvidence.canonicalMandatoryGuardCount();
        state.hasFalsePolarityGuard = baselineEvidence != null && baselineEvidence.canonicalHasFalsePolarityGuard();
        state.mandatoryCofactorCount = baselineEvidence == null ? 0 : baselineEvidence.canonicalMandatoryCofactorCount();
        state.observableDirectness = baselineEvidence == null ? "" : baselineEvidence.canonicalObservableDirectness();
        state.exactBucketKey = buildExactBucketKey(state, operatorEncoding);
        state.coarseBucketKey = buildCoarseBucketKey(state, operatorEncoding);
        if (request != null) {
            state.functionMutantCount = request.functionMutantCount;
            state.siteMutantCount = request.siteMutantCount;
            state.functionProcessedCount = request.functionProcessedCount;
            state.siteProcessedCount = request.siteProcessedCount;
        }

        if (previousResult != null) {
            state.hasPreviousResult = true;
            state.previousCompileSuccess = previousResult.compiled;
            state.previousOriginalPassed = previousResult.originalPassed;
            state.previousKilled = previousResult.killed;
            state.previousRepairRounds = previousResult.timing == null ? 0 : previousResult.timing.repairRounds;
            state.previousTargetStatus = safe(previousResult.targetStatus);
            state.previousFailureReason = safe(previousResult.failureReason);
        }
        return state;
    }

    public static String buildMutantId(Request request) {
        return CanonicalMutantId.build(request);
    }


    private static String mutationLocationString(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        JSONObject mutation = obj.optJSONObject("mutation");
        if (mutation == null) {
            return "";
        }
        JSONObject location = mutation.optJSONObject("location");
        return fieldString(location, key);
    }

    private static String fieldString(JSONObject obj, String key) {
        if (obj == null || key == null || key.trim().isEmpty()) {
            return "";
        }
        Object v = obj.opt(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String optString(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        return obj.optString(key, "").trim();
    }

    private static int arrayLength(JSONObject obj, String key) {
        if (obj == null || key == null || key.trim().isEmpty()) {
            return 0;
        }
        JSONArray arr = obj.optJSONArray(key);
        return arr == null ? 0 : arr.length();
    }

    private static String inferProjectFromRequest(Request request) {
        if (request == null) {
            return "";
        }
        String fromSource = lastPathSegment(request.sourceModuleHome);
        if (!fromSource.isEmpty()) {
            return fromSource;
        }
        return lastPathSegment(request.resultModuleHome);
    }

    private static String lastPathSegment(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static String buildExactBucketKey(EvidenceState state) {
        return buildExactBucketKey(state, LinUCBContextVectorizer.DEFAULT_ENCODING);
    }

    static String buildExactBucketKey(EvidenceState state, LinUCBOperatorEncoding operatorEncoding) {
        if (state == null) {
            return "";
        }
        return joinBucketParts(
                bucketOperatorKey(state.operatorFamily, operatorEncoding),
                state.testReceiverStrategy,
                state.observablePlanKind,
                semanticBucketKey(state.mutationSemanticKind, state.forcedBranchMutation),
                constraintBucketKey(state.entryConstraintStatus),
                state.branchReachabilityKind,
                state.promptSizeBucket
        );
    }

    static String buildCoarseBucketKey(EvidenceState state) {
        return buildCoarseBucketKey(state, LinUCBContextVectorizer.DEFAULT_ENCODING);
    }

    static String buildCoarseBucketKey(EvidenceState state, LinUCBOperatorEncoding operatorEncoding) {
        if (state == null) {
            return "";
        }
        return joinBucketParts(
                bucketOperatorKey(state.operatorFamily, operatorEncoding),
                state.observablePlanKind,
                semanticBucketKey(state.mutationSemanticKind, state.forcedBranchMutation),
                constraintBucketKey(state.entryConstraintStatus));
    }

    private static PromptSizeBucket bucketPromptSize(int estimatedPromptSize) {
        if (estimatedPromptSize <= 8000) {
            return PromptSizeBucket.SMALL;
        }
        if (estimatedPromptSize <= 20000) {
            return PromptSizeBucket.MEDIUM;
        }
        return PromptSizeBucket.LARGE;
    }

    private static String operatorFamily(String operator) {
        if (operator == null) {
            return "";
        }
        String trimmed = operator.trim();
        int idx = trimmed.indexOf('_');
        if (idx > 0) {
            return trimmed.substring(0, idx);
        }
        return trimmed;
    }

    private static String joinBucketParts(String... parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) {
            return "";
        }
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            String value = part == null || part.trim().isEmpty() ? "UNKNOWN" : part.trim();
            sb.append(value);
        }
        return sb.toString();
    }

    private static String semanticBucketKey(String mutationSemanticKind, boolean forcedBranch) {
        if (forcedBranch) {
            return "FORCED_BRANCH";
        }
        String normalized = mutationSemanticKind == null ? "" : mutationSemanticKind.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return "UNKNOWN_SEMANTIC";
        }
        if (normalized.contains("BRANCH_FORCED")) {
            return "FORCED_BRANCH";
        }
        if (normalized.contains("BOUNDARY") || normalized.contains("COMPARISON")) {
            return "BOUNDARY_COMPARISON";
        }
        if (normalized.contains("RETURN")) {
            return "RETURN_ORACLE";
        }
        if (normalized.contains("STATE") || normalized.contains("FIELD")) {
            return "STATE_OR_FIELD";
        }
        return "OTHER_SEMANTIC";
    }

    private static String constraintBucketKey(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (normalized.contains("UNSAT")) {
            return "CONSTRAINT_UNSAT";
        }
        if (normalized.contains("UNKNOWN") || normalized.isEmpty()) {
            return "CONSTRAINT_UNKNOWN";
        }
        if (normalized.contains("SAT")) {
            return "CONSTRAINT_SAT";
        }
        return "CONSTRAINT_OTHER";
    }

    private static String bucketOperatorKey(String operatorFamily, LinUCBOperatorEncoding operatorEncoding) {
        LinUCBOperatorEncoding effectiveEncoding =
                operatorEncoding == null ? LinUCBContextVectorizer.DEFAULT_ENCODING : operatorEncoding;
        if (effectiveEncoding == LinUCBOperatorEncoding.ONE_HOT_19) {
            String normalized = operatorFamily == null ? "" : operatorFamily.trim().toUpperCase();
            return normalized.isEmpty() ? "OTHER" : normalized;
        }
        return sixClassBucket(operatorFamily);
    }

    private static String sixClassBucket(String operatorFamily) {
        if (operatorFamily == null || operatorFamily.trim().isEmpty()) {
            return "MIXED_DELETE_SIMPLIFY";
        }
        String normalized = operatorFamily.trim().toUpperCase();
        if ("AORB".equals(normalized)
                || "AORS".equals(normalized)
                || "AOIS".equals(normalized)
                || "AOIU".equals(normalized)
                || "AODU".equals(normalized)
                || "AODS".equals(normalized)
                || "ASRS".equals(normalized)
                || "CDL".equals(normalized)) {
            return "NUMERIC_EXPR";
        }
        if ("ROR".equals(normalized)) {
            return "RELATIONAL_BRANCH";
        }
        if ("COI".equals(normalized)
                || "COD".equals(normalized)
                || "COR".equals(normalized)) {
            return "BOOLEAN_CONDITION";
        }
        if ("LOI".equals(normalized)
                || "LOR".equals(normalized)
                || "LOD".equals(normalized)
                || "SOR".equals(normalized)) {
            return "BITWISE_SHIFT";
        }
        if ("SDL".equals(normalized)
                || "VDL".equals(normalized)
                || "ODL".equals(normalized)) {
            return "STATE_UPDATE_MISSING";
        }
        return "MIXED_DELETE_SIMPLIFY";
    }
}
