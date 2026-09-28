package org.rip;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.codekb.config.KbConfig;
import org.codekb.model.MutantContextView;
import org.codekb.query.KnowledgeQueryService;
import org.model.MutationConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

public final class CodeKbEvidenceAdapter {
    private CodeKbEvidenceAdapter() {
    }

    public static Map<String, Object> loadContext(MutationConfig config) {
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        try {
            String mutantId = inferMutantId(config);
            if (mutantId.isEmpty()) {
                fallback.put("enabled", false);
                fallback.put("reason", "Unable to infer mutant id from MutationConfig");
                return fallback;
            }
            Path workspaceRoot = resolveWorkspaceRoot();
            KbConfig kbConfig = KbConfig.defaults(workspaceRoot);
            MutantContextView view = new KnowledgeQueryService().loadMutantContext(kbConfig, mutantId);
            if (view == null) {
                fallback.put("enabled", false);
                fallback.put("reason", "Mutant not found in CodeKB: " + mutantId);
                return fallback;
            }
            return toMap(view, workspaceRoot);
        } catch (Throwable t) {
            fallback.put("enabled", false);
            fallback.put("reason", "CodeKB lookup failed: " + safe(t.getMessage()));
            return fallback;
        }
    }

    private static Map<String, Object> toMap(MutantContextView view, Path workspaceRoot) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", true);
        out.put("workspaceRoot", workspaceRoot.toString());
        out.put("mutantId", view.getMutant().getMutantId());
        out.put("project", view.getMutant().getProject());
        out.put("operator", view.getMutant().getOperator());
        out.put("targetClass", view.getMutant().getClassName());
        out.put("targetMethod", view.getMutant().getMethodName());
        out.put("targetLine", view.getMutant().getLineNumber());
        out.put("mutationStatement", view.getMutant().getMutationStatement());
        out.put("originalFile", view.getOriginalFile() == null ? "" : view.getOriginalFile().getPath());
        out.put("mutantFile", view.getMutantFile() == null ? "" : view.getMutantFile().getPath());
        out.put("originalMethod", methodMap(view.getOriginalMethod()));
        out.put("mutantMethod", methodMap(view.getMutantMethod()));
        out.put("candidateEntries", methodList(view.getCandidateEntries()));
        out.put("observableCandidates", observableList(view.getObservableCandidates()));
        out.put("fieldObserverLinks", fieldObserverList(view.getFieldObserverLinks()));
        out.put("mutantWitnessCandidates", witnessList(view.getWitnessCandidates()));
        out.put("candidateImports", candidateImportList(invokeList(view, "getCandidateImports")));
        out.put("mutantMethodCalls", callList(view.getMutantMethodCalls()));
        out.put("mutantFieldAccesses", fieldList(view.getMutantFieldAccesses()));
        out.put("affectedFields", fieldFactList(view.getAffectedFields()));
        out.put("fieldPropagationCandidates", fieldPropagationList(view.getFieldPropagationCandidates()));
        Map<String, Object> compilableFacts = compilableApiFacts(view);
        out.put("compilableApiFacts", compilableFacts);
        out.put("apiRoleFacts", apiRoleFacts(view, compilableFacts));
        return out;
    }

    private static List<Map<String, Object>> observableList(List<MutantContextView.ObservableCandidateView> views) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (views == null) {
            return out;
        }
        for (MutantContextView.ObservableCandidateView view : views) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("methodId", view.getMethodId());
            item.put("methodSignature", safe(view.getMethodSignature()));
            item.put("observableKind", safe(view.getObservableKind()));
            item.put("expression", safe(view.getExpression()));
            item.put("priority", view.getPriority());
            item.put("primary", view.isPrimary());
            item.put("reason", safe(view.getReason()));
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> fieldObserverList(List<MutantContextView.FieldObserverLinkView> views) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (views == null) {
            return out;
        }
        for (MutantContextView.FieldObserverLinkView view : views) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("fieldId", view.getFieldId());
            item.put("fieldName", safe(view.getFieldName()));
            item.put("observerMethodId", view.getObserverMethodId());
            item.put("observerMethodSignature", safe(view.getObserverMethodSignature()));
            item.put("observerKind", safe(view.getObserverKind()));
            item.put("distance", view.getDistance());
            item.put("priority", view.getPriority());
            item.put("reason", safe(view.getReason()));
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> witnessList(List<MutantContextView.WitnessCandidateView> views) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (views == null) {
            return out;
        }
        for (MutantContextView.WitnessCandidateView view : views) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("entryMethodId", view.getEntryMethodId());
            item.put("entryMethodSignature", safe(view.getEntryMethodSignature()));
            item.put("observableMethodId", view.getObservableMethodId());
            item.put("observableMethodSignature", safe(view.getObservableMethodSignature()));
            item.put("witnessRank", view.getWitnessRank());
            item.put("receiverSetup", firstNonBlank(normalizeJsonText(view.getReceiverSetupJson()),
                    genericReceiverSetup(view)));
            item.put("argumentSetup", firstNonBlank(normalizeJsonText(view.getArgumentSetupJson()),
                    genericArgumentSetup(view)));
            item.put("predicateChain", safe(view.getPredicateChainJson()));
            item.put("expectedOriginalOutcome", safe(view.getExpectedOriginalOutcome()));
            item.put("expectedMutantOutcome", safe(view.getExpectedMutantOutcome()));
            item.put("outcomeKind", safe(view.getOutcomeKind()));
            item.put("assertionSketch", normalizeAssertionSketch(view));
            item.put("reason", safe(view.getReason()));
            out.add(item);
        }
        return out;
    }

    private static String normalizeAssertionSketch(MutantContextView.WitnessCandidateView view) {
        String sketch = safe(view == null ? "" : view.getAssertionSketch()).trim();
        String outcome = safe(view == null ? "" : view.getOutcomeKind());
        if ("RETURN_VALUE".equalsIgnoreCase(outcome)
                && sketch.startsWith("assertNotEquals")
                && sketch.contains("originalExpected")) {
            return sketch.replaceFirst("assertNotEquals", "assertEquals")
                    + " // executable assertion targets expectedOriginal; expectedMutant is explanatory evidence.";
        }
        if (sketch.contains(" != ") && ("RETURN_VALUE".equalsIgnoreCase(outcome)
                || "ENTRY_RETURN_VALUE".equalsIgnoreCase(outcome))) {
            return "assertEquals(/* expectedOriginal */, /* actual */); // compare returned values by equality, not reference inequality.";
        }
        return sketch;
    }

    private static String genericReceiverSetup(MutantContextView.WitnessCandidateView view) {
        List<String> steps = new ArrayList<String>();
        steps.add("Construct a live receiver for " + safe(view.getEntryMethodSignature())
                + " using the selected entry chain.");
        steps.add("Shape receiver state so the witness path predicates can hold before invocation.");
        if (!safe(view.getReason()).isEmpty()) {
            steps.add("Keep the setup consistent with the witness reason: " + safe(view.getReason()) + ".");
        }
        return toJsonArray(steps);
    }

    private static String genericArgumentSetup(MutantContextView.WitnessCandidateView view) {
        List<String> steps = new ArrayList<String>();
        List<String> parameters = parseParameterTypes(view.getEntryMethodSignature());
        if (parameters.isEmpty()) {
            steps.add("No call arguments are required for " + safe(view.getEntryMethodSignature()) + ".");
        } else {
            for (int i = 0; i < parameters.size(); i++) {
                steps.add(parameters.get(i) + " arg" + (i + 1)
                        + " = /* type-compatible value that preserves the witness constraints */;");
            }
            steps.add("Keep the arguments type-compatible and avoid early guards that bypass the witness.");
        }
        if (!safe(view.getOutcomeKind()).isEmpty()) {
            steps.add("The observable should be the " + safe(view.getOutcomeKind()).toLowerCase(Locale.ROOT)
                    + " sink at " + safe(view.getObservableMethodSignature()) + ".");
        }
        return toJsonArray(steps);
    }

    private static List<String> parseParameterTypes(String signature) {
        List<String> out = new ArrayList<String>();
        String sig = safe(signature);
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close < open) {
            return out;
        }
        String inside = sig.substring(open + 1, close).trim();
        if (inside.isEmpty()) {
            return out;
        }
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == ',' && depth == 0) {
                String value = current.toString().trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
                current.setLength(0);
                continue;
            }
            if (ch == '<' || ch == '(' || ch == '[') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']') {
                if (depth > 0) {
                    depth--;
                }
            }
            current.append(ch);
        }
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            out.add(value);
        }
        return out;
    }

    private static String normalizeJsonText(String value) {
        String text = safe(value).trim();
        if (text.isEmpty() || "[]".equals(text)) {
            return "";
        }
        return text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJson(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, Object> apiRoleFacts(MutantContextView view, Map<String, Object> compilableFacts) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (view == null) {
            return out;
        }
        Map<String, Object> constructionFacts = new LinkedHashMap<String, Object>();
        Map<String, Object> executionFacts = new LinkedHashMap<String, Object>();
        Map<String, Object> observationFacts = new LinkedHashMap<String, Object>();

        Map<String, Object> receiver = new LinkedHashMap<String, Object>();
        receiver.put("type", safe(view.getMutant().getClassName()));
        receiver.put("instantiableDirectly", isDirectlyInstantiable(compilableFacts));
        receiver.put("preferredStrategies", preferredConstructionStrategies(compilableFacts));
        receiver.put("allowedFactories", allowedFactoryDescriptors(compilableFacts, "RECEIVER_FACTORY"));
        receiver.put("forbiddenDirectConstruction", stringList(compilableFacts.get("forbiddenCalls")));
        constructionFacts.put("receiver", receiver);

        List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
        MutantContextView.MethodLink mutationMethod = view.getMutantMethod();
        if (mutationMethod != null) {
            for (ParameterSpec parameter : parameterSpecs(mutationMethod.getSignature())) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("name", parameter.name);
                item.put("type", parameter.type);
                item.put("instantiableDirectly", isSimpleDirectType(parameter.type, compilableFacts));
                item.put("preferredStrategies", preferredStrategiesForParameter(parameter.type, compilableFacts));
                item.put("allowedFactories", allowedFactoriesForParameter(parameter.type, compilableFacts));
                parameters.add(item);
            }
        }
        constructionFacts.put("parameters", parameters);

        executionFacts.put("mutationMethod", safe(mutationMethod == null ? "" : mutationMethod.getSignature()));
        Map<String, Object> callableEntry = methodMap(view.getMutantMethod());
        callableEntry.put("role", "PRIMARY_EXECUTION_ENTRY");
        callableEntry.put("sameAsMutationMethod", true);
        callableEntry.put("requiresReceiver", mutationMethod != null && !mutationMethod.isStatic());
        executionFacts.put("callableEntry", callableEntry);
        executionFacts.put("argumentBindings", argumentBindings(mutationMethod));
        executionFacts.put("pathConstraints", pathConstraints(view));
        executionFacts.put("distinguishingInputShapes", distinguishingInputShapes(view));

        Map<String, Object> primaryOracle = new LinkedHashMap<String, Object>();
        MutantContextView.ObservableCandidateView bestObservable = firstValidObservable(view.getObservableCandidates(), view);
        if (bestObservable == null) {
            primaryOracle.put("kind", "NO_OBSERVABLE_PROVEN");
            primaryOracle.put("expression", "");
            primaryOracle.put("priority", 0);
        } else {
            primaryOracle.put("kind", safe(bestObservable.getObservableKind()));
            if ("RETURN_VALUE".equalsIgnoreCase(safe(bestObservable.getObservableKind()))) {
                primaryOracle.put("expression", entryReturnOracle(view));
            } else {
                primaryOracle.put("expression", safe(bestObservable.getExpression()));
            }
            primaryOracle.put("priority", bestObservable.getPriority());
        }
        observationFacts.put("primaryOracle", primaryOracle);
        observationFacts.put("secondaryObservables", secondaryObservables(view));
        observationFacts.put("antiPatterns", antiPatterns(compilableFacts));

        out.put("constructionFacts", constructionFacts);
        out.put("executionFacts", executionFacts);
        out.put("observationFacts", observationFacts);
        return out;
    }

    private static Map<String, Object> compilableApiFacts(MutantContextView view) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (view == null) {
            return out;
        }
        if (populateCompilableFactsFromKb(view, out)) {
            return out;
        }
        Path sourcePath = firstExistingPath(
                view.getOriginalFile() == null ? "" : view.getOriginalFile().getPath(),
                view.getMutantFile() == null ? "" : view.getMutantFile().getPath());
        if (sourcePath == null) {
            return out;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourcePath, StandardCharsets.UTF_8);
            String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
            String ownerSimpleName = simpleTypeName(view.getMutant().getClassName());
            TypeDeclaration<?> owner = null;
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                if (ownerSimpleName.equals(td.getNameAsString())) {
                    owner = td;
                    break;
                }
            }
            if (owner == null) {
                return out;
            }
            out.put("sourceFile", sourcePath.toString());
            out.put("packageName", packageName);
            out.put("ownerSimpleName", ownerSimpleName);
            out.put("privateFieldNames", privateFieldNames(owner));
            out.put("enumConstants", enumConstants(cu));
            out.put("constructorSignatures", constructorSignatures(owner));
            out.put("constructorExamples", constructorExamples(owner, cu, packageName));
            out.put("publicMethodNames", publicMethodNames(owner));
            out.put("ownerKind", ownerKind(owner));
            out.put("ownerVisibility", ownerVisibility(owner));
            out.put("ownerInstantiable", ownerInstantiable(owner));
            out.put("allowedConstructors", allowedConstructors(owner));
            out.put("allowedStaticFactories", allowedStaticFactories(owner));
            out.put("allowedInstanceMethods", allowedInstanceMethods(owner));
            out.put("allowedStaticMethods", allowedStaticMethods(owner));
            out.put("exactCallableSignatures", exactCallableSignatures(owner));
            out.put("forbiddenCalls", forbiddenCalls(owner));
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private static boolean populateCompilableFactsFromKb(MutantContextView view, Map<String, Object> out) {
        MutantContextView.TypeView ownerType = view.getOwnerType();
        if (ownerType == null) {
            return false;
        }
        out.put("packageName", view.getOriginalFile() == null ? "" : safe(view.getOriginalFile().getPackageName()));
        out.put("ownerSimpleName", safe(ownerType.getSimpleName()));
        out.put("ownerKind", safe(ownerType.getKind()));
        out.put("ownerVisibility", safe(ownerType.getVisibility()));
        out.put("ownerInstantiable", isOwnerInstantiable(ownerType));
        out.put("allowedConstructors", allowedConstructorsFromKb(view));
        out.put("allowedStaticFactories", allowedStaticFactoriesFromKb(view, ownerType));
        out.put("allowedStaticMethods", allowedStaticMethodsFromKb(view));
        out.put("allowedInstanceMethods", allowedInstanceMethodsFromKb(view));
        out.put("exactCallableSignatures", exactCallableSignaturesFromKb(view, ownerType));
        out.put("forbiddenCalls", forbiddenCallsFromKb(view, ownerType));
        out.put("privateFieldNames", privateFieldNamesFromKb(view));
        out.put("constructorSignatures", constructorSignaturesFromKb(view));
        out.put("publicMethodNames", publicMethodNamesFromKb(view));
        return true;
    }

    private static boolean isOwnerInstantiable(MutantContextView.TypeView ownerType) {
        if (ownerType == null) {
            return false;
        }
        return !"interface".equalsIgnoreCase(ownerType.getKind()) && !ownerType.isAbstract();
    }

    private static List<String> allowedConstructorsFromKb(MutantContextView view) {
        List<String> out = new ArrayList<String>();
        String ownerSimpleName = view.getOwnerType() == null ? "" : safe(view.getOwnerType().getSimpleName());
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (method.isConstructor() && isDirectlyCallable(method.getVisibility())) {
                out.add(ownerSimpleName + "(" + parameterListFromSignature(method.getSignature()) + ")");
            }
        }
        return out;
    }

    private static List<String> constructorSignaturesFromKb(MutantContextView view) {
        List<String> out = new ArrayList<String>();
        String ownerSimpleName = view.getOwnerType() == null ? "" : safe(view.getOwnerType().getSimpleName());
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (method.isConstructor()) {
                out.add(ownerSimpleName + "(" + parameterListFromSignature(method.getSignature()) + ")");
            }
        }
        return out;
    }

    private static List<String> publicMethodNamesFromKb(MutantContextView view) {
        List<String> out = new ArrayList<String>();
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (method.isConstructor() || !"public".equalsIgnoreCase(method.getVisibility())) {
                continue;
            }
            out.add(method.getName() + "(" + parameterListFromSignature(method.getSignature()) + ")");
        }
        return out;
    }

    private static List<String> privateFieldNamesFromKb(MutantContextView view) {
        List<String> out = new ArrayList<String>();
        for (MutantContextView.FieldFactView field : view.getOwnerFields()) {
            if ("private".equalsIgnoreCase(field.getVisibility())) {
                out.add(field.getName());
            }
        }
        return out;
    }

    private static List<String> allowedStaticFactoriesFromKb(MutantContextView view, MutantContextView.TypeView ownerType) {
        List<String> out = new ArrayList<String>();
        String ownerSimpleName = ownerType == null ? "" : safe(ownerType.getSimpleName());
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (!method.isStatic() || method.isConstructor() || !isDirectlyCallable(method.getVisibility())) {
                continue;
            }
            if (!ownerSimpleName.equals(simpleTypeName(method.getReturnType()))) {
                continue;
            }
            out.add(formatMethodFact(method));
        }
        return out;
    }

    private static List<String> allowedStaticMethodsFromKb(MutantContextView view) {
        List<String> out = new ArrayList<String>();
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (method.isStatic() && !method.isConstructor() && isDirectlyCallable(method.getVisibility())) {
                out.add(formatMethodFact(method));
            }
        }
        return out;
    }

    private static List<String> allowedInstanceMethodsFromKb(MutantContextView view) {
        List<String> out = new ArrayList<String>();
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (!method.isStatic() && !method.isConstructor() && isDirectlyCallable(method.getVisibility())) {
                out.add(formatMethodFact(method));
            }
        }
        return out;
    }

    private static List<String> exactCallableSignaturesFromKb(MutantContextView view, MutantContextView.TypeView ownerType) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        out.addAll(allowedConstructorsFromKb(view));
        out.addAll(allowedStaticMethodsFromKb(view));
        out.addAll(allowedInstanceMethodsFromKb(view));
        return new ArrayList<String>(out);
    }

    private static List<String> forbiddenCallsFromKb(MutantContextView view, MutantContextView.TypeView ownerType) {
        List<String> out = new ArrayList<String>();
        String ownerSimpleName = ownerType == null ? "" : safe(ownerType.getSimpleName());
        for (MutantContextView.MethodFactView method : view.getOwnerMethods()) {
            if (method.isConstructor()) {
                if (!isDirectlyCallable(method.getVisibility())) {
                    out.add(ownerSimpleName + "(" + parameterListFromSignature(method.getSignature()) + ")");
                }
            } else if (!isDirectlyCallable(method.getVisibility())) {
                out.add(formatMethodFact(method));
            }
        }
        return out;
    }

    private static boolean isDirectlyCallable(String visibility) {
        return !"private".equalsIgnoreCase(safe(visibility));
    }

    private static String formatMethodFact(MutantContextView.MethodFactView method) {
        String prefix = method.isStatic() ? "static " : "";
        return prefix + safe(method.getReturnType()) + " " + method.getName()
                + "(" + parameterListFromSignature(method.getSignature()) + ")";
    }

    private static String parameterListFromSignature(String signature) {
        String text = safe(signature);
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open) {
            return "";
        }
        return text.substring(open + 1, close).trim();
    }

    private static List<String> privateFieldNames(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        for (FieldDeclaration field : ((ClassOrInterfaceDeclaration) owner).getFields()) {
            if (!field.isPrivate()) {
                continue;
            }
            field.getVariables().forEach(v -> out.add(v.getNameAsString()));
        }
        return out;
    }

    private static Map<String, Object> enumConstants(CompilationUnit cu) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
            List<String> values = new ArrayList<String>();
            for (EnumConstantDeclaration entry : decl.getEntries()) {
                values.add(entry.getNameAsString());
            }
            out.put(decl.getNameAsString(), values);
        }
        return out;
    }

    private static List<String> constructorSignatures(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        for (ConstructorDeclaration ctor : ((ClassOrInterfaceDeclaration) owner).getConstructors()) {
            out.add(owner.getNameAsString() + "(" + joinParameterTypes(ctor.getParameters()) + ")");
        }
        return out;
    }

    private static List<String> constructorExamples(TypeDeclaration<?> owner,
                                                    CompilationUnit cu,
                                                    String packageName) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        String ownerName = owner.getNameAsString();
        String ownerPrefix = packageName == null || packageName.isEmpty()
                ? ownerName
                : ownerName;
        for (ConstructorDeclaration ctor : ((ClassOrInterfaceDeclaration) owner).getConstructors()) {
            List<String> args = new ArrayList<String>();
            boolean supported = true;
            for (Parameter parameter : ctor.getParameters()) {
                String example = exampleValueFor(parameter.getType().asString(), cu, ownerName);
                if (example.isEmpty()) {
                    supported = false;
                    break;
                }
                args.add(example);
            }
            if (supported) {
                out.add("new " + ownerPrefix + "(" + String.join(", ", args) + ")");
            }
        }
        return out;
    }

    private static List<String> publicMethodNames(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        ((ClassOrInterfaceDeclaration) owner).getMethods().stream()
                .filter(m -> m.isPublic())
                .forEach(m -> out.add(m.getNameAsString() + "()"));
        return out;
    }

    private static String ownerKind(TypeDeclaration<?> owner) {
        if (owner instanceof EnumDeclaration) {
            return "enum";
        }
        if (owner instanceof ClassOrInterfaceDeclaration) {
            return ((ClassOrInterfaceDeclaration) owner).isInterface() ? "interface" : "class";
        }
        return "type";
    }

    private static String ownerVisibility(TypeDeclaration<?> owner) {
        if (owner == null) {
            return "";
        }
        if (owner.isPublic()) {
            return "public";
        }
        if (owner.isProtected()) {
            return "protected";
        }
        if (owner.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private static boolean ownerInstantiable(TypeDeclaration<?> owner) {
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }
        ClassOrInterfaceDeclaration decl = (ClassOrInterfaceDeclaration) owner;
        return !decl.isInterface() && !decl.isAbstract();
    }

    private static List<String> allowedConstructors(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        for (ConstructorDeclaration ctor : ((ClassOrInterfaceDeclaration) owner).getConstructors()) {
            if (isDirectlyCallable(ctor)) {
                out.add(formatCallableSignature(owner.getNameAsString(), ctor));
            }
        }
        return out;
    }

    private static List<String> allowedStaticFactories(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        for (MethodDeclaration method : ((ClassOrInterfaceDeclaration) owner).getMethods()) {
            if (!method.isStatic() || !isDirectlyCallable(method)) {
                continue;
            }
            if (!sameSimpleType(owner.getNameAsString(), method.getType().asString())) {
                continue;
            }
            out.add(formatCallableSignature(method.getType().asString(), method));
        }
        return out;
    }

    private static List<String> allowedInstanceMethods(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        for (MethodDeclaration method : ((ClassOrInterfaceDeclaration) owner).getMethods()) {
            if (method.isStatic() || !isDirectlyCallable(method)) {
                continue;
            }
            out.add(formatCallableSignature(method.getType().asString(), method));
        }
        return out;
    }

    private static List<String> allowedStaticMethods(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        for (MethodDeclaration method : ((ClassOrInterfaceDeclaration) owner).getMethods()) {
            if (!method.isStatic() || !isDirectlyCallable(method)) {
                continue;
            }
            out.add(formatCallableSignature(method.getType().asString(), method));
        }
        return out;
    }

    private static List<String> exactCallableSignatures(TypeDeclaration<?> owner) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        out.addAll(allowedConstructors(owner));
        out.addAll(allowedStaticMethods(owner));
        out.addAll(allowedInstanceMethods(owner));
        return new ArrayList<String>(out);
    }

    private static List<String> forbiddenCalls(TypeDeclaration<?> owner) {
        List<String> out = new ArrayList<String>();
        if (!(owner instanceof ClassOrInterfaceDeclaration)) {
            return out;
        }
        String ownerName = owner.getNameAsString();
        for (ConstructorDeclaration ctor : ((ClassOrInterfaceDeclaration) owner).getConstructors()) {
            if (!isDirectlyCallable(ctor)) {
                out.add(formatCallableSignature(ownerName, ctor));
            }
        }
        for (MethodDeclaration method : ((ClassOrInterfaceDeclaration) owner).getMethods()) {
            if (!isDirectlyCallable(method)) {
                out.add(formatCallableSignature(method.getType().asString(), method));
            }
        }
        return out;
    }

    private static boolean isDirectlyCallable(ConstructorDeclaration declaration) {
        return declaration != null
                && (declaration.isPublic() || declaration.isProtected() || !declaration.isPrivate());
    }

    private static boolean isDirectlyCallable(MethodDeclaration declaration) {
        return declaration != null
                && (declaration.isPublic() || declaration.isProtected() || !declaration.isPrivate());
    }

    private static boolean sameSimpleType(String left, String right) {
        return simpleTypeName(left).equals(simpleTypeName(right));
    }

    private static String formatCallableSignature(String returnType, MethodDeclaration method) {
        return CallableSignatureFormatter.method(method);
    }

    private static String formatCallableSignature(String ownerName, ConstructorDeclaration ctor) {
        return CallableSignatureFormatter.constructor(ownerName, ctor);
    }

    private static String joinParameterTypes(List<Parameter> parameters) {
        List<String> types = new ArrayList<String>();
        for (Parameter parameter : parameters) {
            types.add(parameter.getType().asString());
        }
        return String.join(", ", types);
    }

    private static String exampleValueFor(String rawType, CompilationUnit cu, String ownerName) {
        String type = simpleTypeName(rawType);
        if ("boolean".equals(type) || "Boolean".equals(type)) return "false";
        if ("byte".equals(type) || "Byte".equals(type)) return "(byte) 0";
        if ("short".equals(type) || "Short".equals(type)) return "(short) 0";
        if ("int".equals(type) || "Integer".equals(type)) return "0";
        if ("long".equals(type) || "Long".equals(type)) return "0L";
        if ("float".equals(type) || "Float".equals(type)) return "0.0f";
        if ("double".equals(type) || "Double".equals(type)) return "0.0";
        if ("char".equals(type) || "Character".equals(type)) return "'a'";
        if ("String".equals(type) || "CharSequence".equals(type)) return "\"\"";
        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
            if (!decl.getNameAsString().equals(type) || decl.getEntries().isEmpty()) {
                continue;
            }
            return ownerName + "." + type + "." + decl.getEntries().get(0).getNameAsString();
        }
        return "";
    }

    private static String simpleTypeName(String rawType) {
        if (rawType == null) {
            return "";
        }
        String text = rawType.trim();
        int generic = text.indexOf('<');
        if (generic >= 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    private static Path firstExistingPath(String... paths) {
        if (paths == null) {
            return null;
        }
        for (String raw : paths) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                Path p = Paths.get(raw).toAbsolutePath().normalize();
                if (Files.exists(p)) {
                    return p;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Map<String, Object> methodMap(MutantContextView.MethodLink method) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (method == null) {
            return out;
        }
        out.put("typeName", method.getTypeName());
        out.put("methodName", method.getMethodName());
        out.put("signature", method.getSignature());
        out.put("returnType", method.getReturnType());
        out.put("static", method.isStatic());
        out.put("public", method.isPublic());
        out.put("constructor", method.isConstructor());
        out.put("beginLine", method.getBeginLine());
        out.put("endLine", method.getEndLine());
        out.put("score", method.getScore());
        out.put("reason", method.getReason());
        return out;
    }

    private static List<Map<String, Object>> methodList(List<MutantContextView.MethodLink> methods) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MutantContextView.MethodLink method : methods) {
            out.add(methodMap(method));
        }
        return out;
    }

    private static List<Map<String, Object>> callList(List<MutantContextView.MethodCallView> calls) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MutantContextView.MethodCallView call : calls) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("owner", call.getOwner());
            item.put("methodName", call.getMethodName());
            item.put("signature", call.getSignature());
            item.put("line", call.getLineNumber());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> candidateImportList(List<?> imports) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (imports == null) {
            return out;
        }
        for (Object candidateImport : imports) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("importValue", invokeString(candidateImport, "getImportValue"));
            item.put("sourceKind", invokeString(candidateImport, "getSourceKind"));
            item.put("usageContext", invokeString(candidateImport, "getUsageContext"));
            item.put("priority", invokeInt(candidateImport, "getPriority"));
            item.put("scope", invokeString(candidateImport, "getScope"));
            item.put("ownerMethodSignature", invokeString(candidateImport, "getOwnerMethodSignature"));
            item.put("knowledgeSource", invokeString(candidateImport, "getKnowledgeSource", "BASE_KB"));
            out.add(item);
        }
        return out;
    }

    private static List<?> invokeList(Object target, String methodName) {
        Object value = invoke(target, methodName);
        if (value instanceof List<?>) {
            return (List<?>) value;
        }
        return new ArrayList<Object>();
    }

    private static String invokeString(Object target, String methodName) {
        return invokeString(target, methodName, "");
    }

    private static String invokeString(Object target, String methodName, String fallback) {
        Object value = invoke(target, methodName);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static int invokeInt(Object target, String methodName) {
        Object value = invoke(target, methodName);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isEmpty()) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> fieldList(List<MutantContextView.FieldAccessView> accesses) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MutantContextView.FieldAccessView access : accesses) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("ownerType", access.getOwnerType());
            item.put("fieldName", access.getFieldName());
            item.put("accessKind", access.getAccessKind());
            item.put("line", access.getLineNumber());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> fieldFactList(List<MutantContextView.FieldFactView> fields) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (fields == null) {
            return out;
        }
        for (MutantContextView.FieldFactView field : fields) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("fieldId", field.getFieldId());
            item.put("name", field.getName());
            item.put("fieldType", field.getFieldType());
            item.put("visibility", field.getVisibility());
            item.put("static", field.isStatic());
            item.put("final", field.isFinal());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> fieldPropagationList(List<MutantContextView.FieldPropagationView> candidates) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (candidates == null) {
            return out;
        }
        for (MutantContextView.FieldPropagationView candidate : candidates) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("field", fieldFactMap(candidate.getField()));
            item.put("readerMethod", methodMap(candidate.getReaderMethod()));
            item.put("accessKind", candidate.getAccessKind());
            item.put("line", candidate.getLineNumber());
            item.put("score", candidate.getScore());
            item.put("reason", candidate.getReason());
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> fieldFactMap(MutantContextView.FieldFactView field) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (field == null) {
            return out;
        }
        out.put("fieldId", field.getFieldId());
        out.put("name", field.getName());
        out.put("fieldType", field.getFieldType());
        out.put("visibility", field.getVisibility());
        out.put("static", field.isStatic());
        out.put("final", field.isFinal());
        return out;
    }

    private static String inferMutantId(MutationConfig config) {
        String canonical = CanonicalMutantId.build(config);
        if (!canonical.isEmpty()) {
            return canonical;
        }
        return config == null ? "" : CanonicalMutantId.legacyMutantName(config.filepath);
    }

    private static Path resolveWorkspaceRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path p = current; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("CodeKB")) && Files.exists(p.resolve("EvidenceParse")) && Files.exists(p.resolve("MuTestLLM"))) {
                return p;
            }
        }
        return current;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isDirectlyInstantiable(Map<String, Object> facts) {
        if (facts == null) {
            return false;
        }
        Object ownerInstantiable = facts.get("ownerInstantiable");
        if (!(ownerInstantiable instanceof Boolean) || !((Boolean) ownerInstantiable)) {
            return false;
        }
        List<String> allowedConstructors = stringList(facts.get("allowedConstructors"));
        return !allowedConstructors.isEmpty();
    }

    private static List<String> preferredConstructionStrategies(Map<String, Object> facts) {
        List<String> out = new ArrayList<String>();
        if (!stringList(facts.get("allowedStaticFactories")).isEmpty()) {
            out.add("STATIC_FACTORY");
        }
        if (!stringList(facts.get("allowedConstructors")).isEmpty()) {
            out.add("DIRECT_CONSTRUCTOR");
        }
        if (out.isEmpty()) {
            out.add("REFLECTION_OR_ENTRY_LIFT");
        }
        return out;
    }

    private static List<Map<String, Object>> allowedFactoryDescriptors(Map<String, Object> facts, String usageRole) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String signature : stringList(facts.get("allowedStaticFactories"))) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("signature", signature);
            item.put("returnsReceiverType", true);
            item.put("priority", factoryPriority(signature));
            item.put("usageRole", usageRole);
            out.add(item);
        }
        return out;
    }

    private static int factoryPriority(String signature) {
        String text = safe(signature);
        if (text.contains("(") && text.contains(",")) {
            return 95;
        }
        return 80;
    }

    private static boolean isSimpleDirectType(String type, Map<String, Object> facts) {
        String simple = simpleTypeName(type);
        if (simple.isEmpty()) {
            return false;
        }
        if (simple.matches("boolean|byte|short|int|long|float|double|char")
                || "String".equals(simple)
                || "Boolean".equals(simple)
                || "Byte".equals(simple)
                || "Short".equals(simple)
                || "Integer".equals(simple)
                || "Long".equals(simple)
                || "Float".equals(simple)
                || "Double".equals(simple)
                || "Character".equals(simple)) {
            return true;
        }
        return !sameSimpleType(simple, stringValue(facts.get("ownerSimpleName")))
                && stringList(facts.get("allowedStaticFactories")).isEmpty();
    }

    private static List<String> preferredStrategiesForParameter(String type, Map<String, Object> facts) {
        List<String> out = new ArrayList<String>();
        if (sameSimpleType(type, stringValue(facts.get("ownerSimpleName")))
                && !stringList(facts.get("allowedStaticFactories")).isEmpty()) {
            out.add("STATIC_FACTORY");
        } else if (isSimpleDirectType(type, facts)) {
            out.add("DIRECT_LITERAL_OR_OBJECT");
        } else {
            out.add("ENTRY_PARAMETER_BINDING");
        }
        return out;
    }

    private static List<String> allowedFactoriesForParameter(String type, Map<String, Object> facts) {
        if (!sameSimpleType(type, stringValue(facts.get("ownerSimpleName")))) {
            return Collections.emptyList();
        }
        return stringList(facts.get("allowedStaticFactories"));
    }

    private static List<Map<String, Object>> argumentBindings(MutantContextView.MethodLink mutationMethod) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (mutationMethod == null) {
            return out;
        }
        for (ParameterSpec parameter : parameterSpecs(mutationMethod.getSignature())) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("parameter", parameter.name);
            item.put("bindingRole", "ENTRY_PARAMETER");
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> pathConstraints(MutantContextView view) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MutantContextView.FieldAccessView access : view.getMutantFieldAccesses()) {
            if (!"READ".equalsIgnoreCase(safe(access.getAccessKind()))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("predicate", access.getFieldName());
            item.put("kind", "FIELD_GUARD_HINT");
            out.add(item);
            if (out.size() >= 4) {
                break;
            }
        }
        return out;
    }

    private static List<Map<String, Object>> distinguishingInputShapes(MutantContextView view) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MutantContextView.FieldFactView field : view.getAffectedFields()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("goal", "Control field '" + field.getName() + "' through a boundary-adjacent public construction path.");
            item.put("kind", "AFFECTED_FIELD_BOUNDARY");
            out.add(item);
        }
        if (out.isEmpty() && view.getMutant() != null) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("goal", "Drive inputs that flip the mutated comparison or branch around its equality boundary.");
            item.put("kind", "BOUNDARY_PAIR");
            out.add(item);
        }
        return out;
    }

    private static String entryReturnOracle(MutantContextView view) {
        MutantContextView.MethodLink method = view == null ? null : view.getMutantMethod();
        if (method == null) {
            return "result";
        }
        String call = "subject." + safe(method.getMethodName()) + "(" + invocationArgumentNames(method.getSignature()) + ")";
        return safe(method.getReturnType()) + " result = " + call + ";";
    }

    private static MutantContextView.ObservableCandidateView firstValidObservable(
            List<MutantContextView.ObservableCandidateView> candidates,
            MutantContextView view) {
        if (candidates == null) {
            return null;
        }
        String mutantReturnType = view == null || view.getMutantMethod() == null
                ? ""
                : simpleTypeName(view.getMutantMethod().getReturnType());
        for (MutantContextView.ObservableCandidateView item : candidates) {
            if (item == null) {
                continue;
            }
            String kind = safe(item.getObservableKind()).toUpperCase(Locale.ROOT);
            if ("METHOD_COMPLETION".equals(kind)
                    || "NO_OBSERVABLE_PROVEN".equals(kind)
                    || "NO_PUBLIC_OBSERVABLE".equals(kind)) {
                continue;
            }
            if ("RETURN_VALUE".equals(kind) && "void".equalsIgnoreCase(mutantReturnType)) {
                continue;
            }
            if (!"RETURN_VALUE".equals(kind) && isBlank(safe(item.getExpression()))) {
                continue;
            }
            return item;
        }
        return null;
    }

    private static List<Map<String, Object>> secondaryObservables(MutantContextView view) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (MutantContextView.MethodLink entry : view.getCandidateEntries()) {
            if (safe(entry.getMethodName()).equals(safe(view.getMutantMethod() == null ? "" : view.getMutantMethod().getMethodName()))) {
                continue;
            }
            if (!entry.isPublic()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("kind", "PUBLIC_METHOD_DEPENDS_ON_STATE");
            item.put("expression", secondaryExpression(entry));
            item.put("priority", entry.getScore() >= 75.0 ? 70 : 55);
            item.put("useWhen", "only if the primary entry-return oracle is insufficient");
            out.add(item);
            if (out.size() >= 4) {
                break;
            }
        }
        return out;
    }

    private static String secondaryExpression(MutantContextView.MethodLink method) {
        String invocation = "subject." + safe(method.getMethodName()) + "(" + invocationArgumentNames(method.getSignature()) + ")";
        if ("void".equals(simpleTypeName(method.getReturnType()))) {
            return invocation + ";";
        }
        return safe(method.getReturnType()) + " result = " + invocation + ";";
    }

    private static List<String> antiPatterns(Map<String, Object> facts) {
        List<String> out = new ArrayList<String>();
        out.add("Do not use null-only invocation as the primary test path.");
        out.add("Do not replace exact factory signatures with guessed near-miss signatures.");
        if (!stringList(facts.get("forbiddenCalls")).isEmpty()) {
            out.add("Do not use forbidden direct construction when the source marks it inaccessible.");
        }
        return out;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                String text = safe(String.valueOf(item)).trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : safe(String.valueOf(value));
    }

    private static String invocationArgumentNames(String signature) {
        List<ParameterSpec> specs = parameterSpecs(signature);
        List<String> names = new ArrayList<String>();
        for (ParameterSpec spec : specs) {
            names.add(spec.name);
        }
        return String.join(", ", names);
    }

    private static List<ParameterSpec> parameterSpecs(String signature) {
        List<ParameterSpec> out = new ArrayList<ParameterSpec>();
        String params = parameterListFromSignature(signature);
        if (params.isEmpty()) {
            return out;
        }
        int index = 1;
        for (String raw : params.split("\\s*,\\s*")) {
            String type = safe(raw).trim();
            if (type.isEmpty()) {
                continue;
            }
            out.add(new ParameterSpec(type, parameterNameFromType(type, index++)));
        }
        return out;
    }

    private static String parameterNameFromType(String type, int index) {
        String simple = simpleTypeName(type);
        if (simple.isEmpty()) {
            return "arg" + index;
        }
        String base = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        if ("char".equals(base) || "int".equals(base) || "long".equals(base)
                || "double".equals(base) || "float".equals(base) || "boolean".equals(base)) {
            return base + "Value" + index;
        }
        return base.equals("charRange") ? "range" : base + index;
    }

    private static final class ParameterSpec {
        final String type;
        final String name;

        ParameterSpec(String type, String name) {
            this.type = safe(type);
            this.name = safe(name);
        }
    }
}
