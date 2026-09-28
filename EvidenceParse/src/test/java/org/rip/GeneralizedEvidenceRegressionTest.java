package org.rip;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Regression cases distilled from survivor artifacts; no benchmark names are hard-coded in production logic. */
public class GeneralizedEvidenceRegressionTest {

    @Test
    public void encodedAndQualifiedSignaturesMatchJavaParserCallable() {
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(
                "boolean contains(CharRange range) { return false; }");
        assertTrue(CallableSignatureNormalizer.matches(
                method, "boolean_contains(org.example.CharRange)"));
        assertEquals("contains(CharRange)", CallableSignatureNormalizer.canonical(method));
    }

    @Test
    public void semanticContainerUsesWholeReturnExpressionAndKeepsBranchPolarity() throws Exception {
        String original = source("end == Character.MAX_VALUE");
        String mutant = source("true");
        Path originalFile = tempJava(original);
        Path mutantFile = tempJava(mutant);
        int line = lineOf(original, "end == Character.MAX_VALUE");

        MutationSemanticLocator.MutationSemanticContext ctx = MutationSemanticLocator.resolve(
                originalFile, mutantFile, "CharRange",
                "boolean_contains(org.example.CharRange)", Integer.valueOf(line),
                "end == Character.MAX_VALUE", "true");

        assertTrue(ctx.reason, ctx.resolved);
        assertEquals("end == Character.MAX_VALUE", ctx.localOriginalExpression);
        assertEquals("true", ctx.localMutantExpression);
        assertEquals("start == 0 && end == Character.MAX_VALUE", compact(ctx.semanticOriginalExpression));
        assertEquals("start == 0 && true", compact(ctx.semanticMutantExpression));
        assertEquals("RETURN_VALUE", ctx.semanticContainerKind);
        assertTrue(hasGuard(ctx.ancestorGuardConstraints, "range.negated", true));
    }

    @Test
    public void controlPathAddsFallthroughNonNullAndBooleanCofactor() {
        String source = methodOnly("end == Character.MAX_VALUE");
        ControlPathConstraintExtractor.Context ctx = new ControlPathConstraintExtractor.Context();
        ctx.source = source;
        ctx.mutationIndex = source.indexOf("end == Character.MAX_VALUE");
        ctx.mutationOriginalExpression = "end == Character.MAX_VALUE";
        ctx.mutationMutantExpression = "true";
        ctx.semanticOriginalExpression = "start == 0 && end == Character.MAX_VALUE";
        ctx.semanticMutantExpression = "start == 0 && true";

        ControlPathConstraintExtractor.Result result = ControlPathConstraintExtractor.extract(ctx);
        assertTrue(hasGuard(result.mandatoryGuards, "negated", false));
        assertTrue(hasGuard(result.mandatoryGuards, "range.negated", true));
        assertTrue(hasGuard(result.mandatoryGuards, "range != null", true));
        assertTrue(hasGuard(result.mandatoryCofactors, "start == 0", true));
    }

    @Test
    public void finalReturnMutationKeepsBothFallthroughGuardsAndAndCofactor() throws Exception {
        String original = sourceFinalReturn("start <= range.start");
        String mutant = sourceFinalReturn("start == range.start");
        Path originalFile = tempJava(original);
        Path mutantFile = tempJava(mutant);
        int line = lineOf(original, "start <= range.start");

        MutationSemanticLocator.MutationSemanticContext semantic = MutationSemanticLocator.resolve(
                originalFile, mutantFile, "CharRange",
                "boolean_contains(org.example.CharRange)", Integer.valueOf(line),
                "start <= range.start", "start == range.start");

        assertTrue(semantic.reason, semantic.resolved);
        assertEquals("start <= range.start && end >= range.end", compact(semantic.semanticOriginalExpression));
        assertEquals("start == range.start && end >= range.end", compact(semantic.semanticMutantExpression));

        ControlPathConstraintExtractor.Context ctx = new ControlPathConstraintExtractor.Context();
        ctx.source = original.substring(original.indexOf(" public boolean contains"), original.lastIndexOf('}') );
        ctx.mutationIndex = ctx.source.indexOf("start <= range.start");
        ctx.mutationOriginalExpression = "start <= range.start";
        ctx.mutationMutantExpression = "start == range.start";
        ctx.semanticOriginalExpression = "start <= range.start && end >= range.end";
        ctx.semanticMutantExpression = "start == range.start && end >= range.end";

        ControlPathConstraintExtractor.Result result = ControlPathConstraintExtractor.extract(ctx);
        assertTrue(hasGuard(result.mandatoryGuards, "negated", false));
        assertTrue(hasGuard(result.mandatoryGuards, "range.negated", false));
        assertTrue(hasGuard(result.mandatoryGuards, "range != null", true));
        assertTrue(hasGuard(result.mandatoryCofactors, "end >= range.end", true));
    }

    @Test
    public void castInsideReturnDoesNotReplaceMutationSemanticContainer() throws Exception {
        String original = "package org.example; class Key { int id; boolean same(Object other) { "
                + "Key key = (Key) other; return id != key.id; } }";
        String mutant = original.replace("id != key.id", "id < key.id");
        Path originalFile = tempJava(original);
        Path mutantFile = tempJava(mutant);
        int line = lineOf(original, "id != key.id");

        MutationSemanticLocator.MutationSemanticContext ctx = MutationSemanticLocator.resolve(
                originalFile, mutantFile, "Key", "boolean_same(java.lang.Object)",
                Integer.valueOf(line), "id != key.id", "id < key.id");

        assertTrue(ctx.reason, ctx.resolved);
        assertEquals("id != key.id", compact(ctx.semanticOriginalExpression));
        assertEquals("id < key.id", compact(ctx.semanticMutantExpression));
        assertEquals("RETURN_VALUE", ctx.semanticContainerKind);
    }

    @Test
    public void forcedBooleanMutationKeepsConditionAsSemanticContainer() throws Exception {
        String original = "package org.example; class Fill { void fill(char[] a, char v) { "
                + "if (a != null) { java.util.Arrays.fill(a, v); } } }";
        String mutant = original.replace("a != null", "true");
        Path originalFile = tempJava(original);
        Path mutantFile = tempJava(mutant);
        int line = lineOf(original, "a != null");

        MutationSemanticLocator.MutationSemanticContext ctx = MutationSemanticLocator.resolve(
                originalFile, mutantFile, "Fill", "void_fill(char[],char)",
                Integer.valueOf(line), "a != null", "true");

        assertTrue(ctx.reason, ctx.resolved);
        assertEquals("a != null", compact(ctx.semanticOriginalExpression));
        assertEquals("true", compact(ctx.semanticMutantExpression));
        assertEquals("IF_CONDITION", ctx.semanticContainerKind);
    }

    @Test
    public void fluentReturnPrefersStateObserverOverReturnedThis() throws Exception {
        String source = "class Fluent {\n"
                + "  private final StringBuffer buffer = new StringBuffer();\n"
                + "  public Fluent append(String value) { buffer.append(value); return this; }\n"
                + "  public StringBuffer getBuffer() { return buffer; }\n"
                + "}\n";
        Path file = tempJava(source);
        ObservableSelectionEvidenceBuilder.Context ctx = new ObservableSelectionEvidenceBuilder.Context();
        ctx.originJavaFile = file.toString();
        ctx.entryClassName = "Fluent";
        ctx.entryMethodSig = "Fluent append(java.lang.String)";
        ctx.mutationMethodSig = "Fluent append(java.lang.String)";
        ctx.diff = "value => value + \"x\"";
        ctx.observablePlanKind = "ENTRY_RETURN_VALUE";
        ctx.observableCall = "Fluent result = subject.append(value);";

        Map<String, Object> plan = ObservableSelectionEvidenceBuilder.buildObservableSelectionPlan(ctx);
        assertEquals(Boolean.TRUE, item(plan.get("overrideRecommended")));
        String call = String.valueOf(item(plan.get("preferredObservableCall")));
        assertTrue(call, call.contains("getBuffer()") && call.contains("toString()"));
    }

    @Test
    public void mutableParameterBecomesObservableWhenNoOwnerStateObserverExists() throws Exception {
        String source = "class Style {\n"
                + "  protected void appendDetail(StringBuffer buffer, String value) { buffer.append(value); }\n"
                + "}\n";
        Path file = tempJava(source);
        ObservableSelectionEvidenceBuilder.Context ctx = new ObservableSelectionEvidenceBuilder.Context();
        ctx.originJavaFile = file.toString();
        ctx.entryClassName = "Style";
        ctx.entryMethodSig = "void_appendDetail(java.lang.StringBuffer,java.lang.String)";
        ctx.mutationMethodSig = ctx.entryMethodSig;
        ctx.diff = "value => value + \"x\"";
        ctx.observablePlanKind = "UNKNOWN_OBSERVABLE";
        ctx.observableCall = "";

        Map<String, Object> plan = ObservableSelectionEvidenceBuilder.buildObservableSelectionPlan(ctx);
        assertEquals(Boolean.TRUE, item(plan.get("overrideRecommended")));
        assertEquals("PARAMETER_STATE_OBSERVABLE", String.valueOf(item(plan.get("preferredObservableKind"))));
        assertTrue(String.valueOf(item(plan.get("preferredObservableCall"))).contains("buffer.toString()"));
    }

    private static String source(String mutatedPart) {
        return "package org.example;\nimport java.util.Objects;\nclass CharRange {\n"
                + " boolean negated; char start; char end;\n"
                + methodOnly(mutatedPart) + "\n}\n";
    }

    private static String methodOnly(String mutatedPart) {
        return " public boolean contains(CharRange range) {\n"
                + "  Objects.requireNonNull(range, \"range\");\n"
                + "  if (negated) {\n"
                + "   if (range.negated) return start >= range.start && end <= range.end;\n"
                + "   return range.end < start || range.start > end;\n"
                + "  }\n"
                + "  if (range.negated) {\n"
                + "   return start == 0 && " + mutatedPart + ";\n"
                + "  }\n"
                + "  return start <= range.start && end >= range.end;\n"
                + " }";
    }

    private static String sourceFinalReturn(String mutatedPart) {
        return "package org.example;\nimport java.util.Objects;\nclass CharRange {\n"
                + " boolean negated; char start; char end;\n"
                + " public boolean contains(CharRange range) {\n"
                + "  Objects.requireNonNull(range, \"range\");\n"
                + "  if (negated) {\n"
                + "   if (range.negated) return start >= range.start && end <= range.end;\n"
                + "   return range.end < start || range.start > end;\n"
                + "  }\n"
                + "  if (range.negated) { return start == 0 && end == Character.MAX_VALUE; }\n"
                + "  return " + mutatedPart + " && end >= range.end;\n"
                + " }\n}\n";
    }

    private static Path tempJava(String source) throws Exception {
        Path file = Files.createTempFile("rip-regression-", ".java");
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file;
    }

    private static int lineOf(String source, String needle) {
        int idx = source.indexOf(needle);
        assertTrue(idx >= 0);
        int line = 1;
        for (int i = 0; i < idx; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static boolean hasGuard(List<Map<String, Object>> guards, String expression, boolean required) {
        for (Map<String, Object> guard : guards) {
            if (expression.equals(String.valueOf(guard.get("expression")))
                    && required == Boolean.parseBoolean(String.valueOf(guard.get("requiredEvaluation")))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Object item(Object wrapper) {
        if (wrapper instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) wrapper;
            return map.containsKey("item") ? map.get("item") : wrapper;
        }
        return wrapper;
    }
}
