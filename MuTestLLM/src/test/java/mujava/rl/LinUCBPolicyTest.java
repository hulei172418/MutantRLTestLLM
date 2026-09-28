package mujava.rl;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LinUCBPolicyTest {
    @Test
    public void highRewardUpdateMakesActionPreferredForSameContext() throws Exception {
        Path dir = Files.createTempDirectory("linucb-test");
        Path model = dir.resolve("policy.json");

        EvidenceState state = new EvidenceState();
        state.operatorFamily = "ROR";
        state.observablePlanKind = "RETURN_VALUE";
        state.promptSizeBucket = PromptSizeBucket.SMALL.name();
        state.functionMutantCount = 1;

        LinUCBPolicy policy = new LinUCBPolicy(
                model.toString(),
                0.0d,
                1.0d,
                false,
                LinUCBOperatorEncoding.SIX_CLASS);
        policy.update(state, EvidenceAction.ASSERTION_HEAVY, 10.0d);

        assertEquals(EvidenceAction.ASSERTION_HEAVY, policy.select(state));
    }

    @Test
    public void vectorizerSchemaHasStableDimension() {
        double[] x = LinUCBContextVectorizer.vectorize(new EvidenceState());
        assertEquals(LinUCBContextVectorizer.DIMENSION, x.length);
        assertTrue(LinUCBContextVectorizer.SCHEMA_VERSION >= 1);
    }

    @Test
    public void sixClassEncodingUsesDedicatedBucketForCod() {
        EvidenceState state = new EvidenceState();
        state.operatorFamily = "COD";

        double[] x = LinUCBContextVectorizer.vectorize(state, LinUCBOperatorEncoding.SIX_CLASS);
        assertEquals(LinUCBContextVectorizer.dimension(LinUCBOperatorEncoding.SIX_CLASS), x.length);
        assertEquals(1.0d, x[19], 0.0d);
        assertEquals(0.0d, x[14], 0.0d);
        assertEquals(0.0d, x[15], 0.0d);
        assertEquals(0.0d, x[16], 0.0d);
        assertEquals(0.0d, x[17], 0.0d);
        assertEquals(0.0d, x[18], 0.0d);
    }

    @Test
    public void oneHotEncodingKeepsConcreteOperatorIdentity() {
        EvidenceState state = new EvidenceState();
        state.operatorFamily = "COD";

        double[] x = LinUCBContextVectorizer.vectorize(state, LinUCBOperatorEncoding.ONE_HOT_19);
        assertEquals(LinUCBContextVectorizer.dimension(LinUCBOperatorEncoding.ONE_HOT_19), x.length);
        assertEquals(1.0d, x[27], 0.0d);
        assertEquals(0.0d, x[33], 0.0d);
    }
}
