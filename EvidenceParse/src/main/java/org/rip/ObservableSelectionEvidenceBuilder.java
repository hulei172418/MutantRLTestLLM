package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Adds a non-destructive observable recommendation layer.
 *
 * The existing observablePlan in output.json remains unchanged. This builder only
 * emits a stronger observable recommendation when static analysis can confidently
 * find a public state/behavior sink that is more mutation-sensitive than the
 * current baseline observable.
 */
public final class ObservableSelectionEvidenceBuilder {

    private ObservableSelectionEvidenceBuilder() {
    }

    public static final class Context {
        public String originJavaFile = "";
        public String entryClassName = "";
        public String entryMethodSig = "";
        public String mutationClassName = "";
        public String mutationMethodSig = "";
        public String diff = "";
        public String observablePlanKind = "";
        public String observableCall = "";
    }

    public static Map<String, Object> buildObservableSelectionPlan(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            Analysis analysis = analyze(ctx);
            out.put("baselineObservableKind", commented(
                    "Observable kind currently emitted by the baseline resolver",
                    safe(ctx.observablePlanKind)));
            out.put("baselineObservableCall", commented(
                    "Observable call currently emitted by the baseline resolver",
                    safe(ctx.observableCall)));
            out.put("preferredObservableKind", commented(
                    "Recommended stronger observable kind when a field-sensitive public sink is available",
                    analysis.preferredKind));
            out.put("preferredObservableCall", commented(
                    "Recommended stronger observable call when a field-sensitive public sink is available",
                    analysis.preferredCall));
            out.put("overrideRecommended", commented(
                    "Whether downstream prompt construction should prefer the recommended observable over the baseline one",
                    analysis.overrideRecommended));
            out.put("overrideReason", commented(
                    "Why overriding the baseline observable is or is not recommended",
                    analysis.reason));
            out.put("supportingFields", commentedItems(
                    "Mutation-related fields that support this recommendation",
                    analysis.supportingFields));
            out.put("observableDirectness", commented(
                    "How directly the preferred observable exposes the mutation-sensitive value",
                    analysis.directness));
            out.put("observableDirectnessReason", commented(
                    "AST evidence used to classify observable directness",
                    analysis.directnessReason));
            return commented(
                    "Non-destructive observable recommendation layered on top of the existing observablePlan",
                    out);
        } catch (Throwable t) {
            out.put("enabled", commented("Whether observable-selection strengthening ran successfully", false));
            out.put("reason", commented("Construction error",
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
            return commented(
                    "Non-destructive observable recommendation layered on top of the existing observablePlan",
                    out);
        }
    }

    private static Analysis analyze(Context ctx) throws Exception {
        Analysis out = new Analysis();
        out.preferredKind = safe(ctx.observablePlanKind);
        out.preferredCall = safe(ctx.observableCall);
        out.reason = "Keep the baseline observable; no stronger field-sensitive public sink was identified.";

        if (!isUpgradeableBaseline(ctx.observablePlanKind)) {
            out.reason = "Keep the baseline observable because it is not a weak return-only or missing-observable case.";
            return out;
        }

        CompilationUnit cu = parse(ctx.originJavaFile);
        ClassOrInterfaceDeclaration owner = findClass(cu, simpleType(ctx.entryClassName))
                .orElseThrow(() -> new IllegalArgumentException("Entry owner not found: " + ctx.entryClassName));
        CallableDeclaration<?> entry = findCallable(owner, ctx.entryMethodSig)
                .orElseThrow(() -> new IllegalArgumentException("Entry callable not found: " + ctx.entryMethodSig));
        CallableDeclaration<?> mutation = findCallable(owner, ctx.mutationMethodSig)
                .orElseThrow(() -> new IllegalArgumentException("Mutation callable not found: " + ctx.mutationMethodSig));

        if (isConstructorExceptionMutation(ctx, entry, mutation)) {
            out.reason = "Keep the baseline observable override disabled because this mutant is better observed as constructor-completes-vs-IllegalArgumentException, not as a getter/state observable.";
            return out;
        }

        if ("ENTRY_RETURN_VALUE".equalsIgnoreCase(safe(ctx.observablePlanKind))
                && mutationDirectlyFlowsToReturn(mutation, ctx.diff)) {
            out.overrideRecommended = false;
            out.reason = "Keep the entry return value because the mutated expression directly contributes to a return expression.";
            return out;
        }

        LinkedHashSet<String> mutationFields = inferMutationWrittenFields(owner, entry, mutation);
        // Statement-level mutations may alter state through method calls without
        // an explicit assignment in the current method. Include owner fields
        // referenced by the mutation as effect carriers; observer selection still
        // requires an AST-confirmed read of those fields.
        mutationFields.addAll(inferMutationRelevantFields(owner, ctx, entry));
        out.supportingFields.addAll(mutationFields);

        ObserverCandidate best = findBestObserver(owner, mutationFields, entry, mutation);
        if (best == null) {
            ParameterStateCandidate parameterObserver = findParameterStateObserver(entry);
            if (parameterObserver != null) {
                out.preferredKind = "PARAMETER_STATE_OBSERVABLE";
                out.preferredCall = parameterObserver.observableCall;
                out.overrideRecommended = true;
                out.directness = "PARAMETER_SIDE_EFFECT";
                out.directnessReason = parameterObserver.reason;
                out.reason = "Prefer a mutable parameter state observable because no public owner-state observer was found and the entry exposes a mutable side-effect sink.";
                return out;
            }
            out.reason = mutationFields.isEmpty()
                    ? "Keep the baseline observable because no mutation-related state carrier or mutable parameter sink was identified."
                    : "Keep the baseline observable because no public observer with AST-confirmed reads of mutation-related fields was found.";
            return out;
        }

        out.preferredKind = best.directness.startsWith("DIRECT")
                ? "PUBLIC_GETTER_DEPENDS_ON_MUTATED_STATE"
                : "PUBLIC_METHOD_DEPENDS_ON_MUTATED_STATE";
        out.preferredCall = buildObserverCall(best.method);
        out.overrideRecommended = true;
        out.directness = best.directness;
        out.directnessReason = best.reason;
        out.reason = "Prefer a state-sensitive public observable over the baseline "
                + safe(ctx.observablePlanKind) + " because method " + best.method.getNameAsString()
                + " reads mutation-related field(s) " + best.readFields + ".";
        return out;
    }

    private static CompilationUnit parse(String javaFile) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
        JavaParser parser = new JavaParser(cfg);
        com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(Paths.get(javaFile));
        if (!result.getResult().isPresent()) {
            throw new IllegalArgumentException("Parse failed: " + result.getProblems());
        }
        return result.getResult().get();
    }

    private static boolean isUpgradeableBaseline(String baselineKind) {
        String kind = safe(baselineKind).toUpperCase(Locale.ROOT);
        return "ENTRY_RETURN_VALUE".equals(kind)
                || "NO_PUBLIC_OBSERVABLE".equals(kind)
                || "UNKNOWN_OBSERVABLE".equals(kind)
                || "METHOD_COMPLETION".equals(kind)
                || "ENTRY_METHOD_COMPLETION".equals(kind)
                || "VOID_METHOD_COMPLETION".equals(kind)
                || "CONSTRUCTOR_PUBLIC_METHOD_DEPENDS_ON_STATE".equals(kind);
    }

    private static LinkedHashSet<String> inferMutationRelevantFields(ClassOrInterfaceDeclaration owner,
                                                                     Context ctx,
                                                                     CallableDeclaration<?> entry) {
        LinkedHashSet<String> fields = new LinkedHashSet<String>();
        Set<String> diffTokens = extractIdentifiers(ctx.diff);
        Optional<CallableDeclaration<?>> mutation = findCallable(owner, ctx.mutationMethodSig);
        if (mutation.isPresent()) {
            fields.addAll(fieldsReferencedBy(owner, mutation.get(), diffTokens));
            fields.addAll(fieldsAssignedFromMutationCall(owner, entry, mutation.get()));
        }

        for (FieldDeclaration fd : owner.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                String field = v.getNameAsString();
                if (diffTokens.contains(field)) {
                    fields.add(field);
                }
            }
        }

        if (!fields.isEmpty()) {
            return fields;
        }

        fields.addAll(fieldsReadBy(owner, entry));
        return fields;
    }

    private static LinkedHashSet<String> fieldsReferencedBy(ClassOrInterfaceDeclaration owner,
                                                            CallableDeclaration<?> callable,
                                                            Set<String> diffTokens) {
        LinkedHashSet<String> fields = new LinkedHashSet<String>();
        fields.addAll(fieldsReadBy(owner, callable));
        fields.retainAll(ownerFieldNames(owner));
        fields.addAll(intersection(ownerFieldNames(owner), diffTokens));
        return fields;
    }

    private static LinkedHashSet<String> fieldsAssignedFromMutationCall(ClassOrInterfaceDeclaration owner,
                                                                        CallableDeclaration<?> entry,
                                                                        CallableDeclaration<?> mutation) {
        LinkedHashSet<String> fields = new LinkedHashSet<String>();
        String mutationName = mutation.getNameAsString();
        Set<String> ownerFields = owner.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (AssignExpr assign : entry.findAll(AssignExpr.class)) {
            Expression value = assign.getValue();
            if (!value.isMethodCallExpr()) {
                continue;
            }
            MethodCallExpr call = value.asMethodCallExpr();
            if (!mutationName.equals(call.getNameAsString())) {
                continue;
            }
            String target = assignedFieldName(assign.getTarget());
            if (ownerFields.contains(target)) {
                fields.add(target);
            }
        }
        return fields;
    }

    private static String assignedFieldName(Expression target) {
        if (target == null) {
            return "";
        }
        if (target.isFieldAccessExpr()) {
            FieldAccessExpr field = target.asFieldAccessExpr();
            return field.getNameAsString();
        }
        if (target.isNameExpr()) {
            NameExpr name = target.asNameExpr();
            return name.getNameAsString();
        }
        return "";
    }

    private static String diffLeft(String diff) {
        int idx = safe(diff).indexOf("=>");
        return idx < 0 ? safe(diff) : diff.substring(0, idx).trim();
    }

    private static String diffRight(String diff) {
        int idx = safe(diff).indexOf("=>");
        return idx < 0 ? "" : diff.substring(idx + 2).trim();
    }


    private static ObserverCandidate findBestObserver(ClassOrInterfaceDeclaration owner,
                                                      Set<String> fields,
                                                      CallableDeclaration<?> entry,
                                                      CallableDeclaration<?> mutation) {
        ObserverCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (MethodDeclaration method : owner.getMethods()) {
            if (sameCallable(method, entry) || sameCallable(method, mutation)) {
                continue;
            }
            if (method.isPrivate() || method.isStatic() || "void".equals(method.getType().asString())
                    || method.getParameters().size() > 2) {
                continue;
            }
            ObserverCandidate candidate = classifyObserver(method, fields);
            int score = scoreObserver(candidate);
            if (score <= 0) {
                continue;
            }
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean mutationDirectlyFlowsToReturn(CallableDeclaration<?> mutation, String diff) {
        if (!(mutation instanceof MethodDeclaration)) {
            return false;
        }
        MethodDeclaration method = (MethodDeclaration) mutation;
        if (method.getType().isVoidType()) {
            return false;
        }
        Expression left = parseExpressionOrNull(diffLeft(diff));
        Expression right = parseExpressionOrNull(diffRight(diff));
        for (ReturnStmt stmt : method.findAll(ReturnStmt.class)) {
            if (!stmt.getExpression().isPresent()) {
                continue;
            }
            Expression returned = stmt.getExpression().get();
            if ((left != null && containsEquivalentExpression(returned, left))
                    || (right != null && containsEquivalentExpression(returned, right))) {
                return true;
            }
        }
        return false;
    }

    private static Expression parseExpressionOrNull(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        try {
            return unwrapExpression(StaticJavaParser.parseExpression(source.trim()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean containsEquivalentExpression(Expression container, Expression target) {
        if (container == null || target == null) {
            return false;
        }
        if (equivalentExpression(container, target)) {
            return true;
        }
        for (Expression nested : container.findAll(Expression.class)) {
            if (nested != container && equivalentExpression(nested, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equivalentExpression(Expression left, Expression right) {
        if (left == null || right == null) {
            return false;
        }
        Expression a = unwrapExpression(left);
        Expression b = unwrapExpression(right);
        return a.equals(b) || removeWhitespace(a.toString()).equals(removeWhitespace(b.toString()));
    }

    private static Expression unwrapExpression(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static String removeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static LinkedHashSet<String> inferMutationWrittenFields(ClassOrInterfaceDeclaration owner,
                                                                    CallableDeclaration<?> entry,
                                                                    CallableDeclaration<?> mutation) {
        LinkedHashSet<String> ownerFields = owner.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> written = new LinkedHashSet<String>();
        collectAssignedFields(mutation, ownerFields, written);
        if (!sameCallable(entry, mutation)) {
            collectAssignedFields(entry, ownerFields, written);
        }
        written.addAll(fieldsAssignedFromMutationCall(owner, entry, mutation));
        return written;
    }

    private static void collectAssignedFields(CallableDeclaration<?> callable,
                                             Set<String> ownerFields,
                                             Set<String> out) {
        if (callable == null) {
            return;
        }
        for (AssignExpr assign : callable.findAll(AssignExpr.class)) {
            String field = assignedOwnerFieldName(assign.getTarget(), ownerFields, callable);
            if (ownerFields.contains(field)) {
                out.add(field);
            }
        }
        for (UnaryExpr unary : callable.findAll(UnaryExpr.class)) {
            UnaryExpr.Operator op = unary.getOperator();
            if (op != UnaryExpr.Operator.POSTFIX_INCREMENT
                    && op != UnaryExpr.Operator.POSTFIX_DECREMENT
                    && op != UnaryExpr.Operator.PREFIX_INCREMENT
                    && op != UnaryExpr.Operator.PREFIX_DECREMENT) {
                continue;
            }
            String field = assignedOwnerFieldName(unary.getExpression(), ownerFields, callable);
            if (ownerFields.contains(field)) {
                out.add(field);
            }
        }
    }

    private static boolean sameCallable(CallableDeclaration<?> a, CallableDeclaration<?> b) {
        if (a == null || b == null) {
            return false;
        }
        return normalizeCallableSignature(callableSignatureForMatch(a))
                .equals(normalizeCallableSignature(callableSignatureForMatch(b)));
    }

    private static int scoreObserver(ObserverCandidate candidate) {
        if (candidate == null || candidate.method == null) {
            return 0;
        }
        MethodDeclaration method = candidate.method;
        int score = observableDirectnessRank(candidate.directness);
        if (score <= 0) {
            return 0;
        }

        // Directness is the primary ordering criterion. Convenience only breaks
        // ties inside the same semantic directness class.
        if (method.isPublic()) {
            score += 30;
        } else if (method.isProtected()) {
            score += 10;
        }
        if (method.getParameters().isEmpty()) {
            score += 20;
        } else if (method.getParameters().size() == 1) {
            score += 5;
        }
        if (candidate.readFields.size() == 1) {
            score += 10;
        }
        return score;
    }

    private static int observableDirectnessRank(String directness) {
        String value = safe(directness).toUpperCase(Locale.ROOT);
        if ("DIRECT_RETURN_FIELD".equals(value)) {
            return 500;
        }
        if ("DIRECT_FIELD_PROJECTION".equals(value)) {
            return 400;
        }
        if ("SIMPLE_STATE_OBSERVER".equals(value)) {
            return 300;
        }
        if ("MULTI_FIELD_AGGREGATE".equals(value)) {
            return 200;
        }
        return 0;
    }

    private static ObserverCandidate classifyObserver(MethodDeclaration method, Set<String> mutationFields) {
        LinkedHashSet<String> readFields = fieldsReadBy(method, mutationFields);
        readFields.retainAll(mutationFields);
        ObserverCandidate candidate = new ObserverCandidate(method, readFields);
        if (readFields.isEmpty()) {
            candidate.directness = "NO_MUTATED_FIELD_READ";
            candidate.reason = "Method has no AST-confirmed read of mutation-related fields.";
            return candidate;
        }

        for (ReturnStmt stmt : method.findAll(ReturnStmt.class)) {
            if (!stmt.getExpression().isPresent()) {
                continue;
            }
            Expression expr = stmt.getExpression().get();
            LinkedHashSet<String> returnReads = fieldsReadBy(expr, mutationFields, shadowedNames(method));
            returnReads.retainAll(mutationFields);
            if (returnReads.isEmpty()) {
                continue;
            }
            if (isSingleFieldExpression(expr, returnReads)) {
                candidate.directness = "DIRECT_RETURN_FIELD";
                candidate.reason = "Return expression directly returns mutation-related field(s) " + returnReads + ".";
                return candidate;
            }
            if (returnReads.size() == 1) {
                candidate.directness = "DIRECT_FIELD_PROJECTION";
                candidate.reason = "Return expression is a direct projection derived from mutation-related field "
                        + returnReads.iterator().next() + ".";
                return candidate;
            }
            candidate.directness = "MULTI_FIELD_AGGREGATE";
            candidate.reason = "Return expression aggregates mutation-related fields " + returnReads + ".";
            return candidate;
        }

        candidate.directness = readFields.size() == 1 ? "SIMPLE_STATE_OBSERVER" : "MULTI_FIELD_AGGREGATE";
        candidate.reason = "Method body reads mutation-related field(s) " + readFields
                + " but the return expression is not a direct field projection.";
        return candidate;
    }

    private static boolean isSingleFieldExpression(Expression expr, Set<String> fields) {
        if (expr == null || fields == null || fields.size() != 1) {
            return false;
        }
        String field = fields.iterator().next();
        if (expr.isNameExpr()) {
            return field.equals(expr.asNameExpr().getNameAsString());
        }
        if (expr.isFieldAccessExpr()) {
            return field.equals(expr.asFieldAccessExpr().getNameAsString());
        }
        return false;
    }

    private static LinkedHashSet<String> fieldsReadBy(ClassOrInterfaceDeclaration owner,
                                                      CallableDeclaration<?> callable) {
        return fieldsReadBy(callable, ownerFieldNames(owner));
    }

    private static LinkedHashSet<String> fieldsReadBy(CallableDeclaration<?> callable,
                                                      Set<String> ownerFields) {
        if (callable == null) {
            return new LinkedHashSet<String>();
        }
        return fieldsReadBy(callable, ownerFields, shadowedNames(callable));
    }

    private static LinkedHashSet<String> fieldsReadBy(com.github.javaparser.ast.Node node,
                                                      Set<String> ownerFields,
                                                      Set<String> shadowed) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (node == null || ownerFields == null || ownerFields.isEmpty()) {
            return out;
        }
        for (FieldAccessExpr access : node.findAll(FieldAccessExpr.class)) {
            String name = access.getNameAsString();
            if (ownerFields.contains(name) && isOwnerScope(access.getScope())) {
                out.add(name);
            }
        }
        for (NameExpr name : node.findAll(NameExpr.class)) {
            String value = name.getNameAsString();
            if (ownerFields.contains(value) && !shadowed.contains(value) && !isWriteOnlyTarget(name)) {
                out.add(value);
            }
        }
        return out;
    }

    private static boolean isOwnerScope(Expression scope) {
        if (scope == null) {
            return false;
        }
        return scope.isThisExpr() || scope.isSuperExpr();
    }

    private static boolean isWriteOnlyTarget(NameExpr name) {
        Optional<com.github.javaparser.ast.Node> parent = name.getParentNode();
        if (!parent.isPresent()) {
            return false;
        }
        com.github.javaparser.ast.Node p = parent.get();
        if (p instanceof AssignExpr) {
            return ((AssignExpr) p).getTarget() == name;
        }
        if (p instanceof UnaryExpr) {
            UnaryExpr.Operator op = ((UnaryExpr) p).getOperator();
            return op == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || op == UnaryExpr.Operator.POSTFIX_DECREMENT
                    || op == UnaryExpr.Operator.PREFIX_INCREMENT
                    || op == UnaryExpr.Operator.PREFIX_DECREMENT;
        }
        return false;
    }

    private static String assignedOwnerFieldName(Expression target,
                                                Set<String> ownerFields,
                                                CallableDeclaration<?> callable) {
        String field = assignedFieldName(target);
        if (!ownerFields.contains(field)) {
            return "";
        }
        if (target != null && target.isNameExpr() && shadowedNames(callable).contains(field)) {
            return "";
        }
        if (target != null && target.isFieldAccessExpr() && !isOwnerScope(target.asFieldAccessExpr().getScope())) {
            return "";
        }
        return field;
    }

    private static LinkedHashSet<String> ownerFieldNames(ClassOrInterfaceDeclaration owner) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (owner == null) {
            return out;
        }
        for (FieldDeclaration fd : owner.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                out.add(v.getNameAsString());
            }
        }
        return out;
    }

    private static LinkedHashSet<String> shadowedNames(CallableDeclaration<?> callable) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (callable == null) {
            return out;
        }
        for (Parameter parameter : callable.getParameters()) {
            out.add(parameter.getNameAsString());
        }
        for (VariableDeclarator local : callable.findAll(VariableDeclarator.class)) {
            out.add(local.getNameAsString());
        }
        return out;
    }

    private static LinkedHashSet<String> intersection(Set<String> left, Set<String> right) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (left == null || right == null) {
            return out;
        }
        for (String value : left) {
            if (right.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private static String buildObserverCall(MethodDeclaration method) {
        String args = method.getParameters().stream()
                .map(p -> exampleValueForType(p.getType().asString(), p.getNameAsString()))
                .collect(Collectors.joining(", "));
        String call = "subject." + method.getNameAsString() + "(" + args + ")";
        String returnType = CallableSignatureNormalizer.simpleType(method.getType().asString());
        if ("StringBuffer".equals(returnType) || "StringBuilder".equals(returnType)
                || "CharSequence".equals(returnType)) {
            return "String result = " + call + ".toString();";
        }
        return method.getType().asString() + " result = " + call + ";";
    }

    private static ParameterStateCandidate findParameterStateObserver(CallableDeclaration<?> entry) {
        if (entry == null) {
            return null;
        }
        for (Parameter parameter : entry.getParameters()) {
            if (!isMutableStateType(parameter.getType().asString())) {
                continue;
            }
            String name = parameter.getNameAsString();
            if (!parameterParticipatesInCalls(entry, name)) {
                continue;
            }
            String call = parameterObservableCall(parameter);
            if (!call.isEmpty()) {
                return new ParameterStateCandidate(call,
                        "Entry parameter '" + name + "' is a mutable reference used by method calls in the mutation path; observe its post-state instead of successful completion only.");
            }
        }
        return null;
    }

    private static boolean parameterParticipatesInCalls(CallableDeclaration<?> entry, String parameterName) {
        for (MethodCallExpr call : entry.findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent() && call.getScope().get().isNameExpr()
                    && parameterName.equals(call.getScope().get().asNameExpr().getNameAsString())) {
                return true;
            }
            for (Expression argument : call.getArguments()) {
                if (argument.isNameExpr() && parameterName.equals(argument.asNameExpr().getNameAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMutableStateType(String rawType) {
        String type = CallableSignatureNormalizer.simpleType(rawType);
        if (type.endsWith("[]")) {
            return true;
        }
        return "StringBuffer".equals(type)
                || "StringBuilder".equals(type)
                || "Collection".equals(type)
                || "List".equals(type)
                || "Set".equals(type)
                || "Map".equals(type)
                || "Queue".equals(type)
                || "Deque".equals(type)
                || "Writer".equals(type)
                || "OutputStream".equals(type)
                || "ByteBuffer".equals(type);
    }

    private static String parameterObservableCall(Parameter parameter) {
        String name = parameter.getNameAsString();
        String type = CallableSignatureNormalizer.simpleType(parameter.getType().asString());
        if (type.endsWith("[]")) {
            return "String result = java.util.Arrays.deepToString(new Object[] { " + name + " });";
        }
        if ("StringBuffer".equals(type) || "StringBuilder".equals(type) || "CharSequence".equals(type)) {
            return "String result = " + name + ".toString();";
        }
        if ("Collection".equals(type) || "List".equals(type) || "Set".equals(type)
                || "Map".equals(type) || "Queue".equals(type) || "Deque".equals(type)) {
            return "String result = String.valueOf(" + name + ");";
        }
        return "";
    }

    private static boolean isGetterLike(MethodDeclaration method) {
        if (method == null || !method.getParameters().isEmpty()) {
            return false;
        }
        String name = method.getNameAsString();
        return name.startsWith("get") || name.startsWith("is") || "toString".equals(name);
    }

    private static String fieldNameFromGetter(String getter) {
        if (getter == null || getter.isEmpty()) {
            return "";
        }
        if (getter.startsWith("get") && getter.length() > 3) {
            return decapitalize(getter.substring(3));
        }
        if (getter.startsWith("is") && getter.length() > 2) {
            return decapitalize(getter.substring(2));
        }
        return "";
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static Set<String> extractIdentifiers(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (text == null) {
            return out;
        }
        StringBuilder token = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isJavaIdentifierPart(ch)) {
                token.append(ch);
                continue;
            }
            if (token.length() > 0) {
                String value = token.toString();
                if (Character.isJavaIdentifierStart(value.charAt(0)) && !STOP_WORDS.contains(value)) {
                    out.add(value);
                }
                token.setLength(0);
            }
        }
        return out;
    }

    private static Optional<ClassOrInterfaceDeclaration> findClass(CompilationUnit cu, String simpleName) {
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (c.getNameAsString().equals(simpleName)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static Optional<CallableDeclaration<?>> findCallable(ClassOrInterfaceDeclaration owner, String sig) {
        if (owner == null || sig == null || sig.trim().isEmpty()) {
            return Optional.empty();
        }
        for (ConstructorDeclaration c : owner.getConstructors()) {
            if (CallableSignatureNormalizer.matches(c, sig)) {
                return Optional.<CallableDeclaration<?>>of(c);
            }
        }
        for (MethodDeclaration m : owner.getMethods()) {
            if (CallableSignatureNormalizer.matches(m, sig)) {
                return Optional.<CallableDeclaration<?>>of(m);
            }
        }
        return Optional.empty();
    }

    private static String normalizeCallableSignature(String sig) {
        return CallableSignatureNormalizer.canonical(sig);
    }

    private static String callableSignatureForMatch(CallableDeclaration<?> c) {
        return CallableSignatureNormalizer.canonical(c);
    }

    private static boolean sameParameters(CallableDeclaration<?> callable, List<String> ps) {
        if (callable.getParameters().size() != ps.size()) {
            return false;
        }
        for (int i = 0; i < ps.size(); i++) {
            if (!simpleType(callable.getParameter(i).getType().asString()).equals(ps.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isConstructorSignature(String sig) {
        if (sig == null) {
            return false;
        }
        int lp = sig.indexOf('(');
        if (lp <= 0) {
            return false;
        }
        String head = sig.substring(0, lp).trim();
        return !head.contains(" ");
    }

    private static String methodName(String sig) {
        if (sig == null) {
            return "";
        }
        int lp = sig.indexOf('(');
        String head = lp < 0 ? sig.trim() : sig.substring(0, lp).trim();
        int encoded = head.lastIndexOf('_');
        if (encoded >= 0 && encoded + 1 < head.length()) {
            head = head.substring(encoded + 1).trim();
        }
        int sp = head.lastIndexOf(' ');
        return sp >= 0 ? head.substring(sp + 1).trim() : head;
    }

    private static List<String> params(String sig) {
        if (sig == null) {
            return Collections.emptyList();
        }
        int lp = sig.indexOf('(');
        int rp = sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) {
            return Collections.emptyList();
        }
        String inside = sig.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private static String simpleType(String raw) {
        return CallableSignatureNormalizer.simpleType(raw);
    }

    private static String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    private static String exampleValueForType(String raw, String name) {
        String t = simpleType(raw);
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("boolean".equals(t) || "Boolean".equals(t)) {
            return "false";
        }
        if ("byte".equals(t) || "Byte".equals(t)) {
            return "(byte) 1";
        }
        if ("short".equals(t) || "Short".equals(t)) {
            return "(short) 1";
        }
        if ("int".equals(t) || "Integer".equals(t)) {
            return lowerName.contains("offset") ? "1" : "7";
        }
        if ("long".equals(t) || "Long".equals(t)) {
            return "1L";
        }
        if ("float".equals(t) || "Float".equals(t)) {
            return "1.0f";
        }
        if ("double".equals(t) || "Double".equals(t)) {
            return "1.0";
        }
        if ("char".equals(t) || "Character".equals(t)) {
            return "'x'";
        }
        if ("String".equals(t) || "CharSequence".equals(t)) {
            return "\"x\"";
        }
        if ("Reader".equals(t)) {
            return "new java.io.StringReader(\"a,b\\n1,2\")";
        }
        if ("Collection".equals(t) || "List".equals(t) || "Iterable".equals(t)) {
            return "java.util.Arrays.asList(\"a\", \"b\")";
        }
        if (t.endsWith("[]")) {
            return "new " + t.substring(0, t.length() - 2) + "[8]";
        }
        return "null";
    }

    private static boolean isConstructorExceptionMutation(Context ctx,
                                                          CallableDeclaration<?> entry,
                                                          CallableDeclaration<?> mutation) {
        if (!(entry instanceof ConstructorDeclaration) || mutation == null) {
            return false;
        }
        Expression left = parseExpressionOrNull(diffLeft(ctx == null ? "" : ctx.diff));
        Expression right = parseExpressionOrNull(diffRight(ctx == null ? "" : ctx.diff));
        if (left == null && right == null) {
            return false;
        }
        for (IfStmt ifStmt : mutation.findAll(IfStmt.class)) {
            boolean related = (left != null && containsEquivalentExpression(ifStmt.getCondition(), left))
                    || (right != null && containsEquivalentExpression(ifStmt.getCondition(), right));
            if (related && !ifStmt.findAll(ThrowStmt.class).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> commented(String comment, Object item) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("comment", comment);
        out.put("item", item);
        return out;
    }

    private static Map<String, Object> commentedItems(String comment, List<?> items) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("comment", comment);
        out.put("items", items == null ? Collections.emptyList() : items);
        return out;
    }

    private static final Set<String> STOP_WORDS = new LinkedHashSet<String>();
    static {
        Collections.addAll(STOP_WORDS, "if", "else", "true", "false", "null", "new", "return", "throw", "this",
                "super");
    }

    private static final class ParameterStateCandidate {
        final String observableCall;
        final String reason;

        ParameterStateCandidate(String observableCall, String reason) {
            this.observableCall = observableCall == null ? "" : observableCall;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final class Analysis {
        boolean overrideRecommended = false;
        String preferredKind = "";
        String preferredCall = "";
        String reason = "";
        String directness = "";
        String directnessReason = "";
        final List<String> supportingFields = new ArrayList<String>();
    }

    private static final class ObserverCandidate {
        final MethodDeclaration method;
        final LinkedHashSet<String> readFields;
        String directness = "";
        String reason = "";

        ObserverCandidate(MethodDeclaration method, LinkedHashSet<String> readFields) {
            this.method = method;
            this.readFields = readFields == null ? new LinkedHashSet<String>() : readFields;
        }
    }
}
