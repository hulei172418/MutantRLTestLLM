package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JavaParser-based entry analyzer. It ranks public/callable entry candidates by
 * real source reachability, independent control of mutation-sensitive inputs,
 * and a conservative satisfiability estimate for the original-vs-mutant
 * semantic difference.
 *
 * Java source semantics are intentionally derived from AST nodes. When a call,
 * overload or condition cannot be resolved with high confidence, this class
 * returns UNKNOWN instead of guessing from regular expressions.
 */
public final class EntryBindingAnalyzer {
    private EntryBindingAnalyzer() {
    }

    public static final class Context {
        public String originJavaFile = "";
        public String ownerClassName = "";
        public String mutationMethodSig = "";
        public String diff = "";
        public String semanticOriginalExpression = "";
        public String semanticMutantExpression = "";
    }

    public static List<Map<String, Object>> rankCandidates(Context ctx, List<Object> rawCandidates) {
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            CompilationUnit cu = parse(ctx.originJavaFile);
            ClassOrInterfaceDeclaration owner = findClass(cu, simpleType(ctx.ownerClassName)).orElse(null);
            if (owner == null) {
                return copiesWithAnalysisUnavailable(rawCandidates, "entry owner not found: " + ctx.ownerClassName);
            }
            CallableDeclaration<?> mutation = findCallable(owner, ctx.mutationMethodSig).orElse(null);
            if (mutation == null) {
                return copiesWithAnalysisUnavailable(rawCandidates,
                        "mutation method not found: " + ctx.mutationMethodSig);
            }

            Set<String> sensitive = sensitiveParameters(mutation, ctx);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Object raw : rawCandidates) {
                Map<String, Object> candidate = new LinkedHashMap<String, Object>(asMap(raw));
                String signature = firstNonBlank(
                        stringValue(candidate.get("signature")),
                        stringValue(candidate.get("methodSignature")),
                        stringValue(candidate.get("entryMethodSignature")));
                CallableDeclaration<?> entry = findCallable(owner, signature).orElse(null);
                Analysis analysis = analyze(owner, entry, mutation, sensitive, ctx);
                candidate.put("entryBindingAnalysis", analysis.toMap());
                candidate.put("reachability", analysis.reachability);
                candidate.put("constraintStatus", analysis.constraintStatus);
                candidate.put("controllabilityCoverage", analysis.controllabilityCoverage);
                candidate.put("independentControllability", analysis.independentControllability);
                candidate.put("analysisConfidence", analysis.confidence);
                candidate.put("entryRankingScore", analysis.rankingScore(candidate));
                out.add(candidate);
            }

            // Explicit lexicographic order. Do not chain .reversed() across an
            // already-built comparator because that reverses the whole prefix.
            out.sort(new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> left, Map<String, Object> right) {
                    int cmp = Integer.compare(
                            reachabilityRank(stringValue(right.get("reachability"))),
                            reachabilityRank(stringValue(left.get("reachability"))));
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Integer.compare(
                            constraintRank(stringValue(right.get("constraintStatus"))),
                            constraintRank(stringValue(left.get("constraintStatus"))));
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Double.compare(
                            numberValue(right.get("independentControllability")),
                            numberValue(left.get("independentControllability")));
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Double.compare(
                            numberValue(right.get("controllabilityCoverage")),
                            numberValue(left.get("controllabilityCoverage")));
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Double.compare(
                            numberValue(right.get("entryRankingScore")),
                            numberValue(left.get("entryRankingScore")));
                    if (cmp != 0) {
                        return cmp;
                    }
                    return candidateSignature(left).compareTo(candidateSignature(right));
                }
            });
            return out;
        } catch (Throwable t) {
            return copiesWithAnalysisUnavailable(rawCandidates,
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    /**
     * Rebuilds the concrete invocation skeleton from the final SelectedEntry.
     * This prevents an early BFS entry from surviving in testTarget after the
     * controllability-aware ranking selects a different B.
     */
    public static Map<String, Object> buildInvocationFacts(Context ctx, String selectedSignature) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (ctx == null || isBlank(selectedSignature)) {
            return out;
        }
        try {
            CompilationUnit cu = parse(ctx.originJavaFile);
            ClassOrInterfaceDeclaration owner = findClass(cu, simpleType(ctx.ownerClassName)).orElse(null);
            if (owner == null) {
                return out;
            }
            CallableDeclaration<?> callable = findCallable(owner, selectedSignature).orElse(null);
            if (callable == null) {
                return out;
            }

            boolean constructor = callable instanceof ConstructorDeclaration;
            boolean isStatic = callable instanceof MethodDeclaration && ((MethodDeclaration) callable).isStatic();
            String ownerName = owner.getNameAsString();
            String returnType = constructor ? ownerName : ((MethodDeclaration) callable).getType().asString();
            List<String> args = new ArrayList<String>();
            List<String> parameterTypes = new ArrayList<String>();
            for (int i = 0; i < callable.getParameters().size(); i++) {
                String type = callable.getParameter(i).getType().asString();
                parameterTypes.add(type);
                args.add(defaultArgument(type, i, callable.getParameter(i).getNameAsString()));
            }
            String joinedArgs = String.join(", ", args);
            String invocation;
            if (constructor) {
                invocation = ownerName + " result = new " + ownerName + "(" + joinedArgs + ");";
                out.put("entryInvocationKind", "CONSTRUCTOR_INVOCATION");
                out.put("receiverRequired", false);
            } else {
                MethodDeclaration method = (MethodDeclaration) callable;
                String call = (isStatic ? ownerName : "subject") + "." + method.getNameAsString()
                        + "(" + joinedArgs + ")";
                invocation = method.getType().isVoidType() ? call + ";" : returnType + " result = " + call + ";";
                out.put("entryInvocationKind", isStatic ? "STATIC_METHOD_INVOCATION" : "INSTANCE_METHOD_INVOCATION");
                out.put("receiverRequired", !isStatic);
            }
            out.put("entryMethodSignature", callableSignature(callable));
            out.put("selectedEntrySignature", callableSignature(callable));
            out.put("invocationTemplate", invocation);
            out.put("argumentExamples", args);
            out.put("parameterTypes", parameterTypes);
            out.put("returnType", returnType);
            out.put("static", isStatic);
            out.put("constructor", constructor);
            out.put("sourceDerived", true);
            out.put("notes",
                    "Rebuilt from the final SelectedEntry JavaParser AST; concrete values must still satisfy CANONICAL_CORE guards/cofactors.");
            return out;
        } catch (Throwable ignored) {
            return out;
        }
    }

    private static Analysis analyze(ClassOrInterfaceDeclaration owner,
                                    CallableDeclaration<?> entry,
                                    CallableDeclaration<?> mutation,
                                    Set<String> sensitive,
                                    Context ctx) {
        Analysis out = new Analysis();
        if (entry == null) {
            out.reason = "candidate signature could not be resolved in source";
            out.confidence = 0.20d;
            return out;
        }

        TraceResult trace = traceBindings(owner, entry, mutation,
                new LinkedHashMap<String, String>(), new LinkedHashSet<String>());
        Map<String, String> bindings = trace.bindings;
        out.reachability = trace.pathFound ? "REACHABLE" : "UNKNOWN";
        out.bindings.putAll(bindings);

        Set<String> externalSources = new LinkedHashSet<String>();
        Set<String> controlled = new LinkedHashSet<String>();
        for (String parameter : sensitive) {
            String binding = bindings.get(parameter);
            Set<String> sources = externalSources(binding, entry);
            if (!sources.isEmpty()) {
                externalSources.addAll(sources);
                controlled.add(parameter);
            }
        }
        out.externalSources.addAll(externalSources);
        out.controlledSensitiveParameters.addAll(controlled);
        int total = Math.max(1, sensitive.size());
        out.controllabilityCoverage = controlled.size() * 1.0d / total;
        out.independentControllability = externalSources.size() * 1.0d / total;
        out.constraintStatus = trace.pathFound ? inferConstraintStatus(ctx, bindings) : "UNKNOWN";
        if (!trace.pathFound) {
            out.confidence = 0.35d;
        } else if ("UNSAT".equals(out.constraintStatus)) {
            out.confidence = 0.95d;
        } else if ("SAT".equals(out.constraintStatus)) {
            out.confidence = 0.80d;
        } else {
            out.confidence = 0.60d;
        }
        out.reason = buildReason(sensitive, controlled, externalSources, out.reachability, out.constraintStatus);
        return out;
    }

    private static String inferConstraintStatus(Context ctx, Map<String, String> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return "UNKNOWN";
        }

        Expression original = parseExpressionOrNull(ctx == null ? "" : ctx.semanticOriginalExpression);
        Expression mutant = parseExpressionOrNull(ctx == null ? "" : ctx.semanticMutantExpression);
        if (original == null || mutant == null) {
            String diff = ctx == null ? "" : ctx.diff;
            int arrow = diff == null ? -1 : diff.indexOf("=>");
            if (arrow >= 0) {
                original = parseExpressionOrNull(diff.substring(0, arrow));
                mutant = parseExpressionOrNull(diff.substring(arrow + 2));
            }
        }
        if (original == null || mutant == null) {
            return "UNKNOWN";
        }

        Expression boundOriginal = substituteExpression(original, bindings);
        Expression boundMutant = substituteExpression(mutant, bindings);
        if (boundOriginal == null || boundMutant == null) {
            return "UNKNOWN";
        }
        if (boundOriginal.equals(boundMutant)) {
            return "UNSAT";
        }

        Boolean originalValue = evaluateBooleanConstant(boundOriginal);
        Boolean mutantValue = evaluateBooleanConstant(boundMutant);
        if (originalValue != null && mutantValue != null) {
            return originalValue.booleanValue() == mutantValue.booleanValue() ? "UNSAT" : "SAT";
        }
        if (simpleRelationalDifferenceCanBeSatisfied(boundOriginal, boundMutant)) {
            return "SAT";
        }
        return "UNKNOWN";
    }

    private static Expression substituteExpression(Expression expression, Map<String, String> bindings) {
        Expression copy = expression == null ? null : expression.clone();
        if (copy == null) {
            return null;
        }
        for (NameExpr name : new ArrayList<NameExpr>(copy.findAll(NameExpr.class))) {
            String replacement = bindings.get(name.getNameAsString());
            if (isBlank(replacement)) {
                continue;
            }
            Expression replacementExpr = parseExpressionOrNull(replacement);
            if (replacementExpr != null) {
                name.replace(replacementExpr.clone());
            }
        }
        return copy;
    }

    private static Boolean evaluateBooleanConstant(Expression expression) {
        Expression e = unwrap(expression);
        if (e == null) {
            return null;
        }
        if (e.isBooleanLiteralExpr()) {
            return Boolean.valueOf(e.asBooleanLiteralExpr().getValue());
        }
        if (e.isUnaryExpr()
                && e.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            Boolean inner = evaluateBooleanConstant(e.asUnaryExpr().getExpression());
            return inner == null ? null : Boolean.valueOf(!inner.booleanValue());
        }
        if (!e.isBinaryExpr()) {
            return null;
        }

        BinaryExpr binary = e.asBinaryExpr();
        BinaryExpr.Operator op = binary.getOperator();
        if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
            Boolean left = evaluateBooleanConstant(binary.getLeft());
            Boolean right = evaluateBooleanConstant(binary.getRight());
            if (left == null || right == null) {
                return null;
            }
            return op == BinaryExpr.Operator.AND
                    ? Boolean.valueOf(left.booleanValue() && right.booleanValue())
                    : Boolean.valueOf(left.booleanValue() || right.booleanValue());
        }
        if (!isRelationalOrEquality(op)) {
            return null;
        }

        Expression leftExpr = unwrap(binary.getLeft());
        Expression rightExpr = unwrap(binary.getRight());
        if (leftExpr != null && leftExpr.equals(rightExpr)) {
            if (op == BinaryExpr.Operator.GREATER
                    || op == BinaryExpr.Operator.LESS
                    || op == BinaryExpr.Operator.NOT_EQUALS) {
                return Boolean.FALSE;
            }
            if (op == BinaryExpr.Operator.GREATER_EQUALS
                    || op == BinaryExpr.Operator.LESS_EQUALS
                    || op == BinaryExpr.Operator.EQUALS) {
                return Boolean.TRUE;
            }
        }

        Long leftNumber = literalIntegralValue(leftExpr);
        Long rightNumber = literalIntegralValue(rightExpr);
        if (leftNumber != null && rightNumber != null) {
            int cmp = Long.compare(leftNumber.longValue(), rightNumber.longValue());
            switch (op) {
                case GREATER:
                    return Boolean.valueOf(cmp > 0);
                case GREATER_EQUALS:
                    return Boolean.valueOf(cmp >= 0);
                case LESS:
                    return Boolean.valueOf(cmp < 0);
                case LESS_EQUALS:
                    return Boolean.valueOf(cmp <= 0);
                case EQUALS:
                    return Boolean.valueOf(cmp == 0);
                case NOT_EQUALS:
                    return Boolean.valueOf(cmp != 0);
                default:
                    return null;
            }
        }
        if (op == BinaryExpr.Operator.EQUALS || op == BinaryExpr.Operator.NOT_EQUALS) {
            Object leftLiteral = literalValue(leftExpr);
            Object rightLiteral = literalValue(rightExpr);
            if (leftLiteral != null && rightLiteral != null) {
                boolean equal = leftLiteral.equals(rightLiteral);
                return Boolean.valueOf(op == BinaryExpr.Operator.EQUALS ? equal : !equal);
            }
        }
        return null;
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static Long literalIntegralValue(Expression expression) {
        Expression e = unwrap(expression);
        if (e == null) {
            return null;
        }
        try {
            if (e.isIntegerLiteralExpr()) {
                return Long.valueOf(e.asIntegerLiteralExpr().asNumber().longValue());
            }
            if (e.isLongLiteralExpr()) {
                return Long.valueOf(e.asLongLiteralExpr().asNumber().longValue());
            }
            if (e.isCharLiteralExpr()) {
                String value = e.asCharLiteralExpr().getValue();
                return value.length() == 1 ? Long.valueOf(value.charAt(0)) : null;
            }
            if (e.isUnaryExpr()) {
                UnaryExpr unary = e.asUnaryExpr();
                Long inner = literalIntegralValue(unary.getExpression());
                if (inner == null) {
                    return null;
                }
                if (unary.getOperator() == UnaryExpr.Operator.MINUS) {
                    return Long.valueOf(-inner.longValue());
                }
                if (unary.getOperator() == UnaryExpr.Operator.PLUS) {
                    return inner;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static Object literalValue(Expression expression) {
        Expression e = unwrap(expression);
        if (e == null) {
            return null;
        }
        Long number = literalIntegralValue(e);
        if (number != null) {
            return number;
        }
        if (e.isBooleanLiteralExpr()) {
            return Boolean.valueOf(e.asBooleanLiteralExpr().getValue());
        }
        if (e.isStringLiteralExpr()) {
            return e.asStringLiteralExpr().getValue();
        }
        if (e.isNullLiteralExpr()) {
            return "<null>";
        }
        return null;
    }

    private static boolean simpleRelationalDifferenceCanBeSatisfied(Expression original, Expression mutant) {
        Expression o = unwrap(original);
        Expression m = unwrap(mutant);
        if (o == null || m == null || !o.isBinaryExpr() || !m.isBinaryExpr()) {
            return false;
        }
        BinaryExpr ob = o.asBinaryExpr();
        BinaryExpr mb = m.asBinaryExpr();
        if (!isRelationalOrEquality(ob.getOperator()) || !isRelationalOrEquality(mb.getOperator())) {
            return false;
        }
        if (ob.getOperator() == mb.getOperator()) {
            return false;
        }
        Expression ol = unwrap(ob.getLeft());
        Expression or = unwrap(ob.getRight());
        Expression ml = unwrap(mb.getLeft());
        Expression mr = unwrap(mb.getRight());
        if (ol == null || or == null || ml == null || mr == null
                || !ol.equals(ml) || !or.equals(mr)) {
            return false;
        }
        return ol.isNameExpr()
                && or.isNameExpr()
                && !ol.asNameExpr().getNameAsString().equals(or.asNameExpr().getNameAsString());
    }

    private static boolean isRelationalOrEquality(BinaryExpr.Operator op) {
        return op == BinaryExpr.Operator.LESS
                || op == BinaryExpr.Operator.LESS_EQUALS
                || op == BinaryExpr.Operator.GREATER
                || op == BinaryExpr.Operator.GREATER_EQUALS
                || op == BinaryExpr.Operator.EQUALS
                || op == BinaryExpr.Operator.NOT_EQUALS;
    }

    private static Expression parseExpressionOrNull(String text) {
        if (isBlank(text)) {
            return null;
        }
        try {
            return StaticJavaParser.parseExpression(text.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TraceResult traceBindings(ClassOrInterfaceDeclaration owner,
                                             CallableDeclaration<?> current,
                                             CallableDeclaration<?> mutation,
                                             Map<String, String> currentBindings,
                                             Set<String> visited) {
        String visitKey = callableSignature(current);
        if (!visited.add(visitKey)) {
            return TraceResult.notFound();
        }
        if (sameCallable(current, mutation)) {
            Map<String, String> resolved = new LinkedHashMap<String, String>();
            for (Parameter p : current.getParameters()) {
                String name = p.getNameAsString();
                resolved.put(name, currentBindings.containsKey(name) ? currentBindings.get(name) : name);
            }
            return TraceResult.found(resolved);
        }

        for (CallSite site : directCallSites(owner, current)) {
            Map<String, String> next = new LinkedHashMap<String, String>();
            for (int i = 0; i < site.callee.getParameters().size() && i < site.arguments.size(); i++) {
                String formal = site.callee.getParameter(i).getNameAsString();
                next.put(formal, substitute(site.arguments.get(i), currentBindings));
            }
            TraceResult resolved = traceBindings(owner, site.callee, mutation, next,
                    new LinkedHashSet<String>(visited));
            if (resolved.pathFound) {
                return resolved;
            }
        }
        return TraceResult.notFound();
    }

    private static List<CallSite> directCallSites(ClassOrInterfaceDeclaration owner,
                                                   CallableDeclaration<?> current) {
        List<CallSite> out = new ArrayList<CallSite>();
        for (MethodCallExpr call : current.findAll(MethodCallExpr.class)) {
            if (!isSameOwnerMethodCall(owner, call)) {
                continue;
            }
            Optional<MethodDeclaration> callee = findMethod(owner, call.getNameAsString(), call.getArguments().size());
            if (callee.isPresent()) {
                out.add(new CallSite(callee.get(), expressions(call.getArguments())));
            }
        }
        for (ObjectCreationExpr creation : current.findAll(ObjectCreationExpr.class)) {
            Optional<ConstructorDeclaration> ctor = findConstructor(owner, creation.getType().getNameAsString(),
                    creation.getArguments().size());
            if (ctor.isPresent()) {
                out.add(new CallSite(ctor.get(), expressions(creation.getArguments())));
            }
        }
        if (current instanceof ConstructorDeclaration) {
            ConstructorDeclaration cd = (ConstructorDeclaration) current;
            if (!cd.getBody().getStatements().isEmpty()
                    && cd.getBody().getStatement(0).isExplicitConstructorInvocationStmt()) {
                ExplicitConstructorInvocationStmt stmt = cd.getBody().getStatement(0).asExplicitConstructorInvocationStmt();
                if (stmt.isThis()) {
                    Optional<ConstructorDeclaration> ctor = findConstructor(owner, owner.getNameAsString(),
                            stmt.getArguments().size());
                    if (ctor.isPresent() && !sameCallable(ctor.get(), current)) {
                        out.add(new CallSite(ctor.get(), expressions(stmt.getArguments())));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isSameOwnerMethodCall(ClassOrInterfaceDeclaration owner, MethodCallExpr call) {
        if (!call.getScope().isPresent()) {
            return true;
        }
        Expression scope = unwrap(call.getScope().get());
        if (scope != null && scope.isThisExpr()) {
            return true;
        }
        return scope != null
                && scope.isNameExpr()
                && owner.getNameAsString().equals(scope.asNameExpr().getNameAsString());
    }

    private static Set<String> sensitiveParameters(CallableDeclaration<?> mutation, Context ctx) {
        Set<String> names = new LinkedHashSet<String>();
        collectExpressionNames(names, ctx == null ? "" : ctx.semanticOriginalExpression);
        collectExpressionNames(names, ctx == null ? "" : ctx.semanticMutantExpression);
        if (ctx != null && !isBlank(ctx.diff)) {
            int arrow = ctx.diff.indexOf("=>");
            if (arrow >= 0) {
                collectExpressionNames(names, ctx.diff.substring(0, arrow));
                collectExpressionNames(names, ctx.diff.substring(arrow + 2));
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (Parameter p : mutation.getParameters()) {
            if (names.contains(p.getNameAsString())) {
                out.add(p.getNameAsString());
            }
        }
        if (out.isEmpty()) {
            for (Parameter p : mutation.getParameters()) {
                out.add(p.getNameAsString());
            }
        }
        return out;
    }

    private static void collectExpressionNames(Set<String> out, String expressionText) {
        Expression expression = parseExpressionOrNull(expressionText);
        if (expression == null) {
            return;
        }
        for (NameExpr name : expression.findAll(NameExpr.class)) {
            out.add(name.getNameAsString());
        }
    }

    private static Set<String> externalSources(String expr, CallableDeclaration<?> entry) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (isBlank(expr) || entry == null) {
            return out;
        }
        Set<String> params = entry.getParameters().stream()
                .map(Parameter::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Expression parsed = parseExpressionOrNull(expr);
        if (parsed == null) {
            if (params.contains(expr.trim())) {
                out.add(expr.trim());
            }
            return out;
        }
        for (NameExpr name : parsed.findAll(NameExpr.class)) {
            String n = name.getNameAsString();
            if (params.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private static String buildReason(Set<String> sensitive,
                                      Set<String> controlled,
                                      Set<String> externalSources,
                                      String reachability,
                                      String constraintStatus) {
        return "Reachability " + reachability
                + "; sensitive parameters " + sensitive
                + "; externally controlled " + controlled
                + " through independent source(s) " + externalSources
                + "; distinguishing constraint status " + constraintStatus + ".";
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
        if (owner == null || isBlank(sig)) {
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

    private static Optional<MethodDeclaration> findMethod(ClassOrInterfaceDeclaration owner, String name, int arity) {
        MethodDeclaration match = null;
        for (MethodDeclaration m : owner.getMethodsByName(name)) {
            if (m.getParameters().size() != arity) {
                continue;
            }
            if (match != null) {
                // Ambiguous same-name/same-arity overload: do not invent a call edge.
                return Optional.empty();
            }
            match = m;
        }
        return Optional.ofNullable(match);
    }

    private static Optional<ConstructorDeclaration> findConstructor(ClassOrInterfaceDeclaration owner,
                                                                    String name,
                                                                    int arity) {
        if (!owner.getNameAsString().equals(simpleType(name))) {
            return Optional.empty();
        }
        ConstructorDeclaration match = null;
        for (ConstructorDeclaration c : owner.getConstructors()) {
            if (c.getParameters().size() != arity) {
                continue;
            }
            if (match != null) {
                return Optional.empty();
            }
            match = c;
        }
        return Optional.ofNullable(match);
    }

    private static String substitute(String expr, Map<String, String> bindings) {
        if (isBlank(expr) || bindings == null || bindings.isEmpty()) {
            return expr == null ? "" : expr;
        }
        Expression parsed = parseExpressionOrNull(expr);
        if (parsed == null) {
            // No regex fallback for Java expression semantics.
            return expr;
        }
        Expression replaced = substituteExpression(parsed, bindings);
        return replaced == null ? expr : replaced.toString();
    }

    private static CompilationUnit parse(String javaFile) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
        JavaParser parser = new JavaParser(cfg);
        com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(Paths.get(javaFile));
        if (!result.getResult().isPresent()) {
            throw new IllegalArgumentException("Parse failed: " + result.getProblems());
        }
        CompilationUnit cu = result.getResult().get();
        cu.getAllContainedComments().forEach(Comment::remove);
        return cu;
    }

    private static String callableSignature(CallableDeclaration<?> callable) {
        return CallableSignatureNormalizer.canonical(callable);
    }

    private static boolean sameCallable(CallableDeclaration<?> a, CallableDeclaration<?> b) {
        return a != null && b != null
                && CallableSignatureNormalizer.canonical(a).equals(CallableSignatureNormalizer.canonical(b));
    }

    private static List<String> expressions(List<Expression> expressions) {
        List<String> out = new ArrayList<String>();
        for (Expression e : expressions) {
            out.add(e.toString());
        }
        return out;
    }

    private static String simpleType(String name) {
        return CallableSignatureNormalizer.simpleType(name);
    }

    private static String defaultArgument(String typeName, int index, String parameterName) {
        String type = typeName == null ? "" : typeName.trim();
        String simple = simpleType(type);
        if ("boolean".equals(type) || "Boolean".equals(simple)) {
            return index % 2 == 0 ? "false" : "true";
        }
        if ("char".equals(type) || "Character".equals(simple)) {
            char value = (char) ('a' + Math.min(index, 20));
            return "'" + value + "'";
        }
        if ("byte".equals(type) || "Byte".equals(simple)) {
            return "(byte) " + index;
        }
        if ("short".equals(type) || "Short".equals(simple)) {
            return "(short) " + index;
        }
        if ("int".equals(type) || "Integer".equals(simple)) {
            return String.valueOf(index);
        }
        if ("long".equals(type) || "Long".equals(simple)) {
            return String.valueOf(index) + "L";
        }
        if ("float".equals(type) || "Float".equals(simple)) {
            return String.valueOf(index) + ".0f";
        }
        if ("double".equals(type) || "Double".equals(simple)) {
            return String.valueOf(index) + ".0d";
        }
        if ("String".equals(simple)) {
            return "\"" + (index == 0 ? "a" : "b" + index) + "\"";
        }
        if (type.endsWith("[]")) {
            String component = type.substring(0, type.length() - 2).trim();
            return "new " + component + "[0]";
        }
        String symbolic = parameterName == null ? "" : parameterName.trim();
        return symbolic.isEmpty() ? "arg" + (index + 1) : symbolic;
    }

    private static List<Map<String, Object>> copiesWithAnalysisUnavailable(List<Object> rawCandidates, String reason) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object raw : rawCandidates) {
            Map<String, Object> candidate = new LinkedHashMap<String, Object>(asMap(raw));
            Analysis analysis = new Analysis();
            analysis.reason = reason;
            candidate.put("entryBindingAnalysis", analysis.toMap());
            candidate.put("reachability", "UNKNOWN");
            candidate.put("constraintStatus", "UNKNOWN");
            candidate.put("controllabilityCoverage", 0.0d);
            candidate.put("independentControllability", 0.0d);
            candidate.put("analysisConfidence", 0.0d);
            candidate.put("entryRankingScore", analysis.rankingScore(candidate));
            out.add(candidate);
        }
        return out;
    }

    private static int reachabilityRank(String value) {
        String v = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if ("REACHABLE".equals(v)) {
            return 2;
        }
        if ("UNKNOWN".equals(v)) {
            return 1;
        }
        return 0;
    }

    private static int constraintRank(String value) {
        String v = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if ("SAT".equals(v)) {
            return 2;
        }
        if ("UNKNOWN".equals(v)) {
            return 1;
        }
        return 0;
    }

    private static double numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private static String candidateSignature(Map<String, Object> candidate) {
        return firstNonBlank(
                stringValue(candidate.get("signature")),
                stringValue(candidate.get("methodSignature")),
                stringValue(candidate.get("entryMethodSignature")));
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<String, Object>();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (!isBlank(value)) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class TraceResult {
        final boolean pathFound;
        final Map<String, String> bindings;

        private TraceResult(boolean pathFound, Map<String, String> bindings) {
            this.pathFound = pathFound;
            this.bindings = bindings == null
                    ? new LinkedHashMap<String, String>()
                    : bindings;
        }

        static TraceResult found(Map<String, String> bindings) {
            return new TraceResult(true, bindings);
        }

        static TraceResult notFound() {
            return new TraceResult(false, new LinkedHashMap<String, String>());
        }
    }

    private static final class CallSite {
        final CallableDeclaration<?> callee;
        final List<String> arguments;

        CallSite(CallableDeclaration<?> callee, List<String> arguments) {
            this.callee = callee;
            this.arguments = arguments;
        }
    }

    private static final class Analysis {
        final Map<String, String> bindings = new LinkedHashMap<String, String>();
        final Set<String> externalSources = new LinkedHashSet<String>();
        final Set<String> controlledSensitiveParameters = new LinkedHashSet<String>();
        String reachability = "UNKNOWN";
        String constraintStatus = "UNKNOWN";
        double controllabilityCoverage;
        double independentControllability;
        double confidence;
        String reason = "";

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("bindings", new LinkedHashMap<String, String>(bindings));
            out.put("externalSources", new ArrayList<String>(externalSources));
            out.put("controlledSensitiveParameters", new ArrayList<String>(controlledSensitiveParameters));
            out.put("reachability", reachability);
            out.put("constraintStatus", constraintStatus);
            out.put("controllabilityCoverage", controllabilityCoverage);
            out.put("independentControllability", independentControllability);
            out.put("confidence", confidence);
            out.put("reason", reason);
            return out;
        }

        double rankingScore(Map<String, Object> candidate) {
            return reachabilityRank(reachability) * 1000.0d
                    + constraintRank(constraintStatus) * 300.0d
                    + independentControllability * 100.0d
                    + controllabilityCoverage * 50.0d
                    + numberValue(candidate.get("score")) * 0.01d;
        }
    }
}
