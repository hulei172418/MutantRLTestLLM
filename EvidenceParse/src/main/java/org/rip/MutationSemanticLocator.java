package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MutationSemanticLocator {
    private MutationSemanticLocator() {
    }

    public static final class MutationSemanticContext {
        public String localOriginalExpression = "";
        public String localMutantExpression = "";
        public String semanticOriginalExpression = "";
        public String semanticMutantExpression = "";
        public String semanticContainerKind = "";
        public final List<String> ancestorGuards = new ArrayList<String>();
        public final List<Map<String, Object>> ancestorGuardConstraints = new ArrayList<Map<String, Object>>();
        public final Set<String> requiredNonNullVariables = new LinkedHashSet<String>();
        public boolean resolved;
        public String resolutionMode = "";
        public String reason = "";
    }

    public static MutationSemanticContext resolve(Path originalFile,
                                                  Path mutantFile,
                                                  String className,
                                                  String methodSig,
                                                  Integer mutationLine,
                                                  String originalDiffExpression,
                                                  String mutantDiffExpression) {
        MutationSemanticContext out = new MutationSemanticContext();
        if (originalFile == null || mutantFile == null || mutationLine == null || mutationLine.intValue() <= 0) {
            out.resolutionMode = "UNAVAILABLE";
            out.reason = "Missing source file or mutation line.";
            return out;
        }
        try {
            JavaParser parser = new JavaParser(new ParserConfiguration());
            CompilationUnit originalCu = parser.parse(new File(originalFile.toString())).getResult().orElse(null);
            CompilationUnit mutantCu = parser.parse(new File(mutantFile.toString())).getResult().orElse(null);
            if (originalCu == null || mutantCu == null) {
                out.resolutionMode = "PARSE_FAILED";
                out.reason = "JavaParser could not parse original or mutant source.";
                return out;
            }

            CallableDeclaration<?> originalCallable = findCallable(originalCu, className, methodSig, mutationLine.intValue()).orElse(null);
            CallableDeclaration<?> mutantCallable = findCallable(mutantCu, className, methodSig, mutationLine.intValue()).orElse(null);
            if (originalCallable == null || mutantCallable == null) {
                out.resolutionMode = "CALLABLE_NOT_FOUND";
                out.reason = "No callable matched mutation method and line.";
                return out;
            }

            Expression originalLocal = findMutationExpression(originalCallable, mutationLine.intValue(), originalDiffExpression);
            Expression mutantLocal = findMutationExpression(mutantCallable, mutationLine.intValue(), mutantDiffExpression);
            if (originalLocal == null) {
                originalLocal = findSmallestExpressionOnLine(originalCallable, mutationLine.intValue());
            }
            if (mutantLocal == null) {
                mutantLocal = findSmallestExpressionOnLine(mutantCallable, mutationLine.intValue());
            }
            if (originalLocal == null || mutantLocal == null) {
                out.resolutionMode = "LOCAL_EXPRESSION_NOT_FOUND";
                out.reason = "No expression on the mutation line matched the diff.";
                return out;
            }

            SemanticLift originalSemantic = liftToSemanticExpression(originalLocal);
            SemanticLift mutantSemantic = liftToSemanticExpression(mutantLocal);
            SemanticLift reconciledMutant = reconcileSemanticPair(
                    originalSemantic, mutantSemantic, originalLocal, mutantLocal);
            out.localOriginalExpression = originalLocal.toString();
            out.localMutantExpression = mutantLocal.toString();
            out.semanticOriginalExpression = originalSemantic.expression.toString();
            out.semanticMutantExpression = reconciledMutant.expression.toString();
            out.semanticContainerKind = firstNonBlank(originalSemantic.kind, reconciledMutant.kind);
            out.ancestorGuardConstraints.addAll(findAncestorGuardConstraints(originalSemantic.expression));
            out.ancestorGuards.addAll(guardExpressions(out.ancestorGuardConstraints));
            out.requiredNonNullVariables.addAll(inferRequiredNonNullVariables(originalSemantic.expression, originalCallable));
            out.requiredNonNullVariables.addAll(inferRequiredNonNullVariables(mutantSemantic.expression, mutantCallable));
            out.resolved = true;
            out.resolutionMode = "JAVAPARSER_LOCAL";
            out.reason = "Resolved mutation expressions with JavaParser using method signature, mutation line, and diff expression when available.";
            return out;
        } catch (Throwable t) {
            out.resolutionMode = "ERROR";
            out.reason = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            return out;
        }
    }

    private static Optional<CallableDeclaration<?>> findCallable(CompilationUnit cu,
                                                                 String className,
                                                                 String methodSig,
                                                                 int mutationLine) {
        String owner = simpleType(className);
        CallableDeclaration<?> fallback = null;
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!owner.isEmpty() && !owner.equals(type.getNameAsString())) {
                continue;
            }
            List<CallableDeclaration<?>> candidates = new ArrayList<CallableDeclaration<?>>();
            candidates.addAll(type.getConstructors());
            candidates.addAll(type.getMethods());
            for (CallableDeclaration<?> callable : candidates) {
                if (!CallableSignatureNormalizer.matches(callable, methodSig)) {
                    continue;
                }
                if (containsLine(callable, mutationLine)) {
                    return Optional.of(callable);
                }
                fallback = callable;
            }
        }
        return Optional.ofNullable(fallback);
    }

    private static Expression findMutationExpression(CallableDeclaration<?> callable,
                                                     int mutationLine,
                                                     String diffExpression) {
        Expression expectedAst = parseExpressionOrNull(diffExpression);
        Expression best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Expression expression : callable.findAll(Expression.class)) {
            int score = 0;
            if (expectedAst != null && equivalentExpression(expression, expectedAst)) {
                score += 2000;
            } else if (expectedAst != null && containsEquivalentExpression(expression, expectedAst)) {
                score += 500;
            } else if (expectedAst == null && containsLine(expression, mutationLine)) {
                score += 50;
            } else {
                continue;
            }
            score += lineProximityScore(expression, mutationLine);
            score -= expression.toString().length() / 20;
            if (score > bestScore) {
                bestScore = score;
                best = expression;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private static Expression parseExpressionOrNull(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        try {
            return StaticJavaParser.parseExpression(source.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean equivalentExpression(Expression left, Expression right) {
        if (left == null || right == null) {
            return false;
        }
        Expression a = unwrap(left);
        Expression b = unwrap(right);
        return a.equals(b) || removeWhitespace(a.toString()).equals(removeWhitespace(b.toString()));
    }

    private static boolean containsEquivalentExpression(Expression container, Expression expected) {
        if (equivalentExpression(container, expected)) {
            return true;
        }
        for (Expression child : container.findAll(Expression.class)) {
            if (child != container && equivalentExpression(child, expected)) {
                return true;
            }
        }
        return false;
    }

    private static int lineProximityScore(Node node, int mutationLine) {
        if (mutationLine <= 0 || node == null || !node.getRange().isPresent()) {
            return 0;
        }
        Range range = node.getRange().get();
        if (mutationLine >= range.begin.line && mutationLine <= range.end.line) {
            return 300;
        }
        int distance = mutationLine < range.begin.line
                ? range.begin.line - mutationLine
                : mutationLine - range.end.line;
        return Math.max(0, 120 - distance * 20);
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static String removeWhitespace(String value) {
        StringBuilder out = new StringBuilder();
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static Expression findSmallestExpressionOnLine(CallableDeclaration<?> callable, int mutationLine) {
        Expression best = null;
        int bestSize = Integer.MAX_VALUE;
        for (Expression expression : callable.findAll(Expression.class)) {
            if (!containsLine(expression, mutationLine)) {
                continue;
            }
            int size = expression.toString().length();
            if (best == null || size < bestSize) {
                best = expression;
                bestSize = size;
            }
        }
        return best;
    }

    private static SemanticLift liftToSemanticExpression(Expression localNode) {
        Node current = localNode;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            if (parent instanceof IfStmt) {
                IfStmt stmt = (IfStmt) parent;
                if (isDescendantOf(localNode, stmt.getCondition())) {
                    return new SemanticLift(stmt.getCondition(), "IF_CONDITION");
                }
            }
            if (parent instanceof WhileStmt) {
                WhileStmt stmt = (WhileStmt) parent;
                if (isDescendantOf(localNode, stmt.getCondition())) {
                    return new SemanticLift(stmt.getCondition(), "WHILE_CONDITION");
                }
            }
            if (parent instanceof ForStmt) {
                ForStmt stmt = (ForStmt) parent;
                if (stmt.getCompare().isPresent() && isDescendantOf(localNode, stmt.getCompare().get())) {
                    return new SemanticLift(stmt.getCompare().get(), "FOR_CONDITION");
                }
            }
            if (parent instanceof ReturnStmt) {
                return new SemanticLift(((ReturnStmt) parent).getExpression().orElse(localNode), "RETURN_VALUE");
            }
            if (parent instanceof AssignExpr) {
                return new SemanticLift(((AssignExpr) parent).getValue(), "ASSIGN_RHS");
            }
            if (parent instanceof VariableDeclarator) {
                VariableDeclarator declarator = (VariableDeclarator) parent;
                if (declarator.getInitializer().isPresent() && isDescendantOf(localNode, declarator.getInitializer().get())) {
                    return new SemanticLift(declarator.getInitializer().get(), "VARIABLE_INITIALIZER");
                }
            }
            current = parent;
        }
        return new SemanticLift(localNode, "LOCAL_EXPRESSION");
    }

    private static SemanticLift reconcileSemanticPair(SemanticLift originalSemantic,
                                                       SemanticLift mutantSemantic,
                                                       Expression originalLocal,
                                                       Expression mutantLocal) {
        if (originalSemantic == null || mutantSemantic == null) {
            return mutantSemantic;
        }
        boolean localDiffers = !equivalentExpression(originalLocal, mutantLocal);
        boolean semanticCollapsed = equivalentExpression(originalSemantic.expression, mutantSemantic.expression);
        boolean containerMismatch = !safe(originalSemantic.kind).equals(safe(mutantSemantic.kind));
        if (!localDiffers || (!semanticCollapsed && !containerMismatch)) {
            return mutantSemantic;
        }
        Expression rebuilt = replaceLocalExpression(
                originalSemantic.expression, originalLocal, mutantLocal);
        if (rebuilt != null && !equivalentExpression(rebuilt, originalSemantic.expression)) {
            return new SemanticLift(rebuilt, originalSemantic.kind);
        }
        return mutantSemantic;
    }

    private static Expression replaceLocalExpression(Expression semantic,
                                                     Expression originalLocal,
                                                     Expression mutantLocal) {
        if (semantic == null || originalLocal == null || mutantLocal == null) {
            return null;
        }
        if (equivalentExpression(semantic, originalLocal)) {
            return mutantLocal.clone();
        }
        Expression cloned = semantic.clone();
        List<Expression> candidates = cloned.findAll(Expression.class);
        Expression best = null;
        int bestLength = Integer.MAX_VALUE;
        for (Expression candidate : candidates) {
            if (!equivalentExpression(candidate, originalLocal)) {
                continue;
            }
            int length = candidate.toString().length();
            if (length < bestLength) {
                best = candidate;
                bestLength = length;
            }
        }
        if (best == null) {
            return null;
        }
        if (!best.replace(mutantLocal.clone())) {
            return null;
        }
        return cloned;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<Map<String, Object>> findAncestorGuardConstraints(Expression semanticExpression) {
        List<Map<String, Object>> guards = new ArrayList<Map<String, Object>>();
        Node current = semanticExpression;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            if (parent instanceof IfStmt) {
                IfStmt stmt = (IfStmt) parent;
                if (!isDescendantOf(current, stmt.getCondition())) {
                    boolean required = isDescendantOf(current, stmt.getThenStmt());
                    if (!required && stmt.getElseStmt().isPresent() && isDescendantOf(current, stmt.getElseStmt().get())) {
                        required = false;
                    }
                    guards.add(guardConstraint(stmt.getCondition().toString(), required,
                            isDescendantOf(current, stmt.getThenStmt()) ? "AST_ANCESTOR" : "ELSE_BRANCH",
                            "Mutation is inside the branch controlled by this IfStmt."));
                }
            } else if (parent instanceof WhileStmt) {
                WhileStmt stmt = (WhileStmt) parent;
                if (!isDescendantOf(current, stmt.getCondition())) {
                    guards.add(guardConstraint(stmt.getCondition().toString(), true,
                            "AST_ANCESTOR", "Mutation is inside the loop body controlled by this WhileStmt."));
                }
            } else if (parent instanceof ForStmt) {
                ForStmt stmt = (ForStmt) parent;
                if (stmt.getCompare().isPresent() && !isDescendantOf(current, stmt.getCompare().get())) {
                    guards.add(guardConstraint(stmt.getCompare().get().toString(), true,
                            "AST_ANCESTOR", "Mutation is inside the loop body controlled by this ForStmt."));
                }
            }
            current = parent;
        }
        Collections.reverse(guards);
        return dedupeConstraints(guards);
    }

    private static Set<String> inferRequiredNonNullVariables(Expression expression, CallableDeclaration<?> callable) {
        Set<String> allowed = callable.getParameters().stream()
                .map(p -> p.getNameAsString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> out = new LinkedHashSet<String>();
        for (MethodCallExpr call : expression.findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent()) {
                collectNonNullScope(call.getScope().get(), allowed, out);
            }
        }
        for (FieldAccessExpr access : expression.findAll(FieldAccessExpr.class)) {
            collectNonNullScope(access.getScope(), allowed, out);
        }
        return out;
    }

    private static Map<String, Object> guardConstraint(String expression, boolean requiredEvaluation, String source, String reason) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("expression", expression);
        out.put("requiredEvaluation", requiredEvaluation);
        out.put("source", source);
        out.put("hardness", "MANDATORY");
        out.put("reason", reason);
        out.put("confidence", 0.80d);
        return out;
    }

    private static List<String> guardExpressions(List<Map<String, Object>> constraints) {
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> c : constraints == null ? Collections.<Map<String, Object>>emptyList() : constraints) {
            String expr = String.valueOf(c.get("expression"));
            if (!expr.isEmpty()) {
                out.add(expr);
            }
        }
        return out;
    }

    private static void collectNonNullScope(Expression scope, Set<String> allowed, Set<String> out) {
        if (scope == null || scope.isThisExpr() || scope.isSuperExpr() || scope.isTypeExpr()) {
            return;
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            if (allowed.contains(name) && !looksLikeTypeName(name)) {
                out.add(name);
            }
        }
    }

    private static boolean isDescendantOf(Node child, Node possibleAncestor) {
        Node current = child;
        while (current != null) {
            if (current == possibleAncestor) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean containsLine(Node node, int line) {
        Optional<Range> range = node.getRange();
        if (!range.isPresent()) {
            return false;
        }
        Position begin = range.get().begin;
        Position end = range.get().end;
        return begin.line <= line && line <= end.line;
    }

    private static String simpleType(String raw) {
        return CallableSignatureNormalizer.simpleType(raw);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    private static boolean looksLikeTypeName(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static List<Map<String, Object>> dedupeConstraints(List<Map<String, Object>> values) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Map<String, Object> value : values) {
            String key = String.valueOf(value.get("expression")) + "::" + String.valueOf(value.get("requiredEvaluation"));
            if (seen.add(key)) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> dedupe(List<String> values) {
        return new ArrayList<String>(new LinkedHashSet<String>(values));
    }

    private static final class SemanticLift {
        final Expression expression;
        final String kind;

        SemanticLift(Expression expression, String kind) {
            this.expression = expression;
            this.kind = kind == null ? "" : kind;
        }
    }
}
