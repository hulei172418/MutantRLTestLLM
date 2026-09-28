package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.testgenerator.tools.CommonUtils.firstNonBlank;
import static mujava.testgenerator.tools.EvidenceUtils.*;
import static mujava.testgenerator.tools.GeneratorDefaults.*;

/**
 * Compact prompt evidence extracted from the full RIP/DataGenerator output.json.
 *
 * This class owns the evidence-construction pipeline: mutation metadata, entry
 * relation, invocation plan, assertion plan, A-side CPG evidence, and optional
 * B-side entry-lifted evidence.
 */
public final class PromptEvidence {
    final JSONObject mutation = new JSONObject();
    final JSONObject entry = new JSONObject();
    final JSONObject executableTestPlan = new JSONObject();
    final JSONObject invocation = new JSONObject();
    final JSONObject assertions = new JSONObject();
    final JSONObject mutationEvidence = new JSONObject();
    final JSONObject entryEvidence = new JSONObject();
    final JSONObject mutationGraphEvidence = new JSONObject();
    final JSONObject entryGraphEvidence = new JSONObject();
    final JSONObject publicApiEvidence = new JSONObject();
    final JSONObject observablePlan = new JSONObject();
    final JSONObject mutationKillPlan = new JSONObject();
    final JSONObject inputDistinguishPlan = new JSONObject();
    final JSONObject propagationPlan = new JSONObject();
    final JSONObject assertionPlan = new JSONObject();
    final JSONObject ripExecutionPlan = new JSONObject();
    final JSONObject observableSelectionPlan = new JSONObject();
    final JSONObject entryParameterControlPlan = new JSONObject();
    final JSONObject killabilityPlan = new JSONObject();
    final JSONObject liftedReachabilityPlan = new JSONObject();
    final JSONObject indirectEntryTestPlan = new JSONObject();
    final JSONObject reachabilityGuardsPlan = new JSONObject();
    final JSONObject distinguishingInputGuidance = new JSONObject();
    final JSONObject observableDifferencePlan = new JSONObject();
    final JSONObject entryChainPlan = new JSONObject();
    final JSONObject symbolicRipPlan = new JSONObject();
    final JSONObject symbolicFeasibility = new JSONObject();
    final JSONObject evidenceQuality = new JSONObject();
    final JSONObject codeKbContext = new JSONObject();
    final JSONObject apiRoleFacts = new JSONObject();
    final JSONObject primaryChain = new JSONObject();
    final JSONArray alternativeChains = new JSONArray();
    final JSONObject compilationFacts = new JSONObject();
    final JSONObject entrySelection = new JSONObject();
    final JSONObject selectedEntry = new JSONObject();
    final JSONObject canonicalCore = new JSONObject();

    boolean needEntryLiftedEvidence;
    public boolean skipTestGeneration;
    public String skipReason = "";

    public static PromptEvidence fromFullOutput(JSONObject root) {
        PromptEvidence e = new PromptEvidence();
        JSONObject wrappedCleanSchema = root == null ? null : root.optJSONObject("OutputV2");
        if (wrappedCleanSchema != null && wrappedCleanSchema.length() > 0) {
            buildFromCleanSchema(wrappedCleanSchema, root, e);
            EvidencePostProcessor.postProcess(e);
            canonicalizePrimaryObservable(e);
            rebuildCanonicalCore(e);
            return e;
        }
        if (looksLikeCleanSchema(root)) {
            buildFromCleanSchema(root, root, e);
            EvidencePostProcessor.postProcess(e);
            canonicalizePrimaryObservable(e);
            rebuildCanonicalCore(e);
            return e;
        }
        if (root.has("mutation") && root.has("entry") && root.has("invocation") && root.has("assertions")) {
            copyObject(e.mutation, root.optJSONObject("mutation"));
            copyObject(e.entry, root.optJSONObject("entry"));
            copyObject(e.executableTestPlan, root.optJSONObject("executableTestPlan"));
            copyObject(e.invocation, root.optJSONObject("invocation"));
            copyObject(e.assertions, root.optJSONObject("assertions"));
            copyObject(e.mutationEvidence, root.optJSONObject("mutationEvidence"));
            copyObject(e.entryEvidence, root.optJSONObject("entryEvidence"));
            copyObject(e.mutationGraphEvidence, root.optJSONObject("mutationGraphEvidence"));
            copyObject(e.entryGraphEvidence, root.optJSONObject("entryGraphEvidence"));
            copyObject(e.publicApiEvidence, root.optJSONObject("publicApiEvidence"));
            copyObject(e.observablePlan, root.optJSONObject("observablePlan"));
            copyObject(e.mutationKillPlan, root.optJSONObject("mutationKillPlan"));
            copyObject(e.inputDistinguishPlan, root.optJSONObject("inputDistinguishPlan"));
            copyObject(e.propagationPlan, root.optJSONObject("propagationPlan"));
            copyObject(e.assertionPlan, root.optJSONObject("assertionPlan"));
            copyObject(e.ripExecutionPlan, root.optJSONObject("ripExecutionPlan"));
            copyObject(e.observableSelectionPlan, root.optJSONObject("observableSelectionPlan"));
            copyObject(e.entryParameterControlPlan, root.optJSONObject("entryParameterControlPlan"));
            copyObject(e.killabilityPlan, root.optJSONObject("killabilityPlan"));
            copyObject(e.liftedReachabilityPlan, root.optJSONObject("liftedReachabilityPlan"));
            copyObject(e.indirectEntryTestPlan, root.optJSONObject("indirectEntryTestPlan"));
            copyObject(e.reachabilityGuardsPlan, root.optJSONObject("reachabilityGuardsPlan"));
            copyObject(e.distinguishingInputGuidance, root.optJSONObject("distinguishingInputGuidance"));
            copyObject(e.observableDifferencePlan, root.optJSONObject("observableDifferencePlan"));
            copyObject(e.entryChainPlan, root.optJSONObject("entryChainPlan"));
            copyObject(e.symbolicRipPlan, root.optJSONObject("symbolicRipPlan"));
            copyObject(e.symbolicFeasibility, root.optJSONObject("symbolicFeasibility"));
            copyObject(e.evidenceQuality, root.optJSONObject("evidenceQuality"));
            copyObject(e.codeKbContext, root.optJSONObject("codeKbContext"));
            copyObject(e.apiRoleFacts, root.optJSONObject("apiRoleFacts"));
            copyObject(e.primaryChain, root.optJSONObject("primaryChain"));
            copyArray(e.alternativeChains, root.optJSONArray("alternativeChains"));
            copyObject(e.compilationFacts, root.optJSONObject("compilationFacts"));
            copyObject(e.entrySelection, root.optJSONObject("entrySelection"));
            copyObject(e.selectedEntry, firstNonEmptyObject(
                    root.optJSONObject("selectedEntry"),
                    e.entrySelection.optJSONObject("selected")));
            copyObject(e.canonicalCore, root.optJSONObject("canonicalCore"));
            e.needEntryLiftedEvidence = e.entry.optBoolean("needEntryLiftedEvidence", false);
            e.skipTestGeneration = e.entry.optBoolean("skipTestGeneration", false);
            e.skipReason = e.entry.optString("skipReason", "");
            applyObservableSelectionOverride(root, e);
            canonicalizePrimaryObservable(e);
            recoverConstructorStateObservable(root, e);
            canonicalizePrimaryObservable(e);
            EvidencePostProcessor.postProcess(e);
            rebuildCanonicalCore(e);
            return e;
        }

        JSONObject dep = itemObject(root.optJSONObject("DependencyContext"));
        JSONObject depRel = itemObject(childObject(dep, "relationship"));
        JSONObject testEntryContext = itemObject(childObject(dep, "testEntryContext"));
        JSONObject entryRip = itemObject(root.optJSONObject("EntryLiftedRIP"));
        JSONObject entryRelation = itemObject(childObject(entryRip, "entryRelation"));
        JSONObject entryGenPlan = itemObject(childObject(entryRip, "entryGenerationPlan"));

        buildMutation(root, e.mutation);
        buildEntry(depRel, entryRip, entryRelation, testEntryContext, e.entry);
        buildInvocation(testEntryContext, entryGenPlan, e.invocation);
        buildPublicApiAndObservableEvidence(entryGenPlan, testEntryContext, e);
        buildAssertions(entryGenPlan, e.assertions);
        applyObservableSelectionOverride(root, e);
        buildExecutableTestPlan(root, testEntryContext, entryGenPlan, entryRelation, depRel, e);
        buildMutationEvidence(root, e.mutationEvidence);
        buildMutationGraphEvidence(root, e.mutationGraphEvidence);
        buildKillOrientedEvidence(root, e);
        buildCodeKbContext(root, e);
        e.needEntryLiftedEvidence = e.entry.optBoolean("needEntryLiftedEvidence", false);
        e.skipTestGeneration = e.entry.optBoolean("skipTestGeneration", false);
        e.skipReason = e.entry.optString("skipReason", "");
        if (e.needEntryLiftedEvidence) {
            buildEntryEvidence(entryRip, e.entryEvidence);
            buildEntryGraphEvidence(entryRip, e.entryGraphEvidence);
        }
        canonicalizePrimaryObservable(e);
        recoverConstructorStateObservable(root, e);
        canonicalizePrimaryObservable(e);
        EvidencePostProcessor.postProcess(e);
        rebuildCanonicalCore(e);
        return e;
    }

    private static boolean looksLikeCleanSchema(JSONObject root) {
        return root != null
                && root.has("meta")
                && root.has("mutation")
                && root.has("summary")
                && root.has("guidance");
    }

    private static void buildFromCleanSchema(JSONObject cleanRoot, JSONObject fullRoot, PromptEvidence e) {
        JSONObject mutation = cleanRoot.optJSONObject("mutation");
        JSONObject summary = cleanRoot.optJSONObject("summary");
        JSONObject guidance = cleanRoot.optJSONObject("guidance");
        JSONObject evidence = cleanRoot.optJSONObject("evidence");

        JSONObject testTarget = guidance == null ? new JSONObject() : guidance.optJSONObject("testTarget");
        JSONObject setupPlan = guidance == null ? new JSONObject() : guidance.optJSONObject("setupPlan");
        JSONObject assertionGuide = guidance == null ? new JSONObject() : guidance.optJSONObject("assertionPlan");
        JSONObject indirectEntryPlan = guidance == null ? new JSONObject() : guidance.optJSONObject("indirectEntryTestPlan");
        JSONObject reachabilityGuards = guidance == null ? new JSONObject() : guidance.optJSONObject("reachabilityGuards");
        JSONObject distinguishingInputPlan = guidance == null ? new JSONObject() : guidance.optJSONObject("distinguishingInputPlan");
        JSONObject observableDiffPlan = guidance == null ? new JSONObject() : guidance.optJSONObject("observableDifferencePlan");
        JSONObject entryChainGuidance = guidance == null ? new JSONObject() : guidance.optJSONObject("entryChainPlan");
        JSONObject symbolicPlan = guidance == null ? new JSONObject() : guidance.optJSONObject("symbolicRipPlan");
        JSONObject ripPlan = guidance == null ? new JSONObject() : guidance.optJSONObject("ripPlan");
        JSONObject publicApi = guidance == null ? new JSONObject() : guidance.optJSONObject("publicApi");
        JSONObject roleFacts = guidance == null ? new JSONObject() : guidance.optJSONObject("apiRoleFacts");
        JSONObject primaryChain = guidance == null ? new JSONObject() : guidance.optJSONObject("primaryChain");
        JSONArray alternativeChains = guidance == null ? null : guidance.optJSONArray("alternativeChains");
        JSONObject compilationFacts = guidance == null ? new JSONObject() : guidance.optJSONObject("compilationFacts");
        JSONObject entrySelection = guidance == null ? new JSONObject() : guidance.optJSONObject("entrySelection");
        JSONObject selectedEntry = guidance == null ? new JSONObject() : guidance.optJSONObject("selectedEntry");
        JSONObject canonicalCore = guidance == null ? new JSONObject() : guidance.optJSONObject("canonicalCore");

        JSONObject observability = summary == null ? new JSONObject() : summary.optJSONObject("observability");
        JSONObject killability = summary == null ? new JSONObject() : summary.optJSONObject("killability");
        JSONObject reachability = summary == null ? new JSONObject() : summary.optJSONObject("reachability");
        JSONObject infection = summary == null ? new JSONObject() : summary.optJSONObject("infection");
        JSONObject equivalence = summary == null ? new JSONObject() : summary.optJSONObject("equivalence");
        JSONObject symbolicSummary = summary == null ? new JSONObject() : summary.optJSONObject("symbolicFeasibility");
        JSONObject evidenceQualitySummary = summary == null ? new JSONObject() : summary.optJSONObject("evidenceQuality");

        JSONObject structural = evidence == null ? new JSONObject() : evidence.optJSONObject("structural");
        JSONObject graph = evidence == null ? new JSONObject() : evidence.optJSONObject("graph");
        JSONObject reasoning = evidence == null ? new JSONObject() : evidence.optJSONObject("reasoning");
        JSONObject semantic = evidence == null ? new JSONObject() : evidence.optJSONObject("semantic");

        JSONObject dependency = structural == null ? new JSONObject() : structural.optJSONObject("dependency");
        JSONObject codekb = structural == null ? new JSONObject() : structural.optJSONObject("codekb");
        JSONObject graphMutationSite = graph == null ? new JSONObject() : graph.optJSONObject("mutationSite");
        JSONObject graphEntryLifted = graph == null ? new JSONObject() : graph.optJSONObject("entryLifted");
        JSONObject reasoningPropagation = reasoning == null ? new JSONObject() : reasoning.optJSONObject("propagation");
        JSONObject reasoningReachability = reasoning == null ? new JSONObject() : reasoning.optJSONObject("reachability");
        JSONObject reasoningObservability = reasoning == null ? new JSONObject() : reasoning.optJSONObject("observability");
        JSONObject reasoningRip = reasoning == null ? new JSONObject() : reasoning.optJSONObject("rip");

        buildMutationFromV2(mutation, semantic, e.mutation);
        buildEntryFromV2(mutation, killability, dependency, graphEntryLifted, e.entry);
        buildInvocationFromV2(testTarget, e.invocation);
        buildPublicApiAndObservableFromV2(publicApi, observability, reasoningObservability, e);
        buildAssertionsFromV2(assertionGuide, e.assertions);
        buildExecutableTestPlanFromV2(testTarget, setupPlan, publicApi, observability, reachability, infection, e);
        buildMutationEvidenceFromV2(graphMutationSite, semantic, e.mutationEvidence);
        buildMutationGraphEvidenceFromV2(graphMutationSite, reasoningPropagation, reasoningReachability, e.mutationGraphEvidence);
        buildEntryEvidenceFromV2(graphEntryLifted, e.entryEvidence, e.entryGraphEvidence);
        buildKillOrientedEvidenceFromV2(summary, guidance, reasoning, e);
        copyObject(e.indirectEntryTestPlan, indirectEntryPlan);
        copyObject(e.reachabilityGuardsPlan, reachabilityGuards);
        copyObject(e.distinguishingInputGuidance, distinguishingInputPlan);
        copyObject(e.observableDifferencePlan, observableDiffPlan);
        copyObject(e.entryChainPlan, entryChainGuidance);
        copyObject(e.symbolicRipPlan, symbolicPlan);
        copyObject(e.symbolicFeasibility, symbolicSummary);
        copyObject(e.ripExecutionPlan, firstNonEmptyObject(ripPlan, reasoningRip));
        copyObject(e.codeKbContext, codekb == null ? new JSONObject() : codekb);
        mergeGuidanceCodeKbContext(e.codeKbContext, summary, guidance);
        copyObject(e.apiRoleFacts, firstNonEmptyObject(roleFacts, codekb == null ? null : codekb.optJSONObject("apiRoleFacts")));
        copyObject(e.primaryChain, primaryChain);
        copyArray(e.alternativeChains, alternativeChains);
        copyObject(e.compilationFacts, compilationFacts);
        copyObject(e.entrySelection, entrySelection);
        copyObject(e.selectedEntry, firstNonEmptyObject(selectedEntry, entrySelection == null ? null : entrySelection.optJSONObject("selected")));
        copyObject(e.canonicalCore, firstNonEmptyObject(
                canonicalCore,
                cleanRoot.optJSONObject("canonicalCore"),
                fullRoot == null ? null : fullRoot.optJSONObject("canonicalCore")));
        alignInvocationWithSourceDerivedOracle(e);
        canonicalizePrimaryObservable(e);

        e.needEntryLiftedEvidence = e.entry.optBoolean("needEntryLiftedEvidence", false);
        e.skipTestGeneration = e.entry.optBoolean("skipTestGeneration", false);
        e.skipReason = e.entry.optString("skipReason", "");

        if (e.evidenceQuality.length() == 0) {
            if (equivalence != null && equivalence.optBoolean("suspected", false)) {
                e.evidenceQuality.put("equivalenceSuspicion", true);
                putIfNotEmpty(e.evidenceQuality, "equivalenceReason", equivalence.optString("note", ""));
            }
            if (evidenceQualitySummary != null) {
                putIfNotEmpty(e.evidenceQuality, "entryLiftedEvidence", evidenceQualitySummary.optString("entryLiftedEvidence", ""));
                putIfNotEmpty(e.evidenceQuality, "observableStrength", evidenceQualitySummary.optString("observableStrength", ""));
                putIfNotEmpty(e.evidenceQuality, "controllability", evidenceQualitySummary.optString("controllability", ""));
            }
        }

        if (e.observableSelectionPlan.optBoolean("overrideRecommended", false)) {
            alignObservableDependentPlans(e,
                    e.observableSelectionPlan.optString("preferredObservableKind", ""),
                    e.observableSelectionPlan.optString("preferredObservableCall", ""),
                    e.observableSelectionPlan.optString("overrideReason", ""));
        }
        if (e.skipTestGeneration) {
            recoverConstructorStateObservable(fullRoot, e);
        }
        canonicalizePrimaryObservable(e);
        rebuildCanonicalCore(e);
    }
    private static void buildExecutableTestPlan(JSONObject root,
                                                JSONObject testEntryContext,
                                                JSONObject entryGenPlan,
                                                JSONObject entryRelation,
                                                JSONObject depRel,
                                                PromptEvidence e) {
        JSONObject explicit = itemObject(root.optJSONObject("executableTestPlan"));
        if (explicit.length() == 0) {
            explicit = itemObject(childObject(entryGenPlan, "executableTestPlan"));
        }

        JSONObject plan = new JSONObject();

        String testPackage = firstNonBlank(
                fieldString(explicit, "testPackage"),
                e.invocation.optString("package", ""),
                fieldString(entryRelation, "testGenerationPackage"),
                fieldString(testEntryContext, "testPackage")
        );
        putIfNotEmpty(plan, "testPackage", testPackage);

        List<String> supportClasses = new ArrayList<String>();
        supportClasses.addAll(fieldItems(explicit, "supportClasses"));

        JSONObject receiver = e.invocation.optJSONObject("receiver");
        if (receiver != null) {
            String stub = sanitizeJavaSnippet(receiver.optString("testStubClassTemplate", ""));
            if (!stub.isEmpty()) {
                supportClasses.add(stub);
            }
        }

        if (!supportClasses.isEmpty()) {
            plan.put("supportClasses", new JSONArray(
                    limitList(cleanJavaSnippets(supportClasses), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        List<String> setup = new ArrayList<String>();
        setup.addAll(fieldItems(explicit, "requiredSetup"));
        setup.addAll(fieldItems(explicit, "setup"));
        setup.addAll(jsonArrayToStringList(e.invocation.optJSONArray("setup")));

        JSONObject branch = firstNonEmptyObject(
                itemObject(childObject(explicit, "branchReachability")),
                itemObject(childObject(entryGenPlan, "branchReachabilityPlan")),
                itemObject(childObject(itemObject(childObject(entryGenPlan, "publicApi")), "branchReachabilityPlan")),
                itemObject(childObject(itemObject(childObject(testEntryContext, "publicApi")), "branchReachabilityPlan"))
        );

        JSONObject branchOut = new JSONObject();
        putIfNotEmpty(branchOut, "kind", fieldString(branch, "kind"));
        putIfNotEmpty(branchOut, "condition", fieldString(branch, "condition"));
        putIfNotEmpty(branchOut, "setup", sanitizeJavaSnippet(fieldString(branch, "setup")));
        putIfNotEmpty(branchOut, "reason", fieldString(branch, "reason"));

        List<String> branchSetup = new ArrayList<String>();
        branchSetup.addAll(fieldItems(branch, "setupStatements"));
        String branchSetupText = sanitizeJavaSnippet(fieldString(branch, "setup"));
        if (!branchSetupText.isEmpty()) {
            branchSetup.add(branchSetupText);
        }

        if (!branchSetup.isEmpty()) {
            branchOut.put("setupStatements", new JSONArray(
                    limitList(cleanJavaStatements(branchSetup), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
            setup.addAll(branchSetup);
        }

        if (branchOut.length() > 0) {
            plan.put("branchReachability", branchOut);
        }

        String call = firstNonBlank(
                sanitizeJavaSnippet(fieldString(explicit, "entryCall")),
                e.invocation.optString("call", "")
        );
        putIfNotEmpty(plan, "entryCall", call);

        JSONObject observable = firstNonEmptyObject(
                itemObject(childObject(explicit, "observable")),
                e.observablePlan
        );

        JSONObject observableOut = new JSONObject();
        putIfNotEmpty(observableOut, "kind", fieldString(observable, "kind"));
        putIfNotEmpty(observableOut, "setup", sanitizeJavaSnippet(fieldString(observable, "setup")));
        putIfNotEmpty(observableOut, "observableCall", sanitizeJavaSnippet(fieldString(observable, "observableCall")));
        putIfNotEmpty(observableOut, "expectedOriginal", fieldString(observable, "expectedOriginal"));
        putIfNotEmpty(observableOut, "assertion", sanitizeJavaSnippet(fieldString(observable, "assertion")));
        putIfNotEmpty(observableOut, "reason", fieldString(observable, "reason"));

        if (observableOut.length() > 0) {
            plan.put("observable", observableOut);
            String obsSetup = observableOut.optString("setup", "").trim();
            if (!obsSetup.isEmpty()) {
                setup.add(obsSetup);
            }
        }

        List<String> forbidden = new ArrayList<String>();
        forbidden.addAll(fieldItems(explicit, "forbidden"));
        forbidden.addAll(fieldItems(explicit, "forbiddenDirectCalls"));
        forbidden.addAll(fieldItems(explicit, "antiPatterns"));

        JSONObject access = buildAccessConstraints(entryRelation, depRel, testEntryContext, entryGenPlan, e);
        if (access.length() > 0) {
            plan.put("accessConstraints", access);
            forbidden.addAll(jsonArrayToStringList(access.optJSONArray("forbiddenDirectCalls")));
            forbidden.addAll(jsonArrayToStringList(access.optJSONArray("forbiddenConstructions")));
        }

        JSONObject receiverOut = e.invocation.optJSONObject("receiver");
        enrichReceiverWithCompilationEvidence(receiver, receiverOut);
        // Do not duplicate the full receiver object inside EXECUTABLE_TEST_PLAN.
        // INVOCATION_WITH_RECEIVER_AND_STUB_RULES already carries receiver details.
        // Keeping only supportClasses/requiredSetup/entryCall prevents prompt bloat
        // and avoids LLM_OUTPUT_TRUNCATED for abstract-stub cases.
        if (receiverOut != null && receiverOut.length() > 0) {
            JSONObject receiverSummary = new JSONObject();
            putIfNotEmpty(receiverSummary, "strategy", receiverOut.optString("strategy", ""));
            putIfNotEmpty(receiverSummary, "runtimeReceiverClass", receiverOut.optString("runtimeReceiverClass", ""));
            putIfNotEmpty(receiverSummary, "resolutionReason", receiverOut.optString("resolutionReason", ""));
            if (receiverSummary.length() > 0) {
                plan.put("receiverSummary", receiverSummary);
            }
        }

        if (!setup.isEmpty()) {
            setup = cleanJavaStatements(setup);
            plan.put("requiredSetup", new JSONArray(
                    limitList(setup, DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
            e.invocation.put("setup", new JSONArray(setup));
        }

        if (!forbidden.isEmpty()) {
            plan.put("forbidden", new JSONArray(
                    limitList(uniqueNonBlank(forbidden), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        String status = firstNonBlank(fieldString(explicit, "status"), "");
        if (status.isEmpty()) {
            status = inferPlanStatus(plan, e);
        }
        putIfNotEmpty(plan, "status", status);

        String reason = firstNonBlank(
                fieldString(explicit, "reason"),
                branchOut.optString("reason", ""),
                observableOut.optString("reason", "")
        );
        putIfNotEmpty(plan, "reason", reason);

        copyObject(e.executableTestPlan, plan);
    }

    private static void buildMutationFromV2(JSONObject mutation, JSONObject semantic, JSONObject out) {
        if (mutation == null) {
            return;
        }
        putIfNotEmpty(out, "operator", mutation.optString("operator", ""));
        putIfNotEmpty(out, "diff", trimEvidence(mutation.optString("diff", ""), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        List<String> changes = jsonArrayToStringList(semantic == null ? null : semantic.optJSONArray("jimpleChanges"));
        out.put("affected", new JSONArray(limitList(changes, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        JSONObject bodies = mutation.optJSONObject("bodies");
        if (bodies != null && bodies.length() > 0) {
            JSONObject compactBodies = new JSONObject();
            putMutationBody(compactBodies, "original", bodies.optJSONObject("original"));
            putMutationBody(compactBodies, "mutant", bodies.optJSONObject("mutant"));
            if (compactBodies.length() > 0) {
                out.put("bodies", compactBodies);
            }
        }
    }

    private static void buildEntryFromV2(JSONObject mutation,
                                         JSONObject killability,
                                         JSONObject dependency,
                                         JSONObject entryLifted,
                                         JSONObject out) {
        JSONObject entry = mutation == null ? new JSONObject() : mutation.optJSONObject("entry");
        JSONObject relationship = dependency == null ? new JSONObject() : dependency.optJSONObject("relationship");
        JSONObject relation = entryLifted == null ? new JSONObject() : entryLifted.optJSONObject("relation");

        boolean same = entry != null && entry.optBoolean("sameEntryAndMutation", false);
        boolean need = !same && entry != null && !entry.optBoolean("useReflectionFallback", false);

        out.put("sameEntryAndMutation", same);
        out.put("needEntryLiftedEvidence", need);
        putIfNotEmpty(out, "recommendedTarget", "call_test_entry_method");
        putIfNotEmpty(out, "invocationKind", entry == null ? "" : entry.optString("invocationKind", ""));
        putIfNotEmpty(out, "testPackage", "");
        putIfNotEmpty(out, "mutationMethod", mutationMethodString(mutation));
        putIfNotEmpty(out, "entryMethod", entry == null ? "" : entry.optString("method", ""));
        putIfNotEmpty(out, "mutationClass", mutationClassString(mutation));
        putIfNotEmpty(out, "entryClass", entry == null ? "" : entry.optString("class", ""));
        out.put("callChain", new JSONArray(limitList(jsonArrayToStringList(entry == null ? null : entry.optJSONArray("callChain")),
                DEFAULT_MAX_ITEMS_IN_PROMPT)));

        String status = killability == null ? "" : killability.optString("status", "");
        out.put("killabilityStatus", status);
        // A blocked/equivalent hint is not proof. Keep generating so the run can
        // preserve compile-rate accounting and let downstream analysis classify it.
        out.put("skipTestGeneration", false);
        putIfNotEmpty(out, "skipReason", killability == null ? "" : killability.optString("reason", ""));

        if (relationship != null && relationship.length() > 0) {
            putIfNotEmpty(out, "testPackage", relationship.optString("testGenerationPackage", ""));
        }
        if (relation != null && relation.length() > 0 && out.optString("testPackage", "").isEmpty()) {
            putIfNotEmpty(out, "testPackage", relation.optString("testGenerationPackage", ""));
        }
    }

    private static void buildInvocationFromV2(JSONObject testTarget, JSONObject out) {
        if (testTarget == null) {
            return;
        }
        putIfNotEmpty(out, "package", testTarget.optString("package", ""));
        List<String> imports = ensureBasicJUnitImports(jsonArrayToStringList(testTarget.optJSONArray("imports")));
        out.put("imports", new JSONArray(imports));

        JSONObject receiver = testTarget.optJSONObject("receiver");
        JSONObject recv = new JSONObject();
        if (receiver != null) {
            copyObject(recv, receiver);
        }
        out.put("receiver", recv);

        JSONObject invocationPlan = testTarget.optJSONObject("invocationPlan");
        List<String> setup = new ArrayList<String>();
        if (receiver != null) {
            addIfNonBlank(setup, receiver.optString("setupTemplate", ""));
        }
        if (invocationPlan != null) {
            addIfNonBlank(setup, invocationPlan.optString("setupTemplate", ""));
            putIfNotEmpty(out, "call", sanitizeJavaSnippet(invocationPlan.optString("invocationTemplate", "")));
            putIfNotEmpty(out, "notes", invocationPlan.optString("notes", ""));
        }
        JSONArray args = testTarget.optJSONArray("arguments");
        if (args != null) {
            for (int i = 0; i < args.length(); i++) {
                JSONObject arg = args.optJSONObject(i);
                if (arg != null) {
                    addIfNonBlank(setup, arg.optString("exampleValue", ""));
                }
            }
        }
        out.put("setup", new JSONArray(limitList(cleanJavaStatements(setup), DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static void buildPublicApiAndObservableFromV2(JSONObject publicApi,
                                                          JSONObject observability,
                                                          JSONObject reasoningObservability,
                                                          PromptEvidence e) {
        if (publicApi != null) {
            copyObject(e.publicApiEvidence, publicApi);
        }
        boolean overrideRecommended = observability != null && observability.optBoolean("overrideRecommended", false);
        JSONObject observable = new JSONObject();
        putIfNotEmpty(observable, "kind", observability == null ? "" : firstNonBlank(
                overrideRecommended ? observability.optString("preferredKind", "") : "",
                observability.optString("baselineKind", "")));
        putIfNotEmpty(observable, "observableCall", observability == null ? "" : firstNonBlank(
                overrideRecommended ? observability.optString("preferredCall", "") : "",
                observability.optString("baselineCall", "")));
        putIfNotEmpty(observable, "reason", observability == null ? "" : observability.optString("reason", ""));

        if ((observable.optString("observableCall", "").isEmpty()) && reasoningObservability != null) {
            JSONObject selection = reasoningObservability.optJSONObject("selection");
            if (selection != null) {
                boolean selectionOverride = selection.optBoolean("overrideRecommended", false);
                putIfNotEmpty(observable, "observableCall", firstNonBlank(
                        selectionOverride ? selection.optString("preferredObservableCall", "") : "",
                        selection.optString("baselineObservableCall", "")));
                if (observable.optString("kind", "").isEmpty()) {
                    putIfNotEmpty(observable, "kind", firstNonBlank(
                            selectionOverride ? selection.optString("preferredObservableKind", "") : "",
                            selection.optString("baselineObservableKind", "")));
                }
            }
        }
        copyObject(e.observablePlan, observable);
    }

    private static void buildAssertionsFromV2(JSONObject assertionGuide, JSONObject out) {
        if (assertionGuide == null) {
            return;
        }
        out.put("requiredToKill", new JSONArray(limitList(
                cleanJavaStatements(jsonArrayToStringList(assertionGuide.optJSONArray("requiredToKill"))),
                DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("optionalSanityChecks", new JSONArray(limitList(
                cleanJavaStatements(jsonArrayToStringList(assertionGuide.optJSONArray("optionalChecks"))),
                DEFAULT_MAX_ITEMS_IN_PROMPT)));

        JSONObject recommended = assertionGuide.optJSONObject("recommendedAssertions");
        List<String> avoid = new ArrayList<String>(jsonArrayToStringList(assertionGuide.optJSONArray("antiPatterns")));
        List<String> sensitive = new ArrayList<String>();
        List<String> auxiliary = new ArrayList<String>();
        if (recommended != null) {
            sensitive.addAll(recommendedAssertionExpressions(recommended, "primaryAssertions"));
            auxiliary.addAll(recommendedAssertionExpressions(recommended, "secondaryAssertions"));
        }
        out.put("mutationSensitiveObservables", new JSONArray(limitList(sensitive, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("auxiliaryObservables", new JSONArray(limitList(auxiliary, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("avoid", new JSONArray(limitList(avoid, DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static void buildExecutableTestPlanFromV2(JSONObject testTarget,
                                                      JSONObject setupPlan,
                                                      JSONObject publicApi,
                                                      JSONObject observability,
                                                      JSONObject reachability,
                                                      JSONObject infection,
                                                      PromptEvidence e) {
        JSONObject plan = new JSONObject();
        putIfNotEmpty(plan, "testPackage", testTarget == null ? "" : testTarget.optString("package", ""));

        List<String> setup = new ArrayList<String>();
        setup.addAll(jsonArrayToStringList(setupPlan == null ? null : setupPlan.optJSONArray("stateSetup")));
        setup.addAll(jsonArrayToStringList(setupPlan == null ? null : setupPlan.optJSONArray("preconditions")));
        setup.addAll(jsonArrayToStringList(e.invocation.optJSONArray("setup")));
        if (!setup.isEmpty()) {
            plan.put("requiredSetup", new JSONArray(limitList(cleanJavaStatements(setup), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        putIfNotEmpty(plan, "entryCall", e.invocation.optString("call", ""));
        JSONObject observable = new JSONObject();
        putIfNotEmpty(observable, "kind", e.observablePlan.optString("kind", ""));
        putIfNotEmpty(observable, "observableCall", e.observablePlan.optString("observableCall", ""));
        putIfNotEmpty(observable, "reason", e.observablePlan.optString("reason", ""));
        if (observable.length() > 0) {
            plan.put("observable", observable);
        }

        JSONObject branch = new JSONObject();
        putIfNotEmpty(branch, "kind", reachability == null ? "" : reachability.optString("level", ""));
        putIfNotEmpty(branch, "condition", reachability == null ? "" : reachability.optString("keyCondition", ""));
        putIfNotEmpty(branch, "reason", reachability == null ? "" : reachability.optString("reason", ""));
        if (branch.length() > 0) {
            plan.put("branchReachability", branch);
        }

        JSONObject infectionOut = buildInfectionPrompt(infection, e.inputDistinguishPlan, e.mutationKillPlan);
        if (infectionOut.length() > 0) {
            plan.put("infection", infectionOut);
            setup.addAll(jsonArrayToStringList(infectionOut.optJSONArray("requiredPathPredicates")));
            String inputHint = infectionOut.optString("inputHint", "").trim();
            if (!inputHint.isEmpty()) {
                setup.add(inputHint);
            }
        }

        if (!setup.isEmpty()) {
            plan.put("requiredSetup", new JSONArray(limitList(cleanJavaStatements(setup), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        List<String> forbidden = jsonArrayToStringList(setupPlan == null ? null : setupPlan.optJSONArray("avoid"));
        if (!forbidden.isEmpty()) {
            plan.put("forbidden", new JSONArray(limitList(forbidden, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        putIfNotEmpty(plan, "status", inferPlanStatus(plan, e));
        putIfNotEmpty(plan, "reason", observability == null ? "" : observability.optString("reason", ""));
        copyObject(e.executableTestPlan, plan);
    }

    private static void buildMutationEvidenceFromV2(JSONObject graphMutationSite, JSONObject semantic, JSONObject out) {
        if (graphMutationSite == null) {
            return;
        }
        JSONObject origin = graphMutationSite.optJSONObject("origin");
        JSONObject mutated = graphMutationSite.optJSONObject("mutated");
        putIfNotEmpty(out, "originCode", trimEvidence(origin == null ? "" : origin.optString("content", ""), DEFAULT_MAX_CODE_CHARS));
        putIfNotEmpty(out, "mutantCode", trimEvidence(mutated == null ? "" : mutated.optString("content", ""), DEFAULT_MAX_CODE_CHARS));
        out.put("originAffected", new JSONArray(limitList(jsonArrayToStringList(origin == null ? null : origin.optJSONArray("Affected")),
                DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("mutantAffected", new JSONArray(limitList(jsonArrayToStringList(mutated == null ? null : mutated.optJSONArray("Affected")),
                DEFAULT_MAX_ITEMS_IN_PROMPT)));
        JSONObject mutationEffect = semantic == null ? null : semantic.optJSONObject("mutationEffect");
        putIfNotEmpty(out, "propagationHint", mutationEffect == null ? "" : mutationEffect.optString("affectedBehavior", ""));
    }

    private static void buildMutationGraphEvidenceFromV2(JSONObject graphMutationSite,
                                                         JSONObject reasoningPropagation,
                                                         JSONObject reasoningReachability,
                                                         JSONObject out) {
        if (graphMutationSite == null) {
            return;
        }
        out.put("role", "A-side evidence derived from graph.mutationSite and evidence.reasoning.");
        JSONObject origin = graphMutationSite.optJSONObject("origin");
        JSONObject mutated = graphMutationSite.optJSONObject("mutated");
        if (origin != null) {
            JSONObject originGraph = summarizeCpgSide(origin);
            if (originGraph.length() > 0) {
                out.put("originGraph", originGraph);
            }
        }
        if (mutated != null) {
            JSONObject mutantGraph = summarizeCpgSide(mutated);
            if (mutantGraph.length() > 0) {
                out.put("mutantGraph", mutantGraph);
            }
        }
        List<String> available = new ArrayList<String>();
        available.addAll(jsonArrayToStringList(reasoningReachability == null ? null : reasoningReachability.optJSONArray("keyPredicates")));
        available.addAll(jsonArrayToStringList(reasoningPropagation == null ? null : reasoningPropagation.optJSONArray("usesTowardOutput")));
        if (!available.isEmpty()) {
            out.put("availableEvidenceKinds", new JSONArray(limitList(available, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
    }

    private static void buildEntryEvidenceFromV2(JSONObject entryLifted, JSONObject out, JSONObject graphOut) {
        if (entryLifted == null) {
            return;
        }
        JSONObject origin = entryLifted.optJSONObject("origin");
        JSONObject mutated = entryLifted.optJSONObject("mutated");
        if (origin != null) {
            putIfNotEmpty(out, "entryCode", trimEvidence(origin.optString("content", ""), DEFAULT_MAX_CODE_CHARS));
            List<String> callSites = jsonArrayToStringList(origin.optJSONArray("callSitesToMutation"));
            if (!callSites.isEmpty()) {
                out.put("callSiteToMutation", trimEvidence(callSites.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            }
            List<String> paths = jsonArrayToStringList(origin.optJSONArray("Paths"));
            if (!paths.isEmpty()) {
                out.put("pathSummary", trimEvidence(paths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            }

            graphOut.put("role", "B-side evidence derived from graph.entryLifted.");
            JSONObject originGraph = summarizeCpgSide(origin);
            if (originGraph.length() > 0) {
                graphOut.put("originEntryGraph", originGraph);
            }
        }
        graphOut.put("mutatedReusesOriginEntryGraph", mutated != null && mutated.optBoolean("reusedFromOrigin", false));
    }

    private static void buildKillOrientedEvidenceFromV2(JSONObject summary,
                                                        JSONObject guidance,
                                                        JSONObject reasoning,
                                                        PromptEvidence e) {
        JSONObject killability = summary == null ? null : summary.optJSONObject("killability");
        if (killability != null) {
            copyObject(e.killabilityPlan, killability);
            putIfNotEmpty(e.killabilityPlan, "killabilityCategory", killability.optString("category", ""));
            putIfNotEmpty(e.killabilityPlan, "killabilityLevel", killability.optString("level", ""));
        }

        JSONObject observability = summary == null ? null : summary.optJSONObject("observability");
        if (observability != null) {
            JSONObject selection = new JSONObject();
            putIfNotEmpty(selection, "baselineObservableKind", observability.optString("baselineKind", ""));
            putIfNotEmpty(selection, "baselineObservableCall", observability.optString("baselineCall", ""));
            putIfNotEmpty(selection, "preferredObservableKind", observability.optString("preferredKind", ""));
            putIfNotEmpty(selection, "preferredObservableCall", observability.optString("preferredCall", ""));
            selection.put("overrideRecommended", observability.optBoolean("overrideRecommended", false));
            putIfNotEmpty(selection, "overrideReason", observability.optString("reason", ""));
            copyObject(e.observableSelectionPlan, selection);
        }

        JSONObject reachability = summary == null ? null : summary.optJSONObject("reachability");
        if (reachability != null) {
            copyObject(e.liftedReachabilityPlan, reachability);
        }

        JSONObject propagation = summary == null ? null : summary.optJSONObject("propagation");
        if (propagation != null) {
            copyObject(e.propagationPlan, propagation);
        }

        JSONObject setupPlan = guidance == null ? null : guidance.optJSONObject("setupPlan");
        if (setupPlan != null) {
            copyObject(e.inputDistinguishPlan, setupPlan);
            copyObject(e.entryParameterControlPlan, setupPlan);
        }

        JSONObject assertionPlan = guidance == null ? null : guidance.optJSONObject("assertionPlan");
        if (assertionPlan != null) {
            copyObject(e.mutationKillPlan, assertionPlan);
            copyObject(e.assertionPlan, assertionPlan);
        }

        JSONObject infection = summary == null ? null : summary.optJSONObject("infection");
        mergeInfectionIntoInputPlan(infection, e.inputDistinguishPlan);
        mergeInfectionIntoMutationKillPlan(infection, e.mutationKillPlan);

        JSONObject evidenceQualitySummary = summary == null ? null : summary.optJSONObject("evidenceQuality");
        if (evidenceQualitySummary != null) {
            copyObject(e.evidenceQuality, evidenceQualitySummary);
        }

        JSONObject equivalence = summary == null ? null : summary.optJSONObject("equivalence");
        if (equivalence != null && equivalence.optBoolean("suspected", false)) {
            e.evidenceQuality.put("equivalenceSuspicion", true);
            putIfNotEmpty(e.evidenceQuality, "equivalenceReason", equivalence.optString("note", ""));
        }
    }

    private static void mergeGuidanceCodeKbContext(JSONObject target,
                                                   JSONObject summary,
                                                   JSONObject guidance) {
        if (target == null || guidance == null) {
            return;
        }
        JSONObject summaryCodeKb = summary == null ? null : summary.optJSONObject("codekb");
        if (summaryCodeKb != null) {
            if (!target.has("enabled")) {
                target.put("enabled", summaryCodeKb.optBoolean("enabled", false));
            }
            putIfNotEmpty(target, "status", summaryCodeKb.optString("status", ""));
        }
        copyArrayIfAbsent(target, "candidateEntries", guidance.optJSONArray("entryCandidates"));
        copyArrayIfAbsent(target, "observableCandidates", guidance.optJSONArray("observableCandidates"));
        copyArrayIfAbsent(target, "fieldObserverLinks", guidance.optJSONArray("fieldObserverLinks"));
        copyArrayIfAbsent(target, "mutantWitnessCandidates", guidance.optJSONArray("distinguishingWitnesses"));
        copyObjectIfAbsent(target, "bestEntryChain", guidance.optJSONObject("bestEntryChain"));
        copyObjectIfAbsent(target, "bestObservableChain", guidance.optJSONObject("bestObservableChain"));
        JSONObject compilationFacts = guidance.optJSONObject("compilationFacts");
        if (compilationFacts != null) {
            copyObjectIfAbsent(target, "compilableApiFacts", compilationFacts.optJSONObject("receiver"));
            copyArrayIfAbsent(target, "exactCallableSignatures", compilationFacts.optJSONArray("exactCallableSignatures"));
        }
    }

    private static void alignInvocationWithSourceDerivedOracle(PromptEvidence e) {
        if (e == null || e.apiRoleFacts.length() == 0) {
            return;
        }
        JSONObject observationFacts = e.apiRoleFacts.optJSONObject("observationFacts");
        JSONObject primaryOracle = observationFacts == null ? null : observationFacts.optJSONObject("primaryOracle");
        String oracleExpression = sanitizeJavaSnippet(primaryOracle == null ? "" : primaryOracle.optString("expression", ""));
        if (oracleExpression.isEmpty()) {
            return;
        }
        String currentCall = e.invocation.optString("call", "");
        if (currentCall.isEmpty() || isNullOnlyInvocation(currentCall)) {
            e.invocation.put("call", oracleExpression);
            putIfNotEmpty(e.invocation, "sourceDerivedCallOverride",
                    "Replaced null/default invocation with apiRoleFacts.observationFacts.primaryOracle.");
        }
        String entryCall = e.executableTestPlan.optString("entryCall", "");
        if (entryCall.isEmpty() || isNullOnlyInvocation(entryCall)) {
            e.executableTestPlan.put("entryCall", oracleExpression);
        }
        removeNullOnlySetup(e.invocation);
    }

    private static void canonicalizePrimaryObservable(PromptEvidence e) {
        if (e == null) {
            return;
        }
        String observableCall = e.primaryChainOracleCall();
        if (!isExecutableObservableCall(observableCall)) {
            return;
        }

        JSONObject oracle = e.primaryChainOracle();
        JSONObject observable = e.observablePlan == null ? new JSONObject() : e.observablePlan;
        putIfNotEmpty(observable, "kind", firstNonBlank(
                fieldString(oracle, "observableKind"),
                fieldString(oracle, "kind"),
                fieldString(e.observableDifferencePlan, "preferredObservableKind"),
                fieldString(observable, "kind")));
        observable.put("observableCall", observableCall);
        putIfNotEmpty(observable, "expectedOriginal", firstNonBlank(
                fieldString(oracle, "expectedOriginal"),
                fieldString(observable, "expectedOriginal")));
        putIfNotEmpty(observable, "expectedMutant", firstNonBlank(
                fieldString(oracle, "expectedMutant"),
                fieldString(observable, "expectedMutant")));
        putIfNotEmpty(observable, "assertion", firstNonBlank(
                sanitizeJavaSnippet(fieldString(oracle, "assertion")),
                sanitizeJavaSnippet(fieldString(observable, "assertion"))));
        putIfNotEmpty(observable, "reason", firstNonBlank(
                fieldString(oracle, "reason"),
                fieldString(observable, "reason")));
        observable.put("canonicalSource", "primaryChain.oracle");

        if (e.executableTestPlan != null) {
            JSONObject execObservable = e.executableTestPlan.optJSONObject("observable");
            if (execObservable == null) {
                execObservable = new JSONObject();
                e.executableTestPlan.put("observable", execObservable);
            }
            copyObject(execObservable, observable);
        }
        if (e.propagationPlan != null) {
            String sinkExpression = extractObservedExpression(observableCall);
            putIfNotEmpty(e.propagationPlan, "observableSinkKind", fieldString(observable, "kind"));
            putIfNotEmpty(e.propagationPlan, "observableSinkExpression",
                    sinkExpression.isEmpty() ? observableCall : sinkExpression);
            replacePrimaryObservableStep(e.propagationPlan, observableCall);
        }
        if (e.liftedReachabilityPlan != null) {
            replaceLiftedObservableStep(e.liftedReachabilityPlan, observableCall);
        }
    }

    private static void rebuildCanonicalCore(PromptEvidence e) {
        if (e == null) {
            return;
        }
        if (hasEvidenceProvidedCanonicalCore(e)) {
            return;
        }
        String[] names = JSONObject.getNames(e.canonicalCore);
        if (names != null) {
            for (String name : names) {
                e.canonicalCore.remove(name);
            }
        }
        JSONObject entryCore = new JSONObject();
        putIfNotEmpty(entryCore, "entryMethodSignature", firstNonBlank(
                fieldString(e.entry, "entryMethodSignature"),
                fieldString(e.entry, "entryMethod"),
                fieldString(e.entry, "method")));
        putIfNotEmpty(entryCore, "callChainHead", firstNonBlank(
                fieldString(e.entry, "callChainHead"),
                fieldString(e.entry, "recommendedTarget"),
                fieldString(e.executableTestPlan, "entryCall")));
        if (entryCore.length() > 0) {
            e.canonicalCore.put("entry", entryCore);
        }

        JSONObject reachability = new JSONObject();
        JSONArray mandatoryPredicates = new JSONArray();
        mergeStringArray(mandatoryPredicates, fieldArray(e.primaryChain, "requiredPathPredicates"));
        mergeStringArray(mandatoryPredicates, fieldArray(e.ripExecutionPlan, "requiredPathPredicates"));
        mergeStringArray(mandatoryPredicates, fieldArray(e.inputDistinguishPlan, "distinguishingConstraints"));
        mergeStringArray(mandatoryPredicates, fieldArray(e.reachabilityGuardsPlan, "mustPassBeforeMutation"));
        if (mandatoryPredicates.length() > 0) {
            reachability.put("mandatoryPredicates", mandatoryPredicates);
        }
        if (reachability.length() > 0) {
            e.canonicalCore.put("reachability", reachability);
        }

        JSONObject infection = new JSONObject();
        putIfNotEmpty(infection, "semanticKind", firstNonBlank(
                fieldString(e.primaryChain, "semanticKind"),
                fieldString(e.ripExecutionPlan, "semanticKind"),
                fieldString(e.mutationKillPlan, "mutationSemanticKind")));
        putIfNotEmpty(infection, "sourceExpression", firstNonBlank(
                fieldString(e.primaryChain, "sourceExpression"),
                fieldString(e.primaryChain, "source"),
                fieldString(e.ripExecutionPlan, "sourceExpression")));
        putIfNotEmpty(infection, "mutantExpression", firstNonBlank(
                fieldString(e.primaryChain, "mutantExpression"),
                fieldString(e.primaryChain, "mutantSource"),
                fieldString(e.ripExecutionPlan, "mutantExpression")));
        JSONArray mandatoryConstraints = new JSONArray();
        mergeStringArray(mandatoryConstraints, fieldArray(e.primaryChain, "distinguishingConstraints"));
        mergeStringArray(mandatoryConstraints, fieldArray(e.observableDifferencePlan, "mandatoryConstraints"));
        if (mandatoryConstraints.length() > 0) {
            infection.put("mandatoryConstraints", mandatoryConstraints);
        }
        if (infection.length() > 0) {
            e.canonicalCore.put("infection", infection);
        }

        JSONObject observable = new JSONObject();
        putIfNotEmpty(observable, "kind", firstNonBlank(
                fieldString(e.observablePlan, "kind"),
                fieldString(e.observableDifferencePlan, "preferredObservableKind")));
        putIfNotEmpty(observable, "call", e.preferredObservableCall());
        if (observable.length() > 0) {
            e.canonicalCore.put("observable", observable);
        }

        JSONObject oracle = new JSONObject();
        putIfNotEmpty(oracle, "assertionMode", firstNonBlank(
                fieldString(e.primaryChain, "assertionKind"),
                fieldString(e.ripExecutionPlan, "preferredAssertionMode"),
                e.preferredAssertionMode()));
        putIfNotEmpty(oracle, "expectedOriginal", firstNonBlank(
                e.primaryChainExpectedOriginal(),
                fieldString(e.observablePlan, "expectedOriginal")));
        putIfNotEmpty(oracle, "expectedOriginalExecutable",
                String.valueOf(e.observablePlan.optBoolean("expectedOriginalExecutable", false)));
        putIfNotEmpty(oracle, "expectedOriginalNarrative",
                fieldString(e.observablePlan, "expectedOriginalNarrative"));
        putIfNotEmpty(oracle, "expectedMutantExplanation", firstNonBlank(
                e.primaryChainExpectedMutant(),
                fieldString(e.observablePlan, "expectedMutant")));
        if (oracle.length() > 0) {
            e.canonicalCore.put("oracle", oracle);
        }

        JSONObject construction = new JSONObject();
        JSONArray requiredNonNull = new JSONArray();
        mergeStringArray(requiredNonNull, fieldArray(e.primaryChain, "requiredNonNullSubjects"));
        mergeStringArray(requiredNonNull, fieldArray(e.inputDistinguishPlan, "requiredNonNullSubjects"));
        if (requiredNonNull.length() > 0) {
            construction.put("requiredNonNullSubjects", requiredNonNull);
        }
        if (construction.length() > 0) {
            e.canonicalCore.put("construction", construction);
        }
    }

    private static boolean hasEvidenceProvidedCanonicalCore(PromptEvidence e) {
        if (e == null || e.canonicalCore == null || e.canonicalCore.length() == 0) {
            return false;
        }
        return e.canonicalCore.has("entry")
                || e.canonicalCore.has("reachability")
                || e.canonicalCore.has("infection")
                || e.canonicalCore.has("observable")
                || e.canonicalCore.has("oracle")
                || e.canonicalCore.has("construction");
    }

    private static void mergeStringArray(JSONArray target, JSONArray incoming) {
        if (target == null || incoming == null) {
            return;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>(jsonArrayToStringList(target));
        for (int i = 0; i < incoming.length(); i++) {
            String value = String.valueOf(incoming.opt(i)).trim();
            if (!value.isEmpty() && seen.add(value)) {
                target.put(value);
            }
        }
    }

    private static JSONArray fieldArray(JSONObject obj, String key) {
        JSONArray arr = obj == null ? null : obj.optJSONArray(key);
        if (arr == null) {
            return new JSONArray();
        }
        JSONArray copy = new JSONArray();
        copyArray(copy, arr);
        return copy;
    }

    private static boolean isExecutableObservableCall(String call) {
        String normalized = call == null ? "" : call.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String upper = normalized.toUpperCase(java.util.Locale.ROOT);
        return !upper.contains("METHOD_COMPLETION")
                && !upper.contains("NO_OBSERVABLE_PROVEN");
    }

    private JSONObject primaryChainOracle() {
        JSONObject oracle = primaryChain == null ? null : primaryChain.optJSONObject("oracle");
        if (oracle != null) {
            return oracle;
        }
        oracle = primaryChain == null ? null : primaryChain.optJSONObject("Oracle");
        return oracle == null ? new JSONObject() : oracle;
    }

    private String primaryChainPropagationObservableCall() {
        JSONObject propagation = primaryChain == null ? null : primaryChain.optJSONObject("propagation");
        if (propagation == null) {
            return "";
        }
        Object observable = propagation.opt("observable");
        if (observable instanceof JSONObject) {
            JSONObject obj = (JSONObject) observable;
            return firstNonBlank(
                    fieldString(obj, "observableCall"),
                    fieldString(obj, "call"),
                    fieldString(obj, "expression"));
        }
        if (observable instanceof String) {
            return String.valueOf(observable).trim();
        }
        return firstNonBlank(
                fieldString(propagation, "observableCall"),
                fieldString(propagation, "call"),
                fieldString(propagation, "expression"));
    }

    private static boolean isNullOnlyInvocation(String call) {
        String normalized = (call == null ? "" : call).replaceAll("\\s+", "");
        return normalized.contains("(null)")
                || normalized.contains(",null)")
                || normalized.contains("(null,")
                || normalized.contains(",null,");
    }

    private static void removeNullOnlySetup(JSONObject invocation) {
        if (invocation == null) {
            return;
        }
        JSONArray setup = invocation.optJSONArray("setup");
        if (setup == null || setup.length() == 0) {
            return;
        }
        JSONArray cleaned = new JSONArray();
        for (int i = 0; i < setup.length(); i++) {
            String item = setup.optString(i, "").trim();
            if (item.equalsIgnoreCase("null")) {
                continue;
            }
            cleaned.put(setup.opt(i));
        }
        invocation.put("setup", cleaned);
    }

    private static void copyArrayIfAbsent(JSONObject target, String key, JSONArray source) {
        if (target == null || source == null || source.length() == 0 || target.has(key)) {
            return;
        }
        JSONArray copy = new JSONArray();
        copyArray(copy, source);
        target.put(key, copy);
    }

    private static void copyObjectIfAbsent(JSONObject target, String key, JSONObject source) {
        if (target == null || source == null || source.length() == 0 || target.has(key)) {
            return;
        }
        JSONObject copy = new JSONObject();
        copyObject(copy, source);
        target.put(key, copy);
    }

    private static void addIfNonBlank(List<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.add(value.trim());
        }
    }

    private static String mutationMethodString(JSONObject mutation) {
        JSONObject location = mutation == null ? null : mutation.optJSONObject("location");
        return location == null ? "" : location.optString("method", "");
    }

    private static String mutationClassString(JSONObject mutation) {
        JSONObject location = mutation == null ? null : mutation.optJSONObject("location");
        return location == null ? "" : location.optString("class", "");
    }
    private static void enrichReceiverWithCompilationEvidence(JSONObject receiver,
                                                              JSONObject receiverOut) {
        if (receiver == null || receiver.length() == 0 || receiverOut == null) {
            return;
        }

        List<String> antiPatterns = fieldItems(receiver, "antiPatterns");
        if (!antiPatterns.isEmpty()) {
            receiverOut.put("antiPatterns",
                    new JSONArray(limitList(antiPatterns, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        List<String> allowedOverrides = fieldItems(receiver, "allowedOverrides");
        if (!allowedOverrides.isEmpty()) {
            receiverOut.put("allowedOverrides",
                    new JSONArray(limitList(cleanJavaSnippets(allowedOverrides), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        List<String> forbiddenOverrides = fieldItems(receiver, "forbiddenOverrides");
        if (!forbiddenOverrides.isEmpty()) {
            receiverOut.put("forbiddenOverrides",
                    new JSONArray(compactForbiddenOverrides(forbiddenOverrides, receiverOut)));
        }

        List<String> abstractMethods = fieldItems(receiver, "abstractMethodsToImplement");
        if (!abstractMethods.isEmpty()) {
            receiverOut.put("abstractMethodsToImplement",
                    new JSONArray(limitList(cleanJavaSnippets(abstractMethods), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        String stubClassTemplate = firstNonBlank(
                fieldString(receiver, "testStubClassTemplate"),
                fieldStringCompat(receiver, "testStubClassTemplate")
        );
        if (!stubClassTemplate.trim().isEmpty()) {
            receiverOut.put("testStubClassTemplate", sanitizeJavaSnippet(stubClassTemplate));
        }

        String stubConstructorTemplate = firstNonBlank(
                fieldString(receiver, "testStubConstructorTemplate"),
                fieldStringCompat(receiver, "testStubConstructorTemplate")
        );
        if (!stubConstructorTemplate.trim().isEmpty()) {
            receiverOut.put("testStubConstructorTemplate", sanitizeJavaSnippet(stubConstructorTemplate));
        }

        String reason = fieldString(receiver, "resolutionReason");
        if (!reason.trim().isEmpty()) {
            receiverOut.put("resolutionReason", reason);
        }
    }

    private static List<String> compactForbiddenOverrides(List<String> raw, JSONObject receiverOut) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<String>();
        }
        boolean hasStub = receiverOut != null
                && receiverOut.optString("testStubClassTemplate", "").trim().length() > 0;
        if (hasStub) {
            out.add("Only use the generated testStubClassTemplate; do not add extra @Override methods.");
            out.add("Do not override final/private/concrete/nonexistent methods.");
            out.add("Do not reduce access privileges when overriding.");
            return new ArrayList<String>(out);
        }
        for (String x : raw) {
            if (x == null) continue;
            String v = x.trim();
            if (v.isEmpty()) continue;
            out.add(v);
            if (out.size() >= Math.min(5, DEFAULT_MAX_ITEMS_IN_PROMPT)) {
                break;
            }
        }
        return new ArrayList<String>(out);
    }

    private static String fieldStringCompat(JSONObject obj, String name) {
        JSONObject child = childObject(obj, name);
        if (child == null || child.length() == 0) {
            return "";
        }

        String v = child.optString("value", "");
        if (!v.trim().isEmpty()) {
            return v.trim();
        }

        v = child.optString("item", "");
        return v == null ? "" : v.trim();
    }

    private static JSONObject buildAccessConstraints(JSONObject entryRelation,
                                                     JSONObject depRel,
                                                     JSONObject testEntryContext,
                                                     JSONObject entryGenPlan,
                                                     PromptEvidence e) {
        JSONObject out = new JSONObject();

        boolean useReflection = firstBoolean(false,
                fieldBoolean(entryRelation, "useReflectionFallback", null),
                fieldBoolean(depRel, "useReflectionFallback", null));

        out.put("reflectionAllowed", useReflection);

        String mutationMethod = firstNonBlank(
                e.entry.optString("mutationMethod", ""),
                fieldString(entryRelation, "mutationMethod"),
                fieldString(depRel, "mutationMethod")
        );

        String entryMethod = firstNonBlank(
                e.entry.optString("entryMethod", ""),
                fieldString(entryRelation, "testEntryMethod"),
                fieldString(depRel, "testEntryMethod")
        );

        boolean sameEntry = e.entry.optBoolean("sameEntryAndMutation", false);
        boolean mutationLooksPrivate = mutationMethod.toLowerCase(Locale.ROOT).contains("private ");
        boolean entryLooksPrivate = entryMethod.toLowerCase(Locale.ROOT).contains("private ");

        out.put("directPrivateCallAllowed", useReflection || (!mutationLooksPrivate && !entryLooksPrivate));
        out.put("mustUseEntryMethod", !sameEntry || mutationLooksPrivate || entryLooksPrivate);

        List<String> forbiddenCalls = new ArrayList<String>();

        List<String> callChain = jsonArrayToStringList(e.entry.optJSONArray("callChain"));
        for (String c : callChain) {
            String x = c == null ? "" : c.trim();
            if (x.toLowerCase(Locale.ROOT).contains("private ")) {
                forbiddenCalls.add(x);
            }
        }

        JSONObject internal = itemObject(childObject(testEntryContext, "internalCalls"));
        JSONArray calls = itemsArray(internal);
        for (int i = 0; i < calls.length(); i++) {
            Object v = calls.opt(i);
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                String text = o.optString("text", "").trim();
                if (looksInternalForbiddenCall(text)) {
                    forbiddenCalls.add(text);
                }
            }
        }

        if (!forbiddenCalls.isEmpty()) {
            out.put("forbiddenDirectCalls", new JSONArray(
                    limitList(uniqueNonBlank(forbiddenCalls), DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        List<String> forbiddenConstructions = new ArrayList<String>();
        JSONObject recv = e.invocation.optJSONObject("receiver");
        if (recv != null) {
            String strategy = recv.optString("strategy", "");
            if ("TEST_STUB_SUBCLASS".equals(strategy) || "ANONYMOUS_SUBCLASS".equals(strategy)) {
                forbiddenConstructions.add("Do not instantiate the abstract owner directly; use the provided test stub/subclass template.");
            }
            if ("STATIC_FACTORY_BUILDER".equals(strategy)) {
                forbiddenConstructions.add("Do not call new TargetClass() or new Builder(); use the provided factory/builder setup chain.");
            }
        }

        if (!forbiddenConstructions.isEmpty()) {
            out.put("forbiddenConstructions", new JSONArray(
                    limitList(forbiddenConstructions, DEFAULT_MAX_ITEMS_IN_PROMPT)
            ));
        }

        return out;
    }

    private static boolean looksInternalForbiddenCall(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String t = text.trim();
        return t.contains("doPredicate(")
                || t.contains("doPredicateIndex(")
                || t.contains("isCollectionElement(")
                || t.contains("getNodeIterator(");
    }

    private static JSONObject firstNonEmptyObject(JSONObject... objects) {
        if (objects == null) {
            return new JSONObject();
        }
        for (JSONObject o : objects) {
            if (o != null && o.length() > 0) {
                return o;
            }
        }
        return new JSONObject();
    }

    private static void mergeInfectionIntoInputPlan(JSONObject infection, JSONObject inputPlan) {
        if (infection == null || infection.length() == 0 || inputPlan == null) {
            return;
        }
        putIfNotEmpty(inputPlan, "infectionSource", infection.optString("source", ""));
        putIfNotEmpty(inputPlan, "infectionInputHint", infection.optString("inputHint", ""));
        putIfNotEmpty(inputPlan, "infectionPreferredObservable", infection.optString("preferredObservable", ""));
        putIfNotEmpty(inputPlan, "infectionClassification", infection.optString("classification", ""));
        putIfNotEmpty(inputPlan, "recommendedTestShape", infection.optString("recommendedTestShape", ""));
        putIfNotEmpty(inputPlan, "equivalenceRisk", infection.optString("equivalenceRisk", ""));
        putIfNotEmpty(inputPlan, "infectionReason", infection.optString("reason", ""));
        mergeStringArray(inputPlan, "requiredPathPredicates", infection.optJSONArray("requiredPathPredicates"));
    }

    private static void mergeInfectionIntoMutationKillPlan(JSONObject infection, JSONObject mutationKillPlan) {
        if (infection == null || infection.length() == 0 || mutationKillPlan == null) {
            return;
        }
        putIfNotEmpty(mutationKillPlan, "infectionSource", infection.optString("source", ""));
        putIfNotEmpty(mutationKillPlan, "infectionInputHint", infection.optString("inputHint", ""));
        putIfNotEmpty(mutationKillPlan, "preferredObservable", infection.optString("preferredObservable", ""));
        putIfNotEmpty(mutationKillPlan, "infectionReason", infection.optString("reason", ""));
        mergeStringArray(mutationKillPlan, "requiredPathPredicates", infection.optJSONArray("requiredPathPredicates"));
    }

    private static JSONObject buildInfectionPrompt(JSONObject infection,
                                                   JSONObject inputPlan,
                                                   JSONObject mutationKillPlan) {
        JSONObject out = new JSONObject();
        if (infection != null) {
            putIfNotEmpty(out, "source", infection.optString("source", ""));
            putIfNotEmpty(out, "inputHint", infection.optString("inputHint", ""));
            putIfNotEmpty(out, "preferredObservable", infection.optString("preferredObservable", ""));
            putIfNotEmpty(out, "classification", infection.optString("classification", ""));
            putIfNotEmpty(out, "readerContract", infection.optString("readerContract", ""));
            putIfNotEmpty(out, "recommendedTestShape", infection.optString("recommendedTestShape", ""));
            putIfNotEmpty(out, "equivalenceRisk", infection.optString("equivalenceRisk", ""));
            putIfNotEmpty(out, "reason", infection.optString("reason", ""));
            mergeStringArray(out, "requiredPathPredicates", infection.optJSONArray("requiredPathPredicates"));
        }
        mergeStringArray(out, "requiredPathPredicates", inputPlan == null ? null : inputPlan.optJSONArray("requiredPathPredicates"));
        mergeStringArray(out, "distinguishingConstraints", mutationKillPlan == null ? null : mutationKillPlan.optJSONArray("distinguishingConstraints"));
        return out;
    }

    private static void mergeStringArray(JSONObject target, String key, JSONArray incoming) {
        if (target == null || key == null || incoming == null || incoming.length() == 0) {
            return;
        }
        List<String> merged = new ArrayList<String>();
        merged.addAll(jsonArrayToStringList(target.optJSONArray(key)));
        merged.addAll(jsonArrayToStringList(incoming));
        List<String> unique = uniqueNonBlank(merged);
        if (!unique.isEmpty()) {
            target.put(key, new JSONArray(limitList(unique, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
    }

    private static String inferPlanStatus(JSONObject plan, PromptEvidence e) {
        JSONArray setup = plan.optJSONArray("requiredSetup");
        String call = plan.optString("entryCall", "");
        JSONArray support = plan.optJSONArray("supportClasses");

        boolean hasSetup = setup != null && setup.length() > 0;
        boolean hasCall = call != null && !call.trim().isEmpty();
        boolean hasStub = support != null && support.length() > 0;

        JSONObject receiver = e.invocation.optJSONObject("receiver");
        String strategy = receiver == null ? "" : receiver.optString("strategy", "");

        if (hasCall && (hasSetup || hasStub || "STATIC_NO_RECEIVER".equals(strategy))) {
            return "READY";
        }
        if (hasCall) {
            return "PARTIAL";
        }
        return "PARTIAL";
    }

    private static List<String> uniqueNonBlank(List<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.trim().isEmpty()) {
                    set.add(v.trim());
                }
            }
        }
        return new ArrayList<String>(set);
    }

    private static List<String> cleanJavaSnippets(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String v : values) {
                String s = sanitizeJavaSnippet(v);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static void recoverConstructorStateObservable(JSONObject root, PromptEvidence e) {
        if (e == null || !e.skipTestGeneration) {
            return;
        }
        if (!isConstructorNoObservableReason(e.skipReason) || !isConstructorEntry(e.entry)) {
            return;
        }

        JSONObject dep = itemObject(root.optJSONObject("DependencyContext"));
        JSONObject testEntryContext = itemObject(childObject(dep, "testEntryContext"));
        JSONObject entryRip = itemObject(root.optJSONObject("EntryLiftedRIP"));
        JSONObject entryGenPlan = itemObject(childObject(entryRip, "entryGenerationPlan"));
        JSONObject publicApi = firstNonEmptyObject(
                itemObject(childObject(entryGenPlan, "publicApi")),
                itemObject(childObject(testEntryContext, "publicApi"))
        );
        JSONObject observable = firstNonEmptyObject(
                itemObject(root.optJSONObject("observablePlan")),
                itemObject(childObject(entryGenPlan, "observablePlan")),
                itemObject(childObject(publicApi, "observablePlan"))
        );

        if (hasRecoverableObservable(observable)) {
            copyObject(e.observablePlan, observable);
            clearSkipForRecoveredObservable(e, "Recovered constructor observable plan from existing output.json evidence.");
            return;
        }

        JSONObject inferred = inferConstructorObservableFromPublicApi(publicApi);
        if (inferred.length() > 0) {
            copyObject(e.observablePlan, inferred);
            clearSkipForRecoveredObservable(e, "Recovered constructor observable plan from public API evidence.");
        }
    }

    private static boolean isConstructorNoObservableReason(String reason) {
        String normalized = reason == null ? "" : reason.toUpperCase(Locale.ROOT);
        return normalized.contains("NO_PUBLIC_OBSERVABLE_FOR_CONSTRUCTOR_STATE_MUTATION");
    }

    private static boolean isConstructorEntry(JSONObject entry) {
        String invocationKind = firstNonBlank(
                fieldString(entry, "invocationKind"),
                fieldString(entry, "entryInvocationKind")
        );
        return "CONSTRUCTOR_INVOCATION".equals(invocationKind);
    }

    private static boolean hasRecoverableObservable(JSONObject observable) {
        if (observable == null || observable.length() == 0) {
            return false;
        }
        String kind = fieldString(observable, "kind");
        if ("NO_PUBLIC_OBSERVABLE".equals(kind)) {
            return false;
        }
        return !fieldString(observable, "observableCall").isEmpty()
                || !fieldString(observable, "call").isEmpty();
    }

    private static JSONObject inferConstructorObservableFromPublicApi(JSONObject publicApi) {
        JSONObject recovered = new JSONObject();
        if (publicApi == null || publicApi.length() == 0) {
            return recovered;
        }
        List<String> availablePublicMethods = fieldItems(publicApi, "availablePublicMethods");
        String observableMethod = chooseConstructorObservableMethod(availablePublicMethods);
        if (observableMethod.isEmpty()) {
            return recovered;
        }
        String methodName = extractMethodNameFromSignature(observableMethod);
        String returnType = extractReturnTypeFromSignature(observableMethod);
        if (methodName.isEmpty()) {
            return recovered;
        }
        recovered.put("kind", "RECOVERED_CONSTRUCTOR_PUBLIC_OBSERVER");
        recovered.put("observableCall", sanitizeJavaSnippet(returnType + " result = subject." + methodName + "();"));
        recovered.put("reason", "Recovered constructor-state observable from available public API evidence.");
        List<String> antiPatterns = new ArrayList<String>();
        antiPatterns.add("Do not invent new observers; use one of the availablePublicMethods listed by static analysis.");
        recovered.put("antiPatterns", new JSONArray(limitList(antiPatterns, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        return recovered;
    }

    private static String chooseConstructorObservableMethod(List<String> availablePublicMethods) {
        if (availablePublicMethods == null || availablePublicMethods.isEmpty()) {
            return "";
        }
        for (String method : availablePublicMethods) {
            if (method.contains(" getMessage(")) {
                return method;
            }
        }
        for (String method : availablePublicMethods) {
            String name = extractMethodNameFromSignature(method);
            if (name.startsWith("get") || name.startsWith("is") || "toString".equals(name)) {
                return method;
            }
        }
        return "";
    }

    private static String extractMethodNameFromSignature(String signature) {
        if (signature == null) {
            return "";
        }
        int lp = signature.indexOf('(');
        if (lp <= 0) {
            return "";
        }
        String head = signature.substring(0, lp).trim();
        int space = head.lastIndexOf(' ');
        if (space < 0 || space == head.length() - 1) {
            return "";
        }
        return head.substring(space + 1).trim();
    }

    private static String extractReturnTypeFromSignature(String signature) {
        if (signature == null) {
            return "Object";
        }
        int lp = signature.indexOf('(');
        String head = lp < 0 ? signature.trim() : signature.substring(0, lp).trim();
        int space = head.lastIndexOf(' ');
        if (space <= 0) {
            return "Object";
        }
        return head.substring(0, space).trim();
    }

    private static void clearSkipForRecoveredObservable(PromptEvidence e, String reason) {
        e.skipTestGeneration = false;
        e.skipReason = "";
        e.entry.put("skipTestGeneration", false);
        e.entry.put("skipReason", "");
        if (e.observablePlan.length() > 0 && fieldString(e.observablePlan, "reason").isEmpty()) {
            e.observablePlan.put("reason", reason);
        }
        mergeRecoveredObservableIntoExecutablePlan(e);
    }

    private static void mergeRecoveredObservableIntoExecutablePlan(PromptEvidence e) {
        if (e == null || e.observablePlan.length() == 0) {
            return;
        }
        JSONObject observable = new JSONObject();
        putIfNotEmpty(observable, "kind", fieldString(e.observablePlan, "kind"));
        putIfNotEmpty(observable, "setup", sanitizeJavaSnippet(fieldString(e.observablePlan, "setup")));
        putIfNotEmpty(observable, "observableCall", sanitizeJavaSnippet(fieldString(e.observablePlan, "observableCall")));
        putIfNotEmpty(observable, "expectedOriginal", fieldString(e.observablePlan, "expectedOriginal"));
        putIfNotEmpty(observable, "reason", fieldString(e.observablePlan, "reason"));
        if (observable.length() > 0) {
            e.executableTestPlan.put("observable", observable);
        }
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        out.put("mutation", mutation);
        out.put("entry", entry);

        if (executableTestPlan.length() > 0) {
            out.put("executableTestPlan", executableTestPlan);
        }

        out.put("invocation", invocation);
        out.put("assertions", assertions);

        if (publicApiEvidence.length() > 0) {
            out.put("publicApiEvidence", publicApiEvidence);
        }
        if (primaryChain.length() > 0) {
            out.put("primaryChain", primaryChain);
        }
        out.put("alternativeChains", alternativeChains);
        if (compilationFacts.length() > 0) {
            out.put("compilationFacts", compilationFacts);
        }
        if (entrySelection.length() > 0) {
            out.put("entrySelection", entrySelection);
        }
        if (selectedEntry.length() > 0) {
            out.put("selectedEntry", selectedEntry);
        }
        if (canonicalCore.length() > 0) {
            out.put("canonicalCore", canonicalCore);
        }
        if (observablePlan.length() > 0) {
            out.put("observablePlan", observablePlan);
        }
        if (mutationKillPlan.length() > 0) {
            out.put("mutationKillPlan", mutationKillPlan);
        }
        if (inputDistinguishPlan.length() > 0) {
            out.put("inputDistinguishPlan", inputDistinguishPlan);
        }
        if (propagationPlan.length() > 0) {
            out.put("propagationPlan", propagationPlan);
        }
        if (assertionPlan.length() > 0) {
            out.put("assertionPlan", assertionPlan);
        }
        if (ripExecutionPlan.length() > 0) {
            out.put("ripExecutionPlan", ripExecutionPlan);
        }
        if (observableSelectionPlan.length() > 0) {
            out.put("observableSelectionPlan", observableSelectionPlan);
        }
        if (entryParameterControlPlan.length() > 0) {
            out.put("entryParameterControlPlan", entryParameterControlPlan);
        }
        if (killabilityPlan.length() > 0) {
            out.put("killabilityPlan", killabilityPlan);
        }
        if (liftedReachabilityPlan.length() > 0) {
            out.put("liftedReachabilityPlan", liftedReachabilityPlan);
        }
        if (indirectEntryTestPlan.length() > 0) {
            out.put("indirectEntryTestPlan", indirectEntryTestPlan);
        }
        if (reachabilityGuardsPlan.length() > 0) {
            out.put("reachabilityGuardsPlan", reachabilityGuardsPlan);
        }
        if (distinguishingInputGuidance.length() > 0) {
            out.put("distinguishingInputGuidance", distinguishingInputGuidance);
        }
        if (observableDifferencePlan.length() > 0) {
            out.put("observableDifferencePlan", observableDifferencePlan);
        }
        if (entryChainPlan.length() > 0) {
            out.put("entryChainPlan", entryChainPlan);
        }
        if (symbolicRipPlan.length() > 0) {
            out.put("symbolicRipPlan", symbolicRipPlan);
        }
        if (symbolicFeasibility.length() > 0) {
            out.put("symbolicFeasibility", symbolicFeasibility);
        }
        if (evidenceQuality.length() > 0) {
            out.put("evidenceQuality", evidenceQuality);
        }
        if (codeKbContext.length() > 0) {
            out.put("codeKbContext", codeKbContext);
        }
        if (apiRoleFacts.length() > 0) {
            out.put("apiRoleFacts", apiRoleFacts);
        }

        out.put("mutationEvidence", mutationEvidence);

        if (mutationGraphEvidence.length() > 0) {
            out.put("mutationGraphEvidence", mutationGraphEvidence);
        }
        if (needEntryLiftedEvidence && entryEvidence.length() > 0) {
            out.put("entryEvidence", entryEvidence);
        }
        if (needEntryLiftedEvidence && entryGraphEvidence.length() > 0) {
            out.put("entryGraphEvidence", entryGraphEvidence);
        }

        return out;
    }

    public String observablePlanKind() {
        String kind = fieldString(observablePlan, "kind");
        if (!kind.trim().isEmpty()) {
            return kind;
        }
        return fieldString(propagationPlan, "observableSinkKind");
    }

    public String killabilityCategory() {
        return fieldString(killabilityPlan, "killabilityCategory");
    }

    public String preferredAssertionMode() {
        return firstNonBlank(
                fieldString(canonicalCore.optJSONObject("oracle"), "assertionMode"),
                fieldString(ripExecutionPlan, "preferredAssertionMode"),
                fieldString(primaryChain, "assertionKind"),
                fieldString(mutationKillPlan, "preferredAssertionMode"),
                fieldString(observableDifferencePlan, "requiredAssertionMode"));
    }

    public String mutationSemanticKind() {
        return firstNonBlank(
                fieldString(canonicalCore.optJSONObject("infection"), "semanticKind"),
                fieldString(ripExecutionPlan, "semanticKind"),
                fieldString(primaryChain, "semanticKind"),
                fieldString(mutationKillPlan, "mutationSemanticKind"));
    }

    public boolean isReceiverStateDependent() {
        return "RECEIVER_STATE_DEPENDENT".equalsIgnoreCase(killabilityCategory())
                || "RECEIVER_STATE_DEPENDENT".equalsIgnoreCase(fieldString(entryParameterControlPlan, "overall"));
    }

    public boolean hasIndirectEntryTestPlan() {
        return indirectEntryTestPlan != null
                && indirectEntryTestPlan.optBoolean("enabled", false);
    }

    public String indirectEntryControlType() {
        return fieldString(indirectEntryTestPlan, "entryControlType");
    }

    public boolean requiresStateShaping() {
        return indirectEntryTestPlan != null
                && indirectEntryTestPlan.optBoolean("requiresStateShaping", false);
    }

    public boolean requiresInvocationSequence() {
        return indirectEntryTestPlan != null
                && indirectEntryTestPlan.optBoolean("requiresInvocationSequence", false);
    }

    public List<String> stateShapingTargets(int maxItems) {
        return limitList(fieldItems(indirectEntryTestPlan, "stateShapingTargets"), Math.max(1, maxItems));
    }

    public List<String> observablePriority(int maxItems) {
        return limitList(fieldItems(indirectEntryTestPlan, "observablePriority"), Math.max(1, maxItems));
    }

    public String preferredObservableCall() {
        return firstNonBlank(
                canonicalObservableCall(),
                primaryChainOracleCall(),
                fieldString(observableDifferencePlan, "preferredObservableCall"),
                observableSelectionPlan != null && observableSelectionPlan.optBoolean("overrideRecommended", false)
                        ? fieldString(observableSelectionPlan, "preferredObservableCall")
                        : "",
                fieldString(observablePlan, "observableCall"));
    }

    private String canonicalObservableCall() {
        JSONObject observable = canonicalCore.optJSONObject("observable");
        return sanitizeJavaSnippet(firstNonBlank(
                fieldString(observable, "call"),
                fieldString(observable, "observableCall"),
                fieldString(observable, "expression")));
    }

    public String primaryChainOracleCall() {
        return sanitizeJavaSnippet(firstNonBlank(
                primaryChainPropagationObservableCall(),
                fieldString(primaryChainOracle(), "observableCall"),
                fieldString(primaryChainOracle(), "call"),
                fieldString(primaryChainOracle(), "expression")));
    }

    public String primaryChainExpectedOriginal() {
        return firstNonBlank(
                fieldString(canonicalCore.optJSONObject("oracle"), "expectedOriginal"),
                fieldString(primaryChainOracle(), "expectedOriginal"));
    }

    public String primaryChainExpectedMutant() {
        return firstNonBlank(
                fieldString(canonicalCore.optJSONObject("oracle"), "expectedMutantExplanation"),
                fieldString(canonicalCore.optJSONObject("oracle"), "expectedMutant"),
                fieldString(primaryChainOracle(), "expectedMutant"));
    }

    public boolean hasExpectedOriginalOracle() {
        return !primaryChainExpectedOriginal().trim().isEmpty()
                || !fieldString(observablePlan, "expectedOriginal").trim().isEmpty();
    }

    public boolean hasLockedPrimaryChainObservable() {
        return !firstNonBlank(canonicalObservableCall(), primaryChainOracleCall()).trim().isEmpty();
    }

    public boolean isForcedBranchMutation() {
        String kind = mutationSemanticKind();
        return "BRANCH_FORCED_TRUE".equalsIgnoreCase(kind)
                || "BRANCH_FORCED_FALSE".equalsIgnoreCase(kind);
    }

    public boolean hasCanonicalObservable() {
        JSONObject observable = canonicalCore.optJSONObject("observable");
        return observable != null && !firstNonBlank(
                fieldString(observable, "call"),
                fieldString(observable, "observableCall"),
                fieldString(observable, "expression")).trim().isEmpty();
    }

    public String canonicalObservableKind() {
        JSONObject observable = canonicalCore.optJSONObject("observable");
        return firstNonBlank(
                fieldString(observable, "kind"),
                fieldString(observablePlan, "kind"));
    }

    public boolean hasMandatoryInfectionConstraints() {
        JSONObject infection = canonicalCore.optJSONObject("infection");
        JSONArray constraints = infection == null ? null : infection.optJSONArray("mandatoryConstraints");
        JSONArray cofactors = infection == null ? null : infection.optJSONArray("mandatoryCofactors");
        return (constraints != null && constraints.length() > 0)
                || (cofactors != null && cofactors.length() > 0);
    }

    public boolean hasRequiredNonNullSubjects() {
        JSONObject construction = canonicalCore.optJSONObject("construction");
        JSONArray subjects = construction == null ? null : construction.optJSONArray("requiredNonNullSubjects");
        JSONArray bindings = construction == null ? null : construction.optJSONArray("mandatoryBindings");
        return (subjects != null && subjects.length() > 0)
                || (bindings != null && bindings.length() > 0);
    }

    public boolean hasExpectedOriginalExecutable() {
        JSONObject oracle = canonicalCore.optJSONObject("oracle");
        if (oracle != null && oracle.has("expectedOriginalExecutable")) {
            return oracle.optBoolean("expectedOriginalExecutable", false);
        }
        return observablePlan.optBoolean("expectedOriginalExecutable", false);
    }

    public double canonicalEntryControllabilityCoverage() {
        return canonicalEntryControllabilityNumber("coverage");
    }

    public double canonicalEntryIndependentCoverage() {
        return canonicalEntryControllabilityNumber("independentCoverage");
    }

    public String canonicalEntryConstraintStatus() {
        JSONObject controllability = canonicalEntryControllability();
        return firstNonBlank(
                fieldString(controllability, "constraintStatus"),
                fieldString(entrySelection.optJSONObject("selected"), "constraintStatus"));
    }

    public String canonicalObservableDirectness() {
        JSONObject observable = canonicalCore.optJSONObject("observable");
        return fieldString(observable, "directness");
    }

    public int canonicalMandatoryGuardCount() {
        JSONObject reachability = canonicalCore.optJSONObject("reachability");
        JSONArray guards = reachability == null ? null : reachability.optJSONArray("mandatoryGuards");
        return guards == null ? 0 : guards.length();
    }

    public int canonicalMandatoryCofactorCount() {
        JSONObject infection = canonicalCore.optJSONObject("infection");
        JSONArray cofactors = infection == null ? null : infection.optJSONArray("mandatoryCofactors");
        return cofactors == null ? 0 : cofactors.length();
    }

    public boolean canonicalHasFalsePolarityGuard() {
        JSONObject reachability = canonicalCore.optJSONObject("reachability");
        JSONArray guards = reachability == null ? null : reachability.optJSONArray("mandatoryGuards");
        if (guards == null) {
            return false;
        }
        for (int i = 0; i < guards.length(); i++) {
            JSONObject guard = guards.optJSONObject(i);
            if (guard != null && guard.has("requiredEvaluation") && !guard.optBoolean("requiredEvaluation", true)) {
                return true;
            }
        }
        return false;
    }

    private double canonicalEntryControllabilityNumber(String key) {
        JSONObject controllability = canonicalEntryControllability();
        if (controllability == null) {
            return 0.0d;
        }
        Object value = controllability.opt(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private JSONObject canonicalEntryControllability() {
        JSONObject entryCore = canonicalCore.optJSONObject("entry");
        JSONObject controllability = entryCore == null ? null : entryCore.optJSONObject("controllability");
        return controllability == null ? new JSONObject() : controllability;
    }

    public String receiverStrategy() {
        JSONObject receiver = invocation == null ? null : invocation.optJSONObject("receiver");
        return receiver == null ? "" : fieldString(receiver, "strategy");
    }

    public boolean receiverOwnerInstantiable() {
        JSONObject receiver = invocation == null ? null : invocation.optJSONObject("receiver");
        return receiver != null && receiver.optBoolean("ownerInstantiable", false);
    }

    public String receiverBuilderClass() {
        JSONObject receiver = invocation == null ? null : invocation.optJSONObject("receiver");
        return receiver == null ? "" : fieldString(receiver, "builderClass");
    }

    public String receiverFactoryMethod() {
        JSONObject receiver = invocation == null ? null : invocation.optJSONObject("receiver");
        return receiver == null ? "" : fieldString(receiver, "factoryMethod");
    }

    public List<String> availablePublicMethods() {
        return fieldItems(publicApiEvidence, "availablePublicMethods");
    }

    public boolean hasObservableOverride() {
        return observableSelectionPlan != null
                && observableSelectionPlan.optBoolean("overrideRecommended", false)
                && !preferredObservableCall().trim().isEmpty();
    }

    public boolean hasWeakAssertionSignal() {
        if (assertions == null || assertions.length() == 0) {
            return assertionPlan.length() == 0;
        }
        String text = assertions.toString().toLowerCase(java.util.Locale.ROOT);
        boolean legacyWeak = text.contains("assertnotnull")
                || text.contains("asserttrue")
                || text.contains("sanity")
                || text.contains("optionalsanitychecks");
        if (!legacyWeak) {
            return false;
        }
        String planText = assertionPlan.toString().toLowerCase(java.util.Locale.ROOT);
        return !(planText.contains("primaryassertions")
                && (planText.contains("priority") || planText.contains("expression")));
    }

    public boolean hasReachabilityGuards() {
        return reachabilityGuardsPlan != null && reachabilityGuardsPlan.optBoolean("enabled", false);
    }

    public boolean hasFrontLoadedExceptionRisk() {
        return reachabilityGuardsPlan != null
                && reachabilityGuardsPlan.optBoolean("frontLoadedExceptionRisk", false);
    }

    public boolean hasBoundarySensitiveInputGuidance() {
        return distinguishingInputGuidance != null
                && (distinguishingInputGuidance.optJSONArray("distinguishingConstraints") != null
                || distinguishingInputGuidance.optJSONArray("boundaryValueFamilies") != null
                || distinguishingInputGuidance.optJSONArray("preferredConcreteInputs") != null);
    }

    public boolean requiresRealEntryChain() {
        return entryChainPlan != null && entryChainPlan.optBoolean("requiresRealEntryChain", false);
    }

    public String observableDifferenceKind() {
        return fieldString(observableDifferencePlan, "preferredObservableKind");
    }

    public boolean hasSymbolicRipPlan() {
        return symbolicRipPlan != null && symbolicRipPlan.optBoolean("enabled", false);
    }

    public int symbolicPathConstraintCount() {
        JSONArray arr = symbolicRipPlan == null ? null : symbolicRipPlan.optJSONArray("pathConstraints");
        return arr == null ? 0 : arr.length();
    }

    public boolean symbolicSuggestsDeeperPath() {
        return hasSymbolicRipPlan() && hasFrontLoadedExceptionRisk();
    }

    public boolean hasPromptEntrySignal() {
        return entry != null && entry.length() > 0
                && (!fieldString(entry, "entryMethodSignature").isEmpty()
                || !fieldString(entry, "entryMethodName").isEmpty()
                || !fieldString(entry, "kind").isEmpty());
    }

    public boolean hasPromptImportSignal() {
        if (invocation != null) {
            JSONArray imports = invocation.optJSONArray("imports");
            if (imports != null && imports.length() > 0) {
                return true;
            }
        }
        JSONObject receiver = invocation == null ? null : invocation.optJSONObject("receiver");
        if (receiver != null) {
            JSONArray imports = receiver.optJSONArray("requiredImports");
            if (imports != null && imports.length() > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPromptObservableSignal() {
        return (observablePlan != null && observablePlan.length() > 0)
                || (observableDifferencePlan != null && observableDifferencePlan.length() > 0);
    }

    public boolean hasPromptAssertionSignal() {
        return (assertionPlan != null && assertionPlan.length() > 0)
                || (assertions != null && assertions.length() > 0)
                || (observableDifferencePlan != null && observableDifferencePlan.optBoolean("avoidWeakChecks", false));
    }

    public boolean hasPromptReachabilitySignal() {
        return (liftedReachabilityPlan != null && liftedReachabilityPlan.length() > 0)
                || (killabilityPlan != null && killabilityPlan.length() > 0)
                || (reachabilityGuardsPlan != null && reachabilityGuardsPlan.length() > 0)
                || (entryChainPlan != null && entryChainPlan.length() > 0)
                || (symbolicRipPlan != null && symbolicRipPlan.length() > 0);
    }

    public boolean hasEquivalenceSuspicion() {
        return evidenceQuality != null && evidenceQuality.optBoolean("equivalenceSuspicion", false);
    }

    public String equivalenceSuspicionReason() {
        return fieldString(evidenceQuality, "equivalenceReason");
    }

    public boolean hasCodeKbContext() {
        return codeKbContext != null && codeKbContext.optBoolean("enabled", false);
    }

    public String codeKbTopEntrySignature() {
        if (!hasCodeKbContext()) {
            return "";
        }
        JSONArray entries = codeKbContext.optJSONArray("candidateEntries");
        if (entries == null || entries.length() == 0) {
            return "";
        }
        JSONObject first = entries.optJSONObject(0);
        return first == null ? "" : first.optString("signature", "");
    }

    public String codeKbTopEntryReason() {
        if (!hasCodeKbContext()) {
            return "";
        }
        JSONArray entries = codeKbContext.optJSONArray("candidateEntries");
        if (entries == null || entries.length() == 0) {
            return "";
        }
        JSONObject first = entries.optJSONObject(0);
        return first == null ? "" : first.optString("reason", "");
    }

    public int codeKbEntryCount() {
        if (!hasCodeKbContext()) {
            return 0;
        }
        JSONArray entries = codeKbContext.optJSONArray("candidateEntries");
        return entries == null ? 0 : entries.length();
    }

    public int codeKbMethodCallCount() {
        if (!hasCodeKbContext()) {
            return 0;
        }
        JSONArray calls = codeKbContext.optJSONArray("mutantMethodCalls");
        return calls == null ? 0 : calls.length();
    }

    public int codeKbFieldAccessCount() {
        if (!hasCodeKbContext()) {
            return 0;
        }
        JSONArray accesses = codeKbContext.optJSONArray("mutantFieldAccesses");
        return accesses == null ? 0 : accesses.length();
    }

    public int codeKbObservableCount() {
        if (!hasCodeKbContext()) {
            return 0;
        }
        JSONArray observables = codeKbContext.optJSONArray("observableCandidates");
        return observables == null ? 0 : observables.length();
    }

    public int codeKbWitnessCount() {
        if (!hasCodeKbContext()) {
            return 0;
        }
        JSONArray witnesses = codeKbContext.optJSONArray("mutantWitnessCandidates");
        return witnesses == null ? 0 : witnesses.length();
    }

    public String codeKbTopObservableExpression() {
        if (!hasCodeKbContext()) {
            return "";
        }
        JSONArray observables = codeKbContext.optJSONArray("observableCandidates");
        if (observables == null || observables.length() == 0) {
            return "";
        }
        JSONObject first = observables.optJSONObject(0);
        return first == null ? "" : first.optString("expression", "");
    }

    public String codeKbTopObservableReason() {
        if (!hasCodeKbContext()) {
            return "";
        }
        JSONArray observables = codeKbContext.optJSONArray("observableCandidates");
        if (observables == null || observables.length() == 0) {
            return "";
        }
        JSONObject first = observables.optJSONObject(0);
        return first == null ? "" : first.optString("reason", "");
    }

    public boolean prefersExceptionAssertion() {
        String mode = preferredAssertionMode();
        return "EXCEPTION_ASSERTION".equalsIgnoreCase(mode)
                || observablePlanKind().toUpperCase(java.util.Locale.ROOT).contains("EXCEPTION");
    }

    public boolean prefersOutputAssertion() {
        String mode = preferredAssertionMode();
        String kind = observablePlanKind().toUpperCase(java.util.Locale.ROOT);
        return "OUTPUT_ASSERTION".equalsIgnoreCase(mode)
                || kind.contains("STDOUT")
                || kind.contains("STDERR")
                || kind.contains("CONSOLE_OUTPUT")
                || kind.contains("EXTERNAL_OUTPUT");
    }

    public boolean prefersReturnValueAssertion() {
        String mode = preferredAssertionMode();
        String observableKind = observablePlanKind().toUpperCase(java.util.Locale.ROOT);
        String observableCall = preferredObservableCall().toLowerCase(java.util.Locale.ROOT);
        return "RETURN_VALUE_ASSERTION".equalsIgnoreCase(mode)
                || observableKind.contains("RETURN")
                || observableCall.contains("result")
                || observableCall.contains("classify(")
                || observableCall.contains("assert");
    }

    public boolean isPureReturnLikeStaticEntry() {
        String invocationKind = firstNonBlank(
                fieldString(entry, "invocationKind"),
                fieldString(entry, "entryInvocationKind"))
                .toUpperCase(java.util.Locale.ROOT);
        String observableKind = observablePlanKind().toUpperCase(java.util.Locale.ROOT);
        return invocationKind.contains("STATIC_METHOD_INVOCATION")
                && !observableKind.contains("EXCEPTION")
                && prefersReturnValueAssertion();
    }

    public boolean hasHeaderSensitiveInputConstraint() {
        String text = inputDistinguishPlan.toString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("withheader(")
                || text.contains("format.getheader()")
                || text.contains("duplicate_header_vs_missing_policy")
                || text.contains("non_null_header_setup");
    }

    public boolean discouragesDefaultFormatOnly() {
        String text = inputDistinguishPlan.toString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("do not use plain csvformat.default")
                || text.contains("plain csvformat.default");
    }

    public boolean mentionsDuplicateHeaderConstraint() {
        String text = inputDistinguishPlan.toString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("duplicate")
                && text.contains("header");
    }

    public boolean mentionsAllowMissingColumnNamesConstraint() {
        String text = inputDistinguishPlan.toString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("allowmissingcolumnnames");
    }

    public boolean prefersConstructorExceptionSplit() {
        String text = mutationKillPlan.toString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("constructor completes or throws illegalargumentexception")
                || text.contains("constructor completes vs illegalargumentexception")
                || text.contains("construction completes or throws illegalargumentexception")
                || text.contains("thrown-vs-not-thrown")
                || text.contains("throws illegalargumentexception");
    }

    public List<String> codeKbTopFieldAccesses(int limit) {
        return extractCodeKbStrings("mutantFieldAccesses", "field", limit);
    }

    public List<String> codeKbTopMethodCalls(int limit) {
        return extractCodeKbStrings("mutantMethodCalls", "callee", limit);
    }

    public List<String> codeKbTopWitnesses(int limit) {
        if (!hasCodeKbContext()) {
            return Collections.emptyList();
        }
        JSONArray arr = codeKbContext.optJSONArray("mutantWitnessCandidates");
        if (arr == null || arr.length() == 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        int max = Math.max(1, limit);
        for (int i = 0; i < arr.length() && values.size() < max; i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String value = firstNonBlank(
                    item.optString("assertionSketch", ""),
                    item.optString("reason", ""),
                    item.optString("entryMethodSignature", "")
            );
            if (!value.trim().isEmpty()) {
                values.add(value.trim());
            }
        }
        return new ArrayList<String>(values);
    }

    public JSONObject codeKbCompilableApiFacts() {
        JSONObject out = new JSONObject();
        JSONObject facts = codeKbContext.optJSONObject("compilableApiFacts");
        if (facts == null || facts.length() == 0) {
            return out;
        }
        putIfPresent(out, "packageName", facts.optString("packageName", ""));
        putIfPresent(out, "ownerSimpleName", facts.optString("ownerSimpleName", ""));
        putIfPresent(out, "ownerKind", facts.optString("ownerKind", ""));
        putIfPresent(out, "ownerVisibility", facts.optString("ownerVisibility", ""));
        if (facts.has("ownerInstantiable")) {
            out.put("ownerInstantiable", facts.optBoolean("ownerInstantiable", false));
        }
        copyArrayIfPresent(out, "constructorSignatures", facts.optJSONArray("constructorSignatures"), 4);
        copyArrayIfPresent(out, "constructorExamples", facts.optJSONArray("constructorExamples"), 4);
        copyArrayIfPresent(out, "allowedConstructors", facts.optJSONArray("allowedConstructors"), 8);
        copyArrayIfPresent(out, "allowedStaticFactories", facts.optJSONArray("allowedStaticFactories"), 8);
        copyArrayIfPresent(out, "allowedStaticMethods", facts.optJSONArray("allowedStaticMethods"), 12);
        copyArrayIfPresent(out, "allowedInstanceMethods", facts.optJSONArray("allowedInstanceMethods"), 12);
        copyArrayIfPresent(out, "exactCallableSignatures", facts.optJSONArray("exactCallableSignatures"), 20);
        copyArrayIfPresent(out, "forbiddenCalls", facts.optJSONArray("forbiddenCalls"), 20);
        copyArrayIfPresent(out, "privateFieldNames", facts.optJSONArray("privateFieldNames"), 8);
        copyObjectIfPresent(out, "enumConstants", facts.optJSONObject("enumConstants"));
        return out;
    }

    public JSONObject apiRoleFacts() {
        JSONObject out = new JSONObject();
        copyObject(out, apiRoleFacts);
        return out;
    }

    public boolean hasApiRoleFacts() {
        return apiRoleFacts != null && apiRoleFacts.length() > 0;
    }

    public List<String> codeKbAllowedStaticMethods(int limit) {
        return extractCompilableFactStrings("allowedStaticMethods", limit);
    }

    public List<String> codeKbAllowedConstructors(int limit) {
        return extractCompilableFactStrings("allowedConstructors", limit);
    }

    public List<String> codeKbExactCallableSignatures(int limit) {
        return extractCompilableFactStrings("exactCallableSignatures", limit);
    }

    public List<String> codeKbForbiddenCalls(int limit) {
        return extractCompilableFactStrings("forbiddenCalls", limit);
    }

    public boolean codeKbSuggestsConstructorEntry() {
        String signature = codeKbTopEntrySignature().toLowerCase(java.util.Locale.ROOT);
        String reason = codeKbTopEntryReason().toLowerCase(java.util.Locale.ROOT);
        return signature.startsWith("csvparser(")
                || reason.contains("constructor")
                || observablePlanKind().toUpperCase(java.util.Locale.ROOT).contains("CONSTRUCTOR");
    }

    private List<String> extractCodeKbStrings(String arrayKey, String preferredField, int limit) {
        if (!hasCodeKbContext()) {
            return Collections.emptyList();
        }
        JSONArray arr = codeKbContext.optJSONArray(arrayKey);
        if (arr == null || arr.length() == 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        int max = Math.max(1, limit);
        for (int i = 0; i < arr.length() && values.size() < max; i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String value = firstNonBlank(
                    item.optString(preferredField, ""),
                    item.optString("name", ""),
                    item.optString("signature", ""),
                    item.optString("member", ""),
                    item.optString("target", "")
            );
            if (!value.trim().isEmpty()) {
                values.add(value.trim());
            }
        }
        return new ArrayList<String>(values);
    }

    private List<String> extractCompilableFactStrings(String arrayKey, int limit) {
        JSONObject facts = codeKbCompilableApiFacts();
        JSONArray arr = facts.optJSONArray(arrayKey);
        if (arr == null || arr.length() == 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        int max = Math.max(1, limit);
        for (int i = 0; i < arr.length() && values.size() < max; i++) {
            String value = String.valueOf(arr.opt(i)).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return new ArrayList<String>(values);
    }

    private static void putIfPresent(JSONObject out, String key, String value) {
        if (out == null || key == null || value == null || value.trim().isEmpty()) {
            return;
        }
        out.put(key, value);
    }

    private static void copyArrayIfPresent(JSONObject out, String key, JSONArray arr, int maxItems) {
        if (out == null || key == null || arr == null || arr.length() == 0) {
            return;
        }
        JSONArray limited = new JSONArray();
        for (int i = 0; i < arr.length() && i < Math.max(1, maxItems); i++) {
            limited.put(arr.opt(i));
        }
        out.put(key, limited);
    }

    private static void copyObjectIfPresent(JSONObject out, String key, JSONObject value) {
        if (out == null || key == null || value == null || value.length() == 0) {
            return;
        }
        out.put(key, value);
    }

    private static void copyArray(JSONArray target, JSONArray source) {
        if (target == null || source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            target.put(source.opt(i));
        }
    }

    private static void buildMutation(JSONObject root, JSONObject out) {
        out.put("operator", wrapperString(root.optJSONObject("operator")));
        out.put("diff", trimEvidence(wrapperString(root.optJSONObject("Diff")), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        List<String> changes = wrapperItems(root.optJSONObject("JimpleChanges"));
        out.put("affected", new JSONArray(limitList(changes, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        JSONObject bodies = root.optJSONObject("bodies");
        if (bodies != null && bodies.length() > 0) {
            JSONObject compactBodies = new JSONObject();
            putMutationBody(compactBodies, "original", bodies.optJSONObject("original"));
            putMutationBody(compactBodies, "mutant", bodies.optJSONObject("mutant"));
            if (compactBodies.length() > 0) {
                out.put("bodies", compactBodies);
            }
        }
    }

    private static void putMutationBody(JSONObject out, String key, JSONObject body) {
        if (out == null || body == null || body.length() == 0) {
            return;
        }
        JSONObject compact = new JSONObject();
        putIfNotEmpty(compact, "label", body.optString("label", ""));
        putIfNotEmpty(compact, "signature", body.optString("signature", ""));
        putIfNotEmpty(compact, "content", trimEvidence(body.optString("content", ""), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        if (compact.length() > 0) {
            out.put(key, compact);
        }
    }

    private static void buildKillOrientedEvidence(JSONObject root, PromptEvidence e) {
        copyObject(e.mutationKillPlan, itemObject(root.optJSONObject("MutationKillPlan")));
        copyObject(e.inputDistinguishPlan, itemObject(root.optJSONObject("InputDistinguishPlan")));
        copyObject(e.propagationPlan, itemObject(root.optJSONObject("PropagationPlan")));
        copyObject(e.assertionPlan, itemObject(root.optJSONObject("AssertionPlan")));
        copyObject(e.observableSelectionPlan, itemObject(root.optJSONObject("ObservableSelectionPlan")));
        copyObject(e.entryParameterControlPlan, itemObject(root.optJSONObject("EntryParameterControlPlan")));
        copyObject(e.killabilityPlan, itemObject(root.optJSONObject("KillabilityPlan")));
        copyObject(e.liftedReachabilityPlan, itemObject(root.optJSONObject("LiftedReachabilityPlan")));
        copyObject(e.evidenceQuality, itemObject(root.optJSONObject("EvidenceQuality")));
    }

    private static void buildCodeKbContext(JSONObject root, PromptEvidence e) {
        if (root == null || e == null) {
            return;
        }
        copyObject(e.codeKbContext, root.optJSONObject("CodeKBContext"));
    }

    private static void applyObservableSelectionOverride(JSONObject root, PromptEvidence e) {
        if (root == null || e == null) {
            return;
        }
        JSONObject selection = itemObject(root.optJSONObject("ObservableSelectionPlan"));
        if (selection.length() == 0) {
            selection = e.observableSelectionPlan;
        }
        if (selection == null || selection.length() == 0) {
            return;
        }
        copyObject(e.observableSelectionPlan, selection);
        if (!selection.optBoolean("overrideRecommended", false)) {
            return;
        }
        String preferredKind = fieldString(selection, "preferredObservableKind");
        String preferredCall = sanitizeJavaSnippet(fieldString(selection, "preferredObservableCall"));
        if (preferredKind.isEmpty() && preferredCall.isEmpty()) {
            return;
        }
        String baselineKind = fieldString(e.observablePlan, "kind");
        String baselineReason = fieldString(e.observablePlan, "reason");
        if (!preferredKind.isEmpty()) {
            e.observablePlan.put("kind", preferredKind);
        }
        if (!preferredCall.isEmpty()) {
            e.observablePlan.put("observableCall", preferredCall);
        }
        String reason = firstNonBlank(fieldString(selection, "overrideReason"), baselineReason);
        if (!reason.isEmpty()) {
            if (!baselineKind.isEmpty() && !baselineKind.equals(preferredKind)) {
                reason = reason + " Baseline observable kind was " + baselineKind + ".";
            }
            e.observablePlan.put("reason", reason);
        }
        alignObservableDependentPlans(e, preferredKind, preferredCall, reason);
    }

    private static void alignObservableDependentPlans(PromptEvidence e,
                                                      String preferredKind,
                                                      String preferredCall,
                                                      String reason) {
        if (e == null) {
            return;
        }
        if (e.executableTestPlan != null) {
            JSONObject observable = e.executableTestPlan.optJSONObject("observable");
            if (observable == null) {
                observable = new JSONObject();
                e.executableTestPlan.put("observable", observable);
            }
            putIfNotEmpty(observable, "kind", preferredKind);
            putIfNotEmpty(observable, "observableCall", preferredCall);
            putIfNotEmpty(observable, "reason", reason);
        }
        if (e.propagationPlan != null) {
            putIfNotEmpty(e.propagationPlan, "observableSinkKind", preferredKind);
            String sinkExpression = extractObservedExpression(preferredCall);
            putIfNotEmpty(e.propagationPlan, "observableSinkExpression",
                    sinkExpression.isEmpty() ? preferredCall : sinkExpression);
            replacePrimaryObservableStep(e.propagationPlan, preferredCall);
        }
        if (e.liftedReachabilityPlan != null) {
            replaceLiftedObservableStep(e.liftedReachabilityPlan, preferredCall);
        }
    }

    private static void replacePrimaryObservableStep(JSONObject propagationPlan, String preferredCall) {
        if (propagationPlan == null || preferredCall == null || preferredCall.trim().isEmpty()) {
            return;
        }
        JSONArray chain = propagationPlan.optJSONArray("propagationChain");
        if (chain == null || chain.length() == 0) {
            return;
        }
        JSONArray updated = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < chain.length(); i++) {
            String step = chain.optString(i, "");
            if (step.contains("Public observable step:")) {
                updated.put("Public observable step: " + preferredCall);
                replaced = true;
            } else if (!preferredCall.equals(step)) {
                updated.put(step);
            }
        }
        if (!replaced) {
            updated.put("Public observable step: " + preferredCall);
        }
        propagationPlan.put("propagationChain", updated);
    }

    private static void replaceLiftedObservableStep(JSONObject liftedReachabilityPlan, String preferredCall) {
        if (liftedReachabilityPlan == null || preferredCall == null || preferredCall.trim().isEmpty()) {
            return;
        }
        JSONArray steps = liftedReachabilityPlan.optJSONArray("postEntryObservableSteps");
        JSONArray updated = new JSONArray();
        boolean inserted = false;
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                String step = steps.optString(i, "");
                if (step.contains("subject.nextRecord()")
                        || step.startsWith("Observe derived sink:")
                        || step.equals(preferredCall)) {
                    if (!inserted) {
                        updated.put(preferredCall);
                        inserted = true;
                    }
                    continue;
                }
                updated.put(step);
            }
        }
        if (!inserted) {
            updated.put(preferredCall);
        }
        liftedReachabilityPlan.put("postEntryObservableSteps", updated);
        String sinkExpression = extractObservedExpression(preferredCall);
        if (!sinkExpression.isEmpty()) {
            liftedReachabilityPlan.put("liftedObservationBridge",
                    "A-side divergence should propagate through entry B and become observable via: " + sinkExpression);
        }
    }

    private static String extractObservedExpression(String call) {
        String text = sanitizeJavaSnippet(call);
        if (text.isEmpty()) {
            return "";
        }
        int eq = text.indexOf('=');
        if (eq >= 0) {
            String left = text.substring(0, eq).trim();
            int lastSpace = left.lastIndexOf(' ');
            if (lastSpace >= 0 && lastSpace + 1 < left.length()) {
                return left.substring(lastSpace + 1).trim();
            }
        }
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    private static void buildEntry(JSONObject depRel,
                                   JSONObject entryRip,
                                   JSONObject entryRelation,
                                   JSONObject testEntryContext,
                                   JSONObject out) {
        boolean same = firstBoolean(false,
                fieldBoolean(entryRip, "sameEntryAndMutation", null),
                fieldBoolean(entryRelation, "sameEntryAndMutation", null),
                fieldBoolean(depRel, "sameEntryAndMutation", null));
        boolean need = firstBoolean(false,
                fieldBoolean(entryRip, "needEntryLiftedEvidence", null),
                fieldBoolean(entryRelation, "needEntryLiftedEvidence", null),
                fieldBoolean(depRel, "needEntryLiftedEvidence", null));

        out.put("sameEntryAndMutation", same);
        out.put("needEntryLiftedEvidence", need);
        out.put("recommendedTarget", firstNonBlank(
                fieldString(entryRelation, "recommendedTestTarget"),
                fieldString(depRel, "recommendedTestTarget")));
        out.put("invocationKind", firstNonBlank(
                fieldString(entryRip, "entryInvocationKind"),
                fieldString(entryRelation, "entryInvocationKind"),
                fieldString(depRel, "entryInvocationKind"),
                fieldString(testEntryContext, "entryInvocationKind")));
        out.put("testPackage", firstNonBlank(
                fieldString(entryRelation, "testGenerationPackage"),
                fieldString(testEntryContext, "testPackage")));
        out.put("mutationMethod", firstNonBlank(
                fieldString(entryRelation, "mutationMethod"),
                fieldString(depRel, "mutationMethod")));
        out.put("entryMethod", firstNonBlank(
                fieldString(entryRelation, "testEntryMethod"),
                fieldString(depRel, "testEntryMethod")));
        out.put("mutationClass", firstNonBlank(
                fieldString(entryRelation, "mutationClass"),
                fieldString(depRel, "mutationClass")));
        out.put("entryClass", firstNonBlank(
                fieldString(entryRelation, "testEntryClass"),
                fieldString(depRel, "testEntryClass")));

        List<String> callChain = fieldItems(entryRelation, "callChain");
        if (callChain.isEmpty()) {
            callChain = fieldItems(depRel, "callChain");
        }
        out.put("callChain", new JSONArray(cleanCallChain(callChain)));
        out.put("skipTestGeneration", firstBoolean(false,
                fieldBoolean(entryRelation, "skipTestGeneration", null),
                fieldBoolean(depRel, "skipTestGeneration", null)));
        out.put("skipReason", firstNonBlank(
                fieldString(entryRelation, "skipReason"),
                fieldString(depRel, "skipReason")));
    }

    private static void buildPublicApiAndObservableEvidence(JSONObject entryGenPlan,
                                                            JSONObject testEntryContext,
                                                            PromptEvidence e) {
        JSONObject publicApi = itemObject(childObject(entryGenPlan, "publicApi"));
        if (publicApi.length() == 0) {
            publicApi = itemObject(childObject(testEntryContext, "publicApi"));
        }

        JSONObject observable = itemObject(childObject(entryGenPlan, "observablePlan"));
        if (observable.length() == 0) {
            observable = itemObject(childObject(publicApi, "observablePlan"));
        }

        JSONObject branch = itemObject(childObject(entryGenPlan, "branchReachabilityPlan"));
        if (branch.length() == 0) {
            branch = itemObject(childObject(publicApi, "branchReachabilityPlan"));
        }

        List<String> availablePublic = fieldItems(publicApi, "availablePublicMethods");
        List<String> availableSetup = fieldItems(publicApi, "availableSetupMethods");
        List<String> stateSetup = fieldItems(publicApi, "stateSetupPlan");
        List<String> apiAnti = fieldItems(publicApi, "antiPatterns");

        // v35: 全局编译安全规则，必须作为硬约束保留
        List<String> compilationGuardrails = fieldItems(publicApi, "compilationGuardrails");

        JSONObject api = new JSONObject();

        if (!availablePublic.isEmpty()) {
            api.put("availablePublicMethods",
                    new JSONArray(limitList(availablePublic, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!availableSetup.isEmpty()) {
            api.put("availableSetupMethods",
                    new JSONArray(limitList(availableSetup, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!stateSetup.isEmpty()) {
            api.put("stateSetupPlan",
                    new JSONArray(limitList(cleanJavaStatements(stateSetup), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!apiAnti.isEmpty()) {
            api.put("antiPatterns",
                    new JSONArray(limitList(apiAnti, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        if (!compilationGuardrails.isEmpty()) {
            api.put("compilationGuardrails",
                    new JSONArray(limitList(compilationGuardrails, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        JSONObject branchOut = new JSONObject();
        putIfNotEmpty(branchOut, "kind", fieldString(branch, "kind"));
        putIfNotEmpty(branchOut, "condition", fieldString(branch, "condition"));
        putIfNotEmpty(branchOut, "setup", sanitizeJavaSnippet(fieldString(branch, "setup")));
        putIfNotEmpty(branchOut, "reason", fieldString(branch, "reason"));
        if (branchOut.length() > 0) {
            api.put("branchReachabilityPlan", branchOut);
        }

        copyObject(e.publicApiEvidence, api);

        JSONObject obs = new JSONObject();
        putIfNotEmpty(obs, "kind", fieldString(observable, "kind"));
        putIfNotEmpty(obs, "setup", sanitizeJavaSnippet(fieldString(observable, "setup")));
        putIfNotEmpty(obs, "observableCall", sanitizeJavaSnippet(fieldString(observable, "observableCall")));
        putIfNotEmpty(obs, "expectedOriginal", fieldString(observable, "expectedOriginal"));
        putIfNotEmpty(obs, "reason", fieldString(observable, "reason"));

        List<String> obsAnti = fieldItems(observable, "antiPatterns");
        if (!obsAnti.isEmpty()) {
            obs.put("antiPatterns", new JSONArray(limitList(obsAnti, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }

        copyObject(e.observablePlan, obs);
    }

    private static void buildInvocation(JSONObject testEntryContext,
                                        JSONObject entryGenPlan,
                                        JSONObject out) {
        JSONObject invocationPlan = itemObject(childObject(entryGenPlan, "invocationPlan"));
        JSONObject suggested = itemObject(childObject(entryGenPlan, "suggestedTestValues"));
        JSONObject receiver = itemObject(childObject(entryGenPlan, "receiver"));
        if (receiver.length() == 0) {
            receiver = itemObject(childObject(testEntryContext, "receiver"));
        }

        String pkg = fieldString(testEntryContext, "testPackage");
        out.put("package", pkg);

        List<String> imports = fieldItems(testEntryContext, "requiredImports");
        imports = ensureBasicJUnitImports(imports);
        out.put("imports", new JSONArray(imports));

        JSONObject recv = new JSONObject();
        putIfNotEmpty(recv, "strategy", fieldString(receiver, "strategy"));
        putIfNotEmpty(recv, "construction", sanitizeJavaSnippet(fieldString(receiver, "construction")));
        putIfNotEmpty(recv, "ownerKind", fieldString(receiver, "ownerKind"));
        putBooleanIfPresent(recv, "ownerAbstract", fieldBoolean(receiver, "ownerAbstract", null));
        putBooleanIfPresent(recv, "ownerInterface", fieldBoolean(receiver, "ownerInterface", null));
        putBooleanIfPresent(recv, "ownerInstantiable", fieldBoolean(receiver, "ownerInstantiable", null));
        putIfNotEmpty(recv, "runtimeReceiverClass", fieldString(receiver, "runtimeReceiverClass"));
        putIfNotEmpty(recv, "runtimeReceiverSootClass", fieldString(receiver, "runtimeReceiverSootClass"));
        putIfNotEmpty(recv, "declaringClass", fieldString(receiver, "declaringClass"));
        putIfNotEmpty(recv, "dispatchTarget", fieldString(receiver, "dispatchTarget"));
        putBooleanIfPresent(recv, "dispatchesToMutationMethod", fieldBoolean(receiver, "dispatchesToMutationMethod", null));
        putBooleanIfPresent(recv, "subclassOverridesMutationMethod", fieldBoolean(receiver, "subclassOverridesMutationMethod", null));
        putIfNotEmpty(recv, "setupTemplate", sanitizeJavaSnippet(fieldString(receiver, "setupTemplate")));
        putIfNotEmpty(recv, "invocationTemplate", sanitizeJavaSnippet(fieldString(receiver, "invocationTemplate")));
        putIfNotEmpty(recv, "resolutionReason", fieldString(receiver, "resolutionReason"));
        putIfNotEmpty(recv, "notes", fieldString(receiver, "notes"));
        putIfNotEmpty(recv, "factoryMethod", fieldString(receiver, "factoryMethod"));
        putIfNotEmpty(recv, "factoryMethodName", fieldString(receiver, "factoryMethodName"));
        putIfNotEmpty(recv, "builderClass", fieldString(receiver, "builderClass"));
        putIfNotEmpty(recv, "builderTerminalMethod", fieldString(receiver, "builderTerminalMethod"));
        putIfNotEmpty(recv, "builderSetupChain", fieldString(receiver, "builderSetupChain"));
        putIfNotEmpty(recv, "factoryBuilderReason", fieldString(receiver, "factoryBuilderReason"));
        List<String> receiverAntiPatterns = fieldItems(receiver, "antiPatterns");
        List<String> abstractMethods = fieldItems(receiver, "abstractMethodsToImplement");
        List<String> allowedOverrides = fieldItems(receiver, "allowedOverrides");
        List<String> forbiddenOverrides = fieldItems(receiver, "forbiddenOverrides");
        if (!abstractMethods.isEmpty()) {
            recv.put("abstractMethodsToImplement", new JSONArray(limitList(abstractMethods, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        if (!allowedOverrides.isEmpty()) {
            recv.put("allowedOverrides", new JSONArray(limitList(allowedOverrides, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        if (!forbiddenOverrides.isEmpty()) {
            recv.put("forbiddenOverrides", new JSONArray(compactForbiddenOverrides(forbiddenOverrides, recv)));
        }
        String compileReadyStub = firstNonBlank(
                fieldString(receiver, "compileReadyStubTemplate"),
                fieldString(receiver, "testStubClassTemplate"));
        String compileReadyCtor = firstNonBlank(
                fieldString(receiver, "compileReadyConstructorTemplate"),
                fieldString(receiver, "testStubConstructorTemplate"));
        putIfNotEmpty(recv, "testStubClassTemplate", sanitizeJavaSnippet(compileReadyStub));
        putIfNotEmpty(recv, "testStubConstructorTemplate", sanitizeJavaSnippet(compileReadyCtor));
        putIfNotEmpty(recv, "stubCompleteness", fieldString(receiver, "stubCompleteness"));
        if (!receiverAntiPatterns.isEmpty()) {
            recv.put("antiPatterns", new JSONArray(limitList(receiverAntiPatterns, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        }
        out.put("receiver", recv);

        List<String> setup = fieldItems(suggested, "setupStatements");
        String receiverSetupTemplate = sanitizeJavaSnippet(firstNonBlank(
                fieldString(receiver, "compileReadySetupTemplate"),
                fieldString(receiver, "setupTemplate")));
        String setupTemplate = sanitizeJavaSnippet(firstNonBlank(
                fieldString(invocationPlan, "setupTemplate"),
                receiverSetupTemplate));
        if (!receiverSetupTemplate.isEmpty()) {
            setup.add(0, receiverSetupTemplate);
        }
        if (!setupTemplate.isEmpty()) {
            setup.add(0, setupTemplate);
        }
        setup = cleanJavaStatements(setup);
        out.put("setup", new JSONArray(setup));

        String call = sanitizeJavaSnippet(firstNonBlank(
                fieldString(receiver, "compileReadyInvocationTemplate"),
                fieldString(receiver, "invocationTemplate"),
                fieldString(invocationPlan, "invocationTemplate")));
        putIfNotEmpty(out, "call", call);
        putIfNotEmpty(out, "notes", firstNonBlank(fieldString(invocationPlan, "notes"), fieldString(receiver, "notes")));
    }

    private static void buildAssertions(JSONObject entryGenPlan, JSONObject out) {
        JSONObject assertionPlan = itemObject(childObject(entryGenPlan, "assertionPlan"));
        JSONObject recommended = itemObject(childObject(assertionPlan, "recommendedAssertions"));

        List<String> required = recommendedAssertionExpressions(recommended, "primaryAssertions");
        List<String> optional = recommendedAssertionExpressions(recommended, "secondaryAssertions");
        List<String> sensitive = observableExpressions(childObject(assertionPlan, "mutationSensitiveObservables"));
        List<String> auxiliary = observableExpressions(childObject(assertionPlan, "auxiliaryObservables"));
        List<String> avoid = fieldItems(assertionPlan, "antiPatterns");

        out.put("requiredToKill", new JSONArray(limitList(cleanJavaStatements(required), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("optionalSanityChecks", new JSONArray(limitList(cleanJavaStatements(optional), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("mutationSensitiveObservables", new JSONArray(limitList(sensitive, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("auxiliaryObservables", new JSONArray(limitList(auxiliary, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("avoid", new JSONArray(limitList(avoid, DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static void buildMutationEvidence(JSONObject root, JSONObject out) {
        JSONObject origin = itemObject(root.optJSONObject("origin"));
        JSONObject mutated = itemObject(root.optJSONObject("mutated"));
        String originCode = fieldString(origin, "content");
        String mutantCode = fieldString(mutated, "content");
        List<String> originAffected = fieldItems(origin, "Affected");
        List<String> mutatedAffected = fieldItems(mutated, "Affected");

        putIfNotEmpty(out, "originCode", trimEvidence(originCode, DEFAULT_MAX_CODE_CHARS));
        putIfNotEmpty(out, "mutantCode", trimEvidence(mutantCode, DEFAULT_MAX_CODE_CHARS));
        out.put("originAffected", new JSONArray(limitList(originAffected, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        out.put("mutantAffected", new JSONArray(limitList(mutatedAffected, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        putIfNotEmpty(out, "propagationHint", inferPropagationHint(root));
    }


    private static void buildMutationGraphEvidence(JSONObject root, JSONObject out) {
        JSONObject origin = itemObject(root.optJSONObject("origin"));
        JSONObject mutated = itemObject(root.optJSONObject("mutated"));

        out.put("role", "A-side CPG/RIP evidence. Use this to reason from the real mutation point A to observable sinks and to design mutation-sensitive assertions.");

        JSONObject originGraph = summarizeCpgSide(origin);
        JSONObject mutantGraph = summarizeCpgSide(mutated);
        if (originGraph.length() > 0) {
            out.put("originGraph", originGraph);
        }
        if (mutantGraph.length() > 0) {
            out.put("mutantGraph", mutantGraph);
        }
        JSONArray available = unionAvailableEvidenceKinds(originGraph, mutantGraph);
        if (available.length() > 0) {
            out.put("availableEvidenceKinds", available);
        }

        List<String> originPaths = fieldItems(origin, "Paths");
        List<String> mutantPaths = fieldItems(mutated, "Paths");
        if (!originPaths.isEmpty()) {
            out.put("originPathSummary", trimEvidence(originPaths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        if (!mutantPaths.isEmpty()) {
            out.put("mutantPathSummary", trimEvidence(mutantPaths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
    }

    private static void buildEntryGraphEvidence(JSONObject entryRip, JSONObject out) {
        JSONObject origin = itemObject(childObject(entryRip, "origin"));
        JSONObject mutated = itemObject(childObject(entryRip, "mutated"));

        out.put("role", "B-side CPG/RIP evidence. Use this only to understand how callable entry B reaches real mutation method A; design assertions mainly from A-side mutationGraphEvidence.");

        JSONObject originGraph = summarizeCpgSide(origin);
        if (originGraph.length() > 0) {
            out.put("originEntryGraph", originGraph);
        }
        JSONArray available = unionAvailableEvidenceKinds(originGraph);
        if (available.length() > 0) {
            out.put("availableEvidenceKinds", available);
        }

        List<String> callSites = fieldItems(origin, "callSitesToMutation");
        if (!callSites.isEmpty()) {
            out.put("callSiteToMutation", trimEvidence(callSites.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        List<String> paths = fieldItems(origin, "Paths");
        if (!paths.isEmpty()) {
            out.put("entryPathSummary", trimEvidence(paths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        out.put("mutatedReusesOriginEntryGraph", fieldBoolean(mutated, "reusedFromOrigin", false));
    }

    private static JSONArray unionAvailableEvidenceKinds(JSONObject... graphs) {
        LinkedHashSet<String> kinds = new LinkedHashSet<String>();
        if (graphs != null) {
            for (JSONObject g : graphs) {
                if (g == null) {
                    continue;
                }
                JSONArray arr = g.optJSONArray("availableEvidenceKinds");
                if (arr == null) {
                    continue;
                }
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) {
                        kinds.add(s);
                    }
                }
            }
        }
        return new JSONArray(new ArrayList<String>(kinds));
    }

    private static JSONObject summarizeCpgSide(JSONObject side) {
        JSONObject out = new JSONObject();
        JSONObject cpg = childObject(side, "CPG");
        JSONArray arr = itemsArray(cpg);
        if (arr.length() == 0) {
            return out;
        }

        LinkedHashSet<String> availableKinds = new LinkedHashSet<String>();

        Object first = arr.opt(0);
        if (!(first instanceof JSONObject)) {
            out.put("raw", trimEvidence(String.valueOf(first), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            availableKinds.add("raw");
            out.put("availableEvidenceKinds", new JSONArray(new ArrayList<String>(availableKinds)));
            return out;
        }

        JSONObject item = (JSONObject) first;
        String path = fieldString(item, "Path");
        if (!path.isEmpty()) {
            out.put("path", trimEvidence(path, DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            availableKinds.add("path");
        }

        JSONObject cfgRaw = itemObject(childObject(item, "CFG"));
        JSONObject cfg = new JSONObject();
        putNonEmptyArray(cfg, "dom", fieldItems(cfgRaw, "dom"), availableKinds, "cfg.dom");
        putNonEmptyArray(cfg, "pathPredicates", fieldItems(cfgRaw, "path_predicates"), availableKinds, "cfg.pathPredicates");
        putNonEmptyArray(cfg, "controlDepsOut", fieldItems(cfgRaw, "control_deps_out"), availableKinds, "cfg.controlDepsOut");
        if (cfg.length() > 0) {
            out.put("cfg", cfg);
        }

        JSONObject dfgRaw = itemObject(childObject(item, "DFG"));
        JSONObject dfg = new JSONObject();
        putNonEmptyArray(dfg, "defsAtPoint", compactDfgItems(childObject(dfgRaw, "defs_point")), availableKinds, "dfg.defsAtPoint");
        putNonEmptyArray(dfg, "usesTowardOutput", compactDfgItems(childObject(dfgRaw, "uses_toward_output")), availableKinds, "dfg.usesTowardOutput");
        putNonEmptyArray(dfg, "killSet", compactDfgItems(childObject(dfgRaw, "kill_set")), availableKinds, "dfg.killSet");
        putNonEmptyArray(dfg, "heapAccess", compactDfgItems(childObject(dfgRaw, "heap_access")), availableKinds, "dfg.heapAccess");
        putNonEmptyArray(dfg, "mayThrow", compactDfgItems(childObject(dfgRaw, "may_throw")), availableKinds, "dfg.mayThrow");
        putNonEmptyArray(dfg, "aliasGroups", compactDfgItems(childObject(dfgRaw, "alias_groups")), availableKinds, "dfg.aliasGroups");
        if (dfg.length() > 0) {
            out.put("dfg", dfg);
        }

        if (!availableKinds.isEmpty()) {
            out.put("availableEvidenceKinds", new JSONArray(new ArrayList<String>(availableKinds)));
        }
        return out;
    }

    private static void putNonEmptyArray(JSONObject target,
                                         String key,
                                         List<String> values,
                                         LinkedHashSet<String> availableKinds,
                                         String evidenceKind) {
        if (values == null || values.isEmpty()) {
            return;
        }
        target.put(key, new JSONArray(limitList(values, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        availableKinds.add(evidenceKind);
    }

    private static List<String> compactDfgItems(JSONObject wrapper) {
        JSONArray arr = itemsArray(wrapper);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v == null) {
                continue;
            }
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                String var = o.optString("var", "");
                String unit = o.optString("unit", "");
                String sink = o.optString("sink", "");
                StringBuilder sb = new StringBuilder();
                if (!var.isEmpty()) {
                    sb.append(var);
                }
                if (!unit.isEmpty()) {
                    if (sb.length() > 0) sb.append(" <- ");
                    sb.append(unit);
                }
                if (!sink.isEmpty()) {
                    sb.append(" -> sink=").append(sink);
                }
                if (sb.length() == 0) {
                    sb.append(o.toString());
                }
                out.add(trimEvidence(sb.toString(), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            } else {
                out.add(trimEvidence(String.valueOf(v), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
            }
        }
        return out;
    }
    private static void buildEntryEvidence(JSONObject entryRip, JSONObject out) {
        JSONObject origin = itemObject(childObject(entryRip, "origin"));
        JSONObject mutated = itemObject(childObject(entryRip, "mutated"));
        String entryCode = fieldString(origin, "content");
        List<String> callSites = fieldItems(origin, "callSitesToMutation");
        List<String> paths = fieldItems(origin, "Paths");
        boolean reused = fieldBoolean(mutated, "reusedFromOrigin", false);

        putIfNotEmpty(out, "entryCode", trimEvidence(entryCode, DEFAULT_MAX_CODE_CHARS));
        if (!callSites.isEmpty()) {
            out.put("callSiteToMutation", trimEvidence(callSites.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        if (!paths.isEmpty()) {
            out.put("pathSummary", trimEvidence(paths.get(0), DEFAULT_MAX_EVIDENCE_STRING_CHARS));
        }
        out.put("mutatedReusesOriginEntryGraph", reused);
    }
}
