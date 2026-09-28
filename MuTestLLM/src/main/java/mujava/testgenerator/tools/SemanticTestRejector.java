package mujava.testgenerator.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative semantic precheck before javac.
 *
 * This does not try to prove correctness. It only rejects generated tests that
 * clearly violate high-confidence evidence constraints and would otherwise
 * waste
 * repair rounds on the wrong path.
 */
public final class SemanticTestRejector {
    private SemanticTestRejector() {
    }

    public static String reject(Request request, PromptEvidence evidence, String candidateCode) {
        if (request == null || evidence == null || isBlank(candidateCode)) {
            return "";
        }

        String directEntryViolation = rejectDirectInternalCall(request, evidence, candidateCode);
        if (!directEntryViolation.isEmpty()) {
            return directEntryViolation;
        }

        String boundaryViolation = rejectWeakBoundaryInput(evidence, candidateCode);
        if (!boundaryViolation.isEmpty()) {
            return boundaryViolation;
        }

        String oracleViolation = rejectWeakOracle(evidence, candidateCode);
        if (!oracleViolation.isEmpty()) {
            return oracleViolation;
        }

        String apiViolation = rejectInventedApiByExactSignature(evidence, candidateCode);
        if (!apiViolation.isEmpty()) {
            return apiViolation;
        }

        String chainViolation = rejectInvalidChainedCalls(evidence, candidateCode);
        if (!chainViolation.isEmpty()) {
            return chainViolation;
        }

        return "";
    }

    private static String rejectDirectInternalCall(Request request, PromptEvidence evidence, String candidateCode) {
        if (!evidence.requiresRealEntryChain()) {
            return "";
        }
        String mutatedMethod = simpleMethodName(request.methodSignature);
        String guidedEntry = guidedEntryName(evidence);
        if (isBlank(mutatedMethod) || isBlank(guidedEntry) || mutatedMethod.equals(guidedEntry)) {
            return "";
        }
        boolean directMutatedCall = containsCall(candidateCode, mutatedMethod);
        boolean guidedEntryCall = containsCall(candidateCode, guidedEntry);
        if (directMutatedCall && !guidedEntryCall) {
            return "Semantic precheck failed: real entry chain required, but generated test bypassed entry '"
                    + guidedEntry + "' and called internal/helper path '" + mutatedMethod + "' directly.";
        }
        return "";
    }

    private static String rejectWeakBoundaryInput(PromptEvidence evidence, String candidateCode) {
        if (!evidence.hasBoundarySensitiveInputGuidance()) {
            return "";
        }
        if (containsOnlyDefaultLiterals(candidateCode) && !mentionsPreferredConcreteInput(evidence, candidateCode)) {
            return "Semantic precheck failed: boundary-sensitive distinguishing input guidance exists, but generated test still uses only default literals.";
        }
        return "";
    }

    private static String rejectWeakOracle(PromptEvidence evidence, String candidateCode) {
        if (evidence == null || !evidence.hasCanonicalObservable()) {
            return "";
        }
        CompilationUnit cu = parseCandidate(candidateCode);
        if (cu == null) {
            return "";
        }

        if (isExceptionOracle(evidence, cu)) {
            return "";
        }

        String observableKind = upper(evidence.canonicalObservableKind());
        String directness = upper(evidence.canonicalObservableDirectness());
        if (observableKind.contains("UNKNOWN") || observableKind.contains("NO_PUBLIC_OBSERVABLE")) {
            return "";
        }

        List<MethodCallExpr> assertions = new ArrayList<MethodCallExpr>();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.startsWith("assert") || "fail".equals(name)) {
                assertions.add(call);
            }
        }
        if (assertions.isEmpty()) {
            return "Semantic precheck failed: CANONICAL_CORE provides a mutation-sensitive observable, but the generated test has no assertion or exception oracle.";
        }

        boolean allTautological = true;
        boolean allNotNull = true;
        boolean allIdentity = true;
        for (MethodCallExpr assertion : assertions) {
            String name = assertion.getNameAsString();
            if (!isTautologicalAssertion(assertion)) {
                allTautological = false;
            }
            if (!"assertNotNull".equals(name)) {
                allNotNull = false;
            }
            if (!"assertSame".equals(name) && !"assertNotSame".equals(name)) {
                allIdentity = false;
            }
        }

        if (allTautological) {
            return "Semantic precheck failed: generated test uses only tautological assertions such as assertTrue(true)/assertFalse(false), which cannot distinguish original from mutant.";
        }

        if (allNotNull && !isNullSensitiveMutation(evidence)) {
            return "Semantic precheck failed: generated test checks only non-nullness, but the mutation is not null-sensitive and CANONICAL_CORE provides a stronger observable.";
        }

        if (allIdentity && isStateOrSideEffectObservable(observableKind, directness)) {
            return "Semantic precheck failed: generated test checks only object identity/returned-this while CANONICAL_CORE requires observing mutation-sensitive state or side effects.";
        }
        return "";
    }

    private static boolean isTautologicalAssertion(MethodCallExpr assertion) {
        if (assertion == null) {
            return false;
        }
        String name = assertion.getNameAsString();
        if ("assertTrue".equals(name)) {
            Expression actual = lastArgument(assertion);
            return actual instanceof BooleanLiteralExpr && ((BooleanLiteralExpr) actual).getValue();
        }
        if ("assertFalse".equals(name)) {
            Expression actual = lastArgument(assertion);
            return actual instanceof BooleanLiteralExpr && !((BooleanLiteralExpr) actual).getValue();
        }
        return false;
    }

    private static Expression lastArgument(MethodCallExpr call) {
        if (call == null || call.getArguments().isEmpty()) {
            return null;
        }
        return call.getArgument(call.getArguments().size() - 1);
    }

    private static boolean isExceptionOracle(PromptEvidence evidence, CompilationUnit cu) {
        String kind = upper(evidence == null ? "" : evidence.canonicalObservableKind());
        JSONObject oracle = evidence == null ? null : evidence.canonicalCore.optJSONObject("oracle");
        String mode = upper(oracle == null ? "" : oracle.optString("assertionMode", ""));
        if (kind.contains("EXCEPTION") || kind.contains("THROW")
                || mode.contains("EXCEPTION") || mode.contains("THROW")) {
            return true;
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if ("assertThrows".equals(call.getNameAsString())) {
                return true;
            }
        }
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (AnnotationExpr annotation : method.getAnnotations()) {
                if (!"Test".equals(annotation.getNameAsString()) || !annotation.isNormalAnnotationExpr()) {
                    continue;
                }
                NormalAnnotationExpr normal = annotation.asNormalAnnotationExpr();
                for (MemberValuePair pair : normal.getPairs()) {
                    if ("expected".equals(pair.getNameAsString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isNullSensitiveMutation(PromptEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        JSONObject infection = evidence.canonicalCore.optJSONObject("infection");
        if (infection == null) {
            return false;
        }
        String combined = lower(infection.optString("localOriginalExpression", "")) + " "
                + lower(infection.optString("localMutantExpression", "")) + " "
                + lower(infection.optString("semanticOriginalExpression", "")) + " "
                + lower(infection.optString("semanticMutantExpression", ""));
        return combined.contains("null") || combined.contains("requireNonNull".toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isStateOrSideEffectObservable(String kind, String directness) {
        String combined = upper(kind) + " " + upper(directness);
        return combined.contains("STATE")
                || combined.contains("SIDE_EFFECT")
                || combined.contains("PARAMETER")
                || combined.contains("FIELD_PROJECTION")
                || combined.contains("AGGREGATE");
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String rejectInventedApiByExactSignature(PromptEvidence evidence, String candidateCode) {
        JSONObject facts = evidence == null ? null : evidence.codeKbCompilableApiFacts();
        if (facts == null || facts.length() == 0) {
            return "";
        }
        String ownerSimpleName = facts.optString("ownerSimpleName", "").trim();
        if (isBlank(ownerSimpleName)) {
            return "";
        }

        List<SignatureSpec> allowedStaticMethods = parseSignatures(facts.optJSONArray("allowedStaticMethods"));
        List<SignatureSpec> allowedConstructors = parseSignatures(facts.optJSONArray("allowedConstructors"));
        List<SignatureSpec> allowedInstanceMethods = parseSignatures(facts.optJSONArray("allowedInstanceMethods"));
        if (allowedInstanceMethods.isEmpty()) {
            allowedInstanceMethods = parseInstanceSignatures(facts.optJSONArray("exactCallableSignatures"), ownerSimpleName);
        }
        List<SignatureSpec> forbiddenCalls = parseSignatures(facts.optJSONArray("forbiddenCalls"));

        for (CallSite callSite : findStaticCalls(candidateCode, ownerSimpleName)) {
            if (matchesSignature(forbiddenCalls, callSite.methodName, callSite.argCount)) {
                return "Semantic precheck failed: generated test calls forbidden signature '"
                        + ownerSimpleName + "." + callSite.methodName + "(" + callSite.argCount
                        + " args)' even though COMPILABLE_API_FACTS marks it as not directly callable.";
            }
            if (!allowedStaticMethods.isEmpty()
                    && !matchesSignature(allowedStaticMethods, callSite.methodName, callSite.argCount)) {
                return "Semantic precheck failed: generated test calls non-existent static factory/method '"
                        + ownerSimpleName + "." + callSite.methodName + "(" + callSite.argCount
                        + " args)' outside COMPILABLE_API_FACTS.allowedStaticMethods.";
            }
        }

        for (CallSite callSite : findConstructorCalls(candidateCode, ownerSimpleName)) {
            if (matchesSignature(forbiddenCalls, ownerSimpleName, callSite.argCount)) {
                return "Semantic precheck failed: generated test calls forbidden constructor 'new "
                        + ownerSimpleName + "(" + callSite.argCount
                        + " args)' even though COMPILABLE_API_FACTS marks it as not directly callable.";
            }
            if (!allowedConstructors.isEmpty()
                    && !matchesSignature(allowedConstructors, ownerSimpleName, callSite.argCount)) {
                return "Semantic precheck failed: generated test calls non-existent/inaccessible constructor 'new "
                        + ownerSimpleName + "(" + callSite.argCount
                        + " args)' outside COMPILABLE_API_FACTS.allowedConstructors.";
            }
        }
        return "";
    }

    private static String rejectInvalidChainedCalls(PromptEvidence evidence, String candidateCode) {
        JSONObject facts = evidence == null ? null : evidence.codeKbCompilableApiFacts();
        if (facts == null || facts.length() == 0) {
            return "";
        }
        String ownerSimpleName = facts.optString("ownerSimpleName", "").trim();
        if (isBlank(ownerSimpleName)) {
            return "";
        }

        List<SignatureSpec> allowedStaticMethods = parseSignatures(facts.optJSONArray("allowedStaticMethods"));
        List<SignatureSpec> allowedInstanceMethods = parseSignatures(facts.optJSONArray("allowedInstanceMethods"));
        if (allowedInstanceMethods.isEmpty()) {
            allowedInstanceMethods = parseInstanceSignatures(facts.optJSONArray("exactCallableSignatures"), ownerSimpleName);
        }

        String exactChainViolation = rejectChainedCallsForOwner(candidateCode, ownerSimpleName, allowedStaticMethods,
                allowedInstanceMethods);
        if (!exactChainViolation.isEmpty()) {
            return exactChainViolation;
        }
        return "";
    }

    private static String rejectChainedCallsForOwner(String code,
            String ownerSimpleName,
            List<SignatureSpec> allowedStaticMethods,
            List<SignatureSpec> allowedInstanceMethods) {
        if (isBlank(code) || isBlank(ownerSimpleName)) {
            return "";
        }

        CompilationUnit cu = parseCandidate(code);
        if (cu == null) {
            // A syntactically invalid candidate will be handled by javac/repair. Do not
            // invent Java call structure with regex when JavaParser cannot parse it.
            return "";
        }

        for (MethodCallExpr secondCall : cu.findAll(MethodCallExpr.class)) {
            if (!secondCall.getScope().isPresent()) {
                continue;
            }
            Expression scope = unwrap(secondCall.getScope().get());
            if (!(scope instanceof MethodCallExpr)) {
                continue;
            }

            MethodCallExpr firstCall = (MethodCallExpr) scope;
            if (!isStaticOwnerCall(firstCall, ownerSimpleName)) {
                continue;
            }

            String firstMethod = firstCall.getNameAsString();
            int firstArgCount = firstCall.getArguments().size();
            if (!allowedStaticMethods.isEmpty()
                    && !matchesSignature(allowedStaticMethods, firstMethod, firstArgCount)) {
                continue;
            }

            String returnType = resolveReturnType(allowedStaticMethods, firstMethod, firstArgCount);
            if (isBlank(returnType) || !returnsOwnerType(returnType, ownerSimpleName)) {
                continue;
            }

            String secondMethod = secondCall.getNameAsString();
            int secondArgCount = secondCall.getArguments().size();
            if (matchesSignature(allowedInstanceMethods, secondMethod, secondArgCount)) {
                continue;
            }
            if (matchesAnyMethodName(allowedInstanceMethods, secondMethod)) {
                // Same-name overload exists but exact type resolution is unavailable here.
                // Treat it as UNKNOWN rather than rejecting a potentially valid overload.
                continue;
            }

            return "Semantic precheck failed: generated test chains factory '" + ownerSimpleName + "."
                    + firstMethod + "(...)' into non-existent instance method '" + secondMethod
                    + "(...)' on the returned owner type.";
        }
        return "";
    }

    private static boolean matchesAnyMethodName(List<SignatureSpec> specs, String methodName) {
        if (specs == null || specs.isEmpty() || isBlank(methodName)) {
            return false;
        }
        for (SignatureSpec spec : specs) {
            if (spec.methodName.equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveReturnType(List<SignatureSpec> specs, String methodName, int argCount) {
        if (specs == null || specs.isEmpty() || isBlank(methodName)) {
            return "";
        }
        for (SignatureSpec spec : specs) {
            if (spec.methodName.equals(methodName) && spec.argCount == argCount) {
                return spec.returnType;
            }
        }
        return "";
    }

    private static boolean returnsOwnerType(String returnType, String ownerSimpleName) {
        if (isBlank(returnType) || isBlank(ownerSimpleName)) {
            return false;
        }
        String normalized = returnType.trim();
        if (normalized.equals(ownerSimpleName)) {
            return true;
        }
        if (normalized.endsWith("." + ownerSimpleName)) {
            return true;
        }
        return normalized.endsWith("$" + ownerSimpleName);
    }

    private static String guidedEntryName(PromptEvidence evidence) {
        String candidate = simpleMethodName(evidence.codeKbTopEntrySignature());
        if (!isBlank(candidate)) {
            return candidate;
        }
        candidate = simpleMethodName(evidence.entry.optString("entryMethodSignature", ""));
        if (!isBlank(candidate)) {
            return candidate;
        }
        return simpleMethodName(evidence.entry.optString("entryMethodName", ""));
    }

    private static boolean mentionsPreferredConcreteInput(PromptEvidence evidence, String candidateCode) {
        JSONArray arr = evidence.distinguishingInputGuidance.optJSONArray("preferredConcreteInputs");
        if (arr == null) {
            return false;
        }
        for (int i = 0; i < arr.length(); i++) {
            String value = String.valueOf(arr.opt(i));
            if (!isBlank(value) && candidateCode.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOnlyDefaultLiterals(String candidateCode) {
        CompilationUnit cu = parseCandidate(candidateCode);
        if (cu == null) {
            return false;
        }
        boolean sawLiteral = false;
        for (LiteralExpr literal : cu.findAll(LiteralExpr.class)) {
            sawLiteral = true;
            if (!isDefaultLiteralExpression(literal)) {
                return false;
            }
        }
        return sawLiteral;
    }

    private static boolean isDefaultLiteralExpression(LiteralExpr literal) {
        if (literal == null) {
            return false;
        }
        if (literal instanceof NullLiteralExpr || literal instanceof BooleanLiteralExpr) {
            return true;
        }
        if (literal instanceof StringLiteralExpr) {
            String value = ((StringLiteralExpr) literal).asString();
            return value.isEmpty() || " ".equals(value);
        }
        if (literal instanceof CharLiteralExpr) {
            String value = ((CharLiteralExpr) literal).getValue();
            return "\\0".equals(value) || " ".equals(value) || "\u0000".equals(value);
        }
        if (literal instanceof IntegerLiteralExpr) {
            String value = ((IntegerLiteralExpr) literal).getValue().replace("_", "");
            return "0".equals(value) || "1".equals(value);
        }
        if (literal instanceof LongLiteralExpr) {
            String value = ((LongLiteralExpr) literal).getValue().replace("_", "");
            if (value.endsWith("l") || value.endsWith("L")) {
                value = value.substring(0, value.length() - 1);
            }
            return "0".equals(value) || "1".equals(value);
        }
        if (literal instanceof DoubleLiteralExpr) {
            String value = ((DoubleLiteralExpr) literal).getValue().replace("_", "").toLowerCase();
            if (value.endsWith("d") || value.endsWith("f")) {
                value = value.substring(0, value.length() - 1);
            }
            return "0".equals(value) || "0.0".equals(value)
                    || "1".equals(value) || "1.0".equals(value);
        }
        return false;
    }

    private static boolean containsCall(String candidateCode, String methodName) {
        if (isBlank(methodName)) {
            return false;
        }
        CompilationUnit cu = parseCandidate(candidateCode);
        if (cu == null) {
            return false;
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (methodName.equals(call.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String simpleMethodName(String signature) {
        if (isBlank(signature)) {
            return "";
        }
        String trimmed = signature.trim();
        int paren = trimmed.indexOf('(');
        if (paren >= 0) {
            trimmed = trimmed.substring(0, paren);
        }
        int hash = trimmed.lastIndexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(hash + 1);
        }
        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0) {
            trimmed = trimmed.substring(dot + 1);
        }
        return trimmed.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static List<SignatureSpec> parseSignatures(JSONArray arr) {
        List<SignatureSpec> out = new ArrayList<SignatureSpec>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            SignatureSpec spec = SignatureSpec.parse(String.valueOf(arr.opt(i)));
            if (spec != null) {
                out.add(spec);
            }
        }
        return out;
    }

    private static List<SignatureSpec> parseInstanceSignatures(JSONArray arr, String ownerSimpleName) {
        List<SignatureSpec> out = new ArrayList<SignatureSpec>();
        if (arr == null || isBlank(ownerSimpleName)) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            SignatureSpec spec = SignatureSpec.parse(String.valueOf(arr.opt(i)));
            if (spec != null && returnsOwnerType(spec.returnType, ownerSimpleName)) {
                out.add(spec);
            }
        }
        return out;
    }

    private static boolean matchesSignature(List<SignatureSpec> specs, String methodName, int argCount) {
        for (SignatureSpec spec : specs) {
            if (spec.matches(methodName, argCount)) {
                return true;
            }
        }
        return false;
    }

    private static List<CallSite> findStaticCalls(String code, String ownerSimpleName) {
        List<CallSite> out = new ArrayList<CallSite>();
        CompilationUnit cu = parseCandidate(code);
        if (cu == null) {
            return out;
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!isStaticOwnerCall(call, ownerSimpleName)) {
                continue;
            }
            out.add(new CallSite(call.getNameAsString(), call.getArguments().size()));
        }
        return out;
    }

    private static List<CallSite> findConstructorCalls(String code, String ownerSimpleName) {
        List<CallSite> out = new ArrayList<CallSite>();
        CompilationUnit cu = parseCandidate(code);
        if (cu == null) {
            return out;
        }
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            String simpleName = creation.getType().getNameAsString();
            if (ownerSimpleName.equals(simpleName)) {
                out.add(new CallSite(ownerSimpleName, creation.getArguments().size()));
            }
        }
        return out;
    }

    private static CompilationUnit parseCandidate(String code) {
        if (isBlank(code)) {
            return null;
        }
        try {
            return StaticJavaParser.parse(code);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isStaticOwnerCall(MethodCallExpr call, String ownerSimpleName) {
        if (call == null || isBlank(ownerSimpleName) || !call.getScope().isPresent()) {
            return false;
        }
        Expression scope = unwrap(call.getScope().get());
        return scope instanceof NameExpr
                && ownerSimpleName.equals(((NameExpr) scope).getNameAsString());
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static final class CallSite {
        private final String methodName;
        private final int argCount;

        private CallSite(String methodName, int argCount) {
            this.methodName = methodName;
            this.argCount = argCount;
        }
    }

    private static final class SignatureSpec {
        private final String methodName;
        private final int argCount;
        private final String returnType;

        private SignatureSpec(String methodName, int argCount, String returnType) {
            this.methodName = methodName;
            this.argCount = argCount;
            this.returnType = returnType == null ? "" : returnType.trim();
        }

        private boolean matches(String candidateMethod, int candidateArgCount) {
            return methodName.equals(candidateMethod) && argCount == candidateArgCount;
        }

        private static SignatureSpec parse(String text) {
            if (isBlank(text)) {
                return null;
            }
            String value = text.trim();
            int lp = value.indexOf('(');
            int rp = value.lastIndexOf(')');
            if (lp <= 0 || rp < lp) {
                return null;
            }
            String head = value.substring(0, lp).trim();
            String args = value.substring(lp + 1, rp).trim();
            int split = lastWhitespace(head);
            String methodName;
            String returnType;
            if (split < 0) {
                methodName = simpleName(head);
                returnType = head;
            } else {
                methodName = head.substring(split + 1).trim();
                returnType = lastTypeToken(head.substring(0, split).trim());
            }
            if (isBlank(methodName)) {
                return null;
            }
            return new SignatureSpec(methodName, countTopLevelArguments(args), returnType);
        }

        private static int lastWhitespace(String value) {
            for (int i = value.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(value.charAt(i))) {
                    return i;
                }
            }
            return -1;
        }

        private static String lastTypeToken(String value) {
            if (isBlank(value)) {
                return "";
            }
            int split = lastWhitespace(value);
            return split < 0 ? value.trim() : value.substring(split + 1).trim();
        }

        private static String simpleName(String value) {
            String text = value == null ? "" : value.trim();
            int dot = Math.max(text.lastIndexOf('.'), text.lastIndexOf('$'));
            return dot >= 0 ? text.substring(dot + 1) : text;
        }

        private static int countTopLevelArguments(String args) {
            if (isBlank(args)) {
                return 0;
            }
            int count = 1;
            int genericDepth = 0;
            int arrayDepth = 0;
            int parenDepth = 0;
            for (int i = 0; i < args.length(); i++) {
                char ch = args.charAt(i);
                if (ch == '<') {
                    genericDepth++;
                } else if (ch == '>') {
                    genericDepth = Math.max(0, genericDepth - 1);
                } else if (ch == '[') {
                    arrayDepth++;
                } else if (ch == ']') {
                    arrayDepth = Math.max(0, arrayDepth - 1);
                } else if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')') {
                    parenDepth = Math.max(0, parenDepth - 1);
                } else if (ch == ',' && genericDepth == 0 && arrayDepth == 0 && parenDepth == 0) {
                    count++;
                }
            }
            return count;
        }
    }

}
