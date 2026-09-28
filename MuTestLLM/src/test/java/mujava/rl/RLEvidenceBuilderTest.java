package mujava.rl;

import mujava.testgenerator.tools.PromptEvidence;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class RLEvidenceBuilderTest {
    @Test
    public void shouldBuildNonEmptyEvidenceForAllActions() {
        JSONObject root = sampleCompactEvidence();
        for (EvidenceAction action : EvidenceAction.values()) {
            PromptEvidence evidence = RLEvidenceBuilder.build(root, action);
            JSONObject json = evidence.toJson();
            Assert.assertTrue("mutation missing for " + action, json.has("mutation"));
            Assert.assertTrue("entry missing for " + action, json.has("entry"));
            Assert.assertTrue("invocation missing for " + action, json.has("invocation"));
            Assert.assertTrue("assertions missing for " + action, json.has("assertions"));
            Assert.assertTrue("mutationEvidence missing for " + action, json.has("mutationEvidence"));
        }
    }

    @Test
    public void compactShouldDropHeavyGraphSections() {
        PromptEvidence evidence = RLEvidenceBuilder.build(sampleCompactEvidence(), EvidenceAction.COMPACT);
        JSONObject json = evidence.toJson();
        Assert.assertFalse(json.has("mutationGraphEvidence"));
        Assert.assertFalse(json.has("entryGraphEvidence"));
        Assert.assertFalse(json.has("publicApiEvidence"));
        Assert.assertTrue(json.has("codeKbContext"));
        Assert.assertEquals(1, json.getJSONObject("codeKbContext").getJSONArray("candidateEntries").length());
    }

    @Test
    public void compileHeavyShouldKeepOnlyMinimalCodeKbAndNoGraphs() {
        PromptEvidence evidence = RLEvidenceBuilder.build(sampleCompactEvidence(), EvidenceAction.COMPILE_HEAVY);
        JSONObject json = evidence.toJson();
        Assert.assertFalse(json.has("mutationGraphEvidence"));
        Assert.assertFalse(json.has("entryGraphEvidence"));
        Assert.assertFalse(json.has("entryEvidence"));
        Assert.assertEquals(1, json.getJSONObject("codeKbContext").getJSONArray("candidateEntries").length());
    }

    @Test
    public void mutationHeavyShouldKeepMutationGraphButDropEntryGraph() {
        PromptEvidence evidence = RLEvidenceBuilder.build(sampleCompactEvidence(), EvidenceAction.MUTATION_HEAVY);
        JSONObject json = evidence.toJson();
        Assert.assertTrue(json.has("mutationGraphEvidence"));
        Assert.assertFalse(json.has("entryGraphEvidence"));
        Assert.assertFalse(json.has("entryEvidence"));
    }

    @Test
    public void shouldParseCleanSchemaWithoutLegacyEvidence() {
        PromptEvidence evidence = PromptEvidence.fromFullOutput(sampleCleanSchemaWithoutLegacyEvidence());
        JSONObject json = evidence.toJson();

        Assert.assertEquals("COR", json.getJSONObject("mutation").getString("operator"));
        Assert.assertEquals("contains(CharRange)", json.getJSONObject("entry").getString("entryMethod"));
        Assert.assertEquals("boolean result = subject.contains(range);", json.getJSONObject("invocation").getString("call"));
        Assert.assertTrue(json.has("primaryChain"));
        Assert.assertEquals(1, json.getJSONObject("codeKbContext").getJSONArray("candidateEntries").length());
        Assert.assertEquals(1, json.getJSONObject("codeKbContext").getJSONArray("mutantWitnessCandidates").length());
        Assert.assertEquals(1, json.getJSONObject("compilationFacts").getJSONArray("exactCallableSignatures").length());
        Assert.assertEquals("CharRange subject = CharRange.isNotIn('m', 'z');",
                json.getJSONObject("primaryChain").getJSONObject("witness").getJSONArray("receiverSetup").getString(0));
        Assert.assertEquals("CharRange range = CharRange.isIn('a', 'b');",
                json.getJSONObject("primaryChain").getJSONObject("witness").getJSONArray("argumentSetup").getString(0));
        Assert.assertEquals("boolean result = subject.contains(range);",
                json.getJSONObject("primaryChain").getJSONObject("witness").getString("concreteEntryCall"));
    }

    private static JSONObject sampleCompactEvidence() {
        JSONObject root = new JSONObject();
        root.put("mutation", new JSONObject().put("operator", "ROR").put("diff", "x >= 0 -> x > 0"));
        root.put("entry", new JSONObject()
                .put("needEntryLiftedEvidence", true)
                .put("sameEntryAndMutation", false)
                .put("recommendedTarget", "call_test_entry_method")
                .put("invocationKind", "INSTANCE_METHOD_INVOCATION")
                .put("testEntryKind", "ASCENDED_PUBLIC_CALLER")
                .put("useReflectionFallback", false)
                .put("callChain", new JSONArray().put("Parser.parse -> Parser.accept")));
        root.put("executableTestPlan", new JSONObject()
                .put("status", "READY")
                .put("requiredSetup", new JSONArray().put("Parser p = new Parser();"))
                .put("branchReachability", new JSONObject().put("kind", "BOUNDARY"))
                .put("observable", new JSONObject().put("kind", "RETURN_VALUE"))
                .put("entryCall", "p.accept(0);"));
        root.put("invocation", new JSONObject()
                .put("package", "org.example")
                .put("receiver", new JSONObject()
                        .put("strategy", "DIRECT_CONSTRUCTOR")
                        .put("ownerKind", "CONCRETE_CLASS"))
                .put("setup", new JSONArray().put("Parser p = new Parser();"))
                .put("call", "p.accept(0);"));
        root.put("assertions", new JSONObject()
                .put("requiredToKill", new JSONArray().put("assertTrue(p.accept(0));")));
        root.put("mutationEvidence", new JSONObject().put("originCode", "a").put("mutantCode", "b"));
        root.put("mutationGraphEvidence", new JSONObject().put("role", "A-side"));
        root.put("entryEvidence", new JSONObject().put("entryCode", "call-site"));
        root.put("entryGraphEvidence", new JSONObject().put("role", "B-side"));
        root.put("publicApiEvidence", new JSONObject().put("availablePublicMethods", new JSONArray().put("accept")));
        root.put("observablePlan", new JSONObject().put("kind", "RETURN_VALUE"));
        root.put("codeKbContext", new JSONObject()
                .put("enabled", true)
                .put("candidateEntries", new JSONArray()
                        .put(new JSONObject().put("signature", "Parser.parse(int)").put("reason", "top"))
                        .put(new JSONObject().put("signature", "Parser.accept(int)").put("reason", "second")))
                .put("mutantMethodCalls", new JSONArray()
                        .put(new JSONObject().put("name", "accept"))
                        .put(new JSONObject().put("name", "flush")))
                .put("mutantFieldAccesses", new JSONArray()
                        .put(new JSONObject().put("name", "offset"))
                        .put(new JSONObject().put("name", "state"))));
        return root;
    }

    private static JSONObject sampleCleanSchemaWithoutLegacyEvidence() {
        JSONObject root = new JSONObject();
        root.put("meta", new JSONObject()
                .put("schemaVersion", "2.0")
                .put("generator", "EvidenceParse")
                .put("mode", "clean_root"));
        root.put("mutation", new JSONObject()
                .put("operator", "COR")
                .put("diff", "a || b => a && b")
                .put("location", new JSONObject()
                        .put("class", "CharRange")
                        .put("method", "boolean contains(CharRange)"))
                .put("entry", new JSONObject()
                        .put("class", "CharRange")
                        .put("method", "contains(CharRange)")
                        .put("invocationKind", "INSTANCE_METHOD_INVOCATION")
                        .put("sameEntryAndMutation", true)
                        .put("callChain", new JSONArray().put("public CharRange#contains(CharRange)"))));
        root.put("summary", new JSONObject()
                .put("codekb", new JSONObject().put("enabled", true).put("status", "READY"))
                .put("killability", new JSONObject().put("status", "killable").put("level", "LIKELY_FROM_CURRENT_ENTRY"))
                .put("observability", new JSONObject()
                        .put("preferredKind", "ENTRY_RETURN_VALUE")
                        .put("preferredCall", "boolean result = subject.contains(range);")));
        root.put("guidance", new JSONObject()
                .put("testTarget", new JSONObject()
                        .put("package", "org.apache.commons.lang3")
                        .put("imports", new JSONArray().put("org.junit.Test").put("static org.junit.Assert.*"))
                        .put("invocationPlan", new JSONObject()
                                .put("invocationTemplate", "boolean result = subject.contains(null);"))
                        .put("arguments", new JSONArray()
                                .put(new JSONObject().put("name", "range").put("exampleValue", "null"))))
                .put("apiRoleFacts", new JSONObject()
                        .put("observationFacts", new JSONObject()
                                .put("primaryOracle", new JSONObject()
                                        .put("kind", "ENTRY_RETURN_VALUE")
                                        .put("expression", "boolean result = subject.contains(range);"))))
                .put("entryCandidates", new JSONArray()
                        .put(new JSONObject().put("signature", "contains(org.apache.commons.lang3.CharRange)").put("reason", "direct entry")))
                .put("observableCandidates", new JSONArray()
                        .put(new JSONObject().put("expression", "subject.contains(range)").put("reason", "return value")))
                .put("distinguishingWitnesses", new JSONArray()
                        .put(new JSONObject().put("assertionSketch", "assertFalse(subject.contains(range));")))
                .put("compilationFacts", new JSONObject()
                        .put("exactCallableSignatures", new JSONArray().put("contains(org.apache.commons.lang3.CharRange)")))
                .put("primaryChain", new JSONObject()
                        .put("entry", new JSONObject().put("signature", "contains(org.apache.commons.lang3.CharRange)"))
                        .put("witness", new JSONObject()
                                .put("receiverSetup", new JSONArray().put("CharRange subject = CharRange.isNotIn('m', 'z');"))
                                .put("argumentSetup", new JSONArray().put("CharRange range = CharRange.isIn('a', 'b');"))
                                .put("concreteEntryCall", "boolean result = subject.contains(range);"))
                        .put("oracle", new JSONObject().put("observableCall", "boolean result = subject.contains(range);")))
                .put("assertionPlan", new JSONObject()
                        .put("requiredToKill", new JSONArray().put("assert mutation-sensitive return value"))));
        return root;
    }
}
