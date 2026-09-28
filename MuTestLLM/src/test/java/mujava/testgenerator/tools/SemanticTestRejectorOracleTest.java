package mujava.testgenerator.tools;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SemanticTestRejectorOracleTest {

    @Test
    public void rejectsAssertionFreeTestWhenCanonicalObservableExists() {
        PromptEvidence evidence = evidence("RETURN_VALUE", "DIRECT_RETURN_VALUE", "x + 1", "x - 1");
        String code = "import org.junit.Test; public class T { @Test public void t(){ int result = 1; } }";
        assertTrue(SemanticTestRejector.reject(request(), evidence, code).contains("no assertion"));
    }

    @Test
    public void rejectsTautologicalAssertion() {
        PromptEvidence evidence = evidence("RETURN_VALUE", "DIRECT_RETURN_VALUE", "x + 1", "x - 1");
        String code = "import org.junit.Test; import static org.junit.Assert.*; public class T { @Test public void t(){ assertTrue(true); } }";
        assertTrue(SemanticTestRejector.reject(request(), evidence, code).contains("tautological"));
    }

    @Test
    public void rejectsOnlyNotNullForNonNullInsensitiveMutation() {
        PromptEvidence evidence = evidence("PUBLIC_METHOD_DEPENDS_ON_MUTATED_STATE", "DIRECT_FIELD_PROJECTION", "x + 1", "x - 1");
        String code = "import org.junit.Test; import static org.junit.Assert.*; public class T { @Test public void t(){ Object value = new Object(); assertNotNull(value); } }";
        assertTrue(SemanticTestRejector.reject(request(), evidence, code).contains("non-nullness"));
    }

    @Test
    public void allowsExpectedExceptionOracleWithoutAssertion() {
        PromptEvidence evidence = evidence("EXCEPTION", "DIRECT_EXCEPTION", "x == null", "true");
        String code = "import org.junit.Test; public class T { @Test(expected=IllegalArgumentException.class) public void t(){ throw new IllegalArgumentException(); } }";
        assertEquals("", SemanticTestRejector.reject(request(), evidence, code));
    }

    private static PromptEvidence evidence(String kind, String directness, String original, String mutant) {
        PromptEvidence e = new PromptEvidence();
        JSONObject observable = new JSONObject();
        observable.put("kind", kind);
        observable.put("call", "Object result = subject.observe();");
        observable.put("directness", directness);
        e.canonicalCore.put("observable", observable);
        JSONObject infection = new JSONObject();
        infection.put("localOriginalExpression", original);
        infection.put("localMutantExpression", mutant);
        infection.put("semanticOriginalExpression", original);
        infection.put("semanticMutantExpression", mutant);
        e.canonicalCore.put("infection", infection);
        e.canonicalCore.put("oracle", new JSONObject().put("assertionMode", "RELATIONAL_ASSERTION"));
        return e;
    }

    private static Request request() {
        Request r = new Request();
        r.methodSignature = "void m()";
        r.mutantName = "M_1";
        return r;
    }
}
