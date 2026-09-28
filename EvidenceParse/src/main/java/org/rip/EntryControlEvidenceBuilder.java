package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;

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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds minimal prompt-facing evidence about whether the chosen public entry
 * can actually control mutation-sensitive parameters of the real mutation method.
 */
public final class EntryControlEvidenceBuilder {

    private EntryControlEvidenceBuilder() {
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
        public boolean useReflectionFallback = false;
        public boolean skipTestGeneration = false;
        public String skipReason = "";
    }

    public static Map<String, Object> buildEntryParameterControlPlan(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            Analysis a = analyze(ctx);
            out.put("mutationSensitiveParameters", commentedItems(
                    "Mutation-method parameters most likely to determine whether original and mutant diverge",
                    a.sensitiveParameters));
            out.put("entryToMutationBindings", commentedItems(
                    "How the chosen test entry binds each mutation-sensitive parameter at the mutation method call site",
                    a.bindings));
            out.put("controllability", commented(
                    "Whether the chosen public entry can externally control those mutation-sensitive parameters",
                    a.controllability));
            return commented("How the chosen test entry controls parameters of the real mutation method", out);
        } catch (Throwable t) {
            out.put("enabled", commented("Whether parameter-control evidence was built successfully", false));
            out.put("reason", commented("Construction error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
            return commented("How the chosen test entry controls parameters of the real mutation method", out);
        }
    }

    public static Map<String, Object> buildKillabilityPlan(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            Analysis a = analyze(ctx);
            String level = a.uncontrollableSensitiveParameters.isEmpty() ? "LIKELY_FROM_CURRENT_ENTRY" : "UNLIKELY_FROM_CURRENT_ENTRY";
            String mode = a.uncontrollableSensitiveParameters.isEmpty() ? "PUBLIC_ENTRY_OK" : "PUBLIC_ENTRY_UNCONTROLLABLE";
            if (a.receiverStateDependent) {
                level = "LIKELY_WITH_RECEIVER_STATE_SETUP";
                mode = "PUBLIC_ENTRY_NEEDS_STATE_OBSERVATION";
            }
            String category;
            String reason;
            if (ctx.useReflectionFallback) {
                category = "REFLECTION_FALLBACK_ACTIVE";
                reason = "Reflection fallback is already required; public-entry controllability is not the primary bottleneck.";
            } else if (isConstructorExceptionPreferred(ctx)) {
                category = "CONSTRUCTOR_EXCEPTION_PREFERRED";
                reason = "The strongest observable is whether construction completes or throws; prefer constructor exception behavior over weak post-construction state assertions.";
            } else if (!a.uncontrollableSensitiveParameters.isEmpty()) {
                category = "ENTRY_PARAMETER_FIXED";
                reason = "Current chosen entry reaches the mutation, but mutation-sensitive parameters "
                        + a.uncontrollableSensitiveParameters + " are fixed, derived opaquely, or not externally controllable.";
            } else if (a.receiverStateDependent) {
                category = "RECEIVER_STATE_DEPENDENT";
                reason = "The mutation method has no explicit sensitive parameters, but it depends on receiver state "
                        + a.receiverStateDependencies + ". Tests must control constructor/setup state and assert public state/behavior after entry invocation.";
            } else if ("NO_PUBLIC_OBSERVABLE".equalsIgnoreCase(ctx.observablePlanKind)) {
                category = "WEAK_OR_MISSING_OBSERVABLE";
                reason = "Current entry can reach and control the mutation, but no stable public observable was identified.";
            } else {
                category = "LIKELY_FROM_CURRENT_ENTRY";
                reason = "Current entry appears to reach the mutation with controllable sensitive parameters and a usable observable.";
            }

            out.put("entryReachability", commented("Whether the chosen entry can reach the mutation method", "REACHABLE"));
            out.put("parameterControllability", commented("Overall parameter controllability assessment", a.controllabilitySummary));
            out.put("observableStrength", commented("Heuristic quality of the currently selected observable sink", observableStrength(ctx)));
            out.put("killabilityCategory", commented("More specific reason category for the current killability assessment", category));
            out.put("killabilityLevel", commented("Whether generating a killing test from the chosen entry looks promising", level));
            out.put("recommendedMode", commented("Recommended handling mode for downstream generation", mode));
            out.put("reason", commented("Why this killability assessment was made", reason));
            return commented("Overall assessment of whether the chosen entry is suitable for killing this mutant", out);
        } catch (Throwable t) {
            out.put("enabled", commented("Whether killability evidence was built successfully", false));
            out.put("reason", commented("Construction error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
            return commented("Overall assessment of whether the chosen entry is suitable for killing this mutant", out);
        }
    }

    private static Analysis analyze(Context ctx) throws Exception {
        CompilationUnit cu = parse(ctx.originJavaFile);
        ClassOrInterfaceDeclaration owner = findClass(cu, simpleType(ctx.entryClassName))
                .orElseThrow(() -> new IllegalArgumentException("Entry owner not found: " + ctx.entryClassName));
        CallableDeclaration<?> entry = findCallable(owner, ctx.entryMethodSig)
                .orElseThrow(() -> new IllegalArgumentException("Entry callable not found: " + ctx.entryMethodSig));
        CallableDeclaration<?> mutation = findCallable(owner, ctx.mutationMethodSig)
                .orElseThrow(() -> new IllegalArgumentException("Mutation callable not found: " + ctx.mutationMethodSig
                        + " | normalizedExpected=" + normalizeCallableSignature(ctx.mutationMethodSig)
                        + " | candidates=" + describeAvailableCallables(owner)));

        List<String> mutationParams = mutation.getParameters().stream()
                .map(Parameter::getNameAsString)
                .collect(Collectors.toList());
        Set<String> diffTokens = extractDiffIdentifiers(ctx.diff);
        LinkedHashSet<String> sensitive = new LinkedHashSet<String>();
        for (String p : mutationParams) {
            if (diffTokens.contains(p)) {
                sensitive.add(p);
            }
        }
        if (sensitive.isEmpty()) {
            sensitive.addAll(mutationParams);
        }

        Map<String, String> bindings = traceBindings(owner, entry, mutation, new LinkedHashMap<String, String>(), new LinkedHashSet<String>());
        Analysis out = new Analysis();
        if (mutationParams.isEmpty()) {
            out.receiverStateDependencies.addAll(inferReceiverStateDependencies(owner, mutation, ctx.diff));
            out.receiverStateDependent = !out.receiverStateDependencies.isEmpty();
        }
        for (String p : sensitive) {
            Map<String, Object> sensitiveItem = new LinkedHashMap<String, Object>();
            sensitiveItem.put("name", commented("Mutation method parameter name", p));
            sensitiveItem.put("sourceRole", commented("Why this parameter is considered mutation-sensitive",
                    diffTokens.contains(p) ? "Appears directly in the mutation diff" : "Selected conservatively because the diff-sensitive parameter could not be isolated statically"));
            out.sensitiveParameters.add(commented("Mutation-sensitive parameter", sensitiveItem));

            String expr = bindings.get(p);
            Map<String, Object> binding = new LinkedHashMap<String, Object>();
            binding.put("mutationParameter", commented("Parameter of the real mutation method", p));
            binding.put("bindingExpression", commented("Expression passed from the chosen entry to this mutation parameter", expr == null ? "<unknown>" : expr));
            String kind = bindingKind(expr, entry);
            binding.put("bindingKind", commented("How this value is controlled from the chosen entry", kind));
            out.bindings.add(commented("Entry-to-mutation parameter binding", binding));
            if (!"ENTRY_PARAMETER".equals(kind) && !"DERIVED_FROM_ENTRY_INPUT".equals(kind)) {
                out.uncontrollableSensitiveParameters.add(p);
            }
        }

        Map<String, Object> controllability = new LinkedHashMap<String, Object>();
        String overall;
        if (out.receiverStateDependent) {
            overall = "RECEIVER_STATE_DEPENDENT";
        } else {
            overall = out.uncontrollableSensitiveParameters.isEmpty() ? "STRONG" : "WEAK";
        }
        controllability.put("overall", commented("Overall controllability of mutation-sensitive parameters from the chosen entry", overall));
        controllability.put("uncontrollableParameters", commentedItems(
                "Mutation-sensitive parameters that appear fixed, opaque, or not externally controllable from this entry",
                new ArrayList<String>(out.uncontrollableSensitiveParameters)));
        controllability.put("receiverStateDependencies", commentedItems(
                "Receiver fields/method state used by a no-argument mutation method; these must be controlled through entry construction/setup rather than parameter binding",
                new ArrayList<String>(out.receiverStateDependencies)));
        out.controllability = controllability;
        out.controllabilitySummary = overall;
        return out;
    }

    private static Map<String, String> traceBindings(ClassOrInterfaceDeclaration owner,
                                                     CallableDeclaration<?> current,
                                                     CallableDeclaration<?> mutation,
                                                     Map<String, String> currentBindings,
                                                     Set<String> visited) {
        String visitKey = displaySignature(current);
        if (!visited.add(visitKey)) {
            return Collections.emptyMap();
        }

        if (sameCallable(current, mutation)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            for (Parameter p : current.getParameters()) {
                String name = p.getNameAsString();
                out.put(name, currentBindings.containsKey(name) ? currentBindings.get(name) : name);
            }
            return out;
        }

        List<CallSite> callSites = findCandidateCallSites(owner, current, mutation);
        for (CallSite site : callSites) {
            Map<String, String> nextBindings = new LinkedHashMap<String, String>();
            for (int i = 0; i < site.callee.getParameters().size() && i < site.arguments.size(); i++) {
                String formal = site.callee.getParameter(i).getNameAsString();
                String expr = substitute(site.arguments.get(i), currentBindings);
                nextBindings.put(formal, expr);
            }
            Map<String, String> resolved = traceBindings(owner, site.callee, mutation, nextBindings, visited);
            if (!resolved.isEmpty()) {
                return resolved;
            }
        }
        return Collections.emptyMap();
    }

    private static List<CallSite> findCandidateCallSites(ClassOrInterfaceDeclaration owner,
                                                         CallableDeclaration<?> current,
                                                         CallableDeclaration<?> mutation) {
        List<CallSite> out = new ArrayList<CallSite>();
        if (current instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) current;
            for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                Optional<MethodDeclaration> callee = findMethod(owner, call.getNameAsString(), call.getArguments().size());
                if (!callee.isPresent()) {
                    continue;
                }
                if (canReach(owner, callee.get(), mutation, new LinkedHashSet<String>())) {
                    out.add(new CallSite(callee.get(), expressions(call.getArguments())));
                }
            }
        } else if (current instanceof ConstructorDeclaration) {
            ConstructorDeclaration cd = (ConstructorDeclaration) current;
            for (MethodCallExpr call : cd.findAll(MethodCallExpr.class)) {
                Optional<MethodDeclaration> callee = findMethod(owner, call.getNameAsString(), call.getArguments().size());
                if (!callee.isPresent()) {
                    continue;
                }
                if (canReach(owner, callee.get(), mutation, new LinkedHashSet<String>())) {
                    out.add(new CallSite(callee.get(), expressions(call.getArguments())));
                }
            }
            if (!cd.getBody().getStatements().isEmpty()
                    && cd.getBody().getStatement(0).isExplicitConstructorInvocationStmt()) {
                ExplicitConstructorInvocationStmt stmt = cd.getBody().getStatement(0).asExplicitConstructorInvocationStmt();
                Optional<ConstructorDeclaration> ctor = findConstructor(owner, stmt.getArguments().size());
                if (ctor.isPresent() && canReach(owner, ctor.get(), mutation, new LinkedHashSet<String>())) {
                    out.add(new CallSite(ctor.get(), expressions(stmt.getArguments())));
                }
            }
        }
        return out;
    }

    private static boolean canReach(ClassOrInterfaceDeclaration owner,
                                    CallableDeclaration<?> current,
                                    CallableDeclaration<?> mutation,
                                    Set<String> visited) {
        String key = displaySignature(current);
        if (!visited.add(key)) {
            return false;
        }
        if (sameCallable(current, mutation)) {
            return true;
        }
        for (CallSite site : findCandidateCallSites(owner, current, mutation)) {
            if (canReach(owner, site.callee, mutation, visited)) {
                return true;
            }
        }
        return false;
    }

    private static LinkedHashSet<String> inferReceiverStateDependencies(ClassOrInterfaceDeclaration owner,
                                                                        CallableDeclaration<?> mutation,
                                                                        String diff) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        Set<String> diffTokens = extractDiffIdentifiers(diff);
        Set<String> ownerFields = ownerFieldNames(owner);
        out.addAll(fieldsReadBy(mutation, ownerFields));
        out.addAll(intersection(ownerFields, diffTokens));
        if (out.isEmpty() && hasExplicitReceiverStateRead(mutation)) {
            out.add("this.<receiver-state>");
        }
        return out;
    }

    private static String bindingKind(String expr, CallableDeclaration<?> entry) {
        if (expr == null || expr.trim().isEmpty() || "<unknown>".equals(expr)) {
            return "UNKNOWN";
        }
        String trimmed = expr.trim();
        Set<String> entryParams = entry.getParameters().stream()
                .map(Parameter::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (entryParams.contains(trimmed)) {
            return "ENTRY_PARAMETER";
        }
        Set<String> referencedNames = identifiers(trimmed);
        for (String p : entryParams) {
            if (referencedNames.contains(p)) {
                return "DERIVED_FROM_ENTRY_INPUT";
            }
        }
        if (isLiteralLike(trimmed)) {
            return "CONSTANT";
        }
        if (trimmed.startsWith("this.") || trimmed.contains(".")) {
            return "FIELD_OR_OBJECT_STATE";
        }
        return "UNKNOWN";
    }

    private static String observableStrength(Context ctx) {
        String kind = safe(ctx.observablePlanKind).toUpperCase(Locale.ROOT);
        String call = safe(ctx.observableCall).toLowerCase(Locale.ROOT);
        if (call.contains("tostring()") || kind.contains("APPENDABLE")) {
            return "STRONG";
        }
        if (kind.contains("EXCEPTION") || kind.contains("THROWABLE")) {
            return "STRONG";
        }
        if (kind.contains("GETTER") || kind.contains("STATE")) {
            return "MEDIUM";
        }
        return "UNKNOWN";
    }

    private static boolean isConstructorExceptionPreferred(Context ctx) {
        String kind = safe(ctx.observablePlanKind).toUpperCase(Locale.ROOT);
        String call = safe(ctx.observableCall).toLowerCase(Locale.ROOT);
        return kind.contains("CONSTRUCTOR_COMPLETES_VS_EXCEPTION")
                || kind.contains("THROWABLE")
                || call.contains("throws")
                || call.contains("construction completes");
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
        String normalizedExpected = normalizeCallableSignature(sig);
        for (ConstructorDeclaration c : owner.getConstructors()) {
            if (normalizeCallableSignature(callableSignatureForMatch(c)).equals(normalizedExpected)) {
                return Optional.of((CallableDeclaration<?>) c);
            }
        }
        for (MethodDeclaration m : owner.getMethods()) {
            if (normalizeCallableSignature(callableSignatureForMatch(m)).equals(normalizedExpected)) {
                return Optional.of((CallableDeclaration<?>) m);
            }
        }
        return Optional.empty();
    }

    private static String normalizeCallableSignature(String sig) {
        return PromptSignatureFormatter.method(safe(sig))
                .replaceAll("\\s+", "")
                .replace('$', '.');
    }

    private static Optional<MethodDeclaration> findMethod(ClassOrInterfaceDeclaration owner, String name, int arity) {
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.getNameAsString().equals(name) && m.getParameters().size() == arity) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    private static Optional<ConstructorDeclaration> findConstructor(ClassOrInterfaceDeclaration owner, int arity) {
        for (ConstructorDeclaration c : owner.getConstructors()) {
            if (c.getParameters().size() == arity) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
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

    private static boolean sameCallable(CallableDeclaration<?> a, CallableDeclaration<?> b) {
        if (a == null || b == null) {
            return false;
        }
        return displaySignature(a).equals(displaySignature(b));
    }

    private static String displaySignature(CallableDeclaration<?> c) {
        List<String> ps = c.getParameters().stream()
                .map(p -> simpleType(p.getType().asString()))
                .collect(Collectors.toList());
        return c.getNameAsString() + "(" + String.join(",", ps) + ")";
    }

    private static String callableSignatureForMatch(CallableDeclaration<?> c) {
        List<String> ps = c.getParameters().stream()
                .map(p -> simpleType(p.getType().asString()))
                .collect(Collectors.toList());
        String joined = String.join(",", ps);
        if (c instanceof ConstructorDeclaration) {
            return c.getNameAsString() + "(" + joined + ")";
        }
        if (c instanceof MethodDeclaration) {
            MethodDeclaration m = (MethodDeclaration) c;
            return simpleType(m.getType().asString()) + " " + m.getNameAsString() + "(" + joined + ")";
        }
        return displaySignature(c);
    }

    private static String describeAvailableCallables(ClassOrInterfaceDeclaration owner) {
        List<String> candidates = new ArrayList<String>();
        for (ConstructorDeclaration c : owner.getConstructors()) {
            candidates.add(callableSignatureForMatch(c) + " => " + normalizeCallableSignature(callableSignatureForMatch(c)));
        }
        for (MethodDeclaration m : owner.getMethods()) {
            candidates.add(callableSignatureForMatch(m) + " => " + normalizeCallableSignature(callableSignatureForMatch(m)));
        }
        return candidates.toString();
    }

    private static List<String> expressions(List<Expression> args) {
        List<String> out = new ArrayList<String>();
        for (Expression arg : args) {
            out.add(arg == null ? "" : arg.toString());
        }
        return out;
    }

    private static String substitute(String expr, Map<String, String> bindings) {
        if (expr == null) {
            return "";
        }
        String out = expr;
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.trim().isEmpty() || value == null || value.trim().isEmpty()) {
                continue;
            }
            out = out.replaceAll("\\b" + Pattern.quote(key) + "\\b", java.util.regex.Matcher.quoteReplacement(value));
        }
        return out;
    }

    private static Set<String> extractDiffIdentifiers(String diff) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (diff == null) {
            return out;
        }
        java.util.regex.Matcher m = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(diff);
        while (m.find()) {
            String token = m.group();
            if (!STOP_WORDS.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    private static Set<String> identifiers(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        java.util.regex.Matcher m = Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$]*\\b")
                .matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
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

    private static LinkedHashSet<String> fieldsReadBy(CallableDeclaration<?> callable, Set<String> ownerFields) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (callable == null || ownerFields == null || ownerFields.isEmpty()) {
            return out;
        }
        Set<String> shadowed = shadowedNames(callable);
        for (FieldAccessExpr access : callable.findAll(FieldAccessExpr.class)) {
            String field = access.getNameAsString();
            if (ownerFields.contains(field) && isOwnerScope(access.getScope())) {
                out.add(field);
            }
        }
        for (NameExpr name : callable.findAll(NameExpr.class)) {
            String field = name.getNameAsString();
            if (ownerFields.contains(field) && !shadowed.contains(field)) {
                out.add(field);
            }
        }
        return out;
    }

    private static boolean hasExplicitReceiverStateRead(CallableDeclaration<?> callable) {
        if (callable == null) {
            return false;
        }
        for (FieldAccessExpr access : callable.findAll(FieldAccessExpr.class)) {
            if (isOwnerScope(access.getScope())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnerScope(Expression scope) {
        return scope != null && (scope.isThisExpr() || scope.isSuperExpr());
    }

    private static LinkedHashSet<String> shadowedNames(CallableDeclaration<?> callable) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (callable == null) {
            return out;
        }
        for (Parameter p : callable.getParameters()) {
            out.add(p.getNameAsString());
        }
        for (VariableDeclarator v : callable.findAll(VariableDeclarator.class)) {
            out.add(v.getNameAsString());
        }
        return out;
    }

    private static LinkedHashSet<String> intersection(Set<String> left, Set<String> right) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (left == null || right == null) {
            return out;
        }
        for (String item : left) {
            if (right.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private static boolean isLiteralLike(String value) {
        String v = value.trim();
        return "null".equals(v)
                || "true".equals(v)
                || "false".equals(v)
                || v.matches("[-+]?\\d+[Ll]?")
                || v.matches("[-+]?\\d+\\.\\d+[fFdD]?")
                || (v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'"));
    }

    private static CompilationUnit parse(String javaFile) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration();
        JavaParser parser = new JavaParser(cfg);
        return parser.parse(new File(javaFile)).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Failed to parse Java file: " + javaFile));
    }

    private static boolean isConstructorSignature(String sig) {
        if (sig == null) return false;
        int lp = sig.indexOf('(');
        if (lp <= 0) return false;
        String head = sig.substring(0, lp).trim();
        return !head.contains(" ");
    }

    private static String methodName(String sig) {
        if (sig == null) return "";
        int lp = sig.indexOf('(');
        String head = lp < 0 ? sig.trim() : sig.substring(0, lp).trim();
        int sp = head.lastIndexOf(' ');
        return sp >= 0 ? head.substring(sp + 1).trim() : head;
    }

    private static List<String> params(String sig) {
        if (sig == null) return Collections.emptyList();
        int lp = sig.indexOf('('), rp = sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) return Collections.emptyList();
        String inside = sig.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == '<') depth++;
            if (ch == '>') depth--;
            if (ch == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static String simpleType(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace("$", ".");
        s = s.replaceAll("<.*>", "");
        s = s.replace("...", "[]");
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        return s.trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final Set<String> STOP_WORDS = new LinkedHashSet<String>();
    static {
        Collections.addAll(STOP_WORDS, "if", "else", "true", "false", "null", "new", "return", "throw", "this", "super");
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
        out.put("items", items);
        return out;
    }

    private static final class Analysis {
        final List<Object> sensitiveParameters = new ArrayList<Object>();
        final List<Object> bindings = new ArrayList<Object>();
        final LinkedHashSet<String> uncontrollableSensitiveParameters = new LinkedHashSet<String>();
        final LinkedHashSet<String> receiverStateDependencies = new LinkedHashSet<String>();
        boolean receiverStateDependent = false;
        Map<String, Object> controllability = new LinkedHashMap<String, Object>();
        String controllabilitySummary = "UNKNOWN";
    }

    private static final class CallSite {
        final CallableDeclaration<?> callee;
        final List<String> arguments;

        private CallSite(CallableDeclaration<?> callee, List<String> arguments) {
            this.callee = callee;
            this.arguments = arguments;
        }
    }
}
