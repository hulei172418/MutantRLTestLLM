package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class GeneratedCodeEvidenceGuardTest {

    @Test
    public void repairsPrivateFieldReadAndBinaryStaticUtilityCall() {
        PromptEvidence evidence = vectorEvidence();
        String code = "public class T { void t() { "
                + "Vector3D u1 = null; Vector3D u2 = null; "
                + "double x = u1.x; double d = u1.dotProduct(u2); } }";

        String normalized = GeneratedCodeEvidenceGuard.normalizeAgainstEvidence(code, evidence);

        assertTrue(normalized.contains("u1.getX()"));
        assertTrue(normalized.contains("Vector3D.dotProduct(u1, u2)"));
    }

    @Test
    public void doesNotReplacePrivateFieldWriteWithGetter() {
        PromptEvidence evidence = vectorEvidence();
        String code = "public class T { void t() { Vector3D u1 = null; u1.x = 1.0; } }";

        String normalized = GeneratedCodeEvidenceGuard.normalizeAgainstEvidence(code, evidence);

        assertTrue(normalized.contains("u1.x = 1.0"));
        assertFalse(normalized.contains("getX() ="));
    }

    private static PromptEvidence vectorEvidence() {
        PromptEvidence evidence = new PromptEvidence();
        evidence.compilationFacts.put("receiver", new JSONObject().put("ownerSimpleName", "Vector3D"));
        evidence.compilationFacts.put("privateFieldNames", new JSONArray().put("x").put("y").put("z"));
        evidence.compilationFacts.put("exactCallableSignatures", new JSONArray()
                .put("double getX()")
                .put("static double dotProduct(Vector3D, Vector3D)"));
        evidence.publicApiEvidence.put("availablePublicMethods", new JSONArray()
                .put("double getX()")
                .put("double dotProduct(Vector3D, Vector3D)"));
        return evidence;
    }
}
