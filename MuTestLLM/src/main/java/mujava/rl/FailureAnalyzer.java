package mujava.rl;

import mujava.testgenerator.tools.PromptEvidence;
import mujava.testgenerator.tools.Request;
import mujava.testgenerator.tools.Result;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FailureAnalyzer {
    private FailureAnalyzer() {
    }

    public static FailureAnalysis analyze(Request request,
                                          EvidenceState state,
                                          EvidenceAction action,
                                          Result result,
                                          JSONObject fullJson,
                                          PromptEvidence evidence) {
        FailureAnalysis analysis = new FailureAnalysis();
        if (result == null) {
            return analysis;
        }

        analysis.symptom = detectSymptom(result);
        buildSignalLedger(analysis.signalLedger, result, fullJson, evidence);

        if (result.killed) {
            return assign(analysis,
                FailureStage.UNKNOWN,
                RootCauseType.UNCLASSIFIED_FAILURE,
                1.0d,
                "",
                "",
                false,
                "Mutant was killed in this run.",
                "No repair needed. Mutant was killed.");
        }

        String failureReason = lower(result.failureReason);
        String targetStatus = lower(result.targetStatus);
        SignalLedger ledger = analysis.signalLedger;

        if (Result.STATUS_API_FAILED.equalsIgnoreCase(result.targetStatus)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.API_TRANSPORT_FAILURE,
                1.0d,
                "LLM_API",
                "",
                false,
                "The LLM request failed at the transport/provider layer before a Java candidate could be evaluated.",
                "Retry the same semantic stage without changing evidence or rewarding/punishing the evidence-selection policy.");
        }

        if (Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(result.targetStatus)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.GENERATION_PIPELINE_FAILURE,
                0.95d,
                "MUTESTLLM",
                "",
                false,
                "The generation pipeline failed before javac produced a compile diagnosis.",
                "Retry generation at the same semantic stage and inspect response extraction/scaffolding before changing semantic evidence.");
        }

        if (mentionsRuntimeEnvironmentProblem(failureReason)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.RUNTIME_CLASSPATH_OR_ENVIRONMENT,
                0.96d,
                "MUTESTLLM",
                "",
                false,
                "The generated test reached MuTestLLM execution, but the runtime crashed before semantic mutant execution.",
                "Fix MuTestLLM runtime classpath, Java home, file permissions, or target-project execution environment before regenerating tests.");
        }

        if (result.timedOut || targetStatus.contains("timeout")) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.TIMEOUT_OR_BUDGET_LIMIT,
                0.95d,
                "MUTESTLLM",
                "",
                false,
                "Execution ended in timeout before a decisive diagnosis was reached.",
                "Increase generation budget or reduce prompt size for this mutant family.");
        }

        if (!result.compiled) {
            if (mentionsImportProblem(failureReason)) {
                return diagnoseImportChain(analysis, result, fullJson, evidence);
            }
            if (mentionsEntryProblem(failureReason)) {
                return diagnoseEntryChain(analysis);
            }
            if (mentionsReceiverProblem(failureReason)) {
                return diagnoseReceiverChain(analysis);
            }
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.COMPILE_REPAIR_WEAK,
                0.70d,
                "MUTESTLLM",
                "",
                false,
                "Compilation failed but the compile error did not map cleanly to one structural signal chain.",
                "Compilation failed after generation; improve compile repair policy and diagnostics.");
        }

        if (!result.originalPassed) {
            return diagnoseReceiverChain(analysis);
        }

        if (result.equivalenceSuspicion) {
            return assign(analysis,
                FailureStage.CROSS_STAGE,
                RootCauseType.LIKELY_EQUIVALENT_MUTANT,
                0.82d,
                "MANUAL_OR_EQ_RULE",
                "",
                false,
                "Static evidence already suspects equivalence; surviving behavior is consistent with that suspicion.",
                "Treat as likely equivalent and require manual confirmation or a dedicated equivalence workflow.");
        }

        String selfUpdateEquivalenceHint = inferSelfUpdateEquivalenceHint(fullJson);
        if (!isBlank(selfUpdateEquivalenceHint)) {
            return assign(analysis,
                FailureStage.CROSS_STAGE,
                RootCauseType.LIKELY_EQUIVALENT_MUTANT,
                0.66d,
                "MANUAL_OR_EQ_RULE",
                "MUTESTLLM",
                false,
                selfUpdateEquivalenceHint,
                "Do not blindly regenerate the same assertion. First confirm whether the self-update side effect is observable after the mutated expression.");
        }

        FailureAnalysis canonicalDiagnosis = diagnoseCanonicalCoreSurvivor(analysis, result, fullJson, evidence);
        if (canonicalDiagnosis != null) {
            return canonicalDiagnosis;
        }

        if (!ledger.reachabilitySignal.outputJson && ledger.reachabilitySignal.codeKb) {
            copyReachabilityGap(analysis.suspectedReachabilityGaps, fullJson);
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.RIP_REACHABILITY_MISSING,
                0.82d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                "Reachability signal exists in CodeKB but is lost before output.json reasoning.",
                "Assemble reachability evidence from local call-chain signals into output.json.");
        }

        if (!ledger.observableSignal.outputJson && ledger.observableSignal.codeKb) {
            copyObservableSignals(analysis.suspectedObservableGaps,
                locateCodeKb(fullJson).optJSONArray("mutantFieldAccesses"),
                locateCodeKb(fullJson).optJSONArray("mutantMethodCalls"));
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.OBSERVABLE_PLAN_MISSING,
                0.80d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                "Observable signal exists in CodeKB but output.json does not expose an observable plan.",
                "Assemble observable plan and propagation hints into output.json.");
        }

        if (!ledger.observableSignal.prompt && ledger.observableSignal.outputJson) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.PROMPT_COMPRESSION_LOSS,
                0.78d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "Observable evidence exists in output.json but was not retained in PromptEvidence.",
                "Preserve observable and propagation evidence during prompt compression.");
        }

        if (evidence != null && evidence.requiresRealEntryChain() && mentionsEntryChainBypassed(failureReason)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.ENTRY_CHAIN_BYPASSED,
                0.83d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "The generated test bypassed the guided public entry chain and called a non-guided/internal path.",
                "Keep the generated test on the evidence-provided public entry chain and forbid direct helper/private calls.");
        }

        if (evidence != null && evidence.hasFrontLoadedExceptionRisk() && mentionsFrontGuardExceptionBias(failureReason)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.FRONT_GUARD_EXCEPTION_BIAS,
                0.81d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "The generated test stopped at a front-loaded exception/null-guard instead of driving execution into the mutation-sensitive path.",
                "Avoid easy exception-only or null-guard tests; satisfy the reachability guards first and assert a deeper observable difference.");
        }

        if (evidence != null && evidence.hasBoundarySensitiveInputGuidance() && mentionsDistinguishingInputMiss(failureReason)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.DISTINGUISHING_INPUT_NOT_USED,
                0.79d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "The generated test compiled but did not use boundary/distinguishing inputs needed to separate original and mutant behavior.",
                "Switch regeneration toward boundary-sensitive and path-distinguishing inputs instead of reusing default literals.");
        }

        if (evidence != null && evidence.hasSymbolicRipPlan() && mentionsSymbolicPathViolation(failureReason)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.SYMBOLIC_PATH_VIOLATED,
                0.84d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "The generated test missed symbolic/path constraints needed to reach or observe the mutation-sensitive path.",
                "Honor the symbolic path constraints and preferred observable from output.json during regeneration.");
        }

        if (!ledger.assertionSignal.generatedTest && ledger.assertionSignal.prompt) {
            analysis.suspectedAssertionGaps.put("Prompt contained assertion guidance, but the generated test did not assert a mutation-sensitive difference.");
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.ASSERTION_GENERATION_WEAK,
                0.76d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "Assertion guidance survived into the prompt, but generated code did not express a mutation-sensitive assertion.",
                "Strengthen assertion generation around observable differences already present in output.json.");
        }

        if (result.regenerationRound > 0 && mentionsSameFailurePattern(failureReason)) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.REGENERATION_STRATEGY_WEAK,
                0.75d,
                "MUTESTLLM",
                "",
                false,
                "Regeneration repeated the same failure pattern instead of switching repair strategy.",
                "Branch regeneration by root-cause class instead of repeating a generic retry.");
        }

        return assign(analysis,
            FailureStage.CROSS_STAGE,
            RootCauseType.UNCLASSIFIED_FAILURE,
            0.45d,
            "MANUAL_REVIEW",
            "",
            false,
            "No single signal chain shows a decisive first-loss boundary; multiple stages may contribute.",
            "Review signal ledger manually and avoid automatic backfill for this sample.");
    }

    private static FailureAnalysis diagnoseImportChain(FailureAnalysis analysis,
                                                      Result result,
                                                      JSONObject fullJson,
                                                      PromptEvidence evidence) {
        SignalPath path = analysis.signalLedger.importSignal;
        populateSuspectedMissingImports(analysis.suspectedMissingImports, result, fullJson, evidence);
        if (!path.codeKb) {
            return assign(analysis,
                FailureStage.CODEKB,
                RootCauseType.MISSING_IMPORT_KNOWLEDGE,
                0.90d,
                "CODEKB",
                "",
                true,
                "Import signal is present in source-level context but never appears in CodeKB.",
                "Backfill import candidates from source structure and method usage.");
        }
        if (!path.outputJson) {
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.IMPORT_ASSEMBLY_BUG,
                0.90d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                "CodeKB contains import candidates but output.json did not expose them as required imports.",
                "Merge CodeKB candidateImports into requiredImports during output.json assembly.");
        }
        return assign(analysis,
            FailureStage.MUTESTLLM,
            RootCauseType.IMPORT_NOT_USED,
            0.85d,
            "MUTESTLLM",
            "EVIDENCE_PARSE",
            false,
            "Import signal survived into output.json/prompt but the generated test still failed import-related compilation.",
            "Force generated test to honor requiredImports before attempting repair.");
    }

    private static FailureAnalysis diagnoseEntryChain(FailureAnalysis analysis) {
        SignalPath path = analysis.signalLedger.entrySignal;
        if (!path.codeKb) {
            return assign(analysis,
                FailureStage.CODEKB,
                RootCauseType.MISSING_ENTRY_CANDIDATE,
                0.88d,
                "CODEKB",
                "",
                false,
                "Callable entry signal exists in source-level context but CodeKB did not surface entry candidates.",
                "Re-derive same-class public callers and constructors from source AST before retrying; current CodeKB feedback ingest does not yet persist ENTRY candidates.");
        }
        if (!path.outputJson) {
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.ENTRY_ASSEMBLY_BUG,
                0.84d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                "Entry candidates exist in CodeKB but were not expressed through guidance.testTarget/setupPlan.",
                "Expose CodeKB entry candidates in guidance.testTarget and setupPlan.");
        }
        return assign(analysis,
            FailureStage.MUTESTLLM,
            RootCauseType.ENTRY_NOT_USED,
            0.84d,
            "MUTESTLLM",
            "EVIDENCE_PARSE",
            false,
            "Entry guidance reached the prompt, but generated code did not call the guided entry.",
            "Constrain generation to call the guided entry instead of the inaccessible method.");
    }

    private static FailureAnalysis diagnoseReceiverChain(FailureAnalysis analysis) {
        SignalPath path = analysis.signalLedger.receiverSignal;
        if (!path.codeKb) {
            return assign(analysis,
                FailureStage.CODEKB,
                RootCauseType.MISSING_RECEIVER_CONSTRUCTION,
                0.80d,
                "CODEKB",
                "",
                false,
                "Receiver/setup signal is absent from CodeKB, so later stages have no stable construction hints.",
                "Re-derive constructor/setup patterns from source AST before retrying; current CodeKB feedback ingest does not yet persist RECEIVER candidates.");
        }
        if (!path.outputJson) {
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.ENTRY_ASSEMBLY_BUG,
                0.74d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                "Receiver/setup hints exist in CodeKB but were not surfaced into output.json setup guidance.",
                "Lift receiver construction hints into setupPlan and invocation receiver guidance.");
        }
        return assign(analysis,
            FailureStage.MUTESTLLM,
            RootCauseType.RECEIVER_SETUP_WEAK,
            0.76d,
            "MUTESTLLM",
            "EVIDENCE_PARSE",
            false,
            "Receiver/setup guidance survived, but generated code still failed to construct a valid runtime state.",
            "Strengthen receiver/setup generation and retry with constructor-aware prompt repair.");
    }

    private static FailureAnalysis diagnoseCanonicalCoreSurvivor(FailureAnalysis analysis,
                                                                 Result result,
                                                                 JSONObject fullJson,
                                                                 PromptEvidence evidence) {
        if (result == null || !result.compiled || !result.originalPassed || result.killed) {
            return null;
        }
        JSONObject core = locateCanonicalCore(fullJson);
        if (core.length() == 0) {
            if (!isSchema21(fullJson)) {
                return null;
            }
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.CANONICAL_CORE_MISSING,
                0.86d,
                "EVIDENCE_PARSE",
                "",
                false,
                "output.json declares schema 2.1 but does not expose guidance.canonicalCore.",
                "Assemble selected B, mandatory R, semantic I, observable C, and oracle O into guidance.canonicalCore before prompting.");
        }

        JSONObject entry = core.optJSONObject("entry");
        JSONObject reachability = core.optJSONObject("reachability");
        JSONObject infection = core.optJSONObject("infection");
        JSONObject observable = core.optJSONObject("observable");
        JSONObject oracle = core.optJSONObject("oracle");
        JSONObject guidance = locateGuidance(fullJson);
        JSONObject selectedEntry = locateSelectedEntry(fullJson);

        if (entry == null || entry.length() == 0) {
            String why = selectedEntry.length() > 0
                ? "guidance.selectedEntry exists, but canonicalCore.entry did not retain the selected B entry."
                : "Canonical core has no selected entry B, so downstream generation cannot know which public entry should reach A.";
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.ENTRY_ASSEMBLY_BUG,
                0.82d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                why,
                "Populate canonicalCore.entry from entrySelection.selected and keep alternatives optional.");
        }

        JSONObject controllability = entry.optJSONObject("controllability");
        String constraintStatus = optString(controllability, "constraintStatus");
        double independentCoverage = optDouble(controllability, "independentCoverage");
        if (constraintStatus.toUpperCase(Locale.ROOT).contains("UNSAT") || independentCoverage == 0.0d) {
            analysis.suspectedBetterEntries.put(entry);
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.ENTRY_CONSTRAINT_UNSATISFIED,
                0.84d,
                "EVIDENCE_PARSE",
                "CODEKB",
                false,
                "Selected entry B cannot independently satisfy the mutation-sensitive distinguishing constraints.",
                "Re-rank candidate entries using EntryBindingAnalyzer and prefer SAT/UNKNOWN entries with higher independent controllability.");
        }

        JSONArray guards = reachability == null ? null : reachability.optJSONArray("mandatoryGuards");
        if (reachability == null || reachability.length() == 0 || guards == null || guards.length() == 0) {
            String sourceSummary = reachabilitySourceSummary(guidance);
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.RIP_REACHABILITY_MISSING,
                0.78d,
                "EVIDENCE_PARSE",
                "",
                false,
                "Canonical core has no mandatory reachability guards R, so survivor diagnosis cannot confirm the test reached the mutation-sensitive path."
                    + sourceSummary,
                "Extract AST ancestor, fall-through, and NON_NULL guards into canonicalCore.reachability.mandatoryGuards.");
        }
        if (hasFalsePolarityGuard(guards) && mentionsSymbolicPathViolation(lower(result.failureReason))) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.PATH_GUARD_POLARITY_MISMATCH,
                0.82d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "Canonical core contains false-polarity path guards, and the failed run indicates path constraints were not honored.",
                "Regenerate with mandatory guard requiredEvaluation values locked, especially false/fall-through guards.");
        }

        if (infection == null || infection.length() == 0
                || isBlank(optString(infection, "semanticOriginalExpression"))
                || isBlank(optString(infection, "semanticMutantExpression"))) {
            String container = optString(infection, "semanticContainerKind");
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.RIP_INFECTION_MISSING,
                0.80d,
                "EVIDENCE_PARSE",
                "",
                false,
                "Canonical core lacks semantic original/mutant infection expressions I."
                    + (isBlank(container) ? "" : " semanticContainerKind=" + container + "."),
                "Populate canonicalCore.infection with local and semantic original/mutant expressions and mandatory cofactors.");
        }

        JSONArray cofactors = infection.optJSONArray("mandatoryCofactors");
        if (cofactors != null && cofactors.length() > 0 && mentionsDistinguishingInputMiss(lower(result.failureReason))) {
            return assign(analysis,
                FailureStage.MUTESTLLM,
                RootCauseType.MANDATORY_COFACTOR_UNSATISFIED,
                0.80d,
                "MUTESTLLM",
                "EVIDENCE_PARSE",
                false,
                "Canonical core contains mandatory infection cofactors, but the generated test appears to miss distinguishing input constraints.",
                "Regenerate concrete inputs after evaluating every mandatory cofactor and semantic original/mutant expression.");
        }

        if (observable == null || observable.length() == 0 || isBlank(firstNonBlank(
                optString(observable, "call"), optString(observable, "expression")))) {
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.OBSERVABLE_PLAN_MISSING,
                0.78d,
                "EVIDENCE_PARSE",
                "",
                false,
                "Canonical core has no selected observable C.",
                "Rank observables by mutation-effect directness and populate canonicalCore.observable.");
        }
        String directness = optString(observable, "directness").toUpperCase(Locale.ROOT);
        if (directness.contains("INDIRECT") || directness.contains("MULTI_FIELD_AGGREGATE")) {
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.OBSERVABLE_TOO_INDIRECT,
                0.68d,
                "EVIDENCE_PARSE",
                "",
                false,
                "Selected observable C is indirect or aggregate, which weakens the oracle for this surviving mutant.",
                "Prefer direct return or direct field projection observables when mutation-effect flow supports them.");
        }

        if (oracle == null || oracle.length() == 0 || isBlank(optString(oracle, "assertionMode"))) {
            return assign(analysis,
                FailureStage.EVIDENCE_PARSE,
                RootCauseType.ORACLE_MISMATCH,
                0.70d,
                "EVIDENCE_PARSE",
                "MUTESTLLM",
                false,
                "Canonical core has selected B/R/I/C but no explicit oracle O.",
                "Populate canonicalCore.oracle with assertionMode and executable expectedOriginal when statically available.");
        }
        return null;
    }

    private static FailureAnalysis assign(FailureAnalysis analysis,
                                          FailureStage stage,
                                          RootCauseType cause,
                                          double confidence,
                                          String primaryFixTarget,
                                          String secondaryFixTarget,
                                          boolean autoRepairable,
                                          String why,
                                          String suggestedAction) {
        analysis.failureStage = stage;
        analysis.rootCauseType = cause;
        analysis.confidence = confidence;
        analysis.primaryFixTarget = blank(primaryFixTarget);
        analysis.secondaryFixTarget = blank(secondaryFixTarget);
        analysis.autoRepairable = autoRepairable;
        analysis.why = blank(why);
        analysis.suggestedAction = blank(suggestedAction);
        return analysis;
    }

    private static void buildSignalLedger(SignalLedger ledger,
                                          Result result,
                                          JSONObject fullJson,
                                          PromptEvidence evidence) {
        JSONObject codekb = locateCodeKb(fullJson);
        JSONObject guidance = locateGuidance(fullJson);
        JSONObject summary = locateSummary(fullJson);
        JSONObject propagation = locatePropagation(fullJson);

        JSONArray candidateImports = codekb.optJSONArray("candidateImports");
        JSONArray candidateEntries = codekb.optJSONArray("candidateEntries");
        JSONArray mutantFieldAccesses = codekb.optJSONArray("mutantFieldAccesses");
        JSONArray mutantMethodCalls = codekb.optJSONArray("mutantMethodCalls");
        JSONArray requiredImports = locateRequiredImports(guidance, fullJson);

        ledger.importSignal.source = length(candidateImports) > 0 || length(requiredImports) > 0;
        ledger.importSignal.codeKb = length(candidateImports) > 0;
        ledger.importSignal.outputJson = length(requiredImports) > 0;
        ledger.importSignal.prompt = evidence != null && evidence.hasPromptImportSignal();
        ledger.importSignal.generatedTest = result != null && result.compiled;
        ledger.importSignal.note = "candidateImports=" + length(candidateImports) + ", requiredImports=" + length(requiredImports);

        boolean hasGuidanceEntry = hasGuidanceEntry(guidance);
        ledger.entrySignal.source = length(candidateEntries) > 0 || hasGuidanceEntry;
        ledger.entrySignal.codeKb = length(candidateEntries) > 0;
        ledger.entrySignal.outputJson = hasGuidanceEntry;
        ledger.entrySignal.prompt = evidence != null && evidence.hasPromptEntrySignal();
        ledger.entrySignal.generatedTest = result != null && result.compiled && result.originalPassed;
        ledger.entrySignal.note = "candidateEntries=" + length(candidateEntries) + ", testTarget=" + hasGuidanceEntry;

        boolean receiverSource = length(candidateEntries) > 0 || length(mutantMethodCalls) > 0 || length(mutantFieldAccesses) > 0;
        ledger.receiverSignal.source = receiverSource;
        ledger.receiverSignal.codeKb = receiverSource;
        ledger.receiverSignal.outputJson = hasSetupPlan(guidance) || hasInvocationReceiver(fullJson);
        ledger.receiverSignal.prompt = evidence != null && (evidence.isReceiverStateDependent() || evidence.hasPromptEntrySignal());
        ledger.receiverSignal.generatedTest = result != null && result.originalPassed;
        ledger.receiverSignal.note = "methodCalls=" + length(mutantMethodCalls) + ", fieldAccesses=" + length(mutantFieldAccesses);

        boolean reachabilitySummary = hasSummarySection(summary, "reachability");
        ledger.reachabilitySignal.source = length(mutantMethodCalls) > 0 || reachabilitySummary;
        ledger.reachabilitySignal.codeKb = length(mutantMethodCalls) > 0;
        ledger.reachabilitySignal.outputJson = reachabilitySummary || !isEmptyReasoning(propagation);
        ledger.reachabilitySignal.prompt = evidence != null && evidence.hasPromptReachabilitySignal();
        ledger.reachabilitySignal.generatedTest = result != null && result.originalPassed;
        ledger.reachabilitySignal.note = "methodCalls=" + length(mutantMethodCalls) + ", reachabilitySummary=" + reachabilitySummary;

        boolean observableSummary = hasSummarySection(summary, "observability");
        ledger.observableSignal.source = length(mutantFieldAccesses) > 0 || length(mutantMethodCalls) > 0 || observableSummary;
        ledger.observableSignal.codeKb = length(mutantFieldAccesses) > 0 || length(mutantMethodCalls) > 0;
        ledger.observableSignal.outputJson = observableSummary || hasObservableSignal(summary.optJSONObject("observability"), fullJson);
        ledger.observableSignal.prompt = evidence != null && evidence.hasPromptObservableSignal();
        ledger.observableSignal.generatedTest = result != null && result.compiled && result.originalPassed;
        ledger.observableSignal.note = "fieldAccesses=" + length(mutantFieldAccesses) + ", methodCalls=" + length(mutantMethodCalls);

        boolean hasAssertionPlan = hasAssertionPlan(guidance, fullJson);
        ledger.assertionSignal.source = ledger.observableSignal.source || hasAssertionPlan;
        ledger.assertionSignal.codeKb = ledger.observableSignal.codeKb;
        ledger.assertionSignal.outputJson = hasAssertionPlan;
        ledger.assertionSignal.prompt = evidence != null && evidence.hasPromptAssertionSignal();
        ledger.assertionSignal.generatedTest = result != null && result.killed;
        ledger.assertionSignal.note = "assertionPlan=" + hasAssertionPlan + ", weakAssertion=" + (evidence != null && evidence.hasWeakAssertionSignal());
    }

    private static String detectSymptom(Result result) {
        if (result == null) {
            return "unknown";
        }
        if (result.timedOut) {
            return "timeout";
        }
        if (Result.STATUS_API_FAILED.equalsIgnoreCase(result.targetStatus)) {
            return "api_failed";
        }
        if (Result.STATUS_GENERATION_FAILED.equalsIgnoreCase(result.targetStatus)
                || Result.STATUS_LLM_OUTPUT_INVALID.equalsIgnoreCase(result.targetStatus)
                || Result.STATUS_LLM_NO_VISIBLE_CODE.equalsIgnoreCase(result.targetStatus)) {
            return "generation_failed";
        }
        if (Result.STATUS_TASK_CRASHED.equalsIgnoreCase(result.targetStatus)) {
            return "task_crashed";
        }
        if (!result.compiled) {
            return "compile_failed";
        }
        if (!result.originalPassed) {
            return "original_failed";
        }
        if (!result.killed) {
            return "survived";
        }
        return "killed";
    }

    private static JSONObject locateCodeKb(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject evidence = clean.optJSONObject("evidence");
        JSONObject structural = evidence == null ? null : evidence.optJSONObject("structural");
        if (structural != null) {
            JSONObject codekb = structural.optJSONObject("codekb");
            if (codekb != null) {
                return codekb;
            }
        }
        JSONObject legacy = root == null ? null : root.optJSONObject("CodeKBContext");
        if (legacy != null) {
            return legacy;
        }
        JSONObject embedded = root == null ? null : root.optJSONObject("codeKbContext");
        return embedded == null ? new JSONObject() : embedded;
    }

    private static JSONObject locateGuidance(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject guidance = clean.optJSONObject("guidance");
        return guidance == null ? new JSONObject() : guidance;
    }

    private static JSONObject locateSummary(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject summary = clean.optJSONObject("summary");
        return summary == null ? new JSONObject() : summary;
    }

    private static JSONObject locatePropagation(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject evidence = clean.optJSONObject("evidence");
        JSONObject reasoning = evidence == null ? null : evidence.optJSONObject("reasoning");
        if (reasoning != null) {
            JSONObject propagation = reasoning.optJSONObject("propagation");
            if (propagation != null) {
                return propagation;
            }
        }
        return new JSONObject();
    }

    private static JSONArray locateRequiredImports(JSONObject guidance, JSONObject root) {
        JSONObject testTarget = guidance.optJSONObject("testTarget");
        if (testTarget != null) {
            JSONArray imports = testTarget.optJSONArray("imports");
            if (imports != null) {
                return imports;
            }
        }
        JSONObject dep = root == null ? null : root.optJSONObject("DependencyContext");
        JSONObject testEntryContext = dep == null ? null : dep.optJSONObject("testEntryContext");
        if (testEntryContext != null) {
            JSONArray imports = testEntryContext.optJSONArray("requiredImports");
            if (imports != null) {
                return imports;
            }
        }
        return new JSONArray();
    }

    private static JSONObject extractCleanRoot(JSONObject root) {
        if (root == null) {
            return new JSONObject();
        }
        JSONObject wrapped = root.optJSONObject("OutputV2");
        if (wrapped != null && wrapped.length() > 0) {
            return wrapped;
        }
        if (root.has("meta") && root.has("summary") && root.has("guidance")) {
            return root;
        }
        return new JSONObject();
    }

    private static boolean hasGuidanceEntry(JSONObject guidance) {
        JSONObject testTarget = guidance.optJSONObject("testTarget");
        return testTarget != null && testTarget.length() > 0;
    }

    private static boolean hasSetupPlan(JSONObject guidance) {
        JSONObject setupPlan = guidance.optJSONObject("setupPlan");
        return setupPlan != null && setupPlan.length() > 0;
    }

    private static boolean hasInvocationReceiver(JSONObject root) {
        if (root == null) {
            return false;
        }
        JSONObject invocation = root.optJSONObject("invocation");
        if (invocation != null) {
            JSONObject receiver = invocation.optJSONObject("receiver");
            return receiver != null && receiver.length() > 0;
        }
        return root.toString().toLowerCase(Locale.ROOT).contains("\"receiver\"");
    }

    private static boolean hasSummarySection(JSONObject summary, String key) {
        if (summary == null) {
            return false;
        }
        JSONObject section = summary.optJSONObject(key);
        return section != null && section.length() > 0;
    }

    private static boolean hasAssertionPlan(JSONObject guidance, JSONObject root) {
        if (guidance != null) {
            JSONObject assertionPlan = guidance.optJSONObject("assertionPlan");
            if (assertionPlan != null && assertionPlan.length() > 0) {
                return true;
            }
        }
        return root != null && root.toString().toLowerCase(Locale.ROOT).contains("\"assertionplan\"");
    }

    private static boolean mentionsImportProblem(String text) {
        return text.contains("cannot find symbol")
            || text.contains("package ") && text.contains(" does not exist")
            || text.contains("symbol")
            || text.contains("import");
    }

    private static void populateSuspectedMissingImports(JSONArray target,
                                                        Result result,
                                                        JSONObject fullJson,
                                                        PromptEvidence evidence) {
        Set<String> resolved = new LinkedHashSet<String>();
        Set<String> simpleNames = new LinkedHashSet<String>();
        String failureReason = result == null ? "" : blank(result.failureReason);

        collectExplicitImportStatements(resolved, failureReason);
        collectMissingPackageMatches(resolved, failureReason, fullJson, evidence);
        collectSimpleClassMentions(simpleNames, failureReason);
        resolveSimpleNamesAgainstEvidence(resolved, simpleNames, fullJson, evidence);

        for (String importValue : resolved) {
            if (!isBlank(importValue)) {
                target.put(importValue);
            }
        }
    }

    private static void collectExplicitImportStatements(Set<String> resolved, String failureReason) {
        Matcher matcher = Pattern.compile("import\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)+)\\s*;").matcher(failureReason);
        while (matcher.find()) {
            addImportCandidate(resolved, matcher.group(1));
        }
    }

    private static void collectMissingPackageMatches(Set<String> resolved,
                                                     String failureReason,
                                                     JSONObject fullJson,
                                                     PromptEvidence evidence) {
        Matcher matcher = Pattern.compile("package\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)+)\\s+does\\s+not\\s+exist")
                .matcher(failureReason);
        while (matcher.find()) {
            String packageName = blank(matcher.group(1));
            for (String candidate : collectAllImportCandidates(fullJson, evidence)) {
                if (candidate.startsWith(packageName + ".")) {
                    addImportCandidate(resolved, candidate);
                }
            }
        }
    }

    private static void collectSimpleClassMentions(Set<String> simpleNames, String failureReason) {
        Matcher classMatcher = Pattern.compile("symbol:\\s+class\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(failureReason);
        while (classMatcher.find()) {
            simpleNames.add(blank(classMatcher.group(1)));
        }

        Matcher locationMatcher = Pattern.compile("\\b([A-Z][A-Za-z0-9_$]*)\\b").matcher(failureReason);
        while (locationMatcher.find()) {
            String token = blank(locationMatcher.group(1));
            if (looksLikeTypeName(token)) {
                simpleNames.add(token);
            }
        }
    }

    private static void resolveSimpleNamesAgainstEvidence(Set<String> resolved,
                                                          Set<String> simpleNames,
                                                          JSONObject fullJson,
                                                          PromptEvidence evidence) {
        if (simpleNames.isEmpty()) {
            return;
        }
        Set<String> candidates = collectAllImportCandidates(fullJson, evidence);
        for (String simpleName : simpleNames) {
            for (String candidate : candidates) {
                if (simpleName.equals(simpleNameOfImport(candidate))) {
                    addImportCandidate(resolved, candidate);
                }
            }
        }
    }

    private static Set<String> collectAllImportCandidates(JSONObject fullJson, PromptEvidence evidence) {
        Set<String> candidates = new LinkedHashSet<String>();
        JSONObject codekb = locateCodeKb(fullJson);
        JSONArray candidateImports = codekb.optJSONArray("candidateImports");
        for (int i = 0; i < length(candidateImports); i++) {
            JSONObject obj = candidateImports.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            addImportCandidate(candidates, obj.optString("importValue", ""));
        }

        JSONArray requiredImports = locateRequiredImports(locateGuidance(fullJson), fullJson);
        for (int i = 0; i < length(requiredImports); i++) {
            addImportCandidate(candidates, requiredImports.optString(i, ""));
        }

        if (evidence != null) {
            JSONObject prompt = evidence.toJson();
            mergePromptEvidenceImports(candidates, prompt.optJSONObject("codeKbContext"));
            mergePromptEvidenceImports(candidates, prompt.optJSONObject("invocation"));
            mergePromptEvidenceImports(candidates, prompt.optJSONObject("executableTestPlan"));
        }
        return candidates;
    }

    private static void mergePromptEvidenceImports(Set<String> candidates, JSONObject source) {
        if (source == null) {
            return;
        }
        JSONArray direct = source.optJSONArray("requiredImports");
        for (int i = 0; i < length(direct); i++) {
            addImportCandidate(candidates, direct.optString(i, ""));
        }
        JSONArray candidateImports = source.optJSONArray("candidateImports");
        for (int i = 0; i < length(candidateImports); i++) {
            JSONObject obj = candidateImports.optJSONObject(i);
            if (obj != null) {
                addImportCandidate(candidates, obj.optString("importValue", ""));
            } else {
                addImportCandidate(candidates, candidateImports.optString(i, ""));
            }
        }
    }

    private static void addImportCandidate(Set<String> target, String raw) {
        String candidate = blank(raw);
        if (candidate.startsWith("import ")) {
            candidate = candidate.substring("import ".length()).trim();
        }
        if (candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1).trim();
        }
        if (candidate.contains("*")) {
            return;
        }
        if (candidate.indexOf('.') <= 0) {
            return;
        }
        if (!looksLikeFqn(candidate)) {
            return;
        }
        target.add(candidate);
    }

    private static boolean looksLikeFqn(String text) {
        return text.matches("[a-zA-Z_][\\w]*(\\.[a-zA-Z_][\\w$]*)+");
    }

    private static boolean looksLikeTypeName(String text) {
        return !isBlank(text)
            && Character.isUpperCase(text.charAt(0))
            && !"Compilation".equals(text)
            && !"Last".equals(text)
            && !"error".equalsIgnoreCase(text)
            && !"Task".equals(text);
    }

    private static String simpleNameOfImport(String importValue) {
        String normalized = blank(importValue);
        int idx = normalized.lastIndexOf('.');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static boolean mentionsEntryProblem(String text) {
        return text.contains("private access")
            || text.contains("has protected access")
            || text.contains("cannot be accessed")
            || text.contains("method") && text.contains("cannot be applied")
            || text.contains("non-static method");
    }

    private static boolean mentionsReceiverProblem(String text) {
        return text.contains("constructor")
            || text.contains("cannot be applied to given types")
            || text.contains("actual and formal argument lists differ")
            || text.contains("nullpointerexception");
    }

    private static boolean mentionsSameFailurePattern(String text) {
        return text.contains("still")
            || text.contains("again")
            || text.contains("same")
            || text.contains("survived");
    }

    private static boolean mentionsRuntimeEnvironmentProblem(String text) {
        return text.contains("mujava/mutationsystem")
            || text.contains("mujava.mutationsystem")
            || text.contains("noclassdeffounderror")
            || text.contains("classnotfoundexception")
            || text.contains("unsupportedclassversionerror")
            || text.contains("accessdeniedexception")
            || text.contains("拒绝访问")
            || text.contains("permission denied");
    }

    private static boolean mentionsEntryChainBypassed(String text) {
        return text.contains("semantic precheck failed")
            && (text.contains("real entry chain")
            || text.contains("guided entry")
            || text.contains("bypassed entry"));
    }

    private static boolean mentionsFrontGuardExceptionBias(String text) {
        return text.contains("semantic precheck failed")
            && (text.contains("front-loaded exception")
            || text.contains("null-guard")
            || text.contains("exception-only oracle"));
    }

    private static boolean mentionsDistinguishingInputMiss(String text) {
        return text.contains("semantic precheck failed")
            && (text.contains("distinguishing input")
            || text.contains("boundary-sensitive")
            || text.contains("default literals"));
    }

    private static boolean mentionsSymbolicPathViolation(String text) {
        return text.contains("semantic precheck failed")
            && (text.contains("symbolic")
            || text.contains("path constraint")
            || text.contains("preferred observable"));
    }

    private static String inferSelfUpdateEquivalenceHint(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject mutation = clean.optJSONObject("mutation");
        if (mutation == null || mutation.length() == 0) {
            mutation = root == null ? null : root.optJSONObject("mutation");
        }
        if (mutation == null || mutation.length() == 0) {
            return "";
        }

        String operator = lower(mutation.optString("operator", ""));
        if (!("aois".equals(operator) || "aoiu".equals(operator))) {
            return "";
        }

        String diff = mutation.optString("diff", mutation.optString("statement", ""));
        Matcher matcher = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\b\\s*=>\\s*(?:\\1\\s*(?:\\+\\+|--)|(?:\\+\\+|--)\\s*\\1)\\b")
                .matcher(diff);
        if (!matcher.find()) {
            return "";
        }

        String variable = matcher.group(1);
        JSONObject location = mutation.optJSONObject("location");
        String method = location == null ? "" : location.optString("method", "");
        return "The mutant survived and its diff is a self update on '" + variable
            + "' (" + diff + "). For AOIS/AOIU this is often unobservable when the updated value is overwritten or not read after the expression"
            + (isBlank(method) ? "." : " in " + method + ".");
    }

    private static boolean isEmptyReasoning(JSONObject obj) {
        return obj == null || obj.length() == 0 || "{}".equals(obj.toString());
    }

    private static JSONObject locateCanonicalCore(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject guidance = clean.optJSONObject("guidance");
        if (guidance != null) {
            JSONObject canonical = guidance.optJSONObject("canonicalCore");
            if (canonical != null) {
                return canonical;
            }
        }
        JSONObject embedded = clean.optJSONObject("canonicalCore");
        if (embedded != null) {
            return embedded;
        }
        JSONObject legacy = root == null ? null : root.optJSONObject("canonicalCore");
        return legacy == null ? new JSONObject() : legacy;
    }

    private static JSONObject locateSelectedEntry(JSONObject root) {
        JSONObject guidance = locateGuidance(root);
        JSONObject selected = guidance.optJSONObject("selectedEntry");
        if (selected != null && selected.length() > 0) {
            return selected;
        }
        JSONObject entrySelection = guidance.optJSONObject("entrySelection");
        selected = entrySelection == null ? null : entrySelection.optJSONObject("selected");
        if (selected != null && selected.length() > 0) {
            return selected;
        }
        JSONObject compact = root == null ? null : root.optJSONObject("selectedEntry");
        return compact == null ? new JSONObject() : compact;
    }

    private static String reachabilitySourceSummary(JSONObject guidance) {
        if (guidance == null || guidance.length() == 0) {
            return "";
        }
        JSONObject reachabilityGuards = guidance.optJSONObject("reachabilityGuards");
        if (reachabilityGuards == null || reachabilityGuards.length() == 0) {
            return "";
        }
        JSONArray mandatory = reachabilityGuards.optJSONArray("mandatoryGuards");
        JSONArray weak = reachabilityGuards.optJSONArray("weakFallbackGuards");
        JSONArray required = reachabilityGuards.optJSONArray("requiredPathPredicates");
        StringBuilder sb = new StringBuilder();
        if (mandatory != null && mandatory.length() > 0) {
            sb.append(" reachabilityGuards contains ").append(mandatory.length())
                .append(" mandatory guard hint(s) that were not copied into canonicalCore.");
        }
        if (weak != null && weak.length() > 0) {
            sb.append(" Only weak fallback guard(s) are available: ").append(weak.length()).append(".");
        }
        if (required != null && required.length() > 0) {
            sb.append(" requiredPathPredicates count=").append(required.length()).append(".");
        }
        return sb.toString();
    }

    private static boolean isSchema21(JSONObject root) {
        JSONObject clean = extractCleanRoot(root);
        JSONObject meta = clean.optJSONObject("meta");
        if (meta == null) {
            return false;
        }
        return "2.1".equals(meta.optString("schemaVersion", ""));
    }

    private static boolean hasFalsePolarityGuard(JSONArray guards) {
        if (guards == null) {
            return false;
        }
        for (int i = 0; i < guards.length(); i++) {
            JSONObject guard = guards.optJSONObject(i);
            if (guard == null) {
                continue;
            }
            if (!guard.optBoolean("requiredEvaluation", true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObservableSignal(JSONObject observability, JSONObject fullJson) {
        if (observability != null && observability.length() > 0) {
            String text = observability.toString().toLowerCase(Locale.ROOT);
            if (text.contains("observable") || text.contains("assert") || text.contains("return") || text.contains("exception")) {
                return true;
            }
        }
        if (fullJson != null) {
            String text = fullJson.toString().toLowerCase(Locale.ROOT);
            return text.contains("\"observableplan\"")
                || text.contains("\"assertionplan\"")
                || text.contains("\"observableselectionplan\"");
        }
        return false;
    }

    private static void copyReachabilityGap(JSONArray target, JSONObject fullJson) {
        JSONObject codekb = locateCodeKb(fullJson);
        copyMethodCalls(target, codekb.optJSONArray("mutantMethodCalls"));
    }

    private static void copyMethodCalls(JSONArray target, JSONArray calls) {
        for (int i = 0; i < length(calls); i++) {
            JSONObject obj = calls.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String owner = blank(obj.optString("owner"));
            String signature = blank(obj.optString("signature"));
            if (!owner.isEmpty() || !signature.isEmpty()) {
                target.put(owner + "#" + signature);
            }
        }
    }

    private static void copyObservableSignals(JSONArray target, JSONArray accesses, JSONArray calls) {
        for (int i = 0; i < length(accesses); i++) {
            JSONObject obj = accesses.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String owner = blank(obj.optString("ownerType"));
            String field = blank(obj.optString("fieldName"));
            if (!owner.isEmpty() || !field.isEmpty()) {
                target.put("FIELD " + owner + "." + field);
            }
        }
        for (int i = 0; i < length(calls); i++) {
            JSONObject obj = calls.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String owner = blank(obj.optString("owner"));
            String signature = blank(obj.optString("signature"));
            if (!owner.isEmpty() || !signature.isEmpty()) {
                target.put("CALL " + owner + "#" + signature);
            }
        }
    }

    private static int length(JSONArray arr) {
        return arr == null ? 0 : arr.length();
    }

    private static double optDouble(JSONObject obj, String key) {
        if (obj == null || isBlank(key)) {
            return 0.0d;
        }
        Object value = obj.opt(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private static String optString(JSONObject obj, String key) {
        if (obj == null || isBlank(key)) {
            return "";
        }
        return obj.optString(key, "").trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = blank(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
