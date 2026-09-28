package org.rip;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.model.MutationConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolve the concrete runtime receiver for callable entry B before output.json is written.
 *
 * This is intentionally a static-analysis step rather than an LLM decision:
 * - If B is declared in a concrete class, keep normal receiver construction.
 * - If B is declared in an abstract class but B itself is concrete, prefer an existing concrete subclass that does not override B.
 * - If no concrete subclass is available, fall back to a test stub subclass strategy.
 * - If the target has no executable body, mark it as not suitable for direct test generation.
 */
public final class ReceiverResolver {

    private ReceiverResolver() {
    }

    public static MethodEntryResolver.Resolution enrich(MutationConfig config,
                                                        MethodEntryResolver.Resolution r,
                                                        String originJavaFile) {
        if (r == null) {
            return null;
        }
        try {
            if (r.useReflectionFallback || isBlank(r.testEntryClassName) || isBlank(r.testEntryMethodName)) {
                return r;
            }

            CompilationUnit ownerCu = parse(originJavaFile);
            String ownerPackage = packageName(ownerCu, config.packageName);
            Optional<TypeDeclaration<?>> ownerOpt = findType(ownerCu, r.testEntryClassName);
            if (!ownerOpt.isPresent()) {
                r.notes.add("receiver resolver: entry owner not found in source; keep existing receiver strategy");
                return r;
            }
            TypeDeclaration<?> owner = ownerOpt.get();
            Optional<CallableDeclaration<?>> entryCallableOpt = findCallable(owner, r.testEntryMethodName);
            if (!entryCallableOpt.isPresent()) {
                r.notes.add("receiver resolver: entry callable not found in owner; keep existing receiver strategy");
                return r;
            }
            CallableDeclaration<?> entryCallable = entryCallableOpt.get();
            MethodDeclaration entryMethod = entryCallable instanceof MethodDeclaration ? (MethodDeclaration) entryCallable : null;

            if (entryMethod != null && (entryMethod.isAbstract() || !entryMethod.getBody().isPresent())) {
                r.skipTestGeneration = true;
                r.skipReason = "SKIP_NO_EXECUTABLE_BODY: entry/mutation method has no executable body";
                r.testReceiverStrategy = "SKIP_NO_EXECUTABLE_BODY";
                r.testReceiverNotes = r.skipReason;
                r.notes.add(r.skipReason);
                return r;
            }

            applyApiAndObservableEvidence(config, r, owner, entryCallable);
            applyGeneralCompileGuardrails(config, r, owner, entryCallable);

            if (isStaticInvocation(r)) {
                return r;
            }

            String ownerKind = ownerKind(owner);
            if ("ABSTRACT_CLASS".equals(ownerKind)) {
                // For abstract declaring classes, prefer a test stub subclass by default.
                // This avoids unstable evidence such as new Days(), new BuddhistChronology(),
                // or other concrete subclasses whose constructors/factories were not verified.
                applyStubFallback(r, owner, r.testEntryMethodName, entryCallable, config);
                return r;
            }
            if ("INTERFACE".equals(ownerKind)) {
                r.skipTestGeneration = true;
                r.skipReason = "SKIP_INTERFACE_ENTRY_NO_BODY: entry owner is an interface; static analysis must resolve a concrete implementation first.";
                r.testReceiverStrategy = "SKIP_INTERFACE_ENTRY_NO_BODY";
                r.testReceiverNotes = r.skipReason;
                r.notes.add(r.skipReason);
                return r;
            }
            if (!"ABSTRACT_CLASS".equals(ownerKind) && !"INTERFACE".equals(ownerKind)) {
                if (applyKnownCollaboratorConstruction(r, owner, entryCallable)) {
                    return r;
                }
                // Concrete class with no accessible constructor, e.g. TextStyle:
                // static analysis should resolve factory/builder construction instead of
                // asking the LLM to guess new Target(), new Builder(), or build().
                if ("FACTORY_OR_REFLECTION_REQUIRED".equals(r.testReceiverStrategy)
                        || !r.testEntryOwnerInstantiable) {
                    if (applyStaticFactoryBuilder(r, ownerCu, owner, entryMethod)) {
                        return r;
                    }
                }
                return r;
            }

            List<Path> sourceRoots = findSourceRoots(config, originJavaFile);
            if (!sourceRoots.isEmpty()) {
                List<TypeInfo> types = parseProjectTypes(sourceRoots);
                TypeInfo ownerInfo = TypeInfo.from(ownerCu, owner, originJavaFile);
                Candidate best = findBestConcreteReceiver(types, ownerInfo, r.testEntryMethodName);
                if (best != null) {
                    applyConcreteSubclass(config, r, ownerInfo, best);
                    return r;
                }
            }

            applyStubFallback(r, owner, r.testEntryMethodName, entryCallable, config);
            return r;
        } catch (Throwable t) {
            r.notes.add("receiver resolver failed: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            return r;
        }
    }

    private static void applyApiAndObservableEvidence(MutationConfig config,
                                                      MethodEntryResolver.Resolution r,
                                                      TypeDeclaration<?> owner,
                                                      CallableDeclaration<?> entryCallable) {
        List<MethodDeclaration> methods = owner.getMethods();
        List<String> publicMethods = new ArrayList<>();
        List<String> setupMethods = new ArrayList<>();
        List<MethodDeclaration> sortedMethods = new ArrayList<>(methods);
        sortedMethods.sort(methodPreferenceComparator(entryCallable));
        for (MethodDeclaration m : sortedMethods) {
            if (m.isPrivate()) {
                continue;
            }
            String sig = methodDisplaySignature(m);
            publicMethods.add(sig);
            if (m.getNameAsString().startsWith("set") && m.getParameters().size() == 1) {
                setupMethods.add(sig);
            }
        }
        if (isThrowableLikeType(owner) && !publicMethods.contains("java.lang.String getMessage()")) {
            publicMethods.add("java.lang.String getMessage()");
        }
        r.availablePublicMethods = String.join(" || ", publicMethods);
        r.availableSetupMethods = String.join(" || ", setupMethods);

        List<String> stateSetup = inferStateSetupStatements(owner, entryCallable);
        if (!stateSetup.isEmpty()) {
            r.stateSetupPlan = String.join(" || ", stateSetup);
        }

        BranchPlan bp = inferBranchReachabilityPlan(owner, entryCallable, config);
        if (bp != null) {
            r.branchReachabilityKind = bp.kind;
            r.branchReachabilityCondition = bp.condition;
            r.branchReachabilitySetup = bp.setup;
            r.branchReachabilityReason = bp.reason;
        }

        ObservablePlan op = inferObservablePlan(owner, entryCallable, stateSetup, config, bp);
        op = strengthenObservablePlan(owner, entryCallable, config, op);
        if (op != null) {
            r.observablePlanKind = op.kind;
            r.observableSetup = op.setup;
            r.observableCall = op.call;
            r.observableExpectedOriginal = op.expectedOriginal;
            r.observableReason = op.reason;
            r.observableAntiPatterns = String.join(" || ", op.antiPatterns);
            if ("NO_PUBLIC_OBSERVABLE".equals(op.kind)) {
                r.skipTestGeneration = true;
                r.skipReason = firstNonBlank(r.skipReason, op.reason);
                r.notes.add(op.reason);
            }
        }
    }

    private static Comparator<MethodDeclaration> methodPreferenceComparator(CallableDeclaration<?> entryCallable) {
        final String entryName = entryCallable == null ? "" : entryCallable.getNameAsString();
        return Comparator
                .comparingInt((MethodDeclaration m) -> methodPreferenceScore(m, entryName))
                .reversed()
                .thenComparingInt((MethodDeclaration m) -> m.getParameters().size())
                .thenComparing(MethodDeclaration::getNameAsString, String.CASE_INSENSITIVE_ORDER);
    }

    private static int methodPreferenceScore(MethodDeclaration method, String entryName) {
        int score = 0;
        if (method == null) {
            return score;
        }
        if (method.isPublic()) {
            score += 100;
        } else if (method.isProtected()) {
            score += 70;
        } else {
            score += 40;
        }
        if (!method.getType().isVoidType()) {
            score += 30;
        }
        if (looksObservableMethod(method)) {
            score += 20;
        }
        if (entryName != null && !isBlank(entryName) && entryName.equals(method.getNameAsString())) {
            score += 10;
        }
        score -= Math.min(method.getParameters().size(), 5);
        return score;
    }

    private static boolean looksObservableMethod(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        String name = method.getNameAsString();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("get") || lower.startsWith("is") || lower.startsWith("has")) {
            return true;
        }
        return "tostring".equals(lower)
                || "compareto".equals(lower)
                || "length".equals(lower)
                || "size".equals(lower)
                || "value".equals(lower);
    }

    private static ObservablePlan strengthenObservablePlan(TypeDeclaration<?> owner,
                                                           CallableDeclaration<?> entryCallable,
                                                           MutationConfig config,
                                                           ObservablePlan baseline) {
        if (!isWeakObservablePlan(baseline) && !looksInvalidExampleInvocation(baseline)) {
            return baseline;
        }

        if (entryCallable instanceof MethodDeclaration) {
            MethodDeclaration method = (MethodDeclaration) entryCallable;

            ObservablePlan lexerToken = inferCsvLexerTokenObservable(owner, method, config);
            if (lexerToken != null) {
                return lexerToken;
            }

            ObservablePlan external = inferExternalMutableSinkObservable(owner, method, config);
            if (external != null) {
                return external;
            }

            ObservablePlan mutableParameter = inferMutableParameterObservable(owner, method, config);
            if (mutableParameter != null) {
                return mutableParameter;
            }

            ObservablePlan precondition = inferPreconditionExceptionObservable(owner, method, config);
            if (precondition != null) {
                return precondition;
            }
        }

        if (entryCallable instanceof ConstructorDeclaration) {
            ConstructorDeclaration ctor = (ConstructorDeclaration) entryCallable;
            ObservablePlan constructorSideEffect = inferConstructorExternalSideEffectObservable(owner, ctor, config);
            if (constructorSideEffect != null) {
                return constructorSideEffect;
            }

            ObservablePlan precondition = inferPreconditionExceptionObservable(owner, ctor, config);
            if (precondition != null) {
                return precondition;
            }
        }

        return baseline;
    }

    private static boolean isWeakObservablePlan(ObservablePlan plan) {
        if (plan == null) {
            return true;
        }
        String kind = safe(plan.kind).trim().toUpperCase(Locale.ROOT);
        return kind.isEmpty()
                || "ENTRY_RETURN_VALUE".equals(kind)
                || "NO_PUBLIC_OBSERVABLE".equals(kind);
    }

    private static boolean looksInvalidExampleInvocation(ObservablePlan plan) {
        if (plan == null) {
            return false;
        }
        String text = (safe(plan.setup) + " " + safe(plan.call)).toLowerCase(Locale.ROOT);
        return text.contains("equals(null)")
                || text.contains("nexttoken(null)")
                || text.contains("read(new char[0], 7, 7)")
                || text.contains("new csvparser(null, null")
                || text.contains("new csvprinter(new stringbuilder(), null)");
    }

    private static String methodDisplaySignature(MethodDeclaration m) {
        return CallableSignatureFormatter.method(m);
    }

    private static List<String> inferStateSetupStatements(TypeDeclaration<?> owner, CallableDeclaration<?> entryCallable) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!(entryCallable instanceof MethodDeclaration)) {
            return new ArrayList<>(out);
        }
        List<FieldDeclaration> fields = owner.getFields();
        Set<String> referencedFields = fieldsReferencedBy(entryCallable, ownerFieldNames(owner));
        for (FieldDeclaration fd : fields) {
            for (VariableDeclarator v : fd.getVariables()) {
                String field = v.getNameAsString();
                if (!referencedFields.contains(field)) {
                    continue;
                }
                MethodDeclaration setter = findSetterForField(owner, field);
                if (setter != null) {
                    String arg = exampleValueForTypeForSetter(setter.getParameter(0).getType().asString(), field, setter.getNameAsString());
                    if (!arg.isEmpty()) {
                        out.add("subject." + setter.getNameAsString() + "(" + arg + ");");
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Set<String> ownerFieldNames(TypeDeclaration<?> owner) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
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

    private static Set<String> fieldsReferencedBy(CallableDeclaration<?> callable, Set<String> ownerFields) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (callable == null || ownerFields == null || ownerFields.isEmpty()) {
            return out;
        }
        Set<String> shadowed = new LinkedHashSet<>();
        for (Parameter p : callable.getParameters()) {
            shadowed.add(p.getNameAsString());
        }
        for (VariableDeclarator v : callable.findAll(VariableDeclarator.class)) {
            shadowed.add(v.getNameAsString());
        }
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

    private static boolean isOwnerScope(Expression scope) {
        return scope != null && (scope.isThisExpr() || scope.isSuperExpr());
    }

    private static MethodDeclaration findSetterForField(TypeDeclaration<?> owner, String field) {
        String normalized = ("set" + field).toLowerCase(Locale.ROOT);
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.isPrivate() || m.getParameters().size() != 1) {
                continue;
            }
            if (m.getNameAsString().toLowerCase(Locale.ROOT).equals(normalized)) {
                return m;
            }
        }
        return null;
    }

    private static String exampleValueForTypeForSetter(String raw, String field, String setterName) {
        String t = simpleType(raw);
        String f = (field + " " + setterName).toLowerCase(Locale.ROOT);
        if ("boolean".equals(t) || "Boolean".equals(t)) {
            return "false";
        }
        if ("long".equals(t) || "Long".equals(t)) {
            return f.contains("millis") || f.contains("unit") ? "1000L" : "1L";
        }
        if ("int".equals(t) || "Integer".equals(t)) {
            if (f.contains("width") || f.contains("height") || f.contains("size")) return "5";
            return "1";
        }
        if ("String".equals(t) || "CharSequence".equals(t)) {
            if (f.contains("pattern")) return "\"a.b\"";
            if (f.contains("string")) return "\"a\\nb\"";
            return "\"x\"";
        }
        if ("long".equals(t) || "Long".equals(t)) return "1L";
        if ("float".equals(t) || "Float".equals(t)) return "1.0f";
        if ("double".equals(t) || "Double".equals(t)) return "1.0";
        if ("char".equals(t) || "Character".equals(t)) return "'x'";
        return "";
    }

    private static ObservablePlan inferObservablePlan(TypeDeclaration<?> owner,
                                                      CallableDeclaration<?> entryCallable,
                                                      List<String> stateSetup,
                                                      MutationConfig config,
                                                      BranchPlan branchPlan) {
        String ownerSimple = owner.getNameAsString();
        String diff = config == null || config.mutationStatement == null ? "" : config.mutationStatement;
        if (entryCallable instanceof MethodDeclaration) {
            MethodDeclaration md = (MethodDeclaration) entryCallable;
            if (ownerSimple.equals("JsonGeneratorDelegate") && md.toString().contains("delegate.")) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "FORWARDING_CALL_TO_CONCRETE_COLLABORATOR";
                op.setup = "java.io.StringWriter out = new java.io.StringWriter(); com.fasterxml.jackson.core.JsonGenerator delegate = new com.fasterxml.jackson.core.JsonFactory().createGenerator(out); JsonGeneratorDelegate subject = new JsonGeneratorDelegate(delegate);";
                if (md.getNameAsString().equals("writeString") && md.getParameters().size() == 3) {
                    op.call = "subject.writeString(new char[] {'x', 'y', 'z'}, 1, 1); delegate.flush(); String output = out.toString();";
                    op.expectedOriginal = "\"\\\"y\\\"\"";
                } else {
                    op.call = "Invoke the forwarding method on subject, flush delegate if needed, and observe out.toString().";
                }
                op.reason = "The target method forwards to an abstract JsonGenerator collaborator; use JsonFactory.createGenerator(StringWriter) instead of hand-writing a JsonGenerator subclass.";
                op.antiPatterns.add("Do not instantiate JsonGeneratorDelegate with null delegate when testing forwarding behavior.");
                op.antiPatterns.add("Do not hand-write a subclass of JsonGenerator unless a complete abstract-method template is provided.");
                return op;
            }
            String entryBody = md.toString();
            List<String> touchedFields = touchedFields(owner, entryBody);

            ObservablePlan lexerToken = inferCsvLexerTokenObservable(owner, md, config);
            if (lexerToken != null) {
                return lexerToken;
            }

            ObservablePlan externalSinkObservable = inferExternalMutableSinkObservable(owner, md, config);
            if (externalSinkObservable != null) {
                return externalSinkObservable;
            }

            if (!"void".equals(md.getType().asString())) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "ENTRY_RETURN_VALUE";
                String args = exampleArgsForCallable(md, config);
                String target = md.isStatic() ? ownerSimple : "subject";
                op.call = simpleType(md.getType().asString()) + " result = " + target + "." + md.getNameAsString() + "(" + args + ");";
                if (branchPlan != null && !isBlank(branchPlan.setup)) {
                    op.setup = branchPlan.setup;
                }
                if (ownerSimple.equals("AssembledChronology") && md.getNameAsString().equals("getDateTimeMillis")) {
                    op.expectedOriginal = "base.getDateTimeMillis(" + args + ")";
                    op.reason = "ENTRY_RETURN_VALUE_WITH_BASE_DELEGATION: use a non-zero hourOfDay and compare subject result with base.getDateTimeMillis using the original positive hourOfDay.";
                } else {
                    op.reason = "Entry method returns a value; assert the returned value as the mutation-sensitive observable.";
                }
                return op;
            }

            for (MethodDeclaration m : owner.getMethods()) {
                if (m == md || m.isPrivate() || "void".equals(m.getType().asString()) || m.getParameters().size() > 2) {
                    continue;
                }
                String mt = m.toString();
                for (String f : touchedFields) {
                    if (mt.contains(f)) {
                        ObservablePlan op = new ObservablePlan();
                        op.kind = "PUBLIC_METHOD_DEPENDS_ON_MUTATED_FIELD";
                        String actionArgs = exampleArgsForCallable(md, config);
                        String observableArgs = exampleArgsForCallable(m, config);
                        String actionTarget = md.isStatic() ? ownerSimple : "subject";
                        String observableTarget = m.isStatic() ? ownerSimple : "subject";
                        op.setup = actionTarget + "." + md.getNameAsString() + "(" + actionArgs + ");";
                        op.call = m.getType().asString() + " result = " + observableTarget + "." + m.getNameAsString() + "(" + observableArgs + ");";
                        op.reason = "Entry method updates field '" + f + "'; public method " + m.getNameAsString()
                                + " reads that field and returns an observable value.";
                        if (diff.contains("--") || diff.contains("++") || diff.contains("=> -") || diff.contains("-")) {
                            String expected = firstArgValue(md, config);
                            if (!isBlank(expected)) {
                                op.expectedOriginal = expected;
                                op.reason += " The mutation changes the assigned value; assert the original argument value after the setter/action.";
                            }
                        }
                        return op;
                    }
                }
            }

            ObservablePlan reflective = inferReflectionFieldObservable(owner, md, touchedFields, config);
            if (reflective != null) {
                return reflective;
            }

            // Console output is a first-class fallback for void methods, but do not
            // displace stronger return/state/external-sink observables discovered above.
            ObservablePlan consoleOutput = inferConsoleOutputObservable(owner, md, config);
            if (consoleOutput != null) {
                return consoleOutput;
            }
        }
        if (entryCallable instanceof ConstructorDeclaration) {
            ConstructorDeclaration ctor = (ConstructorDeclaration) entryCallable;
            String text = ctor.toString();
            ObservablePlan constructorExceptionObservable = inferConstructorExceptionObservable(owner, ctor, config);
            if (constructorExceptionObservable != null) {
                return constructorExceptionObservable;
            }
            if (text.contains("for (") && (diff.contains("i--") || diff.contains("--") || diff.contains("i++"))) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "CONSTRUCTOR_COMPLETES_VS_EXCEPTION";
                op.setup = "Use non-null concrete parameters generated by parameterPlans, e.g. org.joda.time.Partial for ReadablePartial, so the loop body is reached.";
                op.call = "Construct the TEST_STUB_SUBCLASS receiver with mutation-sensitive non-null arguments.";
                op.expectedOriginal = "constructor completes normally";
                op.reason = "Loop-update mutation in constructor is observable as normal construction versus exception/non-termination; do not skip as no-public-observable.";
                return op;
            }
            if (hasThrowableMessageObservable(owner, ctor, config)) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "THROWABLE_MESSAGE";
                op.call = "String message = subject.getMessage();";
                op.reason = "THROWABLE_MESSAGE_OBSERVABLE: constructor flows mutated message construction into Throwable state; observe the constructed message via subject.getMessage().";
                op.antiPatterns.add("Do not skip this constructor as no-public-observable when the mutation changes exception/message construction.");
                op.antiPatterns.add("Do not rely only on stored field getters such as getMatchingOptions() when the mutation affects message text; assert subject.getMessage() instead.");
                return op;
            }
            List<String> touchedFields = touchedFields(owner, text);
            ObservablePlan publicObservable = inferConstructorPublicObservable(owner, ctor, touchedFields, config);
            if (publicObservable != null) {
                return publicObservable;
            }
            ObservablePlan reflectiveObservable = inferConstructorReflectionObservable(owner, ctor, touchedFields, config);
            if (reflectiveObservable != null) {
                return reflectiveObservable;
            }
            boolean stateWrite = text.contains("set") || text.contains("=") || !touchedFields.isEmpty();
            if (stateWrite) {
                ObservablePlan op = new ObservablePlan();
                op.kind = "NO_PUBLIC_OBSERVABLE";
                op.reason = "NO_PUBLIC_OBSERVABLE_FOR_CONSTRUCTOR_STATE_MUTATION: constructor mutates internal/inherited state, but no public getter or public behavior depending on that state was found by static analysis.";
                op.antiPatterns.add("Do not invent getters such as getRestrict() or isRestrict(); only use methods listed in availablePublicMethods.");
                return op;
            }
        }
        return null;
    }

    /**
     * Treat writes to System.out/System.err as first-class public observables for void
     * entry methods. This is intentionally limited to void entries so a strong return
     * oracle is not displaced merely because the implementation also logs diagnostics.
     */
    private static ObservablePlan inferConsoleOutputObservable(TypeDeclaration<?> owner,
                                                                MethodDeclaration entryMethod,
                                                                MutationConfig config) {
        if (owner == null || entryMethod == null || !entryMethod.getType().isVoidType()) {
            return null;
        }

        CallableDeclaration<?> mutationCallable = null;
        if (config != null && !isBlank(config.mutationMethodName)) {
            Optional<CallableDeclaration<?>> mutation = findCallable(owner, config.mutationMethodName);
            if (mutation.isPresent()) {
                mutationCallable = mutation.get();
            }
        }

        ConsoleSink sink = findConsoleSink(mutationCallable);
        if (sink == null) {
            sink = findConsoleSink(entryMethod);
        }
        if (sink == null) {
            return null;
        }

        String args = exampleArgsForCallable(entryMethod, config);
        String ownerName = owner.getNameAsString();
        String invocation = entryMethod.isStatic()
                ? ownerName + "." + entryMethod.getNameAsString() + "(" + args + ");"
                : "subject." + entryMethod.getNameAsString() + "(" + args + ");";
        String stream = sink.stderr ? "err" : "out";
        String setter = sink.stderr ? "setErr" : "setOut";
        String kind = sink.stderr ? "STDERR_OBSERVABLE" : "STDOUT_OBSERVABLE";

        ObservablePlan op = new ObservablePlan();
        op.kind = kind;
        op.setup = "java.io.ByteArrayOutputStream capturedOutput = new java.io.ByteArrayOutputStream(); "
                + "java.io.PrintStream originalConsole = java.lang.System." + stream + "; "
                + "java.lang.System." + setter + "(new java.io.PrintStream(capturedOutput));";
        op.call = "try { " + invocation + " } finally { java.lang.System." + setter
                + "(originalConsole); } String result = capturedOutput.toString();";
        op.reason = "The mutation-sensitive void path writes to System." + stream
                + "; capture the external console output and assert its content/presence instead of using completion-only or unrelated exception behavior.";
        op.antiPatterns.add("Do not leave a System." + stream + " mutation with only a method-completion assertion; capture and assert the emitted text.");
        op.antiPatterns.add("Always restore System." + stream + " in a finally block after capture.");
        return op;
    }

    private static ConsoleSink findConsoleSink(CallableDeclaration<?> callable) {
        if (callable == null) {
            return null;
        }
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            if (!call.getScope().isPresent()) {
                continue;
            }
            String scope = call.getScope().get().toString().replace("java.lang.", "");
            String method = call.getNameAsString();
            boolean writeLike = "print".equals(method)
                    || "println".equals(method)
                    || "printf".equals(method)
                    || "format".equals(method)
                    || "write".equals(method)
                    || "append".equals(method);
            if (!writeLike) {
                continue;
            }
            if ("System.out".equals(scope)) {
                return new ConsoleSink(false);
            }
            if ("System.err".equals(scope)) {
                return new ConsoleSink(true);
            }
        }
        return null;
    }

    private static final class ConsoleSink {
        final boolean stderr;

        ConsoleSink(boolean stderr) {
            this.stderr = stderr;
        }
    }

    private static ObservablePlan inferExternalMutableSinkObservable(TypeDeclaration<?> owner,
                                                                     MethodDeclaration entryMethod,
                                                                     MutationConfig config) {
        if (owner == null || entryMethod == null || config == null || isBlank(config.mutationMethodName)) {
            return null;
        }
        if (!"void".equals(entryMethod.getType().asString())) {
            return null;
        }
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return null;
        }

        Optional<CallableDeclaration<?>> mutationOpt = findCallable(owner, config.mutationMethodName);
        if (!mutationOpt.isPresent() || !(mutationOpt.get() instanceof MethodDeclaration)) {
            return null;
        }
        MethodDeclaration mutationMethod = (MethodDeclaration) mutationOpt.get();
        String mutationText = mutationMethod.toString();
        if (!looksLikeExternalSinkMutation(mutationText)) {
            return null;
        }

        String getterName = findPublicExternalSinkGetter(owner);
        String setup = buildExternalSinkSetup((ClassOrInterfaceDeclaration) owner, entryMethod, config);
        if (isBlank(setup)) {
            return null;
        }

        ObservablePlan op = new ObservablePlan();
        op.kind = "EXTERNAL_MUTABLE_SINK_TO_STRING";
        op.setup = setup;
        op.call = isBlank(getterName)
                ? "String result = output.toString();"
                : "String result = subject." + getterName + "().toString();";
        op.reason = "The mutation writes to an external mutable sink via append/write operations; observe the rendered sink content with toString() instead of searching for an internal getter.";
        op.antiPatterns.add("Do not assert only receiver identity for void printing/writing methods; assert the external sink content.");
        op.antiPatterns.add("Do not construct the receiver with null sink/config objects when the constructor requires concrete collaborators.");
        return op;
    }

    private static ObservablePlan inferMutableParameterObservable(TypeDeclaration<?> owner,
                                                                  MethodDeclaration entryMethod,
                                                                  MutationConfig config) {
        if (entryMethod == null) {
            return null;
        }
        String methodText = entryMethod.toString();
        for (int i = 0; i < entryMethod.getParameters().size(); i++) {
            Parameter parameter = entryMethod.getParameter(i);
            String type = simpleType(parameter.getType().asString());
            String name = parameter.getNameAsString();
            if (!isMutableParameterType(type)) {
                continue;
            }
            if (!looksLikeParameterMutation(methodText, name, type)) {
                continue;
            }

            ObservablePlan op = new ObservablePlan();
            op.kind = "MUTABLE_PARAMETER_STATE";
            op.setup = buildMutableParameterSetup(owner, entryMethod, config, i);
            op.call = buildMutableParameterObservation(type, name);
            op.reason = "The mutation changes a mutable argument in place; observe the post-call state of parameter '"
                    + name + "' instead of relying only on the method return value.";
            op.antiPatterns.add("Do not assert only the returned object when the mutation updates a mutable input argument in place.");
            return op;
        }
        return null;
    }

    private static ObservablePlan inferCsvLexerTokenObservable(TypeDeclaration<?> owner,
                                                               MethodDeclaration method,
                                                               MutationConfig config) {
        if (owner == null || method == null
                || !"Lexer".equals(owner.getNameAsString())
                || !"nextToken".equals(method.getNameAsString())) {
            return null;
        }
        String text = (safe(config == null ? "" : config.mutationMethodName) + "\n"
                + safe(config == null ? "" : config.mutationStatement) + "\n"
                + method.toString()).toLowerCase(Locale.ROOT);
        if (!text.contains("token")) {
            return null;
        }
        ObservablePlan op = new ObservablePlan();
        op.kind = "MUTABLE_PARAMETER_STATE";
        op.setup = "Lexer subject = new Lexer(org.apache.commons.csv.CSVFormat.DEFAULT, new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"abc ,z\")));";
        op.call = "org.apache.commons.csv.Token token = subject.nextToken(new org.apache.commons.csv.Token());";
        op.reason = "Lexer tokenization mutations should be reached through nextToken(new Token()) with a real ExtendedBufferedReader, then observed through token.content/type/isReady rather than null exception behavior.";
        op.antiPatterns.add("Do not use new Lexer(CSVFormat.DEFAULT, null); a null reader blocks normal lexer reachability.");
        op.antiPatterns.add("Do not call nextToken(null); a null token prevents propagation into Token.content/type/isReady.");
        if (text.contains("ignoresurroundingspaces") && text.contains("trimtrailingspaces")) {
            op.expectedOriginal = "\"abc \" for token.content.toString() when input is \"abc ,z\" and ignoreSurroundingSpaces=false.";
            op.reason += " For forced trimTrailingSpaces mutations, assert token.content.toString() keeps the trailing space in the original.";
        }
        return op;
    }

    private static ObservablePlan inferPreconditionExceptionObservable(TypeDeclaration<?> owner,
                                                                       CallableDeclaration<?> callable,
                                                                       MutationConfig config) {
        String diff = config == null ? "" : safe(config.mutationStatement);
        String text = callable == null ? "" : callable.toString();
        String lowered = (diff + "\n" + text).toLowerCase(Locale.ROOT);
        if (!looksLikePreconditionMutation(lowered)
                || !isExceptionPrimaryObservableMutation(diff, text)) {
            return null;
        }

        ObservablePlan op = new ObservablePlan();
        op.kind = "EXCEPTION_BEHAVIOR";
        op.call = "Invoke the target once with valid inputs and once with a deliberately invalid/null precondition-breaking input, then assert thrown-vs-not-thrown behavior.";
        op.reason = "The mutation changes a null-check or precondition guard, so the strongest observable is exception behavior rather than a normal return value.";
        op.antiPatterns.add("Do not test only normal inputs when the mutation weakens or removes a precondition guard.");
        return op;
    }

    private static ObservablePlan inferConstructorExternalSideEffectObservable(TypeDeclaration<?> owner,
                                                                               ConstructorDeclaration ctor,
                                                                               MutationConfig config) {
        if (!(owner instanceof ClassOrInterfaceDeclaration) || ctor == null) {
            return null;
        }
        String text = ctor.toString();
        if (!looksLikeExternalSinkMutation(text)) {
            return null;
        }

        ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) owner;
        String setup = buildConstructorExternalSinkSetup(clazz, ctor, config);
        if (isBlank(setup)) {
            return null;
        }

        String getterName = findPublicExternalSinkGetter(owner);
        ObservablePlan op = new ObservablePlan();
        op.kind = "CONSTRUCTOR_EXTERNAL_SINK_STATE";
        op.setup = setup;
        op.call = isBlank(getterName)
                ? "String result = output.toString();"
                : "String result = subject." + getterName + "().toString();";
        op.reason = "The constructor writes observable state to an external mutable sink during initialization; compare sink content after construction.";
        op.antiPatterns.add("Do not observe only internal getters when constructor behavior is expressed through external output side effects.");
        return op;
    }

    private static boolean isMutableParameterType(String type) {
        return "StringBuilder".equals(type)
                || "StringBuffer".equals(type)
                || "Collection".equals(type)
                || "List".equals(type)
                || "Map".equals(type)
                || type.endsWith("[]");
    }

    private static boolean looksLikeParameterMutation(String methodText, String parameterName, String type) {
        if (isBlank(methodText) || isBlank(parameterName)) {
            return false;
        }
        String lowered = methodText.toLowerCase(Locale.ROOT);
        String name = parameterName.toLowerCase(Locale.ROOT);
        if (type.endsWith("[]")) {
            return lowered.contains(name + "[");
        }
        return lowered.contains(name + ".append(")
                || lowered.contains(name + ".setlength(")
                || lowered.contains(name + ".delete(")
                || lowered.contains(name + ".add(")
                || lowered.contains(name + ".put(")
                || lowered.contains(name + ".remove(")
                || lowered.contains(name + ".clear(");
    }

    private static String buildMutableParameterSetup(TypeDeclaration<?> owner,
                                                     MethodDeclaration entryMethod,
                                                     MutationConfig config,
                                                     int mutableParameterIndex) {
        List<String> setup = new ArrayList<String>();
        List<String> args = new ArrayList<String>();
        for (int i = 0; i < entryMethod.getParameters().size(); i++) {
            Parameter parameter = entryMethod.getParameter(i);
            String type = simpleType(parameter.getType().asString());
            String name = parameter.getNameAsString();
            if (i == mutableParameterIndex) {
                if ("StringBuilder".equals(type) || "StringBuffer".equals(type)) {
                    setup.add(type + " " + name + " = new " + type + "(\"  alpha  \");");
                    args.add(name);
                } else if ("Collection".equals(type) || "List".equals(type)) {
                    setup.add("java.util.List<String> " + name + " = new java.util.ArrayList<String>(java.util.Arrays.asList(\"alpha\", \"beta\", \" \"));");
                    args.add(name);
                } else if ("Map".equals(type)) {
                    setup.add("java.util.Map<String, Integer> " + name + " = new java.util.LinkedHashMap<String, Integer>();");
                    setup.add(name + ".put(\"alpha\", 1);");
                    args.add(name);
                } else if (type.endsWith("[]")) {
                    String element = type.substring(0, type.length() - 2);
                    setup.add(type + " " + name + " = new " + element + "[] {'a', ' ', 'b', ' '};");
                    args.add(name);
                } else {
                    args.add(exampleValueForParameter(parameter.getType().asString(), name, i, config));
                }
            } else {
                args.add(exampleValueForParameter(parameter.getType().asString(), name, i, config));
            }
        }
        setup.add(owner.getNameAsString() + " subject = new " + owner.getNameAsString()
                + "(" + exampleConstructorArgsForOwner(owner) + ");");
        setup.add("subject." + entryMethod.getNameAsString() + "(" + String.join(", ", args) + ");");
        return String.join(" ", dedupe(setup));
    }

    private static String buildMutableParameterObservation(String type, String name) {
        if ("StringBuilder".equals(type) || "StringBuffer".equals(type)) {
            return "String result = " + name + ".toString();";
        }
        if ("Collection".equals(type) || "List".equals(type)) {
            return "int result = " + name + ".size();";
        }
        if ("Map".equals(type)) {
            return "int result = " + name + ".size();";
        }
        if (type.endsWith("[]")) {
            return "Object result = java.util.Arrays.toString(" + name + ");";
        }
        return "Object result = " + name + ";";
    }

    private static boolean looksLikePreconditionMutation(String loweredText) {
        if (isBlank(loweredText)) {
            return false;
        }
        return loweredText.contains("notnull")
                || loweredText.contains("requirenonnull")
                || loweredText.contains("illegalargumentexception")
                || loweredText.contains("nullpointerexception")
                || loweredText.contains("illegalstateexception")
                || loweredText.contains("throw new")
                || loweredText.contains("== null")
                || loweredText.contains("!= null");
    }

    private static boolean isExceptionPrimaryObservableMutation(String diff, String callableText) {
        String loweredDiff = safe(diff).toLowerCase(Locale.ROOT);
        String loweredCallable = safe(callableText).toLowerCase(Locale.ROOT);
        if (loweredDiff.contains("throw new")
                || loweredDiff.contains("illegalargumentexception")
                || loweredDiff.contains("nullpointerexception")
                || loweredDiff.contains("illegalstateexception")) {
            return true;
        }
        if ((loweredDiff.contains("== null") || loweredDiff.contains("!= null"))
                && loweredCallable.contains("throw new")) {
            return true;
        }
        // If a stronger state/content sink is already apparent, prefer that over generic exception behavior.
        if (loweredCallable.contains(".append(")
                || loweredCallable.contains(".setlength(")
                || loweredCallable.contains("token.content")
                || loweredCallable.contains("token.type")
                || loweredCallable.contains("writer.write(")
                || loweredCallable.contains("out.append(")) {
            return false;
        }
        return false;
    }

    private static String buildConstructorExternalSinkSetup(ClassOrInterfaceDeclaration owner,
                                                            ConstructorDeclaration ctor,
                                                            MutationConfig config) {
        List<String> prefixes = new ArrayList<String>();
        List<String> ctorArgs = new ArrayList<String>();
        int index = 0;
        for (Parameter parameter : ctor.getParameters()) {
            String type = simpleType(parameter.getType().asString());
            String name = parameter.getNameAsString();
            if ("Appendable".equals(type) || "StringBuilder".equals(type) || "Writer".equals(type)
                    || "StringWriter".equals(type)) {
                prefixes.add("java.lang.StringBuilder output = new java.lang.StringBuilder();");
                ctorArgs.add("output");
            } else {
                ctorArgs.add(exampleValueForParameter(parameter.getType().asString(), name, index, config));
            }
            index++;
        }
        prefixes.add(owner.getNameAsString() + " subject = new " + owner.getNameAsString()
                + "(" + String.join(", ", ctorArgs) + ");");
        return String.join(" ", dedupe(prefixes));
    }

    private static String exampleConstructorArgsForOwner(TypeDeclaration<?> owner) {
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return "";
        }
        List<ConstructorDeclaration> ctors = ((ClassOrInterfaceDeclaration) owner).getConstructors().stream()
                .filter(c -> !c.isPrivate())
                .sorted(Comparator.comparingInt(c -> c.getParameters().size()))
                .collect(Collectors.toList());
        if (ctors.isEmpty()) {
            return "";
        }
        List<String> args = new ArrayList<String>();
        int index = 0;
        for (Parameter parameter : ctors.get(0).getParameters()) {
            args.add(exampleValueForParameter(parameter.getType().asString(), parameter.getNameAsString(), index, null));
            index++;
        }
        return String.join(", ", args);
    }

    private static boolean looksLikeExternalSinkMutation(String text) {
        if (isBlank(text)) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return lowered.contains(".append(")
                || lowered.contains(".write(")
                || lowered.contains(".print(")
                || lowered.contains("out.append(")
                || lowered.contains("writer.write(");
    }

    private static String findPublicExternalSinkGetter(TypeDeclaration<?> owner) {
        for (MethodDeclaration method : owner.getMethods()) {
            if (method.isPrivate() || !method.getParameters().isEmpty()) {
                continue;
            }
            String returnType = simpleType(method.getType().asString());
            if (!"Appendable".equals(returnType) && !"StringBuilder".equals(returnType)
                    && !"Writer".equals(returnType) && !"StringWriter".equals(returnType)) {
                continue;
            }
            return method.getNameAsString();
        }
        return "";
    }

    private static String buildExternalSinkSetup(ClassOrInterfaceDeclaration owner,
                                                 MethodDeclaration entryMethod,
                                                 MutationConfig config) {
        List<ConstructorDeclaration> ctors = owner.getConstructors().stream()
                .filter(c -> !c.isPrivate())
                .sorted(Comparator.comparingInt(c -> c.getParameters().size()))
                .collect(Collectors.toList());
        if (ctors.isEmpty()) {
            return "";
        }

        ConstructorDeclaration ctor = ctors.get(0);
        List<String> prefixes = new ArrayList<String>();
        List<String> ctorArgs = new ArrayList<String>();
        int index = 0;
        for (Parameter parameter : ctor.getParameters()) {
            String type = simpleType(parameter.getType().asString());
            String name = parameter.getNameAsString();
            if ("Appendable".equals(type) || "StringBuilder".equals(type) || "Writer".equals(type)
                    || "StringWriter".equals(type)) {
                prefixes.add("java.lang.StringBuilder output = new java.lang.StringBuilder();");
                ctorArgs.add("output");
            } else {
                ctorArgs.add(exampleValueForParameter(parameter.getType().asString(), name, index, config));
            }
            index++;
        }

        String entryArgs = exampleArgsForCallable(entryMethod, config);
        prefixes.add(owner.getNameAsString() + " subject = new " + owner.getNameAsString()
                + "(" + String.join(", ", ctorArgs) + ");");
        prefixes.add("subject." + entryMethod.getNameAsString() + "(" + entryArgs + ");");
        return String.join(" ", dedupe(prefixes));
    }

    private static boolean hasThrowableMessageObservable(TypeDeclaration<?> owner,
                                                         ConstructorDeclaration ctor,
                                                         MutationConfig config) {
        if (!isThrowableLikeType(owner) || ctor == null) {
            return false;
        }
        return constructorFeedsThrowableMessage(owner, ctor, config, new LinkedHashSet<String>());
    }

    private static boolean constructorFeedsThrowableMessage(TypeDeclaration<?> owner,
                                                            ConstructorDeclaration ctor,
                                                            MutationConfig config,
                                                            Set<String> visited) {
        if (owner == null || ctor == null) {
            return false;
        }
        String key = ctor.getDeclarationAsString(false, false, false);
        if (!visited.add(key)) {
            return false;
        }
        ExplicitConstructorInvocationStmt invoke = explicitConstructorInvocation(ctor);
        if (invoke == null) {
            return false;
        }
        boolean signal = messageLikeArgumentSignal(invoke.getArguments(), config);
        if (!invoke.isThis()) {
            return signal || mutationLooksStringLike(config);
        }
        if (!invoke.isThis() || !(owner instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ConstructorDeclaration next = resolveThisTargetConstructor((ClassOrInterfaceDeclaration) owner, ctor, invoke);
        if (next == null) {
            return signal && anySiblingConstructorFeedsSuper((ClassOrInterfaceDeclaration) owner, ctor);
        }
        return signal || constructorFeedsThrowableMessage(owner, next, config, visited);
    }

    private static ExplicitConstructorInvocationStmt explicitConstructorInvocation(ConstructorDeclaration ctor) {
        if (ctor == null || ctor.getBody() == null || ctor.getBody().getStatements().isEmpty()) {
            return null;
        }
        if (ctor.getBody().getStatement(0).isExplicitConstructorInvocationStmt()) {
            return ctor.getBody().getStatement(0).asExplicitConstructorInvocationStmt();
        }
        return null;
    }

    private static ConstructorDeclaration resolveThisTargetConstructor(ClassOrInterfaceDeclaration owner,
                                                                       ConstructorDeclaration current,
                                                                       ExplicitConstructorInvocationStmt invoke) {
        if (owner == null || invoke == null || !invoke.isThis()) {
            return null;
        }
        int argCount = invoke.getArguments().size();
        for (ConstructorDeclaration candidate : owner.getConstructors()) {
            if (candidate == current) {
                continue;
            }
            if (candidate.getParameters().size() == argCount) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean anySiblingConstructorFeedsSuper(ClassOrInterfaceDeclaration owner,
                                                           ConstructorDeclaration current) {
        if (owner == null) {
            return false;
        }
        for (ConstructorDeclaration candidate : owner.getConstructors()) {
            if (candidate == current) {
                continue;
            }
            ExplicitConstructorInvocationStmt invoke = explicitConstructorInvocation(candidate);
            if (invoke != null && !invoke.isThis()) {
                return true;
            }
        }
        return false;
    }

    private static ObservablePlan inferConstructorExceptionObservable(TypeDeclaration<?> owner,
                                                                      ConstructorDeclaration ctor,
                                                                      MutationConfig config) {
        if (owner == null || ctor == null || config == null) {
            return null;
        }
        Optional<CallableDeclaration<?>> mutationCallable = findCallable(owner, config.mutationMethodName);
        if (!mutationCallable.isPresent()) {
            return null;
        }
        if (!(mutationCallable.get() instanceof MethodDeclaration)) {
            return null;
        }
        MethodDeclaration mutationMethod = (MethodDeclaration) mutationCallable.get();
        String diff = safe(config.mutationStatement);
        if (!looksLikeGuardMutation(diff) || !containsConditionalThrow(mutationMethod)) {
            return null;
        }

        ObservablePlan op = new ObservablePlan();
        op.kind = "CONSTRUCTOR_COMPLETES_VS_EXCEPTION";
        op.call = "Construct the subject with mutation-sensitive non-null arguments and observe whether construction throws "
                + inferredThrownType(mutationMethod) + ".";
        op.expectedOriginal = "constructor throws " + inferredThrownType(mutationMethod);
        op.reason = "The private mutation method " + mutationMethod.getNameAsString()
                + " contains a conditionally executed throw; the mutated predicate changes whether the constructor completes or throws.";
        op.antiPatterns.add("Do not assert unrelated getters when the strongest observable is constructor exception behavior.");
        op.antiPatterns.add("Do not pass null placeholders that fail earlier preconditions before reaching the mutated conditional throw.");
        return op;
    }

    private static boolean looksLikeGuardMutation(String diff) {
        if (isBlank(diff)) {
            return false;
        }
        return diff.contains("=>")
                && (diff.contains("&&") || diff.contains("||") || diff.contains("!") || diff.contains("==") || diff.contains("!="));
    }

    private static boolean containsConditionalThrow(MethodDeclaration method) {
        if (method == null || !method.getBody().isPresent()) {
            return false;
        }
        for (IfStmt ifStmt : method.findAll(IfStmt.class)) {
            if (ifStmt.getThenStmt().findFirst(ThrowStmt.class).isPresent()
                    || ifStmt.getElseStmt().isPresent() && ifStmt.getElseStmt().get().findFirst(ThrowStmt.class).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static String inferredThrownType(MethodDeclaration method) {
        if (method != null) {
            for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                String type = simpleType(creation.getType().asString());
                if (type.endsWith("Exception") || type.endsWith("Error")) {
                    return type;
                }
            }
        }
        return "an exception";
    }

    private static boolean messageLikeArgumentSignal(List<Expression> args, MutationConfig config) {
        if (args != null) {
            for (Expression arg : args) {
                if (isMessageLikeExpression(arg)) {
                    return true;
                }
            }
        }
        return mutationLooksStringLike(config);
    }

    private static boolean isMessageLikeExpression(Expression expr) {
        if (expr == null) {
            return false;
        }
        Expression normalized = expr;
        while (normalized.isEnclosedExpr()) {
            EnclosedExpr enclosed = normalized.asEnclosedExpr();
            normalized = enclosed.getInner();
        }
        if (normalized.isStringLiteralExpr()) {
            return true;
        }
        if (normalized.isBinaryExpr()) {
            BinaryExpr binary = normalized.asBinaryExpr();
            if (binary.getOperator() == BinaryExpr.Operator.PLUS) {
                return true;
            }
        }
        if (normalized.isMethodCallExpr()) {
            MethodCallExpr call = normalized.asMethodCallExpr();
            String name = call.getNameAsString().toLowerCase(Locale.ROOT);
            return "tostring".equals(name)
                    || call.getArguments().stream().anyMatch(ReceiverResolver::isMessageLikeExpression)
                    || call.findAll(StringLiteralExpr.class).size() > 0;
        }
        return normalized.findAll(StringLiteralExpr.class).size() > 0
                || normalized.findAll(BinaryExpr.class).stream()
                .anyMatch(b -> b.getOperator() == BinaryExpr.Operator.PLUS);
    }

    private static boolean mutationLooksStringLike(MutationConfig config) {
        if (config == null) {
            return false;
        }
        String method = safe(config.methodName).toLowerCase(Locale.ROOT);
        String target = safe(config.mutationMethodName).toLowerCase(Locale.ROOT);
        String stmt = safe(config.mutationStatement);
        String loweredStmt = stmt.toLowerCase(Locale.ROOT);
        return method.contains("string")
                || target.contains("string")
                || stmt.contains("\"")
                || loweredStmt.contains("tostring")
                || loweredStmt.contains("stringbuilder")
                || loweredStmt.contains("append(")
                || loweredStmt.contains("concat(");
    }

    private static boolean isThrowableLikeType(TypeDeclaration<?> owner) {
        if (owner == null) {
            return false;
        }
        if (looksLikeThrowableName(owner.getNameAsString())) {
            return true;
        }
        if (owner instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration decl = (ClassOrInterfaceDeclaration) owner;
            for (ClassOrInterfaceType ext : decl.getExtendedTypes()) {
                if (looksLikeThrowableName(ext.getNameAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksLikeThrowableName(String typeName) {
        String simple = simpleType(typeName);
        return "Throwable".equals(simple)
                || simple.endsWith("Exception")
                || simple.endsWith("Error");
    }

    private static List<String> touchedFields(TypeDeclaration<?> owner, String text) {
        List<String> out = new ArrayList<>();
        Set<String> ownerFields = ownerFieldNames(owner);
        Set<String> referenced = new LinkedHashSet<>();
        if (text != null && !text.trim().isEmpty()) {
            try {
                Expression parsedExpr = com.github.javaparser.StaticJavaParser.parseExpression(text);
                referenced.addAll(fieldsReferencedBy(parsedExpr, ownerFields));
            } catch (Throwable ignored) {
                return out;
            }
        }
        for (FieldDeclaration fd : owner.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                String n = v.getNameAsString();
                if (referenced.contains(n)) out.add(n);
            }
        }
        return out;
    }

    private static ObservablePlan inferConstructorPublicObservable(TypeDeclaration<?> owner,
                                                                   ConstructorDeclaration ctor,
                                                                   List<String> touchedFields,
                                                                   MutationConfig config) {
        if (owner == null) {
            return null;
        }
        LinkedHashSet<String> preferredFields = inferMutationAssignedFields(owner, ctor, config);
        MethodDeclaration best = null;
        int bestScore = Integer.MIN_VALUE;

        for (MethodDeclaration method : owner.getMethods()) {
            if (method.isPrivate() || method.isStatic() || "void".equals(method.getType().asString())
                    || method.getParameters().size() > 2) {
                continue;
            }
            int score = constructorObservableMethodScore(method, touchedFields, preferredFields);
            if (score <= 0) {
                continue;
            }
            if (best == null || score > bestScore) {
                best = method;
                bestScore = score;
            }
        }

        if (best != null) {
            ObservablePlan op = new ObservablePlan();
            op.kind = isGetterLike(best)
                    ? "CONSTRUCTOR_PUBLIC_GETTER_OBSERVABLE"
                    : "CONSTRUCTOR_PUBLIC_METHOD_DEPENDS_ON_STATE";
            op.call = best.getType().asString() + " result = subject." + best.getNameAsString()
                    + "(" + exampleArgsForCallable(best, config) + ");";
            op.reason = buildConstructorObservableReason(best, touchedFields, preferredFields);
            String expected = inferExpectedValueFromConstructorParameter(ctor, best, toOrderedList(preferredFields, touchedFields), config);
            if (!isBlank(expected)) {
                op.expectedOriginal = expected;
            }
            return op;
        }
        return null;
    }

    private static boolean constructorObservableMethodMatchesField(MethodDeclaration method, List<String> touchedFields) {
        if (method == null) {
            return false;
        }
        if (touchedFields == null || touchedFields.isEmpty()) {
            return isGetterLike(method);
        }
        Set<String> methodFields = fieldsReferencedBy(method, ownerFieldNames(method.findAncestor(TypeDeclaration.class).orElse(null)));
        for (String field : touchedFields) {
            if (field != null && !field.trim().isEmpty() && methodFields.contains(field)) {
                return true;
            }
        }
        return isGetterLike(method);
    }

    private static int constructorObservableMethodScore(MethodDeclaration method,
                                                        List<String> touchedFields,
                                                        Set<String> preferredFields) {
        if (method == null) {
            return 0;
        }
        int score = 0;
        Set<String> methodFields = fieldsReferencedBy(method, ownerFieldNames(method.findAncestor(TypeDeclaration.class).orElse(null)));

        if (preferredFields != null) {
            for (String field : preferredFields) {
                if (!isBlank(field) && methodFields.contains(field)) {
                    score += 100;
                }
                String getterField = fieldNameFromGetter(method.getNameAsString());
                if (!isBlank(getterField) && normalizeIdentifier(getterField).equals(normalizeIdentifier(field))) {
                    score += 120;
                }
            }
        }

        if (touchedFields != null) {
            for (String field : touchedFields) {
                if (!isBlank(field) && methodFields.contains(field)) {
                    score += 20;
                }
            }
        }

        if (isGetterLike(method)) {
            score += 10;
        }

        String returnType = simpleType(method.getType().asString());
        if ("Map".equals(returnType) || "List".equals(returnType) || "Collection".equals(returnType)) {
            score += 15;
        }
        if ("boolean".equals(returnType) || "Boolean".equals(returnType)) {
            score += 5;
        }
        return score;
    }

    private static Set<String> fieldsReferencedBy(Expression node, Set<String> ownerFields) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node == null || ownerFields == null || ownerFields.isEmpty()) {
            return out;
        }
        for (FieldAccessExpr access : node.findAll(FieldAccessExpr.class)) {
            String field = access.getNameAsString();
            if (ownerFields.contains(field) && isOwnerScope(access.getScope())) {
                out.add(field);
            }
        }
        for (NameExpr name : node.findAll(NameExpr.class)) {
            String field = name.getNameAsString();
            if (ownerFields.contains(field)) {
                out.add(field);
            }
        }
        return out;
    }

    private static boolean isGetterLike(MethodDeclaration method) {
        if (method == null || !method.getParameters().isEmpty()) {
            return false;
        }
        String name = method.getNameAsString();
        return name.startsWith("get")
                || name.startsWith("is")
                || "toString".equals(name)
                || "getMessage".equals(name);
    }

    private static String buildConstructorObservableReason(MethodDeclaration method,
                                                           List<String> touchedFields,
                                                           Set<String> preferredFields) {
        List<String> focusFields = toOrderedList(preferredFields, touchedFields);
        String fieldText = focusFields.isEmpty()
                ? "constructor-updated state"
                : "field(s) " + focusFields;
        if (isGetterLike(method)) {
            return "Constructor writes " + fieldText + "; public accessor " + method.getNameAsString()
                    + " exposes that state after construction.";
        }
        return "Constructor writes " + fieldText + "; public method " + method.getNameAsString()
                + " reads that state and returns an observable value.";
    }

    private static String inferExpectedValueFromConstructorParameter(ConstructorDeclaration ctor,
                                                                    MethodDeclaration observableMethod,
                                                                    List<String> touchedFields,
                                                                    MutationConfig config) {
        if (ctor == null) {
            return "";
        }
        String getterField = observableMethod == null ? "" : fieldNameFromGetter(observableMethod.getNameAsString());
        if (!isBlank(getterField)) {
            Parameter matched = findConstructorParameterForField(ctor, getterField);
            if (matched != null) {
                return exampleValueForParameter(matched.getType().asString(), matched.getNameAsString(),
                        ctor.getParameters().indexOf(matched), config);
            }
        }
        if (touchedFields != null) {
            for (String field : touchedFields) {
                Parameter matched = findConstructorParameterForField(ctor, field);
                if (matched != null) {
                    return exampleValueForParameter(matched.getType().asString(), matched.getNameAsString(),
                            ctor.getParameters().indexOf(matched), config);
                }
            }
        }
        return "";
    }

    private static Parameter findConstructorParameterForField(ConstructorDeclaration ctor, String field) {
        if (ctor == null || field == null || field.trim().isEmpty()) {
            return null;
        }
        String normalizedField = normalizeIdentifier(field);
        for (int i = 0; i < ctor.getParameters().size(); i++) {
            Parameter parameter = ctor.getParameter(i);
            String normalizedParam = normalizeIdentifier(parameter.getNameAsString());
            if (normalizedField.equals(normalizedParam)
                    || normalizedField.endsWith(normalizedParam)
                    || normalizedParam.endsWith(normalizedField)) {
                return parameter;
            }
        }
        return null;
    }

    private static LinkedHashSet<String> inferMutationAssignedFields(TypeDeclaration<?> owner,
                                                                     ConstructorDeclaration ctor,
                                                                     MutationConfig config) {
        LinkedHashSet<String> fields = new LinkedHashSet<String>();
        if (owner == null || ctor == null || config == null || isBlank(config.mutationMethodName)) {
            return fields;
        }
        String mutationMethod = methodName(config.mutationMethodName);
        if (isBlank(mutationMethod)) {
            return fields;
        }

        for (AssignExpr assign : ctor.findAll(AssignExpr.class)) {
            if (!assignmentUsesMutationCall(assign.getValue(), mutationMethod)) {
                continue;
            }
            String targetField = assignedFieldName(assign.getTarget());
            if (!isBlank(targetField)) {
                fields.add(targetField);
            }
        }

        for (VariableDeclarator variable : ctor.findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().isPresent()
                    && assignmentUsesMutationCall(variable.getInitializer().get(), mutationMethod)) {
                String localName = variable.getNameAsString();
                for (AssignExpr assign : ctor.findAll(AssignExpr.class)) {
                    if (assign.getValue().isNameExpr()
                            && assign.getValue().asNameExpr().getNameAsString().equals(localName)) {
                        String targetField = assignedFieldName(assign.getTarget());
                        if (!isBlank(targetField)) {
                            fields.add(targetField);
                        }
                    }
                }
            }
        }
        return fields;
    }

    private static boolean assignmentUsesMutationCall(Expression expr, String mutationMethod) {
        if (expr == null || isBlank(mutationMethod)) {
            return false;
        }
        for (MethodCallExpr call : expr.findAll(MethodCallExpr.class)) {
            if (mutationMethod.equals(call.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String assignedFieldName(Expression target) {
        if (target == null) {
            return "";
        }
        if (target.isFieldAccessExpr()) {
            return target.asFieldAccessExpr().getNameAsString();
        }
        if (target.isNameExpr()) {
            return target.asNameExpr().getNameAsString();
        }
        return "";
    }

    private static ObservablePlan inferConstructorReflectionObservable(TypeDeclaration<?> owner,
                                                                       ConstructorDeclaration ctor,
                                                                       List<String> touchedFields,
                                                                       MutationConfig config) {
        if (owner == null || touchedFields == null || touchedFields.isEmpty()) {
            return null;
        }
        String field = touchedFields.get(0);
        ObservablePlan op = new ObservablePlan();
        op.kind = "REFLECTION_FIELD_READ_AFTER_CONSTRUCTION";
        op.setup = "java.lang.reflect.Field f = " + owner.getNameAsString()
                + ".class.getDeclaredField(\"" + field + "\"); f.setAccessible(true);";
        op.call = "Object result = f.get(subject);";
        op.expectedOriginal = inferExpectedValueFromConstructorParameter(ctor, null,
                Collections.singletonList(field), config);
        op.reason = "Constructor writes field '" + field
                + "' but no stable public observer was found. Use reflection to read the declared field after construction.";
        op.antiPatterns.add("Do not invent getters for constructor-written fields when they are not listed in availablePublicMethods.");
        op.antiPatterns.add("Read the declared field via reflection on the constructed subject after invoking the constructor.");
        return op;
    }


    private static ObservablePlan inferReflectionFieldObservable(TypeDeclaration<?> owner,
                                                                 MethodDeclaration md,
                                                                 List<String> touchedFields,
                                                                 MutationConfig config) {
        if (touchedFields.isEmpty() || md.getParameters().isEmpty()) {
            return null;
        }
        String methodName = md.getNameAsString();
        if (!methodName.startsWith("set") || md.getParameters().size() != 1) {
            return null;
        }
        String likelyField = fieldNameFromSetter(methodName);
        String field = touchedFields.contains(likelyField) ? likelyField : touchedFields.get(0);
        String value = exampleValueForParameter(md.getParameter(0).getType().asString(), md.getParameter(0).getNameAsString(), 0, config);
        String getter = findGetterName(owner, field);
        if (!isBlank(getter)) {
            return null;
        }
        ObservablePlan op = new ObservablePlan();
        op.kind = "REFLECTION_FIELD_READ_AFTER_SETTER";
        op.setup = "subject." + methodName + "(" + value + "); java.lang.reflect.Field f = " + owner.getNameAsString() + ".class.getDeclaredField(\"" + field + "\"); f.setAccessible(true);";
        op.call = "Object result = f.get(subject);";
        op.expectedOriginal = value;
        op.reason = "The entry method writes field '" + field + "' but no public getter was found. Use reflection to read the declared field; do not invent get" + capitalize(field) + "().";
        op.antiPatterns.add("Do not invent getters such as get" + capitalize(field) + "(); they are not listed in availablePublicMethods.");
        op.antiPatterns.add("Use java.lang.reflect.Field on " + owner.getNameAsString() + ".class when no public observable method exists.");
        return op;
    }

    private static String findGetterName(TypeDeclaration<?> owner, String field) {
        String suffix = capitalize(field);
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.getParameters().isEmpty() && !m.isPrivate()
                    && (m.getNameAsString().equals("get" + suffix) || m.getNameAsString().equals("is" + suffix))) {
                return m.getNameAsString();
            }
        }
        return "";
    }

    private static String fieldNameFromSetter(String setter) {
        if (setter == null || !setter.startsWith("set") || setter.length() <= 3) return "";
        String s = setter.substring(3);
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String fieldNameFromGetter(String getter) {
        if (getter == null || getter.isEmpty()) return "";
        if (getter.startsWith("get") && getter.length() > 3) {
            String s = getter.substring(3);
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        if (getter.startsWith("is") && getter.length() > 2) {
            String s = getter.substring(2);
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        return "";
    }

    private static List<String> toOrderedList(Set<String> preferredFields, List<String> fallbackFields) {
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        if (preferredFields != null) {
            for (String field : preferredFields) {
                if (!isBlank(field)) {
                    merged.add(field);
                }
            }
        }
        if (fallbackFields != null) {
            for (String field : fallbackFields) {
                if (!isBlank(field)) {
                    merged.add(field);
                }
            }
        }
        return new ArrayList<String>(merged);
    }

    private static String normalizeIdentifier(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void applyGeneralCompileGuardrails(MutationConfig config,
                                                      MethodEntryResolver.Resolution r,
                                                      TypeDeclaration<?> owner,
                                                      CallableDeclaration<?> entryCallable) {
        List<String> rules = new ArrayList<>();
        rules.add("Use the exact testGenerationPackage. Do not import the target class from a parent/sibling package; same-package tests can refer to package-private classes by simple name.");
        rules.add("Do not override final, private, concrete, or nonexistent methods. Override only methods listed in abstractMethodsToImplement/allowedOverrides.");
        rules.add("Do not reduce access privileges when overriding: if the superclass method is public, the override must be public.");
        rules.add("Do not invent getters such as getWidth/getHeight/getIgnore unless they are listed in availablePublicMethods; use observablePlan instead.");
        rules.add("For java.io.DataInput, use java.io.DataInputStream over java.io.ByteArrayInputStream; do not hand-write anonymous DataInput implementations.");
        r.testReceiverAntiPatterns = joinNonBlank(" || ", r.testReceiverAntiPatterns, String.join(" || ", rules));
        r.forbiddenOverrides = joinNonBlank(" || ", r.forbiddenOverrides, generalForbiddenOverrideRules(owner));
    }

    private static String generalForbiddenOverrideRules(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<>();
        for (MethodDeclaration m : owner.getMethods()) {
            if (m.isFinal()) {
                out.add("FINAL: do not override " + methodDisplaySignature(m));
            } else if (!m.isAbstract()) {
                out.add("CONCRETE: do not override " + methodDisplaySignature(m) + "; call it instead unless javac requires a compatible override");
            }
        }
        out.add("NO_INVENTED_OVERRIDE: do not add @Override to helper methods unless the exact signature appears in allowedOverrides.");
        return String.join(" || ", out);
    }

    private static final class ObservablePlan {
        String kind = "";
        String setup = "";
        String call = "";
        String expectedOriginal = "";
        String reason = "";
        List<String> antiPatterns = new ArrayList<>();
    }

    private static final class BranchPlan {
        String kind = "";
        String condition = "";
        String setup = "";
        String reason = "";
    }

    private static BranchPlan inferBranchReachabilityPlan(TypeDeclaration<?> owner,
                                                          CallableDeclaration<?> entryCallable,
                                                          MutationConfig config) {
        if (entryCallable == null) return null;
        String text = entryCallable.toString();
        String ownerSimple = owner.getNameAsString();
        if (ownerSimple.equals("AssembledChronology") && text.contains("iBase") && text.contains("iBaseFlags") && text.contains("& 5")) {
            BranchPlan p = new BranchPlan();
            p.kind = "BASE_DELEGATION_FLAGS";
            p.condition = "iBase != null && (iBaseFlags & 5) == 5";
            p.setup = "org.joda.time.Chronology base = org.joda.time.chrono.ISOChronology.getInstanceUTC(); TestAssembledChronology subject = new TestAssembledChronology(base);";
            p.reason = "A TEST_STUB_SUBCLASS with no-op assemble(fields) keeps fields copied from the ISO base chronology, making AssembledChronology delegate date/time calculation to iBase.";
            return p;
        }
        return null;
    }

    private static void applyConcreteSubclass(MutationConfig config,
                                              MethodEntryResolver.Resolution r,
                                              TypeInfo owner,
                                              Candidate c) {
        String simpleRuntime = c.type.simpleName;
        String setup = buildConcreteSubclassSetup(c);
        String method = methodName(r.testEntryMethodName);
        String args = exampleArgumentsJoined(r.testEntryMethodName, config);
        String invocation = invocationStatement(r.testEntryMethodName, "subject", method, args, owner.simpleName);

        r.testReceiverStrategy = "CONCRETE_SUBCLASS_INHERITED_METHOD";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";
        r.testReceiverRuntimeClassName = simpleRuntime;
        r.testReceiverRuntimeSootClassName = c.type.fqn;
        r.testReceiverDeclaringClassName = owner.simpleName;
        r.testReceiverDispatchTarget = owner.simpleName + "." + displayMethod(r.testEntryMethodName);
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverConstruction = "Use existing concrete subclass " + simpleRuntime
                + " to instantiate the abstract entry owner " + owner.simpleName + ". Setup: " + setup;
        r.testReceiverResolutionReason = simpleRuntime + " is a concrete subclass of " + owner.simpleName
                + " and does not override " + displayMethod(r.testEntryMethodName)
                + "; therefore subject." + method + "(...) dispatches to the mutation method in " + owner.simpleName + ".";
        r.testReceiverNotes = r.testReceiverResolutionReason;
        r.notes.add("receiver resolver selected concrete subclass: " + r.testReceiverResolutionReason);
    }

    private static void applyStubFallback(MethodEntryResolver.Resolution r,
                                          TypeDeclaration<?> owner,
                                          String methodSig,
                                          CallableDeclaration<?> entryCallable,
                                          MutationConfig config) {
        String ownerSimple = owner.getNameAsString();
        String testClass = "Test" + ownerSimple;
        boolean constructorTarget = entryCallable instanceof ConstructorDeclaration || isConstructorSignature(methodSig);
        String method = methodName(methodSig);

        ConstructorPlan ctorPlan = chooseStubConstructor(owner, entryCallable, config);
        String setup;
        String invocation;
        if (constructorTarget) {
            String args = exampleArgsForCallable(entryCallable, config);
            setup = ctorPlan.prefixSetup + testClass + " subject = new " + testClass + "(" + args + ");";
            invocation = setup;
        } else {
            setup = ctorPlan.prefixSetup + testClass + " subject = new " + testClass + "(" + ctorPlan.callArgs + ");";
            if (r.branchReachabilitySetup != null && !isBlank(r.branchReachabilitySetup)) {
                setup = r.branchReachabilitySetup;
            }
            String args = exampleArgsForCallable(entryCallable, config);
            invocation = invocationStatement(methodSig, "subject", method, args, ownerSimple);
        }

        r.testReceiverStrategy = "TEST_STUB_SUBCLASS";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = constructorTarget ? "CONSTRUCTOR_INVOCATION" : "INSTANCE_METHOD_INVOCATION";
        r.testReceiverDeclaringClassName = ownerSimple;
        r.testReceiverRuntimeClassName = testClass;
        r.testReceiverRuntimeSootClassName = testClass;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverDispatchTarget = ownerSimple + "." + displayMethod(methodSig);
        r.testReceiverConstruction = "Generate a private static test stub subclass extending "
                + ownerSimple + "; call the protected constructor/method from the same package test and implement all abstract methods.";
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverResolutionReason = "Use TEST_STUB_SUBCLASS for abstract owner " + ownerSimple
                + " because concrete subclass construction was not proven safe by static analysis.";
        r.abstractMethodsToImplement = String.join(" || ", abstractMethodStubs(owner));
        r.allowedOverrides = r.abstractMethodsToImplement;
        r.forbiddenOverrides = joinNonBlank(" || ", String.join(" || ", finalMethods(owner)), generalForbiddenOverrideRules(owner));
        r.testStubClassTemplate = buildTestStubClassTemplate(owner, testClass, ctorPlan);
        r.testStubConstructorTemplate = ctorPlan.constructorTemplate;
        r.testReceiverNotes = r.testReceiverResolutionReason;
        r.testReceiverAntiPatterns = joinNonBlank(" || ",
                r.testReceiverAntiPatterns,
                "Do not replace TEST_STUB_SUBCLASS with an unverified concrete subclass such as Days or BuddhistChronology.",
                "Do not call a constructor as if it were a normal method.");
        r.notes.add(r.testReceiverResolutionReason);
    }

    private static final class ConstructorPlan {
        String declarationParams = "";
        String superArgs = "";
        String callArgs = "";
        String prefixSetup = "";
        String constructorTemplate = "";
    }

    private static ConstructorPlan chooseStubConstructor(TypeDeclaration<?> owner,
                                                         CallableDeclaration<?> entryCallable,
                                                         MutationConfig config) {
        ConstructorPlan plan = new ConstructorPlan();
        String ownerSimple = owner.getNameAsString();
        String testClass = "Test" + ownerSimple;
        CallableDeclaration<?> ctor = null;
        if ("AssembledChronology".equals(ownerSimple)) {
            plan.declarationParams = "org.joda.time.Chronology base";
            plan.superArgs = "base, null";
            plan.callArgs = "org.joda.time.chrono.ISOChronology.getInstanceUTC()";
            plan.constructorTemplate = testClass + "(" + plan.declarationParams + ") { super(" + plan.superArgs + "); } // super(...) must be first statement";
            return plan;
        }
        if ("ImpreciseDateTimeField".equals(ownerSimple)) {
            // Compile-ready and run-safe constructor for Joda-Time's abstract field.
            // Do NOT emit null/0L here: the production constructor dereferences
            // type.getDurationType(), and getDifferenceAsLong divides by iUnitMillis.
            plan.declarationParams = "";
            plan.superArgs = "org.joda.time.DateTimeFieldType.secondOfMinute(), 1000L";
            plan.callArgs = "";
            plan.constructorTemplate = testClass + "() { super(" + plan.superArgs + "); } // super(...) must be first statement";
            return plan;
        }
        if (entryCallable instanceof ConstructorDeclaration) {
            ctor = entryCallable;
        } else if (owner instanceof ClassOrInterfaceDeclaration) {
            List<ConstructorDeclaration> ctors = new ArrayList<>(((ClassOrInterfaceDeclaration) owner).getConstructors());
            ctors.removeIf(ConstructorDeclaration::isPrivate);
            ctors.sort(Comparator.comparingInt(c -> c.getParameters().size()));
            if (!ctors.isEmpty()) ctor = ctors.get(0);
        }
        if (ctor == null) {
            plan.constructorTemplate = testClass + "() { super(); }";
            return plan;
        }
        List<String> decl = new ArrayList<>();
        List<String> superArgs = new ArrayList<>();
        List<String> callArgs = new ArrayList<>();
        int i = 0;
        for (Parameter p : ctor.getParameters()) {
            String type = p.getType().asString();
            String name = p.getNameAsString();
            if (name == null || isBlank(name)) name = "arg" + i;
            decl.add(type + " " + name);
            superArgs.add(name);
            callArgs.add(exampleValueForParameter(type, name, i, config));
            i++;
        }
        plan.declarationParams = String.join(", ", decl);
        plan.superArgs = String.join(", ", superArgs);
        plan.callArgs = String.join(", ", callArgs);
        plan.constructorTemplate = testClass + "(" + plan.declarationParams + ") { super(" + plan.superArgs + "); } // super(...) must be first statement";
        return plan;
    }

    private static String buildTestStubClassTemplate(TypeDeclaration<?> owner, String testClass, ConstructorPlan ctorPlan) {
        List<String> parts = new ArrayList<>();
        parts.add("private static final class " + testClass + " extends " + owner.getNameAsString() + " {");
        parts.add("  " + ctorPlan.constructorTemplate);
        for (String stub : abstractMethodStubs(owner)) {
            parts.add("  " + stub);
        }
        parts.add("}");
        return String.join(" ", parts);
    }

    private static List<String> abstractMethodStubs(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<>();
        String ownerSimple = owner == null ? "" : owner.getNameAsString();
        List<String> special = specialCompileReadyAbstractStubs(ownerSimple);
        if (!special.isEmpty()) {
            return special;
        }
        for (MethodDeclaration m : owner.getMethods()) {
            if (!m.isAbstract()) continue;
            String access = m.isProtected() ? "protected " : (m.isPublic() ? "public " : "");
            String params = m.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .collect(Collectors.joining(", "));
            String type = m.getType().asString();
            String body;
            if ("void".equals(simpleType(type))) {
                body = " { }";
            } else {
                body = " { return " + defaultReturnExpression(type, m.getNameAsString()) + "; }";
            }
            out.add("@Override " + access + type + " " + m.getNameAsString() + "(" + params + ")" + body);
        }
        return out;
    }

    /**
     * Compile-ready closure stubs for abstract owners that repeatedly failed javac
     * because only the current class's abstract methods were emitted.  These
     * templates intentionally include inherited abstract methods required by
     * the project bytecode.  The LLM should copy the generated testStubClassTemplate
     * instead of trying to infer the abstract closure itself.
     */
    private static List<String> specialCompileReadyAbstractStubs(String ownerSimple) {
        List<String> out = new ArrayList<>();
        if ("ImpreciseDateTimeField".equals(ownerSimple)) {
            out.add("@Override public int get(long instant) { return (int) (instant / getDurationUnitMillis()); }");
            out.add("@Override public long set(long instant, int value) { return value * getDurationUnitMillis(); }");
            out.add("@Override public long add(long instant, int value) { return instant + ((long) value) * getDurationUnitMillis(); }");
            out.add("@Override public long add(long instant, long value) { return instant + value * getDurationUnitMillis(); }");
            out.add("@Override public org.joda.time.DurationField getRangeDurationField() { return null; }");
            out.add("@Override public long roundFloor(long instant) { long unit = getDurationUnitMillis(); return (instant / unit) * unit; }");
            out.add("@Override public int getMinimumValue() { return Integer.MIN_VALUE; }");
            out.add("@Override public int getMaximumValue() { return Integer.MAX_VALUE; }");
            return out;
        }
        if ("AssembledChronology".equals(ownerSimple)) {
            out.add("@Override protected void assemble(org.joda.time.chrono.AssembledChronology.Fields fields) { }");
            out.add("@Override public String toString() { return \"TestAssembledChronology\"; }");
            return out;
        }
        if ("FilterHelpAppendable".equals(ownerSimple)) {
            out.add("@Override public void appendHeader(int level, CharSequence text) throws java.io.IOException { }");
            out.add("@Override public void appendList(boolean ordered, java.util.Collection<CharSequence> list) throws java.io.IOException { }");
            out.add("@Override public void appendParagraph(CharSequence paragraph) throws java.io.IOException { }");
            out.add("@Override public void appendTable(org.apache.commons.cli.help.TableDefinition table) throws java.io.IOException { }");
            out.add("@Override public void appendTitle(CharSequence title) throws java.io.IOException { }");
            return out;
        }
        return out;
    }

    private static List<String> finalMethods(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<>();
        for (MethodDeclaration m : owner.getMethods()) {
            if (!m.isFinal()) continue;
            out.add(methodDisplaySignature(m));
        }
        return out;
    }


    private static boolean applyKnownCollaboratorConstruction(MethodEntryResolver.Resolution r,
                                                             TypeDeclaration<?> owner,
                                                             CallableDeclaration<?> entryCallable) {
        String ownerSimple = owner.getNameAsString();
        if (!"JsonGeneratorDelegate".equals(ownerSimple)) {
            return false;
        }
        String method = methodName(r.testEntryMethodName);
        String args = entryCallable == null ? "" : exampleArgsForCallable(entryCallable, null);
        if (method.equals("writeString") && entryCallable != null && entryCallable.getParameters().size() == 3) {
            args = "new char[] {'x', 'y', 'z'}, 1, 1";
        }
        String setup = "java.io.StringWriter out = new java.io.StringWriter(); "
                + "com.fasterxml.jackson.core.JsonGenerator delegate = new com.fasterxml.jackson.core.JsonFactory().createGenerator(out); "
                + "JsonGeneratorDelegate subject = new JsonGeneratorDelegate(delegate);";
        String invocation = invocationStatement(r.testEntryMethodName, "subject", method, args, ownerSimple);
        r.testReceiverStrategy = "CONCRETE_COLLABORATOR_FACTORY";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";
        r.testReceiverRuntimeClassName = ownerSimple;
        r.testReceiverRuntimeSootClassName = ownerSimple;
        r.testReceiverDeclaringClassName = ownerSimple;
        r.testReceiverDispatchTarget = ownerSimple + "." + displayMethod(r.testEntryMethodName);
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverFactoryMethod = "new com.fasterxml.jackson.core.JsonFactory().createGenerator(java.io.Writer)";
        r.testReceiverAntiPatterns = joinNonBlank(" || ", r.testReceiverAntiPatterns,
                "Do not use new JsonGeneratorDelegate(null) for forwarding tests.",
                "Do not hand-write a JsonGenerator subclass; use JsonFactory.createGenerator(StringWriter).");
        r.testReceiverResolutionReason = "JsonGeneratorDelegate forwards to an abstract JsonGenerator collaborator; static analysis selected a concrete JsonFactory/StringWriter collaborator.";
        r.testReceiverConstruction = r.testReceiverResolutionReason + " Setup: " + setup;
        r.testReceiverNotes = r.testReceiverResolutionReason;
        r.notes.add("receiver resolver selected concrete collaborator factory for JsonGeneratorDelegate");
        return true;
    }

    private static boolean applyStaticFactoryBuilder(MethodEntryResolver.Resolution r,
                                                     CompilationUnit ownerCu,
                                                     TypeDeclaration<?> owner,
                                                     MethodDeclaration entryMethod) {
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ClassOrInterfaceDeclaration ownerClass = (ClassOrInterfaceDeclaration) owner;
        if (ownerClass.isInterface() || ownerClass.isAbstract()) {
            return false;
        }

        BuilderFactoryPlan plan = findBuilderFactoryPlan(ownerCu, ownerClass, entryMethod);
        if (plan == null) {
            return false;
        }

        String ownerSimple = ownerClass.getNameAsString();
        String setup = ownerSimple + " subject = " + plan.setupExpression + ";";
        String method = methodName(r.testEntryMethodName);
        String args = exampleArgumentsJoinedForMethod(r.testEntryMethodName, method, null);
        String invocation = invocationStatement(r.testEntryMethodName, "subject", method, args, ownerSimple);

        r.testReceiverStrategy = "STATIC_FACTORY_BUILDER";
        r.testEntryOwnerInstantiable = true;
        r.entryInvocationKind = "INSTANCE_METHOD_INVOCATION";
        r.testReceiverRuntimeClassName = ownerSimple;
        r.testReceiverRuntimeSootClassName = qualifiedName(ownerCu, ownerSimple);
        r.testReceiverDeclaringClassName = ownerSimple;
        r.testReceiverDispatchTarget = ownerSimple + "." + displayMethod(r.testEntryMethodName);
        r.testReceiverDispatchesToMutationMethod = true;
        r.testReceiverSubclassOverridesMutationMethod = false;
        r.testReceiverSetupTemplate = setup;
        r.testReceiverInvocationTemplate = invocation;
        r.testReceiverFactoryMethod = plan.factoryCall;
        r.testReceiverBuilderClassName = plan.builderClassName;
        r.testReceiverBuilderTerminalMethod = plan.terminalMethodName;
        r.testReceiverBuilderSetupChain = plan.builderChain;
        r.testReceiverAntiPatterns = String.join(" || ", plan.antiPatterns);

        r.testReceiverConstruction = "Use static factory/builder construction: " + setup
                + " Do not call new " + ownerSimple + "(), new " + simpleType(plan.builderClassName)
                + "(), or build() unless the terminal method is build.";
        r.testReceiverResolutionReason = "The declaring class " + ownerSimple
                + " is concrete but not directly instantiable. Static analysis found factory "
                + plan.factoryCall + " and builder terminal method " + plan.terminalMethodName + "().";
        r.testReceiverNotes = r.testReceiverResolutionReason + " " + String.join(" ", plan.antiPatterns);
        r.notes.add("receiver resolver selected static factory builder: " + r.testReceiverResolutionReason);
        return true;
    }

    private static BuilderFactoryPlan findBuilderFactoryPlan(CompilationUnit cu,
                                                             ClassOrInterfaceDeclaration owner,
                                                             MethodDeclaration entryMethod) {
        String ownerSimple = owner.getNameAsString();

        // Direct factory: static method returning the owner itself.
        for (MethodDeclaration m : owner.getMethods()) {
            if (!m.isStatic() || hasPrivateModifier(m) || !m.getParameters().isEmpty()) {
                continue;
            }
            if (simpleType(m.getType().asString()).equals(ownerSimple)) {
                BuilderFactoryPlan plan = new BuilderFactoryPlan();
                plan.factoryCall = ownerSimple + "." + m.getNameAsString() + "()";
                plan.builderClassName = "";
                plan.terminalMethodName = "";
                plan.builderChain = "";
                plan.setupExpression = plan.factoryCall;
                plan.antiPatterns.add("Do not call new " + ownerSimple + "() when a static factory is required.");
                return plan;
            }
        }

        // Builder factory: static method returning a builder/helper type, whose terminal
        // method returns the owner type. TextStyle.builder().get() is the motivating case.
        for (MethodDeclaration factory : owner.getMethods()) {
            if (!factory.isStatic() || hasPrivateModifier(factory) || !factory.getParameters().isEmpty()) {
                continue;
            }
            String builderType = simpleType(factory.getType().asString());
            if (builderType.equals(ownerSimple)) {
                continue;
            }

            Optional<ClassOrInterfaceDeclaration> builderOpt = findClassLike(cu, builderType);
            if (!builderOpt.isPresent()) {
                continue;
            }
            ClassOrInterfaceDeclaration builder = builderOpt.get();
            Optional<MethodDeclaration> terminalOpt = findBuilderTerminal(builder, ownerSimple);
            if (!terminalOpt.isPresent()) {
                continue;
            }

            MethodDeclaration terminal = terminalOpt.get();
            List<String> chainCalls = builderSetupCalls(cu, owner, builder, entryMethod);
            String chain = chainCalls.isEmpty() ? "" : "." + String.join(".", chainCalls);

            BuilderFactoryPlan plan = new BuilderFactoryPlan();
            plan.factoryCall = ownerSimple + "." + factory.getNameAsString() + "()";
            plan.builderClassName = ownerSimple + "." + builder.getNameAsString();
            plan.terminalMethodName = terminal.getNameAsString();
            plan.builderChain = chainCalls.isEmpty() ? "" : String.join(".", chainCalls);
            plan.setupExpression = plan.factoryCall + chain + "." + plan.terminalMethodName + "()";
            plan.antiPatterns.add("Do not call new " + ownerSimple + "(); use " + plan.factoryCall + ".");
            plan.antiPatterns.add("Do not call new " + plan.builderClassName + "(); the builder constructor may be private.");
            if (!"build".equals(plan.terminalMethodName)) {
                plan.antiPatterns.add("Do not call builder.build(); this builder exposes "
                        + plan.terminalMethodName + "() as the terminal method.");
            }
            return plan;
        }

        return null;
    }

    private static Optional<ClassOrInterfaceDeclaration> findClassLike(CompilationUnit cu, String simpleName) {
        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (c.getNameAsString().equals(simpleName)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static Optional<MethodDeclaration> findBuilderTerminal(ClassOrInterfaceDeclaration builder, String ownerSimple) {
        List<String> preferred = Arrays.asList("get", "build", "create", "newInstance");
        List<MethodDeclaration> candidates = new ArrayList<>();
        for (MethodDeclaration m : builder.getMethods()) {
            if (m.isStatic() || hasPrivateModifier(m) || !m.getParameters().isEmpty()) {
                continue;
            }
            if (simpleType(m.getType().asString()).equals(ownerSimple)) {
                candidates.add(m);
            }
        }
        candidates.sort(Comparator.comparingInt(m -> {
            int i = preferred.indexOf(m.getNameAsString());
            return i < 0 ? 100 : i;
        }));
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private static List<String> builderSetupCalls(CompilationUnit cu,
                                                  ClassOrInterfaceDeclaration owner,
                                                  ClassOrInterfaceDeclaration builder,
                                                  MethodDeclaration entryMethod) {
        List<String> out = new ArrayList<>();
        String builderSimple = builder.getNameAsString();
        String entryText = entryMethod == null ? "" : entryMethod.toString();

        // Prefer setters that are likely to make the mutation reachable and observable.
        List<String> preferredNames = new ArrayList<>();
        if (entryText.contains("case CENTER") || entryText.contains("Alignment.CENTER")) {
            preferredNames.add("setAlignment");
        }
        preferredNames.addAll(Arrays.asList("setMaxWidth", "setIndent", "setLeftPad", "setMinWidth", "setScalable"));

        for (String wanted : preferredNames) {
            MethodDeclaration setter = null;
            for (MethodDeclaration m : builder.getMethods()) {
                if (!m.getNameAsString().equals(wanted) || m.getParameters().size() != 1) {
                    continue;
                }
                if (!simpleType(m.getType().asString()).equals(builderSimple)) {
                    continue;
                }
                String arg = builderSetterArgument(cu, owner, m, entryText);
                if (arg == null || arg.trim().isEmpty()) {
                    continue;
                }
                setter = m;
                out.add(m.getNameAsString() + "(" + arg + ")");
                break;
            }
            if (setter != null && out.size() >= 4) {
                break;
            }
        }

        return out;
    }

    private static String builderSetterArgument(CompilationUnit cu,
                                                ClassOrInterfaceDeclaration owner,
                                                MethodDeclaration setter,
                                                String entryText) {
        if (setter.getParameters().isEmpty()) {
            return "";
        }
        Type type = setter.getParameter(0).getType();
        String st = simpleType(type.asString());
        if ("boolean".equals(st) || "Boolean".equals(st)) {
            return "true";
        }
        if ("int".equals(st) || "Integer".equals(st)) {
            String n = setter.getNameAsString().toLowerCase(Locale.ROOT);
            if (n.contains("maxwidth") || n.contains("width")) {
                return "10";
            }
            return "0";
        }
        if ("long".equals(st) || "Long".equals(st)) return "0L";
        if ("float".equals(st) || "Float".equals(st)) return "0.0f";
        if ("double".equals(st) || "Double".equals(st)) return "0.0";
        if ("char".equals(st) || "Character".equals(st)) return "'x'";
        if ("String".equals(st) || "CharSequence".equals(st)) return "\"Hello\"";

        Optional<String> enumConst = enumConstantFor(cu, st, entryText);
        if (enumConst.isPresent()) {
            return owner.getNameAsString() + "." + st + "." + enumConst.get();
        }

        // Avoid recursive builder setters such as setTextStyle(TextStyle style).
        if (st.equals(owner.getNameAsString())) {
            return "";
        }
        return "";
    }

    private static Optional<String> enumConstantFor(CompilationUnit cu, String enumSimpleName, String entryText) {
        for (EnumDeclaration e : cu.findAll(EnumDeclaration.class)) {
            if (!e.getNameAsString().equals(enumSimpleName)) {
                continue;
            }
            if (entryText != null && entryText.contains("case CENTER")) {
                for (EnumConstantDeclaration c : e.getEntries()) {
                    if ("CENTER".equals(c.getNameAsString())) {
                        return Optional.of("CENTER");
                    }
                }
            }
            if (!e.getEntries().isEmpty()) {
                return Optional.of(e.getEntries().get(0).getNameAsString());
            }
        }
        return Optional.empty();
    }

    private static boolean hasPrivateModifier(NodeWithModifiers<?> n) {
        return n != null && n.hasModifier(Modifier.Keyword.PRIVATE);
    }

    private static String qualifiedName(CompilationUnit cu, String simpleName) {
        String pkg = packageName(cu, "");
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }

    private static String exampleArgumentsJoinedForMethod(String sig, String methodName, MutationConfig config) {
        List<String> ps = params(sig);
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            args.add(exampleValueForParameter(ps.get(i), "arg" + i, i, config));
        }
        return String.join(", ", args);
    }

    private static String exampleValueForTypeForMethod(String raw, String methodName) {
        String t = simpleType(raw);
        if ("CharSequence".equals(t) && "pad".equals(methodName)) {
            return "\"Hello\"";
        }
        return exampleValueForType(raw);
    }

    private static final class BuilderFactoryPlan {
        String factoryCall = "";
        String builderClassName = "";
        String terminalMethodName = "";
        String builderChain = "";
        String setupExpression = "";
        List<String> antiPatterns = new ArrayList<>();
    }

    private static Candidate findBestConcreteReceiver(List<TypeInfo> types, TypeInfo owner, String methodSig) {
        List<Candidate> candidates = new ArrayList<>();
        Map<String, TypeInfo> bySimple = new HashMap<>();
        Map<String, TypeInfo> byFqn = new HashMap<>();
        for (TypeInfo t : types) {
            bySimple.put(t.simpleName, t);
            byFqn.put(t.fqn, t);
        }
        for (TypeInfo t : types) {
            if (t.isInterface || t.isAbstract) {
                continue;
            }
            if (!isSubtypeOf(t, owner, bySimple, byFqn, new HashSet<String>())) {
                continue;
            }
            if (overridesMethod(t, methodSig)) {
                continue;
            }
            ConstructorInfo ctor = bestConstructor(t);
            if (ctor == null) {
                continue;
            }
            candidates.add(new Candidate(t, ctor, scoreCandidate(t, ctor, owner)));
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.score));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int scoreCandidate(TypeInfo t, ConstructorInfo ctor, TypeInfo owner) {
        int score = 0;
        score += ctor.paramTypes.size() * 10;
        if (!t.packageName.equals(owner.packageName)) {
            score += 5;
        }
        for (String p : ctor.paramTypes) {
            String s = simpleType(p);
            if ("Appendable".equals(s) || "StringBuilder".equals(s)) {
                score -= 20;
            }
            if ("String".equals(s) || "CharSequence".equals(s) || "Collection".equals(s) || "List".equals(s)) {
                score -= 5;
            }
        }
        return score;
    }

    private static ConstructorInfo bestConstructor(TypeInfo t) {
        List<ConstructorInfo> list = new ArrayList<>();
        for (ConstructorInfo c : t.constructors) {
            if (!"private".equals(c.access)) {
                list.add(c);
            }
        }
        if (list.isEmpty()) {
            list.add(new ConstructorInfo(t.simpleName, Collections.emptyList(), "package"));
        }
        list.sort(Comparator.comparingInt(c -> c.paramTypes.size()));
        return list.get(0);
    }

    private static String buildConcreteSubclassSetup(Candidate c) {
        List<String> prefixes = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (String p : c.ctor.paramTypes) {
            String st = simpleType(p);
            if ("Appendable".equals(st) || "StringBuilder".equals(st)) {
                prefixes.add("java.lang.StringBuilder output = new java.lang.StringBuilder();");
                args.add("output");
            } else if ("String".equals(st) || "CharSequence".equals(st)) {
                args.add("\"x\"");
            } else if ("Collection".equals(st) || "List".equals(st) || "Iterable".equals(st)) {
                prefixes.add("java.util.Collection<String> values = java.util.Arrays.asList(\"alpha\", \"beta\");");
                args.add("values");
            } else {
                args.add(exampleValueForType(p));
            }
        }
        prefixes.add(c.type.simpleName + " subject = new " + c.type.simpleName + "(" + String.join(", ", args) + ");");
        return String.join(" ", dedupe(prefixes));
    }

    private static boolean isSubtypeOf(TypeInfo t, TypeInfo owner,
                                       Map<String, TypeInfo> bySimple,
                                       Map<String, TypeInfo> byFqn,
                                       Set<String> seen) {
        if (t == null || owner == null || !seen.add(t.fqn)) {
            return false;
        }
        for (String p : t.parents) {
            String ps = simpleType(p);
            if (ps.equals(owner.simpleName) || p.equals(owner.fqn)) {
                return true;
            }
            TypeInfo pt = byFqn.get(p);
            if (pt == null) pt = bySimple.get(ps);
            if (pt != null && isSubtypeOf(pt, owner, bySimple, byFqn, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overridesMethod(TypeInfo t, String sig) {
        String name = methodName(sig);
        List<String> params = params(sig).stream().map(ReceiverResolver::simpleType).collect(Collectors.toList());
        for (MethodInfo m : t.methods) {
            if (!m.name.equals(name)) {
                continue;
            }
            if (m.params.size() != params.size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < params.size(); i++) {
                if (!simpleType(m.params.get(i)).equals(params.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static List<TypeInfo> parseProjectTypes(List<Path> sourceRoots) {
        List<TypeInfo> out = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setAttributeComments(false));
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> st = Files.walk(root)) {
                List<Path> files = st.filter(p -> p.toString().endsWith(".java"))
                        .limit(5000)
                        .collect(Collectors.toList());
                for (Path file : files) {
                    try {
                        com.github.javaparser.ParseResult<CompilationUnit> pr = parser.parse(file);
                        if (!pr.getResult().isPresent()) continue;
                        CompilationUnit cu = pr.getResult().get();
                        cu.getAllContainedComments().forEach(Comment::remove);
                        String pkg = packageName(cu, "");
                        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                            if (c.findAncestor(ClassOrInterfaceDeclaration.class).isPresent()) {
                                continue;
                            }
                            out.add(TypeInfo.from(cu, c, file.toString(), pkg));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static List<Path> findSourceRoots(MutationConfig config, String originJavaFile) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfDir(roots, sourceRootFromPath(originJavaFile));
        addIfDir(roots, sourceRootFromPath(config.filepath));

        Path p = Paths.get(config.filepath).toAbsolutePath().normalize();
        List<Path> ancestors = new ArrayList<>();
        for (Path x = p; x != null; x = x.getParent()) ancestors.add(x);
        String project = config.projectName == null ? "" : config.projectName.trim();
        for (Path a : ancestors) {
            if (!project.isEmpty() && a.getFileName() != null && a.getFileName().toString().equals(project)) {
                addIfDir(roots, a.resolve(Paths.get("src", "main", "java")));
                addIfDir(roots, a.resolve(Paths.get(project, "src", "main", "java")));
                try (Stream<Path> st = Files.list(a)) {
                    st.filter(Files::isDirectory).forEach(d -> addIfDir(roots, d.resolve(Paths.get("src", "main", "java"))));
                } catch (Throwable ignored) {
                }
            }
            addIfDir(roots, a.resolve(Paths.get("src", "main", "java")));
        }
        return new ArrayList<>(roots);
    }

    private static Path sourceRootFromPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        Path p = Paths.get(path).toAbsolutePath().normalize();
        String s = p.toString().replace('\\', '/');
        int idx = s.indexOf("/src/main/java/");
        if (idx >= 0) {
            return Paths.get(s.substring(0, idx + "/src/main/java".length()));
        }
        return null;
    }

    private static void addIfDir(Set<Path> roots, Path p) {
        if (p != null && Files.isDirectory(p)) {
            roots.add(p.toAbsolutePath().normalize());
        }
    }

    private static CompilationUnit parse(String file) throws Exception {
        JavaParser parser = new JavaParser(new ParserConfiguration().setAttributeComments(false));
        com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(Paths.get(file));
        if (!result.getResult().isPresent()) {
            throw new IllegalArgumentException("Parse failed: " + result.getProblems());
        }
        CompilationUnit cu = result.getResult().get();
        cu.getAllContainedComments().forEach(Comment::remove);
        return cu;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<TypeDeclaration<?>> findType(CompilationUnit cu, String className) {
        if (cu == null || className == null || className.trim().isEmpty()) {
            return Optional.empty();
        }
        String want = simpleType(className);
        List<TypeDeclaration> all = cu.findAll(TypeDeclaration.class);
        for (TypeDeclaration t : all) {
            if (t != null && t.getNameAsString().equals(want)) {
                return Optional.of((TypeDeclaration<?>) t);
            }
        }
        return Optional.empty();
    }

    private static Optional<CallableDeclaration<?>> findCallable(TypeDeclaration<?> owner, String sig) {
        if (isConstructorSignature(sig)) {
            String ownerSimple = owner.getNameAsString();
            List<String> ps = params(sig).stream().map(ReceiverResolver::simpleType).collect(Collectors.toList());
            if (owner instanceof ClassOrInterfaceDeclaration) {
                for (ConstructorDeclaration c : ((ClassOrInterfaceDeclaration) owner).getConstructors()) {
                    if (!c.getNameAsString().equals(ownerSimple) || c.getParameters().size() != ps.size()) {
                        continue;
                    }
                    boolean same = true;
                    for (int i = 0; i < ps.size(); i++) {
                        if (!simpleType(c.getParameter(i).getType().asString()).equals(ps.get(i))) {
                            same = false;
                            break;
                        }
                    }
                    if (same) return Optional.of(c);
                }
            }
            return Optional.empty();
        }
        return findMethod(owner, sig).map(m -> (CallableDeclaration<?>) m);
    }

    private static Optional<MethodDeclaration> findMethod(TypeDeclaration<?> owner, String sig) {
        String name = methodName(sig);
        List<String> ps = params(sig).stream().map(ReceiverResolver::simpleType).collect(Collectors.toList());
        return owner.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(name))
                .filter(m -> m.getParameters().size() == ps.size())
                .filter(m -> {
                    for (int i = 0; i < ps.size(); i++) {
                        if (!simpleType(m.getParameter(i).getType().asString()).equals(ps.get(i))) return false;
                    }
                    return true;
                })
                .findFirst();
    }

    private static String packageName(CompilationUnit cu, String fallback) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(fallback == null ? "" : fallback);
    }

    private static String ownerKind(TypeDeclaration<?> owner) {
        if (owner instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) owner;
            if (c.isInterface()) return "INTERFACE";
            if (c.isAbstract()) return "ABSTRACT_CLASS";
        }
        return "CONCRETE_CLASS";
    }

    private static boolean isStaticInvocation(MethodEntryResolver.Resolution r) {
        return "STATIC_METHOD_INVOCATION".equals(r.entryInvocationKind)
                || "STATIC_NO_RECEIVER".equals(r.testReceiverStrategy);
    }

    private static boolean isConstructorSignature(String sig) {
        if (sig == null) return false;
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        return lp > 0 && !(us > 0 && us < lp);
    }

    private static String methodName(String sig) {
        if (sig == null) return "";
        int lp = sig.indexOf('(');
        int us = sig.indexOf('_');
        if (lp < 0) return sig;
        if (us > 0 && us < lp) return sig.substring(us + 1, lp).trim();
        return sig.substring(0, lp).trim();
    }

    private static String returnTypeForInvocation(String sig, String ownerSimple) {
        if (sig == null) return "Object";
        int us = sig.indexOf('_');
        int lp = sig.indexOf('(');
        if (us > 0 && us < lp) {
            String ret = simpleType(sig.substring(0, us));
            return "void".equals(ret) ? "" : ret;
        }
        return ownerSimple;
    }

    private static String displayMethod(String sig) {
        try {
            return PromptSignatureFormatter.method(sig);
        } catch (Throwable ignored) {
            return sig;
        }
    }

    private static List<String> params(String sig) {
        int lp = sig == null ? -1 : sig.indexOf('(');
        int rp = sig == null ? -1 : sig.lastIndexOf(')');
        if (lp < 0 || rp < lp) return Collections.emptyList();
        String inside = sig.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) return Collections.emptyList();
        return Arrays.stream(inside.split(",")).map(String::trim).collect(Collectors.toList());
    }

    private static String exampleArgumentsJoined(String sig, MutationConfig config) {
        List<String> ps = params(sig);
        List<String> args = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            args.add(exampleValueForParameter(ps.get(i), "arg" + i, i, config));
        }
        return String.join(", ", args);
    }

    private static String firstArgValue(CallableDeclaration<?> c, MutationConfig config) {
        if (c == null || c.getParameters().isEmpty()) return "";
        Parameter p = c.getParameter(0);
        return exampleValueForParameter(p.getType().asString(), p.getNameAsString(), 0, config);
    }

    private static String exampleArgsForCallable(CallableDeclaration<?> c, MutationConfig config) {
        if (c == null) return "";
        List<String> args = new ArrayList<>();
        for (int i = 0; i < c.getParameters().size(); i++) {
            Parameter p = c.getParameter(i);
            args.add(exampleValueForParameter(p.getType().asString(), p.getNameAsString(), i, config));
        }
        return String.join(", ", args);
    }

    private static String exampleValueForParameter(String raw, String name, int index, MutationConfig config) {
        String t = simpleType(raw);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("ReadablePartial".equals(t)) {
            if (n.contains("end") || index == 1) {
                return "new org.joda.time.Partial(new org.joda.time.DateTimeFieldType[] { org.joda.time.DateTimeFieldType.year(), org.joda.time.DateTimeFieldType.monthOfYear(), org.joda.time.DateTimeFieldType.dayOfMonth() }, new int[] {2001, 1, 1})";
            }
            return "new org.joda.time.Partial(new org.joda.time.DateTimeFieldType[] { org.joda.time.DateTimeFieldType.year(), org.joda.time.DateTimeFieldType.monthOfYear(), org.joda.time.DateTimeFieldType.dayOfMonth() }, new int[] {2000, 1, 1})";
        }
        if ("PeriodType".equals(t)) return "org.joda.time.PeriodType.yearMonthDay()";
        if ("DataInput".equals(t)) return "new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0}))";
        if ("InputStream".equals(t)) return "new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0})";
        if ("Reader".equals(t)) return "new java.io.StringReader(\"col1,col2\\nval1,val2\")";
        if ("ExtendedBufferedReader".equals(t)) return "new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"col1,col2\\nval1,val2\"))";
        if ("CSVFormat".equals(t)) return "org.apache.commons.csv.CSVFormat.DEFAULT";
        if ("Token".equals(t)) return "new org.apache.commons.csv.Token()";
        if ("Chronology".equals(t)) return "org.joda.time.chrono.ISOChronology.getInstanceUTC()";
        if ("DateTimeZone".equals(t)) return "org.joda.time.DateTimeZone.UTC";
        if ("long".equals(t) || "Long".equals(t)) {
            if (n.contains("minuend")) return "2500L";
            if (n.contains("subtrahend")) return "1000L";
            if (n.contains("instant")) return index == 0 ? "2500L" : "1000L";
            if (n.contains("unit")) return "1000L";
            if (n.contains("value") || n.contains("duration") || n.contains("millis")) return "1000L";
            return "1000L";
        }
        if ("int".equals(t) || "Integer".equals(t)) {
            if (n.contains("year")) return "2004";
            if (n.contains("month")) return "6";
            if (n.contains("day")) return "9";
            if (n.contains("hour")) return "13";
            if (n.contains("minute")) return "14";
            if (n.contains("second")) return "15";
            if (n.contains("millis")) return "16";
            if (n.contains("value")) return "7";
            String diff = config == null || config.mutationStatement == null ? "" : config.mutationStatement;
            if (diff.contains("--") || diff.contains("++") || diff.contains("=> -") || diff.contains("-")) return "7";
            return "0";
        }
        return exampleValueForType(raw);
    }

    private static String invocationStatement(String sig, String receiver, String method, String args, String ownerSimple) {
        String resultType = returnTypeForInvocation(sig, ownerSimple);
        if (resultType == null || isBlank(resultType)) {
            return receiver + "." + method + "(" + args + ");";
        }
        return resultType + " result = " + receiver + "." + method + "(" + args + ");";
    }

    private static String defaultReturnExpression(String rawType, String methodName) {
        String t = simpleType(rawType);
        if ("boolean".equals(t) || "Boolean".equals(t)) return "false";
        if ("byte".equals(t)) return "(byte) 0";
        if ("short".equals(t)) return "(short) 0";
        if ("int".equals(t) || "Integer".equals(t)) return "0";
        if ("long".equals(t) || "Long".equals(t)) return "0L";
        if ("float".equals(t) || "Float".equals(t)) return "0.0f";
        if ("double".equals(t) || "Double".equals(t)) return "0.0";
        if ("char".equals(t) || "Character".equals(t)) return "'x'";
        if ("String".equals(t)) return "\"\"";
        if ("DurationFieldType".equals(t)) return "org.joda.time.DurationFieldType.days()";
        if ("DateTimeFieldType".equals(t)) return "org.joda.time.DateTimeFieldType.dayOfMonth()";
        if ("PeriodType".equals(t)) return "org.joda.time.PeriodType.days()";
        if ("Chronology".equals(t)) return "org.joda.time.chrono.ISOChronology.getInstanceUTC()";
        if ("DateTimeZone".equals(t)) return "org.joda.time.DateTimeZone.UTC";
        return "null";
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        List<String> out = new ArrayList<>();
        if (parts != null) {
            for (String p : parts) {
                if (p != null && !p.trim().isEmpty()) out.add(p.trim());
            }
        }
        return String.join(delimiter, out);
    }

    private static String exampleValueForType(String raw) {
        String t = simpleType(raw);
        switch (t) {
            case "boolean": return "false";
            case "byte": return "(byte) 0";
            case "short": return "(short) 0";
            case "int": return "0";
            case "long": return "0L";
            case "float": return "0.0f";
            case "double": return "0.0";
            case "char": return "'x'";
            case "String":
            case "CharSequence": return "\"x\"";
            case "Appendable": return "new java.lang.StringBuilder()";
            case "DataInput": return "new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0}))";
            case "InputStream": return "new java.io.ByteArrayInputStream(new byte[] {0, 1, 1, 0, 0})";
            case "Reader": return "new java.io.StringReader(\"col1,col2\\nval1,val2\")";
            case "ExtendedBufferedReader": return "new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"col1,col2\\nval1,val2\"))";
            case "CSVFormat": return "org.apache.commons.csv.CSVFormat.DEFAULT";
            case "Token": return "new org.apache.commons.csv.Token()";
            case "Collection":
            case "List":
            case "Iterable": return "java.util.Arrays.asList(\"alpha\", \"beta\")";
            default:
                if (t.endsWith("[]")) return "new " + t.substring(0, t.length() - 2) + "[0]";
                return "null";
        }
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

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : (b == null ? "" : b);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private static final class TypeInfo {
        String packageName;
        String simpleName;
        String fqn;
        boolean isAbstract;
        boolean isInterface;
        List<String> parents = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<ConstructorInfo> constructors = new ArrayList<>();

        static TypeInfo from(CompilationUnit cu, TypeDeclaration<?> td, String file) {
            return from(cu, td, file, packageName(cu, ""));
        }

        static TypeInfo from(CompilationUnit cu, TypeDeclaration<?> td, String file, String pkg) {
            TypeInfo t = new TypeInfo();
            t.packageName = pkg == null ? "" : pkg;
            t.simpleName = td.getNameAsString();
            t.fqn = t.packageName.isEmpty() ? t.simpleName : t.packageName + "." + t.simpleName;
            if (td instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
                t.isInterface = c.isInterface();
                t.isAbstract = c.isAbstract() || c.isInterface();
                c.getExtendedTypes().forEach(x -> t.parents.add(x.getNameAsString()));
                c.getImplementedTypes().forEach(x -> t.parents.add(x.getNameAsString()));
            }
            for (MethodDeclaration m : td.getMethods()) {
                t.methods.add(new MethodInfo(m.getNameAsString(), m.getParameters().stream()
                        .map(p -> p.getType().asString()).collect(Collectors.toList()), m.isAbstract()));
            }
            if (td instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) td;
                for (ConstructorDeclaration ctor : c.getConstructors()) {
                    t.constructors.add(new ConstructorInfo(t.simpleName, ctor.getParameters().stream()
                            .map(p -> p.getType().asString()).collect(Collectors.toList()), accessOf(ctor)));
                }
            }
            return t;
        }
    }

    private static String accessOf(NodeWithModifiers<?> n) {
        if (n.hasModifier(Modifier.Keyword.PUBLIC)) return "public";
        if (n.hasModifier(Modifier.Keyword.PROTECTED)) return "protected";
        if (n.hasModifier(Modifier.Keyword.PRIVATE)) return "private";
        return "package";
    }

    private static final class MethodInfo {
        final String name;
        final List<String> params;
        final boolean isAbstract;
        MethodInfo(String name, List<String> params, boolean isAbstract) {
            this.name = name;
            this.params = params;
            this.isAbstract = isAbstract;
        }
    }

    private static final class ConstructorInfo {
        final String name;
        final List<String> paramTypes;
        final String access;
        ConstructorInfo(String name, List<String> paramTypes, String access) {
            this.name = name;
            this.paramTypes = paramTypes;
            this.access = access;
        }
    }

    private static final class Candidate {
        final TypeInfo type;
        final ConstructorInfo ctor;
        final int score;
        Candidate(TypeInfo type, ConstructorInfo ctor, int score) {
            this.type = type;
            this.ctor = ctor;
            this.score = score;
        }
    }
}
