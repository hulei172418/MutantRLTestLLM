package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Builds the first-generation prompt from compact PromptEvidence.
 */
public final class InitialPromptBuilder {
    private InitialPromptBuilder() {
    }

    public static String build(Request request, PromptEvidence e, String testSetName) {
        String packageName = packageNameOf(testSetName);
        String simpleClassName = simpleNameOf(testSetName);

        StringBuilder sb = new StringBuilder(16384);
        sb.append("You are generating one Java JUnit4 test class to kill one Java mutant.\n");
        sb.append("Return only one complete Java source file. Do not include markdown fences or explanations.\n\n");

        sb.append("Hard requirements:\n");
        sb.append("1. Use JUnit4 only. Do not use Mockito, AssertJ, Truth, PowerMock, or extra libraries.\n");
        sb.append("2. Generate exactly one public class named ").append(simpleClassName).append(".\n");
        if (!packageName.isEmpty()) {
            sb.append("3. The test must start with: package ").append(packageName).append(";\n");
        } else {
            sb.append("3. Use the default package.\n");
        }
        sb.append("4. Keep the test deterministic, short, and self-contained.\n");
        sb.append("5. Do not call private members directly unless the evidence explicitly provides a reflection plan.\n");
        sb.append("6. Do not assign the result of a void method.\n");
        sb.append("7. Prefer declaring each @Test method as throws Exception unless the evidence proves the whole setup/call path has no checked exceptions.\n\n");
        MavenCompilerConfigResolver.CompilerLevel compilerLevel =
                MavenCompilerConfigResolver.resolve(request == null ? "" : request.sourceModuleHome);
        if (compilerLevel.isSpecified()) {
            sb.append("- Java language/API compatibility must follow the tested module POM: ")
                    .append(compilerLevel.promptDescription())
                    .append(". Do not use syntax or APIs above this level.\n");
        }

        sb.append("Evidence interpretation:\n");
        sb.append("- Treat EXECUTABLE_TEST_PLAN and INVOCATION as the concrete construction/call plan. Use them before raw graph text when they are present and syntactically complete.\n");
        sb.append("- Constructor and receiver setup must be type-compatible with the real signature. Do not substitute a raw Reader/StringReader where the constructor requires a project wrapper such as ExtendedBufferedReader.\n");
        sb.append("- Treat COMPILATION_FACTS as the hard compile-time contract: package, imports, exact callable signatures, allowed factories, forbidden calls, visibility, and private-field boundaries. Do not contradict them.\n");
        sb.append("- This is semanticStage=STRICT_CORE. Treat CANONICAL_CORE as the required first-plan B/R/I/C/O contract when present: selected entry B, mandatory reachability R, semantic infection I, observable C, and oracle O. Later survivor regeneration may relax O first, then C+O; exact program/API facts remain hard constraints.\n");
        sb.append("- Before emitting Java code, instantiate concrete values that satisfy every CANONICAL_CORE.reachability.mandatoryGuards.requiredEvaluation, every CANONICAL_CORE.infection.mandatoryCofactors item, and make semanticOriginalExpression differ from semanticMutantExpression before observing C.\n");
        sb.append("- Do not replace the selected entry, flip mandatory guard polarity, drop mandatory cofactors, choose a different primary observable, or reverse the oracle direction.\n");
        if (e.isForcedBranchMutation()) {
            sb.append("- For a forced branch mutation, choose concrete input/state that makes the ORIGINAL condition evaluate opposite to the forced mutant value; otherwise both programs take equivalent behavior.\n");
        }
        sb.append("- Treat PRIMARY_CHAIN as the single highest-value mutant-killing plan. Use it before any fallback evidence.\n");
        sb.append("- If PRIMARY_CHAIN.oracle.observableCall is present, treat it as the locked primary observable C for the final assertion.\n");
        sb.append("- expectedOriginal is the executable JUnit assertion target; expectedMutant explains the mutant divergence and must not replace expectedOriginal.\n");
        sb.append("- Do not use object-reference inequality such as result != baselineValue for String/object oracles; assert the original value exactly.\n");
        sb.append("- Treat ALTERNATIVE_CHAINS as ranked fallbacks only when the primary chain is not compilable or is too weak.\n");
        sb.append("- Treat OBSERVABLE_PLAN and ASSERTIONS as supporting hints, not as a second competing execution plan.\n");
        sb.append("- Treat CODE_KB_CONTEXT as source-derived structural evidence used to support the primary chain and compile-time choices.\n");
        sb.append("- Treat CODE_KB_CONTEXT as structural code knowledge extracted directly from original/mutant Java files. Use its ranked candidateEntries, mutantMethodCalls, and mutantFieldAccesses to prefer realistic public entry methods and observables.\n");
        sb.append("- Treat RANKED_STRATEGY_CANDIDATES as ordered soft strategies. They are compatibility-preserving preferences, not hard facts.\n");
        sb.append("- Treat MUTATION_GRAPH_EVIDENCE_A_CPG as A-side RIP/CPG evidence for why an assertion can kill the mutant.\n");
        sb.append("- Treat ENTRY_GRAPH_EVIDENCE_B_CPG and LIFTED_REACHABILITY_PLAN, when present, as B-side reachability/observation bridge evidence showing how callable entry B reaches mutation method A and exposes A-side divergence.\n\n");

        sb.append("Generation priority:\n");
        sb.append("1. Preserve package/class/JUnit4 requirements.\n");
        sb.append("2. For this STRICT_CORE initial attempt, preserve CANONICAL_CORE B/R/I/C/O. If execution later proves the compiled test LIVE, the regeneration loop—not this initial prompt—may progressively relax O and then C+O.\n");
        sb.append("3. Use EXECUTABLE_TEST_PLAN and INVOCATION only for source-compatible construction/call syntax; replace their illustrative concrete argument values when needed to satisfy CANONICAL_CORE.\n");
        sb.append("4. Follow PRIMARY_CHAIN only as the explainable projection of CANONICAL_CORE; it must not override the core.\n");
        sb.append("5. Honor COMPILATION_FACTS so constructor arguments, exact method signatures, factory usage, and access choices stay source-compatible.\n");
        sb.append("6. Use ALTERNATIVE_CHAINS only if the canonical selected entry is structurally impossible to invoke; never silently switch primary B/C for convenience.\n");
        sb.append("7. Use graph evidence only to support the canonical chain; do not introduce inaccessible internal calls from graph text.\n\n");

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

        appendCodeKbGuidance(sb, e);
        appendObservablePlanGuidance(sb, e);
        appendKillPlanGuidance(sb, e);
        appendReachabilityAndInputGuidance(sb, e);
        appendIndirectEntryGuidance(sb, e);

        sb.append("Final output rule:\n");
        sb.append("Return only the Java source code for the requested test class.\n");

        return sb.toString();
    }

    private static void appendCodeKbGuidance(StringBuilder sb, PromptEvidence e) {
        if (e == null) {
            return;
        }
        JSONObject compilableFacts = e.codeKbCompilableApiFacts();
        if (!e.hasCodeKbContext() && compilableFacts.length() == 0) {
            return;
        }
        sb.append("CodeKB guidance:\n");
        if (e.hasCodeKbContext()) {
            sb.append("- CODE_KB_CONTEXT is enabled and comes from direct original/mutant source parsing.\n");
            sb.append("- Candidate public entries: ").append(e.codeKbEntryCount()).append(".\n");
            if (!e.codeKbTopEntrySignature().trim().isEmpty()) {
                sb.append("- Highest-ranked candidate entry: ").append(e.codeKbTopEntrySignature()).append("\n");
            }
            if (!e.codeKbTopEntryReason().trim().isEmpty()) {
                sb.append("- Why this entry ranks first: ").append(e.codeKbTopEntryReason()).append("\n");
            }
            sb.append("- Parsed mutant method calls: ").append(e.codeKbMethodCallCount()).append(".\n");
            sb.append("- Parsed mutant field accesses: ").append(e.codeKbFieldAccessCount()).append(".\n");
            sb.append("- Ranked observable candidates: ").append(e.codeKbObservableCount()).append(".\n");
            sb.append("- Ranked witness candidates: ").append(e.codeKbWitnessCount()).append(".\n");
            if (!e.codeKbTopMethodCalls(3).isEmpty()) {
                sb.append("- Representative mutated call-chain methods: ").append(e.codeKbTopMethodCalls(3)).append("\n");
            }
            if (!e.codeKbTopFieldAccesses(4).isEmpty()) {
                sb.append("- Representative mutated field/state accesses: ").append(e.codeKbTopFieldAccesses(4)).append("\n");
            }
            if (!e.codeKbTopObservableExpression().trim().isEmpty()) {
                sb.append("- Highest-ranked observable candidate: ").append(e.codeKbTopObservableExpression()).append("\n");
            }
            if (!e.codeKbTopObservableReason().trim().isEmpty()) {
                sb.append("- Why this observable is strong: ").append(e.codeKbTopObservableReason()).append("\n");
            }
            if (!e.codeKbTopWitnesses(2).isEmpty()) {
                sb.append("- Witness sketches: ").append(e.codeKbTopWitnesses(2)).append("\n");
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
            sb.append("- COMPILABLE_API_FACTS comes from the exact source file and must win over guessed APIs.\n");
            sb.append("- Only call constructors/factories/methods whose exact signatures appear in COMPILABLE_API_FACTS.allowed* / exactCallableSignatures.\n");
            sb.append("- Never invent merged pseudo-signatures such as combining a range factory and a negation flag into one method unless that exact signature appears in COMPILABLE_API_FACTS.\n");
            if (compilableFacts.has("forbiddenCalls")) {
                sb.append("- Any signature listed in COMPILABLE_API_FACTS.forbiddenCalls is illegal in the real source file and must not appear in the test.\n");
            }
            if (!e.codeKbAllowedStaticMethods(8).isEmpty()) {
                sb.append("- Exact allowed static factories/methods: ").append(e.codeKbAllowedStaticMethods(8)).append("\n");
            }
            if (!e.codeKbAllowedConstructors(4).isEmpty()) {
                sb.append("- Exact allowed constructors: ").append(e.codeKbAllowedConstructors(4)).append("\n");
            }
            if (!e.codeKbForbiddenCalls(6).isEmpty()) {
                sb.append("- Known forbidden direct calls: ").append(e.codeKbForbiddenCalls(6)).append("\n");
            }
        }
        if (e.hasApiRoleFacts()) {
            sb.append("- API_ROLE_FACTS splits the source facts into construction / execution / observation roles. Do not mix them.\n");
            sb.append("- Use constructionFacts only to build receiver/parameter objects.\n");
            sb.append("- Use executionFacts.callableEntry as the real mutation-triggering call.\n");
            sb.append("- Use observationFacts.primaryOracle first; use secondaryObservables only when the primary oracle is too weak.\n");
        }
        sb.append("- Use these ranked entries to choose the most realistic public constructor/factory/parse path before falling back to weaker getters or generic smoke tests.\n");
        if (e.prefersExceptionAssertion()) {
            sb.append("- This mutant prefers an exception-based oracle. Use thrown-vs-not-thrown behavior as the primary kill condition before weaker post-state checks.\n");
        }
        if (e.hasHeaderSensitiveInputConstraint()) {
            sb.append("- This mutant is header-sensitive. Prefer withHeader(...), duplicate-header, empty-header, and allowMissingColumnNames combinations that satisfy distinguishingConstraints.\n");
        }
        if (e.discouragesDefaultFormatOnly()) {
            sb.append("- Do not rely on plain CSVFormat.DEFAULT alone when it skips the mutated branch.\n");
        }
        if (!e.receiverStrategy().trim().isEmpty()
                && ("FACTORY_OR_REFLECTION_REQUIRED".equals(e.receiverStrategy())
                || "STATIC_FACTORY_BUILDER".equals(e.receiverStrategy())
                || !e.receiverOwnerInstantiable())) {
            sb.append("- Receiver construction is restricted. Prefer INVOCATION.receiver.factoryMethod, INVOCATION.setup, or source-listed static creators in PUBLIC_API.availablePublicMethods before guessing new TargetClass(...).\n");
        }
        if (!e.preferredObservableCall().trim().isEmpty() && !e.prefersExceptionAssertion()) {
            sb.append("- A stronger observable is available: prefer ").append(e.preferredObservableCall())
                    .append(" over assertNotNull/simple success when both compile.\n");
        }
        if (e.hasLockedPrimaryChainObservable() && !e.prefersExceptionAssertion()) {
            sb.append("- PRIMARY_CHAIN locks the main observable C: use ")
                    .append(e.primaryChainOracleCall())
                    .append(" for the final kill assertion.\n");
            if (e.hasExpectedOriginalOracle()) {
                sb.append("- Assert the original-program expected value from PRIMARY_CHAIN/OBSERVABLE_PLAN, not the mutant value.\n");
            }
        }
        if (e.prefersExceptionAssertion() && e.codeKbSuggestsConstructorEntry()) {
            sb.append("- The top-ranked entry is a constructor and the kill plan prefers exception behavior. Make constructor success-vs-IllegalArgumentException the primary oracle.\n");
            sb.append("- Do not write a test whose final decision comes only from nextRecord(), record contents, or getHeaderMap() after successful construction.\n");
        }
        if (e.mentionsDuplicateHeaderConstraint()) {
            sb.append("- Use a duplicate-header setup from INPUT_DISTINGUISH_PLAN. This mutant is not a generic parsing case.\n");
        }
        if (e.mentionsAllowMissingColumnNamesConstraint()) {
            sb.append("- Vary allowMissingColumnNames explicitly; do not leave it at an implicit default when the constraint mentions it.\n");
        }
        sb.append("- If EXECUTABLE_TEST_PLAN and CODE_KB_CONTEXT disagree, prefer the one that still preserves a valid public path to the mutated method and a stronger observable.\n\n");
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
            sb.append("- This constructor mutation is observable through a public accessor after construction. Construct the subject first, then call OBSERVABLE_PLAN.observableCall and assert its result.\n");
        } else if ("CONSTRUCTOR_PUBLIC_METHOD_DEPENDS_ON_STATE".equals(kind)) {
            sb.append("- This constructor mutation is observable through a public behavior method that depends on constructor-written state. Use OBSERVABLE_PLAN.observableCall exactly after construction.\n");
        } else if ("REFLECTION_FIELD_READ_AFTER_CONSTRUCTION".equals(kind)) {
            sb.append("- No stable public observer was found. Use the reflection setup and observable call from OBSERVABLE_PLAN exactly; do not replace it with a guessed getter.\n");
        } else if ("REFLECTION_FIELD_READ_AFTER_SETTER".equals(kind)) {
            sb.append("- Use the reflection setup and observable call exactly; do not replace them with a guessed public API.\n");
        } else if ("THROWABLE_MESSAGE".equals(kind)) {
            sb.append("- The mutation changes constructor-produced message state. Assert the constructed throwable message using OBSERVABLE_PLAN.observableCall.\n");
        } else if ("EXTERNAL_MUTABLE_SINK_TO_STRING".equals(kind)
                || "CONSTRUCTOR_EXTERNAL_SINK_STATE".equals(kind)) {
            sb.append("- The mutation is observable through external mutable sink content. Use OBSERVABLE_PLAN.observableCall and assert the rendered sink text rather than object identity.\n");
        } else if ("MUTABLE_PARAMETER_STATE".equals(kind)) {
            sb.append("- The mutation updates a mutable input argument in place. Assert the post-call state of that argument exactly as described by OBSERVABLE_PLAN.observableCall.\n");
        } else if ("EXCEPTION_BEHAVIOR".equals(kind)) {
            sb.append("- The mutation changes precondition or exception behavior. Include a concrete call that distinguishes thrown-vs-not-thrown behavior.\n");
        }
        if (e.hasObservableOverride()) {
            sb.append("- OBSERVABLE_SELECTION_PLAN overrode the baseline observable. Prefer using ")
                    .append(e.preferredObservableCall())
                    .append(" as the main oracle when it helps produce a stronger test.\n");
            sb.append("- If the baseline observable is simpler and still mutation-sensitive, it may remain part of the final test.\n");
        }
        sb.append('\n');
    }

    private static void appendKillPlanGuidance(StringBuilder sb, PromptEvidence e) {
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
        sb.append("Kill-plan guidance:\n");
        sb.append("- If RIP_EXECUTION_PLAN is present, follow it in order: satisfy reachability, trigger infection, propagate to the observable sink, then assert the oracle.\n");
        sb.append("- Do not replace RIP reachability/infection inputs with null-only or NPE-only calls unless RIP_EXECUTION_PLAN explicitly classifies the mutation as null-check behavior.\n");
        sb.append("- Prefer concrete non-default inputs from INPUT_DISTINGUISH_PLAN over null/0 placeholders.\n");
        sb.append("- Make the test satisfy distinguishingConstraints and boundaryValueFamilies when they are present.\n");
        sb.append("- Align the final assertion with PROPAGATION_PLAN.observableSinkExpression or ASSERTION_PLAN.primaryAssertions.\n");
        sb.append("- If LIFTED_REACHABILITY_PLAN is present, treat it as the bridge from callable entry B to the real mutated behavior A.\n");
        if (e.hasEquivalenceSuspicion()) {
            sb.append("- EVIDENCE_QUALITY reports equivalenceSuspicion=true. Treat this as a static hint, not a proof.\n");
            sb.append("- Before writing a test, reason explicitly about whether the mutant is semantically equivalent on all reachable states. Generate a killing test only if you can justify a real divergence.\n");
            if (!e.equivalenceSuspicionReason().trim().isEmpty()) {
                sb.append("- Static suspicion reason: ").append(e.equivalenceSuspicionReason()).append("\n");
            }
        }
        if (e.isReceiverStateDependent()) {
            sb.append("- KILLABILITY_PLAN says this mutant is RECEIVER_STATE_DEPENDENT. Control receiver state through constructor/setup, then assert externally visible state from the preferred getter/method.\n");
            sb.append("- Do not stop at assertNotNull, record size, object identity, or simple successful execution if a stronger state observable is available.\n");
            if (e.hasObservableOverride()) {
                sb.append("- For this case, favor the overridden observable when it exposes stronger state than the old baseline path.\n");
            }
        }
        if (e.prefersExceptionAssertion()) {
            sb.append("- preferredAssertionMode is EXCEPTION_ASSERTION. The final test must make pass/fail depend on whether construction or the target call throws IllegalArgumentException.\n");
            sb.append("- If the original is expected to throw, assertThrows is the primary oracle and any later getHeaderMap()/nextRecord() checks are secondary only.\n");
            sb.append("- If the original is expected to succeed, do not wrap the target construction in a broad catch that would also let the mutant pass.\n");
        }
        if (e.prefersOutputAssertion()) {
            sb.append("- preferredAssertionMode is OUTPUT_ASSERTION. Capture the evidence-selected System.out/System.err sink and make pass/fail depend on mutation-sensitive output text or output presence.\n");
            sb.append("- Restore the original PrintStream in a finally block; do not leave console redirection active after the target invocation.\n");
            sb.append("- Do not replace a direct console-output oracle with completion-only, assertNotNull, or unrelated exception assertions.\n");
        }
        if (e.prefersConstructorExceptionSplit()) {
            sb.append("- The propagation target explicitly says constructor completion vs IllegalArgumentException is the split. Preserve that split as the main kill condition.\n");
        }
        if (e.evidenceQuality.length() > 0) {
            sb.append("- EVIDENCE_QUALITY highlights weak evidence; compensate by choosing stronger concrete inputs and a specific observable/assertion.\n");
        }
        sb.append('\n');
    }

    private static void appendReachabilityAndInputGuidance(StringBuilder sb, PromptEvidence e) {
        if (e == null) {
            return;
        }
        if (e.hasReachabilityGuards()) {
            sb.append("Reachability-guard guidance:\n");
            sb.append("- Use REACHABILITY_GUARDS_PLAN.mustPassBeforeMutation as a reachability hint before choosing the oracle.\n");
            if (e.hasFrontLoadedExceptionRisk()) {
                sb.append("- Avoid null-only or front-door exception-only tests when the mutation-sensitive behavior is deeper in the normal path.\n");
            }
            sb.append('\n');
        }
        if (e.hasBoundarySensitiveInputGuidance()) {
            sb.append("Distinguishing-input guidance:\n");
            sb.append("- Use DISTINGUISHING_INPUT_GUIDANCE to pick inputs that actually separate original from mutant behavior.\n");
            sb.append("- Do not stop at generic boundary values unless they change control flow, propagation, or the final observable.\n\n");
        }
        if (e.hasSymbolicRipPlan()) {
            sb.append("Symbolic-RIP guidance:\n");
            sb.append("- Treat SYMBOLIC_RIP_PLAN.pathConstraints as lightweight symbolic evidence about the mutation path.\n");
            sb.append("- Use SYMBOLIC_RIP_PLAN.preferredAssignments, preferredObservableCall, and oracleSketch as optional guidance when they help construct a stronger test.\n\n");
        }
        if (e.requiresRealEntryChain()) {
            sb.append("Entry-chain guidance:\n");
            sb.append("- Preserve the real public entry/call-chain from ENTRY_CHAIN_PLAN. Do not bypass it with direct helper/private calls or impossible synthetic state.\n\n");
        }
    }

    private static void appendIndirectEntryGuidance(StringBuilder sb, PromptEvidence e) {
        if (e == null || !e.hasIndirectEntryTestPlan()) {
            return;
        }
        sb.append("Indirect-entry guidance:\n");
        sb.append("- This mutant is reached indirectly through a public entry rather than by calling the mutated helper method directly.\n");
        sb.append("- entryControlType = ").append(e.indirectEntryControlType()).append("\n");
        if (e.requiresStateShaping()) {
            sb.append("- Shape receiver/internal state before the final observable call. Key targets: ")
                    .append(e.stateShapingTargets(6)).append("\n");
        }
        if (e.requiresInvocationSequence()) {
            sb.append("- Use a multi-step public-call sequence. A single smoke-test call is usually insufficient for this mutant.\n");
        }
        if (!e.observablePriority(5).isEmpty()) {
            sb.append("- Observable priority: ").append(e.observablePriority(5)).append("\n");
        }
        sb.append("- Do not stop at assertNotNull, simple success, or a trivial first-token/first-return assertion when INDIRECT_ENTRY_TEST_PLAN requires a stronger sequence-sensitive oracle.\n\n");
    }

    static void appendCompactSection(StringBuilder sb, String title, JSONObject obj) {
        if (obj == null || obj.length() == 0) {
            return;
        }
        sb.append(title).append(":\n");
        sb.append(obj.toString(2)).append("\n\n");
    }

    static void appendCompactSection(StringBuilder sb, String title, JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return;
        }
        sb.append(title).append(":\n");
        sb.append(arr.toString(2)).append("\n\n");
    }
}
