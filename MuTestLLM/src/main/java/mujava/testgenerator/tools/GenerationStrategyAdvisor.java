package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Builds ranked, soft strategy candidates from existing evidence.
 *
 * These candidates are guidance rather than hard constraints:
 * - hard compile facts still come from COMPILABLE_API_FACTS / PUBLIC_API
 * - lower-ranked candidates remain valid fallbacks if higher-ranked ones do not
 *   compile or do not expose a stronger oracle
 */
public final class GenerationStrategyAdvisor {
    private GenerationStrategyAdvisor() {
    }

    public static JSONObject build(Request request, PromptEvidence evidence) {
        JSONObject out = new JSONObject();
        if (evidence == null) {
            return out;
        }

        JSONArray hardFacts = new JSONArray();
        hardFacts.put("Treat COMPILABLE_API_FACTS and PUBLIC_API_AND_COMPILATION_GUARDRAILS as hard facts.");
        hardFacts.put("Treat the ranked strategies below as soft options, not mandatory templates.");
        hardFacts.put("Prefer the first candidate that stays compilable and yields a stronger mutation-sensitive oracle.");
        hardFacts.put("If a higher-ranked candidate conflicts with stronger source-backed evidence, skip it and try the next one.");
        out.put("selectionPolicy", hardFacts);

        JSONArray construction = new JSONArray();
        int constructionRank = 1;
        if (requiresRestrictedConstruction(evidence)) {
            construction.put(candidate(constructionRank++,
                    "SELF_FACTORY_FIRST",
                    "Receiver construction is restricted or non-trivial.",
                    "Prefer evidence-backed static creators, INVOCATION.setup, or source-listed factory/builder paths before guessing new Target(...).",
                    "Keeps compilation stable when constructors are private, package-restricted, or semantically weak."));
        }
        if (needsSameTypeCompanion(request)) {
            construction.put(candidate(constructionRank++,
                    "COMPANION_OBJECT_FIRST",
                    "The entry likely needs another same-type or semantically paired object.",
                    "Construct the companion/collaborator object first, then the receiver, then call the entry.",
                    "Improves compile and reachability for equals/contains/compare-style mutations."));
        }
        if (evidence.isReceiverStateDependent() || evidence.requiresStateShaping()) {
            construction.put(candidate(constructionRank++,
                    "RECEIVER_STATE_SHAPING",
                    "The mutant depends on receiver state rather than a single scalar input.",
                    "Prioritize constructor/setup state shaping before trying new assertions.",
                    "Useful when the mutation is only visible after state propagation."));
        }
        if (construction.length() > 0) {
            out.put("constructionCandidates", construction);
        }

        JSONArray observable = new JSONArray();
        int observableRank = 1;
        if (evidence.prefersExceptionAssertion()) {
            observable.put(candidate(observableRank++,
                    "EXCEPTION_SPLIT_FIRST",
                    "The strongest observable is an exception split.",
                    "Make pass/fail depend on thrown-vs-not-thrown behavior before considering weaker post-state checks.",
                    "Use only when exception evidence is explicit."));
        }
        if (looksRenderedOrBufferedObservable(evidence)) {
            observable.put(candidate(observableRank++,
                    "BUFFER_THEN_ASSERT",
                    "The mutation likely propagates into rendered text or buffer content.",
                    "Create the real sink/buffer first, invoke the entry, then assert on the final rendered content.",
                    "Works well for append/toString/formatting/state-rendering families."));
        }
        if (evidence.prefersReturnValueAssertion()) {
            observable.put(candidate(observableRank++,
                    "RETURN_OR_RENDERED_VALUE_FIRST",
                    "A return value or rendered value is a likely public oracle.",
                    "Prefer asserting the returned/rendered value over object identity or simple success.",
                    "Helps prevent weak sanity-only tests."));
        }
        if (evidence.isReceiverStateDependent() || evidence.requiresInvocationSequence() || evidence.hasIndirectEntryTestPlan()) {
            observable.put(candidate(observableRank++,
                    "STATE_AFTER_CALL",
                    "The mutation needs propagation through state or a call sequence.",
                    "Let the entry/call chain finish first, then assert externally visible post-state.",
                    "Useful when a one-shot smoke test reaches the method but stays LIVE."));
        }
        if (observable.length() > 0) {
            out.put("observableCandidates", observable);
        }

        JSONArray assertion = new JSONArray();
        int assertionRank = 1;
        if (hasDiffPair(evidence)) {
            assertion.put(candidate(assertionRank++,
                    "ORACLE_FROM_DIFF_PAIR",
                    "Evidence already contains an Original-vs-Mutant difference pair.",
                    "Derive the final oracle directly from that difference instead of inventing a fresh weak assertion.",
                    "Most stable when the evidence already names the divergence shape."));
        }
        if (!evidence.prefersExceptionAssertion()) {
            assertion.put(candidate(assertionRank++,
                    "WEAK_TO_RELATIONAL",
                    "Weak sanity assertions are likely to survive.",
                    "Upgrade assertNotNull/simple success into equality, inequality, content, or state-difference assertions.",
                    "Improves kill rate without hard-coding exact source text."));
        }
        if (looksRenderedOrBufferedObservable(evidence)) {
            assertion.put(candidate(assertionRank++,
                    "CONTENT_FRAGMENT_OR_SHAPE",
                    "Rendered content is easier to compare than internal state.",
                    "Prefer fragments, separators, counts, ordering, or full rendered-value assertions.",
                    "Keeps the oracle public and mutation-sensitive."));
        }
        if (evidence.isReceiverStateDependent() || evidence.hasIndirectEntryTestPlan()) {
            assertion.put(candidate(assertionRank++,
                    "DOUBLE_OBSERVE",
                    "A single weak observable may miss the mutation.",
                    "Use one primary observable plus one lightweight auxiliary confirmation if both remain short and compilable.",
                    "Useful for stateful or indirect-entry mutants."));
        }
        if (assertion.length() > 0) {
            out.put("assertionCandidates", assertion);
        }

        return out;
    }

    private static JSONObject candidate(int rank,
                                        String name,
                                        String when,
                                        String preference,
                                        String rationale) {
        JSONObject obj = new JSONObject();
        obj.put("rank", rank);
        obj.put("name", name);
        obj.put("when", when);
        obj.put("preference", preference);
        obj.put("rationale", rationale);
        return obj;
    }

    private static boolean requiresRestrictedConstruction(PromptEvidence evidence) {
        String strategy = safe(evidence.receiverStrategy());
        return "FACTORY_OR_REFLECTION_REQUIRED".equals(strategy)
                || "STATIC_FACTORY_BUILDER".equals(strategy)
                || !evidence.receiverOwnerInstantiable();
    }

    private static boolean needsSameTypeCompanion(Request request) {
        if (request == null) {
            return false;
        }
        String targetSimple = simpleTypeName(request.targetClassName);
        String methodSignature = safe(request.methodSignature);
        return !targetSimple.isEmpty()
                && (methodSignature.contains("(" + targetSimple + ")")
                || methodSignature.contains("," + targetSimple + ")")
                || methodSignature.contains("," + targetSimple + ","));
    }

    private static boolean looksRenderedOrBufferedObservable(PromptEvidence evidence) {
        String text = (safe(evidence.preferredObservableCall()) + "\n"
                + safe(evidence.observablePlanKind()) + "\n"
                + safe(evidence.observablePlan.toString()) + "\n"
                + safe(evidence.propagationPlan.toString()))
                .toLowerCase(Locale.ROOT);
        return text.contains("stringbuffer")
                || text.contains("stringbuilder")
                || text.contains("appendable")
                || text.contains("writer")
                || text.contains("buffer")
                || text.contains("tostring")
                || text.contains("render")
                || text.contains("content");
    }

    private static boolean hasDiffPair(PromptEvidence evidence) {
        String text = (safe(evidence.inputDistinguishPlan.toString()) + "\n"
                + safe(evidence.mutationKillPlan.toString()) + "\n"
                + safe(evidence.assertionPlan.toString()))
                .toLowerCase(Locale.ROOT);
        return text.contains("original:")
                && text.contains("mutant:");
    }

    private static String simpleTypeName(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) {
            return "";
        }
        String trimmed = typeName.trim();
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0) {
            trimmed = trimmed.substring(dot + 1);
        }
        return trimmed.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
