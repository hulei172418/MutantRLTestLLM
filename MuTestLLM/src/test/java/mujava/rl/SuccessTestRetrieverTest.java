package mujava.rl;

import mujava.MutationSystem;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class SuccessTestRetrieverTest {
    @Test
    public void shouldUseEarlierRoundExactStatusAndSameSiteSuccess() throws Exception {
        Path temp = Files.createTempDirectory("history-retriever-test");
        Path resultHome = temp.resolve("result");
        Path trajectory = temp.resolve("trajectory.jsonl");

        String siblingTestName = "org.example.Parser_ROR_1_Test";
        Path siblingJava = resultHome.resolve(MutationSystem.TESTSET_MODE_LLMS)
                .resolve("src")
                .resolve(siblingTestName.replace('.', java.io.File.separatorChar) + ".java");
        Files.createDirectories(siblingJava.getParent());
        Files.write(siblingJava,
                Arrays.asList(
                        "package org.example;",
                        "import org.junit.Test;",
                        "public class Parser_ROR_1_Test { @Test public void t() {} }"),
                StandardCharsets.UTF_8);

        JSONObject siblingState = new JSONObject()
                .put("line", "42")
                .put("outerLoopRound", 1)
                .put("observablePlanKind", "RETURN_VALUE")
                .put("testEntryKind", "DIRECT")
                .put("entryInvocationKind", "INSTANCE_METHOD_INVOCATION");
        JSONObject sibling = new JSONObject()
                .put("mutantId", "projectA::org.example.Parser::boolean_accept(int)::ROR_1")
                .put("project", "projectA")
                .put("className", "org.example.Parser")
                .put("method", "boolean_accept(int)")
                .put("operator", "ROR_1")
                .put("line", "42")
                .put("outerLoopRound", 1)
                .put("state", siblingState)
                .put("testName", siblingTestName)
                .put("compileSuccess", true)
                .put("originalPass", true)
                .put("killed", true)
                .put("repairRounds", 0)
                .put("targetStatus", "KILLED")
                .put("reward", 3.0d);

        JSONObject previousState = new JSONObject()
                .put("line", "42")
                .put("outerLoopRound", 1);
        JSONObject previous = new JSONObject()
                .put("mutantId", "projectA::org.example.Parser::boolean_accept(int)::ROR_2")
                .put("project", "projectA")
                .put("className", "org.example.Parser")
                .put("method", "boolean_accept(int)")
                .put("operator", "ROR_2")
                .put("line", "42")
                .put("outerLoopRound", 1)
                .put("state", previousState)
                .put("testName", "org.example.Parser_ROR_2_Test")
                .put("compileSuccess", false)
                .put("originalPass", false)
                .put("killed", false)
                .put("repairRounds", 1)
                .put("targetStatus", "COMPILE_FAILED_AFTER_REPAIR")
                .put("failureReason", "javac failed");

        Files.write(trajectory,
                Arrays.asList(sibling.toString(), previous.toString()),
                StandardCharsets.UTF_8);

        EvidenceState state = new EvidenceState();
        state.mutantId = "projectA::org.example.Parser::boolean_accept(int)::ROR_2";
        state.project = "projectA";
        state.className = "org.example.Parser";
        state.method = "boolean_accept(int)";
        state.operator = "ROR_2";
        state.operatorFamily = "ROR";
        state.line = "42";
        state.outerLoopRound = 2;
        state.observablePlanKind = "RETURN_VALUE";
        state.testEntryKind = "DIRECT";
        state.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";

        HistoricalExperienceSnapshot snapshot = SuccessTestRetriever.inspect(
                state, trajectory.toString(), resultHome.toString(), 2);
        snapshot.applyTo(state);

        Assert.assertTrue(snapshot.hasPreviousResult);
        Assert.assertFalse(snapshot.previousCompileSuccess);
        Assert.assertEquals(1, snapshot.previousRepairRounds);
        Assert.assertEquals(1, snapshot.sameSiteProcessedCount);
        Assert.assertEquals(1, snapshot.sameSiteSuccessfulCount);
        Assert.assertEquals(1.0d, snapshot.sameSiteCompileSuccessRate, 0.0001d);
        Assert.assertEquals(1.0d, snapshot.sameSiteKillRate, 0.0001d);
        Assert.assertEquals(1, snapshot.getReferences().size());
        Assert.assertEquals(SuccessTestRetriever.RELATION_SAME_SITE,
                snapshot.getReferences().get(0).relation);
        Assert.assertTrue(state.hasPreviousResult);
        Assert.assertTrue(state.hasSuccessfulReference);
        Assert.assertEquals(1, state.successfulReferenceCount);
    }
}
