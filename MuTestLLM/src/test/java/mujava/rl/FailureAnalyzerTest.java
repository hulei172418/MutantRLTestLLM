package mujava.rl;

import mujava.testgenerator.tools.PromptEvidence;
import mujava.testgenerator.tools.Request;
import mujava.testgenerator.tools.Result;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class FailureAnalyzerTest {
    @Test
    public void shouldRecoverMissingImportFromCodeKbCandidateImports() {
        Request request = new Request();
        request.sourceModuleHome = "commons-csv-1.2";
        request.targetClassName = "org.apache.commons.csv.Lexer";
        request.methodSignature = "nextToken(Token)";
        request.mutantName = "AOIU_6";

        Result result = new Result();
        result.compiled = false;
        result.failureReason = "Compilation failed after repair rounds. Last error: cannot find symbol symbol: class CSVRecord";

        FailureAnalysis analysis = FailureAnalyzer.analyze(
                request,
                StateBuilder.build(request, sampleFullJson(), PromptEvidence.fromFullOutput(sampleFullJson())),
                EvidenceAction.MUTATION_HEAVY,
                result,
                sampleFullJson(),
                PromptEvidence.fromFullOutput(sampleFullJson()));

        Assert.assertTrue(contains(analysis.suspectedMissingImports, "org.apache.commons.csv.CSVRecord"));
    }

    @Test
    public void shouldRecoverExplicitImportStatementFromCompileError() {
        Request request = new Request();
        request.sourceModuleHome = "commons-csv-1.2";
        request.targetClassName = "org.apache.commons.csv.Lexer";
        request.methodSignature = "nextToken(Token)";
        request.mutantName = "AOIU_6";

        Result result = new Result();
        result.compiled = false;
        result.failureReason = "Compilation failed after repair rounds. Last error: /tmp/Test.java:3: error: package org.example.missing does not exist import org.example.missing.Helper;";

        JSONObject root = new JSONObject();
        root.put("mutation", new JSONObject());
        root.put("entry", new JSONObject());
        root.put("invocation", new JSONObject());
        root.put("assertions", new JSONObject());
        root.put("mutationEvidence", new JSONObject());

        FailureAnalysis analysis = FailureAnalyzer.analyze(
                request,
                StateBuilder.build(request, root, PromptEvidence.fromFullOutput(root)),
                EvidenceAction.MUTATION_HEAVY,
                result,
                root,
                PromptEvidence.fromFullOutput(root));

        Assert.assertTrue(contains(analysis.suspectedMissingImports, "org.example.missing.Helper"));
    }

    @Test
    public void classifiesApiFailureSeparatelyFromCompileFailure() {
        Request request = new Request();
        request.sourceModuleHome = "commons-csv-1.2";
        request.targetClassName = "org.apache.commons.csv.Lexer";
        request.methodSignature = "nextToken(Token)";
        request.mutantName = "AOIU_6";

        Result result = new Result();
        result.compiled = false;
        result.targetStatus = Result.STATUS_API_FAILED;
        result.failureReason = "API failed: LLM API failed after 2 attempt(s)";

        JSONObject root = new JSONObject();
        root.put("mutation", new JSONObject());
        root.put("entry", new JSONObject());
        root.put("invocation", new JSONObject());
        root.put("assertions", new JSONObject());
        root.put("mutationEvidence", new JSONObject());
        PromptEvidence evidence = PromptEvidence.fromFullOutput(root);

        FailureAnalysis analysis = FailureAnalyzer.analyze(
                request,
                StateBuilder.build(request, root, evidence),
                EvidenceAction.MUTATION_HEAVY,
                result,
                root,
                evidence);

        Assert.assertEquals("api_failed", analysis.symptom);
        Assert.assertEquals(RootCauseType.API_TRANSPORT_FAILURE, analysis.rootCauseType);
        Assert.assertEquals("LLM_API", analysis.primaryFixTarget);
    }

    private static boolean contains(JSONArray arr, String expected) {
        for (int i = 0; i < arr.length(); i++) {
            if (expected.equals(arr.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject sampleFullJson() {
        JSONObject root = new JSONObject();
        root.put("mutation", new JSONObject().put("operator", "AOIU"));
        root.put("entry", new JSONObject());
        root.put("invocation", new JSONObject());
        root.put("assertions", new JSONObject());
        root.put("mutationEvidence", new JSONObject());

        JSONObject structural = new JSONObject();
        structural.put("codekb", new JSONObject()
                .put("candidateImports", new JSONArray()
                        .put(new JSONObject()
                                .put("importValue", "org.apache.commons.csv.CSVRecord")
                                .put("sourceKind", "SOURCE_FILE")
                                .put("usageContext", "compile fix")
                                .put("priority", 95))));

        JSONObject evidence = new JSONObject();
        evidence.put("structural", structural);

        JSONObject summary = new JSONObject();
        JSONObject guidance = new JSONObject();

        JSONObject outputV2 = new JSONObject();
        outputV2.put("summary", summary);
        outputV2.put("guidance", guidance);
        outputV2.put("evidence", evidence);

        root.put("OutputV2", outputV2);
        return root;
    }
}
