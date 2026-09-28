package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight source-level extraction of mandatory path constraints and
 * cofactors.
 */
public final class ControlPathConstraintExtractor {
    private ControlPathConstraintExtractor() {
    }

    public static final class Context {
        public String source = "";
        public String mutationOriginalExpression = "";
        public String mutationMutantExpression = "";
        public List<String> ancestorGuards = new ArrayList<String>();
        public List<Map<String, Object>> ancestorGuardConstraints = new ArrayList<Map<String, Object>>();
        public Set<String> requiredNonNullVariables = new LinkedHashSet<String>();
        public String semanticOriginalExpression = "";
        public String semanticMutantExpression = "";
        public int mutationIndex = -1;
    }

    public static final class Result {
        public final List<Map<String, Object>> mandatoryGuards = new ArrayList<Map<String, Object>>();
        public final List<Map<String, Object>> mandatoryCofactors = new ArrayList<Map<String, Object>>();
        public final List<Map<String, Object>> weakFallbackGuards = new ArrayList<Map<String, Object>>();
        public final Set<String> requiredNonNullSubjects = new LinkedHashSet<String>();
        public boolean hasFalsePolarityGuard;
        public boolean hasFallthroughGuard;
        public boolean unreachableByPrecedingTerminator;
        public boolean controlPathConflict;
        public int polarityInversionCount;
        public String reason = "";

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("mandatoryGuards", mandatoryGuards);
            out.put("mandatoryCofactors", mandatoryCofactors);
            out.put("weakFallbackGuards", weakFallbackGuards);
            out.put("requiredNonNullSubjects", new ArrayList<String>(requiredNonNullSubjects));
            out.put("hasFalsePolarityGuard", hasFalsePolarityGuard);
            out.put("hasFallthroughGuard", hasFallthroughGuard);
            out.put("unreachableByPrecedingTerminator", unreachableByPrecedingTerminator);
            out.put("controlPathConflict", controlPathConflict);
            out.put("polarityInversionCount", polarityInversionCount);
            out.put("reason", reason);
            return out;
        }
    }

    public static Result extract(Context ctx) {
        Result out = new Result();
        if (ctx == null) {
            out.reason = "missing context";
            return out;
        }

        SourceControlPath sourceControlPath = sourceControlPath(ctx);
        if (sourceControlPath.astResolved) {
            out.mandatoryGuards.addAll(sourceControlPath.guards);
            out.hasFallthroughGuard = sourceControlPath.hasFallthroughGuard;
            out.unreachableByPrecedingTerminator = sourceControlPath.unreachableByPrecedingTerminator;
        } else {
            for (Map<String, Object> structured : ctx.ancestorGuardConstraints) {
                Map<String, Object> item = normalizeGuardConstraint(structured);
                if (!item.isEmpty()) {
                    out.mandatoryGuards.add(item);
                }
            }
            for (String guard : ctx.ancestorGuards) {
                if (containsGuardExpression(out.mandatoryGuards, guard)) {
                    continue;
                }
                Map<String, Object> item = guard(String.valueOf(guard), true, "AST_ANCESTOR",
                        "Ancestor branch must hold for the mutation site to execute.");
                out.mandatoryGuards.add(item);
            }

        }
        dedupeGuards(out);
        addNonNullSubjectsFromGuards(out);

        for (String value : ctx.requiredNonNullVariables) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            out.requiredNonNullSubjects.add(value);
            out.mandatoryGuards.add(nonNullGuard(value));
        }
        dedupeGuards(out);

        out.mandatoryCofactors.addAll(cofactors(ctx.semanticOriginalExpression, ctx.semanticMutantExpression,
                ctx.mutationOriginalExpression, ctx.mutationMutantExpression));
        out.polarityInversionCount = polarityInversionCount(
                firstNonBlank(ctx.semanticOriginalExpression, ctx.mutationOriginalExpression),
                firstNonBlank(ctx.mutationOriginalExpression, ctx.semanticOriginalExpression));
        // The inversion count describes the local original expression inside the
        // semantic original container. Mutant text must not be used as the target
        // because it does not exist in the original AST.
        out.controlPathConflict = hasGuardConflict(out.mandatoryGuards);
        if (out.controlPathConflict) {
            demoteConflictingGuards(out);
        }
        out.hasFalsePolarityGuard = out.mandatoryGuards.stream()
                .map(m -> String.valueOf(m.get("requiredEvaluation")))
                .anyMatch("false"::equalsIgnoreCase);
        out.reason = out.mandatoryGuards.isEmpty() && out.mandatoryCofactors.isEmpty()
                ? "No mandatory path constraints or cofactors could be extracted."
                : sourceControlPath.astResolved
                        ? "Extracted mandatory guards, non-null subjects, and propagation cofactors from JavaParser AST evidence."
                        : "JavaParser could not resolve the mutation node; only already-structured fallback evidence was retained, without regex control-flow guessing.";
        return out;
    }

    private static SourceControlPath sourceControlPath(Context ctx) {
        SourceControlPath out = new SourceControlPath();
        CallableDeclaration<?> callable = parseSingleCallable(ctx.source);
        if (callable == null) {
            return out;
        }
        Node mutationNode = findMutationNode(callable, ctx);
        if (mutationNode == null) {
            return out;
        }
        out.astResolved = true;
        out.guards.addAll(astAncestorGuards(mutationNode));
        collectPrecedingTerminatingGuards(mutationNode, out);
        return out;
    }

    private static CallableDeclaration<?> parseSingleCallable(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<String>();
        candidates.add(source);
        candidates.add("class __RipTmp {\n" + source + "\n}");
        candidates.add("class __RipTmp {\nvoid __ripTmp() " + source + "\n}");
        JavaParser parser = new JavaParser(new ParserConfiguration().setAttributeComments(false));
        for (String candidate : candidates) {
            try {
                CompilationUnit cu = parser.parse(candidate).getResult().orElse(null);
                if (cu == null) {
                    continue;
                }
                List<CallableDeclaration> callables = cu.findAll(CallableDeclaration.class);
                if (!callables.isEmpty()) {
                    return callables.get(0);
                }
            } catch (Throwable ignored) {
                // Try the next wrapping style.
            }
        }
        return null;
    }

    private static Expression parseExpression(String source) {
    if (source == null || source.trim().isEmpty()) {
        return null;
    }
    try {
        return StaticJavaParser.parseExpression(source.trim());
    } catch (RuntimeException ex) {
        return null;
    }
}

    private static Node findMutationNode(CallableDeclaration<?> callable, Context ctx) {
        int line = lineOfIndex(ctx.source, ctx.mutationIndex);
        Expression localOriginal = parseExpression(ctx.mutationOriginalExpression);
        Expression semanticOriginal = parseExpression(ctx.semanticOriginalExpression);

        Node best = findBestExpressionNode(callable, localOriginal, line);
        if (best != null) {
            return best;
        }
        best = findBestExpressionNode(callable, semanticOriginal, line);
        if (best != null) {
            return best;
        }

        // Statement-level operators (for example SDL/ODL) may not have a
        // parseable expression pair. Fall back to the smallest/deepest statement
        // containing the recorded mutation line. This is AST-based and avoids
        // source-window/regex control-flow guessing.
        Statement bestStatement = null;
        int bestSpan = Integer.MAX_VALUE;
        for (Statement stmt : callable.findAll(Statement.class)) {
            if (line > 0 && !containsLine(stmt, line)) {
                continue;
            }
            int span = statementSpan(stmt);
            if (bestStatement == null || span < bestSpan) {
                bestStatement = stmt;
                bestSpan = span;
            }
        }
        return bestStatement;
    }

    private static Node findBestExpressionNode(CallableDeclaration<?> callable,
                                               Expression expected,
                                               int line) {
        if (callable == null || expected == null) {
            return null;
        }
        Expression best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Expression expression : callable.findAll(Expression.class)) {
            if (!equivalentExpression(expression, expected)) {
                continue;
            }
            int score = 1000;
            if (line > 0 && containsLine(expression, line)) {
                score += 500;
            }
            score -= expression.toString().length();
            if (score > bestScore) {
                best = expression;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean equivalentExpression(Expression left, Expression right) {
        if (left == null || right == null) {
            return false;
        }
        Expression a = unwrapExpression(left);
        Expression b = unwrapExpression(right);
        return a.equals(b) || normalize(a.toString()).equals(normalize(b.toString()));
    }

    private static Expression unwrapExpression(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static int statementSpan(Statement statement) {
        if (statement == null || !statement.getRange().isPresent()) {
            return Integer.MAX_VALUE / 2;
        }
        com.github.javaparser.Range range = statement.getRange().get();
        return Math.max(0, range.end.line - range.begin.line) * 1000
                + Math.max(0, range.end.column - range.begin.column);
    }

    private static boolean containsEquivalentExpression(Node node, Expression expected) {
        if (node == null || expected == null) {
            return false;
        }
        for (Expression expression : node.findAll(Expression.class)) {
            if (unwrap(expression).equals(unwrap(expected))) {
                return true;
            }
        }
        return false;
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static boolean containsLine(Node node, int line) {
        if (line <= 0 || node == null || !node.getRange().isPresent()) {
            return false;
        }
        Position begin = node.getRange().get().begin;
        Position end = node.getRange().get().end;
        return line >= begin.line && line <= end.line;
    }

    private static int lineOfIndex(String source, int index) {
        if (source == null || index < 0) {
            return -1;
        }
        int limit = Math.min(source.length(), index);
        int line = 1;
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Map<String, Object>> astAncestorGuards(Node mutationNode) {
        List<Map<String, Object>> guards = new ArrayList<Map<String, Object>>();
        Node current = mutationNode;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            if (parent instanceof IfStmt) {
                IfStmt ifStmt = (IfStmt) parent;
                if (containsNode(ifStmt.getThenStmt(), current)) {
                    guards.add(guard(ifStmt.getCondition().toString(), true, "AST_ANCESTOR",
                            "Mutation is inside the then branch of this IfStmt."));
                } else if (ifStmt.getElseStmt().isPresent()
                        && containsNode(ifStmt.getElseStmt().get(), current)) {
                    guards.add(guard(ifStmt.getCondition().toString(), false, "ELSE_BRANCH",
                            "Mutation is inside the else branch of this IfStmt."));
                }
            }
            current = parent;
        }
        return reverse(guards);
    }

    private static void collectPrecedingTerminatingGuards(Node mutationNode, SourceControlPath out) {
        Node current = mutationNode;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            if (parent instanceof BlockStmt) {
                collectPrecedingTerminatingGuards((BlockStmt) parent, current, out);
            }
            current = parent;
        }
    }

    private static void collectPrecedingTerminatingGuards(BlockStmt block, Node directChild, SourceControlPath out) {
        List<Statement> statements = block.getStatements();
        int index = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) == directChild || containsNode(statements.get(i), directChild)) {
                index = i;
                break;
            }
        }
        if (index <= 0) {
            return;
        }
        for (int i = 0; i < index; i++) {
            Statement stmt = statements.get(i);
            if (!stmt.isIfStmt()) {
                continue;
            }
            IfStmt ifStmt = stmt.asIfStmt();
            boolean thenTerminates = definitelyTerminates(ifStmt.getThenStmt());
            boolean elseTerminates = ifStmt.getElseStmt().isPresent()
                    && definitelyTerminates(ifStmt.getElseStmt().get());
            if (thenTerminates && elseTerminates) {
                out.unreachableByPrecedingTerminator = true;
                continue;
            }
            if (thenTerminates) {
                out.guards.add(guard(ifStmt.getCondition().toString(), false, "PRECEDING_TERMINATING_GUARD",
                        "Then branch terminates; condition must be false to fall through to the mutation."));
                out.hasFallthroughGuard = true;
            } else if (elseTerminates) {
                out.guards.add(guard(ifStmt.getCondition().toString(), true, "PRECEDING_TERMINATING_GUARD",
                        "Else branch terminates; condition must be true to fall through to the mutation."));
                out.hasFallthroughGuard = true;
            }
        }
    }

    private static boolean definitelyTerminates(Statement statement) {
        if (statement == null) {
            return false;
        }
        if (statement.isReturnStmt() || statement.isThrowStmt()) {
            return true;
        }
        if (statement.isBlockStmt()) {
            BlockStmt block = statement.asBlockStmt();
            if (block.getStatements().isEmpty()) {
                return false;
            }
            return definitelyTerminates(block.getStatement(block.getStatements().size() - 1));
        }
        if (statement.isIfStmt()) {
            IfStmt ifStmt = statement.asIfStmt();
            return ifStmt.getElseStmt().isPresent()
                    && definitelyTerminates(ifStmt.getThenStmt())
                    && definitelyTerminates(ifStmt.getElseStmt().get());
        }
        return false;
    }

    private static boolean containsNode(Node container, Node target) {
        if (container == null || target == null) {
            return false;
        }
        if (container == target) {
            return true;
        }
        Node current = target;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current == container) {
                return true;
            }
        }
        return false;
    }

    private static void dedupeGuards(Result result) {
        List<Map<String, Object>> deduped = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Map<String, Object> guard : result.mandatoryGuards) {
            GuardIdentity identity = guardIdentity(guard);
            String key = identity.expression + "::" + identity.requiredEvaluation;
            if (seen.add(key)) {
                Map<String, Object> normalized = new LinkedHashMap<String, Object>(guard);
                normalized.put("expression", identity.expression);
                normalized.put("requiredEvaluation", identity.requiredEvaluation);
                deduped.add(normalized);
            }
        }
        result.mandatoryGuards.clear();
        result.mandatoryGuards.addAll(deduped);
    }

    private static Map<String, Object> normalizeGuardConstraint(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String expression = String.valueOf(raw.get("expression"));
        if (expression == null || expression.trim().isEmpty() || "null".equals(expression)) {
            return out;
        }
        Object requiredRaw = raw.containsKey("requiredEvaluation") ? raw.get("requiredEvaluation") : Boolean.TRUE;
        GuardIdentity identity = guardIdentity(expression, Boolean.parseBoolean(String.valueOf(requiredRaw)));
        out.put("expression", identity.expression);
        out.put("requiredEvaluation", identity.requiredEvaluation);
        out.put("source", String.valueOf(raw.containsKey("source") ? raw.get("source") : "AST_ANCESTOR"));
        out.put("hardness", String.valueOf(raw.containsKey("hardness") ? raw.get("hardness") : "MANDATORY"));
        out.put("reason", String.valueOf(raw.containsKey("reason")
                ? raw.get("reason")
                : "Structured ancestor guard derived from JavaParser."));
        Object confidence = raw.containsKey("confidence") ? raw.get("confidence") : Double.valueOf(0.80d);
        out.put("confidence", confidence);
        return out;
    }

    private static boolean containsGuardExpression(List<Map<String, Object>> guards, String expression) {
        GuardIdentity targetTrue = guardIdentity(expression, true);
        GuardIdentity targetFalse = guardIdentity(expression, false);
        if (targetTrue.expression.isEmpty()) {
            return true;
        }
        for (Map<String, Object> guard : guards == null ? new ArrayList<Map<String, Object>>() : guards) {
            GuardIdentity existing = guardIdentity(guard);
            if (existing.expression.equals(targetTrue.expression)
                    || existing.expression.equals(targetFalse.expression)) {
                return true;
            }
        }
        return false;
    }

    private static void demoteConflictingGuards(Result result) {
        if (result == null || result.mandatoryGuards.isEmpty()) {
            return;
        }
        Set<String> trueGuards = new LinkedHashSet<String>();
        Set<String> falseGuards = new LinkedHashSet<String>();
        for (Map<String, Object> guard : result.mandatoryGuards) {
            GuardIdentity identity = guardIdentity(guard);
            if (identity.expression.isEmpty()) {
                continue;
            }
            if (identity.requiredEvaluation) {
                trueGuards.add(identity.expression);
            } else {
                falseGuards.add(identity.expression);
            }
        }
        Set<String> conflicts = new LinkedHashSet<String>(trueGuards);
        conflicts.retainAll(falseGuards);
        if (conflicts.isEmpty()) {
            return;
        }

        List<Map<String, Object>> retained = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> guard : result.mandatoryGuards) {
            GuardIdentity identity = guardIdentity(guard);
            if (!conflicts.contains(identity.expression)) {
                retained.add(guard);
                continue;
            }
            Map<String, Object> weak = new LinkedHashMap<String, Object>(guard);
            weak.put("hardness", "OPTIONAL_HINT");
            weak.put("confidence", 0.20d);
            weak.put("source", "CONFLICTING_STATIC_GUARD");
            weak.put("reason", "Conflicting true/false static guard evidence was detected; do not enforce this guard as mandatory until the AST path is resolved.");
            result.weakFallbackGuards.add(weak);
        }
        result.mandatoryGuards.clear();
        result.mandatoryGuards.addAll(retained);
    }

    private static boolean hasGuardConflict(List<Map<String, Object>> guards) {
        Set<String> trueGuards = new LinkedHashSet<String>();
        Set<String> falseGuards = new LinkedHashSet<String>();
        for (Map<String, Object> guard : guards) {
            GuardIdentity identity = guardIdentity(guard);
            if (identity.expression.isEmpty()) {
                continue;
            }
            if (identity.requiredEvaluation) {
                trueGuards.add(identity.expression);
            } else {
                falseGuards.add(identity.expression);
            }
        }
        for (String expression : trueGuards) {
            if (falseGuards.contains(expression)) {
                return true;
            }
        }
        return false;
    }

    private static GuardIdentity guardIdentity(Map<String, Object> guard) {
        if (guard == null) {
            return new GuardIdentity("", true);
        }
        return guardIdentity(
                String.valueOf(guard.get("expression")),
                Boolean.parseBoolean(String.valueOf(guard.get("requiredEvaluation"))));
    }

    private static GuardIdentity guardIdentity(String expression, boolean requiredEvaluation) {
        Expression parsed = parseExpression(expression);
        boolean required = requiredEvaluation;
        if (parsed != null) {
            parsed = unwrapExpression(parsed);
            while (parsed instanceof UnaryExpr
                    && ((UnaryExpr) parsed).getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                required = !required;
                parsed = unwrapExpression(((UnaryExpr) parsed).getExpression());
            }
            return new GuardIdentity(normalize(parsed.toString()), required);
        }
        return new GuardIdentity(normalize(expression), required);
    }

    private static void addNonNullSubjectsFromGuards(Result result) {
        if (result == null) {
            return;
        }
        Set<String> subjects = new LinkedHashSet<String>();
        for (Map<String, Object> guard : result.mandatoryGuards) {
            Expression parsed = parseExpression(String.valueOf(guard.get("expression")));
            if (parsed == null) {
                continue;
            }
            for (FieldAccessExpr access : parsed.findAll(FieldAccessExpr.class)) {
                Expression scope = access.getScope();
                if (scope.isNameExpr()) {
                    String subject = scope.asNameExpr().getNameAsString();
                    if (!subject.isEmpty() && !looksLikeTypeName(subject)) {
                        subjects.add(subject);
                    }
                }
            }
        }
        for (String subject : subjects) {
            result.requiredNonNullSubjects.add(subject);
            result.mandatoryGuards.add(nonNullGuard(subject));
        }
        dedupeGuards(result);
    }

    private static boolean looksLikeTypeName(String value) {
        return value != null && !value.isEmpty() && Character.isUpperCase(value.charAt(0));
    }

    private static final class GuardIdentity {
        final String expression;
        final boolean requiredEvaluation;

        GuardIdentity(String expression, boolean requiredEvaluation) {
            this.expression = expression == null ? "" : expression;
            this.requiredEvaluation = requiredEvaluation;
        }
    }

    private static List<Map<String, Object>> cofactors(String semanticOriginalExpression,
                                                       String semanticMutantExpression,
                                                       String localOriginalExpression,
                                                       String localMutantExpression) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String original = firstNonBlank(semanticOriginalExpression, localOriginalExpression);
        String mutant = firstNonBlank(semanticMutantExpression, localMutantExpression);
        if (original.trim().isEmpty() || mutant.trim().isEmpty()) {
            return out;
        }

        out.addAll(booleanPropagationCofactors(original, mutant, localOriginalExpression));
        return out;
    }

    private static List<Map<String, Object>> booleanPropagationCofactors(String original,
                                                                         String mutant,
                                                                         String localOriginalExpression) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        out.addAll(booleanParentChainCofactors(original, localOriginalExpression));
        if (!out.isEmpty()) {
            return out;
        }
        SplitExpression originalSplit = splitBooleanExpressionAst(original);
        SplitExpression mutantSplit = splitBooleanExpressionAst(mutant);
        if (!originalSplit.valid && !mutantSplit.valid) {
            return out;
        }

        if (originalSplit.valid && mutantSplit.valid
                && normalize(originalSplit.left).equals(normalize(mutantSplit.left))
                && normalize(originalSplit.right).equals(normalize(mutantSplit.right))
                && !originalSplit.operator.equals(mutantSplit.operator)) {
            out.add(constraint(
                    trimOuterParens(originalSplit.left) + " != " + trimOuterParens(originalSplit.right),
                    true,
                    "BOOLEAN_OPERATOR_REPLACEMENT",
                    "For &&/|| replacement, the two operands must differ so original and mutant boolean values diverge."));
            return out;
        }

        SplitExpression split = originalSplit.valid ? originalSplit : mutantSplit;
        if (!split.valid) {
            return out;
        }
        boolean rightChanged = mutantSplit.valid
                && normalize(split.left).equals(normalize(mutantSplit.left))
                && !normalize(split.right).equals(normalize(mutantSplit.right));
        boolean leftChanged = mutantSplit.valid
                && normalize(split.right).equals(normalize(mutantSplit.right))
                && !normalize(split.left).equals(normalize(mutantSplit.left));
        String sibling = rightChanged ? split.left : leftChanged ? split.right : "";
        if (sibling.trim().isEmpty()) {
            return out;
        }
        boolean required = "&&".equals(split.operator);
        out.add(constraint(trimOuterParens(sibling), required, "BOOLEAN_PROPAGATION",
                "Sibling operand must be " + required
                        + " so the changed operand can influence the enclosing boolean expression."));
        return out;
    }

    private static List<Map<String, Object>> booleanParentChainCofactors(String semanticExpression,
                                                                         String localExpression) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (semanticExpression == null || semanticExpression.trim().isEmpty()
                || localExpression == null || localExpression.trim().isEmpty()) {
            return out;
        }
        try {
            Expression root = StaticJavaParser.parseExpression(semanticExpression);
            Expression mutation = findMatchingExpression(root, localExpression);
            if (mutation == null || mutation == root) {
                return out;
            }
            Node current = mutation;
            while (current.getParentNode().isPresent()) {
                Node parent = current.getParentNode().get();
                if (parent instanceof BinaryExpr) {
                    BinaryExpr binary = (BinaryExpr) parent;
                    BinaryExpr.Operator operator = binary.getOperator();
                    if (operator == BinaryExpr.Operator.AND || operator == BinaryExpr.Operator.OR) {
                        Expression sibling = containsNode(binary.getLeft(), current)
                                ? binary.getRight()
                                : containsNode(binary.getRight(), current) ? binary.getLeft() : null;
                        if (sibling != null) {
                            boolean required = operator == BinaryExpr.Operator.AND;
                            out.add(constraint(
                                    trimOuterParens(sibling.toString()),
                                    required,
                                    "BOOLEAN_PROPAGATION",
                                    "Sibling operand must be " + required
                                            + " for the mutation-local difference to propagate through "
                                            + operator.asString() + "."));
                        }
                    }
                }
                if (parent == root) {
                    break;
                }
                current = parent;
            }
            return out;
        } catch (Throwable ignored) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private static Expression findMatchingExpression(Expression root, String target) {
        String normalizedTarget = normalize(target);
        if (normalizedTarget.isEmpty()) {
            return null;
        }
        Expression best = null;
        int bestLength = Integer.MAX_VALUE;
        for (Expression expression : root.findAll(Expression.class)) {
            if (!normalize(expression.toString()).equals(normalizedTarget)) {
                continue;
            }
            int length = expression.toString().length();
            if (length < bestLength) {
                bestLength = length;
                best = expression;
            }
        }
        return best;
    }

    private static int polarityInversionCount(String semanticExpression, String localExpression) {
        if (semanticExpression == null || semanticExpression.trim().isEmpty()
                || localExpression == null || localExpression.trim().isEmpty()) {
            return 0;
        }
        try {
            Expression root = StaticJavaParser.parseExpression(semanticExpression);
            Expression mutation = findMatchingExpression(root, localExpression);
            if (mutation == null) {
                return 0;
            }
            int count = 0;
            Node current = mutation;
            while (current.getParentNode().isPresent()) {
                Node parent = current.getParentNode().get();
                if (parent instanceof UnaryExpr
                        && ((UnaryExpr) parent).getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                    count++;
                }
                if (parent == root) {
                    break;
                }
                current = parent;
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static SplitExpression splitBooleanExpressionAst(String expression) {
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            while (parsed.isEnclosedExpr()) {
                parsed = parsed.asEnclosedExpr().getInner();
            }
            if (!parsed.isBinaryExpr()) {
                return SplitExpression.invalid();
            }
            BinaryExpr binary = parsed.asBinaryExpr();
            String op = binary.getOperator() == BinaryExpr.Operator.AND ? "&&"
                    : binary.getOperator() == BinaryExpr.Operator.OR ? "||"
                    : "";
            if (op.isEmpty()) {
                return SplitExpression.invalid();
            }
            return new SplitExpression(
                    trimOuterParens(binary.getLeft().toString()),
                    trimOuterParens(binary.getRight().toString()),
                    op,
                    true);
        } catch (Throwable ignored) {
            return SplitExpression.invalid();
        }
    }

    private static final class SplitExpression {
        final String left;
        final String right;
        final String operator;
        final boolean valid;

        SplitExpression(String left, String right, String operator, boolean valid) {
            this.left = left == null ? "" : left;
            this.right = right == null ? "" : right;
            this.operator = operator == null ? "" : operator;
            this.valid = valid;
        }

        static SplitExpression invalid() {
            return new SplitExpression("", "", "", false);
        }
    }

    private static Map<String, Object> guard(String expression, boolean requiredEvaluation, String source, String reason) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("expression", expression);
        out.put("requiredEvaluation", requiredEvaluation);
        out.put("source", source);
        out.put("hardness", "MANDATORY");
        out.put("reason", reason);
        out.put("confidence", 0.80d);
        return out;
    }

    private static Map<String, Object> weakGuard(String expression, boolean requiredEvaluation, String source, String reason) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("expression", expression);
        out.put("requiredEvaluation", requiredEvaluation);
        out.put("source", source);
        out.put("hardness", "OPTIONAL_HINT");
        out.put("reason", reason);
        out.put("confidence", 0.35d);
        return out;
    }

    private static Map<String, Object> nonNullGuard(String subject) {
        return constraint(subject + " != null", true, "NON_NULL_DEREFERENCE",
                "Dereferenced subject must be non-null for the mutation-sensitive expression to execute.");
    }

    private static Map<String, Object> constraint(String expression,
                                                  boolean requiredEvaluation,
                                                  String source,
                                                  String reason) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("expression", expression);
        out.put("requiredEvaluation", requiredEvaluation);
        out.put("source", source);
        out.put("hardness", "MANDATORY");
        out.put("reason", reason);
        out.put("confidence", 0.85d);
        return out;
    }

    private static String trimOuterParens(String value) {
        String out = value == null ? "" : value.trim();
        while (out.startsWith("(") && out.endsWith(")") && balanced(out.substring(1, out.length() - 1))) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    private static boolean balanced(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static List<String> dedupe(List<String> items) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String item : items) {
            String text = item == null ? "" : item.trim();
            if (!text.isEmpty() && seen.add(text)) {
                out.add(text);
            }
        }
        return out;
    }

    private static <T> List<T> reverse(List<T> items) {
        List<T> out = new ArrayList<T>(items);
        java.util.Collections.reverse(out);
        return out;
    }

    private static final class SourceControlPath {
        final List<Map<String, Object>> guards = new ArrayList<Map<String, Object>>();
        boolean astResolved;
        boolean hasFallthroughGuard;
        boolean unreachableByPrecedingTerminator;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
