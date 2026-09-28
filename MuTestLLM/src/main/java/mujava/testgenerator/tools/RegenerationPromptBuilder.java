package mujava.testgenerator.tools;

import java.util.Collections;
import java.util.List;

import org.json.JSONObject;

import mujava.rl.EvidenceAction;
import mujava.rl.SuccessTestReference;
import static mujava.testgenerator.tools.InitialPromptBuilder.appendCompactSection;
import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Builds a second-pass prompt for compiled-but-live tests.
 */
public final class RegenerationPromptBuilder {
    public enum RegenerationStrategy {
        INPUT_STRENGTHEN,
        ASSERTION_STRENGTHEN,
        OBSERVABLE_SWITCH,
        EXCEPTION_ORACLE,
        REFERENCE_GUIDED_TARGET_KILL
    }

    private RegenerationPromptBuilder() {
    }

    public static String build(Request request,
                               PromptEvidence e,
                               String testSetName,
                               String previousCode,
                               String targetStatus,
                               String failureReason,
                               EvidenceAction action,
                               int round,
                               RegenerationStrategy strategy,
                               List<SuccessTestReference> references) {
        String packageName = packageNameOf(testSetName);
        String simpleClassName = simpleNameOf(testSetName);
        List<SuccessTestReference> refs = references == null
                ? Collections.<SuccessTestReference>emptyList()
                : references;
        RegenerationStrategy effectiveStrategy = strategy == null
                ? RegenerationStrategy.INPUT_STRENGTHEN
                : strategy;
        String semanticStage = semanticStageName(round);

        StringBuilder sb = new StringBuilder(24576);
        MavenCompilerConfigResolver.CompilerLevel compilerLevel =
                MavenCompilerConfigResolver.resolve(request == null ? "" : request.sourceModuleHome);
        if (compilerLevel.isSpecified()) {
            sb.append("Java language/API compatibility must follow the tested module POM: ")
                    .append(compilerLevel.promptDescription())
                    .append(". Do not use syntax or APIs above this level.\n\n");
        }
        sb.append("Regenerate the Java JUnit4 test to kill the target mutant.\n");
        sb.append("The previous generated test compiled and ran on the original program but did not kill the mutant.\n");
        sb.append("Return only one complete Java source file. Do not include markdown fences or explanations.\n\n");

        sb.append("Hard constraints:\n");
        sb.append("- Use JUnit4 only. No Mockito, AssertJ, Truth, or extra third-party libraries.\n");
        sb.append("- Generate exactly one public class named ").append(simpleClassName).append(".\n");
        if (!packageName.isEmpty()) {
            sb.append("- The test must start with: package ").append(packageName).append(";\n");
        }
        sb.append("- Keep the test deterministic, short, and compilable in the existing project.\n");
        sb.append("- Preserve the target intent: kill mutant ").append(request.mutantName)
                .append(" for method ").append(request.methodSignature).append(".\n");
        sb.append("- Do not repeat the same test structure if it already produced a live mutant.\n\n");
        appendSemanticStageConstraints(sb, round);

        sb.append("Regeneration context:\n");
        sb.append("semanticStage = ").append(semanticStage).append('\n');
        sb.append("round = ").append(round).append('\n');
        sb.append("selectedEvidenceAction = ").append(action == null ? "" : action.name()).append('\n');
        sb.append("regenerationStrategy = ").append(effectiveStrategy.name()).append('\n');
        sb.append("previousTargetStatus = ").append(targetStatus == null ? "" : targetStatus).append('\n');
        sb.append("previousFailureReason = ").append(failureReason == null ? "" : failureReason).append("\n\n");

        if (refs.isEmpty()) {
            sb.append("Exploration guidance:\n");
            sb.append("- Produce a materially different test from the previous one.\n");
            if (round <= 1) {
                sb.append("- Keep B/R/I/C fixed and materially redesign only the oracle/assertion over the same C.\n");
            } else {
                sb.append("- Keep B/R/I fixed; because the earlier oracle relaxation stayed live, choose a stronger evidence-backed observable C and rebuild O around it.\n");
            }
            sb.append("- Use PRIMARY_CHAIN, ALTERNATIVE_CHAINS, EXECUTABLE_TEST_PLAN, OBSERVABLE_PLAN, COMPILATION_FACTS, MUTATION bodies, and CODE_KB_CONTEXT to search for a stronger killing check.\n");
            sb.append("- Do not only rename variables or reformat the same logic.\n\n");
        } else {
            sb.append("Reference guidance:\n");
            sb.append("- The following tests were successful same-method kills for nearby mutants.\n");
            sb.append("- Borrow reusable setup/assertion ideas only when they remain valid for the current mutant.\n");
            sb.append("- Do not copy irrelevant values blindly. Adapt them to the current mutation evidence.\n");
            sb.append("- Keep one final test class only: ").append(simpleClassName).append(".\n\n");
            int idx = 1;
            for (SuccessTestReference ref : refs) {
                sb.append("REFERENCE_TEST_").append(idx).append(":\n");
                sb.append("testName = ").append(ref.testName).append('\n');
                sb.append("sourceAction = ").append(ref.action == null ? "" : ref.action).append('\n');
                sb.append("reward = ").append(ref.reward).append('\n');
                sb.append(ref.code == null ? "" : ref.code).append("\n\n");
                idx++;
            }
        }

        appendStrategyGuidance(sb, effectiveStrategy, e, refs);

        sb.append("Target metadata:\n");
        sb.append("targetClassName = ").append(request.targetClassName).append('\n');
        sb.append("methodSignature = ").append(request.methodSignature).append('\n');
        sb.append("mutantName = ").append(request.mutantName).append('\n');
        sb.append("testFqn = ").append(testSetName).append("\n\n");

        appendCompactSection(sb, "ENTRY", e.entry);
        appendCompactSection(sb, "CANONICAL_CORE", e.canonicalCore);
        appendCompactSection(sb, "ENTRY_SELECTION", e.entrySelection);
        appendCompactSection(sb, "EXECUTABLE_TEST_PLAN", e.executableTestPlan);
        appendCompactSection(sb, "INVOCATION_WITH_RECEIVER_AND_STUB_RULES", e.invocation);
        appendCompactSection(sb, "COMPILATION_FACTS", e.compilationFacts);
        appendCompactSection(sb, "PRIMARY_CHAIN", e.primaryChain);
        appendCompactSection(sb, "ALTERNATIVE_CHAINS", e.alternativeChains);
        appendCompactSection(sb, "OBSERVABLE_PLAN", e.observablePlan);
        appendCompactSection(sb, "ASSERTIONS", e.assertions);
        appendCompactSection(sb, "API_ROLE_FACTS", e.apiRoleFacts());
        appendCompactSection(sb, "MUTATION", e.mutation);
        appendCompactSection(sb, "MUTATION_GRAPH_EVIDENCE_A_CPG", e.mutationGraphEvidence);
        appendCompactSection(sb, "MUTATION_EVIDENCE", e.mutationEvidence);
        appendCompactSection(sb, "EVIDENCE_QUALITY", e.evidenceQuality);
        appendCompactSection(sb, "CODE_KB_CONTEXT", e.codeKbContext);
        appendCompactSection(sb, "RANKED_STRATEGY_CANDIDATES", GenerationStrategyAdvisor.build(request, e));

        if (e.needEntryLiftedEvidence && e.entryEvidence.length() > 0) {
            appendCompactSection(sb, "ENTRY_EVIDENCE", e.entryEvidence);
        }
        if (e.needEntryLiftedEvidence && e.entryGraphEvidence.length() > 0) {
            appendCompactSection(sb, "ENTRY_GRAPH_EVIDENCE_B_CPG", e.entryGraphEvidence);
        }
        if (e.liftedReachabilityPlan.length() > 0) {
            appendCompactSection(sb, "LIFTED_REACHABILITY_PLAN", e.liftedReachabilityPlan);
        }

        appendCodeKbGuidance(sb, e, effectiveStrategy);
        appendObservablePlanGuidance(sb, e);
        appendReachabilityAndInputGuidance(sb, e, effectiveStrategy);
        appendIndirectEntryGuidance(sb, e, effectiveStrategy);
        appendKillPlanGuidance(sb, e, effectiveStrategy);

        sb.append("Previous generated live test:\n");
        sb.append(previousCode == null ? "" : previousCode).append("\n\n");
        sb.append("Return only the Java source code for the regenerated test class.\n");
        return sb.toString();
    }

    private static String semanticStageName(int round) {
        return round <= 1 ? "RELAX_ORACLE" : "RELAX_OBSERVABLE_ORACLE";
    }

    private static void appendSemanticStageConstraints(StringBuilder sb, int round) {
        sb.append("Semantic constraint policy:\n");
        sb.append("- COMPILATION_FACTS remain HARD in every round: exact package, visibility, constructor/method signatures, static-vs-instance usage, and accessible APIs must not be contradicted.\n");
        sb.append("- CANONICAL_CORE.B, mandatory reachability R, and semantic infection I are SEMANTIC_INVARIANTS in every regeneration round. Do not change the selected entry B, flip requiredEvaluation, drop mandatory cofactors, or erase the original-vs-mutant infection condition.\n");
        sb.append("- Before emitting Java code, choose concrete values that satisfy the mandatory R/I constraints and make semanticOriginalExpression differ from semanticMutantExpression.\n");
        if (round <= 1) {
            sb.append("- semanticStage=RELAX_ORACLE: keep the selected observable C fixed, but O is no longer immutable. Redesign the JUnit assertion/oracle over the SAME C so it is true on the original program and distinguishes the mutant.\n");
            sb.append("- PRIMARY_CHAIN.oracle.observableCall, when present, remains the required C in this round. You may strengthen exact expected values, exception conditions/messages, collection/text content, tolerances, or state predicates without switching C.\n");
            sb.append("- Do not switch to another return/state/exception/stdout sink in this round.\n");
        } else {
            sb.append("- semanticStage=RELAX_OBSERVABLE_ORACLE: B/R/I remain fixed, but the earlier C/O plan is now only preferred, not mandatory. You may replace C and rebuild O when the previous compiled test stayed LIVE.\n");
            sb.append("- Any replacement C must be evidence-backed. Search ALTERNATIVE_CHAINS, OBSERVABLE_PLAN, MUTATION.bodies.original/mutant, CODE_KB_CONTEXT, public accessors/state, return values, thrown exceptions, System.out/System.err, or externally mutated argument/sink state.\n");
            sb.append("- Prefer an observable that directly differs after the same mutation-sensitive execution. For void methods, explicitly consider stdout/stderr, public state, mutable argument/sink state, and exception-vs-normal completion.\n");
            sb.append("- Do not invent getters/helpers or access private fields directly. Use COMPILATION_FACTS/PUBLIC_API to reach the chosen observable.\n");
            sb.append("- Do not switch selected entry B in this round. Entry relaxation is intentionally disabled by default.\n");
        }
        sb.append("- expectedOriginal must describe behavior that the original program actually exhibits. expectedMutant is contrast evidence and must never be asserted as the original expectation.\n");
        sb.append("- Do not use object-reference inequality such as result != baselineValue for String/object oracles; prefer mutation-sensitive semantic assertions.\n\n");
    }

    private static void appendCodeKbGuidance(StringBuilder sb,
                                             PromptEvidence e,
                                             RegenerationStrategy strategy) {
        if (e == null) {
            return;
        }
        JSONObject compilableFacts = e.codeKbCompilableApiFacts();
        if (!e.hasCodeKbContext() && compilableFacts.length() == 0) {
            return;
        }
        sb.append("CodeKB regeneration guidance:\n");
        if (e.hasCodeKbContext()) {
            sb.append("- CODE_KB_CONTEXT comes from direct Java source parsing, not only legacy output.json heuristics.\n");
            sb.append("- Candidate public entries: ").append(e.codeKbEntryCount()).append(".\n");
            if (!e.codeKbTopEntrySignature().trim().isEmpty()) {
                sb.append("- Highest-ranked candidate entry: ").append(e.codeKbTopEntrySignature()).append("\n");
            }
            if (!e.codeKbTopEntryReason().trim().isEmpty()) {
                sb.append("- Why it ranks first: ").append(e.codeKbTopEntryReason()).append("\n");
            }
            sb.append("- Parsed mutant method calls: ").append(e.codeKbMethodCallCount()).append(".\n");
            sb.append("- Parsed mutant field accesses: ").append(e.codeKbFieldAccessCount()).append(".\n");
            if (!e.codeKbTopMethodCalls(3).isEmpty()) {
                sb.append("- Representative mutated call-chain methods: ").append(e.codeKbTopMethodCalls(3)).append("\n");
            }
            if (!e.codeKbTopFieldAccesses(4).isEmpty()) {
                sb.append("- Representative mutated field/state accesses: ").append(e.codeKbTopFieldAccesses(4)).append("\n");
            }
        } else {
            sb.append("- Ranked CodeKB entry candidates are unavailable for this mutant, but exact source-derived callable facts still exist.\n");
        }
        if (!e.preferredAssertionMode().trim().isEmpty()) {
            sb.append("- Preferred assertion mode: ").append(e.preferredAssertionMode()).append("\n");
        }
        if (!e.mutationSemanticKind().trim().isEmpty()) {
            sb.append("- Mutation semantic kind: ").append(e.mutationSemanticKind()).append("\n");
        }
        if (compilableFacts.length() > 0) {
            sb.append("- Use COMPILABLE_API_FACTS as the compile-safe baseline before trying a different observable/input strategy.\n");
            sb.append("- Keep all regenerated constructor/factory/method calls inside COMPILABLE_API_FACTS.exactCallableSignatures.\n");
        }
        if (e.hasApiRoleFacts()) {
            sb.append("- API_ROLE_FACTS separates construction / execution / observation roles. Keep them separated during regeneration.\n");
            sb.append("- Do not convert a construction factory into the final oracle, and do not replace the execution entry with a secondary observable.\n");
        }
        if (strategy == RegenerationStrategy.OBSERVABLE_SWITCH) {
            sb.append("- For observable-switch rounds, use mutantMethodCalls and mutantFieldAccesses to choose a stronger externally visible oracle instead of repeating the old baseline getter path.\n");
        } else if (strategy == RegenerationStrategy.EXCEPTION_ORACLE) {
            sb.append("- For exception-oracle rounds, keep the top-ranked entry path stable and regenerate around thrown-vs-not-thrown behavior or exception message/content checks.\n");
            sb.append("- Do not let getHeaderMap(), nextRecord(), or record-value assertions become the main oracle unless constructor success on the original is already established and the kill plan explicitly requires a post-state check.\n");
        } else if (strategy == RegenerationStrategy.INPUT_STRENGTHEN) {
            sb.append("- For input-strengthen rounds, keep the high-ranked entry path stable and vary the constructor/setup/input values around the parsed call chain.\n");
        } else if (strategy == RegenerationStrategy.ASSERTION_STRENGTHEN) {
            sb.append("- For assertion-strengthen rounds, keep the reachability path but align the final assertion with the strongest sink implied by CodeKBContext and PROPAGATION_PLAN.\n");
        } else if (strategy == RegenerationStrategy.REFERENCE_GUIDED_TARGET_KILL) {
            sb.append("- When adapting a successful sibling test, prefer the sibling shape only if it still matches the highest-ranked CodeKB entry path for the current mutant.\n");
        }
        sb.append('\n');
    }

    private static void appendStrategyGuidance(StringBuilder sb,
                                               RegenerationStrategy strategy,
                                               PromptEvidence e,
                                               List<SuccessTestReference> refs) {
        if (strategy == null) {
            return;
        }
        sb.append("Strategy-specific guidance:\n");
        switch (strategy) {
            case REFERENCE_GUIDED_TARGET_KILL:
                sb.append("- Reuse the strongest same-method successful references as templates for setup and assertion structure.\n");
                sb.append("- Prefer adapting a successful sibling-mutant test over inventing a brand-new weak sanity test.\n");
                sb.append("- Preserve the current mutant's distinguishingConstraints and observable sink even when borrowing a sibling test shape.\n");
                if (refs != null && !refs.isEmpty()) {
                    sb.append("- The references are already ranked by similarity; prioritize the first one unless another reference fits the current observable better.\n");
                }
                break;
            case OBSERVABLE_SWITCH:
                sb.append("- The previous test likely exercised the method but observed the wrong output.\n");
                sb.append("- Switch to PROPAGATION_PLAN.observableSinkExpression or OBSERVABLE_PLAN.observableCall exactly and align assertions to that observable.\n");
                sb.append("- Remove weak checks like only assertNotNull/assertSame/object identity when a stronger observable is provided.\n");
                if (e != null && e.hasObservableOverride()) {
                    sb.append("- OBSERVABLE_SELECTION_PLAN already selected a stronger observable. Prefer using ")
                            .append(e.preferredObservableCall())
                            .append(" as the main oracle when it improves mutant sensitivity.\n");
                }
                break;
            case EXCEPTION_ORACLE:
                sb.append("- The strongest divergence is exception behavior, not a weak post-state check.\n");
                sb.append("- Regenerate around a concrete setup where the original and mutant disagree on constructor/call success.\n");
                sb.append("- Prefer assertThrows / fail-on-no-exception structure, and only keep nextRecord/getHeaderMap checks as secondary confirmation after the primary exception oracle.\n");
                if (e != null && e.hasHeaderSensitiveInputConstraint()) {
                    sb.append("- Use header-sensitive setup values from INPUT_DISTINGUISH_PLAN rather than plain CSVFormat.DEFAULT.\n");
                }
                if (e != null && e.mentionsDuplicateHeaderConstraint()) {
                    sb.append("- Use an explicit duplicate-header configuration. Do not regress to a non-duplicate happy-path CSV input.\n");
                }
                if (e != null && e.mentionsAllowMissingColumnNamesConstraint()) {
                    sb.append("- Toggle allowMissingColumnNames explicitly because the divergence depends on that policy branch.\n");
                }
                break;
            case ASSERTION_STRENGTHEN:
                sb.append("- Keep the construction and call path mostly stable, but make the final assertion mutation-sensitive.\n");
                sb.append("- Replace weak assertions with exact value, text fragment, length/content, or state-difference checks derived from ASSERTION_PLAN, PROPAGATION_PLAN, OBSERVABLE_PLAN, and ASSERTIONS.\n");
                break;
            case INPUT_STRENGTHEN:
            default:
                sb.append("- Keep the observable path, but materially change the concrete inputs and setup values.\n");
                sb.append("- Prefer boundary values, non-default combinations, null/empty/special-character cases, or state combinations suggested by INPUT_DISTINGUISH_PLAN and MUTATION_KILL_PLAN.\n");
                break;
        }
        if (e != null && e.observablePlan != null && e.observablePlan.length() > 0) {
            String kind = e.observablePlan.optString("kind", "").trim();
            if (!kind.isEmpty()) {
                sb.append("- Observable anchor kind: ").append(kind).append(".\n");
            }
        }
        if (e != null && e.hasLockedPrimaryChainObservable() && !e.prefersExceptionAssertion()) {
            sb.append("- PRIMARY_CHAIN proposed observable C: ")
                    .append(e.primaryChainOracleCall()).append(".\n");
            sb.append("- In RELAX_ORACLE keep this C; in RELAX_OBSERVABLE_ORACLE it becomes a preferred candidate rather than an immutable lock.\n");
            if (e.hasExpectedOriginalOracle()) {
                sb.append("- Any regenerated oracle must assert behavior that is true on the original program; expectedMutant remains explanatory only.\n");
            }
        }
        if (e != null && e.prefersOutputAssertion()) {
            sb.append("- preferredAssertionMode=OUTPUT_ASSERTION: capture the selected System.out/System.err stream and make pass/fail depend on mutation-sensitive output text, structure, or presence.\n");
            sb.append("- Restore the original PrintStream in a finally block. Do not weaken an output oracle into completion-only, assertNotNull, or an unrelated exception check.\n");
            sb.append("- In RELAX_ORACLE keep the selected output sink C and strengthen only O; in RELAX_OBSERVABLE_ORACLE another evidence-backed public observable may replace C if the output sink remains non-discriminating.\n");
        }
        if (e != null && e.isReceiverStateDependent()) {
            sb.append("- KILLABILITY_PLAN marks this mutant as RECEIVER_STATE_DEPENDENT. Regenerate around constructor/setup state, then assert a public state-dependent oracle.\n");
            sb.append("- A live test that only proves nextRecord()/construction succeeds is insufficient here.\n");
        }
        sb.append('\n');
    }

    private static void appendKillPlanGuidance(StringBuilder sb,
                                               PromptEvidence e,
                                               RegenerationStrategy strategy) {
        if (e == null) {
            return;
        }
        if (e.mutationKillPlan.length() == 0
                && e.inputDistinguishPlan.length() == 0
                && e.propagationPlan.length() == 0
                && e.assertionPlan.length() == 0
                && e.ripExecutionPlan.length() == 0) {
            return;
        }
        sb.append("Kill-plan regeneration guidance:\n");
        sb.append("- Preserve the semantic invariants B/R/I. Repair the failed live test only in the dimensions allowed by the current semantic stage: O first, then C+O.\n");
        sb.append("- Do not regenerate another null-only/NPE-only test unless RIP_EXECUTION_PLAN explicitly says null handling is the mutation-sensitive behavior.\n");
        sb.append("- The previous test stayed LIVE, so do not reuse only null/default placeholders when INPUT_DISTINGUISH_PLAN suggests stronger concrete inputs.\n");
        sb.append("- Prefer ASSERTION_PLAN.primaryAssertions and PROPAGATION_PLAN.observableSinkExpression over weak sanity checks.\n");
        sb.append("- Respect MUTATION_KILL_PLAN.preferredAssertionMode when deciding whether to change inputs, observable, or assertion style.\n");
        if (e.hasEquivalenceSuspicion()) {
            sb.append("- EVIDENCE_QUALITY reports equivalenceSuspicion=true. Treat it as a heuristic warning, not a proof.\n");
            sb.append("- First re-evaluate whether the mutant can diverge on any reachable state. If it still looks equivalent, do not just reshuffle assertions around the same behavior.\n");
            if (!e.equivalenceSuspicionReason().trim().isEmpty()) {
                sb.append("- Static suspicion reason: ").append(e.equivalenceSuspicionReason()).append("\n");
            }
        }
        if (e.isReceiverStateDependent()) {
            sb.append("- Because the mutant is receiver-state-dependent, the final assertion must target externally visible state after construction/setup. Do not let baseline behavior checks become the final oracle.\n");
            if (e.hasObservableOverride()) {
                sb.append("- Favor the overridden observable when it provides a stronger kill assertion: ")
                        .append(e.preferredObservableCall())
                        .append("\n");
            }
        }
        if (strategy == RegenerationStrategy.INPUT_STRENGTHEN) {
            sb.append("- Focus this round on distinguishingConstraints, boundaryValueFamilies, and preferredConcreteInputs.\n");
        } else if (strategy == RegenerationStrategy.ASSERTION_STRENGTHEN) {
            sb.append("- Focus this round on primaryAssertions, assertionTemplates, and antiPatterns.\n");
        } else if (strategy == RegenerationStrategy.OBSERVABLE_SWITCH) {
            sb.append("- Focus this round on the propagation chain and the strongest public observable sink.\n");
        } else if (strategy == RegenerationStrategy.EXCEPTION_ORACLE) {
            sb.append("- Focus this round on preferredAssertionMode=EXCEPTION_ASSERTION, distinguishing thrown-vs-not-thrown behavior, and exact exception content when available.\n");
            sb.append("- Do not accept a regenerated test that merely proves parsing succeeds. It must make pass/fail depend on the constructor or target call exception split.\n");
        } else if (strategy == RegenerationStrategy.REFERENCE_GUIDED_TARGET_KILL) {
            sb.append("- Keep the sibling-test shape only if it still satisfies the current mutant's kill-plan constraints.\n");
        }
        if (e.liftedReachabilityPlan.length() > 0) {
            sb.append("- Use LIFTED_REACHABILITY_PLAN to keep the B -> A path valid while changing inputs/assertions.\n");
        }
        sb.append('\n');
    }

    private static void appendObservablePlanGuidance(StringBuilder sb, PromptEvidence e) {
        if (e == null || e.observablePlan == null || e.observablePlan.length() == 0) {
            return;
        }
        String kind = e.observablePlan.optString("kind", "").trim();
        if (kind.isEmpty()) {
            return;
        }
        sb.append("Observable-plan guidance:\n");
        sb.append("- OBSERVABLE_PLAN.kind = ").append(kind).append("\n");
        if ("CONSTRUCTOR_PUBLIC_GETTER_OBSERVABLE".equals(kind)
                || "RECOVERED_CONSTRUCTOR_PUBLIC_OBSERVER".equals(kind)) {
            sb.append("- Regenerate around a constructor-plus-public-accessor pattern. Keep construction valid, then assert the accessor result.\n");
        } else if ("CONSTRUCTOR_PUBLIC_METHOD_DEPENDS_ON_STATE".equals(kind)) {
            sb.append("- Regenerate around the provided public behavior method. Do not keep a live test that only checks construction or object identity.\n");
        } else if ("REFLECTION_FIELD_READ_AFTER_CONSTRUCTION".equals(kind)
                || "REFLECTION_FIELD_READ_AFTER_SETTER".equals(kind)) {
            sb.append("- If you use reflection, copy the reflection setup and observable call exactly from OBSERVABLE_PLAN. Do not invent a getter.\n");
        } else if ("THROWABLE_MESSAGE".equals(kind)) {
            sb.append("- The strongest regeneration path is to assert the constructed message text rather than weak sanity checks.\n");
        } else if ("EXTERNAL_MUTABLE_SINK_TO_STRING".equals(kind)
                || "CONSTRUCTOR_EXTERNAL_SINK_STATE".equals(kind)) {
            sb.append("- Regenerate around the external sink content. Assert rendered text/content rather than sink object identity or a weak return value.\n");
        } else if ("MUTABLE_PARAMETER_STATE".equals(kind)) {
            sb.append("- The regeneration should assert the mutated post-call state of the mutable argument, not only the method return value.\n");
        } else if ("EXCEPTION_BEHAVIOR".equals(kind)) {
            sb.append("- Regenerate with an explicit precondition-violating or null input and assert thrown-vs-not-thrown behavior.\n");
        }
        if (e.hasObservableOverride()) {
            sb.append("- OBSERVABLE_SELECTION_PLAN overrode the baseline observable. Prefer using ")
                    .append(e.preferredObservableCall())
                    .append(" as the main oracle when it improves mutant sensitivity.\n");
            sb.append("- If the previous test mixed this with baseline checks, keep whichever observable yields the clearer mutation-sensitive pass/fail condition.\n");
        }
        sb.append('\n');
    }

    private static void appendReachabilityAndInputGuidance(StringBuilder sb,
                                                           PromptEvidence e,
                                                           RegenerationStrategy strategy) {
        if (e == null) {
            return;
        }
        if (e.hasReachabilityGuards()) {
            sb.append("Reachability-guard regeneration guidance:\n");
            sb.append("- Use REACHABILITY_GUARDS_PLAN.mustPassBeforeMutation as reachability guidance before asserting anything.\n");
            if (e.hasFrontLoadedExceptionRisk()) {
                sb.append("- The previous live test may have stopped at a front-door exception or guard. Regenerate around the deeper normal path instead.\n");
            }
            if (strategy == RegenerationStrategy.EXCEPTION_ORACLE && !e.prefersExceptionAssertion()) {
                sb.append("- Do not force an exception oracle if OBSERVABLE_DIFFERENCE_PLAN says the stronger difference is a return/state/output sink.\n");
            }
            sb.append('\n');
        }
        if (e.hasBoundarySensitiveInputGuidance()) {
            sb.append("Distinguishing-input regeneration guidance:\n");
            sb.append("- Change concrete values so they satisfy DISTINGUISHING_INPUT_GUIDANCE, not just generic edge cases.\n");
            if (strategy == RegenerationStrategy.INPUT_STRENGTHEN) {
                sb.append("- This round should vary the mutation-sensitive boundaries and concrete setups first.\n");
            }
            sb.append('\n');
        }
        if (e.hasSymbolicRipPlan()) {
            sb.append("Symbolic-RIP regeneration guidance:\n");
            sb.append("- Use SYMBOLIC_RIP_PLAN.pathConstraints and preferredAssignments as optional hints for rebuilding the test around a mutation-feasible path.\n");
            sb.append("- If symbolic evidence conflicts with a clearer compilable test shape, prefer the stronger end-to-end mutant-killing test.\n\n");
        }
        if (e.requiresRealEntryChain()) {
            sb.append("Entry-chain regeneration guidance:\n");
            sb.append("- Preserve the real public entry/call-chain from ENTRY_CHAIN_PLAN. Do not switch to direct helper/private invocation during regeneration.\n\n");
        }
    }

    private static void appendIndirectEntryGuidance(StringBuilder sb,
                                                    PromptEvidence e,
                                                    RegenerationStrategy strategy) {
        if (e == null || !e.hasIndirectEntryTestPlan()) {
            return;
        }
        sb.append("Indirect-entry regeneration guidance:\n");
        sb.append("- This mutant is only observable through a public entry that indirectly reaches a private/helper method.\n");
        sb.append("- entryControlType = ").append(e.indirectEntryControlType()).append("\n");
        if (e.requiresStateShaping()) {
            sb.append("- Rebuild the receiver/internal state instead of recycling the old default setup. Targets: ")
                    .append(e.stateShapingTargets(6)).append("\n");
        }
        if (e.requiresInvocationSequence()) {
            sb.append("- Regenerate around a multi-step public-call sequence; do not keep a one-call test if it stayed LIVE.\n");
        }
        if (!e.observablePriority(5).isEmpty()) {
            sb.append("- Preferred observable order: ").append(e.observablePriority(5)).append("\n");
        }
        if (strategy == RegenerationStrategy.OBSERVABLE_SWITCH) {
            sb.append("- For observable-switch rounds, prefer sequence-sensitive or exception/state-delta observables over repeating the same first-return assertion.\n");
        } else if (strategy == RegenerationStrategy.ASSERTION_STRENGTHEN) {
            sb.append("- For assertion-strengthen rounds, keep the reachability path but move the pass/fail decision to the strongest indirect-entry observable.\n");
        } else if (strategy == RegenerationStrategy.INPUT_STRENGTHEN) {
            sb.append("- For input-strengthen rounds, vary receiver state and call ordering, not just scalar literals.\n");
        }
        sb.append('\n');
    }
}
