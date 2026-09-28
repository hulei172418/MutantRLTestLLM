package org.rip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds structured mutant-killing evidence from existing RIP/Lifted metadata.
 *
 * The goal is not only to explain how a test can call the code, but to expose:
 * - which inputs distinguish original vs mutant,
 * - how the difference propagates,
 * - which observable/assertion should be used to kill the mutant.
 */
public final class KillPlanEvidenceBuilder {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("(this\\.)?[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*(?:\\(\\))?");

    private static final Set<String> STOP_WORDS = new LinkedHashSet<String>(Arrays.asList(
            "if", "else", "true", "false", "null", "new", "return", "throw", "this", "super"
    ));

    private KillPlanEvidenceBuilder() {
    }

    public static final class Context {
        public String operator = "";
        public String diff = "";

        public String mutationClassName = "";
        public String mutationMethodSig = "";
        public String entryClassName = "";
        public String entryMethodSig = "";
        public String testEntryKind = "";
        public boolean useReflectionFallback;
        public String testCallChain = "";

        public String receiverConstruction = "";
        public String receiverSetupTemplate = "";
        public String receiverInvocationTemplate = "";
        public String receiverStrategy = "";

        public String observablePlanKind = "";
        public String observableSetup = "";
        public String observableCall = "";
        public String observableExpectedOriginal = "";
        public String observableReason = "";

        public String branchReachabilityKind = "";
        public String branchReachabilityCondition = "";
        public String branchReachabilitySetup = "";
        public String branchReachabilityReason = "";

        public String semanticOriginalExpression = "";
        public String semanticMutantExpression = "";
        public List<String> ancestorPathPredicates = new ArrayList<String>();
        public Set<String> requiredNonNullVariables = new LinkedHashSet<String>();
        public String semanticResolutionMode = "";
        public String semanticResolutionReason = "";

        public String availablePublicMethods = "";
        public String availableSetupMethods = "";
        public String stateSetupPlan = "";
        public boolean skipTestGeneration;
        public String skipReason = "";
    }

    public static Map<String, Object> buildMutationKillPlan(Context ctx) {
        String semanticKind = mutationSemanticKind(ctx);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("mutationSemanticKind", commented("Heuristic semantic category of the mutation", semanticKind));
        item.put("reachConditionSummary", commented("What inputs/setup are needed to reach the mutated behavior",
                reachConditionSummary(ctx, semanticKind)));
        item.put("infectionConditionSummary", commented("What constraints are likely to make original and mutant diverge",
                infectionConditionSummary(ctx, semanticKind)));
        item.put("propagationTargetSummary", commented("Which public observable is most likely to expose the divergence",
                propagationTargetSummary(ctx)));
        item.put("preferredAssertionMode", commented("Preferred assertion style for killing this mutant",
                preferredAssertionMode(ctx)));
        item.put("requiresSequentialCalls", commented("Whether the evidence suggests multiple calls/state transitions are needed",
                requiresSequentialCalls(ctx)));
        item.put("requiresStateBeforeAfterComparison", commented(
                "Whether the evidence suggests comparing object state before/after the target call",
                requiresStateBeforeAfterComparison(ctx)));
        item.put("confidence", commented("Confidence of this heuristic kill plan", confidenceLabel(ctx, semanticKind)));
        return commented("Mutation-sensitive plan for constructing a killing test", item);
    }

    public static Map<String, Object> buildRipExecutionPlan(Context ctx) {
        String semanticKind = mutationSemanticKind(ctx);
        List<Map<String, Object>> criticalVariables = buildCriticalVariables(ctx);
        List<Map<String, Object>> constraints = buildDistinguishingConstraints(ctx, semanticKind, criticalVariables);
        List<Map<String, Object>> preferredInputs = buildPreferredConcreteInputs(ctx);
        List<String> propagationChain = buildPropagationChain(ctx);

        Map<String, Object> reachability = new LinkedHashMap<String, Object>();
        reachability.put("goal", commented("R: make the mutated statement/path execute",
                reachConditionSummary(ctx, semanticKind)));
        reachability.put("entry", commented("Callable test entry B selected by static analysis",
                safe(ctx.entryClassName) + "#" + safe(ctx.entryMethodSig)));
        reachability.put("sameEntryAndMutation", commented(
                "Whether generated tests can call the mutation method directly",
                sameMethod(ctx.entryClassName, ctx.entryMethodSig, ctx.mutationClassName, ctx.mutationMethodSig)));
        reachability.put("requiredSetup", commentedList(
                "Setup and preconditions needed before invoking B",
                buildRipReachabilitySteps(ctx, preferredInputs)));

        Map<String, Object> infection = new LinkedHashMap<String, Object>();
        infection.put("goal", commented("I: make original and mutant internal states/branch outcomes diverge",
                infectionConditionSummary(ctx, semanticKind)));
        infection.put("mutationExpression", commented("Original side of the mutated expression/branch",
                firstNonBlank(ctx.semanticOriginalExpression, safe(diffLeft(ctx.diff)))));
        infection.put("mutantExpression", commented("Mutant side of the mutated expression/branch",
                firstNonBlank(ctx.semanticMutantExpression, safe(diffRight(ctx.diff)))));
        infection.put("semanticKind", commented("Heuristic mutation semantic category", semanticKind));
        infection.put("distinguishingConstraints", commentedList(
                "Concrete constraints likely to trigger infection",
                constraints));
        infection.put("criticalVariables", commentedList(
                "Variables/expressions directly participating in infection",
                criticalVariables));

        Map<String, Object> propagation = new LinkedHashMap<String, Object>();
        propagation.put("goal", commented("P: propagate the infected state to a test-observable sink",
                propagationTargetSummary(ctx)));
        propagation.put("chain", commentedList("Expected propagation chain from mutation to observable behavior",
                propagationChain));
        propagation.put("observableSinkKind", commented("Observable sink category",
                normalizeObservableKind(ctx.observablePlanKind)));
        propagation.put("observableSinkExpression", commented("Preferred observable expression/call",
                observableSinkExpression(ctx)));
        propagation.put("requiresAdditionalCalls", commented(
                "Whether post-entry calls are likely needed before asserting",
                requiresAdditionalCalls(ctx)));

        Map<String, Object> oracle = new LinkedHashMap<String, Object>();
        oracle.put("preferredAssertionMode", commented("Assertion style aligned with RIP propagation",
                preferredAssertionMode(ctx)));
        oracle.put("primaryAssertions", commentedList("Mutation-sensitive assertions",
                buildPrimaryAssertions(ctx)));
        oracle.put("assertionTemplates", commentedList("Prompt-ready oracle templates",
                buildAssertionTemplates(ctx)));

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("model", commented("Mutation testing reasoning model used by this plan",
                "RIP: Reachability -> Infection -> Propagation -> Oracle"));
        item.put("reachability", commented("Reachability evidence and setup", reachability));
        item.put("infection", commented("Infection evidence and distinguishing constraints", infection));
        item.put("propagation", commented("Propagation evidence and observable sink", propagation));
        item.put("oracle", commented("Assertion/oracle guidance derived from propagation", oracle));
        item.put("guardrails", commentedList(
                "Inputs or assertions that compile but usually do not exercise RIP correctly",
                buildRipGuardrails(ctx, semanticKind)));
        item.put("confidence", commented("Confidence of the RIP execution plan",
                confidenceLabel(ctx, semanticKind)));
        return commented("Unified RIP execution plan for generated killing tests", item);
    }

    public static Map<String, Object> buildInputDistinguishPlan(Context ctx) {
        String semanticKind = mutationSemanticKind(ctx);
        List<Map<String, Object>> criticalVariables = buildCriticalVariables(ctx);
        List<Map<String, Object>> constraints = buildDistinguishingConstraints(ctx, semanticKind, criticalVariables);
        List<Map<String, Object>> boundaries = buildBoundaryFamilies(ctx, semanticKind, criticalVariables);
        List<Map<String, Object>> preferredInputs = buildPreferredConcreteInputs(ctx);
        List<String> weakInputs = buildDiscouragedWeakInputs(ctx, semanticKind);
        List<String> notes = buildInputConstructionNotes(ctx, semanticKind);

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("criticalVariables", commentedList(
                "Variables/expressions directly participating in the mutated computation", criticalVariables));
        item.put("distinguishingConstraints", commentedList(
                "Input constraints likely to distinguish original and mutant", constraints));
        item.put("boundaryValueFamilies", commentedList(
                "Boundary-oriented input families worth trying", boundaries));
        item.put("preferredConcreteInputs", commentedList(
                "Concrete input templates inferred from parameter/receiver types", preferredInputs));
        item.put("discouragedWeakInputs", commentedList(
                "Weak default inputs that are usually insufficient for killing the mutant", weakInputs));
        item.put("inputConstructionNotes", commentedList(
                "Additional notes for constructing a mutation-sensitive test input", notes));
        return commented("Input constraints and candidate values that can distinguish original from mutant", item);
    }

    public static Map<String, Object> buildPropagationPlan(Context ctx) {
        List<String> chain = buildPropagationChain(ctx);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("infectionSource", commented("Expression/branch summary where the original-mutant divergence starts",
                safe(diffLeft(ctx.diff))));
        item.put("propagationChain", commentedList("How the mutation-side difference is expected to propagate", chain));
        item.put("observableSinkKind", commented("Kind of public observable sink", normalizeObservableKind(ctx.observablePlanKind)));
        item.put("observableSinkExpression", commented("Concrete public expression/call that should expose the mutation effect",
                observableSinkExpression(ctx)));
        item.put("requiresAdditionalCalls", commented("Whether additional public calls beyond the entry invocation are likely needed",
                requiresAdditionalCalls(ctx)));
        item.put("requiresObjectStateObservation", commented("Whether public object state should be observed after invocation",
                requiresStateObservation(ctx)));
        item.put("requiresExceptionObservation", commented("Whether exception type/message observation is preferred",
                isExceptionObservable(ctx)));
        return commented("How the mutation-side value difference propagates to an observable sink", item);
    }

    public static Map<String, Object> buildAssertionPlan(Context ctx) {
        List<Map<String, Object>> primary = buildPrimaryAssertions(ctx);
        List<Map<String, Object>> secondary = buildSecondaryAssertions(ctx);
        List<String> antiPatterns = buildAssertionAntiPatterns(ctx);
        List<String> templates = buildAssertionTemplates(ctx);

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("primaryAssertions", commentedList("Assertions most likely needed to kill the mutant", primary));
        item.put("secondaryAssertions", commentedList("Sanity assertions that may complement the primary kill assertion", secondary));
        item.put("antiPatterns", commentedList("Assertion styles that often fail to distinguish original and mutant", antiPatterns));
        item.put("assertionTemplates", commentedList("Prompt-ready assertion templates/guidance", templates));
        return commented("Recommended mutation-sensitive assertions", item);
    }

    public static Map<String, Object> buildLiftedReachabilityPlan(Context ctx) {
        boolean same = sameMethod(ctx.entryClassName, ctx.entryMethodSig, ctx.mutationClassName, ctx.mutationMethodSig);
        List<String> conditions = buildLiftedConditions(ctx, same);
        List<String> postEntrySteps = buildPostEntryObservableSteps(ctx, same);

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("enabled", commented("Whether lifted reachability evidence is relevant for this mutant", !same));
        item.put("sameEntryAndMutation", commented("Whether callable entry B is the same method as mutation method A", same));
        item.put("entryMethod", commented("Callable test entry B", safe(ctx.entryClassName) + "#" + safe(ctx.entryMethodSig)));
        item.put("mutationMethod", commented("Real mutation method A", safe(ctx.mutationClassName) + "#" + safe(ctx.mutationMethodSig)));
        item.put("callChain", commentedList("Resolved call chain from B to A", splitCallChain(ctx.testCallChain)));
        item.put("entryToMutationConditions", commentedList(
                "Conditions/setup needed for the entry call to actually reach the mutated method", conditions));
        item.put("postEntryObservableSteps", commentedList(
                "Public follow-up steps after entry invocation that can expose the mutation", postEntrySteps));
        item.put("liftedObservationBridge", commented(
                "How the A-side difference is expected to become observable from B-side behavior",
                liftedObservationBridge(ctx, same)));
        return commented("How callable test entry B reaches mutation method A and exposes A-side difference", item);
    }

    public static Map<String, Object> buildEvidenceQuality(Context ctx) {
        List<Map<String, Object>> preferredInputs = buildPreferredConcreteInputs(ctx);
        List<Map<String, Object>> constraints = buildDistinguishingConstraints(ctx, mutationSemanticKind(ctx), buildCriticalVariables(ctx));
        List<String> chain = buildPropagationChain(ctx);
        boolean hasSpecificObservable = !isBlank(observableSinkExpression(ctx));
        boolean hasExactAssertionTarget = !buildPrimaryAssertions(ctx).isEmpty();
        List<String> weaknesses = buildWeaknesses(ctx, preferredInputs, constraints, chain, hasSpecificObservable);
        boolean equivalenceSuspicion = isLikelyEquivalentMutation(ctx);

        double score = 0.0d;
        if (!preferredInputs.isEmpty()) {
            score += 0.20d;
        }
        if (!constraints.isEmpty()) {
            score += 0.20d;
        }
        if (chain.size() >= 2) {
            score += 0.20d;
        }
        if (hasSpecificObservable) {
            score += 0.20d;
        }
        if (hasExactAssertionTarget) {
            score += 0.20d;
        }

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("hasConcreteInputExamples", commented("Whether concrete non-trivial input templates were inferred", !preferredInputs.isEmpty()));
        item.put("hasDistinguishingConstraints", commented("Whether the evidence contains explicit original-vs-mutant input constraints", !constraints.isEmpty()));
        item.put("hasPropagationChain", commented("Whether a multi-step propagation explanation was inferred", chain.size() >= 2));
        item.put("hasSpecificObservable", commented("Whether a concrete public observable expression/call was identified", hasSpecificObservable));
        item.put("hasExactAssertionTarget", commented("Whether the evidence recommends a concrete mutation-sensitive assertion target", hasExactAssertionTarget));
        item.put("equivalenceSuspicion", commented("Whether static heuristics suspect this mutant may be semantically equivalent", equivalenceSuspicion));
        item.put("equivalenceReason", commented("Why static heuristics suspect semantic equivalence", equivalenceSuspicion ? equivalenceSuspicionReason(ctx) : ""));
        item.put("weaknesses", commentedList("Known weaknesses of the current evidence package", weaknesses));
        item.put("score", commented("Heuristic evidence quality score in [0,1]", score));
        return commented("Static estimate of how actionable this evidence is for mutant killing", item);
    }

    private static String mutationSemanticKind(Context ctx) {
        String diff = safe(ctx.diff);
        String operator = safe(ctx.operator).toUpperCase(Locale.ROOT);
        String normalizedLeft = normalizeBooleanLiteral(effectiveOriginalExpression(ctx));
        String normalizedRight = normalizeBooleanLiteral(effectiveMutantExpression(ctx));
        if ("true".equals(normalizedRight) && !"true".equals(normalizedLeft)) {
            return "BRANCH_FORCED_TRUE";
        }
        if ("false".equals(normalizedRight) && !"false".equals(normalizedLeft)) {
            return "BRANCH_FORCED_FALSE";
        }
        if (diff.contains("=>")) {
            String left = diffLeft(diff);
            String right = diffRight(diff);
            if ((containsAny(left, "<=", ">=", "<", ">", "==", "!=")
                    || containsAny(right, "<=", ">=", "<", ">", "==", "!="))
                    && (containsAny(left, "++", "--") || containsAny(right, "++", "--"))) {
                return "PREDICATE_SIDE_EFFECT_CHANGE";
            }
            if (containsAny(left, "<=", ">=", "<", ">", "==", "!=")
                    || containsAny(right, "<=", ">=", "<", ">", "==", "!=")) {
                return "BOUNDARY_OR_COMPARISON_CHANGE";
            }
            if (containsAny(left, "&&", "||") || containsAny(right, "&&", "||")) {
                return "BOOLEAN_CONNECTOR_CHANGE";
            }
            if (containsAny(left, "+", "-", "*", "/", "%") || containsAny(right, "+", "-", "*", "/", "%")) {
                if (operator.startsWith("AOI")) {
                    return "ARITHMETIC_SIGN_FLIP";
                }
                return "ARITHMETIC_OPERATOR_CHANGE";
            }
            if (containsAny(left, "null") || containsAny(right, "null")) {
                return "NULL_CHECK_CHANGE";
            }
            if (containsAny(left, "return") || containsAny(right, "return")) {
                return "EARLY_RETURN_CHANGE";
            }
        }
        if (operator.startsWith("SDL") || operator.startsWith("ODL")) {
            return "EARLY_RETURN_OR_BRANCH_DELETION";
        }
        if (operator.startsWith("ROR")) {
            return "BOUNDARY_OR_COMPARISON_CHANGE";
        }
        if (operator.startsWith("COR") || operator.startsWith("COD")) {
            return "BOOLEAN_CONNECTOR_CHANGE";
        }
        if (operator.startsWith("AOR") || operator.startsWith("AOI")) {
            return "ARITHMETIC_OPERATOR_CHANGE";
        }
        if (isConstructor(ctx.entryMethodSig) || isConstructor(ctx.mutationMethodSig)) {
            return "CONSTRUCTOR_STATE_CHANGE";
        }
        return "GENERIC_VALUE_OR_CONTROL_CHANGE";
    }

    private static List<Map<String, Object>> buildCriticalVariables(Context ctx) {
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String token : extractIdentifiers(diffLeft(ctx.diff))) {
            if (!seen.add(token)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", commented("Variable/expression name", token));
            item.put("role", commented("Why this token matters to mutation behavior", criticalRole(token)));
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> buildDistinguishingConstraints(Context ctx,
                                                                            String semanticKind,
                                                                            List<Map<String, Object>> criticalVariables) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String left = diffLeft(ctx.diff);
        String right = diffRight(ctx.diff);

        if ("ARITHMETIC_OPERATOR_CHANGE".equals(semanticKind) || "ARITHMETIC_SIGN_FLIP".equals(semanticKind)) {
            out.add(constraint("inequality",
                    "Prefer inputs where the operands participating in the changed arithmetic expression are non-zero and not trivially equal.",
                    "Arithmetic mutants are often equivalent under zero/default operands."));
        } else if ("PREDICATE_SIDE_EFFECT_CHANGE".equals(semanticKind)) {
            out.add(constraint("predicate_side_effect",
                    "Use inputs that force evaluation of the mutated predicate and then observe a downstream externally visible effect.",
                    "Post-increment/decrement inside a predicate often changes later local state rather than the immediate return value."));
        } else if ("BRANCH_FORCED_TRUE".equals(semanticKind) || "BRANCH_FORCED_FALSE".equals(semanticKind)) {
            out.add(forcedBranchConstraint(ctx, semanticKind));
        } else if ("BOUNDARY_OR_COMPARISON_CHANGE".equals(semanticKind)) {
            out.add(constraint("boundary",
                    "Exercise equality and one-step-beyond values around the mutated comparison boundary.",
                    "Comparison mutants usually diverge at the exact boundary or immediately beyond it."));
        } else if ("BOOLEAN_CONNECTOR_CHANGE".equals(semanticKind)) {
            out.add(constraint("truth_assignment",
                    "Construct truth assignments that make the original and mutant branch conditions disagree.",
                    "Connector changes require covering mixed true/false combinations."));
        } else if ("NULL_CHECK_CHANGE".equals(semanticKind)) {
            out.add(constraint("nullability",
                    "Cover both null and non-null cases, prioritizing the branch whose guard changed.",
                    "Null-check mutants usually diverge only on one side of the guard."));
        } else if ("EARLY_RETURN_OR_BRANCH_DELETION".equals(semanticKind) || "EARLY_RETURN_CHANGE".equals(semanticKind)) {
            out.add(constraint("branch_execution",
                    "Use inputs that should traverse the deleted/forced branch and observe the public behavior after it.",
                    "Deletion-style mutants often require proving that a branch should still execute."));
        }

        if (!isBlank(ctx.branchReachabilityCondition)) {
            out.add(constraint("reachability",
                    safe(ctx.branchReachabilityCondition),
                    firstNonBlank(ctx.branchReachabilityReason, "Static analysis inferred this condition as relevant for reaching the mutation.")));
        }
        for (String predicate : ctx.ancestorPathPredicates == null ? Collections.<String>emptyList() : ctx.ancestorPathPredicates) {
            if (!isBlank(predicate)) {
                out.add(constraint("ancestor_guard",
                        safe(predicate),
                        "JavaParser located this ancestor guard as required before the mutation semantic expression executes."));
            }
        }
        for (String variable : ctx.requiredNonNullVariables == null ? Collections.<String>emptySet() : ctx.requiredNonNullVariables) {
            if (!isBlank(variable)) {
                out.add(propertyConstraint("non_null_dereference",
                        variable + " != null",
                        "NON_NULL",
                        variable,
                        "The semantic mutation expression dereferences this value before the mutation can be evaluated."));
            }
        }
        if (requiresInitializeHeaderSetup(ctx)) {
            out.add(constraint("non_null_header_setup",
                    "Use CSVFormat.withHeader(...) or CSVFormat.withHeader(new String[]{}) so format.getHeader() is non-null and initializeHeader() actually executes.",
                    "Plain CSVFormat.DEFAULT keeps format.getHeader()==null, which bypasses the mutated logic entirely."));
        }
        if (looksLikeTokenTrimMutation(ctx)) {
            out.add(constraint("simple_token_trailing_space",
                    "Use CSVFormat.DEFAULT or another format with ignoreSurroundingSpaces=false, and input an unquoted token ending with whitespace before a delimiter/EOL, e.g. \"abc ,z\".",
                    "The original preserves trailing spaces when ignoreSurroundingSpaces=false; the mutant forces trimTrailingSpaces and removes them."));
        }
        if (looksLikeCsvLexerEofContractMutation(ctx)) {
            out.add(constraint("escape_return_domain",
                    "Only treat <= END_OF_STREAM as killable if the escape-reading helper can return a value below END_OF_STREAM.",
                    "For normal Lexer.readEscape behavior, return values are END_OF_STREAM or valid non-negative/special characters, so < END_OF_STREAM is unreachable."));
        }
        if (looksLikePostfixOverwrittenLocalMutation(ctx)) {
            out.add(constraint("postfix_side_effect_observability",
                    "Before generating a test, verify the post-increment/decrement side effect is read before the local variable is reassigned.",
                    "A postfix expression returns the original value; if the local side effect is overwritten before observation, no RIP infection propagates."));
        }
        if (looksLikeTrimTrailingSpacesEquivalentMutation(ctx)) {
            out.add(constraint("trim_trailing_spaces_state_machine",
                    "Classify this as likely equivalent: length starts at buffer.length(), only monotonically decreases to a reachable value in [0, buffer.length()], and setLength(length) is idempotent when length is unchanged.",
                    "The mutation does not create a reachable StringBuilder content difference; extra setLength calls to the current length are not observable."));
        }
        if (looksLikeLexerNextTokenEquivalentMutation(ctx)) {
            out.add(constraint("lexer_next_token_equivalence_check",
                    "Classify this as likely equivalent unless a concrete reader state makes Token.type/content/isReady differ after nextToken(new Token()).",
                    "The changed local/predicate usually falls through to an equivalent EOF/TOKEN decision or passes the original value through a postfix expression."));
        }
        if (looksLikeLexerNextTokenCommentKillableMutation(ctx)) {
            out.add(constraint("lexer_comment_path",
                    "Use multiple nextToken calls over input such as \"a,#comment\\n\" so the second token sees c == '#' while lastChar is a delimiter, not start-of-line.",
                    "This distinguishes normal token text '#comment' from a mutant that treats any comment marker as a COMMENT token."));
        }
        if (looksLikeLexerNextTokenCommentEofKillableMutation(ctx)) {
            out.add(constraint("lexer_comment_eof_path",
                    "Use a comment marker at EOF, e.g. \"#\", and assert that nextToken(new Token()).type is EOF.",
                    "Deleting token.type = EOF in the comment-at-EOF path can return the default token type instead of EOF."));
        }
        if (looksLikeLexerNextTokenEmptyLineKillableMutation(ctx)) {
            out.add(constraint("lexer_empty_line_path",
                    "Use CSVFormat.DEFAULT.withIgnoreEmptyLines(true) and inputs containing repeated blank lines followed by EOF or a token, then assert Token.type/isReady/content across sequential nextToken calls.",
                    "Deleted empty-line state updates can change whether the lexer skips blank lines or emits EORECORD/EOF at the right time."));
        }
        if (isConstructorExceptionMutation(ctx)) {
            out.addAll(buildInitializeHeaderExceptionConstraints(ctx));
        }

        if (out.isEmpty() && !criticalVariables.isEmpty()) {
            out.add(constraint("generic",
                    "Avoid default-only inputs; drive the critical variables to non-trivial values and observe a public effect.",
                    "The mutation affects " + joinCriticalNames(criticalVariables) + "."));
        }

        if (!isBlank(left) && !left.equals(right)) {
            out.add(constraint("diff_pair",
                    "Original: " + left + " | Mutant: " + right,
                    "Use this pair to reason about which inputs make the two evaluations differ."));
        }
        String effectiveOriginal = effectiveOriginalExpression(ctx);
        String effectiveMutant = effectiveMutantExpression(ctx);
        if (!isBlank(effectiveOriginal)
                && !isBlank(effectiveMutant)
                && !effectiveOriginal.equals(effectiveMutant)) {
            out.add(0, propertyConstraint("semantic_difference",
                    "(" + effectiveOriginal + ") != (" + effectiveMutant + ")",
                    "SEMANTIC_DIFFERENCE",
                    "",
                    firstNonBlank(ctx.semanticResolutionReason,
                            "Use the full semantic expressions, not only the local diff, to drive infection.")));
            String forced = forcedOriginalEvaluation(effectiveOriginal, effectiveMutant);
            if (!isBlank(forced)) {
                out.add(1, propertyConstraint("forced_branch_evaluation",
                        forced,
                        "REQUIRED_ORIGINAL_EVALUATION",
                        effectiveOriginal,
                        "The mutant forces a branch value, so the original condition must evaluate to the opposite value to infect."));
            }
        }
        return out;
    }

    private static List<Map<String, Object>> buildBoundaryFamilies(Context ctx,
                                                                   String semanticKind,
                                                                   List<Map<String, Object>> criticalVariables) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if ("BRANCH_FORCED_TRUE".equals(semanticKind) || "BRANCH_FORCED_FALSE".equals(semanticKind)) {
            return out;
        }
        if ("BOUNDARY_OR_COMPARISON_CHANGE".equals(semanticKind)) {
            out.add(boundary("comparison_boundary", Arrays.asList("n-1", "n", "n+1"),
                    "Comparison mutations frequently diverge at the equality boundary."));
        }
        if ("ARITHMETIC_OPERATOR_CHANGE".equals(semanticKind) || "ARITHMETIC_SIGN_FLIP".equals(semanticKind)) {
            out.add(boundary("numeric_operands", Arrays.asList("-1", "0", "1", "2"),
                    "Arithmetic mutants often need non-zero signed inputs."));
        }
        if (!isBlank(ctx.observablePlanKind) && ctx.observablePlanKind.toLowerCase(Locale.ROOT).contains("collection")) {
            out.add(boundary("collection_size", Arrays.asList("empty", "singleton", "two-elements"),
                    "Collection-observable mutations often differ across minimal size buckets."));
        }
        if (!out.isEmpty()) {
            return out;
        }
        if (!criticalVariables.isEmpty()) {
            out.add(boundary("generic_non_default", Arrays.asList("default", "boundary", "one-step-beyond"),
                    "Use default, boundary, and slightly perturbed values for critical variables."));
        }
        return out;
    }

    private static List<Map<String, Object>> buildPreferredConcreteInputs(Context ctx) {
        LinkedHashSet<String> paramTypes = new LinkedHashSet<String>(extractParameterTypes(ctx.entryMethodSig));
        String all = mergedText(ctx);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();

        if (containsType(all, paramTypes, "Reader")) {
            out.add(inputTemplate("reader", "java.io.Reader",
                    Arrays.asList("new StringReader(\"a,b\\nc,d\")", "new StringReader(\"col1,col2\\nval1,val2\")"),
                    "Prefer non-empty in-memory text over null Reader when parser behavior must propagate."));
        }
        if (containsType(all, paramTypes, "ExtendedBufferedReader")) {
            out.add(inputTemplate("reader", "org.apache.commons.csv.ExtendedBufferedReader",
                    Arrays.asList(
                            "new ExtendedBufferedReader(new StringReader(\"abc ,z\"))",
                            "new ExtendedBufferedReader(new StringReader(\"a,b\\n1,2\"))",
                            "new ExtendedBufferedReader(new StringReader(\"\\n\"))",
                            "new ExtendedBufferedReader(new StringReader(\"a,#comment\\n\"))"),
                    "Use a real buffered reader so lexer/parser state-machine mutations reach token type/content/state instead of failing on null collaborators."));
        }
        if (containsType(all, paramTypes, "Token")) {
            out.add(inputTemplate("token", "org.apache.commons.csv.Token",
                    Arrays.asList("new Token()"),
                    "Use a real token instance so lexer mutations propagate to token.type/token.content/token.isReady instead of throwing on a null token."));
        }
        if (requiresInitializeHeaderSetup(ctx)) {
            out.add(inputTemplate("csv_format_header_setup", "org.apache.commons.csv.CSVFormat",
                    Arrays.asList(
                            "org.apache.commons.csv.CSVFormat.DEFAULT.withHeader(new String[] {})",
                            "org.apache.commons.csv.CSVFormat.DEFAULT.withHeader(\"A\", \"B\", \"A\")",
                            "org.apache.commons.csv.CSVFormat.DEFAULT.withHeader(\"\", \"B\", \"\").withAllowMissingColumnNames(true)"
                    ),
                    "initializeHeader() only runs when format.getHeader() is non-null; duplicate/empty header shapes are needed for header-validation mutants."));
        }
        if (containsType(all, paramTypes, "Appendable") || containsType(all, paramTypes, "StringBuilder")) {
            out.add(inputTemplate("output", "java.lang.Appendable",
                    Arrays.asList("new StringBuilder()", "new StringBuilder(\"seed\")"),
                    "Use mutable external state so side effects can be asserted."));
        }
        if (containsType(all, paramTypes, "String")) {
            out.add(inputTemplate("text", "java.lang.String",
                    Arrays.asList("\"\"", "\"x\"", "\"a,b\""),
                    "Cover empty, singleton, and structured string cases."));
        }
        if (containsType(all, paramTypes, "List") || containsType(all, paramTypes, "Collection")) {
            out.add(inputTemplate("collection", "java.util.Collection",
                    Arrays.asList("Collections.emptyList()", "Arrays.asList(\"x\")", "Arrays.asList(\"x\", \"y\")"),
                    "Collection-sensitive mutants usually need size-based variation."));
        }
        if (containsType(all, paramTypes, "Map")) {
            out.add(inputTemplate("map", "java.util.Map",
                    Arrays.asList("new LinkedHashMap<>()", "Collections.singletonMap(\"k\", 1)"),
                    "Map-based observables often require both empty and populated cases."));
        }
        if (containsAny(all, "int", "long", "double", "float")) {
            out.add(inputTemplate("numeric", "primitive numbers",
                    Arrays.asList("0", "1", "-1", "2"),
                    "Use signed and boundary-adjacent numeric values instead of only zero."));
        }
        if (looksLikeAppendableOutputMutation(ctx)) {
            out.add(inputTemplate("quoted_or_delimited_text", "java.lang.String",
                    Arrays.asList("\"a,b,c\"", "\"a'b'c\"", "\"a\\rb\\nc\""),
                    "Prefer strings containing delimiter, quote, or line breaks so output formatting differences become visible."));
        }
        if (looksLikeLineCounterMutation(ctx)) {
            out.add(inputTemplate("multi_line_text", "java.lang.String",
                    Arrays.asList("\"foo\\n\\nhello\"", "\"1\\n2\\r3\\n\"", "\"foo\\rbaar\\r\\nfoo\""),
                    "Prefer multiple logical lines so line-counter or reader-state mutations propagate to public getters."));
        }
        if (looksLikeCsvLexerTokenMutation(ctx)) {
            out.add(inputTemplate("csv_lexer_token_stream", "Lexer + ExtendedBufferedReader + Token",
                    Arrays.asList(
                            "CSVFormat.DEFAULT + new ExtendedBufferedReader(new StringReader(\"abc ,z\")) + new Token()",
                            "CSVFormat.DEFAULT + new ExtendedBufferedReader(new StringReader(\"a,#comment\\n\")) + sequential nextToken(new Token()) calls",
                            "CSVFormat.DEFAULT.withIgnoreEmptyLines(true) + new ExtendedBufferedReader(new StringReader(\"\\n\\nX\")) + new Token()",
                            "CSVFormat.DEFAULT.withEscape('\\\\') + new ExtendedBufferedReader(new StringReader(\"a\\\\x,z\")) + new Token()",
                            "CSVFormat.DEFAULT.withEscape('\\\\') + new ExtendedBufferedReader(new StringReader(\"a\\\\\")) + new Token()"),
                    "Lexer token-state mutants must enter through nextToken(new Token()) with a real reader; Token.content/type/isReady are the observable state."));
        }
        return out;
    }

    private static List<String> buildRipReachabilitySteps(Context ctx, List<Map<String, Object>> preferredInputs) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (!isBlank(ctx.receiverSetupTemplate)) {
            out.add("Receiver setup: " + ctx.receiverSetupTemplate);
        } else if (!isBlank(ctx.receiverConstruction)) {
            out.add("Receiver construction: " + ctx.receiverConstruction);
        }
        if (!isBlank(ctx.branchReachabilitySetup)) {
            out.add("Branch setup: " + ctx.branchReachabilitySetup);
        }
        if (!isBlank(ctx.branchReachabilityCondition)) {
            out.add("Satisfy branch condition: " + ctx.branchReachabilityCondition);
        }
        if (looksLikeCsvLexerTokenMutation(ctx)) {
            out.add("Entry-lifted call: do not call private parseSimpleToken directly; call Lexer#nextToken(new Token()) from the same package.");
            out.add("Use a non-null ExtendedBufferedReader, e.g. new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader(new StringReader(\"abc ,z\"))).");
            out.add("Use a non-null Token argument so parseSimpleToken can propagate into token.content/token.type/token.isReady.");
        }
        for (Map<String, Object> input : preferredInputs) {
            String target = stringItem(input.get("target"));
            String reason = stringItem(input.get("reason"));
            List<String> examples = stringItems(input.get("examples"));
            if (!examples.isEmpty()) {
                out.add("Input " + target + ": prefer " + examples.get(0)
                        + (reason.isEmpty() ? "" : " because " + reason));
            }
        }
        if (out.isEmpty()) {
            out.add("Invoke the selected entry with non-default, mutation-sensitive arguments.");
        }
        return new ArrayList<String>(out);
    }

    private static List<String> buildRipGuardrails(Context ctx, String semanticKind) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        out.addAll(buildDiscouragedWeakInputs(ctx, semanticKind));
        out.addAll(buildAssertionAntiPatterns(ctx));
        if (containsAny(mergedText(ctx), "Token", "ExtendedBufferedReader", "Reader", "CSVFormat", "Appendable")) {
            out.add("Do not replace required collaborators with null merely to test exception behavior; that usually satisfies neither reachability nor infection.");
        }
        if (!"NULL_CHECK_CHANGE".equals(semanticKind)) {
            out.add("Null-only tests are weak unless the mutated expression is explicitly a null-check.");
        }
        return new ArrayList<String>(out);
    }

    private static List<String> buildDiscouragedWeakInputs(Context ctx, String semanticKind) {
        List<String> out = new ArrayList<String>();
        if (containsAny(mergedText(ctx), "null")) {
            out.add("Do not rely only on null/default constructor arguments when the mutation requires propagation through real behavior.");
        }
        out.add("Do not use only 0/false/empty inputs unless the mutation explicitly targets the default case.");
        if (requiresInitializeHeaderSetup(ctx)) {
            out.add("Do not use plain CSVFormat.DEFAULT for initializeHeader() mutants; it leaves format.getHeader()==null and skips the target logic.");
        }
        if ("ARITHMETIC_OPERATOR_CHANGE".equals(semanticKind) || "ARITHMETIC_SIGN_FLIP".equals(semanticKind)) {
            out.add("Avoid zero-only arithmetic operands; they often hide the difference between original and mutant.");
        }
        if (looksLikeAppendableOutputMutation(ctx)) {
            out.add("Do not assert only non-null output; compare the final emitted text exactly.");
        }
        if (looksLikeLineCounterMutation(ctx)) {
            out.add("Do not assert only readLine() return values when the mutation changes line counters or reader state.");
        }
        if (looksLikeCsvLexerTokenMutation(ctx)) {
            out.add("Do not instantiate Lexer with a null ExtendedBufferedReader; that prevents normal tokenization reachability.");
            out.add("Do not call nextToken(null); pass new Token() so mutated writes to token.content/type can propagate.");
        }
        if ("BOUNDARY_OR_COMPARISON_CHANGE".equals(semanticKind)) {
            out.add("Avoid testing only one side of the comparison; include the exact boundary and one-step-beyond cases.");
        }
        return out;
    }

    private static List<String> buildInputConstructionNotes(Context ctx, String semanticKind) {
        List<String> out = new ArrayList<String>();
        if (!isBlank(ctx.branchReachabilitySetup)) {
            out.add("Reachability setup: " + ctx.branchReachabilitySetup);
        }
        if (!isBlank(ctx.observableSetup)) {
            out.add("Observable setup: " + ctx.observableSetup);
        }
        if (!isBlank(ctx.receiverConstruction)) {
            out.add("Receiver construction should preserve real project behavior: " + ctx.receiverConstruction);
        }
        if (requiresInitializeHeaderSetup(ctx)) {
            out.add("For CSVParser.initializeHeader() mutants, choose a CSVFormat built with withHeader(...); otherwise the constructor never reaches the mutated header-initialization branch.");
        }
        if ("CONSTRUCTOR_STATE_CHANGE".equals(semanticKind)) {
            out.add("For constructor-state mutations, observe public behavior after construction rather than asserting object non-nullness.");
        }
        if (isConstructorExceptionMutation(ctx)) {
            out.add("For duplicate/empty-header guard mutations, the strongest oracle is usually constructor completes vs IllegalArgumentException, not nextRecord() or getHeaderMap() alone.");
        }
        if (looksLikeAppendableOutputMutation(ctx)) {
            out.add("Prefer a concrete writer/buffer such as StringBuilder or StringWriter and assert its final text.");
        }
        if (looksLikeLineCounterMutation(ctx)) {
            out.add("After each read-like call, observe a public line-number or position getter rather than only the returned text.");
        }
        return out;
    }

    private static List<String> buildPropagationChain(Context ctx) {
        List<String> chain = new ArrayList<String>();
        if (!sameMethod(ctx.entryClassName, ctx.entryMethodSig, ctx.mutationClassName, ctx.mutationMethodSig)) {
            for (String step : splitCallChain(ctx.testCallChain)) {
                chain.add("Entry reachability: " + step);
            }
        }
        if (!isBlank(diffLeft(ctx.diff))) {
            chain.add("Infection starts when the mutated expression/branch evaluates differently: " + diffLeft(ctx.diff));
        }
        if (!isBlank(ctx.observableCall)) {
            chain.add("Public observable step: " + ctx.observableCall);
        }
        if (!isBlank(ctx.observableReason)) {
            chain.add("Observable rationale: " + ctx.observableReason);
        }
        String derivedSink = derivedObservableSinkExpression(ctx);
        if (!isBlank(derivedSink) && isBlank(ctx.observableCall)) {
            chain.add("Derived observable sink: " + derivedSink);
        }
        if (chain.isEmpty()) {
            chain.add("Observe a public return/state/exception effect after invoking the selected entry.");
        }
        return chain;
    }

    private static List<Map<String, Object>> buildPrimaryAssertions(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String mode = preferredAssertionMode(ctx);
        String expression = observableSinkExpression(ctx);
        if (!isBlank(expression)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("kind", commented("Assertion category", mode.toLowerCase(Locale.ROOT)));
            item.put("expression", commented("Expression/call to observe", expression));
            item.put("expectedBehavior", commented("Why this assertion is mutation-sensitive",
                    propagationTargetSummary(ctx)));
            if (looksLikeTokenTrimMutation(ctx)) {
                item.put("expectedOriginal", commented("Concrete original-program expectation",
                        "\"abc \" when input is \"abc ,z\" and ignoreSurroundingSpaces=false"));
            } else if (looksLikeTrimTrailingSpacesMutation(ctx)) {
                item.put("expectedOriginal", commented("Concrete original-program expectation",
                        "buffer.toString() after trimming trailing whitespace from the same mutable StringBuilder instance"));
            } else if (looksLikeLexerNextTokenCommentKillableMutation(ctx)) {
                item.put("expectedOriginal", commented("Concrete original-program expectation",
                        "second token type TOKEN and content \"#comment\" for input \"a,#comment\\n\""));
            } else if (looksLikeLexerNextTokenCommentEofKillableMutation(ctx)) {
                item.put("expectedOriginal", commented("Concrete original-program expectation",
                        "token.type EOF for input \"#\""));
            }
            item.put("priority", commented("Priority", "high"));
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> buildSecondaryAssertions(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!isBlank(ctx.observableExpectedOriginal)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("kind", commented("Assertion category", "expected_original_hint"));
            item.put("expression", commented("Observed target", observableSinkExpression(ctx)));
            item.put("expectedBehavior", commented("Original-program expectation inferred by analysis",
                    ctx.observableExpectedOriginal));
            item.put("priority", commented("Priority", "medium"));
            out.add(item);
        }
        if (!isBlank(ctx.observableCall)) {
            Map<String, Object> sanity = new LinkedHashMap<String, Object>();
            sanity.put("kind", commented("Assertion category", "sanity"));
            sanity.put("expression", commented("Observed target", ctx.observableCall));
            sanity.put("expectedBehavior", commented("Reason",
                    "Use only as a sanity assertion; it is not sufficient alone unless it captures the mutated value."));
            sanity.put("priority", commented("Priority", "low"));
            out.add(sanity);
        }
        return out;
    }

    private static List<String> buildAssertionAntiPatterns(Context ctx) {
        List<String> out = new ArrayList<String>();
        out.add("Do not assert only object non-nullness when the mutation affects a value, branch, or side effect.");
        if (requiresStateObservation(ctx)) {
            out.add("Do not stop at returned receiver identity; assert the externally visible state after the call.");
        }
        if (isExceptionObservable(ctx)) {
            out.add("Do not assert only that some exception was thrown; prefer exception type and message/content checks.");
        }
        if ("ENTRY_RETURN_VALUE".equalsIgnoreCase(normalizeObservableKind(ctx.observablePlanKind))) {
            out.add("Do not assert only result != null; assert a mutation-sensitive property of the returned value.");
        }
        if (looksLikeAppendableOutputMutation(ctx)) {
            out.add("Do not accept multiple output variants; use exact emitted text as the oracle when formatting behavior is mutated.");
        }
        if (looksLikeLineCounterMutation(ctx)) {
            out.add("Do not stop at returned line content; assert the post-call line number or related public state.");
        }
        return out;
    }

    private static List<String> buildAssertionTemplates(Context ctx) {
        List<String> out = new ArrayList<String>();
        String expression = observableSinkExpression(ctx);
        String mode = preferredAssertionMode(ctx);
        if (isBlank(expression)) {
            out.add("Assert a public value/state/exception that can differ between original and mutant.");
            return out;
        }
        if ("EXCEPTION_ASSERTION".equals(mode)) {
            out.add("assertThrows(...);");
            out.add("assertTrue(exception.getMessage().contains(\"...\"));");
        } else if (looksLikeTokenTrimMutation(ctx)) {
            out.add("Lexer subject = new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader(new StringReader(\"abc ,z\")));");
            out.add("Token token = subject.nextToken(new Token());");
            out.add("assertEquals(\"abc \", token.content.toString());");
        } else if (looksLikeLexerNextTokenCommentKillableMutation(ctx)) {
            out.add("Lexer subject = new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader(new StringReader(\"a,#comment\\n\")));");
            out.add("Token first = subject.nextToken(new Token());");
            out.add("Token second = subject.nextToken(new Token());");
            out.add("assertEquals(Token.Type.TOKEN, second.type);");
            out.add("assertEquals(\"#comment\", second.content.toString());");
        } else if (looksLikeLexerNextTokenCommentEofKillableMutation(ctx)) {
            out.add("Lexer subject = new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader(new StringReader(\"#\")));");
            out.add("Token token = subject.nextToken(new Token());");
            out.add("assertEquals(Token.Type.EOF, token.type);");
        } else if (looksLikeLexerNextTokenEmptyLineKillableMutation(ctx)) {
            out.add("Lexer subject = new Lexer(CSVFormat.DEFAULT.withIgnoreEmptyLines(true), new ExtendedBufferedReader(new StringReader(\"\\n\\nX\")));");
            out.add("Token token = subject.nextToken(new Token());");
            out.add("assertEquals(Token.Type.TOKEN, token.type);");
            out.add("assertEquals(\"X\", token.content.toString());");
        } else if (looksLikeTrimTrailingSpacesMutation(ctx)) {
            out.add("StringBuilder buffer = new StringBuilder(\"abc  \");");
            out.add("subject.trimTrailingSpaces(buffer);");
            out.add("assertEquals(\"abc\", buffer.toString());");
        } else if (looksLikeAppendableOutputMutation(ctx)) {
            out.add("assertEquals(expectedOutput, " + expression + ");");
        } else if (looksLikeLineCounterMutation(ctx)) {
            out.add("assertEquals(expectedLineNumber, " + expression + ");");
        } else if ("STATE_ASSERTION".equals(mode)) {
            out.add("assertEquals(expected, " + expression + ");");
        } else if ("RELATIONAL_ASSERTION".equals(mode)) {
            out.add("assertEquals(expectedOriginal, " + expression + ");");
        } else if ("RETURN_ASSERTION".equals(mode)) {
            out.add("assertEquals(expectedOriginal, " + expression + ");");
        } else {
            out.add("assertEquals(expected, " + expression + ");");
        }
        return out;
    }

    private static List<String> buildLiftedConditions(Context ctx, boolean same) {
        if (same) {
            return Collections.singletonList("B is the same method as A; focus on A-side input constraints directly.");
        }
        List<String> out = new ArrayList<String>();
        if (!isBlank(ctx.branchReachabilityCondition)) {
            out.add(ctx.branchReachabilityCondition);
        }
        if (!isBlank(ctx.receiverConstruction)) {
            out.add("Construct the entry receiver/collaborators so the B -> A call chain is exercised: " + ctx.receiverConstruction);
        }
        if (!isBlank(ctx.branchReachabilitySetup)) {
            out.add("Entry reachability setup: " + ctx.branchReachabilitySetup);
        }
        if (out.isEmpty()) {
            out.add("Invoke the callable entry with non-default inputs and ensure the resolved call chain actually reaches the mutated method.");
        }
        return out;
    }

    private static List<String> buildPostEntryObservableSteps(Context ctx, boolean same) {
        List<String> out = new ArrayList<String>();
        if (!same) {
            out.add("After invoking B, observe a public behavior that depends on A rather than asserting only that B returned.");
        }
        if (!isBlank(ctx.observableCall)) {
            out.add(ctx.observableCall);
        }
        String derivedSink = derivedObservableSinkExpression(ctx);
        if (!isBlank(derivedSink)
                && (isBlank(ctx.observableCall) || !safe(ctx.observableCall).contains(derivedSink))) {
            out.add("Observe derived sink: " + derivedSink);
        }
        if (!isBlank(ctx.observableSetup)) {
            out.add(ctx.observableSetup);
        }
        return out;
    }

    private static List<String> buildWeaknesses(Context ctx,
                                                List<Map<String, Object>> preferredInputs,
                                                List<Map<String, Object>> constraints,
                                                List<String> chain,
                                                boolean hasSpecificObservable) {
        List<String> out = new ArrayList<String>();
        if (preferredInputs.isEmpty()) {
            out.add("No concrete non-default input templates were inferred.");
        }
        if (constraints.isEmpty()) {
            out.add("No explicit distinguishing input constraints were inferred from the mutation diff.");
        }
        if (chain.size() < 2) {
            out.add("Propagation evidence is shallow; the observable sink may still be too generic.");
        }
        if (!hasSpecificObservable) {
            out.add("No exact public observable expression was identified.");
        }
        if (containsAny(mergedText(ctx), "null")) {
            out.add("Existing setup hints still rely on null/default construction, which often compiles but does not kill the mutant.");
        }
        if (ctx.skipTestGeneration) {
            out.add("skipTestGeneration=true: killing-test guidance is informational unless upstream skip logic is changed.");
        }
        return out;
    }

    private static Map<String, Object> constraint(String kind, String constraint, String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("kind", commented("Constraint kind", kind));
        m.put("constraint", commented("Input/property constraint", constraint));
        m.put("reason", commented("Why this constraint matters", reason));
        return m;
    }

    private static Map<String, Object> propertyConstraint(String kind,
                                                          String constraint,
                                                          String requiredInputProperty,
                                                          String subjectExpression,
                                                          String reason) {
        Map<String, Object> m = constraint(kind, constraint, reason);
        if (!isBlank(requiredInputProperty)) {
            m.put("requiredInputProperty", commented("Required structured input property", requiredInputProperty));
        }
        if (!isBlank(subjectExpression)) {
            m.put("subjectExpression", commented("Input expression or variable the property applies to", subjectExpression));
        }
        m.put("hardness", commented("Whether the constraint is mandatory for RIP infection/reachability", "MANDATORY"));
        return m;
    }

    private static Map<String, Object> forcedBranchConstraint(Context ctx, String semanticKind) {
        String original = effectiveOriginalExpression(ctx);
        boolean forcedFalse = "BRANCH_FORCED_FALSE".equals(semanticKind);
        Map<String, Object> m = propertyConstraint("forced_branch",
                forcedFalse ? original : "!(" + original + ")",
                "REQUIRED_ORIGINAL_EVALUATION",
                original,
                forcedFalse
                        ? "The mutant condition is always false, so the original condition must evaluate to true."
                        : "The mutant condition is always true, so the original condition must evaluate to false.");
        m.put("requiredOriginalEvaluation", commented("Required original condition value", forcedFalse));
        return m;
    }

    private static String effectiveOriginalExpression(Context ctx) {
        return firstNonBlank(ctx.semanticOriginalExpression, diffLeft(ctx.diff));
    }

    private static String effectiveMutantExpression(Context ctx) {
        return firstNonBlank(ctx.semanticMutantExpression, diffRight(ctx.diff));
    }

    private static String normalizeBooleanLiteral(String value) {
        String text = safe(value).trim();
        while (text.startsWith("(") && text.endsWith(")") && text.length() > 1) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static String forcedOriginalEvaluation(String originalExpression, String mutantExpression) {
        String mutant = safe(mutantExpression).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(mutant)) {
            return "(" + safe(originalExpression).trim() + ") == false";
        }
        if ("false".equals(mutant)) {
            return "(" + safe(originalExpression).trim() + ") == true";
        }
        return "";
    }

    private static Map<String, Object> boundary(String variable, List<String> family, String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("variable", commented("Boundary family target", variable));
        m.put("family", commentedList("Candidate values/buckets", family));
        m.put("reason", commented("Why this boundary family matters", reason));
        return m;
    }

    private static Map<String, Object> inputTemplate(String target, String typeHint, List<String> examples, String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("target", commented("Input target", target));
        m.put("typeHint", commented("Type/category", typeHint));
        m.put("examples", commentedList("Example expressions", examples));
        m.put("reason", commented("Why these inputs are preferred", reason));
        return m;
    }

    private static String reachConditionSummary(Context ctx, String semanticKind) {
        if (!isBlank(ctx.branchReachabilityCondition)) {
            return ctx.branchReachabilityCondition;
        }
        if (looksLikeCsvLexerTokenMutation(ctx)) {
            return "Use the lifted Lexer#nextToken(Token) entry with a real ExtendedBufferedReader and non-null Token so private parseSimpleToken executes on a simple token path.";
        }
        if (requiresInitializeHeaderSetup(ctx)) {
            return "Configure CSVFormat.withHeader(...) or withHeader(new String[]{}) so format.getHeader() is non-null and the constructor reaches initializeHeader().";
        }
        if (!sameMethod(ctx.entryClassName, ctx.entryMethodSig, ctx.mutationClassName, ctx.mutationMethodSig)) {
            return "Invoke the resolved entry method B with setup that traverses the B -> A call chain before observing a public effect.";
        }
        if ("CONSTRUCTOR_STATE_CHANGE".equals(semanticKind)) {
            return "Construct the object with non-default inputs and then observe post-construction public behavior.";
        }
        return "Drive the callable entry with non-default inputs so the mutated computation/branch is actually exercised.";
    }

    private static String infectionConditionSummary(Context ctx, String semanticKind) {
        if (isConstructorExceptionMutation(ctx)) {
            return "Use explicit duplicate or empty header configurations so the original and mutant disagree on whether the CSVParser constructor throws IllegalArgumentException.";
        }
        if (looksLikeTokenTrimMutation(ctx)) {
            return "Set ignoreSurroundingSpaces=false and use an unquoted simple token ending with whitespace before a delimiter or EOL so original preserves trailing spaces while mutant trims them.";
        }
        if (looksLikeTrimTrailingSpacesEquivalentMutation(ctx)) {
            return "No infection is expected: trimTrailingSpaces keeps length in [0, buffer.length()] and extra/idempotent setLength(length) calls do not change StringBuilder content.";
        }
        if (looksLikeLexerNextTokenCommentKillableMutation(ctx)) {
            return "Drive a non-start-of-line comment marker: the original parses '#comment' as TOKEN text, while the mutant may treat it as COMMENT.";
        }
        if (looksLikeLexerNextTokenCommentEofKillableMutation(ctx)) {
            return "Drive a comment marker immediately followed by EOF so deletion of token.type = EOF can leave the returned Token in its default type.";
        }
        if (looksLikeLexerNextTokenEmptyLineKillableMutation(ctx)) {
            return "Drive ignoreEmptyLines=true with repeated blank lines so deleted reader-state updates can change the emitted Token.type/content.";
        }
        if (looksLikeLexerNextTokenEquivalentMutation(ctx)) {
            return "The changed nextToken local/predicate appears to fall through to the same EOF/TOKEN decision or uses a postfix expression whose side effect is overwritten.";
        }
        if (looksLikeCsvLexerEofContractMutation(ctx)) {
            return "The comparison differs only for values below END_OF_STREAM; verify whether readEscape can produce such values before attempting a kill.";
        }
        if (looksLikePostfixOverwrittenLocalMutation(ctx)) {
            return "The postfix operation returns the original operand value and its local side effect appears overwritten before observation; treat as likely equivalent unless a later read of that local exists.";
        }
        if ("ARITHMETIC_OPERATOR_CHANGE".equals(semanticKind) || "ARITHMETIC_SIGN_FLIP".equals(semanticKind)) {
            return "Use non-zero, non-trivial operands so the changed arithmetic operator/sign affects the result.";
        }
        if ("PREDICATE_SIDE_EFFECT_CHANGE".equals(semanticKind)) {
            return "Force the mutated predicate to execute and then observe the downstream state/output that depends on the side effect.";
        }
        if ("BOUNDARY_OR_COMPARISON_CHANGE".equals(semanticKind)) {
            return "Exercise the exact comparison boundary and values immediately around it.";
        }
        if ("BOOLEAN_CONNECTOR_CHANGE".equals(semanticKind)) {
            return "Cover truth assignments where the original and mutant branch predicates evaluate differently.";
        }
        if ("NULL_CHECK_CHANGE".equals(semanticKind)) {
            return "Cover both null and non-null cases to flip the mutated guard outcome.";
        }
        if ("EARLY_RETURN_OR_BRANCH_DELETION".equals(semanticKind) || "EARLY_RETURN_CHANGE".equals(semanticKind)) {
            return "Force execution through the deleted/forced branch and observe the downstream behavior.";
        }
        return "Choose inputs that make the changed expression or branch outcome visible rather than collapsing to the same default behavior.";
    }

    private static String propagationTargetSummary(Context ctx) {
        if (isConstructorExceptionMutation(ctx)) {
            return "Observe whether CSVParser construction completes or throws IllegalArgumentException while header validation runs inside initializeHeader().";
        }
        if (looksLikeTokenTrimMutation(ctx)) {
            return "Observe Token.content after nextToken(new Token()): original keeps the trailing space, while the mutant trims it.";
        }
        if (looksLikeTrimTrailingSpacesMutation(ctx)) {
            return "Observe the same StringBuilder instance after trimTrailingSpaces(buffer); equivalent forms should leave buffer.toString() unchanged.";
        }
        if (looksLikeLexerNextTokenCommentKillableMutation(ctx)) {
            return "Observe the second Token.type and Token.content after sequential nextToken(new Token()) calls.";
        }
        if (looksLikeLexerNextTokenCommentEofKillableMutation(ctx)) {
            return "Observe Token.type after nextToken(new Token()) on a comment-at-EOF input.";
        }
        if (looksLikeLexerNextTokenEmptyLineKillableMutation(ctx)) {
            return "Observe Token.type, Token.isReady, and Token.content after nextToken(new Token()) on repeated blank-line input.";
        }
        if (looksLikeCsvLexerTokenMutation(ctx)) {
            return "Observe Token.content, Token.type, or Token.isReady after nextToken(new Token()), not just method completion.";
        }
        if (!isBlank(ctx.observableReason)) {
            return ctx.observableReason;
        }
        String kind = normalizeObservableKind(ctx.observablePlanKind);
        if ("EXCEPTION_MESSAGE".equals(kind) || "EXCEPTION_TYPE".equals(kind)) {
            return "Observe the thrown exception rather than only method completion.";
        }
        if ("APPENDABLE_CONTENT".equals(kind) || "WRITER_CONTENT".equals(kind)) {
            return "Observe the final emitted text from the external buffer/writer after the call.";
        }
        if ("EXTERNAL_MUTABLE_STATE".equals(kind) || "PUBLIC_STATE_GETTER".equals(kind)) {
            return "Observe externally visible state after the target call.";
        }
        if ("ENTRY_RETURN_VALUE".equals(kind) || "RETURN_VALUE".equals(kind) || "RETURN_OBJECT_GETTER".equals(kind)) {
            return "Observe a mutation-sensitive property of the returned value/object.";
        }
        return "Observe a public value/state/exception that depends on the mutated computation.";
    }

    private static String preferredAssertionMode(Context ctx) {
        if (isConstructorExceptionMutation(ctx)) {
            return "EXCEPTION_ASSERTION";
        }
        String kind = normalizeObservableKind(ctx.observablePlanKind);
        if ("EXCEPTION_MESSAGE".equals(kind) || "EXCEPTION_TYPE".equals(kind)) {
            return "EXCEPTION_ASSERTION";
        }
        if ("APPENDABLE_CONTENT".equals(kind) || "WRITER_CONTENT".equals(kind)
                || "EXTERNAL_MUTABLE_STATE".equals(kind) || "PUBLIC_STATE_GETTER".equals(kind)
                || isConstructor(ctx.entryMethodSig)) {
            return "STATE_ASSERTION";
        }
        if (requiresSequentialCalls(ctx)) {
            return "RELATIONAL_ASSERTION";
        }
        return "RETURN_ASSERTION";
    }

    private static boolean requiresSequentialCalls(Context ctx) {
        String text = mergedText(ctx).toLowerCase(Locale.ROOT);
        return text.contains("nextrecord")
                || text.contains("iterator")
                || text.contains("before/after")
                || text.contains("currentlinenumber")
                || text.contains("position")
                || text.contains("readline()");
    }

    private static boolean requiresStateBeforeAfterComparison(Context ctx) {
        String kind = normalizeObservableKind(ctx.observablePlanKind);
        return "PUBLIC_STATE_GETTER".equals(kind)
                || "EXTERNAL_MUTABLE_STATE".equals(kind)
                || "APPENDABLE_CONTENT".equals(kind)
                || "WRITER_CONTENT".equals(kind)
                || isConstructor(ctx.entryMethodSig);
    }

    private static String confidenceLabel(Context ctx, String semanticKind) {
        int score = 0;
        if (!"GENERIC_VALUE_OR_CONTROL_CHANGE".equals(semanticKind)) {
            score++;
        }
        if (!isBlank(ctx.observableCall)) {
            score++;
        }
        if (!isBlank(ctx.branchReachabilityCondition)) {
            score++;
        }
        if (!sameMethod(ctx.entryClassName, ctx.entryMethodSig, ctx.mutationClassName, ctx.mutationMethodSig)
                && !isBlank(ctx.testCallChain)) {
            score++;
        }
        if (score >= 3) {
            return "high";
        }
        if (score >= 2) {
            return "medium";
        }
        return "low";
    }

    private static String observableSinkExpression(Context ctx) {
        if (looksLikeTokenTrimMutation(ctx)) {
            return "token.content.toString()";
        }
        if (looksLikeTrimTrailingSpacesMutation(ctx)) {
            return "buffer.toString()";
        }
        if (looksLikeLexerNextTokenCommentKillableMutation(ctx)) {
            return "second.type and second.content.toString()";
        }
        if (looksLikeLexerNextTokenCommentEofKillableMutation(ctx)) {
            return "token.type";
        }
        if (looksLikeLexerNextTokenEmptyLineKillableMutation(ctx)) {
            return "token.type and token.content.toString()";
        }
        if (looksLikeCsvLexerTokenMutation(ctx) && isWeakObservableExpression(ctx.observableCall)) {
            return "token.content.toString()";
        }
        if (isConstructorExceptionMutation(ctx) && !isBlank(ctx.receiverInvocationTemplate)) {
            return ctx.receiverInvocationTemplate;
        }
        if (!isBlank(ctx.observableCall)) {
            String extracted = extractObservedExpression(ctx.observableCall);
            if (!isBlank(extracted)) {
                return extracted;
            }
            return ctx.observableCall;
        }
        return derivedObservableSinkExpression(ctx);
    }

    private static boolean requiresAdditionalCalls(Context ctx) {
        if (isConstructorExceptionMutation(ctx)) {
            return false;
        }
        return !isBlank(ctx.observableCall)
                && !safe(ctx.observableCall).equals(safe(ctx.receiverInvocationTemplate));
    }

    private static boolean requiresStateObservation(Context ctx) {
        if (isConstructorExceptionMutation(ctx)) {
            return false;
        }
        String kind = normalizeObservableKind(ctx.observablePlanKind);
        return "PUBLIC_STATE_GETTER".equals(kind)
                || "EXTERNAL_MUTABLE_STATE".equals(kind)
                || "APPENDABLE_CONTENT".equals(kind)
                || "WRITER_CONTENT".equals(kind)
                || "CONSTRUCTOR_STATE".equals(kind);
    }

    private static boolean isExceptionObservable(Context ctx) {
        if (isConstructorExceptionMutation(ctx)) {
            return true;
        }
        String kind = normalizeObservableKind(ctx.observablePlanKind);
        return kind.contains("EXCEPTION");
    }

    private static String liftedObservationBridge(Context ctx, boolean same) {
        if (same) {
            return "A and B are the same method; assertions can directly target the A-side observable.";
        }
        String observable = observableSinkExpression(ctx);
        if (!isBlank(observable)) {
            return "A-side divergence should propagate through entry B and become observable via: " + observable;
        }
        return "A-side divergence is not directly observable at the call site; invoke B, then observe a public behavior that depends on A.";
    }

    private static String normalizeObservableKind(String kind) {
        String normalized = isBlank(kind) ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if ("THROWABLE_MESSAGE".equals(normalized)) {
            return "EXCEPTION_MESSAGE";
        }
        if ("CONSTRUCTOR_COMPLETES_VS_EXCEPTION".equals(normalized) || "EXCEPTION_BEHAVIOR".equals(normalized)) {
            return "EXCEPTION_TYPE";
        }
        if ("PUBLIC_METHOD_DEPENDS_ON_MUTATED_FIELD".equals(normalized)
                || "CONSTRUCTOR_PUBLIC_GETTER_OBSERVABLE".equals(normalized)) {
            return "PUBLIC_STATE_GETTER";
        }
        if (normalized.contains("APPENDABLE") || normalized.contains("WRITER")) {
            return "APPENDABLE_CONTENT";
        }
        return normalized;
    }

    private static String derivedObservableSinkExpression(Context ctx) {
        if (looksLikeAppendableOutputMutation(ctx)) {
            return "output.toString()";
        }
        if (looksLikeLineCounterMutation(ctx)) {
            return "subject.getCurrentLineNumber()";
        }
        if (looksLikeCsvLexerTokenMutation(ctx)) {
            return "token.content.toString()";
        }
        if (looksLikeThrowableMessageMutation(ctx)) {
            return "subject.getMessage()";
        }
        return "";
    }

    private static boolean looksLikeAppendableOutputMutation(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String text = mergedText(ctx).toLowerCase(Locale.ROOT);
        return text.contains("appendable")
                || text.contains("stringbuilder")
                || text.contains("stringwriter")
                || text.contains("out.append")
                || text.contains("printandquote")
                || text.contains("printandescape");
    }

    private static boolean looksLikeLineCounterMutation(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String text = mergedText(ctx).toLowerCase(Locale.ROOT);
        return text.contains("currentlinenumber")
                || text.contains("eolcounter")
                || text.contains("readline()")
                || text.contains("getline")
                || text.contains("position");
    }

    private static boolean looksLikeThrowableMessageMutation(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String text = mergedText(ctx).toLowerCase(Locale.ROOT);
        return text.contains("throwable_message")
                || text.contains("getmessage()")
                || text.contains("message");
    }

    private static boolean looksLikeCsvLexerTokenMutation(Context ctx) {
        if (ctx == null) {
            return false;
        }
        String text = (safe(ctx.mutationClassName) + "\n"
                + safe(ctx.entryClassName) + "\n"
                + safe(ctx.mutationMethodSig) + "\n"
                + safe(ctx.entryMethodSig) + "\n"
                + mergedText(ctx)).toLowerCase(Locale.ROOT);
        return text.contains("lexer")
                && text.contains("token")
                && (text.contains("parsesimpletoken") || text.contains("nexttoken"));
    }

    private static boolean looksLikeTokenTrimMutation(Context ctx) {
        String text = (safe(ctx.diff) + "\n" + safe(ctx.mutationMethodSig)).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        return text.contains("ignoresurroundingspaces")
                && text.contains("trimtrailingspaces")
                && (text.contains("=>if(true)") || text.contains("if(true)"));
    }

    private static boolean looksLikeTrimTrailingSpacesMutation(Context ctx) {
        String text = (safe(ctx.mutationMethodSig) + "\n" + safe(ctx.entryMethodSig) + "\n" + safe(ctx.diff))
                .toLowerCase(Locale.ROOT);
        return text.contains("trimtrailingspaces")
                && text.contains("stringbuilder")
                && (text.contains("length") || text.contains("setlength") || text.contains("buffer.length"));
    }

    private static boolean looksLikeTrimTrailingSpacesEquivalentMutation(Context ctx) {
        if (!looksLikeTrimTrailingSpacesMutation(ctx)) {
            return false;
        }
        String left = diffLeft(ctx.diff).replaceAll("\\s+", "");
        String right = diffRight(ctx.diff).replaceAll("\\s+", "");
        String diff = safe(ctx.diff).replaceAll("\\s+", "");
        return (left.equals("length") && (right.equals("-length") || right.equals("~length")
                || right.equals("length++") || right.equals("length--")))
                || (left.equals("length>0") && right.equals("length!=0"))
                || (left.equals("length!=buffer.length()")
                && (right.equals("length<buffer.length()") || right.equals("length<=buffer.length()") || right.equals("true")))
                || diff.contains("if(length!=buffer.length()){buffer.setLength(length);}=>if(true){buffer.setLength(length);}");
    }

    private static boolean looksLikeCsvLexerEofContractMutation(Context ctx) {
        String left = diffLeft(ctx.diff).replaceAll("\\s+", "");
        String right = diffRight(ctx.diff).replaceAll("\\s+", "");
        String text = (safe(ctx.mutationMethodSig) + "\n" + safe(ctx.diff)).toLowerCase(Locale.ROOT);
        return text.contains("end_of_stream")
                && text.contains("unescaped")
                && left.contains("==Constants.END_OF_STREAM")
                && right.contains("<=Constants.END_OF_STREAM");
    }

    private static boolean looksLikePostfixOverwrittenLocalMutation(Context ctx) {
        String left = diffLeft(ctx.diff).replaceAll("\\s+", "");
        String right = diffRight(ctx.diff).replaceAll("\\s+", "");
        if (left.isEmpty()) {
            return false;
        }
        boolean postfix = right.equals(left + "++") || right.equals(left + "--");
        if (!postfix) {
            return false;
        }
        String method = safe(ctx.mutationMethodSig).toLowerCase(Locale.ROOT);
        String text = mergedText(ctx).toLowerCase(Locale.ROOT);
        return method.contains("parsesimpletoken")
                || method.contains("nexttoken")
                || method.contains("read(char[],int,int)")
                || text.contains("reader.read")
                || text.contains("token.content");
    }

    private static boolean looksLikeLexerNextTokenMutation(Context ctx) {
        String text = (safe(ctx.mutationClassName) + "\n"
                + safe(ctx.entryClassName) + "\n"
                + safe(ctx.mutationMethodSig) + "\n"
                + safe(ctx.entryMethodSig) + "\n"
                + safe(ctx.diff)).toLowerCase(Locale.ROOT);
        return text.contains("lexer")
                && text.contains("nexttoken")
                && text.contains("token");
    }

    private static boolean looksLikeLexerNextTokenCommentKillableMutation(Context ctx) {
        if (!looksLikeLexerNextTokenMutation(ctx)) {
            return false;
        }
        String diff = safe(ctx.diff).replaceAll("\\s+", "");
        return diff.contains("isStartOfLine(lastChar)&&isCommentStart(c)=>isCommentStart(c)");
    }

    private static boolean looksLikeLexerNextTokenCommentEofKillableMutation(Context ctx) {
        if (!looksLikeLexerNextTokenMutation(ctx)) {
            return false;
        }
        String diff = safe(ctx.diff).replaceAll("\\s+", "");
        return diff.contains("token.type=EOF;returntoken;=>returntoken;");
    }

    private static boolean looksLikeLexerNextTokenEmptyLineKillableMutation(Context ctx) {
        if (!looksLikeLexerNextTokenMutation(ctx)) {
            return false;
        }
        String diff = safe(ctx.diff).replaceAll("\\s+", "");
        return diff.contains("lastChar=c;c=reader.read();eol=readEndOfLine(c);if(isEndOfFile(c)){token.type=EOF;returntoken;}=>")
                || diff.contains("lastChar=c;c=reader.read();eol=readEndOfLine(c);=>");
    }

    private static boolean looksLikeLexerNextTokenEquivalentMutation(Context ctx) {
        if (!looksLikeLexerNextTokenMutation(ctx)
                || looksLikeLexerNextTokenCommentKillableMutation(ctx)
                || looksLikeLexerNextTokenCommentEofKillableMutation(ctx)
                || looksLikeLexerNextTokenEmptyLineKillableMutation(ctx)) {
            return false;
        }
        String left = diffLeft(ctx.diff).replaceAll("\\s+", "");
        String right = diffRight(ctx.diff).replaceAll("\\s+", "");
        if (!left.isEmpty() && (right.equals(left + "++") || right.equals(left + "--")
                || right.equals("-" + left) || right.equals("~" + left))) {
            return true;
        }
        String diff = safe(ctx.diff).replaceAll("\\s+", "");
        return diff.contains("isEndOfFile(lastChar)||!isDelimiter(lastChar)&&isEndOfFile(c)=>!isDelimiter(lastChar)&&isEndOfFile(c)");
    }

    private static boolean isWeakObservableExpression(String observableCall) {
        String text = safe(observableCall).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return text.isEmpty()
                || text.contains("nexttoken(null)")
                || text.contains("=subject.nexttoken(")
                || text.contains("assertnotnull")
                || text.contains("exception");
    }

    private static String diffLeft(String diff) {
        int idx = safe(diff).indexOf("=>");
        return idx < 0 ? safe(diff) : diff.substring(0, idx).trim();
    }

    private static String diffRight(String diff) {
        int idx = safe(diff).indexOf("=>");
        return idx < 0 ? safe(diff) : diff.substring(idx + 2).trim();
    }

    private static List<String> extractIdentifiers(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        Matcher m = IDENTIFIER_PATTERN.matcher(safe(text));
        while (m.find()) {
            String token = m.group();
            String lowered = token.toLowerCase(Locale.ROOT);
            if (STOP_WORDS.contains(lowered)) {
                continue;
            }
            if (token.length() <= 1 && Character.isLowerCase(token.charAt(0))) {
                continue;
            }
            out.add(token);
        }
        return new ArrayList<String>(out);
    }

    private static String criticalRole(String token) {
        if (token.contains(".")) {
            return "member_or_method_in_mutated_expression";
        }
        return "local_or_parameter_in_mutated_expression";
    }

    private static String joinCriticalNames(List<Map<String, Object>> items) {
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> item : items) {
            Object field = item.get("name");
            if (field instanceof Map) {
                Object value = ((Map<?, ?>) field).get("item");
                if (value != null) {
                    names.add(String.valueOf(value));
                }
            }
        }
        return String.join(", ", names);
    }

    private static boolean sameMethod(String entryClassName,
                                      String entryMethodSig,
                                      String mutationClassName,
                                      String mutationMethodSig) {
        return normalize(entryClassName).equals(normalize(mutationClassName))
                && normalize(entryMethodSig).equals(normalize(mutationMethodSig));
    }

    private static String normalize(String text) {
        return safe(text).replaceAll("\\s+", "").replace('$', '.');
    }

    private static boolean isConstructor(String signature) {
        String sig = safe(signature);
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        return lp > 0 && !(us > 0 && us < lp);
    }

    private static List<String> splitCallChain(String chain) {
        if (isBlank(chain)) {
            return Collections.emptyList();
        }
        String[] parts = chain.split("\\s*->\\s*");
        List<String> out = new ArrayList<String>();
        for (String part : parts) {
            if (!isBlank(part)) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static List<String> extractParameterTypes(String signature) {
        String sig = safe(signature);
        int lp = sig.indexOf('(');
        int rp = sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) {
            return Collections.emptyList();
        }
        String inside = sig.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = inside.split("\\s*,\\s*");
        return Arrays.asList(parts);
    }

    private static boolean containsType(String all, Collection<String> paramTypes, String expected) {
        String e = expected.toLowerCase(Locale.ROOT);
        if (safe(all).toLowerCase(Locale.ROOT).contains(e)) {
            return true;
        }
        for (String type : paramTypes) {
            if (safe(type).toLowerCase(Locale.ROOT).contains(e)) {
                return true;
            }
        }
        return false;
    }

    private static String mergedText(Context ctx) {
        return safe(ctx.diff) + "\n"
                + safe(ctx.receiverConstruction) + "\n"
                + safe(ctx.receiverSetupTemplate) + "\n"
                + safe(ctx.receiverInvocationTemplate) + "\n"
                + safe(ctx.observableCall) + "\n"
                + safe(ctx.observableReason) + "\n"
                + safe(ctx.availablePublicMethods) + "\n"
                + safe(ctx.stateSetupPlan);
    }

    private static boolean requiresInitializeHeaderSetup(Context ctx) {
        String text = safe(ctx.mutationMethodSig) + "\n" + safe(ctx.diff);
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("initializeheader")
                || lower.contains("formatheader")
                || lower.contains("headerrecord")
                || lower.contains("containsheader")
                || lower.contains("allowmissingcolumnnames")
                || lower.contains("emptyheader");
    }

    private static boolean isConstructorExceptionMutation(Context ctx) {
        String lower = safe(ctx.diff).toLowerCase(Locale.ROOT);
        if (!requiresInitializeHeaderSetup(ctx) || !isConstructor(ctx.entryMethodSig)) {
            return false;
        }
        return lower.contains("emptyheader")
                || lower.contains("containsheader")
                || lower.contains("allowmissingcolumnnames")
                || lower.contains("header == null")
                || lower.contains("header.trim().isempty()");
    }

    private static boolean isLikelyEquivalentMutation(Context ctx) {
        String left = diffLeft(ctx.diff).replaceAll("\\s+", "");
        String right = diffRight(ctx.diff).replaceAll("\\s+", "");
        if (looksLikeTrimTrailingSpacesEquivalentMutation(ctx)) {
            return true;
        }
        if (looksLikeLexerNextTokenEquivalentMutation(ctx)) {
            return true;
        }
        if (looksLikePostfixOverwrittenLocalMutation(ctx)) {
            return true;
        }
        if (looksLikeCsvLexerEofContractMutation(ctx)) {
            return true;
        }
        if ((left.equals("formatHeader.length") && right.equals("-formatHeader.length"))
                || (left.equals("-formatHeader.length") && right.equals("formatHeader.length"))) {
            return true;
        }
        if (left.equals("formatHeader.length==0") && right.equals("formatHeader.length<=0")) {
            return true;
        }
        if (left.equals("i<headerRecord.length") && right.equals("i!=headerRecord.length")) {
            return true;
        }
        return left.equals("!emptyHeader||emptyHeader&&!this.format.getAllowMissingColumnNames()")
                && right.equals("!emptyHeader^(emptyHeader&&!this.format.getAllowMissingColumnNames())");
    }

    private static String equivalenceSuspicionReason(Context ctx) {
        String left = diffLeft(ctx.diff).replaceAll("\\s+", "");
        String right = diffRight(ctx.diff).replaceAll("\\s+", "");
        if (looksLikeTrimTrailingSpacesEquivalentMutation(ctx)) {
            return "trimTrailingSpaces uses a monotonic non-negative length index and setLength(length). The mutated forms only add idempotent setLength calls or change conditions to equivalent reachable states, so StringBuilder content is unchanged.";
        }
        if (looksLikeLexerNextTokenEquivalentMutation(ctx)) {
            return "The nextToken mutation changes a local EOF/delimiter/postfix expression whose altered value either falls through to the same Token state or is overwritten before any public Token field can observe it.";
        }
        if (looksLikePostfixOverwrittenLocalMutation(ctx)) {
            return "The mutation changes a local variable to a postfix increment/decrement expression. The expression still yields the original value, and the local side effect is overwritten before the token state can observe it.";
        }
        if (looksLikeCsvLexerEofContractMutation(ctx)) {
            return "readEscape returns END_OF_STREAM or valid character values; it does not produce values below END_OF_STREAM, so == END_OF_STREAM and <= END_OF_STREAM are equivalent under the helper contract.";
        }
        if ((left.equals("formatHeader.length") && right.equals("-formatHeader.length"))
                || (left.equals("-formatHeader.length") && right.equals("formatHeader.length"))) {
            return "Negating an integer expression does not change the truth of an equality-to-zero check when the compared value is zero iff its negation is zero.";
        }
        if (left.equals("formatHeader.length==0") && right.equals("formatHeader.length<=0")) {
            return "Array length is never negative in Java, so == 0 and <= 0 are equivalent here.";
        }
        if (left.equals("i<headerRecord.length") && right.equals("i!=headerRecord.length")) {
            return "The loop index starts at 0 and increases by one, so the mutated condition is equivalent on all reachable loop states.";
        }
        if (left.equals("!emptyHeader||emptyHeader&&!this.format.getAllowMissingColumnNames()")
                && right.equals("!emptyHeader^(emptyHeader&&!this.format.getAllowMissingColumnNames())")) {
            return "The two boolean subexpressions are mutually exclusive, so OR and XOR evaluate identically on all reachable states.";
        }
        return "Static pattern heuristics found a likely semantic equivalence in the mutation diff.";
    }

    private static List<Map<String, Object>> buildInitializeHeaderExceptionConstraints(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String diff = safe(ctx.diff).replaceAll("\\s+", "");
        if (diff.contains("!emptyHeader=>emptyHeader")) {
            out.add(constraint("duplicate_non_empty_header",
                    "Use explicit duplicate non-empty headers such as withHeader(\"A\", \"B\", \"A\"). Original should throw, mutant tends to accept.",
                    "The negated non-empty duplicate guard is flipped."));
        } else if (diff.contains("!this.format.getAllowMissingColumnNames()=>this.format.getAllowMissingColumnNames()")) {
            out.add(constraint("duplicate_empty_header_policy",
                    "Use duplicate empty headers with allowMissingColumnNames=true, e.g. withHeader(\"\", \"B\", \"\").withAllowMissingColumnNames(true).",
                    "Original allows repeated empty names when missing column names are allowed; the mutant flips that policy and should throw."));
        } else if (diff.contains("header==null||header.trim().isEmpty()=>!(header==null||header.trim().isEmpty())")) {
            out.add(constraint("empty_header_classification",
                    "Use empty or blank duplicate header names so the original treats them as empty while the mutant misclassifies them as non-empty.",
                    "The emptiness predicate is inverted, which changes whether duplicate empty headers are permitted."));
        } else if (diff.contains("emptyHeader&&!this.format.getAllowMissingColumnNames()=>emptyHeader||!this.format.getAllowMissingColumnNames()")) {
            out.add(constraint("duplicate_header_vs_missing_policy",
                    "Use duplicate non-empty headers with allowMissingColumnNames=true so the original throws but the mutant weakens the duplicate-header guard.",
                    "The mutant broadens the empty-header subcondition and can suppress the original duplicate-name exception."));
        } else if (diff.contains("emptyHeader&&!this.format.getAllowMissingColumnNames()=>emptyHeader^!this.format.getAllowMissingColumnNames()")) {
            out.add(constraint("duplicate_empty_header_xor",
                    "Use duplicate empty headers with allowMissingColumnNames=true so the original accepts but the mutant throws.",
                    "Replacing && with ^ makes the guard disagree exactly on the empty-header/allow-missing combination."));
        } else if (diff.contains("!emptyHeader||emptyHeader&&!this.format.getAllowMissingColumnNames()=>!emptyHeader&&(emptyHeader&&!this.format.getAllowMissingColumnNames())")) {
            out.add(constraint("duplicate_header_guard_removed",
                    "Use any explicit duplicate header, especially withHeader(\"A\", \"B\", \"A\"), to make the original throw while the mutant accepts.",
                    "The mutant effectively removes the normal duplicate-name rejection path."));
        }
        return out;
    }

    private static boolean containsAny(String text, String... parts) {
        String s = safe(text);
        for (String part : parts) {
            if (s.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String extractObservedExpression(String observableCall) {
        String text = safe(observableCall).trim();
        int eq = text.indexOf('=');
        if (eq > 0) {
            String lhs = text.substring(0, eq).trim();
            String[] tokens = lhs.split("\\s+");
            return tokens.length == 0 ? lhs : tokens[tokens.length - 1];
        }
        return text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String stringItem(Object node) {
        if (node == null) {
            return "";
        }
        if (node instanceof Map<?, ?>) {
            Object item = ((Map<?, ?>) node).get("item");
            return item == null ? "" : String.valueOf(item);
        }
        return String.valueOf(node);
    }

    private static List<String> stringItems(Object node) {
        Object value = node;
        if (value instanceof Map<?, ?>) {
            value = ((Map<?, ?>) value).get("items");
        }
        if (!(value instanceof Collection<?>)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Object item : (Collection<?>) value) {
            if (item != null && !String.valueOf(item).trim().isEmpty()) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static Map<String, Object> commented(String comment, Object item) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("comment", comment);
        out.put("item", item);
        return out;
    }

    private static Map<String, Object> commentedList(String comment, Collection<?> items) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("comment", comment);
        out.put("items", items == null ? Collections.emptyList() : items);
        return out;
    }
}
