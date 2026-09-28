package mujava.rl;

import mujava.testgenerator.tools.PromptEvidence;
import mujava.testgenerator.tools.Request;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class StateBuilderTest {
    @Test
    public void shouldBuildStableStateFromCompactEvidence() {
        Request request = new Request();
        request.sourceModuleHome = "projectA";
        request.targetClassName = "org.example.Parser";
        request.methodSignature = "boolean_accept(int)";
        request.mutantName = "ROR_1";
        request.taskId = "row-1";
        request.outerLoopRound = 2;

        JSONObject root = sampleCompactEvidence();
        PromptEvidence evidence = PromptEvidence.fromFullOutput(root);

        EvidenceState state = StateBuilder.build(request, root, evidence);
        Assert.assertEquals("projectA::org.example.Parser::boolean_accept(int)::ROR_1", state.mutantId);
        Assert.assertEquals("ROR_1", state.operator);
        Assert.assertEquals("42", state.line);
        Assert.assertEquals(2, state.outerLoopRound);
        Assert.assertEquals("ASCENDED_PUBLIC_CALLER", state.testEntryKind);
        Assert.assertEquals("INSTANCE_METHOD_INVOCATION", state.entryInvocationKind);
        Assert.assertEquals("DIRECT_CONSTRUCTOR", state.testReceiverStrategy);
        Assert.assertEquals("RETURN_VALUE", state.observablePlanKind);
        Assert.assertEquals("BOUNDARY", state.branchReachabilityKind);
        Assert.assertEquals("ROR", state.operatorFamily);
        Assert.assertEquals("SMALL", state.promptSizeBucket);
        Assert.assertEquals("RELATIONAL_BRANCH|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL", state.exactBucketKey);
        Assert.assertEquals("RELATIONAL_BRANCH|RETURN_VALUE", state.coarseBucketKey);
        Assert.assertTrue(state.needEntryLiftedEvidence);
        Assert.assertTrue(state.estimatedPromptSize > 0);
    }

    @Test
    public void shouldSupportOneHotOperatorBuckets() {
        Request request = new Request();
        request.sourceModuleHome = "projectA";
        request.targetClassName = "org.example.Parser";
        request.methodSignature = "boolean_accept(int)";
        request.mutantName = "COD_3";

        JSONObject root = sampleCompactEvidence();
        PromptEvidence evidence = PromptEvidence.fromFullOutput(root);

        EvidenceState state = StateBuilder.build(
                request,
                root,
                evidence,
                null,
                LinUCBOperatorEncoding.ONE_HOT_19);
        Assert.assertEquals("COD", state.operatorFamily);
        Assert.assertEquals("COD|DIRECT_CONSTRUCTOR|RETURN_VALUE|BOUNDARY|SMALL", state.exactBucketKey);
        Assert.assertEquals("COD|RETURN_VALUE", state.coarseBucketKey);
    }

    @Test
    public void shouldDefaultMissingFieldsWithoutThrowing() {
        Request request = new Request();
        request.sourceModuleHome = "projectB";
        request.targetClassName = "C";
        request.methodSignature = "m()";
        request.mutantName = "AOIS_2";

        JSONObject root = new JSONObject();
        root.put("mutation", new JSONObject());
        root.put("entry", new JSONObject());
        root.put("invocation", new JSONObject());
        root.put("assertions", new JSONObject());
        root.put("mutationEvidence", new JSONObject());

        PromptEvidence evidence = PromptEvidence.fromFullOutput(root);
        EvidenceState state = StateBuilder.build(request, root, evidence);
        Assert.assertEquals("", state.testEntryKind);
        Assert.assertFalse(state.useReflectionFallback);
        Assert.assertEquals(0, state.previousRepairRounds);
    }

    private static JSONObject sampleCompactEvidence() {
        JSONObject root = new JSONObject();
        root.put("mutation", new JSONObject().put("operator", "ROR").put("diff", "x >= 0 -> x > 0")
                .put("location", new JSONObject().put("line", 42)));
        root.put("entry", new JSONObject()
                .put("needEntryLiftedEvidence", true)
                .put("testEntryKind", "ASCENDED_PUBLIC_CALLER")
                .put("useReflectionFallback", false)
                .put("invocationKind", "INSTANCE_METHOD_INVOCATION"));
        root.put("executableTestPlan", new JSONObject()
                .put("status", "READY")
                .put("branchReachability", new JSONObject().put("kind", "BOUNDARY")));
        root.put("invocation", new JSONObject()
                .put("invocationKind", "INSTANCE_METHOD_INVOCATION")
                .put("receiver", new JSONObject()
                        .put("strategy", "DIRECT_CONSTRUCTOR")
                        .put("ownerKind", "CONCRETE_CLASS"))
                .put("setup", new JSONArray().put("Parser p = new Parser();"))
                .put("call", "p.accept(0);"));
        root.put("assertions", new JSONObject().put("requiredToKill", new JSONArray().put("assertTrue(true);")));
        root.put("mutationEvidence", new JSONObject().put("originCode", "a").put("mutantCode", "b"));
        root.put("mutationGraphEvidence", new JSONObject().put("role", "A-side"));
        root.put("entryEvidence", new JSONObject().put("entryCode", "call-site"));
        root.put("entryGraphEvidence", new JSONObject().put("role", "B-side"));
        root.put("publicApiEvidence", new JSONObject().put("availablePublicMethods", new JSONArray().put("accept")));
        root.put("observablePlan", new JSONObject().put("kind", "RETURN_VALUE"));
        return root;
    }
}
