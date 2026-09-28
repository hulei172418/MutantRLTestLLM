package org.rip;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.astjimple.AstToJimpleBridge;
import static org.astjimple.AstToJimpleBridge.analyzeAffectedUnits;
import static org.astjimple.AstToJimpleBridge.analyzeAllPathsThroughAffected;
import static org.astjimple.AstToJimpleBridge.pathToString;
import static org.astjimple.AstToJimpleBridge.unitLineRange;
import org.astjimple.ChangeRange;
import org.astjimple.DiffWithLineRanges;
import org.astjimple.DiffWithLineRanges1;
import static org.astjimple.MethodContent.extractMethodAsOneLine;
import org.astjimple.SourceOwnerResolver;
import org.graph.ASTVisualizer;
import org.graph.CFGVisualizer;
import org.graph.DFGVisualizer;
import org.model.Bundle;
import org.model.CFG;
import org.model.DFG;
import org.model.Info;
import org.model.Info.InfoItem;
import org.model.MutationConfig;
import static org.rip.RipExtractor.computeControlDepsText;
import static org.rip.RipExtractor.locateByText;
import static org.rip.RipExtractor.unitsToBlockSummaries;
import org.utils.DotToImageConverter;
import org.utils.PathSanitizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;

import soot.Body;
import soot.Local;
import soot.Scene;
import soot.SootClass;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.jimple.IfStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.ThrowStmt;
import soot.toolkits.graph.BlockGraph;
import soot.toolkits.graph.BriefBlockGraph;
import soot.toolkits.graph.DominatorsFinder;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.MHGDominatorsFinder;
import soot.toolkits.graph.MHGPostDominatorsFinder;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.SimpleLocalDefs;
import soot.toolkits.scalar.SimpleLocalUses;
import soot.toolkits.scalar.UnitValueBoxPair;

public class RipParser {
    private static final boolean LEGACY_EVIDENCE_ENABLED = Boolean
            .parseBoolean(System.getProperty("llm.evidence.legacy.enabled", "false"));

    // ==== Main input (examples consistent with prompts, can also pass from
    // main) ====
    private String mID = "m6"; // Mutant ID
    private String OID = "p"; // Original ID
    private String Operator = "COR"; // operator
    private String MUTANT_NAME = "COR"; // full mutant name, e.g. ROR_1
    private String PROJECT_NAME = "";
    private String TARGET_CLASS_ID = "";
    private String Diff = "bucket256(sample) == (targetBucket & 0xFF) && sample.getKey() < min && sample.getValue() > 0"; // Differing
                                                                                                                          // statement
    private String SRC_FILE_P = "src/main/java/demo/origin/Bucket.java"; // Source code of p
    private String SRC_FILE_M = "src/main/java/demo/" + mID + "/Bucket.java"; // Source code of m
    private MutationSemanticLocator.MutationSemanticContext MUTATION_SEMANTIC_CONTEXT =
            new MutationSemanticLocator.MutationSemanticContext();

    private String CLASS_NAME_P = "Bucket"; // Fully qualified class name of p (include package if any)
    private String CLASS_NAME_M = "Bucket"; // Fully qualified class name of m (usually same)
    private String METHOD_NAME_SUBSTR = "double_getSupportLowerBound(double,int)"; // Only analyze methods whose names
                                                                                   // contain this
    // substring

    // Test-generation entry metadata. METHOD_NAME_SUBSTR must remain the real
    // mutation method A; TEST_ENTRY_METHOD_SUBSTR is the callable public entry B
    // that generated unit tests should target.
    private String TEST_ENTRY_CLASS_NAME_P = CLASS_NAME_P;
    private String TEST_ENTRY_CLASS_NAME_M = CLASS_NAME_M;
    private String TEST_ENTRY_METHOD_SUBSTR = METHOD_NAME_SUBSTR;
    private String TEST_ENTRY_KIND = "DIRECT_OR_UNRESOLVED";
    private String TEST_CALL_CHAIN = "";
    private String TEST_ENTRY_NOTES = "";
    private String TEST_GENERATION_PACKAGE = "";
    private boolean USE_REFLECTION_FALLBACK = false;

    // Receiver construction metadata for callable test entry B.
    // DependencyContextBuilder
    // can infer these again from source, but these fields allow resolver decisions
    // to be
    // persisted into output.json when available.
    private String TEST_ENTRY_OWNER_KIND = "";
    private boolean TEST_ENTRY_OWNER_ABSTRACT = false;
    private boolean TEST_ENTRY_OWNER_INTERFACE = false;
    private boolean TEST_ENTRY_OWNER_INSTANTIABLE = false;
    private String TEST_RECEIVER_STRATEGY = "";
    private String TEST_RECEIVER_CONSTRUCTION = "";
    private String TEST_RECEIVER_NOTES = "";
    private String ENTRY_INVOCATION_KIND = "";
    private String TEST_RECEIVER_RUNTIME_CLASS = "";
    private String TEST_RECEIVER_RUNTIME_SOOT_CLASS = "";
    private String TEST_RECEIVER_DECLARING_CLASS = "";
    private String TEST_RECEIVER_DISPATCH_TARGET = "";
    private boolean TEST_RECEIVER_DISPATCHES_TO_MUTATION = false;
    private boolean TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION = false;
    private String TEST_RECEIVER_SETUP_TEMPLATE = "";
    private String TEST_RECEIVER_INVOCATION_TEMPLATE = "";
    private String TEST_RECEIVER_RESOLUTION_REASON = "";
    private String TEST_RECEIVER_FACTORY_METHOD = "";
    private String TEST_RECEIVER_BUILDER_CLASS = "";
    private String TEST_RECEIVER_BUILDER_TERMINAL_METHOD = "";
    private String TEST_RECEIVER_BUILDER_SETUP_CHAIN = "";
    private String TEST_RECEIVER_ANTI_PATTERNS = "";
    private String AVAILABLE_PUBLIC_METHODS = "";
    private String AVAILABLE_SETUP_METHODS = "";
    private String STATE_SETUP_PLAN = "";
    private String OBSERVABLE_PLAN_KIND = "";
    private String OBSERVABLE_SETUP = "";
    private String OBSERVABLE_CALL = "";
    private String OBSERVABLE_EXPECTED_ORIGINAL = "";
    private String OBSERVABLE_REASON = "";
    private String OBSERVABLE_ANTI_PATTERNS = "";
    private String BRANCH_REACHABILITY_KIND = "";
    private String BRANCH_REACHABILITY_CONDITION = "";
    private String BRANCH_REACHABILITY_SETUP = "";
    private String BRANCH_REACHABILITY_REASON = "";
    private String ABSTRACT_METHODS_TO_IMPLEMENT = "";
    private String ALLOWED_OVERRIDES = "";
    private String FORBIDDEN_OVERRIDES = "";
    private String TEST_STUB_CLASS_TEMPLATE = "";
    private String TEST_STUB_CONSTRUCTOR_TEMPLATE = "";
    private boolean SKIP_TEST_GENERATION = false;
    private String SKIP_REASON = "";

    private String CLASSES_DIR_P = "target/classes/demo/" + "origin"; // p's .class directory
    private String CLASSES_DIR_M = "target/classes/demo/" + mID; // m's .class directory

    // Output path for strategy dataset
    private String outputPath = CLASSES_DIR_M + "/graph/output.json"; // Output JSON path
    private String codeEmbeddingPath = CLASSES_DIR_M + "/graph/codeEmbedding.json"; // Fine-tuned code embedding
                                                                                    // strategy
    private String zeroShotPath = CLASSES_DIR_M + "/graph/zeroShot.json"; // zero-shot strategy
    private String fewShotPath = CLASSES_DIR_M + "/graph/fewShot.json"; // few-shot strategy
    private String fineTuningPath = CLASSES_DIR_M + "/graph/fineTuning.json"; // fine-tuning strategy

    private List<String> SPEC_OBSERVED = Arrays.asList("return", "exception", "state");
    private List<String> DOMAIN_ASSUMPTIONS = listOf();

    private List<ChangeRange> ranges;

    /**
     * Only cache origin-side fields that are independent of the current mutant.
     * Do NOT cache affected/paths/cpg here, because they depend on the current
     * ranges
     * computed from the current original-mutant pair.
     */
    private static final Map<String, OriginStaticSnapshot> ORIGIN_STATIC_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> SYMBOLIC_KEYWORDS = new LinkedHashSet<String>(Arrays.asList(
            "if", "else", "true", "false", "null", "return", "new", "throw", "throws",
            "int", "long", "double", "float", "boolean", "char", "byte", "short", "void",
            "this", "super", "class", "instanceof"));

    /**
     * Safe text cache for method source extraction.
     * The key includes the concrete file path, so using it on mutant files is safe.
     */
    private static final Map<String, String> METHOD_TEXT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public RipParser() {
    }

    public RipParser(MutationConfig config) {
        if (config == null) {
            config = new MutationConfig();
        }
        String operator = firstNonBlank(config.operator);
        mID = operator.replaceAll("\\D+", "");
        OID = "";
        MUTANT_NAME = operator;
        Operator = operator.replaceAll("_\\d+$", "");
        Diff = config.mutationStatement;
        PROJECT_NAME = firstNonBlank(config.projectName, inferProjectName(config.filepath));

        String mutationOwnerClass = firstNonBlank(
                config.mutationClassName,
                config.className,
                config.classNameF);
        String mutationOwnerSootClass = firstNonBlank(
                config.mutationSootClassName,
                sourceClassPathToSootBinaryName(mutationOwnerClass));
        // Some spreadsheets store the method-level directory as filepath, e.g.
        // .../traditional_mutants/ConstantPool(java.io.DataInput), while the
        // actual mutant source/class files are under .../AOIS_5. Normalize it
        // before deriving source files, class directories, and output.json path.
        config.filepath = normalizeMutantDirectory(
                config.filepath,
                operator,
                mutationOwnerClass,
                config.classNameF,
                config.className);
        String originalDir = resolveOriginalDirectory(config.filepath);
        String sourceFileOwnerClass = firstNonBlank(
                config.classNameF,
                config.className,
                mutationOwnerClass);

        SRC_FILE_P = resolveJavaSourceFile(originalDir, sourceFileOwnerClass, mutationOwnerClass);
        SRC_FILE_M = resolveJavaSourceFile(config.filepath, config.className, sourceFileOwnerClass, mutationOwnerClass);

        // classNameF is often the public file owner. For package-private sibling
        // classes in the same file, e.g. ClassNameReader.java containing
        // package-private ConstantPool, the executable body is in config.className /
        // config.mutationClassName, not in classNameF. Use the real mutation owner
        // for Soot/Jimple and method extraction, while SRC_FILE_* may still point to
        // the public file owner source file.
        CLASS_NAME_P = mutationOwnerSootClass;
        CLASS_NAME_M = mutationOwnerSootClass;
        TARGET_CLASS_ID = firstNonBlank(
                config.rawTargetClassId,
                config.packageName,
                config.mutationSootClassName,
                mutationOwnerSootClass,
                mutationOwnerClass);

        // Keep this as the real mutation method A. The diff, affected Jimple,
        // CFG/DFG and RIP evidence are still computed for this method.
        METHOD_NAME_SUBSTR = config.methodName;

        // Separately store the test-generation entry B resolved by DataGenerator.
        TEST_ENTRY_CLASS_NAME_P = isBlank(config.testEntryClassName)
                ? CLASS_NAME_P
                : config.testEntryClassName;
        TEST_ENTRY_CLASS_NAME_M = TEST_ENTRY_CLASS_NAME_P;
        TEST_ENTRY_METHOD_SUBSTR = isBlank(config.testEntryMethodName)
                ? METHOD_NAME_SUBSTR
                : config.testEntryMethodName;
        TEST_ENTRY_KIND = isBlank(config.testEntryKind)
                ? "DIRECT_OR_UNRESOLVED"
                : config.testEntryKind;
        TEST_CALL_CHAIN = config.testCallChain == null ? "" : config.testCallChain;
        TEST_ENTRY_NOTES = config.testEntryNotes == null ? "" : config.testEntryNotes;
        String sourcePackageName = readPackageName(SRC_FILE_P);
        TEST_GENERATION_PACKAGE = sanitizeTestGenerationPackage(
                firstNonBlank(config.testGenerationPackage, sourcePackageName),
                sourcePackageName,
                TARGET_CLASS_ID,
                mutationOwnerSootClass);
        if (TEST_GENERATION_PACKAGE == null) {
            TEST_GENERATION_PACKAGE = "";
        }
        USE_REFLECTION_FALLBACK = config.useReflectionFallback;

        TEST_ENTRY_OWNER_KIND = config.testEntryOwnerKind == null ? "" : config.testEntryOwnerKind;
        TEST_ENTRY_OWNER_ABSTRACT = config.testEntryOwnerAbstract;
        TEST_ENTRY_OWNER_INTERFACE = config.testEntryOwnerInterface;
        TEST_ENTRY_OWNER_INSTANTIABLE = config.testEntryOwnerInstantiable;
        TEST_RECEIVER_STRATEGY = config.testReceiverStrategy == null ? "" : config.testReceiverStrategy;
        TEST_RECEIVER_CONSTRUCTION = config.testReceiverConstruction == null ? "" : config.testReceiverConstruction;
        TEST_RECEIVER_NOTES = config.testReceiverNotes == null ? "" : config.testReceiverNotes;
        ENTRY_INVOCATION_KIND = config.entryInvocationKind == null ? "" : config.entryInvocationKind;
        TEST_RECEIVER_RUNTIME_CLASS = config.testReceiverRuntimeClassName == null ? ""
                : config.testReceiverRuntimeClassName;
        TEST_RECEIVER_RUNTIME_SOOT_CLASS = config.testReceiverRuntimeSootClassName == null ? ""
                : config.testReceiverRuntimeSootClassName;
        TEST_RECEIVER_DECLARING_CLASS = config.testReceiverDeclaringClassName == null ? ""
                : config.testReceiverDeclaringClassName;
        TEST_RECEIVER_DISPATCH_TARGET = config.testReceiverDispatchTarget == null ? ""
                : config.testReceiverDispatchTarget;
        TEST_RECEIVER_DISPATCHES_TO_MUTATION = config.testReceiverDispatchesToMutationMethod;
        TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION = config.testReceiverSubclassOverridesMutationMethod;
        TEST_RECEIVER_SETUP_TEMPLATE = config.testReceiverSetupTemplate == null ? "" : config.testReceiverSetupTemplate;
        TEST_RECEIVER_INVOCATION_TEMPLATE = config.testReceiverInvocationTemplate == null ? ""
                : config.testReceiverInvocationTemplate;
        TEST_RECEIVER_RESOLUTION_REASON = config.testReceiverResolutionReason == null ? ""
                : config.testReceiverResolutionReason;
        TEST_RECEIVER_FACTORY_METHOD = config.testReceiverFactoryMethod == null ? "" : config.testReceiverFactoryMethod;
        TEST_RECEIVER_BUILDER_CLASS = config.testReceiverBuilderClassName == null ? ""
                : config.testReceiverBuilderClassName;
        TEST_RECEIVER_BUILDER_TERMINAL_METHOD = config.testReceiverBuilderTerminalMethod == null ? ""
                : config.testReceiverBuilderTerminalMethod;
        TEST_RECEIVER_BUILDER_SETUP_CHAIN = config.testReceiverBuilderSetupChain == null ? ""
                : config.testReceiverBuilderSetupChain;
        TEST_RECEIVER_ANTI_PATTERNS = config.testReceiverAntiPatterns == null ? "" : config.testReceiverAntiPatterns;
        AVAILABLE_PUBLIC_METHODS = config.availablePublicMethods == null ? "" : config.availablePublicMethods;
        AVAILABLE_SETUP_METHODS = config.availableSetupMethods == null ? "" : config.availableSetupMethods;
        STATE_SETUP_PLAN = config.stateSetupPlan == null ? "" : config.stateSetupPlan;
        OBSERVABLE_PLAN_KIND = config.observablePlanKind == null ? "" : config.observablePlanKind;
        OBSERVABLE_SETUP = config.observableSetup == null ? "" : config.observableSetup;
        OBSERVABLE_CALL = config.observableCall == null ? "" : config.observableCall;
        OBSERVABLE_EXPECTED_ORIGINAL = config.observableExpectedOriginal == null ? ""
                : config.observableExpectedOriginal;
        OBSERVABLE_REASON = config.observableReason == null ? "" : config.observableReason;
        OBSERVABLE_ANTI_PATTERNS = config.observableAntiPatterns == null ? "" : config.observableAntiPatterns;
        BRANCH_REACHABILITY_KIND = config.branchReachabilityKind == null ? "" : config.branchReachabilityKind;
        BRANCH_REACHABILITY_CONDITION = config.branchReachabilityCondition == null ? ""
                : config.branchReachabilityCondition;
        BRANCH_REACHABILITY_SETUP = config.branchReachabilitySetup == null ? "" : config.branchReachabilitySetup;
        BRANCH_REACHABILITY_REASON = config.branchReachabilityReason == null ? "" : config.branchReachabilityReason;
        ABSTRACT_METHODS_TO_IMPLEMENT = config.abstractMethodsToImplement == null ? ""
                : config.abstractMethodsToImplement;
        ALLOWED_OVERRIDES = config.allowedOverrides == null ? "" : config.allowedOverrides;
        FORBIDDEN_OVERRIDES = config.forbiddenOverrides == null ? "" : config.forbiddenOverrides;
        TEST_STUB_CLASS_TEMPLATE = config.testStubClassTemplate == null ? "" : config.testStubClassTemplate;
        TEST_STUB_CONSTRUCTOR_TEMPLATE = config.testStubConstructorTemplate == null ? ""
                : config.testStubConstructorTemplate;
        SKIP_TEST_GENERATION = config.skipTestGeneration;
        SKIP_REASON = config.skipReason == null ? "" : config.skipReason;

        String fileDirP = PathSanitizer.path(config.filepath).getParent().getParent().getParent().toString();
        CLASSES_DIR_P = fileDirP + "/original/";
        CLASSES_DIR_M = config.filepath + "/";

        // Output path for strategy dataset
        outputPath = CLASSES_DIR_M + "/graph/output.json"; // Output JSON path
        codeEmbeddingPath = CLASSES_DIR_M + "/graph/codeEmbedding.json"; // Fine-tuned code embedding
                                                                         // strategy
        zeroShotPath = CLASSES_DIR_M + "/graph/zeroShot.json"; // zero-shot strategy
        fewShotPath = CLASSES_DIR_M + "/graph/fewShot.json"; // few-shot strategy
        fineTuningPath = CLASSES_DIR_M + "/graph/fineTuning.json"; // fine-tuning strategy
    }

    public static void main(String[] args) throws Exception {
        RipParser parser = new RipParser();
        String json = parser.analyzePairToJson();
        System.out.println(json);
    }

    public void test() throws Exception {
        String json = analyzePairToJson();
        System.out.println(json);
    }

    public String analyzePairToJson() throws Exception {
        long start = System.nanoTime();
        Bundle bundle = buildCommonInfoLightweight();
        long end = System.nanoTime();
        double duration1Ms = (end - start) / 1_000_000.0;
        // System.out.println("CommonInfo: " + duration1Ms + " ms");

        start = end;
        if (LEGACY_EVIDENCE_ENABLED) {
            buildLegacySootOriginSide(bundle);
            end = System.nanoTime();
            duration1Ms = (end - start) / 1_000_000.0;
            // System.out.println("OriginSide: " + duration1Ms + " ms");

            start = end;
            buildLegacySootMutantSide(bundle);
            end = System.nanoTime();
            duration1Ms = (end - start) / 1_000_000.0;
            // System.out.println("MutantSide: " + duration1Ms + " ms");
        } else {
            buildLightweightOriginSide(bundle);
            buildLightweightMutantSide(bundle);
        }

        Map<String, Object> out = assembleOutput(bundle);

        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(new File(outputPath), out);
        return om.writeValueAsString(out);
    }

    /**
     * Default diff path for output.json generation.
     * Uses the lightweight JavaParser/LCS implementation instead of GumTree so
     * batch generation does not pay the heavier AST matching cost on every row.
     */
    private Bundle buildCommonInfoLightweight() throws Exception {
        Bundle bundle = new Bundle();
        bundle.operator.item = Operator;
        bundle.diff.item = Diff;
        bundle.domainAssumptions.items = DOMAIN_ASSUMPTIONS;
        bundle.specObserved.items = SPEC_OBSERVED;
        ranges = DiffWithLineRanges1.diffWithLineRanges(
                SRC_FILE_P,
                SRC_FILE_M,
                CLASS_NAME_P,
                METHOD_NAME_SUBSTR);

        // Some mutation spreadsheets point to an abstract/interface outer method,
        // while the actual changed executable body is in a nested concrete class.
        // Retarget after diff ranges are known, so Jimple body lookup uses the real
        // owner and signature. Examples: DateTimeFieldType$Standard...,
        // DurationFieldType$Standard..., InputAccessor$Std, Angle$Deg, etc.
        retargetChangedNestedCallableIfNeeded();

        bundle.jimpleChanges.items = ranges.stream().map(ChangeRange::toString).collect(Collectors.toList());
        return bundle;
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String inferProjectName(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "";
        }
        String[] parts = normalized.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("result".equals(parts[i]) && i > 0) {
                return parts[i - 1];
            }
            if ("Programs".equalsIgnoreCase(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private static String readPackageName(String javaFile) {
        if (javaFile == null || javaFile.trim().isEmpty()) {
            return "";
        }
        File file = new File(javaFile);
        if (!file.isFile()) {
            return "";
        }
        Pattern packagePattern = Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ") || trimmed.startsWith("public ")
                        || trimmed.startsWith("class ") || trimmed.startsWith("interface ")
                        || trimmed.startsWith("enum ")) {
                    return "";
                }
                Matcher matcher = packagePattern.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private static String sanitizeTestGenerationPackage(String candidate,
            String sourcePackageName,
            String targetClassId,
            String mutationOwnerSootClass) {
        String value = candidate == null ? "" : candidate.trim();
        if (value.isEmpty()) {
            return "";
        }
        String actualPackage = sourcePackageName == null ? "" : sourcePackageName.trim();
        if (looksLikeTargetClassName(value, targetClassId, mutationOwnerSootClass)) {
            return actualPackage;
        }
        return value;
    }

    private static boolean looksLikeTargetClassName(String value, String targetClassId, String mutationOwnerSootClass) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty()) {
            return false;
        }
        String target = firstNonBlank(targetClassId, mutationOwnerSootClass);
        if (!target.isEmpty() && candidate.equals(target)) {
            return true;
        }
        String simple = simpleName(firstNonBlank(target, mutationOwnerSootClass));
        return !simple.isEmpty() && candidate.endsWith("." + simple);
    }

    private static String simpleName(String className) {
        if (className == null) {
            return "";
        }
        String s = className.trim();
        if (s.isEmpty()) {
            return s;
        }
        int dollar = s.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < s.length()) {
            s = s.substring(dollar + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) {
            s = s.substring(dot + 1);
        }
        return s;
    }

    private static String sourceClassPathToSootBinaryName(String sourceClassPath) {
        if (sourceClassPath == null) {
            return "";
        }
        String s = sourceClassPath.trim().replace('$', '.');
        if (s.isEmpty()) {
            return s;
        }
        // In this codebase class names are usually simple source paths without the
        // package. Convert nested source paths such as StrMatcher.CharMatcher into
        // the binary/Soot name StrMatcher$CharMatcher. Top-level names remain as-is.
        int firstDot = s.indexOf('.');
        if (firstDot < 0) {
            return s;
        }
        return s.substring(0, firstDot) + "$" + s.substring(firstDot + 1).replace('.', '$');
    }

    private static String normalizeMutantDirectory(String dir, String operator, String... classCandidates) {
        if (dir == null || dir.trim().isEmpty()) {
            return dir;
        }
        java.nio.file.Path base = PathSanitizer.path(dir);
        if (base.getFileName() != null && base.getFileName().toString().endsWith(".java")) {
            base = base.getParent();
        }

        // Already points at the concrete operator directory, e.g. .../AOIS_5.
        if (containsAnyJavaFile(base)) {
            return normalizePath(base);
        }

        // Excel rows sometimes point at the method-level directory. Append the
        // operator id when the operator subdirectory exists.
        String op = operator == null ? "" : operator.trim();
        if (!op.isEmpty()) {
            java.nio.file.Path byOperator = base.resolve(op);
            if (java.nio.file.Files.isDirectory(byOperator)) {
                return normalizePath(byOperator);
            }
        }

        // Fallback: choose a child directory that declares the requested class.
        if (classCandidates != null) {
            try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(base)) {
                java.util.List<java.nio.file.Path> dirs = st
                        .filter(java.nio.file.Files::isDirectory)
                        .collect(java.util.stream.Collectors.toList());
                for (java.nio.file.Path d : dirs) {
                    for (String c : classCandidates) {
                        String simple = simpleName(c);
                        if (!simple.isEmpty() && findJavaFileDeclaringType(d, simple) != null) {
                            return normalizePath(d);
                        }
                    }
                    if (containsAnyJavaFile(d)) {
                        // Keep as last-chance within the loop; most mutant operator dirs
                        // contain exactly the generated source file.
                        return normalizePath(d);
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return normalizePath(base);
    }

    private static String resolveOriginalDirectory(String mutantDir) {
        if (mutantDir == null || mutantDir.trim().isEmpty()) {
            return mutantDir;
        }
        java.nio.file.Path p = PathSanitizer.path(mutantDir);
        for (java.nio.file.Path cur = p; cur != null; cur = cur.getParent()) {
            java.nio.file.Path candidate = cur.resolve("original");
            if (java.nio.file.Files.isDirectory(candidate)) {
                return normalizePath(candidate);
            }
        }
        // Preserve old layout assumption as a deterministic fallback.
        try {
            return normalizePath(PathSanitizer.path(mutantDir).getParent().getParent().getParent().resolve("original"));
        } catch (Exception ex) {
            return mutantDir;
        }
    }

    private static boolean containsAnyJavaFile(java.nio.file.Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return false;
        }
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(dir)) {
            return st.anyMatch(p -> p.getFileName().toString().endsWith(".java"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizePath(java.nio.file.Path p) {
        return PathSanitizer.normalizeForJson(p);
    }

    private static String resolveJavaSourceFile(String dir, String... classCandidates) {
        if (dir == null || isBlank(dir)) {
            return dir;
        }
        java.nio.file.Path base = PathSanitizer.path(dir);

        // 1) Try the conventional file names first. This preserves the old behavior
        // for normal one-public-class-one-file cases.
        if (classCandidates != null) {
            for (String c : classCandidates) {
                String simple = simpleName(c);
                if (simple.isEmpty()) {
                    continue;
                }
                java.nio.file.Path candidate = base.resolve(simple + ".java");
                if (java.nio.file.Files.exists(candidate)) {
                    return candidate.toString();
                }
            }
        }

        // 2) If the target type is a package-private sibling class, its source file
        // is named after another public class. Search Java files in the mutant/origin
        // directory and choose the one that declares the requested type.
        if (classCandidates != null) {
            for (String c : classCandidates) {
                String simple = simpleName(c);
                if (simple.isEmpty()) {
                    continue;
                }
                java.nio.file.Path found = findJavaFileDeclaringType(base, simple);
                if (found != null) {
                    return found.toString();
                }
            }
        }

        // 3) Last resort: many mutant directories contain exactly one Java file.
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(base)) {
            java.util.List<java.nio.file.Path> javaFiles = st
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
            if (javaFiles.size() == 1) {
                return javaFiles.get(0).toString();
            }
        } catch (Exception ignored) {
            // fall through to deterministic fallback
        }

        // Keep a deterministic path for the final error message if nothing exists.
        String fallback = classCandidates == null || classCandidates.length == 0
                ? "Unknown"
                : simpleName(firstNonBlank(classCandidates));
        return base.resolve(fallback + ".java").toString();
    }

    private static java.nio.file.Path findJavaFileDeclaringType(java.nio.file.Path dir, String simpleTypeName) {
        if (dir == null || simpleTypeName == null || isBlank(simpleTypeName)) {
            return null;
        }
        if (!java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        java.util.regex.Pattern decl = java.util.regex.Pattern.compile(
                "(?m)(?:^|\\s)(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|static\\s+|strictfp\\s+)*"
                        + "(?:class|interface|enum|record)\\s+"
                        + java.util.regex.Pattern.quote(simpleTypeName)
                        + "(?:\\s|<|\\{|extends|implements)");
        try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(dir)) {
            java.util.List<java.nio.file.Path> javaFiles = st
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
            for (java.nio.file.Path p : javaFiles) {
                try {
                    String text = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                    if (decl.matcher(text).find()) {
                        return p;
                    }
                } catch (Exception ignored) {
                    // try next file
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Bundle buildCommonInfo() throws Exception {
        Bundle bundle = new Bundle();
        bundle.operator.item = Operator;
        bundle.diff.item = Diff;
        bundle.domainAssumptions.items = DOMAIN_ASSUMPTIONS;
        bundle.specObserved.items = SPEC_OBSERVED;
        ranges = DiffWithLineRanges.diffWithLineRangesForMethod(
                SRC_FILE_P,
                SRC_FILE_M,
                CLASS_NAME_P,
                METHOD_NAME_SUBSTR);

        // Some mutation spreadsheets point to an abstract/interface outer method,
        // while the actual changed executable body is in a nested concrete class.
        // Retarget after diff ranges are known, so Soot/Jimple body lookup uses
        // the real owner and signature. Examples: DateTimeFieldType$Standard...,
        // DurationFieldType$Standard..., InputAccessor$Std, Angle$Deg, etc.
        retargetChangedNestedCallableIfNeeded();

        // ranges = DiffWithLineRanges.diffWithLineRanges(SRC_FILE_P, SRC_FILE_M);
        bundle.jimpleChanges.items = ranges.stream().map(ChangeRange::toString).collect(Collectors.toList());

        return bundle;
    }

    private void retargetChangedNestedCallableIfNeeded() {
        try {
            Optional<SourceOwnerResolver.Resolution> opt = SourceOwnerResolver.resolveChangedCallableOwner(
                    SRC_FILE_M,
                    CLASS_NAME_M,
                    METHOD_NAME_SUBSTR,
                    ranges);
            if (!opt.isPresent()) {
                return;
            }
            SourceOwnerResolver.Resolution r = opt.get();
            String oldClassP = CLASS_NAME_P;
            String oldClassM = CLASS_NAME_M;
            String oldMethod = METHOD_NAME_SUBSTR;

            CLASS_NAME_P = r.ownerBinaryName;
            CLASS_NAME_M = r.ownerBinaryName;
            METHOD_NAME_SUBSTR = r.callableSignature;

            // If B was the same as A before retargeting, keep B aligned with the
            // real executable mutation method. This prevents EntryLiftedRIP from
            // trying to read an abstract/interface body.
            if (TEST_ENTRY_CLASS_NAME_P == null || isBlank(TEST_ENTRY_CLASS_NAME_P)
                    || TEST_ENTRY_CLASS_NAME_P.equals(oldClassP)
                    || TEST_ENTRY_CLASS_NAME_P.equals(oldClassM)) {
                TEST_ENTRY_CLASS_NAME_P = CLASS_NAME_P;
                TEST_ENTRY_CLASS_NAME_M = CLASS_NAME_M;
            }
            if (TEST_ENTRY_METHOD_SUBSTR == null || isBlank(TEST_ENTRY_METHOD_SUBSTR)
                    || TEST_ENTRY_METHOD_SUBSTR.equals(oldMethod)) {
                TEST_ENTRY_METHOD_SUBSTR = METHOD_NAME_SUBSTR;
            }
            TEST_ENTRY_NOTES = appendNote(TEST_ENTRY_NOTES,
                    "retargeted mutation owner by changed source line: " + r.reason);
        } catch (Exception ignored) {
            // Retargeting is best-effort; keep legacy behavior if anything goes wrong.
        }
    }

    private static String appendNote(String old, String note) {
        if (note == null || isBlank(note)) {
            return old == null ? "" : old;
        }
        if (old == null || isBlank(old)) {
            return note;
        }
        return old + " | " + note;
    }

    private void buildLegacySootOriginSide(Bundle bundle) throws Exception {
        String cacheKey = originStaticCacheKey();
        OriginStaticSnapshot cached = ORIGIN_STATIC_CACHE.get(cacheKey);

        Body body;

        if (cached == null) {
            cached = new OriginStaticSnapshot();
            cached.id = OID;
            cached.content = cachedExtractMethod(SRC_FILE_P, CLASS_NAME_P, METHOD_NAME_SUBSTR, true);

            AstToJimpleBridge.initSoot(CLASSES_DIR_P);
            body = AstToJimpleBridge.getBody(CLASS_NAME_P, METHOD_NAME_SUBSTR);
            cached.ir = body.toString();

            ORIGIN_STATIC_CACHE.put(cacheKey, cached);
        } else {
            AstToJimpleBridge.initSoot(CLASSES_DIR_P);
            body = AstToJimpleBridge.getBody(CLASS_NAME_P, METHOD_NAME_SUBSTR);
        }

        bundle.origin.item.id.item = cached.id;
        bundle.origin.item.content.item = cached.content;
        bundle.origin.item.IR.item = cached.ir;

        // These fields depend on the current pair-specific ranges, so they must be
        // recomputed for each mutant.
        List<Unit> affected = analyzeAffectedUnits(body, ranges);
        bundle.origin.item.Affected.items = formatAffectedUnits(affected);

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, ranges, affected);
        bundle.origin.item.Paths.items = validePaths.stream()
                .map(AstToJimpleBridge::pathToString)
                .collect(Collectors.toList());

        for (List<Unit> path : validePaths) {
            bundle.origin.item.CPG.add(buildInfoItem(path, body, affected));
        }
    }

    private void buildLegacySootMutantSide(Bundle bundle) throws Exception {
        bundle.mutant.item.id.item = mID;
        bundle.mutant.item.content.item = cachedExtractMethod(SRC_FILE_M, CLASS_NAME_M, METHOD_NAME_SUBSTR, true);

        AstToJimpleBridge.initSoot(CLASSES_DIR_M);
        Body body = AstToJimpleBridge.getBody(CLASS_NAME_M, METHOD_NAME_SUBSTR);
        bundle.mutant.item.IR.item = body.toString();

        List<Unit> affected = analyzeAffectedUnits(body, ranges);
        bundle.mutant.item.Affected.items = formatAffectedUnits(affected);

        List<List<Unit>> validePaths = analyzeAllPathsThroughAffected(body, ranges, affected);
        bundle.mutant.item.Paths.items = validePaths.stream().map(AstToJimpleBridge::pathToString)
                .collect(Collectors.toList());

        for (List<Unit> path : validePaths) {
            InfoItem item = buildInfoItem(path, body, affected);
            bundle.mutant.item.CPG.add(item);
        }
    }

    private void buildLightweightOriginSide(Bundle bundle) throws Exception {
        bundle.origin.item.id.item = OID;
        bundle.origin.item.content.item = cachedExtractMethod(SRC_FILE_P, CLASS_NAME_P, METHOD_NAME_SUBSTR, true);
        bundle.origin.item.IR.item = "";
        bundle.origin.item.Affected.items = listOf();
        bundle.origin.item.Paths.items = listOf();
    }

    private void buildLightweightMutantSide(Bundle bundle) throws Exception {
        bundle.mutant.item.id.item = mID;
        bundle.mutant.item.content.item = cachedExtractMethod(SRC_FILE_M, CLASS_NAME_M, METHOD_NAME_SUBSTR, true);
        bundle.mutant.item.IR.item = "";
        bundle.mutant.item.Affected.items = listOf();
        bundle.mutant.item.Paths.items = listOf();
    }

    // ===== Construct InfoItem based on affected path (textual CFG/DFG) =====
    private InfoItem buildInfoItem(List<Unit> path, Body body, List<Unit> affectedList) {
        InfoItem item = new InfoItem();
        item.Path = pathToString(path);

        UnitGraph ug = new ExceptionalUnitGraph(body);
        BlockGraph bg = new BriefBlockGraph(body);
        DominatorsFinder<Unit> dom = new MHGDominatorsFinder<>(ug);
        DominatorsFinder<Unit> pdom = new MHGPostDominatorsFinder<>(ug);

        // Aggregate and deduplicate (affected nodes may be >1)
        LinkedHashSet<String> domSummaries = new LinkedHashSet<>();
        LinkedHashSet<String> pathPreds = new LinkedHashSet<>();
        LinkedHashSet<String> ctrlDeps = new LinkedHashSet<>();

        for (Unit affected : affectedList) {
            Unit mutUnit = locateByText(body, affected.toString());
            if (mutUnit == null)
                continue;

            // ---- CFG.dom: Dominator chain summary for basic blocks ----
            domSummaries.addAll(unitsToBlockSummaries(bg, dom.getDominators(mutUnit)));

            // ---- CFG.path_predicates: If conditions along dominator chain (Top-K) ----
            for (Unit u : dom.getDominators(mutUnit)) {
                if (u instanceof IfStmt) {
                    pathPreds.add(((IfStmt) u).getCondition().toString());
                }
            }

            // ---- CFG.control_deps_out: Control dependencies based on post-dominators ----
            ctrlDeps.addAll(computeControlDepsText(bg, pdom, mutUnit));

            // ---- DFG: Build from the affected point ----
            buildDFG(item, mutUnit, ug, pdom, SPEC_OBSERVED);
        }

        // Write back (clip Top-K to avoid excessive length)
        item.CFG.dom.items = new ArrayList<>(domSummaries);
        item.CFG.path_predicates.items = pathPreds.stream().limit(8).collect(Collectors.toList());
        item.CFG.control_deps_out.items = new ArrayList<>(ctrlDeps);

        return item;
    }

    // ===== Build DFG (defs_at_mut / uses_toward_output / kill_set etc.) =====
    private static void buildDFG(InfoItem item, Unit mutUnit, UnitGraph ug,
            DominatorsFinder<Unit> pdom, List<String> specObserved) {

        SimpleLocalDefs sdefs = new SimpleLocalDefs(ug);
        SimpleLocalUses suses = new SimpleLocalUses(ug, sdefs);

        // ---------- 1) Identify true variables carrying 螖 (tracked set) ----------
        LinkedHashSet<String> tracked = new LinkedHashSet<>();

        if (mutUnit instanceof AssignStmt) {
            AssignStmt as = (AssignStmt) mutUnit;
            Value lhs = as.getLeftOp();

            if (lhs instanceof Local && !isTemp((Local) lhs)) {
                // Mutation point directly defines a real variable (e.g., numEntries =
                // numEntries + entries)
                DFG.KV def = new DFG.KV();
                def.var = lhs.toString();
                def.unit = mutUnit.toString();
                item.DFG.defs_point.add(def);
                tracked.add(def.var);
            } else {
                // Mutation point defines a temp var (e.g., $stack5 = neg entries), find next
                // real assignment via uses
                for (UnitValueBoxPair use : suses.getUsesOf(mutUnit)) {
                    if (use.unit instanceof AssignStmt) {
                        Value realLhs = ((AssignStmt) use.unit).getLeftOp();
                        if (realLhs instanceof Local && !isTemp((Local) realLhs)) {
                            DFG.KV def = new DFG.KV();
                            def.var = realLhs.toString();
                            def.unit = use.unit.toString();
                            item.DFG.defs_point.add(def);
                            tracked.add(def.var);
                        }
                    }
                }
            }
        }
        if (tracked.isEmpty()) {
            // For pure control flow changes, may consider pseudo-vars; here we return
            // directly to avoid noise
            return;
        }

        // ---------- 2) uses_toward_output: Forward slice until sink ----------
        Set<Unit> visited = new HashSet<>();
        Deque<Unit> work = new ArrayDeque<>();
        work.add(mutUnit);

        while (!work.isEmpty()) {
            Unit u = work.poll();
            if (!visited.add(u))
                continue;
            if (u instanceof IfStmt && specObserved.contains("return")) {
                if (guardsReturn(u, ug, specObserved)) {
                    // Locals used in condition and tracked (carrying 螖) 鈫?count as observable usage
                    // at return (via control)
                    for (ValueBox vb : u.getUseBoxes()) {
                        Value v = vb.getValue();
                        if (v instanceof Local) {
                            String name = v.toString();
                            if (tracked.contains(name)) {
                                DFG.SinkUse su = new DFG.SinkUse();
                                su.var = name;
                                su.unit = u.toString();
                                su.sink = "return";
                                item.DFG.uses_toward_output.add(su);
                            }
                        }
                    }
                }
            }

            if (isSink(u, specObserved)) {
                // Only record tracked variable usages at sink
                for (ValueBox vb : u.getUseBoxes()) {
                    Value v = vb.getValue();
                    if (v instanceof Local) {
                        String name = v.toString();
                        if (tracked.contains(name)) {
                            DFG.SinkUse use = new DFG.SinkUse();
                            use.var = name;
                            use.unit = u.toString();
                            use.sink = sinkKind(u);
                            item.DFG.uses_toward_output.add(use);
                        }
                    }
                }
                continue; // Stop expanding branch once sink is reached
            }

            for (UnitValueBoxPair p : suses.getUsesOf(u)) {
                work.add(p.unit);
            }
        }

        // --- 3) kill_set: Overwriting definitions of tracked vars before any sink ---
        LinkedHashSet<String> killSig = new LinkedHashSet<>();
        for (Unit u : ug) {
            if (!(u instanceof AssignStmt))
                continue;
            Value lhs = ((AssignStmt) u).getLeftOp();
            if (!(lhs instanceof Local))
                continue;
            String var = lhs.toString();
            if (isTemp((Local) lhs))
                continue; // Skip temporaries
            if (!tracked.contains(var))
                continue; // Focus only on 螖-carrying vars
            if (u == mutUnit)
                continue; // Don鈥檛 count mutation point itself as a kill

            boolean pdByAnySink = postdominatedByAnySink(u, ug, pdom, specObserved);
            String sig = var + "|" + u.toString() + "|" + pdByAnySink;
            if (killSig.add(sig)) {
                DFG.Kill k = new DFG.Kill();
                k.var = var;
                k.unit = u.toString();
                k.postdominated_by_sink = pdByAnySink;
                item.DFG.kill_set.add(k);
            }
        }

        // ---------- 4) Deduplicate (defs / uses) ----------
        dedupDFGLists(item);
    }

    // ====== Utilities ======
    private static boolean isTemp(Local l) {
        // return l.getName().startsWith("$");
        return false; // Not filtering yet; allow tracking temporaries
    }

    private static boolean postdominatedByAnySink(Unit u, UnitGraph ug,
            DominatorsFinder<Unit> pdom,
            List<String> specObserved) {
        for (Unit cand : ug) {
            if (isSink(cand, specObserved)) {
                if (pdom.getDominators(u).contains(cand))
                    return true;
            }
        }
        return false;
    }

    private static boolean isSink(Unit u, List<String> specObserved) {
        if (specObserved.contains("return") && u instanceof ReturnStmt)
            return true;
        if (specObserved.contains("exception") && u instanceof ThrowStmt)
            return true;
        // "state" sink depends on project definition (e.g., external state writes); not
        // handled here yet
        return false;
    }

    private static String sinkKind(Unit u) {
        if (u instanceof ReturnStmt)
            return "return";
        if (u instanceof ThrowStmt)
            return "exception";
        return "state";
    }

    // Whether If guards a return: any successor must reach return sink within
    // bounded steps
    private static boolean guardsReturn(Unit ifUnit, UnitGraph ug, List<String> specObserved) {
        if (!(ifUnit instanceof IfStmt))
            return false;
        // First, check if direct successor is return
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (isSink(s, specObserved))
                return true;
        }
        // Otherwise, do shallow forward search (to avoid full graph cost)
        for (Unit s : ug.getSuccsOf(ifUnit)) {
            if (reachesSinkWithin(s, ug, specObserved, 12))
                return true; // Step count can be adjusted
        }
        return false;
    }

    private static boolean reachesSinkWithin(Unit start, UnitGraph ug,
            List<String> specObserved, int maxSteps) {
        Set<Unit> seen = new HashSet<>();
        Deque<Unit> q = new ArrayDeque<>();
        q.add(start);
        int steps = 0;
        while (!q.isEmpty() && steps++ < maxSteps) {
            Unit cur = q.poll();
            if (!seen.add(cur))
                continue;
            if (isSink(cur, specObserved))
                return true;
            for (Unit nxt : ug.getSuccsOf(cur))
                q.add(nxt);
        }
        return false;
    }

    private static void dedupDFGLists(InfoItem item) {
        // Deduplicate defs
        LinkedHashSet<String> sig = new LinkedHashSet<>();
        item.DFG.defs_point.items.removeIf(kv -> !sig.add(kv.var + "|" + kv.unit));

        // Deduplicate uses
        sig.clear();
        item.DFG.uses_toward_output.items.removeIf(u -> !sig.add(u.var + "|" + u.unit + "|" + u.sink));

        // Deduplicate kill_set
        sig.clear();
        item.DFG.kill_set.items.removeIf(u -> !sig.add(u.var + "|" + u.unit + "|" + u.postdominated_by_sink));
    }

    private static List<String> formatAffectedUnits(List<Unit> units) {
        return units.stream().map(u -> {
            int[] lr = unitLineRange(u);
            return String.format("JIMPLE [%d-%d] %s%n", lr[0], lr[1], u);
        }).collect(Collectors.toList());
    }

    private Map<String, Object> assembleOutput(Bundle bundle) {
        initializeMutationSemanticContext();
        Map<String, Object> dependencyContext = buildDependencyContextOutput();
        Map<String, Object> entryLiftedRip = buildEntryLiftedRipOutput();
        Map<String, Object> mutationKillPlan = buildMutationKillPlanOutput();
        Map<String, Object> inputDistinguishPlan = buildInputDistinguishPlanOutput();
        Map<String, Object> propagationPlan = buildPropagationPlanOutput();
        Map<String, Object> assertionPlan = buildAssertionPlanOutput();
        Map<String, Object> ripExecutionPlan = buildRipExecutionPlanOutput();
        Map<String, Object> entryParameterControlPlan = buildEntryParameterControlPlanOutput();
        Map<String, Object> killabilityPlan = buildKillabilityPlanOutput();
        Map<String, Object> observableSelectionPlan = buildObservableSelectionPlanOutput();
        Map<String, Object> liftedReachabilityPlan = buildLiftedReachabilityPlanOutput();
        Map<String, Object> evidenceQuality = buildEvidenceQualityOutput();
        Map<String, Object> codeKbContext = CodeKbEvidenceAdapter.loadContext(buildMutationConfigSnapshot());
        return buildCleanOutput(bundle,
                dependencyContext,
                entryLiftedRip,
                mutationKillPlan,
                inputDistinguishPlan,
                propagationPlan,
                assertionPlan,
                ripExecutionPlan,
                entryParameterControlPlan,
                killabilityPlan,
                observableSelectionPlan,
                liftedReachabilityPlan,
                evidenceQuality,
                codeKbContext);
    }

    private void initializeMutationSemanticContext() {
        MUTATION_SEMANTIC_CONTEXT = MutationSemanticLocator.resolve(
                PathSanitizer.path(SRC_FILE_P),
                PathSanitizer.path(SRC_FILE_M),
                CLASS_NAME_P,
                METHOD_NAME_SUBSTR,
                firstMutationLine(),
                mutationOriginalRawExpression(),
                mutationMutatedRawExpression());
    }

    private Map<String, Object> buildCleanOutput(
            Bundle bundle,
            Map<String, Object> dependencyContext,
            Map<String, Object> entryLiftedRip,
            Map<String, Object> mutationKillPlan,
            Map<String, Object> inputDistinguishPlan,
            Map<String, Object> propagationPlan,
            Map<String, Object> assertionPlan,
            Map<String, Object> ripExecutionPlan,
            Map<String, Object> entryParameterControlPlan,
            Map<String, Object> killabilityPlan,
            Map<String, Object> observableSelectionPlan,
            Map<String, Object> liftedReachabilityPlan,
            Map<String, Object> evidenceQuality,
            Map<String, Object> codeKbContext) {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> dependency = asMap(unwrapAnnotated(dependencyContext));
        Map<String, Object> dependencyRelationship = asMap(dependency.get("relationship"));
        Map<String, Object> testEntryContext = asMap(dependency.get("testEntryContext"));
        Map<String, Object> mutationContext = asMap(dependency.get("mutationContext"));

        Map<String, Object> entryLifted = asMap(unwrapAnnotated(entryLiftedRip));
        Map<String, Object> entryRelation = asMap(entryLifted.get("entryRelation"));
        Map<String, Object> entryGenerationPlan = asMap(entryLifted.get("entryGenerationPlan"));
        Map<String, Object> entryOrigin = asMap(entryLifted.get("origin"));
        Map<String, Object> entryMutated = asMap(entryLifted.get("mutated"));

        Map<String, Object> mutationKill = asMap(unwrapAnnotated(mutationKillPlan));
        Map<String, Object> inputDistinguish = asMap(unwrapAnnotated(inputDistinguishPlan));
        Map<String, Object> propagation = asMap(unwrapAnnotated(propagationPlan));
        Map<String, Object> assertion = asMap(unwrapAnnotated(assertionPlan));
        Map<String, Object> ripExecution = asMap(unwrapAnnotated(ripExecutionPlan));
        Map<String, Object> entryControl = asMap(unwrapAnnotated(entryParameterControlPlan));
        Map<String, Object> killability = asMap(unwrapAnnotated(killabilityPlan));
        Map<String, Object> observableSelection = asMap(unwrapAnnotated(observableSelectionPlan));
        Map<String, Object> liftedReachability = asMap(unwrapAnnotated(liftedReachabilityPlan));
        Map<String, Object> quality = asMap(unwrapAnnotated(evidenceQuality));
        Map<String, Object> codekb = asMap(unwrapAnnotated(codeKbContext));
        testEntryContext.put("requiredImports", resolveRequiredImports(testEntryContext, entryGenerationPlan, codekb));
        Map<String, Object> origin = asMap(normalizeObject(bundle.origin));
        Map<String, Object> mutated = asMap(normalizeObject(bundle.mutant));
        List<Object> domainAssumptions = asList(normalizeObject(bundle.domainAssumptions));
        List<Object> specObserved = asList(normalizeObject(bundle.specObserved));
        List<Object> jimpleChanges = asList(normalizeObject(bundle.jimpleChanges));
        Map<String, Object> mutationSensitivePath = inferMutationSensitivePath(origin, mutated, propagation,
                observableSelection);

        out.put("meta", buildCleanMeta(codekb));
        out.put("mutation", buildCleanMutation(dependencyRelationship, entryRelation, origin, mutated));
        out.put("summary", buildCleanSummary(specObserved, killability, observableSelection, liftedReachability,
                propagation, quality, domainAssumptions, testEntryContext, entryGenerationPlan, codekb,
                mutationSensitivePath, ripExecution));
        Map<String, Object> guidance = buildCleanGuidance(specObserved, testEntryContext, entryGenerationPlan, mutationKill,
                inputDistinguish, assertion, entryControl, observableSelection, liftedReachability, propagation,
                mutationSensitivePath, ripExecution, codekb);
        out.put("guidance", guidance);
        Map<String, Object> canonicalCore = asMap(guidance.get("canonicalCore"));
        if (!canonicalCore.isEmpty()) {
            out.put("canonicalCore", canonicalCore);
        }
        if (LEGACY_EVIDENCE_ENABLED) {
            out.put("evidence", buildCleanEvidence(domainAssumptions, specObserved, jimpleChanges, dependency,
                    mutationContext, codekb, origin, mutated, entryLifted, entryOrigin, entryMutated, propagation,
                    liftedReachability, observableSelection, entryControl, testEntryContext, entryGenerationPlan,
                    mutationSensitivePath, ripExecution));
        }
        return out;
    }

    private Map<String, Object> buildCleanMeta(Map<String, Object> codekb) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", "2.2");
        meta.put("generatedAt", OffsetDateTime.now().toString());
        meta.put("generator", "EvidenceParse");
        meta.put("mode", "clean_root");
        meta.put("project", firstNonBlank(stringValue(codekb.get("project")), ""));
        meta.put("mutantId", firstNonBlank(stringValue(codekb.get("mutantId")), mID));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("originalJava", SRC_FILE_P);
        source.put("mutantJava", SRC_FILE_M);
        source.put("originalClassesDir", CLASSES_DIR_P);
        source.put("mutantClassesDir", CLASSES_DIR_M);
        meta.put("source", source);
        return meta;
    }

    private Map<String, Object> buildCleanMutation(Map<String, Object> relationship,
            Map<String, Object> entryRelation,
            Map<String, Object> origin,
            Map<String, Object> mutated) {
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("operator", Operator);
        mutation.put("diff", Diff);
        mutation.put("statement", Diff);

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("class", firstNonBlank(stringValue(relationship.get("mutationClass")), CLASS_NAME_P));
        location.put("method", firstNonBlank(stringValue(relationship.get("mutationMethod")),
                PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
        location.put("line", firstMutationLine());
        mutation.put("location", location);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("class", firstNonBlank(stringValue(relationship.get("testEntryClass")),
                stringValue(entryRelation.get("testEntryClass"))));
        entry.put("method", firstNonBlank(stringValue(relationship.get("testEntryMethod")),
                stringValue(entryRelation.get("testEntryMethod"))));
        entry.put("kind", firstNonBlank(stringValue(relationship.get("testEntryKind")),
                stringValue(entryRelation.get("testEntryKind"))));
        entry.put("invocationKind", firstNonBlank(stringValue(relationship.get("entryInvocationKind")),
                stringValue(entryRelation.get("entryInvocationKind"))));
        entry.put("callChain", asList(relationship.get("callChain")));
        entry.put("sameEntryAndMutation", Boolean.TRUE.equals(relationship.get("sameEntryAndMutation")));
        entry.put("useReflectionFallback", Boolean.TRUE.equals(relationship.get("useReflectionFallback")));
        mutation.put("entry", entry);

        Map<String, Object> bodies = new LinkedHashMap<>();
        bodies.put("original", buildMutationBodyBlock("original", origin));
        bodies.put("mutant", buildMutationBodyBlock("mutant", mutated));
        mutation.put("bodies", bodies);
        return mutation;
    }

    private Map<String, Object> buildMutationBodyBlock(String label, Map<String, Object> side) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("signature", firstNonBlank(stringValue(side.get("signature")), stringValue(side.get("method")),
                PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
        body.put("content", stringValue(side.get("content")));
        return body;
    }

    private Map<String, Object> buildCleanSummary(
            List<Object> specObserved,
            Map<String, Object> killability,
            Map<String, Object> observableSelection,
            Map<String, Object> liftedReachability,
            Map<String, Object> propagation,
            Map<String, Object> quality,
            List<Object> domainAssumptions,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> codekb,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> ripExecution) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> killabilitySummary = new LinkedHashMap<>();
        String killabilityLevel = stringValue(killability.get("killabilityLevel"));
        killabilitySummary.put("status", deriveKillabilityStatus(killabilityLevel));
        killabilitySummary.put("level", killabilityLevel);
        killabilitySummary.put("category", stringValue(killability.get("killabilityCategory")));
        killabilitySummary.put("recommendedMode", stringValue(killability.get("recommendedMode")));
        killabilitySummary.put("reason", stringValue(killability.get("reason")));
        summary.put("killability", killabilitySummary);

        String primarySink = selectPrimarySink(specObserved, propagation, observableSelection);
        if (shouldPreferReturnOverGenericException(primarySink, specObserved, propagation, observableSelection)) {
            primarySink = "return";
        }
        if ("exception".equals(primarySink) && isEntryParameterFixed(killabilitySummary)) {
            primarySink = firstNonExceptionSink(specObserved, "state");
        }
        List<Object> secondarySinks = withoutSink(specObserved, primarySink);

        boolean entryParameterFixed = isEntryParameterFixed(killabilitySummary);
        String baselineKind = stringValue(observableSelection.get("baselineObservableKind"));
        String baselineCall = stringValue(observableSelection.get("baselineObservableCall"));
        String preferredKind = preferredObservableKind(primarySink, observableSelection, propagation);
        String preferredCall = preferredObservableCall(primarySink, testEntryContext, entryGenerationPlan,
                observableSelection, propagation);
        boolean overrideRecommended = Boolean.TRUE.equals(observableSelection.get("overrideRecommended"));
        if (entryParameterFixed) {
            preferredKind = firstNonBlank(baselineKind, preferredKind);
            preferredCall = firstNonBlank(baselineCall, preferredCall);
            overrideRecommended = false;
        }
        Map<String, Object> observability = new LinkedHashMap<>();
        observability.put("primarySink", primarySink);
        observability.put("secondarySinks", secondarySinks);
        observability.put("baselineKind", baselineKind);
        observability.put("baselineCall", baselineCall);
        observability.put("preferredKind", preferredKind);
        observability.put("preferredCall", preferredCall);
        observability.put("overrideRecommended", overrideRecommended);
        observability.put("reason", preferredObservableReason(primarySink, observableSelection, propagation));
        summary.put("observability", observability);

        Map<String, Object> reachability = new LinkedHashMap<>();
        boolean liftedEnabled = Boolean.TRUE.equals(liftedReachability.get("enabled"));
        boolean sameEntryAndMutation = Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"));
        reachability.put("level", sameEntryAndMutation ? "DIRECT_MUTATION_METHOD"
                : (liftedEnabled ? "ENTRY_LIFTED_REACHABLE" : "ENTRY_LIFTED_DISABLED"));
        reachability.put("entryPathMode", sameEntryAndMutation ? "MUTATION_METHOD_DIRECT" : "ENTRY_TO_MUTATION");
        reachability.put("keyCondition", firstListValue(liftedReachability.get("entryToMutationConditions")));
        reachability.put("reason", stringValue(liftedReachability.get("liftedObservationBridge")));
        summary.put("reachability", reachability);

        if (!mutationSensitivePath.isEmpty()) {
            Map<String, Object> infection = new LinkedHashMap<>();
            infection.put("source", stringValue(mutationSensitivePath.get("source")));
            infection.put("requiredPathPredicates", asList(mutationSensitivePath.get("requiredPathPredicates")));
            infection.put("inputHint", stringValue(mutationSensitivePath.get("inputHint")));
            infection.put("preferredObservable", stringValue(mutationSensitivePath.get("preferredObservable")));
            putIfNotEmpty(infection, "classification", stringValue(mutationSensitivePath.get("classification")));
            putIfNotEmpty(infection, "readerContract", stringValue(mutationSensitivePath.get("readerContract")));
            putIfNotEmpty(infection, "recommendedTestShape",
                    stringValue(mutationSensitivePath.get("recommendedTestShape")));
            putIfNotEmpty(infection, "equivalenceRisk", stringValue(mutationSensitivePath.get("equivalenceRisk")));
            infection.put("reason", stringValue(mutationSensitivePath.get("reason")));
            summary.put("infection", infection);
        }

        Map<String, Object> propagationSummary = new LinkedHashMap<>();
        boolean hasPropagationChain = !asList(propagation.get("propagationChain")).isEmpty();
        if (isEntryParameterFixed(killabilitySummary)) {
            propagationSummary.put("level", "ENTRY_PARAMETER_FIXED");
            propagationSummary.put("requiresPublicSink", false);
            propagationSummary.put("reason", stringValue(killabilitySummary.get("reason")));
        } else {
            propagationSummary.put("level", hasPropagationChain
                    ? firstNonBlank(stringValue(propagation.get("observableSinkKind")), "HAS_PROPAGATION_CHAIN")
                    : "UNKNOWN");
            propagationSummary.put("requiresPublicSink",
                    !stringValue(propagation.get("observableSinkExpression")).isEmpty());
            propagationSummary.put("reason", firstNonBlank(
                    stringValue(propagation.get("infectionSource")),
                    firstListValue(propagation.get("propagationChain"))));
        }
        summary.put("propagation", propagationSummary);

        if (!ripExecution.isEmpty()) {
            Map<String, Object> rip = new LinkedHashMap<>();
            rip.put("model", stringValue(ripExecution.get("model")));
            rip.put("reachabilityGoal", stringValue(asMap(ripExecution.get("reachability")).get("goal")));
            rip.put("infectionGoal", stringValue(asMap(ripExecution.get("infection")).get("goal")));
            rip.put("propagationGoal", stringValue(asMap(ripExecution.get("propagation")).get("goal")));
            rip.put("oracleMode", stringValue(asMap(ripExecution.get("oracle")).get("preferredAssertionMode")));
            rip.put("confidence", stringValue(ripExecution.get("confidence")));
            summary.put("rip", rip);
        }

        Map<String, Object> equivalence = new LinkedHashMap<>();
        boolean equivalenceSuspicion = Boolean.TRUE.equals(quality.get("equivalenceSuspicion"));
        String equivalenceReason = stringValue(quality.get("equivalenceReason"));
        String structuralEquivalenceReason = inferStructuralEquivalenceReason();
        boolean structuralEquivalence = !isBlank(structuralEquivalenceReason);
        boolean staticEquivalence = equivalenceSuspicion || structuralEquivalence;
        String staticEquivalenceReason = !isBlank(equivalenceReason) ? equivalenceReason : structuralEquivalenceReason;
        List<Object> equivalenceRuleHits = new ArrayList<>(domainAssumptions);
        if (entryParameterFixed) {
            equivalenceRuleHits.add(
                    "ENTRY_PARAMETER_FIXED: chosen public entry reaches the mutation but cannot externally control mutation-sensitive parameters.");
        }
        if (staticEquivalence && !isBlank(staticEquivalenceReason)) {
            equivalenceRuleHits.add("STATIC_EQUIVALENCE_HEURISTIC: " + staticEquivalenceReason);
        }
        equivalence.put("suspected", staticEquivalence
                || (!domainAssumptions.isEmpty()
                        && !entryParameterFixed
                        && "blocked_or_equivalent".equals(killabilitySummary.get("status"))));
        equivalence.put("ruleHits", equivalenceRuleHits);
        equivalence.put("note", entryParameterFixed
                ? firstNonBlank(staticEquivalenceReason, stringValue(killabilitySummary.get("reason")))
                : firstNonBlank(staticEquivalenceReason, firstListValue(quality.get("weaknesses"))));
        summary.put("equivalence", equivalence);

        Map<String, Object> symbolicFeasibility = buildSymbolicFeasibility(
                specObserved,
                killabilitySummary,
                liftedReachability,
                observableSelection,
                propagation,
                mutationSensitivePath);
        if (!symbolicFeasibility.isEmpty()) {
            summary.put("symbolicFeasibility", symbolicFeasibility);
        }

        Map<String, Object> evidenceQualitySummary = new LinkedHashMap<>();
        evidenceQualitySummary.put("entryLiftedEvidence",
                Boolean.TRUE.equals(liftedReachability.get("enabled")) ? "available" : "not_needed_or_unavailable");
        evidenceQualitySummary.put("observableStrength",
                Boolean.TRUE.equals(quality.get("hasSpecificObservable")) ? "specific_observable_available" : "weak");
        evidenceQualitySummary.put("controllability", killabilitySummary.get("category"));
        evidenceQualitySummary.put("notes", asList(quality.get("weaknesses")));
        summary.put("evidenceQuality", evidenceQualitySummary);

        Map<String, Object> codekbStatus = new LinkedHashMap<>();
        boolean codekbEnabled = Boolean.TRUE.equals(codekb.get("enabled"));
        codekbStatus.put("enabled", codekbEnabled);
        if (!codekbEnabled) {
            codekbStatus.put("status", "DEGRADED");
            codekbStatus.put("warning",
                    "CodeKB structural facts are unavailable for this sample; compile-safe callable evidence may be incomplete.");
            codekbStatus.put("reason", stringValue(codekb.get("reason")));
        } else {
            codekbStatus.put("status", "READY");
        }
        summary.put("codekb", codekbStatus);

        return summary;
    }

    private Map<String, Object> buildCleanGuidance(
            List<Object> specObserved,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> mutationKill,
            Map<String, Object> inputDistinguish,
            Map<String, Object> assertion,
            Map<String, Object> entryControl,
            Map<String, Object> observableSelection,
            Map<String, Object> liftedReachability,
            Map<String, Object> propagation,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> ripExecution,
            Map<String, Object> codekb) {
        Map<String, Object> guidance = new LinkedHashMap<>();
        String primarySink = selectPrimarySink(specObserved, propagation, observableSelection);
        if (shouldPreferReturnOverGenericException(primarySink, specObserved, propagation, observableSelection)) {
            primarySink = "return";
        }
        if ("exception".equals(primarySink) && hasUncontrollableEntryParameters(entryControl)) {
            primarySink = firstNonExceptionSink(specObserved, "state");
        }

        Map<String, Object> testTarget = new LinkedHashMap<>();
        testTarget.put("package", stringValue(testEntryContext.get("testPackage")));
        testTarget.put("imports", asList(testEntryContext.get("requiredImports")));
        testTarget.put("entryInvocationKind", firstNonBlank(stringValue(testEntryContext.get("entryInvocationKind")),
                stringValue(entryGenerationPlan.get("entryInvocationKind"))));
        testTarget.put("receiver", firstNonEmptyMap(asMap(testEntryContext.get("receiver")),
                asMap(entryGenerationPlan.get("receiver"))));
        testTarget.put("invocationPlan", firstNonEmptyMap(asMap(testEntryContext.get("invocationPlan")),
                asMap(entryGenerationPlan.get("invocationPlan"))));
        Map<String, Object> suggestedTestValues = firstNonEmptyMap(asMap(testEntryContext.get("suggestedTestValues")),
                asMap(entryGenerationPlan.get("suggestedTestValues")));
        testTarget.put("arguments", asList(suggestedTestValues.get("exampleArguments")));
        guidance.put("testTarget", testTarget);

        if (!ripExecution.isEmpty()) {
            guidance.put("ripPlan", ripExecution);
        }
        Map<String, Object> apiRoleFacts = asMap(codekb.get("apiRoleFacts"));
        if (!apiRoleFacts.isEmpty()) {
            guidance.put("apiRoleFacts", apiRoleFacts);
        }
        Map<String, Object> rankedCodekb = enrichCodeKbEntryCandidatesWithBindingEvidence(codekb);
        Map<String, Object> codekbCandidates = buildCodeKbCandidateGuidance(rankedCodekb, mutationSensitivePath);
        guidance.putAll(codekbCandidates);

        // Compile-critical API facts are finalized and strictly cross-checked inside
        // EvidenceParse. Downstream Prompt construction must consume these facts as-is
        // and must never repair static/instance or signature information.
        Map<String, Object> publicApi = publicApiOrFallback(testEntryContext, entryGenerationPlan);
        Map<String, Object> compilationFacts = buildCompilationFacts(testTarget, rankedCodekb, publicApi);
        ApiEvidenceConsistencyValidator.validateStrict(publicApi, compilationFacts);
        if (!compilationFacts.isEmpty()) {
            guidance.put("compilationFacts", compilationFacts);
        }
        Map<String, Object> primaryChain = buildPrimaryChain(
                testTarget,
                mutationKill,
                inputDistinguish,
                entryControl,
                observableSelection,
                propagation,
                mutationSensitivePath,
                rankedCodekb);
        if (!primaryChain.isEmpty()) {
            guidance.put("primaryChain", primaryChain);
        }
        Map<String, Object> entrySelection = buildEntrySelection(rankedCodekb, primaryChain);
        if (!entrySelection.isEmpty()) {
            guidance.put("entrySelection", entrySelection);
            Map<String, Object> selectedEntry = asMap(entrySelection.get("selected"));
            if (!selectedEntry.isEmpty()) {
                guidance.put("selectedEntry", selectedEntry);
            }
        }
        Map<String, Object> canonicalCore = buildCanonicalCore(
                primaryChain,
                entrySelection,
                testTarget,
                inputDistinguish,
                entryControl,
                observableSelection,
                propagation,
                mutationSensitivePath,
                ripExecution);
        harmonizeTestTargetWithCanonicalCore(testTarget, canonicalCore);
        harmonizePrimaryChainWithCanonicalCore(primaryChain, canonicalCore);
        if (!canonicalCore.isEmpty()) {
            guidance.put("canonicalCore", canonicalCore);
        }
        List<Object> alternativeChains = buildAlternativeChains(rankedCodekb, primaryChain);
        // Keep the field stable even when no valid fallback witness exists.
        guidance.put("alternativeChains", alternativeChains);

        Map<String, Object> branchReachabilityPlan = asMap(publicApi.get("branchReachabilityPlan"));
        Map<String, Object> observablePlan = harmonizeObservablePlan(
                asMap(publicApi.get("observablePlan")),
                primarySink,
                testEntryContext,
                entryGenerationPlan,
                observableSelection,
                propagation);
        branchReachabilityPlan = harmonizeBranchReachabilityPlan(branchReachabilityPlan, mutationSensitivePath);
        observablePlan = harmonizePathSensitiveObservablePlan(observablePlan, mutationSensitivePath);

        Map<String, Object> setupPlan = new LinkedHashMap<>();
        setupPlan.put("stateSetup", mergeLists(
                sanitizeList(asList(publicApi.get("stateSetupPlan"))),
                summarizeInputTemplates(inputDistinguish.get("preferredConcreteInputs")),
                sanitizeList(asList(inputDistinguish.get("inputConstructionNotes"))),
                sanitizeList(listOf(stringValue(observablePlan.get("setup")))),
                sanitizeList(listOf(stringValue(mutationSensitivePath.get("inputHint"))))));
        setupPlan.put("preconditions", mergeLists(
                summarizeConstraintList(inputDistinguish.get("distinguishingConstraints")),
                sanitizeList(asList(liftedReachability.get("entryToMutationConditions"))),
                sanitizeList(listOf(stringValue(branchReachabilityPlan.get("condition")))),
                sanitizeList(asList(mutationSensitivePath.get("requiredPathPredicates")))));
        setupPlan.put("bindings", sanitizeList(asList(entryControl.get("entryToMutationBindings"))));
        setupPlan.put("avoid", firstNonEmptyList(sanitizeList(asList(assertion.get("antiPatterns"))),
                sanitizeList(asList(asMap(entryGenerationPlan.get("assertionPlan")).get("antiPatterns")))));
        guidance.put("setupPlan", setupPlan);

        Map<String, Object> assertionPlan = new LinkedHashMap<>();
        assertionPlan.put("requiredToKill", mergeLists(
                sanitizeList(listOf(
                        stringValue(mutationKill.get("reachConditionSummary")),
                        stringValue(mutationKill.get("infectionConditionSummary")),
                        stringValue(mutationKill.get("propagationTargetSummary")),
                        stringValue(mutationSensitivePath.get("recommendedTestShape")))),
                requiredKillSteps(primarySink, testEntryContext, observableSelection, propagation)));
        assertionPlan.put("optionalChecks", summarizeAssertionItems(assertion.get("secondaryAssertions")));
        Map<String, Object> recommendedAssertions = firstNonEmptyMap(assertion,
                asMap(entryGenerationPlan.get("assertionPlan")));
        recommendedAssertions = harmonizeAssertionPlan(recommendedAssertions, primarySink, testEntryContext,
                observableSelection, propagation);
        assertionPlan.put("recommendedAssertions", recommendedAssertions);
        assertionPlan.put("antiPatterns", firstNonEmptyList(sanitizeList(asList(assertion.get("antiPatterns"))),
                sanitizeList(asList(asMap(entryGenerationPlan.get("assertionPlan")).get("antiPatterns")))));
        guidance.put("assertionPlan", assertionPlan);

        Map<String, Object> reachabilityGuards = buildReachabilityGuardsPlan(
                testTarget,
                setupPlan,
                entryControl,
                liftedReachability,
                mutationSensitivePath,
                observableSelection);
        if (!reachabilityGuards.isEmpty()) {
            guidance.put("reachabilityGuards", reachabilityGuards);
        }

        Map<String, Object> distinguishingInputPlan = buildDistinguishingInputPlan(
                inputDistinguish,
                setupPlan,
                mutationSensitivePath,
                mutationKill);
        if (!distinguishingInputPlan.isEmpty()) {
            guidance.put("distinguishingInputPlan", distinguishingInputPlan);
        }
        harmonizeFinalTestTarget(testTarget, inputDistinguish, mutationSensitivePath);

        Map<String, Object> observableDifferencePlan = buildObservableDifferencePlan(
                specObserved,
                assertionPlan,
                observableSelection,
                propagation,
                mutationKill,
                entryControl);
        if (!observableDifferencePlan.isEmpty()) {
            guidance.put("observableDifferencePlan", observableDifferencePlan);
        }

        Map<String, Object> entryChainPlan = buildEntryChainPlan(
                testTarget,
                setupPlan,
                entryControl,
                liftedReachability,
                mutationSensitivePath,
                observableSelection);
        if (!entryChainPlan.isEmpty()) {
            guidance.put("entryChainPlan", entryChainPlan);
        }

        Map<String, Object> symbolicRipPlan = buildSymbolicRipPlan(
                specObserved,
                testTarget,
                setupPlan,
                assertionPlan,
                observableSelection,
                liftedReachability,
                propagation,
                entryControl,
                mutationSensitivePath);
        if (!symbolicRipPlan.isEmpty()) {
            guidance.put("symbolicRipPlan", symbolicRipPlan);
        }

        if (!publicApi.isEmpty()) {
            publicApi.put("observablePlan", observablePlan);
            guidance.put("publicApi", publicApi);
        }
        Map<String, Object> indirectEntryTestPlan = buildIndirectEntryTestPlan(
                specObserved,
                testTarget,
                setupPlan,
                assertionPlan,
                entryControl,
                observableSelection,
                liftedReachability,
                propagation,
                mutationSensitivePath,
                ripExecution);
        if (!indirectEntryTestPlan.isEmpty()) {
            guidance.put("indirectEntryTestPlan", indirectEntryTestPlan);
        }
        return guidance;
    }

    private Map<String, Object> enrichCodeKbEntryCandidatesWithBindingEvidence(Map<String, Object> codekb) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(codekb == null
                ? Collections.<String, Object>emptyMap()
                : codekb);
        List<Object> candidates = sanitizeList(asList(out.get("candidateEntries")));
        if (candidates.isEmpty()) {
            return out;
        }
        EntryBindingAnalyzer.Context ctx = new EntryBindingAnalyzer.Context();
        ctx.originJavaFile = SRC_FILE_P;
        ctx.ownerClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;
        ctx.diff = Diff;
        ctx.semanticOriginalExpression = firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression,
                mutationOriginalExpression());
        ctx.semanticMutantExpression = firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression,
                mutationMutatedExpression());
        List<Map<String, Object>> ranked = EntryBindingAnalyzer.rankCandidates(ctx, candidates);
        if (!ranked.isEmpty()) {
            out.put("candidateEntries", new ArrayList<Object>(ranked));
        }
        return out;
    }

    private Map<String, Object> buildEntrySelection(Map<String, Object> codekb, Map<String, Object> primaryChain) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> candidates = sanitizeList(asList(codekb.get("candidateEntries")));
        Map<String, Object> selected = candidates.isEmpty()
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(asMap(candidates.get(0)));
        if (selected.isEmpty()) {
            selected = new LinkedHashMap<String, Object>(asMap(primaryChain.get("entry")));
        }
        if (!selected.isEmpty()) {
            Map<String, Object> selectedOut = new LinkedHashMap<String, Object>();
            selectedOut.put("signature", firstNonBlank(
                    stringValue(selected.get("signature")),
                    stringValue(selected.get("methodSignature")),
                    stringValue(selected.get("entryMethodSignature"))));
            putIfNotEmpty(selectedOut, "kind", firstNonBlank(
                    stringValue(selected.get("kind")),
                    stringValue(selected.get("entryKind")),
                    "CALLABLE_ENTRY"));
            selectedOut.put("reachability", firstNonBlank(stringValue(selected.get("reachability")), "UNKNOWN"));
            selectedOut.put("constraintStatus", firstNonBlank(stringValue(selected.get("constraintStatus")), "UNKNOWN"));
            selectedOut.put("controllabilityCoverage", selected.get("controllabilityCoverage"));
            selectedOut.put("independentControllability", selected.get("independentControllability"));
            selectedOut.put("entryRankingScore", selected.get("entryRankingScore"));
            selectedOut.put("score", selected.get("score"));
            selectedOut.put("beginLine", selected.get("beginLine"));
            selectedOut.put("endLine", selected.get("endLine"));
            selectedOut.put("static", selected.get("static"));
            selectedOut.put("constructor", selected.get("constructor"));
            putIfNotEmpty(selectedOut, "returnType", stringValue(selected.get("returnType")));
            putIfNotEmpty(selectedOut, "reason", stringValue(selected.get("reason")));
            putIfNotEmpty(selectedOut, "priority", stringValue(selected.get("priority")));
            Map<String, Object> bindingAnalysis = asMap(selected.get("entryBindingAnalysis"));
            selectedOut.put("bindingAnalysis", bindingAnalysis);
            selectedOut.put("confidence", firstNonBlank(
                    stringValue(selected.get("analysisConfidence")),
                    stringValue(bindingAnalysis.get("confidence")),
                    "0.0"));
            out.put("selected", selectedOut);
        }

        List<Object> alternatives = new ArrayList<Object>();
        String selectedSignature = firstNonBlank(
                stringValue(selected.get("signature")),
                stringValue(selected.get("methodSignature")),
                stringValue(selected.get("entryMethodSignature")));
        int rank = 2;
        for (Object item : candidates) {
            Map<String, Object> candidate = asMap(item);
            String signature = firstNonBlank(
                    stringValue(candidate.get("signature")),
                    stringValue(candidate.get("methodSignature")),
                    stringValue(candidate.get("entryMethodSignature")));
            if (isBlank(signature) || signature.equals(selectedSignature)) {
                continue;
            }
            Map<String, Object> alt = new LinkedHashMap<String, Object>();
            alt.put("rank", rank++);
            alt.put("signature", signature);
            putIfNotEmpty(alt, "kind", firstNonBlank(stringValue(candidate.get("entryKind")), "CALLABLE_ENTRY"));
            alt.put("reachability", firstNonBlank(stringValue(candidate.get("reachability")), "UNKNOWN"));
            alt.put("constraintStatus", firstNonBlank(stringValue(candidate.get("constraintStatus")), "UNKNOWN"));
            alt.put("controllabilityCoverage", candidate.get("controllabilityCoverage"));
            alt.put("independentControllability", candidate.get("independentControllability"));
            alt.put("entryRankingScore", candidate.get("entryRankingScore"));
            alt.put("score", candidate.get("score"));
            putIfNotEmpty(alt, "priority", stringValue(candidate.get("priority")));
            putIfNotEmpty(alt, "reason", stringValue(candidate.get("reason")));
            alt.put("bindingAnalysis", asMap(candidate.get("entryBindingAnalysis")));
            alt.put("confidence", firstNonBlank(
                    stringValue(candidate.get("analysisConfidence")),
                    stringValue(asMap(candidate.get("entryBindingAnalysis")).get("confidence")),
                    "0.0"));
            alternatives.add(alt);
            if (alternatives.size() >= 3) {
                break;
            }
        }
        out.put("alternatives", alternatives);
        return out;
    }

    private static String canonicalControllabilitySummary(Map<String, Object> selectedEntry,
                                                          String fallbackSummary) {
        String reachability = stringValue(selectedEntry == null ? null : selectedEntry.get("reachability"));
        String constraintStatus = stringValue(selectedEntry == null ? null : selectedEntry.get("constraintStatus"));
        double coverage = numericValue(selectedEntry == null ? null : selectedEntry.get("controllabilityCoverage"));
        double confidence = numericValue(firstNonNull(
                selectedEntry == null ? null : selectedEntry.get("analysisConfidence"),
                selectedEntry == null ? null : selectedEntry.get("confidence")));

        if ("UNSAT".equalsIgnoreCase(constraintStatus)) {
            return "BLOCKED";
        }
        if ("UNREACHABLE".equalsIgnoreCase(reachability)) {
            return "UNREACHABLE";
        }
        if ("UNKNOWN".equalsIgnoreCase(reachability)
                || (coverage <= 0.0d && confidence <= 0.0d)) {
            return "UNKNOWN";
        }
        if ("REACHABLE".equalsIgnoreCase(reachability)) {
            if (coverage >= 0.80d && confidence >= 0.50d) {
                return "STRONG";
            }
            if (coverage > 0.0d || confidence > 0.0d) {
                return "PARTIAL";
            }
        }
        return firstNonBlank(fallbackSummary, "UNKNOWN");
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static double numericValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private Map<String, Object> buildCanonicalCore(
            Map<String, Object> primaryChain,
            Map<String, Object> entrySelection,
            Map<String, Object> testTarget,
            Map<String, Object> inputDistinguish,
            Map<String, Object> entryControl,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> ripExecution) {
        Map<String, Object> core = new LinkedHashMap<String, Object>();

        Map<String, Object> selectedEntry = asMap(entrySelection.get("selected"));
        Map<String, Object> primaryEntry = asMap(primaryChain.get("entry"));
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        putIfNotEmpty(entry, "signature", firstNonBlank(
                stringValue(selectedEntry.get("signature")),
                stringValue(primaryEntry.get("signature")),
                stringValue(asMap(testTarget.get("invocationPlan")).get("entryMethodSignature"))));
        putIfNotEmpty(entry, "kind", firstNonBlank(
                stringValue(selectedEntry.get("kind")),
                stringValue(primaryEntry.get("kind"))));
        putIfNotEmpty(entry, "invocationKind", stringValue(testTarget.get("entryInvocationKind")));
        Map<String, Object> controllability = new LinkedHashMap<String, Object>();
        putIfNotEmpty(controllability, "summary", canonicalControllabilitySummary(
                selectedEntry,
                firstNonBlank(
                        stringValue(entryControl.get("controllabilitySummary")),
                        stringValue(asMap(entryControl.get("controllability")).get("overall")))));
        putIfNotEmpty(controllability, "constraintStatus", stringValue(selectedEntry.get("constraintStatus")));
        controllability.put("coverage", selectedEntry.get("controllabilityCoverage"));
        controllability.put("independentCoverage", selectedEntry.get("independentControllability"));
        putIfNotEmpty(controllability, "confidence", stringValue(selectedEntry.get("confidence")));
        Map<String, Object> bindingAnalysis = asMap(selectedEntry.get("bindingAnalysis"));
        if (!bindingAnalysis.isEmpty()) {
            controllability.put("bindings", bindingAnalysis.get("bindings"));
            controllability.put("externalSources", bindingAnalysis.get("externalSources"));
        }
        if (!controllability.isEmpty()) {
            entry.put("controllability", controllability);
        }
        if (!entry.isEmpty()) {
            core.put("entry", entry);
        }

        Map<String, Object> reachability = new LinkedHashMap<String, Object>();
        List<Object> guards = new ArrayList<Object>();
        for (Object item : sanitizeList(asList(mutationSensitivePath.get("mandatoryGuards")))) {
            Map<String, Object> guard = asMap(item);
            if (!guard.isEmpty()) {
                guards.add(guard);
            }
        }
        for (String predicate : sanitizeStringList(mutationSensitivePath.get("requiredPathPredicates"))) {
            if (containsGuard(guards, predicate)) {
                continue;
            }
            Map<String, Object> guard = structuredGuard(predicate, "SOURCE_DERIVED_PATH_PREDICATE", 0.80d);
            if (!guard.isEmpty()) {
                guards.add(guard);
            }
        }
        if (!guards.isEmpty()) {
            reachability.put("mandatoryGuards", guards);
        }
        reachability.put("hasFalsePolarityGuard",
                Boolean.TRUE.equals(mutationSensitivePath.get("hasFalsePolarityGuard")));
        reachability.put("hasFallthroughGuard",
                Boolean.TRUE.equals(mutationSensitivePath.get("hasFallthroughGuard")));
        boolean unreachableByTerminator =
                Boolean.TRUE.equals(mutationSensitivePath.get("unreachableByPrecedingTerminator"));
        boolean controlPathConflict =
                Boolean.TRUE.equals(mutationSensitivePath.get("controlPathConflict"));
        reachability.put("unreachableByPrecedingTerminator", unreachableByTerminator);
        reachability.put("controlPathConflict", controlPathConflict);
        reachability.put("status", unreachableByTerminator
                ? "UNREACHABLE"
                : controlPathConflict ? "CONFLICT" : "RESOLVED");
        reachability.put("confidence", unreachableByTerminator || controlPathConflict ? 0.20d : 0.90d);
        if (!reachability.isEmpty()) {
            core.put("reachability", reachability);
        }

        Map<String, Object> infection = new LinkedHashMap<String, Object>();
        putIfNotEmpty(infection, "localOriginalExpression", firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.localOriginalExpression,
                mutationOriginalExpression()));
        putIfNotEmpty(infection, "localMutantExpression", firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.localMutantExpression,
                mutationMutatedExpression()));
        putIfNotEmpty(infection, "semanticOriginalExpression", firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression,
                stringValue(asMap(primaryChain.get("infection")).get("sourceExpression")),
                mutationOriginalExpression()));
        putIfNotEmpty(infection, "semanticMutantExpression", firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression,
                stringValue(asMap(primaryChain.get("infection")).get("mutantExpression")),
                mutationMutatedExpression()));
        putIfNotEmpty(infection, "semanticKind", firstNonBlank(
                stringValue(ripExecution.get("semanticKind")),
                stringValue(asMap(ripExecution.get("infection")).get("semanticKind")),
                stringValue(mutationSensitivePath.get("classification"))));
        putIfNotEmpty(infection, "semanticContainerKind", stringValue(mutationSensitivePath.get("semanticContainerKind")));
        infection.put("polarityInversionCount", parseInt(mutationSensitivePath.get("polarityInversionCount")));
        List<Object> mandatoryConstraints = summarizeMandatoryConstraints(
                inputDistinguish.get("distinguishingConstraints"));
        if (!mandatoryConstraints.isEmpty()) {
            infection.put("mandatoryConstraints", mandatoryConstraints);
        }
        List<Object> mandatoryCofactors = sanitizeList(asList(mutationSensitivePath.get("mandatoryCofactors")));
        if (!mandatoryCofactors.isEmpty()) {
            infection.put("mandatoryCofactors", mandatoryCofactors);
        }
        if (!infection.isEmpty()) {
            core.put("infection", infection);
        }

        Map<String, Object> observable = new LinkedHashMap<String, Object>();
        Map<String, Object> primaryObservable = asMap(asMap(primaryChain.get("propagation")).get("observable"));
        boolean observableOverride = Boolean.TRUE.equals(observableSelection.get("overrideRecommended"));
        String selectedObservableKind = stringValue(observableSelection.get("preferredObservableKind"));
        String selectedObservableCall = stringValue(observableSelection.get("preferredObservableCall"));
        putIfNotEmpty(observable, "kind", observableOverride
                ? firstNonBlank(selectedObservableKind,
                        stringValue(primaryObservable.get("kind")),
                        stringValue(observableSelection.get("baselineObservableKind")))
                : firstNonBlank(
                        stringValue(primaryObservable.get("kind")),
                        selectedObservableKind,
                        stringValue(observableSelection.get("baselineObservableKind"))));
        putIfNotEmpty(observable, "call", observableOverride
                ? firstNonBlank(selectedObservableCall,
                        stringValue(asMap(primaryChain.get("oracle")).get("observableCall")),
                        stringValue(observableSelection.get("baselineObservableCall")),
                        stringValue(propagation.get("observableSinkExpression")))
                : firstNonBlank(
                        stringValue(asMap(primaryChain.get("oracle")).get("observableCall")),
                        selectedObservableCall,
                        stringValue(observableSelection.get("baselineObservableCall")),
                        stringValue(propagation.get("observableSinkExpression"))));
        putIfNotEmpty(observable, "expression", firstNonBlank(
                stringValue(primaryObservable.get("expression")),
                stringValue(propagation.get("observableSinkExpression"))));
        ObservableDirectness directness = inferObservableDirectness(observable);
        String selectionDirectness = stringValue(observableSelection.get("observableDirectness"));
        String selectionDirectnessReason = stringValue(observableSelection.get("observableDirectnessReason"));
        putIfNotEmpty(observable, "directness", firstNonBlank(selectionDirectness, directness.kind));
        putIfNotEmpty(observable, "directnessReason", firstNonBlank(selectionDirectnessReason, directness.reason));
        observable.put("directnessConfidence", isBlank(selectionDirectness) ? directness.confidence : 0.85d);
        if (!observable.isEmpty()) {
            core.put("observable", observable);
        }

        Map<String, Object> oracle = new LinkedHashMap<String, Object>();
        Map<String, Object> primaryOracle = asMap(primaryChain.get("oracle"));
        putIfNotEmpty(oracle, "assertionMode", firstNonBlank(
                stringValue(primaryOracle.get("assertionKind")),
                stringValue(ripExecution.get("preferredAssertionMode")),
                stringValue(asMap(ripExecution.get("oracle")).get("preferredAssertionMode"))));
        putIfNotEmpty(oracle, "expectedOriginal", stringValue(primaryOracle.get("expectedOriginal")));
        putIfNotEmpty(oracle, "expectedMutantExplanation", stringValue(primaryOracle.get("expectedMutant")));
        oracle.put("expectedOriginalExecutable", isExecutableJavaExpression(
                stringValue(primaryOracle.get("expectedOriginal"))));
        if (!oracle.isEmpty()) {
            core.put("oracle", oracle);
        }

        Map<String, Object> construction = new LinkedHashMap<String, Object>();
        Set<String> nonNullSubjects = requiredNonNullSubjects(inputDistinguish, mutationSensitivePath);
        if (!nonNullSubjects.isEmpty()) {
            construction.put("requiredNonNullSubjects", new ArrayList<String>(nonNullSubjects));
        }
        construction.put("mandatoryBindings", sanitizeList(asList(entryControl.get("entryToMutationBindings"))));
        if (!construction.isEmpty()) {
            core.put("construction", construction);
        }

        return core;
    }

    private static Map<String, Object> structuredGuard(String expression, String source, double confidence) {
        Map<String, Object> guard = new LinkedHashMap<String, Object>();
        GuardFact fact = canonicalGuardFact(expression, true);
        if (isBlank(fact.expression)) {
            return guard;
        }
        guard.put("expression", fact.expression);
        guard.put("requiredEvaluation", Boolean.valueOf(fact.requiredEvaluation));
        guard.put("source", firstNonBlank(source, "SOURCE_DERIVED_PATH_PREDICATE"));
        guard.put("hardness", "MANDATORY");
        guard.put("confidence", confidence);
        return guard;
    }

    private static boolean containsGuard(List<Object> guards, String expression) {
        GuardFact target = canonicalGuardFact(expression, true);
        if (isBlank(target.expression)) {
            return true;
        }
        for (Object item : guards) {
            Map<String, Object> existing = asMap(item);
            GuardFact fact = canonicalGuardFact(
                    stringValue(existing.get("expression")),
                    !existing.containsKey("requiredEvaluation")
                            || Boolean.parseBoolean(String.valueOf(existing.get("requiredEvaluation"))));
            if (target.expression.equals(fact.expression)
                    && target.requiredEvaluation == fact.requiredEvaluation) {
                return true;
            }
        }
        return false;
    }

    private static GuardFact canonicalGuardFact(String rawExpression, boolean requiredEvaluation) {
        String expression = stringValue(rawExpression).trim();
        if (isBlank(expression)) {
            return new GuardFact("", requiredEvaluation);
        }
        try {
            Expression parsed = StaticJavaParser.parseExpression(expression);
            while (parsed.isEnclosedExpr()) {
                parsed = parsed.asEnclosedExpr().getInner();
            }
            if (parsed.isUnaryExpr()
                    && parsed.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                Expression inner = parsed.asUnaryExpr().getExpression();
                while (inner.isEnclosedExpr()) {
                    inner = inner.asEnclosedExpr().getInner();
                }
                return new GuardFact(inner.toString(), !requiredEvaluation);
            }
            return new GuardFact(parsed.toString(), requiredEvaluation);
        } catch (RuntimeException ignored) {
            return new GuardFact(expression, requiredEvaluation);
        }
    }

    private static List<Object> summarizeMandatoryConstraints(Object rawConstraints) {
        List<Object> out = new ArrayList<Object>();
        for (Object item : asList(rawConstraints)) {
            Map<String, Object> constraint = asMap(item);
            if (constraint.isEmpty()) {
                continue;
            }
            String hardness = stringValue(constraint.get("hardness"));
            if (!"MANDATORY".equalsIgnoreCase(hardness)) {
                continue;
            }
            String expression = firstNonBlank(
                    stringValue(constraint.get("constraint")),
                    stringValue(constraint.get("expression")));
            if (!isBlank(expression) && !out.contains(expression)) {
                out.add(expression);
            }
        }
        return out;
    }

    private static final class GuardFact {
        final String expression;
        final boolean requiredEvaluation;

        GuardFact(String expression, boolean requiredEvaluation) {
            this.expression = expression == null ? "" : expression;
            this.requiredEvaluation = requiredEvaluation;
        }
    }

    private static ObservableDirectness inferObservableDirectness(Map<String, Object> observable) {
        String kind = stringValue(observable.get("kind")).toUpperCase(Locale.ROOT);
        String call = firstNonBlank(
                stringValue(observable.get("call")),
                stringValue(observable.get("expression"))).trim();
        String normalizedCall = call.toLowerCase(Locale.ROOT);
        String combined = (kind + " " + call).toUpperCase(Locale.ROOT);
        if (kind.contains("ENTRY_RETURN_VALUE") || kind.equals("RETURN_VALUE") || kind.contains("DIRECT_RETURN")) {
            return new ObservableDirectness("DIRECT_RETURN_VALUE", 0.95d,
                    "Observable is the entry or mutation-sensitive return value.");
        }
        if (combined.contains("EXCEPTION") || combined.contains("THROW")) {
            return new ObservableDirectness("DIRECT_EXCEPTION", 0.90d,
                    "Observable is completion versus a thrown exception on the mutation-sensitive path.");
        }
        if (combined.contains("DIRECT_FIELD")
                || combined.contains("REFLECTION_FIELD")
                || combined.contains("GETTER_DEPENDS_ON_MUTATED_STATE")
                || looksLikeSimpleGetterCall(normalizedCall)) {
            return new ObservableDirectness("DIRECT_FIELD_PROJECTION", 0.85d,
                    "Observable projects a mutation-related field through a getter or explicit field read.");
        }
        if (combined.contains("PREDICATE")
                || combined.contains("BOOLEAN")
                || combined.contains("METHOD_DEPENDS_ON_MUTATED_STATE")
                || looksLikeBooleanObserverCall(normalizedCall)) {
            return new ObservableDirectness("SIMPLE_STATE_OBSERVER", 0.70d,
                    "Observable is a simple state predicate or state-dependent public method.");
        }
        if (combined.contains("TOSTRING")
                || combined.contains("HASH")
                || combined.contains("EQUALS")
                || combined.contains("AGGREGATE")
                || normalizedCall.contains(".tostring(")
                || normalizedCall.contains(".hashcode(")
                || normalizedCall.contains(".equals(")) {
            return new ObservableDirectness("MULTI_FIELD_AGGREGATE", 0.45d,
                    "Observable aggregates multiple state fields, so mutation effect may be diluted.");
        }
        if (!isBlank(call)) {
            return new ObservableDirectness("INDIRECT_OBSERVER", 0.30d,
                    "Observable is available but no direct return, field projection, or simple state dependency was proven.");
        }
        return new ObservableDirectness("UNKNOWN_OBSERVABLE", 0.0d,
                "No concrete observable call or expression was selected.");
    }

    private static boolean looksLikeSimpleGetterCall(String normalizedCall) {
        if (normalizedCall == null || isBlank(normalizedCall)) {
            return false;
        }
        return normalizedCall.matches(".*\\.[a-z_$][\\w$]*\\(\\s*\\).*")
                && (normalizedCall.matches(".*\\.get[a-z0-9_$]*\\(\\s*\\).*")
                || normalizedCall.matches(".*\\.is[a-z0-9_$]*\\(\\s*\\).*"));
    }

    private static boolean looksLikeBooleanObserverCall(String normalizedCall) {
        if (normalizedCall == null || isBlank(normalizedCall)) {
            return false;
        }
        return normalizedCall.matches(".*\\.(contains|matches|startswith|endswith|has[a-z0-9_$]*|can[a-z0-9_$]*)\\s*\\(.*\\).*");
    }

    private static final class ObservableDirectness {
        final String kind;
        final double confidence;
        final String reason;

        ObservableDirectness(String kind, double confidence, String reason) {
            this.kind = kind == null ? "" : kind;
            this.confidence = confidence;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static void harmonizePrimaryChainWithCanonicalCore(Map<String, Object> primaryChain,
                                                               Map<String, Object> canonicalCore) {
        if (primaryChain == null || canonicalCore == null || canonicalCore.isEmpty()) {
            return;
        }
        Map<String, Object> entry = asMap(canonicalCore.get("entry"));
        if (!entry.isEmpty()) {
            Map<String, Object> primaryEntry = ensureChildMap(primaryChain, "entry");
            putIfNotEmpty(primaryEntry, "signature", stringValue(entry.get("signature")));
            putIfNotEmpty(primaryEntry, "kind", stringValue(entry.get("kind")));
            putIfNotEmpty(primaryEntry, "constraintStatus",
                    stringValue(asMap(entry.get("controllability")).get("constraintStatus")));
        }

        Map<String, Object> reachability = asMap(canonicalCore.get("reachability"));
        if (!reachability.isEmpty()) {
            Map<String, Object> primaryReachability = ensureChildMap(primaryChain, "reachability");
            primaryReachability.put("mandatoryGuards", sanitizeList(asList(reachability.get("mandatoryGuards"))));
        }

        Map<String, Object> infection = asMap(canonicalCore.get("infection"));
        if (!infection.isEmpty()) {
            Map<String, Object> primaryInfection = ensureChildMap(primaryChain, "infection");
            putIfNotEmpty(primaryInfection, "sourceExpression",
                    stringValue(infection.get("semanticOriginalExpression")));
            putIfNotEmpty(primaryInfection, "mutantExpression",
                    stringValue(infection.get("semanticMutantExpression")));
            primaryInfection.put("mandatoryCofactors", sanitizeList(asList(infection.get("mandatoryCofactors"))));
        }

        Map<String, Object> observable = asMap(canonicalCore.get("observable"));
        if (!observable.isEmpty()) {
            Map<String, Object> propagation = ensureChildMap(primaryChain, "propagation");
            Map<String, Object> primaryObservable = ensureChildMap(propagation, "observable");
            putIfNotEmpty(primaryObservable, "kind", stringValue(observable.get("kind")));
            putIfNotEmpty(primaryObservable, "expression", firstNonBlank(
                    stringValue(observable.get("call")),
                    stringValue(observable.get("expression"))));
            putIfNotEmpty(primaryObservable, "directness", stringValue(observable.get("directness")));
        }

        Map<String, Object> oracle = asMap(canonicalCore.get("oracle"));
        if (!oracle.isEmpty()) {
            Map<String, Object> primaryOracle = ensureChildMap(primaryChain, "oracle");
            putIfNotEmpty(primaryOracle, "assertionKind", stringValue(oracle.get("assertionMode")));
            putIfNotEmpty(primaryOracle, "observableCall", firstNonBlank(
                    stringValue(observable.get("call")),
                    stringValue(observable.get("expression"))));
            putIfNotEmpty(primaryOracle, "expectedOriginal", stringValue(oracle.get("expectedOriginal")));
            putIfNotEmpty(primaryOracle, "expectedMutant", stringValue(oracle.get("expectedMutantExplanation")));
        }
    }

    private void harmonizeTestTargetWithCanonicalCore(Map<String, Object> testTarget,
                                                       Map<String, Object> canonicalCore) {
        if (testTarget == null || canonicalCore == null || canonicalCore.isEmpty()) {
            return;
        }
        String selectedSignature = stringValue(asMap(canonicalCore.get("entry")).get("signature"));
        if (isBlank(selectedSignature)) {
            return;
        }

        EntryBindingAnalyzer.Context ctx = new EntryBindingAnalyzer.Context();
        ctx.originJavaFile = SRC_FILE_P;
        ctx.ownerClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;
        ctx.diff = Diff;
        ctx.semanticOriginalExpression = firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression,
                mutationOriginalExpression());
        ctx.semanticMutantExpression = firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression,
                mutationMutatedExpression());

        Map<String, Object> sourceFacts = EntryBindingAnalyzer.buildInvocationFacts(ctx, selectedSignature);
        Map<String, Object> invocationPlan = ensureChildMap(testTarget, "invocationPlan");
        putIfNotEmpty(invocationPlan, "entryMethodSignature", selectedSignature);
        putIfNotEmpty(invocationPlan, "selectedEntrySignature", selectedSignature);
        if (!sourceFacts.isEmpty()) {
            putIfNotEmpty(testTarget, "entryInvocationKind", stringValue(sourceFacts.get("entryInvocationKind")));
            invocationPlan.put("receiverRequired", sourceFacts.get("receiverRequired"));
            putIfNotEmpty(invocationPlan, "invocationTemplate", stringValue(sourceFacts.get("invocationTemplate")));
            putIfNotEmpty(invocationPlan, "returnType", stringValue(sourceFacts.get("returnType")));
            invocationPlan.put("sourceDerived", Boolean.TRUE);
            invocationPlan.put("argumentExamples", sanitizeList(asList(sourceFacts.get("argumentExamples"))));
            invocationPlan.put("parameterTypes", sanitizeList(asList(sourceFacts.get("parameterTypes"))));
            putIfNotEmpty(invocationPlan, "notes", stringValue(sourceFacts.get("notes")));
            testTarget.put("arguments", sanitizeList(asList(sourceFacts.get("argumentExamples"))));
        }
    }

    private static boolean isExecutableJavaExpression(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            StaticJavaParser.parseExpression(value);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Map<String, Object> ensureChildMap(Map<String, Object> parent, String key) {
        Map<String, Object> child = asMap(parent.get(key));
        if (child.isEmpty()) {
            child = new LinkedHashMap<String, Object>();
            parent.put(key, child);
        } else if (!(parent.get(key) instanceof Map<?, ?>)) {
            child = new LinkedHashMap<String, Object>(child);
            parent.put(key, child);
        }
        return child;
    }

    @SafeVarargs
    private static <T> List<T> listOf(T... items) {
        if (items == null || items.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.asList(items);
    }

    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (keyValues == null) {
            return out;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Map<String, Object> buildCodeKbCandidateGuidance(Map<String, Object> codekb,
            Map<String, Object> mutationSensitivePath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> entryCandidates = sanitizeList(asList(codekb.get("candidateEntries")));
        if (!entryCandidates.isEmpty()) {
            out.put("entryCandidates", entryCandidates);
            out.put("bestEntryChain", firstEntryChain(entryCandidates));
        }
        List<Object> observableCandidates = sanitizeList(asList(codekb.get("observableCandidates")));
        if (!observableCandidates.isEmpty()) {
            out.put("observableCandidates", observableCandidates);
            out.put("bestObservableChain", firstObservableChain(observableCandidates));
        }
        List<Object> fieldObserverLinks = sanitizeList(asList(codekb.get("fieldObserverLinks")));
        if (!fieldObserverLinks.isEmpty()) {
            out.put("fieldObserverLinks", fieldObserverLinks);
        }
        List<Object> witnesses = sanitizeList(asList(codekb.get("mutantWitnessCandidates")));
        witnesses = enrichWitnessesWithSourceConstraints(witnesses, mutationSensitivePath);
        if (!witnesses.isEmpty()) {
            out.put("distinguishingWitnesses", witnesses);
        }
        return out;
    }

    private List<Object> enrichWitnessesWithSourceConstraints(List<Object> witnesses,
            Map<String, Object> mutationSensitivePath) {
        if (witnesses.isEmpty()) {
            return witnesses;
        }
        Map<String, Object> constraints = buildSourceDerivedWitnessConstraints(mutationSensitivePath);
        if (constraints.isEmpty()) {
            return witnesses;
        }
        List<Object> out = new ArrayList<Object>();
        for (Object item : witnesses) {
            Map<String, Object> witness = new LinkedHashMap<String, Object>(asMap(item));
            witness.put("witnessBindingStatus", "SOURCE_CONSTRAINTS_ONLY");
            witness.put("sourceDerivedConstraints", constraints);
            out.add(witness);
        }
        return out;
    }

    private Map<String, Object> buildSourceDerivedWitnessConstraints(
            Map<String, Object> mutationSensitivePath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        putIfNotEmpty(out, "originalExpression", firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression,
                mutationOriginalExpression()));
        putIfNotEmpty(out, "mutantExpression", firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression,
                mutationMutatedExpression()));
        putIfNotEmpty(out, "diff", Diff);
        out.put("requiredPathPredicates",
                sanitizeStringList(mutationSensitivePath.get("requiredPathPredicates")));
        out.put("criticalSymbols", extractSymbolicVariables(
                sanitizeStringList(mutationSensitivePath.get("requiredPathPredicates")),
                mutationOriginalExpression(),
                mutationMutatedExpression(),
                Diff));
        out.put("reason",
                "Derived from the mutated source expression and path predicates only; no project-specific factory or literal binding is synthesized here.");
        return out;
    }

    private Map<String, Object> buildCompilationFacts(
            Map<String, Object> testTarget,
            Map<String, Object> codekb,
            Map<String, Object> publicApi) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("package", stringValue(testTarget.get("package")));
        out.put("imports", sanitizeList(asList(testTarget.get("imports"))));

        Map<String, Object> compilable = asMap(codekb.get("compilableApiFacts"));
        Map<String, Object> receiver = new LinkedHashMap<String, Object>();
        receiver.put("ownerSimpleName", stringValue(compilable.get("ownerSimpleName")));
        receiver.put("ownerKind", stringValue(compilable.get("ownerKind")));
        receiver.put("ownerVisibility", stringValue(compilable.get("ownerVisibility")));
        receiver.put("ownerInstantiable", compilable.get("ownerInstantiable"));
        receiver.put("allowedStaticFactories", sanitizeList(asList(compilable.get("allowedStaticFactories"))));
        receiver.put("forbiddenCalls", sanitizeList(asList(compilable.get("forbiddenCalls"))));
        out.put("receiver", receiver);

        out.put("exactCallableSignatures", sanitizeList(asList(compilable.get("exactCallableSignatures"))));
        out.put("privateFieldNames", sanitizeList(asList(compilable.get("privateFieldNames"))));
        out.put("compilationGuardrails", sanitizeList(asList(publicApi.get("compilationGuardrails"))));
        return out;
    }

    private Map<String, Object> buildPrimaryChain(
            Map<String, Object> testTarget,
            Map<String, Object> mutationKill,
            Map<String, Object> inputDistinguish,
            Map<String, Object> entryControl,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> codekb) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();

        Map<String, Object> bestEntry = firstEntryChain(sanitizeList(asList(codekb.get("candidateEntries"))));
        if (!bestEntry.isEmpty()) {
            out.put("entry", bestEntry);
        }

        Map<String, Object> reachability = new LinkedHashMap<String, Object>();
        reachability.put("mustSatisfy", sanitizeList(asList(mutationSensitivePath.get("requiredPathPredicates"))));
        reachability.put("receiverState", sanitizeList(asList(asMap(entryControl.get("controllability")).get("receiverStateDependencies"))));
        reachability.put("reason", firstNonBlank(
                stringValue(mutationSensitivePath.get("reason")),
                stringValue(mutationKill.get("reachConditionSummary"))));
        out.put("reachability", reachability);

        Map<String, Object> infection = new LinkedHashMap<String, Object>();
        infection.put("sourceExpression", stringValue(mutationSensitivePath.get("source")));
        infection.put("mutantExpression", firstNonBlank(
                stringValue(mutationSensitivePath.get("mutantSource")),
                mutationMutatedExpression()));
        putIfNotEmpty(infection, "distinguishingPredicate", stringValue(mutationSensitivePath.get("distinguishingPredicate")));
        infection.put("distinguishingConstraints", summarizeConstraintList(inputDistinguish.get("distinguishingConstraints")));
        infection.put("reason", stringValue(mutationKill.get("infectionConditionSummary")));
        out.put("infection", infection);

        Map<String, Object> bestObservable = firstObservableChain(sanitizeList(asList(codekb.get("observableCandidates"))));
        Map<String, Object> propagationChain = new LinkedHashMap<String, Object>();
        propagationChain.put("observable", bestObservable);
        propagationChain.put("chain", sanitizeList(asList(propagation.get("propagationChain"))));
        propagationChain.put("cfgPath", sanitizeList(asList(mutationSensitivePath.get("requiredPathPredicates"))));
        propagationChain.put("dfgPath", sanitizeList(asList(propagation.get("observableSinkExpression"))));
        out.put("propagation", propagationChain);

        Map<String, Object> oracle = new LinkedHashMap<String, Object>();
        oracle.put("assertionKind", stringValue(mutationKill.get("preferredAssertionMode")));
        oracle.put("observableCall", selectConsistentObservableCall(bestObservable, observableSelection, propagation));
        List<Object> witnesses = sanitizeList(asList(codekb.get("mutantWitnessCandidates")));
        if (!witnesses.isEmpty()) {
            Map<String, Object> witness = asMap(witnesses.get(0));
            oracle.put("expectedOriginal", stringValue(witness.get("expectedOriginalOutcome")));
            oracle.put("expectedMutant", stringValue(witness.get("expectedMutantOutcome")));
            oracle.put("assertionSketch", stringValue(witness.get("assertionSketch")));
            out.put("witness", witness);
        }
        out.put("oracle", oracle);
        out.put("invocation", firstNonEmptyMap(asMap(testTarget.get("invocationPlan")), Collections.<String, Object>emptyMap()));
        List<Object> sourceCallPath = buildSourceCallPath();
        if (!sourceCallPath.isEmpty()) {
            out.put("sourceCallPath", sourceCallPath);
        }
        return out;
    }

    private List<Object> buildSourceCallPath() {
        List<Object> out = new ArrayList<Object>();
        String entryMethod = PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR);
        if (!isBlank(entryMethod)) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("kind", "ENTRY_METHOD");
            entry.put("signature", entryMethod);
            entry.put("line", firstMutationLine());
            out.add(entry);
        }

        String source = safeExtractForEntry(SRC_FILE_P, CLASS_NAME_P, METHOD_NAME_SUBSTR);
        if (source == null || isBlank(source) || source.startsWith("<entry source extraction failed:")) {
            return out;
        }

        List<String> sourceGuards = new ArrayList<String>();
        if (MUTATION_SEMANTIC_CONTEXT != null && MUTATION_SEMANTIC_CONTEXT.resolved) {
            sourceGuards.addAll(MUTATION_SEMANTIC_CONTEXT.ancestorGuards);
        }
        List<String> weakSourceGuards = new ArrayList<String>();
        String changed = firstNonBlank(
                MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression,
                mutationOriginalExpression());
        if (sourceGuards.isEmpty()) {
            int mutationIndex = source.indexOf(changed);
            if (mutationIndex < 0) {
                mutationIndex = source.indexOf(mutationMutatedExpression());
            }
            weakSourceGuards.addAll(extractSourceIfConditionsBefore(source, mutationIndex));
        }
        for (String predicate : sourceGuards) {
            Map<String, Object> guard = new LinkedHashMap<String, Object>();
            guard.put("kind", "BRANCH_GUARD");
            guard.put("condition", predicate);
            out.add(guard);
        }
        for (String predicate : weakSourceGuards) {
            Map<String, Object> guard = new LinkedHashMap<String, Object>();
            guard.put("kind", "WEAK_BRANCH_GUARD");
            guard.put("condition", predicate);
            out.add(guard);
        }

        if (!isBlank(changed)) {
            Map<String, Object> mutationSite = new LinkedHashMap<String, Object>();
            mutationSite.put("kind", "MUTATION_SITE");
            mutationSite.put("expression", changed + " => " + firstNonBlank(
                    MUTATION_SEMANTIC_CONTEXT == null ? "" : MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression,
                    mutationMutatedExpression()));
            mutationSite.put("line", firstMutationLine());
            out.add(mutationSite);
        }

        String observableCall = firstNonBlank(
                OBSERVABLE_CALL,
                stringValue(asMap(buildPrimaryObservableFromCodeKb()).get("expression")));
        if (!isBlank(observableCall)) {
            Map<String, Object> observable = new LinkedHashMap<String, Object>();
            observable.put("kind", "OBSERVABLE");
            observable.put("expression", observableCall);
            out.add(observable);
        }
        return out;
    }

    private Map<String, Object> buildPrimaryObservableFromCodeKb() {
        Map<String, Object> codekb = asMap(unwrapAnnotated(CodeKbEvidenceAdapter.loadContext(buildMutationConfigSnapshot())));
        List<Object> observableCandidates = sanitizeList(asList(codekb.get("observableCandidates")));
        return firstObservableChain(observableCandidates);
    }

    private List<String> extractSourceIfConditionsBefore(String source, int mutationIndex) {
        List<String> conditions = new ArrayList<String>();
        if (source == null || isBlank(source)) {
            return conditions;
        }
        int mutationLine = lineAtOffset(source, mutationIndex < 0 ? source.length() : mutationIndex);
        try {
            com.github.javaparser.ast.CompilationUnit cu = StaticJavaParser.parse(source);
            for (com.github.javaparser.ast.stmt.IfStmt ifStmt
                    : cu.findAll(com.github.javaparser.ast.stmt.IfStmt.class)) {
                if (!ifStmt.getBegin().isPresent()
                        || ifStmt.getBegin().get().line >= mutationLine) {
                    continue;
                }
                String condition = ifStmt.getCondition().toString();
                if (!isBlank(condition) && !conditions.contains(condition)) {
                    conditions.add(condition);
                }
            }
        } catch (RuntimeException ignored) {
            // Weak source guards are optional evidence. Do not guess Java control flow
            // with regular expressions when JavaParser cannot parse the source.
        }
        return conditions;
    }

    private static int lineAtOffset(String source, int offset) {
        if (source == null || source.isEmpty()) {
            return 1;
        }
        int limit = Math.max(0, Math.min(offset, source.length()));
        int line = 1;
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private List<Object> buildAlternativeChains(Map<String, Object> codekb, Map<String, Object> primaryChain) {
        List<Object> out = new ArrayList<Object>();
        String primaryEntry = stringValue(asMap(primaryChain.get("entry")).get("signature"));
        String primaryObservable = stringValue(asMap(primaryChain.get("oracle")).get("observableCall"));
        int rank = 2;
        for (Object item : sanitizeList(asList(codekb.get("mutantWitnessCandidates")))) {
            Map<String, Object> candidate = asMap(item);
            String signature = firstNonBlank(
                    stringValue(candidate.get("entryMethodSignature")),
                    stringValue(candidate.get("signature")));
            String observable = firstNonBlank(
                    stringValue(candidate.get("observableMethodSignature")),
                    stringValue(candidate.get("observable")));
            if (isBlank(signature) || signature.equals(primaryEntry)) {
                continue;
            }
            Map<String, Object> alt = new LinkedHashMap<String, Object>();
            alt.put("rank", rank++);
            alt.put("entry", signature);
            alt.put("reason", stringValue(candidate.get("reason")));
            alt.put("observable", firstNonBlank(observable, primaryObservable));
            alt.put("witnessRank", candidate.get("witnessRank"));
            alt.put("predicateChain", candidate.get("predicateChain"));
            out.add(alt);
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private Map<String, Object> publicApiOrFallback(
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan) {
        return firstNonEmptyMap(asMap(testEntryContext.get("publicApi")),
                asMap(entryGenerationPlan.get("publicApi")));
    }

    private Map<String, Object> firstEntryChain(List<Object> entryCandidates) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (entryCandidates.isEmpty()) {
            return out;
        }
        Map<String, Object> first = asMap(entryCandidates.get(0));
        out.put("signature", firstNonBlank(
                stringValue(first.get("signature")),
                stringValue(first.get("methodSignature")),
                stringValue(first.get("entryMethodSignature"))));
        out.put("kind", firstNonBlank(stringValue(first.get("entryKind")), "CALLABLE_ENTRY"));
        out.put("priority", first.get("priority"));
        out.put("score", first.get("score"));
        out.put("reachability", firstNonBlank(stringValue(first.get("reachability")), "UNKNOWN"));
        out.put("constraintStatus", firstNonBlank(stringValue(first.get("constraintStatus")), "UNKNOWN"));
        out.put("controllabilityCoverage", first.get("controllabilityCoverage"));
        out.put("independentControllability", first.get("independentControllability"));
        out.put("entryRankingScore", first.get("entryRankingScore"));
        out.put("entryBindingAnalysis", asMap(first.get("entryBindingAnalysis")));
        out.put("analysisConfidence", first.get("analysisConfidence"));
        out.put("reason", stringValue(first.get("reason")));
        return out;
    }

    private Map<String, Object> firstObservableChain(List<Object> observableCandidates) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> first = Collections.emptyMap();
        for (Object candidate : observableCandidates) {
            Map<String, Object> current = asMap(candidate);
            if (isValidObservable(current)) {
                first = current;
                break;
            }
        }
        if (first.isEmpty()) {
            return out;
        }
        out.put("expression", stringValue(first.get("expression")));
        out.put("kind", stringValue(first.get("observableKind")));
        out.put("priority", first.get("priority"));
        out.put("reason", stringValue(first.get("reason")));
        return out;
    }

    private static boolean isValidObservable(Map<String, Object> observable) {
        if (observable == null || observable.isEmpty()) {
            return false;
        }
        String kind = firstNonBlank(
                stringValue(observable.get("kind")),
                stringValue(observable.get("observableKind"))).toUpperCase(Locale.ROOT);
        if (isBlank(kind)
                || "METHOD_COMPLETION".equals(kind)
                || "NO_OBSERVABLE_PROVEN".equals(kind)
                || "NO_PUBLIC_OBSERVABLE".equals(kind)) {
            return false;
        }
        if ("RETURN_VALUE".equals(kind) || "ENTRY_RETURN_VALUE".equals(kind)) {
            return true;
        }
        return !isBlank(firstNonBlank(
                stringValue(observable.get("expression")),
                stringValue(observable.get("observableCall"))));
    }

    private static String selectConsistentObservableCall(Map<String, Object> bestObservable,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        String kind = firstNonBlank(
                stringValue(bestObservable.get("kind")),
                stringValue(bestObservable.get("observableKind")));
        if ("RETURN_VALUE".equalsIgnoreCase(kind) || "ENTRY_RETURN_VALUE".equalsIgnoreCase(kind)) {
            return firstNonBlank(
                    stringValue(observableSelection.get("baselineObservableCall")),
                    stringValue(propagation.get("observableSinkExpression")),
                    stringValue(bestObservable.get("expression")));
        }
        if (isValidObservable(bestObservable)) {
            return firstNonBlank(
                    stringValue(bestObservable.get("expression")),
                    stringValue(observableSelection.get("preferredObservableCall")),
                    stringValue(propagation.get("observableSinkExpression")));
        }
        return firstNonBlank(
                stringValue(observableSelection.get("baselineObservableCall")),
                stringValue(propagation.get("observableSinkExpression")));
    }

    private Map<String, Object> buildReachabilityGuardsPlan(
            Map<String, Object> testTarget,
            Map<String, Object> setupPlan,
            Map<String, Object> entryControl,
            Map<String, Object> liftedReachability,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> observableSelection) {
        List<String> allConstraints = mergeStringLists(
                sanitizeStringList(setupPlan.get("preconditions")),
                sanitizeStringList(liftedReachability.get("entryToMutationConditions")),
                sanitizeStringList(mutationSensitivePath.get("requiredPathPredicates")));
        List<String> guards = new ArrayList<String>();
        List<String> mustPass = new ArrayList<String>();
        for (String item : allConstraints) {
            if (isFrontDoorGuardConstraint(item)) {
                guards.add(item);
            } else {
                mustPass.add(item);
            }
        }
        List<String> antiPatterns = new ArrayList<String>();
        antiPatterns.add(
                "Do not choose null/default inputs that satisfy only an early guard or exception path before the mutation executes.");
        antiPatterns.add(
                "Do not stop at a front-door exception if the mutation-sensitive behavior is deeper in the normal execution path.");
        antiPatterns.addAll(sanitizeStringList(asMap(testTarget.get("receiver")).get("antiPatterns")));
        boolean frontGuardRisk = "exception".equals(firstNonBlank(
                stringValue(observableSelection.get("preferredObservableKind")),
                stringValue(observableSelection.get("baselineObservableKind"))))
                && !hasUncontrollableEntryParameters(entryControl);

        if (guards.isEmpty() && mustPass.isEmpty() && antiPatterns.isEmpty() && !frontGuardRisk) {
            return Collections.emptyMap();
        }

        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("enabled", true);
        plan.put("entryMode", Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"))
                ? "DIRECT_MUTATION_METHOD"
                : "ENTRY_TO_MUTATION");
        plan.put("mandatoryGuards", sanitizeList(asList(mutationSensitivePath.get("mandatoryGuards"))));
        plan.put("mustPassBeforeMutation", mustPass);
        plan.put("mustAvoidFrontDoorGuards", guards);
        plan.put("hasFalsePolarityGuard", Boolean.TRUE.equals(mutationSensitivePath.get("hasFalsePolarityGuard")));
        plan.put("hasFallthroughGuard", Boolean.TRUE.equals(mutationSensitivePath.get("hasFallthroughGuard")));
        plan.put("unreachableByPrecedingTerminator",
                Boolean.TRUE.equals(mutationSensitivePath.get("unreachableByPrecedingTerminator")));
        plan.put("controlPathConflict", Boolean.TRUE.equals(mutationSensitivePath.get("controlPathConflict")));
        plan.put("frontLoadedExceptionRisk", frontGuardRisk);
        plan.put("antiPatterns", antiPatterns);
        plan.put("reason", firstNonBlank(
                stringValue(liftedReachability.get("liftedObservationBridge")),
                stringValue(mutationSensitivePath.get("reason")),
                "Reachability depends on satisfying the path predicates before the mutation-sensitive block executes."));
        return plan;
    }

    private Map<String, Object> buildDistinguishingInputPlan(
            Map<String, Object> inputDistinguish,
            Map<String, Object> setupPlan,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> mutationKill) {
        List<String> rawConstraints = mergeStringLists(
                summarizeConstraintList(inputDistinguish.get("distinguishingConstraints")),
                sanitizeStringList(setupPlan.get("preconditions")),
                sanitizeStringList(mutationSensitivePath.get("requiredPathPredicates")));
        List<String> constraints = normalizeDistinguishingConstraints(rawConstraints);
        List<String> boundaryFamilies = sanitizeStringList(inputDistinguish.get("boundaryValueFamilies"));
        List<String> concreteInputs = mergeStringLists(
                summarizeInputTemplates(inputDistinguish.get("preferredConcreteInputs")),
                sanitizeStringList(inputDistinguish.get("inputConstructionNotes")),
                sanitizeStringList(setupPlan.get("stateSetup")));
        concreteInputs = removeWeakDefaultConcreteInputs(concreteInputs, constraints);
        List<String> antiPatterns = new ArrayList<String>();
        antiPatterns.add(
                "Do not use only null, zero, empty, or single-character defaults unless the distinguishing constraints explicitly require them.");
        antiPatterns.add(
                "Do not claim a boundary test kills the mutant unless the chosen boundary changes original-vs-mutant control flow or output.");
        antiPatterns.addAll(sanitizeStringList(inputDistinguish.get("antiPatterns")));

        if (constraints.isEmpty() && boundaryFamilies.isEmpty() && concreteInputs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("enabled", true);
        plan.put("distinguishingConstraints", constraints);
        plan.put("boundaryValueFamilies", boundaryFamilies);
        plan.put("preferredConcreteInputs", concreteInputs);
        plan.put("antiPatterns", antiPatterns);
        plan.put("mustDifferentiateOriginalVsMutant", true);
        plan.put("reason", firstNonBlank(
                stringValue(mutationSensitivePath.get("inputHint")),
                stringValue(mutationKill.get("infectionConditionSummary")),
                "Inputs must be chosen to make original and mutant behavior diverge, not merely to reach the method."));
        return plan;
    }

    private static void harmonizeFinalTestTarget(Map<String, Object> testTarget,
                                                 Map<String, Object> inputDistinguish,
                                                 Map<String, Object> mutationSensitivePath) {
        Set<String> requiredNonNull = requiredNonNullSubjects(inputDistinguish, mutationSensitivePath);
        if (requiredNonNull.isEmpty() || testTarget == null || testTarget.isEmpty()) {
            return;
        }
        for (Object raw : asList(testTarget.get("arguments"))) {
            Map<String, Object> argument = asMap(raw);
            String name = stringValue(argument.get("name"));
            if (isBlank(name) || !requiredNonNull.contains(name)) {
                continue;
            }
            if ("null".equals(stringValue(argument.get("exampleValue")).trim())) {
                argument.put("exampleValue", "");
                argument.put("requiredProperties", listOf("NON_NULL"));
                argument.put("constructionStatus", "REQUIRES_NON_NULL_BINDING");
            }
        }
        Map<String, Object> invocationPlan = asMap(testTarget.get("invocationPlan"));
        String invocation = stringValue(invocationPlan.get("invocationTemplate"));
        if (containsConflictingNullBinding(invocation, requiredNonNull)) {
            invocationPlan.put("setupStatus", "REQUIRES_CONSTRAINT_BINDING");
            invocationPlan.put("notes", appendNote(stringValue(invocationPlan.get("notes")),
                    "A null argument example conflicts with mandatory RIP NON_NULL constraints; keep the invocation shape but bind a non-null value."));
        }
        Map<String, Object> receiver = asMap(testTarget.get("receiver"));
        String setup = stringValue(receiver.get("setupTemplate"));
        if (containsConflictingNullBinding(setup, requiredNonNull)) {
            receiver.put("setupStatus", "REQUIRES_CONSTRAINT_BINDING");
            receiver.put("notes", appendNote(stringValue(receiver.get("notes")),
                    "Receiver setup contains a null example that must be replaced only when a valid project API binding is available."));
        }
    }

    private static Set<String> requiredNonNullSubjects(Map<String, Object> inputDistinguish,
                                                       Map<String, Object> mutationSensitivePath) {
        Set<String> out = new LinkedHashSet<String>();
        collectRequiredNonNullFromConstraints(inputDistinguish == null ? null : inputDistinguish.get("distinguishingConstraints"), out);
        collectRequiredNonNullFromConstraints(mutationSensitivePath == null ? null : mutationSensitivePath.get("requiredPathPredicates"), out);
        return out;
    }

    private static void collectRequiredNonNullFromConstraints(Object raw, Set<String> out) {
        for (Object item : asList(raw)) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                String property = stringValue(map.get("requiredInputProperty"));
                if ("NON_NULL".equalsIgnoreCase(property)) {
                    String subject = firstIdentifier(stringValue(map.get("subjectExpression")));
                    if (!isBlank(subject)) {
                        out.add(subject);
                    }
                }
                String constraint = stringValue(map.get("constraint"));
                addSimpleNonNullSubject(constraint, out);
            } else {
                addSimpleNonNullSubject(stringValue(item), out);
            }
        }
    }

    private static void addSimpleNonNullSubject(String text, Set<String> out) {
        Matcher matcher = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*!=\\s*null\\b").matcher(text == null ? "" : text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    private static String firstIdentifier(String text) {
        Matcher matcher = Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$]*\\b").matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "";
    }

    private static boolean containsConflictingNullBinding(String text, Set<String> requiredNonNull) {
        String value = text == null ? "" : text;
        if (!value.contains("null")) {
            return false;
        }
        for (String subject : requiredNonNull) {
            if (!isBlank(subject) && value.matches("(?s).*\\b" + Pattern.quote(subject) + "\\b.*null.*")) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalizeDistinguishingConstraints(List<String> rawConstraints) {
        List<String> normalized = new ArrayList<String>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        List<String> frontGuards = new ArrayList<String>();
        for (String item : rawConstraints == null ? Collections.<String>emptyList() : rawConstraints) {
            String text = item == null ? "" : item.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (isFrontDoorGuardConstraint(text)) {
                frontGuards.add(text);
                continue;
            }
            if (seen.add(text)) {
                normalized.add(text);
            }
        }
        if (!frontGuards.isEmpty()) {
            normalized.add(0, "Avoid front-door guards before the mutation: " + String.join(" ; ", frontGuards));
        }
        return normalized;
    }

    private List<String> removeWeakDefaultConcreteInputs(List<String> concreteInputs, List<String> constraints) {
        List<String> inputs = concreteInputs == null ? Collections.<String>emptyList() : concreteInputs;
        if (!hasFrontDoorGuardRisk(constraints)) {
            return inputs;
        }
        List<String> filtered = new ArrayList<String>();
        for (String input : inputs) {
            String text = input == null ? "" : input.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (isWeakDefaultConcreteInput(text)) {
                continue;
            }
            filtered.add(text);
        }
        return filtered.isEmpty() ? inputs : filtered;
    }

    private boolean isWeakDefaultConcreteInput(String text) {
        String lower = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return true;
        }
        return lower.equals("null")
                || lower.equals("0")
                || lower.equals("false")
                || lower.equals("\"\"")
                || lower.contains("default")
                || lower.contains("empty");
    }

    private boolean hasFrontDoorGuardRisk(Collection<String> constraints) {
        for (String constraint : constraints == null ? Collections.<String>emptyList() : constraints) {
            if (isFrontDoorGuardConstraint(constraint)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildObservableDifferencePlan(
            List<Object> specObserved,
            Map<String, Object> assertionPlan,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> mutationKill,
            Map<String, Object> entryControl) {
        String primarySink = selectPrimarySink(specObserved, propagation, observableSelection);
        if (shouldPreferReturnOverGenericException(primarySink, specObserved, propagation, observableSelection)) {
            primarySink = "return";
        }
        if ("exception".equals(primarySink) && hasUncontrollableEntryParameters(entryControl)) {
            primarySink = firstNonExceptionSink(specObserved, "state");
        }
        List<String> antiPatterns = new ArrayList<String>();
        antiPatterns.add(
                "Do not stop at assertNotNull, object identity, or mere successful execution when a stronger observable is available.");
        antiPatterns.add(
                "Do not use a front-door exception oracle when the preferred observable is a return value, output sink, or post-state.");
        antiPatterns.addAll(sanitizeStringList(assertionPlan.get("antiPatterns")));

        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("enabled", true);
        plan.put("primarySink", primarySink);
        plan.put("preferredObservableKind", preferredObservableKind(primarySink, observableSelection, propagation));
        plan.put("preferredObservableCall", preferredObservableCall(primarySink, Collections.emptyMap(),
                Collections.emptyMap(), observableSelection, propagation));
        plan.put("baselineObservableCall", stringValue(observableSelection.get("baselineObservableCall")));
        plan.put("requiredAssertionMode", firstNonBlank(
                stringValue(mutationKill.get("preferredAssertionMode")),
                "ASSERT_MUTATION_SENSITIVE_DIFFERENCE"));
        plan.put("antiPatterns", antiPatterns);
        plan.put("avoidWeakChecks", true);
        plan.put("reason", preferredObservableReason(primarySink, observableSelection, propagation));
        return plan;
    }

    private Map<String, Object> buildEntryChainPlan(
            Map<String, Object> testTarget,
            Map<String, Object> setupPlan,
            Map<String, Object> entryControl,
            Map<String, Object> liftedReachability,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> observableSelection) {
        boolean sameEntryAndMutation = Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"));
        List<String> invocationSequence = new ArrayList<String>();
        invocationSequence
                .add("Construct the receiver and apply required setup before invoking the callable test entry.");
        if (!sameEntryAndMutation) {
            invocationSequence.add(
                    "Reach the mutation only through the resolved public entry/call-chain; do not call helper or private methods directly.");
        }
        invocationSequence.addAll(sanitizeStringList(mutationSensitivePath.get("recommendedSequence")));
        String observableCall = firstNonBlank(
                stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(observableSelection.get("baselineObservableCall")));
        if (!isBlank(observableCall)) {
            invocationSequence.add("Use the final observation call as the kill oracle: " + observableCall);
        }

        List<String> prohibitedShortcuts = new ArrayList<String>();
        prohibitedShortcuts.add("Do not bypass the resolved public entry with direct private/helper calls.");
        prohibitedShortcuts.add(
                "Do not replace the real receiver/setup chain with null placeholders or impossible synthetic state.");
        prohibitedShortcuts.addAll(sanitizeStringList(setupPlan.get("avoid")));

        if (sameEntryAndMutation && invocationSequence.size() <= 2 && prohibitedShortcuts.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("enabled", true);
        plan.put("directMutationMethod", sameEntryAndMutation);
        plan.put("requiredPublicEntry", stringValue(testTarget.get("entryInvocationKind")));
        plan.put("entryRelation", sameEntryAndMutation ? "DIRECT" : "INDIRECT_PUBLIC_CHAIN");
        plan.put("requiresRealEntryChain", !sameEntryAndMutation);
        plan.put("invocationSequence", invocationSequence);
        plan.put("prohibitedShortcuts", prohibitedShortcuts);
        plan.put("reason", firstNonBlank(
                stringValue(liftedReachability.get("liftedObservationBridge")),
                stringValue(mutationSensitivePath.get("reason")),
                "The mutation is best exposed by preserving the real public entry and call-chain."));
        return plan;
    }

    private Map<String, Object> buildIndirectEntryTestPlan(
            List<Object> specObserved,
            Map<String, Object> testTarget,
            Map<String, Object> setupPlan,
            Map<String, Object> assertionPlan,
            Map<String, Object> entryControl,
            Map<String, Object> observableSelection,
            Map<String, Object> liftedReachability,
            Map<String, Object> propagation,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> ripExecution) {
        boolean sameEntryAndMutation = Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"));
        if (sameEntryAndMutation) {
            return Collections.emptyMap();
        }

        String entryControlType = deriveIndirectEntryControlType(entryControl);
        boolean requiresStateShaping = !"INDIRECT_BUT_CONTROLLABLE".equals(entryControlType)
                || !sanitizeStringList(entryControl.get("receiverStateDependencies")).isEmpty();
        boolean requiresInvocationSequence = deriveRequiresInvocationSequence(
                testTarget, setupPlan, assertionPlan, propagation, ripExecution);

        List<String> stateShapingTargets = new ArrayList<String>();
        stateShapingTargets.addAll(sanitizeStringList(entryControl.get("receiverStateDependencies")));
        if (stateShapingTargets.isEmpty()) {
            stateShapingTargets.addAll(inferStateTargetsFromObservableHints(observableSelection, propagation));
        }

        List<String> recommendedInvocationSequence = new ArrayList<String>();
        recommendedInvocationSequence.add(
                "Construct the receiver using testTarget.receiver and setupPlan.stateSetup before invoking the public entry.");
        recommendedInvocationSequence.add(
                "Reach the private/helper mutation only through the resolved public entry call-chain; do not call the helper directly.");
        if (requiresStateShaping) {
            recommendedInvocationSequence.add(
                    "Shape receiver state before the final oracle call: " + joinHumanList(stateShapingTargets) + ".");
        }
        if (requiresInvocationSequence) {
            recommendedInvocationSequence.add(
                    "Use a multi-step public-call sequence when needed: a setup/positioning call followed by a mutation-sensitive observation call.");
        }
        String strongestObservable = firstNonBlank(
                stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(observableSelection.get("baselineObservableCall")),
                stringValue(propagation.get("observableSinkExpression")));
        if (!isBlank(strongestObservable)) {
            recommendedInvocationSequence.add(
                    "Make the final pass/fail decision depend on the strongest observable call: "
                            + strongestObservable);
        }

        List<String> observablePriority = buildIndirectObservablePriority(specObserved, observableSelection,
                propagation);

        List<String> antiPatterns = new ArrayList<String>();
        antiPatterns.add(
                "Do not stop after a single smoke-test public call when the mutation is only indirectly reachable.");
        antiPatterns.add(
                "Do not assert only the first trivial return value if the same value can appear before internal state diverges.");
        antiPatterns.add(
                "Do not call the private/helper mutation method directly unless the evidence explicitly provides a reflection path.");
        antiPatterns.addAll(sanitizeStringList(assertionPlan.get("antiPatterns")));
        antiPatterns.addAll(sanitizeStringList(setupPlan.get("avoid")));

        List<String> candidateInputs = new ArrayList<String>();
        candidateInputs.addAll(sanitizeStringList(setupPlan.get("stateSetup")));
        candidateInputs.addAll(sanitizeStringList(setupPlan.get("preconditions")));
        candidateInputs.addAll(sanitizeStringList(mutationSensitivePath.get("requiredPathPredicates")));
        if (candidateInputs.isEmpty()) {
            candidateInputs.add(
                    "Prefer non-default receiver/setup combinations that move internal cursor/state before the final observable call.");
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("enabled", true);
        plan.put("entryRelation", "INDIRECT_PUBLIC_ENTRY_TO_PRIVATE_MUTATION");
        plan.put("entryControlType", entryControlType);
        plan.put("requiresStateShaping", requiresStateShaping);
        plan.put("requiresInvocationSequence", requiresInvocationSequence);
        plan.put("stateShapingTargets", sanitizeList(stateShapingTargets));
        plan.put("recommendedInvocationSequence", sanitizeList(recommendedInvocationSequence));
        plan.put("observablePriority", sanitizeList(observablePriority));
        plan.put("antiPatterns", sanitizeList(antiPatterns));
        plan.put("candidateDistinguishingInputs", sanitizeList(candidateInputs));
        plan.put("reason", firstNonBlank(
                stringValue(asMap(ripExecution.get("reachability")).get("goal")),
                stringValue(liftedReachability.get("liftedObservationBridge")),
                stringValue(propagation.get("reason")),
                "The mutant is only observable through a public entry that indirectly reaches a private/helper method."));
        return plan;
    }

    private String deriveIndirectEntryControlType(Map<String, Object> entryControl) {
        String overall = firstNonBlank(
                stringValue(entryControl.get("parameterControllability")),
                stringValue(asMap(entryControl.get("controllability")).get("overall")));
        if ("RECEIVER_STATE_DEPENDENT".equalsIgnoreCase(overall)) {
            return "STATE_DERIVED_RECEIVER";
        }
        if (!asList(asMap(entryControl.get("controllability")).get("uncontrollableParameters")).isEmpty()) {
            return "ENTRY_PARAMETER_FIXED";
        }
        return "INDIRECT_BUT_CONTROLLABLE";
    }

    private boolean deriveRequiresInvocationSequence(
            Map<String, Object> testTarget,
            Map<String, Object> setupPlan,
            Map<String, Object> assertionPlan,
            Map<String, Object> propagation,
            Map<String, Object> ripExecution) {
        if (!sanitizeList(asList(asMap(ripExecution.get("propagation")).get("chain"))).isEmpty()) {
            return true;
        }
        if ("MUTABLE_PARAMETER_STATE".equalsIgnoreCase(stringValue(propagation.get("observableSinkKind")))) {
            return true;
        }
        if (sanitizeList(asList(setupPlan.get("stateSetup"))).size() > 1) {
            return true;
        }
        String requiredToKillText = sanitizeList(asList(assertionPlan.get("requiredToKill"))).toString()
                .toLowerCase(Locale.ROOT);
        if (requiredToKillText.contains("after") || requiredToKillText.contains("follow-up")
                || requiredToKillText.contains("sequence")) {
            return true;
        }
        String entryInvocationKind = stringValue(testTarget.get("entryInvocationKind"));
        return entryInvocationKind.toUpperCase(Locale.ROOT).contains("INSTANCE_METHOD_INVOCATION");
    }

    private List<String> inferStateTargetsFromObservableHints(
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        List<String> out = new ArrayList<String>();
        String text = firstNonBlank(
                stringValue(observableSelection.get("overrideReason")),
                stringValue(propagation.get("reason")));
        Matcher matcher = Pattern.compile("\\[([^\\]]+)]").matcher(text);
        while (matcher.find()) {
            String[] parts = matcher.group(1).split(",");
            for (String part : parts) {
                String cleaned = part == null ? "" : part.trim();
                if (!cleaned.isEmpty()) {
                    out.add(cleaned);
                }
            }
        }
        return sanitizeList(out);
    }

    private List<String> buildIndirectObservablePriority(
            List<Object> specObserved,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        List<String> out = new ArrayList<String>();
        String preferredKind = stringValue(observableSelection.get("preferredObservableKind")).toUpperCase(Locale.ROOT);
        String preferredCall = stringValue(observableSelection.get("preferredObservableCall"));
        if (preferredKind.contains("EXCEPTION")) {
            out.add("exception difference via the public entry");
        }
        if (!isBlank(preferredCall)) {
            out.add("sequence-sensitive observable call: " + preferredCall);
        }
        if ("MUTABLE_PARAMETER_STATE".equalsIgnoreCase(stringValue(propagation.get("observableSinkKind")))) {
            out.add("post-call mutable argument state");
        }
        if (asList(specObserved).contains("state")) {
            out.add("state delta after entry invocation");
        }
        if (asList(specObserved).contains("return")) {
            out.add("returned value or returned token sequence");
        }
        if (out.isEmpty()) {
            out.add("public entry observable difference");
        }
        return out;
    }

    private String joinHumanList(List<String> items) {
        List<String> clean = sanitizeList(items);
        if (clean.isEmpty()) {
            return "receiver state";
        }
        return String.join(", ", clean);
    }

    private Map<String, Object> buildCleanEvidence(
            List<Object> domainAssumptions,
            List<Object> specObserved,
            List<Object> jimpleChanges,
            Map<String, Object> dependency,
            Map<String, Object> mutationContext,
            Map<String, Object> codekb,
            Map<String, Object> origin,
            Map<String, Object> mutated,
            Map<String, Object> entryLifted,
            Map<String, Object> entryOrigin,
            Map<String, Object> entryMutated,
            Map<String, Object> propagation,
            Map<String, Object> liftedReachability,
            Map<String, Object> observableSelection,
            Map<String, Object> entryControl,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> mutationSensitivePath,
            Map<String, Object> ripExecution) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        String primarySink = selectPrimarySink(specObserved, propagation, observableSelection);
        if (shouldPreferReturnOverGenericException(primarySink, specObserved, propagation, observableSelection)) {
            primarySink = "return";
        }
        if ("exception".equals(primarySink) && hasUncontrollableEntryParameters(entryControl)) {
            primarySink = firstNonExceptionSink(specObserved, "state");
        }

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("domainAssumptions", domainAssumptions);
        semantic.put("specObserved", specObserved);
        semantic.put("mutationEffect", mapOf(
                "changedPredicate", Diff == null ? "" : Diff,
                "affectedBehavior", stringValue(observableSelection.get("overrideReason"))));
        semantic.put("jimpleChanges", jimpleChanges);
        evidence.put("semantic", semantic);

        Map<String, Object> structural = new LinkedHashMap<>();
        Map<String, Object> enrichedCodekb = new LinkedHashMap<String, Object>(codekb);
        Map<String, Object> codekbHints = buildCodeKbAnalysisHints(
                liftedReachability,
                entryControl,
                observableSelection,
                propagation,
                mutationSensitivePath);
        if (!codekbHints.isEmpty()) {
            enrichedCodekb.put("analysisHints", codekbHints);
        }
        structural.put("dependency", mapOf(
                "relationship", asMap(dependency.get("relationship"))));
        structural.put("codekb", enrichedCodekb);
        evidence.put("structural", structural);

        Map<String, Object> graph = new LinkedHashMap<>();
        Map<String, Object> mutationSite = new LinkedHashMap<>();
        mutationSite.put("origin", origin);
        mutationSite.put("mutated", mutated);
        mutationSite.put("diff", mapOf("jimpleChanges", jimpleChanges));
        graph.put("mutationSite", mutationSite);

        Map<String, Object> entryLiftedGraph = new LinkedHashMap<>();
        entryLiftedGraph.put("reusedFromOrigin", valueFromMap(entryMutated, "reusedFromOrigin"));
        entryLiftedGraph.put("reuseReason", stringValue(entryMutated.get("reuseReason")));
        entryLiftedGraph.put("origin", entryOrigin);
        entryLiftedGraph.put("mutated", entryMutated);
        entryLiftedGraph.put("relation", stripKeys(asMap(entryLifted.get("entryRelation")), Collections.singleton("receiver")));
        graph.put("entryLifted", entryLiftedGraph);
        evidence.put("graph", graph);

        Map<String, Object> reasoning = new LinkedHashMap<>();
        Map<String, Object> alignedReachability = harmonizeReachabilityReasoning(
                liftedReachability,
                primarySink,
                testEntryContext,
                entryGenerationPlan,
                observableSelection,
                propagation);
        Map<String, Object> alignedPropagation = harmonizePropagationReasoning(
                propagation,
                primarySink,
                testEntryContext,
                entryGenerationPlan,
                observableSelection);
        reasoning.put("reachability", mapOf(
                "summary", alignedReachability,
                "keyPredicates", mergeLists(
                        collectPredicates(origin, mutated, entryOrigin, entryMutated),
                        asList(mutationSensitivePath.get("requiredPathPredicates"))),
                "controlDeps", collectControlDeps(origin, mutated, entryOrigin, entryMutated)));
        if (!mutationSensitivePath.isEmpty()) {
            reasoning.put("infection", mapOf(
                    "mutationSensitivePath", mutationSensitivePath));
        }
        if (!ripExecution.isEmpty()) {
            reasoning.put("rip", ripExecution);
        }
        reasoning.put("propagation", mapOf(
                "summary", alignedPropagation,
                "defs", collectDfgField(origin, mutated, entryOrigin, entryMutated, "defs_point"),
                "usesTowardOutput", collectDfgField(origin, mutated, entryOrigin, entryMutated, "uses_toward_output"),
                "killSet", collectDfgField(origin, mutated, entryOrigin, entryMutated, "kill_set")));
        reasoning.put("observability", mapOf(
                "selection", observableSelection,
                "publicObservables",
                asList(asMap(asMap(dependency.get("testEntryContext")).get("publicApi")).get("availablePublicMethods")),
                "parameterControl", entryControl));
        Map<String, Object> symbolic = buildSymbolicReasoning(
                origin,
                mutated,
                entryOrigin,
                entryMutated,
                observableSelection,
                propagation,
                liftedReachability,
                entryControl,
                mutationSensitivePath);
        if (!symbolic.isEmpty()) {
            reasoning.put("symbolic", symbolic);
        }
        evidence.put("reasoning", reasoning);

        return evidence;
    }

    private Map<String, Object> buildCleanDebug(Bundle bundle, Map<String, Object> entryLifted,
            Map<String, Object> quality) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("schemaMode", "clean_root");
        debug.put("entryLiftedWarnings", firstNonEmptyList(asList(asMap(entryLifted.get("origin")).get("warnings")),
                asList(asMap(entryLifted.get("mutated")).get("warnings"))));
        debug.put("qualityNotes", asList(quality.get("notes")));
        debug.put("entrySource", mapOf(
                "origin", safeExtractForEntry(SRC_FILE_P, TEST_ENTRY_CLASS_NAME_P, TEST_ENTRY_METHOD_SUBSTR),
                "mutated", safeExtractForEntry(SRC_FILE_M, TEST_ENTRY_CLASS_NAME_M, TEST_ENTRY_METHOD_SUBSTR)));
        debug.put("mutationSource", mapOf(
                "origin", safeExtractForEntry(SRC_FILE_P, CLASS_NAME_P, METHOD_NAME_SUBSTR),
                "mutated", safeExtractForEntry(SRC_FILE_M, CLASS_NAME_M, METHOD_NAME_SUBSTR)));
        return debug;
    }

    private static Object unwrapAnnotated(Object node) {
        if (node instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) node;
            if (map.containsKey("item") && map.containsKey("comment") && map.size() == 2) {
                return unwrapAnnotated(map.get("item"));
            }
            if (map.containsKey("items") && map.containsKey("comment") && map.size() == 2) {
                return unwrapAnnotated(map.get("items"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("comment".equals(key)) {
                    continue;
                }
                out.put(key, unwrapAnnotated(entry.getValue()));
            }
            return out;
        }
        if (node instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) node;
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                out.add(unwrapAnnotated(item));
            }
            return out;
        }
        return node;
    }

    private static Object normalizeObject(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map<?, ?> || node instanceof Collection<?>) {
            return unwrapAnnotated(node);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            if (node instanceof Bundle.CommentedList<?>) {
                return unwrapAnnotated(mapper.convertValue(node, Map.class));
            }
            if (node instanceof Bundle.Commented<?> || node instanceof Bundle.Side || node instanceof Info.InfoItem
                    || node instanceof CFG || node instanceof DFG) {
                return unwrapAnnotated(mapper.convertValue(node, Map.class));
            }
        } catch (IllegalArgumentException ignored) {
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        if (node instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) node;
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object node) {
        if (node instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) node;
            return new ArrayList<>((Collection<Object>) collection);
        }
        if (node == null) {
            return listOf();
        }
        return listOf(node);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, String value) {
        if (target == null || key == null) {
            return;
        }
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            target.put(key, text);
        }
    }

    private static String firstListValue(Object value) {
        List<Object> values = asList(value);
        for (Object item : values) {
            String s = stringValue(item);
            if (!isBlank(s)) {
                return s;
            }
        }
        return "";
    }

    private static Map<String, Object> firstNonEmptyMap(Map<String, Object> first, Map<String, Object> second) {
        return first.isEmpty() ? second : first;
    }

    private static List<Object> firstNonEmptyList(List<Object> first, List<Object> second) {
        return first.isEmpty() ? second : first;
    }

    private static String deriveKillabilityStatus(String level) {
        String normalized = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("UNLIKELY")) {
            return "blocked_or_equivalent";
        }
        if (normalized.contains("LIKELY")) {
            return "killable";
        }
        if (normalized.contains("EXCEPTION") || normalized.contains("STATE")) {
            return "killable_with_guidance";
        }
        return "blocked_or_equivalent";
    }

    private static boolean isEntryParameterFixed(Map<String, Object> killabilitySummary) {
        return "ENTRY_PARAMETER_FIXED".equalsIgnoreCase(stringValue(killabilitySummary.get("category")));
    }

    private static boolean hasUncontrollableEntryParameters(Map<String, Object> entryControl) {
        Map<String, Object> controllability = asMap(entryControl.get("controllability"));
        if (!asList(controllability.get("uncontrollableParameters")).isEmpty()) {
            return true;
        }
        return "WEAK".equalsIgnoreCase(stringValue(controllability.get("overall")));
    }

    private static String firstNonExceptionSink(List<?> specObserved, String fallback) {
        List<String> preferredOrder = listOf("state", "return");
        Set<String> values = specObserved.stream()
                .map(String::valueOf)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String candidate : preferredOrder) {
            if (values.contains(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private static boolean isExceptionLike(String value) {
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        return text.contains("exception")
                || text.contains("throw")
                || text.contains("thrown")
                || text.contains("assertthrows");
    }

    private static String normalizePrimarySink(List<?> specObserved) {
        List<String> preferredOrder = listOf("exception", "state", "return");
        Set<String> values = specObserved.stream()
                .map(String::valueOf)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String candidate : preferredOrder) {
            if (values.contains(candidate)) {
                return candidate;
            }
        }
        return values.isEmpty() ? "" : values.iterator().next();
    }

    private static String selectPrimarySink(List<?> specObserved,
            Map<String, Object> propagation,
            Map<String, Object> observableSelection) {
        String preferredKind = stringValue(observableSelection.get("preferredObservableKind")).toLowerCase(Locale.ROOT);
        if (isConcreteStateObservableKind(preferredKind)) {
            return "state";
        }
        if (Boolean.TRUE.equals(propagation.get("requiresExceptionObservation"))) {
            return "exception";
        }
        String kind = stringValue(propagation.get("observableSinkKind")).toLowerCase(Locale.ROOT);
        if (isConcreteStateObservableKind(kind)) {
            return "state";
        }
        if (kind.contains("exception")) {
            return "exception";
        }
        if (kind.contains("state")) {
            return "state";
        }
        if (kind.contains("return")) {
            return "return";
        }
        if (preferredKind.contains("exception")) {
            return "exception";
        }
        if (preferredKind.contains("state")) {
            return "state";
        }
        if (preferredKind.contains("return")) {
            return "return";
        }
        return normalizePrimarySink(specObserved);
    }

    private boolean shouldPreferReturnOverGenericException(List<?> specObserved,
            Map<String, Object> propagation,
            Map<String, Object> observableSelection) {
        return shouldPreferReturnOverGenericException(
                selectPrimarySink(specObserved, propagation, observableSelection),
                specObserved,
                propagation,
                observableSelection);
    }

    private boolean shouldPreferReturnOverGenericException(String primarySink,
            List<?> specObserved,
            Map<String, Object> propagation,
            Map<String, Object> observableSelection) {
        if (!"exception".equals(primarySink) || isVoidMutationMethod()) {
            return false;
        }
        Set<String> values = specObserved.stream()
                .map(String::valueOf)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!values.contains("return")) {
            return false;
        }
        if (Boolean.TRUE.equals(propagation.get("requiresExceptionObservation"))) {
            return false;
        }
        String propagationKind = stringValue(propagation.get("observableSinkKind"));
        String preferredKind = stringValue(observableSelection.get("preferredObservableKind"));
        String preferredCall = stringValue(observableSelection.get("preferredObservableCall"));
        return !isExceptionLike(propagationKind)
                && !isExceptionLike(preferredKind)
                && !isExceptionLike(preferredCall);
    }

    private boolean isVoidMutationMethod() {
        String method = METHOD_NAME_SUBSTR == null ? "" : METHOD_NAME_SUBSTR.trim();
        return method.startsWith("void_") || method.startsWith("void ");
    }

    private static boolean isConcreteStateObservableKind(String kind) {
        if (kind == null || isBlank(kind)) {
            return false;
        }
        String lowered = kind.toLowerCase(Locale.ROOT);
        return lowered.contains("mutable_parameter_state")
                || lowered.contains("external_mutable_sink")
                || lowered.contains("external_sink_state")
                || lowered.contains("external_output")
                || lowered.contains("stdout")
                || lowered.contains("stderr")
                || lowered.contains("console_output")
                || lowered.contains("public_getter_observable")
                || lowered.contains("public_method_depends_on_state")
                || lowered.contains("throwable_message")
                || lowered.contains("reflection_field_read")
                || lowered.contains("appendable_content")
                || lowered.contains("writer_content");
    }

    private static List<Object> withoutSink(List<?> specObserved, String primarySink) {
        List<Object> out = new ArrayList<>();
        for (Object item : sanitizeList(specObserved)) {
            if (!stringValue(item).equalsIgnoreCase(primarySink)) {
                out.add(item);
            }
        }
        return out;
    }

    private static String preferredObservableKind(String primarySink,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        if ("exception".equals(primarySink)) {
            return "EXCEPTION_SINK";
        }
        if ("state".equals(primarySink)) {
            String preferred = firstNonBlank(stringValue(observableSelection.get("preferredObservableKind")),
                    stringValue(propagation.get("observableSinkKind")));
            return isExceptionLike(preferred) ? "STATE_SINK" : firstNonBlank(preferred, "STATE_SINK");
        }
        String preferred = firstNonBlank(stringValue(observableSelection.get("preferredObservableKind")),
                stringValue(propagation.get("observableSinkKind")));
        return isExceptionLike(preferred) ? "RETURN_SINK" : firstNonBlank(preferred, "RETURN_SINK");
    }

    private static String preferredObservableCall(String primarySink,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        if ("exception".equals(primarySink)) {
            Map<String, Object> invocationPlan = firstNonEmptyMap(asMap(testEntryContext.get("invocationPlan")),
                    asMap(entryGenerationPlan.get("invocationPlan")));
            return firstNonBlank(stringValue(invocationPlan.get("invocationTemplate")),
                    stringValue(invocationPlan.get("setupTemplate")),
                    stringValue(propagation.get("observableSinkExpression")),
                    "entry invocation");
        }
        String preferredCall = firstNonBlank(stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(observableSelection.get("baselineObservableCall")),
                stringValue(propagation.get("observableSinkExpression")));
        if (!isExceptionLike(preferredCall)) {
            if (!isBlank(preferredCall)) {
                return isDefaultOnlyObservableCall(preferredCall) ? "" : preferredCall;
            }
            if ("return".equals(primarySink)) {
                Map<String, Object> invocationPlan = firstNonEmptyMap(asMap(testEntryContext.get("invocationPlan")),
                        asMap(entryGenerationPlan.get("invocationPlan")));
                String invocation = firstNonBlank(stringValue(invocationPlan.get("invocationTemplate")),
                        stringValue(invocationPlan.get("setupTemplate")));
                return isDefaultOnlyObservableCall(invocation) ? "" : invocation;
            }
        }
        return "";
    }

    private static boolean isDefaultOnlyObservableCall(String call) {
        if (call == null) {
            return false;
        }
        String trimmed = call.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int open = trimmed.indexOf('(');
        int close = trimmed.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return false;
        }
        String args = trimmed.substring(open + 1, close).trim();
        if (args.isEmpty()) {
            return false;
        }
        for (String rawPart : args.split(",")) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (!isDefaultLiteralExpression(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultLiteralExpression(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("(") && value.contains(")")) {
            int close = value.indexOf(')');
            if (close <= 0) {
                break;
            }
            value = value.substring(close + 1).trim();
        }
        if (value.isEmpty()) {
            return false;
        }
        return "0".equals(value)
                || "0.0".equals(value)
                || "0.0f".equals(value)
                || "0d".equals(value)
                || "0l".equals(value)
                || "false".equals(value)
                || "null".equals(value)
                || "\"\"".equals(value)
                || "''".equals(value)
                || "'\\0'".equals(value);
    }

    private static String preferredObservableReason(String primarySink,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        if ("exception".equals(primarySink)) {
            return firstNonBlank(
                    "Prefer exception observation because propagation evidence indicates the strongest divergence is entry completion vs thrown exception.",
                    stringValue(observableSelection.get("overrideReason")),
                    firstListValue(propagation.get("propagationChain")));
        }
        return firstNonBlank(stringValue(observableSelection.get("overrideReason")),
                firstListValue(propagation.get("propagationChain")));
    }

    private Map<String, Object> inferMutationSensitivePath(Map<String, Object> origin,
            Map<String, Object> mutated,
            Map<String, Object> propagation,
            Map<String, Object> observableSelection) {
        if (MUTATION_SEMANTIC_CONTEXT != null
                && MUTATION_SEMANTIC_CONTEXT.resolved
                && !isBlank(MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression)
                && !isBlank(MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            String sourceText = firstNonBlank(stringValue(origin.get("content")), stringValue(mutated.get("content")));
            out.put("source", MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression);
            out.put("mutantSource", MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression);
            out.put("kind", "AST_SEMANTIC_MUTATION");
            putIfNotEmpty(out, "semanticContainerKind", MUTATION_SEMANTIC_CONTEXT.semanticContainerKind);
            out.put("ancestorGuardConstraints", sanitizeList(new ArrayList<Object>(
                    MUTATION_SEMANTIC_CONTEXT.ancestorGuardConstraints)));
            List<Object> predicates = new ArrayList<Object>();
            predicates.addAll(MUTATION_SEMANTIC_CONTEXT.ancestorGuards);
            for (String variable : MUTATION_SEMANTIC_CONTEXT.requiredNonNullVariables) {
                predicates.add(variable + " != null");
            }
            String forced = forcedOriginalEvaluation(
                    MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression,
                    MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression);
            if (!isBlank(forced)) {
                predicates.add(forced);
            }
            ControlPathConstraintExtractor.Result controlPath = extractControlPathConstraints(sourceText, predicates);
            predicates.addAll(guardPredicates(controlPath.mandatoryGuards));
            out.put("requiredPathPredicates", normalizeAndRepairPredicates(
                    sourceText,
                    MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression,
                    predicates));
            out.put("mandatoryGuards", controlPath.mandatoryGuards);
            out.put("mandatoryCofactors", controlPath.mandatoryCofactors);
            out.put("weakFallbackGuards", controlPath.weakFallbackGuards);
            out.put("requiredNonNullSubjects", new ArrayList<String>(controlPath.requiredNonNullSubjects));
            out.put("hasFalsePolarityGuard", controlPath.hasFalsePolarityGuard);
            out.put("hasFallthroughGuard", controlPath.hasFallthroughGuard);
            out.put("unreachableByPrecedingTerminator", controlPath.unreachableByPrecedingTerminator);
            out.put("controlPathConflict", controlPath.controlPathConflict);
            out.put("polarityInversionCount", controlPath.polarityInversionCount);
            out.put("distinguishingPredicate",
                    "(" + MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression + ") != ("
                            + MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression + ")");
            out.put("inputHint", semanticInputHint(MUTATION_SEMANTIC_CONTEXT));
            out.put("preferredObservable", pathSensitivePreferredObservable(
                    firstNonBlank(stringValue(origin.get("content")), stringValue(mutated.get("content"))),
                    observableSelection,
                    propagation));
            out.put("confidence", "high");
            out.put("resolutionMode", MUTATION_SEMANTIC_CONTEXT.resolutionMode);
            out.put("reason", firstNonBlank(MUTATION_SEMANTIC_CONTEXT.reason,
                    "Mutation context was derived from original and mutant ASTs at the mutation line."));
            return out;
        }
        String source = firstNonBlank(stringValue(origin.get("content")), stringValue(mutated.get("content")));
        String changed = mutationOriginalExpression();
        if (isBlank(source) || isBlank(changed)) {
            return new LinkedHashMap<>();
        }

        LinkedHashSet<Object> predicates = new LinkedHashSet<>();
        LinkedHashSet<Object> weakPredicates = new LinkedHashSet<>();
        int mutationIndex = source.indexOf(changed);
        if (mutationIndex < 0) {
            mutationIndex = source.indexOf(firstNonBlank(stringValue(propagation.get("infectionSource")), changed));
        }

        Map<String, Object> ternary = inferTernaryPath(source, changed);
        if (!ternary.isEmpty()) {
            predicates.addAll(asList(ternary.get("requiredPathPredicates")));
        }

        Map<String, Object> readerReadEvidence = LEGACY_EVIDENCE_ENABLED
                ? inferReaderReadArrayEvidence(source, changed)
                : Collections.<String, Object>emptyMap();
        predicates.addAll(asList(readerReadEvidence.get("requiredPathPredicates")));

        String inputHint = firstNonBlank(
                stringValue(readerReadEvidence.get("inputHint")),
                pathSensitiveInputHint(source, predicates));
        String preferredObservable = firstNonBlank(
                stringValue(readerReadEvidence.get("preferredObservable")),
                pathSensitivePreferredObservable(source, observableSelection, propagation));
        if (predicates.isEmpty() && weakPredicates.isEmpty() && isBlank(inputHint) && isBlank(preferredObservable)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        ControlPathConstraintExtractor.Result controlPath = extractControlPathConstraints(source, predicates);
        predicates.addAll(guardPredicates(controlPath.mandatoryGuards));
        out.put("source", changed);
        out.put("kind", firstNonBlank(
                stringValue(readerReadEvidence.get("kind")),
                ternary.isEmpty() ? "PATH_SENSITIVE_MUTATION" : "TERNARY_BRANCH_MUTATION"));
        out.put("requiredPathPredicates", normalizeAndRepairPredicates(source, mutationMutatedExpression(),
                new ArrayList<>(predicates)));
        out.put("weakPathPredicates", normalizeAndRepairPredicates(source, mutationMutatedExpression(),
                new ArrayList<>(weakPredicates)));
        out.put("mandatoryGuards", controlPath.mandatoryGuards);
        out.put("mandatoryCofactors", controlPath.mandatoryCofactors);
        out.put("weakFallbackGuards", controlPath.weakFallbackGuards);
        out.put("requiredNonNullSubjects", new ArrayList<String>(controlPath.requiredNonNullSubjects));
        out.put("hasFalsePolarityGuard", controlPath.hasFalsePolarityGuard);
        out.put("hasFallthroughGuard", controlPath.hasFallthroughGuard);
        out.put("unreachableByPrecedingTerminator", controlPath.unreachableByPrecedingTerminator);
        out.put("controlPathConflict", controlPath.controlPathConflict);
        out.put("polarityInversionCount", controlPath.polarityInversionCount);
        out.put("inputHint", inputHint);
        out.put("preferredObservable", preferredObservable);
        out.put("confidence", firstNonBlank(
                stringValue(readerReadEvidence.get("confidence")),
                ternary.isEmpty() ? "medium" : "high"));
        putIfNotEmpty(out, "classification", stringValue(readerReadEvidence.get("classification")));
        putIfNotEmpty(out, "readerContract", stringValue(readerReadEvidence.get("readerContract")));
        putIfNotEmpty(out, "recommendedTestShape", stringValue(readerReadEvidence.get("recommendedTestShape")));
        putIfNotEmpty(out, "equivalenceRisk", stringValue(readerReadEvidence.get("equivalenceRisk")));
        out.put("reason", firstNonBlank(
                stringValue(readerReadEvidence.get("reason")),
                stringValue(ternary.get("reason")),
                "Mutation expression is guarded by nearby branch predicates; tests must satisfy these predicates before asserting propagation."));
        return out;
    }

    private ControlPathConstraintExtractor.Result extractControlPathConstraints(String source, Collection<?> fallbackGuards) {
        ControlPathConstraintExtractor.Context ctx = new ControlPathConstraintExtractor.Context();
        ctx.source = source == null ? "" : source;
        ctx.mutationOriginalExpression = mutationOriginalExpression();
        ctx.mutationMutantExpression = mutationMutatedExpression();
        ctx.mutationIndex = mutationIndex(ctx.source, ctx.mutationOriginalExpression, ctx.mutationMutantExpression);
        if (MUTATION_SEMANTIC_CONTEXT != null && MUTATION_SEMANTIC_CONTEXT.resolved) {
            ctx.ancestorGuards = new ArrayList<String>(MUTATION_SEMANTIC_CONTEXT.ancestorGuards);
            ctx.ancestorGuardConstraints = new ArrayList<Map<String, Object>>(
                    MUTATION_SEMANTIC_CONTEXT.ancestorGuardConstraints);
            ctx.requiredNonNullVariables = new LinkedHashSet<String>(MUTATION_SEMANTIC_CONTEXT.requiredNonNullVariables);
            ctx.semanticOriginalExpression = MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression;
            ctx.semanticMutantExpression = MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression;
        } else {
            for (Object item : fallbackGuards == null ? Collections.emptyList() : fallbackGuards) {
                String guard = stringValue(item);
                if (!isBlank(guard) && !guard.contains("!=")) {
                    ctx.ancestorGuards.add(guard);
                }
                addSimpleNonNullSubject(guard, ctx.requiredNonNullVariables);
            }
            ctx.semanticOriginalExpression = mutationOriginalExpression();
            ctx.semanticMutantExpression = mutationMutatedExpression();
        }
        return ControlPathConstraintExtractor.extract(ctx);
    }

    private static int mutationIndex(String source, String originalExpression, String mutantExpression) {
        if (source == null || isBlank(source)) {
            return -1;
        }
        int idx = source.indexOf(originalExpression == null ? "" : originalExpression);
        if (idx >= 0) {
            return idx;
        }
        idx = source.indexOf(mutantExpression == null ? "" : mutantExpression);
        return idx;
    }

    private static List<Object> guardPredicates(List<Map<String, Object>> guards) {
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> guard : guards == null ? Collections.<Map<String, Object>>emptyList() : guards) {
            String expression = stringValue(guard.get("expression"));
            if (isBlank(expression)) {
                continue;
            }
            boolean required = !guard.containsKey("requiredEvaluation")
                    || Boolean.TRUE.equals(guard.get("requiredEvaluation"))
                    || "true".equalsIgnoreCase(stringValue(guard.get("requiredEvaluation")));
            out.add(required ? expression : "!(" + expression + ")");
        }
        return out;
    }

    private Map<String, Object> inferReaderReadArrayEvidence(String source, String changedExpression) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isReaderArrayReadImplementation(source)) {
            return out;
        }

        String diff = normalizeTokenText(Diff);
        LinkedHashSet<Object> predicates = new LinkedHashSet<>();
        out.put("kind", "READER_ARRAY_READ_STATE_MACHINE");
        out.put("readerContract",
                "Reader.read(char[], int, int) may return -1 at EOF, 0 for a zero-length read or custom Reader, and a positive count after copying characters.");

        if (diff.contains("lastChar=>-lastChar") || diff.contains("lastChar=>~lastChar")) {
            predicates.add("boundary read uses previous-character state");
            predicates.add("current iteration depends on the first position in the buffer");
            predicates.add("line-separator transition must be exercised");
            out.put("classification", "killable");
            out.put("inputHint",
                    "Use an input that exercises the boundary branch where the current read depends on prior state rather than the current buffer slot.");
            out.put("recommendedTestShape",
                    "Assert the observable state differs only when the boundary branch is reached on the second step.");
            out.put("reason",
                    "The mutation affects a state-dependent branch. A same-path input that never reaches the boundary case will not distinguish original and mutant.");
        } else if (diff.contains("lastChar=>lastChar++") || diff.contains("lastChar=>lastChar--")) {
            predicates.add("previous-character state is observed");
            predicates.add("boundary branch is reached with a positive-length read");
            out.put("classification", "likely_equivalent");
            out.put("equivalenceRisk",
                    "The post-increment/decrement expression returns the original lastChar value, and the side effect is overwritten later by lastChar = buf[offset + len - 1] on the same len > 0 path.");
            out.put("reason", "The apparent state mutation is killed by a later assignment before public observation.");
        } else if (diff.contains("length=>-length") && source.contains("length == 0")) {
            predicates.add("length == 0");
            out.put("classification", "likely_equivalent");
            out.put("equivalenceRisk", "length == 0 and -length == 0 are equivalent for Java int equality to zero.");
            out.put("reason", "Arithmetic negation does not change the zero comparison outcome.");
        } else if (diff.contains("length=>~length")) {
            predicates.add("length == -1");
            out.put("classification", "killable");
            out.put("inputHint",
                    "Use a negative-length path that drives the mutated comparison away from the original guard boundary.");
            out.put("recommendedTestShape",
                    "Assert the original throws for negative length rather than only checking normal reads.");
            out.put("reason", "Bitwise complement changes the length == 0 guard only at length == -1.");
        } else if (diff.contains("length==0=>false")) {
            predicates.add("length == 0");
            out.put("classification", "likely_equivalent_for_valid_inputs");
            out.put("equivalenceRisk",
                    "For valid zero-length reads, Reader.read returns 0 even without the guard. Only invalid buf/offset with length == 0 may expose behavior.");
            out.put("reason", "The deleted zero-length guard is redundant for ordinary valid arguments.");
        } else if (diff.contains("if(length==0)") && diff.contains("=>finalintlen=super.read")) {
            predicates.add("length == 0");
            predicates.add("invalid buf or invalid offset");
            out.put("classification", "killable_on_invalid_zero_length");
            out.put("inputHint",
                    "Use a zero-length boundary case where the early guard and the later argument validation disagree.");
            out.put("recommendedTestShape",
                    "Assert zero-length invalid-argument guard behavior, not normal read behavior.");
            out.put("reason", "Statement deletion removes the early return guard.");
        } else if (diff.contains("len>0=>len>=0")) {
            predicates.add("super.read(buf, offset, length) returns 0");
            out.put("classification", "killable_with_custom_reader");
            out.put("inputHint",
                    "Use a reader implementation whose read result can hit the zero-return boundary for a positive-length request.");
            out.put("recommendedTestShape",
                    "Assert the original treats len == 0 as non-positive and skips buf[offset + len - 1], while the mutant enters the len >= 0 branch.");
            out.put("reason",
                    "The relational mutation differs only at len == 0, which ordinary StringReader rarely returns for positive length.");
        } else if (diff.contains("len==-1=>len>=-1") || diff.contains("len==-1=>true")
                || diff.contains("if(len==-1)") && diff.contains("=>if(true)")) {
            predicates.add("super.read(buf, offset, length) returns 0");
            out.put("classification", "killable_with_custom_reader");
            out.put("inputHint",
                    "Use a reader input path that makes the wrapped read call return zero so the branch boundary can be observed.");
            out.put("recommendedTestShape", "Drive the len == 0 boundary in the else branch after len > 0 is false.");
            out.put("reason", "The mutation differs from len == -1 only at the len == 0 boundary.");
        } else if (diff.contains("len==-1=>len<=-1")) {
            predicates.add("len == -1 or len < -1");
            out.put("classification", "likely_equivalent_under_reader_contract");
            out.put("equivalenceRisk",
                    "Reader.read returns -1 or a non-negative value by contract; values below -1 are outside the normal contract, so len <= -1 is equivalent to len == -1.");
            out.put("reason", "The changed comparison only adds impossible Reader return values.");
        } else if (diff.contains("i<offset+len=>i!=offset+len")) {
            predicates.add("monotonic for-loop with i++");
            out.put("classification", "likely_equivalent");
            out.put("equivalenceRisk",
                    "With i initialized at offset and incremented by one, i < offset + len and i != offset + len terminate at the same boundary unless the induction variable is otherwise modified.");
            out.put("reason", "The loop boundary mutation is equivalent for a normal +1 induction variable.");
        } else if (diff.contains("i>0=>i!=0")) {
            predicates.add("i is an int index");
            out.put("classification", "equivalent");
            out.put("equivalenceRisk",
                    "For integer index values, i > 0 and i != 0 differ only for negative i. This loop starts at offset and valid read offsets are non-negative.");
            out.put("reason", "The changed relation adds invalid negative index states.");
        } else if (diff.contains("i=>i++")) {
            predicates.add("len > 0");
            predicates.add("adjacent input elements must trigger the next-iteration boundary");
            out.put("classification", "killable");
            out.put("inputHint",
                    "Use an input where adjacent elements force the loop index mutation to affect the next inspection step.");
            out.put("recommendedTestShape", "Assert current line number after one read of the full input.");
            out.put("reason",
                    "Post-increment of the loop index affects which buffer character is inspected next; inputs that never reach the adjacent-element boundary will not expose it.");
        }

        if (!predicates.isEmpty()) {
            String changed = mutationMutatedExpression();
            out.put("requiredPathPredicates", normalizeAndRepairPredicates(source, changed, new ArrayList<>(predicates)));
        }
        return out;
    }

    private static String semanticInputHint(MutationSemanticLocator.MutationSemanticContext ctx) {
        if (ctx == null || !ctx.resolved) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        if (!ctx.requiredNonNullVariables.isEmpty()) {
            parts.add("Bind non-null values for " + String.join(", ", ctx.requiredNonNullVariables)
                    + " because the mutation-sensitive expression dereferences them.");
        }
        if (!isBlank(ctx.semanticOriginalExpression) && !isBlank(ctx.semanticMutantExpression)) {
            parts.add("Choose inputs so (" + ctx.semanticOriginalExpression + ") differs from ("
                    + ctx.semanticMutantExpression + ").");
        }
        String forced = forcedOriginalEvaluation(ctx.semanticOriginalExpression, ctx.semanticMutantExpression);
        if (!isBlank(forced)) {
            parts.add("For the forced-branch mutant, satisfy " + forced + ".");
        }
        return String.join(" ", parts);
    }

    private static String forcedOriginalEvaluation(String originalExpression, String mutantExpression) {
        String mutant = mutantExpression == null ? "" : mutantExpression.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(mutant)) {
            return "(" + (originalExpression == null ? "" : originalExpression.trim()) + ") == false";
        }
        if ("false".equals(mutant)) {
            return "(" + (originalExpression == null ? "" : originalExpression.trim()) + ") == true";
        }
        return "";
    }

    private static boolean isReaderArrayReadImplementation(String source) {
        String text = source == null ? "" : source;
        return text.contains("read(final char[]")
                && text.contains("super.read")
                && text.contains("buf")
                && text.contains("offset")
                && text.contains("length")
                && text.contains("len");
    }

    private String mutationOriginalExpression() {
        return normalizeMutationExpression(mutationOriginalRawExpression());
    }

    private String mutationOriginalRawExpression() {
        String diff = Diff == null ? "" : Diff;
        int arrow = diff.indexOf("=>");
        String left = arrow >= 0 ? diff.substring(0, arrow) : diff;
        return left.trim();
    }

    private static String normalizeMutationExpression(String expression) {
        String text = expression == null ? "" : expression.trim();
        while (text.startsWith("(") && text.endsWith(")") && text.length() > 1) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static List<Object> normalizeAndRepairPredicates(String source, String changedExpression, List<Object> predicates) {
        List<Object> out = new ArrayList<>();
        if (predicates == null) {
            return out;
        }
        for (Object item : predicates) {
            String text = stringValue(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            out.add(text);
        }

        LinkedHashSet<Object> dedup = new LinkedHashSet<>(out);
        return new ArrayList<>(dedup);
    }


    private static Map<String, Object> inferTernaryPath(String source, String changedExpression) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (isBlank(source) || isBlank(changedExpression)) {
            return out;
        }
        try {
            com.github.javaparser.ast.CompilationUnit cu = StaticJavaParser.parse(source);
            com.github.javaparser.ast.expr.Expression changed =
                    StaticJavaParser.parseExpression(changedExpression);
            for (com.github.javaparser.ast.expr.ConditionalExpr ternary
                    : cu.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class)) {
                boolean inTrue = containsEquivalentExpression(ternary.getThenExpr(), changed);
                boolean inFalse = containsEquivalentExpression(ternary.getElseExpr(), changed);
                if (!inTrue && !inFalse) {
                    continue;
                }
                String condition = ternary.getCondition().toString();
                String required = inTrue
                        ? condition
                        : negateSimpleCondition(condition);
                out.put("requiredPathPredicates", sanitizeList(listOf(required)));
                out.put("ternaryCondition", condition);
                out.put("selectedBranch", inTrue ? "true" : "false");
                out.put("reason", "Mutation-sensitive expression '" + changedExpression
                        + "' is in the " + (inTrue ? "true" : "false")
                        + " branch of a ternary expression; tests must force condition '"
                        + condition + "' to evaluate " + (inTrue ? "true" : "false") + ".");
                return out;
            }
        } catch (RuntimeException ignored) {
            // Ternary evidence is optional. Prefer UNKNOWN over regex-based Java parsing.
        }
        return out;
    }

    private static boolean containsEquivalentExpression(
            com.github.javaparser.ast.Node container,
            com.github.javaparser.ast.expr.Expression expected) {
        if (container == null || expected == null) {
            return false;
        }
        if (container instanceof com.github.javaparser.ast.expr.Expression
                && unwrapExpression((com.github.javaparser.ast.expr.Expression) container)
                .equals(unwrapExpression(expected))) {
            return true;
        }
        for (com.github.javaparser.ast.expr.Expression expression
                : container.findAll(com.github.javaparser.ast.expr.Expression.class)) {
            if (unwrapExpression(expression).equals(unwrapExpression(expected))) {
                return true;
            }
        }
        return false;
    }

    private static com.github.javaparser.ast.expr.Expression unwrapExpression(
            com.github.javaparser.ast.expr.Expression expression) {
        com.github.javaparser.ast.expr.Expression current = expression;
        while (current != null && current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    private static String normalizeTokenText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }

    private static String trimOuterParens(String value) {
        String text = value == null ? "" : value.trim();
        int lastOpen = text.lastIndexOf('(');
        if (lastOpen >= 0) {
            text = text.substring(lastOpen + 1).trim();
        }
        while (text.startsWith("(") && text.endsWith(")") && text.length() > 1) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String negateSimpleCondition(String condition) {
        String text = trimOuterParens(condition);
        if (isBlank(text)) {
            return "";
        }
        try {
            com.github.javaparser.ast.expr.Expression expression =
                    StaticJavaParser.parseExpression(text);
            com.github.javaparser.ast.expr.Expression unwrapped = unwrapExpression(expression);
            if (unwrapped.isUnaryExpr()
                    && unwrapped.asUnaryExpr().getOperator()
                    == com.github.javaparser.ast.expr.UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return unwrapped.asUnaryExpr().getExpression().toString();
            }
            return new com.github.javaparser.ast.expr.UnaryExpr(
                    new com.github.javaparser.ast.expr.EnclosedExpr(expression.clone()),
                    com.github.javaparser.ast.expr.UnaryExpr.Operator.LOGICAL_COMPLEMENT)
                    .toString();
        } catch (RuntimeException ignored) {
            return "!(" + text + ")";
        }
    }

    private static String pathSensitiveInputHint(String source, Collection<Object> predicates) {
        String text = source == null ? "" : source;
        String predicateText = predicates == null ? "" : predicates.stream()
                .map(RipParser::stringValue)
                .filter(s -> !isBlank(s))
                .collect(Collectors.joining(" ; "));
        if (!isBlank(text) && !isBlank(predicateText)) {
            return "Use an input that satisfies the extracted path predicates before the mutation-sensitive branch: "
                    + predicateText + ".";
        }
        return "";
    }

    private static String pathSensitivePreferredObservable(String source,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        return firstNonBlank(stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(propagation.get("observableSinkExpression")));
    }

    private String inferStructuralEquivalenceReason() {
        String diff = normalizeTokenText(Diff);
        String method = METHOD_NAME_SUBSTR == null ? "" : METHOD_NAME_SUBSTR.toLowerCase(Locale.ROOT);
        if (method.contains("initializeheader")
                && diff.contains("formatheader.length=>-formatheader.length")) {
            return "initializeHeader compares formatHeader.length to zero; negating the length does not change the truth of == 0, so the branch outcome is unchanged.";
        }
        return "";
    }

    private static Map<String, Object> harmonizeBranchReachabilityPlan(Map<String, Object> branchReachabilityPlan,
            Map<String, Object> mutationSensitivePath) {
        Map<String, Object> out = new LinkedHashMap<>(branchReachabilityPlan);
        if (mutationSensitivePath.isEmpty()) {
            return out;
        }
        String current = stringValue(out.get("condition"));
        String inferred = String.join(" && ", asList(mutationSensitivePath.get("requiredPathPredicates")).stream()
                .map(RipParser::stringValue)
                .filter(s -> !isBlank(s))
                .collect(Collectors.toList()));
        if (isBlank(current) && !isBlank(inferred)) {
            out.put("condition", inferred);
        }
        putIfNotEmpty(out, "inputHint", stringValue(mutationSensitivePath.get("inputHint")));
        putIfNotEmpty(out, "preferredObservable", stringValue(mutationSensitivePath.get("preferredObservable")));
        return out;
    }

    private static Map<String, Object> harmonizePathSensitiveObservablePlan(Map<String, Object> observablePlan,
            Map<String, Object> mutationSensitivePath) {
        Map<String, Object> out = new LinkedHashMap<>(observablePlan);
        if (mutationSensitivePath.isEmpty()) {
            return out;
        }
        String preferredObservable = stringValue(mutationSensitivePath.get("preferredObservable"));
        String currentObservable = stringValue(out.get("observableCall"));
        if (!isBlank(preferredObservable)
                && !shouldKeepExistingObservable(currentObservable, preferredObservable, mutationSensitivePath)) {
            out.put("observableCall", preferredObservable);
            out.put("reason", firstNonBlank(stringValue(out.get("reason")),
                    "Prefer the path-sensitive observable tied to the mutated state."));
        }
        putIfNotEmpty(out, "pathSensitiveInputHint", stringValue(mutationSensitivePath.get("inputHint")));
        return out;
    }

    private static boolean shouldKeepExistingObservable(String currentObservable,
            String preferredObservable,
            Map<String, Object> mutationSensitivePath) {
        String current = currentObservable == null ? "" : currentObservable.trim().toLowerCase(Locale.ROOT);
        String preferred = preferredObservable == null ? "" : preferredObservable.trim().toLowerCase(Locale.ROOT);
        if (current.isEmpty() || preferred.isEmpty() || current.equals(preferred)) {
            return false;
        }

        String classification = stringValue(mutationSensitivePath.get("classification")).toLowerCase(Locale.ROOT);
        boolean currentLooksConcreteState = current.contains("token.content")
                || current.contains("token.type")
                || current.contains("token.isready")
                || current.contains("buffer.tostring()")
                || current.contains("result = buffer.tostring()")
                || current.contains("writer.tostring()")
                || current.contains("out.tostring()");
        boolean preferredLooksWeakCursor = preferred.contains("getlastchar()")
                || preferred.contains("getcurrentlinenumber()")
                || preferred.contains("getcharacterposition()");

        if (currentLooksConcreteState && preferredLooksWeakCursor) {
            return true;
        }
        return currentLooksConcreteState && classification.contains("likely_equivalent");
    }

    private static Map<String, Object> harmonizeObservablePlan(Map<String, Object> observablePlan,
            String primarySink,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        Map<String, Object> out = new LinkedHashMap<>(observablePlan);
        if (shouldPreserveReturnObservable(primarySink, out, observableSelection, propagation)) {
            return out;
        }
        if (!"exception".equals(primarySink)) {
            return out;
        }
        out.put("kind", "EXCEPTION_SINK");
        out.put("observableCall", preferredObservableCall(primarySink, testEntryContext, entryGenerationPlan,
                observableSelection, propagation));
        out.put("reason", preferredObservableReason(primarySink, observableSelection, propagation));
        return out;
    }

    private static Map<String, Object> harmonizeReachabilityReasoning(Map<String, Object> liftedReachability,
            String primarySink,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        Map<String, Object> out = new LinkedHashMap<>(liftedReachability);
        if (shouldPreserveReturnObservable(primarySink, observableSelection, observableSelection, propagation)) {
            return out;
        }
        if (!"exception".equals(primarySink)) {
            return out;
        }
        String preferredCall = preferredObservableCall(primarySink, testEntryContext, entryGenerationPlan,
                observableSelection, propagation);
        out.put("postEntryObservableSteps", listOf(
                "After invoking B, assert whether the entry completes or throws the distinguishing exception.",
                preferredCall));
        out.put("liftedObservationBridge",
                "A-side divergence should propagate through entry B and become observable via thrown-vs-not-thrown behavior.");
        return out;
    }

    private static Map<String, Object> harmonizePropagationReasoning(Map<String, Object> propagation,
            String primarySink,
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> observableSelection) {
        Map<String, Object> out = new LinkedHashMap<>(propagation);
        if (shouldPreserveReturnObservable(primarySink, observableSelection, observableSelection, out)) {
            String preferredCall = firstNonBlank(
                    stringValue(observableSelection.get("preferredObservableCall")),
                    stringValue(out.get("observableSinkExpression")));
            if (!isBlank(preferredCall)) {
                out.put("observableSinkExpression", preferredCall);
            }
            if (stringValue(out.get("observableSinkKind")).toUpperCase(Locale.ROOT).contains("EXCEPTION")) {
                out.put("observableSinkKind", "ENTRY_RETURN_VALUE");
            }
            out.put("requiresExceptionObservation", false);
            out.put("requiresObjectStateObservation", false);
            return out;
        }
        if (!"exception".equals(primarySink)) {
            String oldKind = stringValue(out.get("observableSinkKind")).toLowerCase(Locale.ROOT);
            if (oldKind.contains("exception")) {
                out.put("observableSinkKind", "ENTRY_PARAMETER_FIXED");
                out.put("observableSinkExpression", "");
                out.put("requiresExceptionObservation", false);
                out.put("requiresObjectStateObservation", false);
                out.put("requiresAdditionalCalls", false);
                out.put("propagationChain", listOf(
                        "Entry reachability: invoke the selected callable entry.",
                        "Infection is blocked because mutation-sensitive parameters are fixed or not externally controllable from the chosen public entry.",
                        "Do not use exception-only assertions such as subject.print(null); first find a controllable entry or classify the mutant as likely equivalent from public behavior."));
            }
            return out;
        }
        String preferredCall = preferredObservableCall(primarySink, testEntryContext, entryGenerationPlan,
                observableSelection, propagation);
        out.put("observableSinkKind", "EXCEPTION_SINK");
        out.put("observableSinkExpression", preferredCall);
        out.put("requiresExceptionObservation", true);
        out.put("requiresObjectStateObservation", false);
        out.put("requiresAdditionalCalls", false);
        out.put("propagationChain", listOf(
                "Entry reachability: invoke the selected callable entry.",
                "Infection starts when the mutated expression/branch evaluates differently: "
                        + stringValue(propagation.get("infectionSource")),
                "Public observable step: " + preferredCall,
                "Observable rationale: distinguish constructor/call completion from thrown exception behavior."));
        return out;
    }

    private static boolean shouldPreserveReturnObservable(String primarySink,
            Map<String, Object> observablePlan,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        if (!"exception".equals(primarySink)) {
            return false;
        }
        String observableKind = firstNonBlank(
                stringValue(observablePlan.get("kind")),
                stringValue(observableSelection.get("preferredKind")),
                stringValue(propagation.get("observableSinkKind")))
                .toUpperCase(Locale.ROOT);
        String observableCall = firstNonBlank(
                stringValue(observablePlan.get("observableCall")),
                stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(propagation.get("observableSinkExpression")))
                .toLowerCase(Locale.ROOT);
        if (observableKind.contains("RETURN")) {
            return true;
        }
        return observableCall.contains("assert")
                || observableCall.contains("result")
                || observableCall.contains("return")
                || observableCall.contains("classify(")
                || observableCall.contains("equals(");
    }

    private static List<Object> resolveRequiredImports(
            Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan,
            Map<String, Object> codekb) {
        LinkedHashSet<String> imports = new LinkedHashSet<String>();
        imports.add("org.junit.Test");
        imports.add("static org.junit.Assert.*");

        String testPackage = stringValue(testEntryContext.get("testPackage"));
        String usageText = buildImportUsageText(testEntryContext, entryGenerationPlan);
        imports.addAll(collectImportStrings(testEntryContext.get("requiredImports"), testPackage));
        imports.addAll(collectImportStrings(entryGenerationPlan.get("requiredImports"), testPackage));

        for (Object item : asList(codekb.get("candidateImports"))) {
            Map<String, Object> candidate = asMap(item);
            String importValue = normalizeImportValue(stringValue(candidate.get("importValue")));
            if (importValue.isEmpty() || isJavaLangImport(importValue)
                    || isSamePackageImport(importValue, testPackage)) {
                continue;
            }
            String scope = stringValue(candidate.get("scope"));
            int priority = parseInt(candidate.get("priority"));
            if (!isHardRequiredImportCandidate(candidate)
                    && "SOURCE_FILE".equals(scope)
                    && priority < 60
                    && !mentionsImportedType(usageText, importValue)) {
                continue;
            }
            imports.add(importValue);
        }
        return sanitizeList(new ArrayList<Object>(imports));
    }

    private Map<String, Object> buildCodeKbAnalysisHints(
            Map<String, Object> liftedReachability,
            Map<String, Object> entryControl,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> mutationSensitivePath) {
        Map<String, Object> hints = new LinkedHashMap<String, Object>();
        hints.put("requiresRealEntryChain", !Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation")));
        hints.put("entryControlType", deriveIndirectEntryControlType(entryControl));
        hints.put("preferredObservableKind", preferredObservableKind(
                selectPrimarySink(SPEC_OBSERVED, propagation, observableSelection),
                observableSelection,
                propagation));
        hints.put("preferredObservableCall", firstNonBlank(
                stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(propagation.get("observableSinkExpression"))));
        hints.put("frontLoadedExceptionRisk", "exception".equals(firstNonBlank(
                stringValue(observableSelection.get("preferredObservableKind")),
                stringValue(observableSelection.get("baselineObservableKind")))));
        hints.put("requiredPathPredicates", sanitizeList(asList(mutationSensitivePath.get("requiredPathPredicates"))));
        hints.put("inputHint", stringValue(mutationSensitivePath.get("inputHint")));
        Map<String, Object> symbolic = buildSymbolicHintSummary(
                observableSelection,
                propagation,
                liftedReachability,
                entryControl,
                mutationSensitivePath);
        if (!symbolic.isEmpty()) {
            hints.put("symbolic", symbolic);
        }
        return hints;
    }

    private Map<String, Object> buildSymbolicFeasibility(
            List<Object> specObserved,
            Map<String, Object> killabilitySummary,
            Map<String, Object> liftedReachability,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> mutationSensitivePath) {
        LinkedHashSet<String> rawConstraints = symbolicPathConstraints(liftedReachability, mutationSensitivePath);
        List<String> blockingGuards = inferBlockingGuards(rawConstraints, observableSelection);
        List<String> pathConstraints = extractReachabilityPredicates(rawConstraints, blockingGuards);
        String primarySink = selectPrimarySink(specObserved, propagation, observableSelection);
        if (shouldPreferReturnOverGenericException(primarySink, specObserved, propagation, observableSelection)) {
            primarySink = "return";
        }
        if ("exception".equals(primarySink) && isEntryParameterFixed(killabilitySummary)) {
            primarySink = firstNonExceptionSink(specObserved, "state");
        }
        String level = pathConstraints.isEmpty() ? "NO_SYMBOLIC_PATH_SUMMARY"
                : (blockingGuards.isEmpty() ? "PATH_CONSTRAINTS_AVAILABLE" : "PATH_AND_GUARD_CONSTRAINTS_AVAILABLE");
        if (pathConstraints.isEmpty() && blockingGuards.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", true);
        out.put("model", "LOCAL_CFG_SYMBOLIC_SUMMARY");
        out.put("level", level);
        out.put("pathConstraintCount", pathConstraints.size());
        out.put("blockingGuardCount", blockingGuards.size());
        out.put("entryRelation", Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"))
                ? "DIRECT_MUTATION_METHOD"
                : "ENTRY_TO_MUTATION");
        out.put("primaryObservableSink", firstNonBlank(
                preferredObservableKind(primarySink, observableSelection, propagation),
                primarySink));
        out.put("killabilityCategory", stringValue(killabilitySummary.get("category")));
        out.put("reason", firstNonBlank(
                stringValue(mutationSensitivePath.get("reason")),
                stringValue(killabilitySummary.get("reason")),
                "CFG path predicates and mutation-sensitive constraints provide a lightweight symbolic execution summary."));
        return out;
    }

    private Map<String, Object> buildSymbolicRipPlan(
            List<Object> specObserved,
            Map<String, Object> testTarget,
            Map<String, Object> setupPlan,
            Map<String, Object> assertionPlan,
            Map<String, Object> observableSelection,
            Map<String, Object> liftedReachability,
            Map<String, Object> propagation,
            Map<String, Object> entryControl,
            Map<String, Object> mutationSensitivePath) {
        LinkedHashSet<String> rawConstraints = symbolicPathConstraints(liftedReachability, mutationSensitivePath);
        List<String> blockingGuards = inferBlockingGuards(rawConstraints, observableSelection);
        List<String> pathConstraints = extractReachabilityPredicates(rawConstraints, blockingGuards);
        String primarySink = selectPrimarySink(specObserved, propagation, observableSelection);
        if (shouldPreferReturnOverGenericException(primarySink, specObserved, propagation, observableSelection)) {
            primarySink = "return";
        }
        if ("exception".equals(primarySink) && hasUncontrollableEntryParameters(entryControl)) {
            primarySink = firstNonExceptionSink(specObserved, "state");
        }
        String preferredCall = preferredObservableCall(primarySink, testTarget, Collections.emptyMap(),
                observableSelection, propagation);
        String distinguishingPredicate = buildDistinguishingPredicateText(pathConstraints, blockingGuards, mutationSensitivePath);
        List<String> symbolicVariables = extractSymbolicVariables(pathConstraints, mutationOriginalExpression(),
                mutationMutatedExpression(), stringValue(propagation.get("observableSinkExpression")));
        List<String> assignments = symbolicAssignments(setupPlan, mutationSensitivePath);
        List<String> propagationRequirements = symbolicPropagationRequirements(propagation, preferredCall, primarySink);
        List<String> antiPatterns = new ArrayList<String>();
        antiPatterns.add("Do not ignore the path constraints and jump directly to a weak oracle.");
        antiPatterns.add(
                "Do not choose a null/default guard-triggering input when the symbolic path says the mutation is deeper in the normal path.");
        antiPatterns.add("Do not bypass the real public entry/call-chain when the symbolic summary is entry-lifted.");
        antiPatterns.addAll(sanitizeStringList(assertionPlan.get("antiPatterns")));

        if (pathConstraints.isEmpty() && assignments.isEmpty() && isBlank(preferredCall)) {
            return Collections.emptyMap();
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", true);
        out.put("model", "LOCAL_CFG_SYMBOLIC_SUMMARY");
        out.put("entryMode", Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"))
                ? "DIRECT_MUTATION_METHOD"
                : "ENTRY_LIFTED_TO_MUTATION");
        out.put("pathConstraints", new ArrayList<String>(pathConstraints));
        out.put("blockingGuards", blockingGuards);
        putIfNotEmpty(out, "predicateChain", buildPredicateChainText(pathConstraints, blockingGuards));
        putIfNotEmpty(out, "distinguishingPredicate", distinguishingPredicate);
        out.put("symbolicVariables", symbolicVariables);
        out.put("preferredAssignments", assignments);
        out.put("propagationRequirements", propagationRequirements);
        out.put("preferredObservableKind", preferredObservableKind(primarySink, observableSelection, propagation));
        putIfNotEmpty(out, "preferredObservableCall", preferredCall);
        out.put("oracleSketch", buildOracleSketch(primarySink, preferredCall, observableSelection, propagation));
        out.put("entryControlType", deriveIndirectEntryControlType(entryControl));
        out.put("antiPatterns", new ArrayList<String>(antiPatterns));
        out.put("confidence", pathConstraints.size() >= 2 ? "medium" : "low");
        out.put("reason", firstNonBlank(
                stringValue(mutationSensitivePath.get("reason")),
                stringValue(propagation.get("reason")),
                "Use CFG-derived path constraints as a lightweight symbolic guide for RIP-oriented test generation."));
        return out;
    }

    private Map<String, Object> buildSymbolicReasoning(
            Map<String, Object> origin,
            Map<String, Object> mutated,
            Map<String, Object> entryOrigin,
            Map<String, Object> entryMutated,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> liftedReachability,
            Map<String, Object> entryControl,
            Map<String, Object> mutationSensitivePath) {
        LinkedHashSet<String> rawConstraints = symbolicPathConstraints(liftedReachability, mutationSensitivePath);
        List<Object> sourcePredicates = collectPredicates(origin, mutated, entryOrigin, entryMutated);
        List<String> blockingGuards = inferBlockingGuards(rawConstraints, observableSelection);
        List<String> pathConstraints = extractReachabilityPredicates(rawConstraints, blockingGuards);
        String originalExpr = mutationOriginalExpression();
        String mutatedExpr = mutationMutatedExpression();
        String distinguishingPredicate = buildDistinguishingPredicateText(pathConstraints, blockingGuards, mutationSensitivePath);
        String preferredCall = firstNonBlank(
                stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(propagation.get("observableSinkExpression")));
        if (pathConstraints.isEmpty() && sourcePredicates.isEmpty() && isBlank(originalExpr)
                && isBlank(mutatedExpr)) {
            return Collections.emptyMap();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("model", "LOCAL_CFG_SYMBOLIC_SUMMARY");
        out.put("pathConstraints", new ArrayList<String>(pathConstraints));
        out.put("sourcePredicates", sanitizeList(sourcePredicates));
        out.put("blockingGuards", blockingGuards);
        putIfNotEmpty(out, "predicateChain", buildPredicateChainText(pathConstraints, blockingGuards));
        putIfNotEmpty(out, "mutationExpressionOriginal", originalExpr);
        putIfNotEmpty(out, "mutationExpressionMutated", mutatedExpr);
        putIfNotEmpty(out, "distinguishingPredicate", distinguishingPredicate);
        out.put("symbolicVariables", extractSymbolicVariables(pathConstraints, originalExpr, mutatedExpr,
                stringValue(propagation.get("observableSinkExpression"))));
        out.put("observableSplit", mapOf(
                "kind", firstNonBlank(
                        stringValue(observableSelection.get("preferredObservableKind")),
                        stringValue(propagation.get("observableSinkKind"))),
                "call", preferredCall,
                "reason", firstNonBlank(
                        stringValue(observableSelection.get("overrideReason")),
                        stringValue(propagation.get("reason")))));
        out.put("entryRelation", Boolean.TRUE.equals(liftedReachability.get("sameEntryAndMutation"))
                ? "DIRECT_MUTATION_METHOD"
                : "ENTRY_LIFTED_TO_MUTATION");
        out.put("entryControlType", deriveIndirectEntryControlType(entryControl));
        out.put("reasoningSteps", buildSymbolicReasoningSteps(pathConstraints, blockingGuards, originalExpr,
                mutatedExpr, preferredCall));
        return out;
    }

    private Map<String, Object> buildSymbolicHintSummary(
            Map<String, Object> observableSelection,
            Map<String, Object> propagation,
            Map<String, Object> liftedReachability,
            Map<String, Object> entryControl,
            Map<String, Object> mutationSensitivePath) {
        LinkedHashSet<String> rawConstraints = symbolicPathConstraints(liftedReachability, mutationSensitivePath);
        List<String> blockingGuards = inferBlockingGuards(rawConstraints, observableSelection);
        List<String> pathConstraints = extractReachabilityPredicates(rawConstraints, blockingGuards);
        if (pathConstraints.isEmpty() && blockingGuards.isEmpty()) {
            return Collections.emptyMap();
        }
        String preferredCall = firstNonBlank(
                stringValue(observableSelection.get("preferredObservableCall")),
                stringValue(propagation.get("observableSinkExpression")));
        if (isDefaultOnlyObservableCall(preferredCall)) {
            preferredCall = "";
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("available", true);
        out.put("pathConstraintCount", pathConstraints.size());
        out.put("blockingGuardCount", blockingGuards.size());
        putIfNotEmpty(out, "predicateChain", buildPredicateChainText(pathConstraints, blockingGuards));
        out.put("distinguishingPredicate", buildDistinguishingPredicateText(pathConstraints, blockingGuards, mutationSensitivePath));
        out.put("preferredObservableCall", preferredCall);
        out.put("entryControlType", deriveIndirectEntryControlType(entryControl));
        return out;
    }

    private LinkedHashSet<String> symbolicPathConstraints(
            Map<String, Object> liftedReachability,
            Map<String, Object> mutationSensitivePath) {
        LinkedHashSet<String> constraints = new LinkedHashSet<String>();
        for (Object item : asList(liftedReachability.get("entryToMutationConditions"))) {
            String text = stringValue(item).trim();
            if (!text.isEmpty()) {
                constraints.add(text);
            }
        }
        for (Object item : asList(mutationSensitivePath.get("requiredPathPredicates"))) {
            String text = stringValue(item).trim();
            if (!text.isEmpty()) {
                constraints.add(text);
            }
        }
        return constraints;
    }

    private List<String> inferBlockingGuards(Collection<String> pathConstraints,
            Map<String, Object> observableSelection) {
        LinkedHashSet<String> guards = new LinkedHashSet<String>();
        String overrideReason = stringValue(observableSelection.get("overrideReason"));
        for (String constraint : pathConstraints == null ? Collections.<String>emptyList() : pathConstraints) {
            String normalized = constraint == null ? "" : constraint.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (isFrontDoorGuardConstraint(normalized)) {
                guards.add(normalized);
            }
        }
        if (overrideReason.toLowerCase(Locale.ROOT).contains("require non-null")) {
            guards.add(overrideReason.trim());
        }
        return new ArrayList<String>(guards);
    }

    private List<String> extractReachabilityPredicates(Collection<String> pathConstraints, Collection<String> blockingGuards) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        Set<String> blocking = new LinkedHashSet<String>();
        for (String guard : blockingGuards == null ? Collections.<String>emptyList() : blockingGuards) {
            if (guard != null && !guard.trim().isEmpty()) {
                blocking.add(guard.trim());
            }
        }
        for (String constraint : pathConstraints == null ? Collections.<String>emptyList() : pathConstraints) {
            String normalized = constraint == null ? "" : constraint.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (!looksLikePredicateConstraint(normalized)) {
                continue;
            }
            if (blocking.contains(normalized) || isFrontDoorGuardConstraint(normalized)) {
                continue;
            }
            out.add(normalized);
        }
        return new ArrayList<String>(out);
    }

    private boolean looksLikePredicateConstraint(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("original:")
                || lower.startsWith("mutant:")
                || lower.startsWith("prefer ")
                || lower.startsWith("use ")
                || lower.startsWith("avoid ")
                || lower.startsWith("b is the same method")) {
            return false;
        }
        return normalized.contains("==")
                || normalized.contains("!=")
                || normalized.contains("<=")
                || normalized.contains(">=")
                || normalized.contains(" < ")
                || normalized.contains(" > ")
                || normalized.contains("&&")
                || normalized.contains("||")
                || normalized.contains(".isEmpty()")
                || normalized.contains(" null");
    }

    private boolean isFrontDoorGuardConstraint(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        boolean hasWeakDefaultCheck = normalized.contains("null")
                || normalized.contains("<= 0")
                || normalized.contains("<=0")
                || normalized.contains("< 0")
                || normalized.contains("<0")
                || normalized.contains("== 0")
                || normalized.contains("==0")
                || normalized.contains(".isempty()")
                || normalized.contains("length == 0")
                || normalized.contains("length==0")
                || normalized.contains("trim().isempty()");
        return hasWeakDefaultCheck && (normalized.contains("||") || normalized.startsWith("if "));
    }

    private String mutationMutatedExpression() {
        return normalizeMutationExpression(mutationMutatedRawExpression());
    }

    private String mutationMutatedRawExpression() {
        String diff = Diff == null ? "" : Diff;
        int arrow = diff.indexOf("=>");
        String right = arrow >= 0 ? diff.substring(arrow + 2) : "";
        return right.trim();
    }

    private String buildDistinguishingPredicateText(Collection<String> pathConstraints,
            Collection<String> blockingGuards,
            Map<String, Object> mutationSensitivePath) {
        List<String> constraints = new ArrayList<String>();
        if (pathConstraints != null) {
            for (String constraint : pathConstraints) {
                if (constraint != null && !constraint.trim().isEmpty()) {
                    constraints.add(constraint.trim());
                }
            }
        }
        String hint = stringValue(mutationSensitivePath.get("inputHint"));
        if (constraints.isEmpty()) {
            if (blockingGuards != null && !blockingGuards.isEmpty()) {
                return "avoid front-door guards: " + String.join(" && not(", blockingGuards) + ")";
            }
            return hint;
        }
        if (blockingGuards != null && !blockingGuards.isEmpty()) {
            return "avoid { " + String.join(" ; ", sanitizeStringList(blockingGuards))
                    + " } and satisfy { " + String.join(" && ", constraints) + " }";
        }
        return String.join(" && ", constraints);
    }

    private String buildPredicateChainText(Collection<String> pathConstraints, Collection<String> blockingGuards) {
        List<String> chain = new ArrayList<String>();
        for (String guard : blockingGuards == null ? Collections.<String>emptyList() : blockingGuards) {
            String text = guard == null ? "" : guard.trim();
            if (!text.isEmpty()) {
                chain.add("avoid(" + text + ")");
            }
        }
        for (String constraint : pathConstraints == null ? Collections.<String>emptyList() : pathConstraints) {
            String text = constraint == null ? "" : constraint.trim();
            if (!text.isEmpty()) {
                chain.add("satisfy(" + text + ")");
            }
        }
        return chain.isEmpty() ? "" : String.join(" -> ", chain);
    }

    private List<String> extractSymbolicVariables(Collection<String> constraints, String... extraTexts) {
        LinkedHashSet<String> vars = new LinkedHashSet<String>();
        for (String text : constraints == null ? Collections.<String>emptyList() : constraints) {
            collectIdentifierTokens(vars, text);
        }
        if (extraTexts != null) {
            for (String text : extraTexts) {
                collectIdentifierTokens(vars, text);
            }
        }
        return new ArrayList<String>(vars);
    }

    private void collectIdentifierTokens(Set<String> target, String text) {
        if (target == null || text == null || isBlank(text)) {
            return;
        }
        Matcher matcher = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\b").matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null || isBlank(token)) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (SYMBOLIC_KEYWORDS.contains(lower) || Character.isUpperCase(token.charAt(0))) {
                continue;
            }
            target.add(token);
        }
    }

    private List<String> symbolicAssignments(Map<String, Object> setupPlan,
            Map<String, Object> mutationSensitivePath) {
        LinkedHashSet<String> assignments = new LinkedHashSet<String>();
        for (Object item : asList(setupPlan.get("stateSetup"))) {
            String text = stringValue(item).trim();
            if (!text.isEmpty()) {
                assignments.add(text);
            }
        }
        String inputHint = stringValue(mutationSensitivePath.get("inputHint")).trim();
        if (!inputHint.isEmpty()) {
            assignments.add(inputHint);
        }
        return new ArrayList<String>(assignments);
    }

    private List<String> symbolicPropagationRequirements(Map<String, Object> propagation,
            String preferredCall,
            String primarySink) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (Object item : asList(propagation.get("propagationChain"))) {
            String text = stringValue(item).trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        if (!isBlank(preferredCall)) {
            out.add("Final observable call: " + preferredCall);
        }
        if (!isBlank(primarySink)) {
            out.add("Oracle sink category: " + primarySink);
        }
        return new ArrayList<String>(out);
    }

    private Map<String, Object> buildOracleSketch(String primarySink,
            String preferredCall,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("mode", firstNonBlank(
                stringValue(observableSelection.get("preferredObservableKind")),
                stringValue(propagation.get("observableSinkKind")),
                primarySink));
        putIfNotEmpty(out, "observableCall", preferredCall);
        out.put("expectedSplit", firstNonBlank(
                stringValue(observableSelection.get("overrideReason")),
                stringValue(propagation.get("reason")),
                "Assert an externally visible difference after satisfying the symbolic path constraints."));
        return out;
    }

    private List<String> buildSymbolicReasoningSteps(Collection<String> pathConstraints,
            List<String> blockingGuards,
            String originalExpr,
            String mutatedExpr,
            String preferredCall) {
        List<String> steps = new ArrayList<String>();
        if (pathConstraints != null && !pathConstraints.isEmpty()) {
            steps.add("Reachability: satisfy path constraints " + String.join(" && ", pathConstraints) + ".");
        }
        if (blockingGuards != null && !blockingGuards.isEmpty()) {
            steps.add("Blocking guards: avoid front-door guards " + String.join(", ", blockingGuards)
                    + " when they prevent mutation execution.");
        }
        if (!isBlank(originalExpr) || !isBlank(mutatedExpr)) {
            steps.add(
                    "Infection: force original expression '" + originalExpr + "' and mutated expression '" + mutatedExpr
                            + "' to evaluate differently.");
        }
        if (!isBlank(preferredCall)) {
            steps.add("Propagation/Oracle: observe the divergence via " + preferredCall + ".");
        }
        return steps;
    }

    private static List<String> mergeStringLists(List<?>... lists) {
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        if (lists == null) {
            return new ArrayList<String>();
        }
        for (List<?> list : lists) {
            if (list == null) {
                continue;
            }
            for (Object item : list) {
                String normalized = item == null ? "" : String.valueOf(item).trim();
                if (!normalized.isEmpty()) {
                    merged.add(normalized);
                }
            }
        }
        return new ArrayList<String>(merged);
    }

    private static List<String> sanitizeStringList(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : asList(value)) {
            String normalized = stringValue(item).trim();
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static boolean isHardRequiredImportCandidate(Map<String, Object> candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        String scope = stringValue(candidate.get("scope"));
        String source = stringValue(candidate.get("knowledgeSource"));
        int priority = parseInt(candidate.get("priority"));
        if ("FEEDBACK_KB".equals(source)) {
            return true;
        }
        if ("MUTATION_METHOD".equals(scope) || "ENTRY_METHOD".equals(scope)) {
            return priority >= 50;
        }
        return priority >= 90;
    }

    private static String buildImportUsageText(Map<String, Object> testEntryContext,
            Map<String, Object> entryGenerationPlan) {
        List<String> parts = new ArrayList<String>();
        collectUsageText(parts, testEntryContext.get("entryInvocationKind"));
        collectUsageText(parts, asMap(testEntryContext.get("receiver")).get("construction"));
        collectUsageText(parts, asMap(testEntryContext.get("receiver")).get("setupTemplate"));
        Map<String, Object> invocationPlan = firstNonEmptyMap(asMap(testEntryContext.get("invocationPlan")),
                asMap(entryGenerationPlan.get("invocationPlan")));
        collectUsageText(parts, invocationPlan.get("invocationTemplate"));
        collectUsageText(parts, invocationPlan.get("setupTemplate"));
        Map<String, Object> publicApi = firstNonEmptyMap(asMap(testEntryContext.get("publicApi")),
                asMap(entryGenerationPlan.get("publicApi")));
        collectUsageText(parts, asMap(publicApi.get("observablePlan")).get("observableCall"));
        collectUsageText(parts, asMap(publicApi.get("observablePlan")).get("setup"));
        for (Object value : asList(publicApi.get("stateSetupPlan"))) {
            collectUsageText(parts, value);
        }
        Map<String, Object> assertionPlan = asMap(entryGenerationPlan.get("assertionPlan"));
        for (Object item : asList(assertionPlan.get("recommendedAssertions"))) {
            Map<String, Object> assertion = asMap(item);
            collectUsageText(parts, assertion.get("template"));
            collectUsageText(parts, assertion.get("expression"));
        }
        return String.join("\n", parts);
    }

    private static void collectUsageText(List<String> parts, Object value) {
        String text = stringValue(value);
        if (!isBlank(text)) {
            parts.add(text);
        }
    }

    private static List<String> collectImportStrings(Object value, String testPackage) {
        LinkedHashSet<String> imports = new LinkedHashSet<String>();
        for (Object item : asList(value)) {
            String importValue = normalizeImportValue(stringValue(item));
            if (!importValue.isEmpty() && !isJavaLangImport(importValue)
                    && !isSamePackageImport(importValue, testPackage)) {
                imports.add(importValue);
            }
        }
        return new ArrayList<String>(imports);
    }

    private static String normalizeImportValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("import ")) {
            normalized = normalized.substring("import ".length()).trim();
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static boolean isJavaLangImport(String importValue) {
        return importValue.startsWith("java.lang.");
    }

    private static boolean isSamePackageImport(String importValue, String testPackage) {
        if (importValue.isEmpty() || testPackage == null || isBlank(testPackage)
                || importValue.startsWith("static ")) {
            return false;
        }
        if (!importValue.contains(".")) {
            return false;
        }
        String normalized = importValue.endsWith(".*") ? importValue.substring(0, importValue.length() - 2)
                : importValue;
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        return testPackage.equals(normalized.substring(0, lastDot));
    }

    private static boolean mentionsImportedType(String usageText, String importValue) {
        if (usageText == null || isBlank(usageText)) {
            return false;
        }
        String simpleName = importedSimpleName(importValue);
        return !simpleName.isEmpty() && usageText.contains(simpleName);
    }

    private static String importedSimpleName(String importValue) {
        String normalized = normalizeImportValue(importValue);
        if (normalized.startsWith("static ")) {
            normalized = normalized.substring("static ".length()).trim();
        }
        if (normalized.endsWith(".*")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        int lastDot = normalized.lastIndexOf('.');
        return lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
    }

    private static int parseInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> sanitizeList(List<? extends T> out2) {
        List<T> out = new ArrayList<>();
        for (T value : out2) {
            if (value == null) {
                continue;
            }
            if (value instanceof String) {
                String s = ((String) value).trim();
                if (!s.isEmpty()) {
                    out.add((T) s);
                }
                continue;
            }
            out.add(value);
        }
        return out;
    }

    @SafeVarargs
    private static List<Object> mergeLists(List<Object>... groups) {
        LinkedHashSet<Object> merged = new LinkedHashSet<>();
        for (List<Object> group : groups) {
            merged.addAll(sanitizeList(group));
        }
        return new ArrayList<>(merged);
    }

    private static List<Object> summarizeConstraintList(Object value) {
        List<Object> out = new ArrayList<>();
        for (Object item : asList(value)) {
            Map<String, Object> map = asMap(item);
            String constraint = stringValue(map.get("constraint"));
            String reason = stringValue(map.get("reason"));
            String text = firstNonBlank(constraint,
                    reason);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return sanitizeList(out);
    }

    private static List<Object> summarizeInputTemplates(Object value) {
        List<Object> out = new ArrayList<>();
        for (Object item : asList(value)) {
            Map<String, Object> map = asMap(item);
            String target = stringValue(map.get("target"));
            List<Object> examples = sanitizeList(asList(map.get("examples")));
            String reason = stringValue(map.get("reason"));
            String summary = "";
            if (!target.isEmpty() && !examples.isEmpty()) {
                summary = target + ": try " + stringValue(examples.get(0));
            } else if (!examples.isEmpty()) {
                summary = "Try " + stringValue(examples.get(0));
            } else if (!reason.isEmpty()) {
                summary = reason;
            }
            if (!summary.isEmpty()) {
                out.add(summary);
            }
        }
        return sanitizeList(out);
    }

    private static List<Object> summarizeAssertionItems(Object value) {
        List<Object> out = new ArrayList<>();
        for (Object item : asList(value)) {
            Map<String, Object> map = asMap(item);
            String expression = stringValue(map.get("expression"));
            String expected = stringValue(map.get("expectedBehavior"));
            String summary = firstNonBlank(
                    expression.isEmpty() ? "" : expression + (expected.isEmpty() ? "" : " -> " + expected),
                    expected);
            if (!summary.isEmpty()) {
                out.add(summary);
            }
        }
        return sanitizeList(out);
    }

    private static List<Object> requiredKillSteps(String primarySink,
            Map<String, Object> testEntryContext,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        List<Object> out = new ArrayList<>();
        if ("exception".equals(primarySink)) {
            Map<String, Object> invocationPlan = asMap(testEntryContext.get("invocationPlan"));
            String invocation = firstNonBlank(stringValue(invocationPlan.get("invocationTemplate")),
                    stringValue(invocationPlan.get("setupTemplate")),
                    "the selected entry invocation");
            out.add("Drive " + invocation + " with inputs that make the mutated condition evaluate differently.");
            out.add("Assert opposite exception behavior at the entry boundary, including exception type and relevant message content.");
        } else {
            String sinkExpression = stringValue(propagation.get("observableSinkExpression"));
            if (!sinkExpression.isEmpty()) {
                out.add("Observe the primary public sink " + sinkExpression + " after the entry invocation.");
            }
        }
        String preferredCall = stringValue(observableSelection.get("preferredObservableCall"));
        if (!preferredCall.isEmpty() && !"exception".equals(primarySink)) {
            out.add("Use " + preferredCall + " only when it aligns with the selected primary sink.");
        }
        return sanitizeList(out);
    }

    private static Map<String, Object> harmonizeAssertionPlan(Map<String, Object> plan,
            String primarySink,
            Map<String, Object> testEntryContext,
            Map<String, Object> observableSelection,
            Map<String, Object> propagation) {
        Map<String, Object> out = new LinkedHashMap<>(plan);
        if (!"exception".equals(primarySink)) {
            return out;
        }
        Map<String, Object> invocationPlan = asMap(testEntryContext.get("invocationPlan"));
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("kind", "exception_assertion");
        primary.put("expression", firstNonBlank(stringValue(invocationPlan.get("invocationTemplate")),
                stringValue(invocationPlan.get("setupTemplate")),
                "entry invocation"));
        primary.put("expectedBehavior", firstNonBlank(
                "Observe whether entry invocation completes or throws a distinguishing exception.",
                stringValue(propagation.get("infectionSource"))));
        primary.put("priority", "high");
        out.put("primaryAssertions", listOf(primary));
        return out;
    }

    private Integer firstMutationLine() {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        for (ChangeRange range : ranges) {
            if (range == null) {
                continue;
            }
            if (range.startLine > 0) {
                return range.startLine;
            }
        }
        return null;
    }

    private static Map<String, Object> stripKeys(Map<String, Object> source, Set<String> keysToDrop) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!keysToDrop.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static Object valueFromMap(Map<String, Object> map, String key) {
        return map.get(key);
    }

    private static List<Object> collectPredicates(Map<String, Object>... sides) {
        return collectCpgScalarLists("path_predicates", sides);
    }

    private static List<Object> collectControlDeps(Map<String, Object>... sides) {
        return collectCpgScalarLists("control_deps_out", sides);
    }

    private static List<Object> collectDfgField(
            Map<String, Object> origin,
            Map<String, Object> mutated,
            Map<String, Object> entryOrigin,
            Map<String, Object> entryMutated,
            String fieldName) {
        LinkedHashSet<Object> values = new LinkedHashSet<>();
        for (Map<String, Object> side : listOf(origin, mutated, entryOrigin, entryMutated)) {
            for (Object item : asList(side.get("CPG"))) {
                Map<String, Object> cpg = asMap(item);
                values.addAll(asList(asMap(cpg.get("DFG")).get(fieldName)));
            }
        }
        return new ArrayList<>(values);
    }

    @SafeVarargs
    private static List<Object> collectCpgScalarLists(String fieldName, Map<String, Object>... sides) {
        LinkedHashSet<Object> values = new LinkedHashSet<>();
        for (Map<String, Object> side : sides) {
            for (Object item : asList(side.get("CPG"))) {
                Map<String, Object> cpg = asMap(item);
                values.addAll(asList(asMap(cpg.get("CFG")).get(fieldName)));
            }
        }
        return new ArrayList<>(values);
    }

    private MutationConfig buildMutationConfigSnapshot() {
        MutationConfig config = new MutationConfig();
        config.operator = firstNonBlank(MUTANT_NAME, Operator);
        config.methodName = METHOD_NAME_SUBSTR;
        config.className = simpleName(CLASS_NAME_P);
        config.classNameF = simpleName(CLASS_NAME_P);
        config.mutationStatement = Diff;
        config.rawTargetClassId = firstNonBlank(TARGET_CLASS_ID, CLASS_NAME_P, config.classNameF, config.className);
        config.packageName = firstNonBlank(TEST_GENERATION_PACKAGE, readPackageName(SRC_FILE_P));
        config.projectName = PROJECT_NAME;
        config.filepath = CLASSES_DIR_M == null ? "" : PathSanitizer.path(CLASSES_DIR_M).toString();
        return config;
    }

    private KillPlanEvidenceBuilder.Context buildKillPlanContext() {
        KillPlanEvidenceBuilder.Context ctx = new KillPlanEvidenceBuilder.Context();
        ctx.operator = Operator;
        ctx.diff = Diff;
        ctx.mutationClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;
        ctx.entryClassName = TEST_ENTRY_CLASS_NAME_P;
        ctx.entryMethodSig = TEST_ENTRY_METHOD_SUBSTR;
        ctx.testEntryKind = TEST_ENTRY_KIND;
        ctx.useReflectionFallback = USE_REFLECTION_FALLBACK;
        ctx.testCallChain = TEST_CALL_CHAIN;
        ctx.receiverConstruction = TEST_RECEIVER_CONSTRUCTION;
        ctx.receiverSetupTemplate = TEST_RECEIVER_SETUP_TEMPLATE;
        ctx.receiverInvocationTemplate = TEST_RECEIVER_INVOCATION_TEMPLATE;
        ctx.receiverStrategy = TEST_RECEIVER_STRATEGY;
        ctx.observablePlanKind = OBSERVABLE_PLAN_KIND;
        ctx.observableSetup = OBSERVABLE_SETUP;
        ctx.observableCall = OBSERVABLE_CALL;
        ctx.observableExpectedOriginal = OBSERVABLE_EXPECTED_ORIGINAL;
        ctx.observableReason = OBSERVABLE_REASON;
        ctx.branchReachabilityKind = BRANCH_REACHABILITY_KIND;
        ctx.branchReachabilityCondition = BRANCH_REACHABILITY_CONDITION;
        ctx.branchReachabilitySetup = BRANCH_REACHABILITY_SETUP;
        ctx.branchReachabilityReason = BRANCH_REACHABILITY_REASON;
        if (MUTATION_SEMANTIC_CONTEXT != null && MUTATION_SEMANTIC_CONTEXT.resolved) {
            ctx.semanticOriginalExpression = MUTATION_SEMANTIC_CONTEXT.semanticOriginalExpression;
            ctx.semanticMutantExpression = MUTATION_SEMANTIC_CONTEXT.semanticMutantExpression;
            ctx.ancestorPathPredicates = new ArrayList<String>(MUTATION_SEMANTIC_CONTEXT.ancestorGuards);
            ctx.requiredNonNullVariables = new LinkedHashSet<String>(MUTATION_SEMANTIC_CONTEXT.requiredNonNullVariables);
            ctx.semanticResolutionMode = MUTATION_SEMANTIC_CONTEXT.resolutionMode;
            ctx.semanticResolutionReason = MUTATION_SEMANTIC_CONTEXT.reason;
        }
        ctx.availablePublicMethods = AVAILABLE_PUBLIC_METHODS;
        ctx.availableSetupMethods = AVAILABLE_SETUP_METHODS;
        ctx.stateSetupPlan = STATE_SETUP_PLAN;
        ctx.skipTestGeneration = SKIP_TEST_GENERATION;
        ctx.skipReason = SKIP_REASON;
        return ctx;
    }

    private Map<String, Object> buildMutationKillPlanOutput() {
        return KillPlanEvidenceBuilder.buildMutationKillPlan(buildKillPlanContext());
    }

    private Map<String, Object> buildInputDistinguishPlanOutput() {
        return KillPlanEvidenceBuilder.buildInputDistinguishPlan(buildKillPlanContext());
    }

    private Map<String, Object> buildPropagationPlanOutput() {
        return KillPlanEvidenceBuilder.buildPropagationPlan(buildKillPlanContext());
    }

    private Map<String, Object> buildAssertionPlanOutput() {
        return KillPlanEvidenceBuilder.buildAssertionPlan(buildKillPlanContext());
    }

    private Map<String, Object> buildRipExecutionPlanOutput() {
        return KillPlanEvidenceBuilder.buildRipExecutionPlan(buildKillPlanContext());
    }

    private Map<String, Object> buildLiftedReachabilityPlanOutput() {
        return KillPlanEvidenceBuilder.buildLiftedReachabilityPlan(buildKillPlanContext());
    }

    private Map<String, Object> buildEvidenceQualityOutput() {
        return KillPlanEvidenceBuilder.buildEvidenceQuality(buildKillPlanContext());
    }

    private EntryControlEvidenceBuilder.Context buildEntryControlContext() {
        EntryControlEvidenceBuilder.Context ctx = new EntryControlEvidenceBuilder.Context();
        ctx.originJavaFile = SRC_FILE_P;
        ctx.entryClassName = TEST_ENTRY_CLASS_NAME_P;
        ctx.entryMethodSig = TEST_ENTRY_METHOD_SUBSTR;
        ctx.mutationClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;
        ctx.diff = Diff;
        ctx.observablePlanKind = OBSERVABLE_PLAN_KIND;
        ctx.observableCall = OBSERVABLE_CALL;
        ctx.useReflectionFallback = USE_REFLECTION_FALLBACK;
        ctx.skipTestGeneration = SKIP_TEST_GENERATION;
        ctx.skipReason = SKIP_REASON;
        return ctx;
    }

    private Map<String, Object> buildEntryParameterControlPlanOutput() {
        return EntryControlEvidenceBuilder.buildEntryParameterControlPlan(buildEntryControlContext());
    }

    private Map<String, Object> buildKillabilityPlanOutput() {
        return EntryControlEvidenceBuilder.buildKillabilityPlan(buildEntryControlContext());
    }

    private ObservableSelectionEvidenceBuilder.Context buildObservableSelectionContext() {
        ObservableSelectionEvidenceBuilder.Context ctx = new ObservableSelectionEvidenceBuilder.Context();
        ctx.originJavaFile = SRC_FILE_P;
        ctx.entryClassName = TEST_ENTRY_CLASS_NAME_P;
        ctx.entryMethodSig = TEST_ENTRY_METHOD_SUBSTR;
        ctx.mutationClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;
        ctx.diff = Diff;
        ctx.observablePlanKind = OBSERVABLE_PLAN_KIND;
        ctx.observableCall = OBSERVABLE_CALL;
        return ctx;
    }

    private Map<String, Object> buildObservableSelectionPlanOutput() {
        return ObservableSelectionEvidenceBuilder.buildObservableSelectionPlan(buildObservableSelectionContext());
    }

    private Map<String, Object> buildDependencyContextOutput() {
        DependencyContextBuilder.Context ctx = new DependencyContextBuilder.Context();

        // In the current same-file resolver, SRC_FILE_P contains both A and B.
        ctx.originJavaFile = SRC_FILE_P;

        // B: callable test entry selected by MethodEntryResolver.
        ctx.entryClassName = TEST_ENTRY_CLASS_NAME_P;
        ctx.entryMethodSig = TEST_ENTRY_METHOD_SUBSTR;

        // A: real mutation method used for A-side RIP/Jimple evidence.
        ctx.mutationClassName = CLASS_NAME_P;
        ctx.mutationMethodSig = METHOD_NAME_SUBSTR;

        ctx.testGenerationPackage = TEST_GENERATION_PACKAGE;
        ctx.testEntryKind = TEST_ENTRY_KIND;
        ctx.useReflectionFallback = USE_REFLECTION_FALLBACK;
        ctx.testCallChain = TEST_CALL_CHAIN;

        ctx.receiverOwnerKind = TEST_ENTRY_OWNER_KIND;
        ctx.receiverOwnerAbstract = TEST_ENTRY_OWNER_ABSTRACT;
        ctx.receiverOwnerInterface = TEST_ENTRY_OWNER_INTERFACE;
        ctx.receiverOwnerInstantiable = TEST_ENTRY_OWNER_INSTANTIABLE;
        ctx.receiverStrategy = TEST_RECEIVER_STRATEGY;
        ctx.receiverConstruction = TEST_RECEIVER_CONSTRUCTION;
        ctx.receiverNotes = TEST_RECEIVER_NOTES;
        ctx.receiverRuntimeClassName = TEST_RECEIVER_RUNTIME_CLASS;
        ctx.receiverRuntimeSootClassName = TEST_RECEIVER_RUNTIME_SOOT_CLASS;
        ctx.receiverDeclaringClassName = TEST_RECEIVER_DECLARING_CLASS;
        ctx.receiverDispatchTarget = TEST_RECEIVER_DISPATCH_TARGET;
        ctx.receiverDispatchesToMutationMethod = TEST_RECEIVER_DISPATCHES_TO_MUTATION;
        ctx.receiverSubclassOverridesMutationMethod = TEST_RECEIVER_SUBCLASS_OVERRIDES_MUTATION;
        ctx.receiverSetupTemplate = TEST_RECEIVER_SETUP_TEMPLATE;
        ctx.receiverInvocationTemplate = TEST_RECEIVER_INVOCATION_TEMPLATE;
        ctx.receiverResolutionReason = TEST_RECEIVER_RESOLUTION_REASON;
        ctx.receiverFactoryMethod = TEST_RECEIVER_FACTORY_METHOD;
        ctx.receiverBuilderClassName = TEST_RECEIVER_BUILDER_CLASS;
        ctx.receiverBuilderTerminalMethod = TEST_RECEIVER_BUILDER_TERMINAL_METHOD;
        ctx.receiverBuilderSetupChain = TEST_RECEIVER_BUILDER_SETUP_CHAIN;
        ctx.receiverAntiPatterns = TEST_RECEIVER_ANTI_PATTERNS;
        ctx.availablePublicMethods = AVAILABLE_PUBLIC_METHODS;
        ctx.availableSetupMethods = AVAILABLE_SETUP_METHODS;
        ctx.stateSetupPlan = STATE_SETUP_PLAN;
        ctx.observablePlanKind = OBSERVABLE_PLAN_KIND;
        ctx.observableSetup = OBSERVABLE_SETUP;
        ctx.observableCall = OBSERVABLE_CALL;
        ctx.observableExpectedOriginal = OBSERVABLE_EXPECTED_ORIGINAL;
        ctx.observableReason = OBSERVABLE_REASON;
        ctx.observableAntiPatterns = OBSERVABLE_ANTI_PATTERNS;
        ctx.branchReachabilityKind = BRANCH_REACHABILITY_KIND;
        ctx.branchReachabilityCondition = BRANCH_REACHABILITY_CONDITION;
        ctx.branchReachabilitySetup = BRANCH_REACHABILITY_SETUP;
        ctx.branchReachabilityReason = BRANCH_REACHABILITY_REASON;
        ctx.abstractMethodsToImplement = ABSTRACT_METHODS_TO_IMPLEMENT;
        ctx.allowedOverrides = ALLOWED_OVERRIDES;
        ctx.forbiddenOverrides = FORBIDDEN_OVERRIDES;
        ctx.testStubClassTemplate = TEST_STUB_CLASS_TEMPLATE;
        ctx.testStubConstructorTemplate = TEST_STUB_CONSTRUCTOR_TEMPLATE;
        ctx.skipTestGeneration = SKIP_TEST_GENERATION;
        ctx.skipReason = SKIP_REASON;

        return DependencyContextBuilder.build(ctx);
    }

    private Map<String, Object> buildEntryLiftedRipOutput() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        try {
            Map<String, Object> dependency = asMap(unwrapAnnotated(buildDependencyContextOutput()));
            Map<String, Object> relationship = asMap(dependency.get("relationship"));
            Map<String, Object> entryContext = asMap(dependency.get("testEntryContext"));

            boolean sameEntryAndMutation = sameMethodForEntryLiftedOutput(
                    TEST_ENTRY_CLASS_NAME_P,
                    TEST_ENTRY_METHOD_SUBSTR,
                    CLASS_NAME_P,
                    METHOD_NAME_SUBSTR);
            boolean needEntryLiftedEvidence = !sameEntryAndMutation && !USE_REFLECTION_FALLBACK;
            String entryKind = entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK,
                    TEST_RECEIVER_STRATEGY, ENTRY_INVOCATION_KIND);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", commented("Whether entry-lifted evidence was generated successfully", true));
            item.put("sameEntryAndMutation", commented(
                    "Whether callable test entry B is the same method as real mutation method A",
                    sameEntryAndMutation));
            item.put("needEntryLiftedEvidence", commented(
                    "Whether B-side entry evidence should be provided to the LLM",
                    needEntryLiftedEvidence));
            item.put("mutationClass", commented("Class containing real mutation method A", CLASS_NAME_P));
            item.put("mutationMethod",
                    commented("Real mutation method A", PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
            item.put("testEntryClass",
                    commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
            item.put("testEntryMethod", commented("Callable test entry method B",
                    PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
            item.put("testEntryKind", commented("Entry resolution kind", TEST_ENTRY_KIND));
            item.put("useReflectionFallback",
                    commented("Whether reflection fallback is required", USE_REFLECTION_FALLBACK));
            item.put("callChain",
                    commented("Resolved call chain from B to A", PromptSignatureFormatter.callChain(TEST_CALL_CHAIN)));
            item.put("notes", commented("Entry resolution notes", TEST_ENTRY_NOTES));
            item.put("testGenerationPackage", commented("Package used by generated tests", TEST_GENERATION_PACKAGE));
            item.put("entryInvocationKind",
                    commented("How generated tests should invoke callable test entry B", entryKind));
            item.put("entryGenerationPlan",
                    commented("Invocation plan and suggested test values for callable test entry B",
                            entryContext));
            item.put("entryRelation",
                    commented("Source-based relationship between A and B", buildSourceEntryRelation(relationship)));
            item.put("origin",
                    commented("Source-based entry evidence for original program",
                            buildSourceEntrySide(OID, false, "", sameEntryAndMutation)));
            item.put("mutated", commented(
                    "Source-based entry evidence for mutated program",
                    buildSourceEntrySide(mID, !sameEntryAndMutation,
                            sameEntryAndMutation ? ""
                                    : "B-side evidence reuses the same source-level invocation plan because the mutation is located in A.",
                            sameEntryAndMutation)));

            fallback.put("comment",
                    "Source and dependency based evidence for callable test entry B; Soot/Jimple is not used in the default path");
            fallback.put("item", item);
            return fallback;
        } catch (Throwable t) {
            boolean sameEntryAndMutation = sameMethodForEntryLiftedOutput(
                    TEST_ENTRY_CLASS_NAME_P,
                    TEST_ENTRY_METHOD_SUBSTR,
                    CLASS_NAME_P,
                    METHOD_NAME_SUBSTR);
            boolean needEntryLiftedEvidence = !sameEntryAndMutation && !USE_REFLECTION_FALLBACK;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", commented("Whether entry-lifted evidence was generated successfully", false));
            item.put("error", commented("Entry-lifted evidence construction error",
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
            item.put("sameEntryAndMutation", commented(
                    "Whether callable test entry B is the same method as real mutation method A",
                    sameEntryAndMutation));
            item.put("needEntryLiftedEvidence", commented(
                    "Whether B-side entry evidence should be provided to the LLM",
                    needEntryLiftedEvidence));
            item.put("mutationClass", commented("Class containing real mutation method A", CLASS_NAME_P));
            item.put("mutationMethod",
                    commented("Real mutation method A", PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
            item.put("testEntryClass",
                    commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
            item.put("testEntryMethod", commented("Callable test entry method B",
                    PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
            item.put("testEntryKind", commented("Entry resolution kind", TEST_ENTRY_KIND));
            item.put("useReflectionFallback",
                    commented("Whether reflection fallback is required", USE_REFLECTION_FALLBACK));
            item.put("callChain",
                    commented("Resolved call chain from B to A", PromptSignatureFormatter.callChain(TEST_CALL_CHAIN)));
            item.put("notes", commented("Entry resolution notes", TEST_ENTRY_NOTES));
            item.put("testGenerationPackage", commented("Package used by generated tests", TEST_GENERATION_PACKAGE));
            String entryKind = entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK,
                    TEST_RECEIVER_STRATEGY, ENTRY_INVOCATION_KIND);
            item.put("entryInvocationKind",
                    commented("How generated tests should invoke callable test entry B", entryKind));
            item.put("entryGenerationPlan",
                    commented("Invocation plan and suggested test values for callable test entry B",
                            buildFallbackEntryGenerationPlan(entryKind)));

            String errorText = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            item.put("origin",
                    commented("Fallback source evidence for original program after lightweight entry-lifted construction failed",
                            buildFallbackEntrySide(OID, false, "", errorText, sameEntryAndMutation)));
            item.put("mutated", commented(
                    "Fallback source evidence for mutated program after lightweight entry-lifted construction failed",
                    buildFallbackEntrySide(mID, !sameEntryAndMutation,
                            sameEntryAndMutation ? ""
                                    : "B-side source evidence would normally reuse the origin plan because the mutation is located in A, but construction failed before materialization.",
                            errorText, sameEntryAndMutation)));

            fallback.put("comment",
                    "Source-based entry-lifted evidence construction failed, but origin/mutated fallback branches are still provided");
            fallback.put("item", item);
            return fallback;
        }
    }

    private Map<String, Object> buildSourceEntryRelation(Map<String, Object> dependencyRelationship) {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("mutationClass", commented("Class containing the real mutation method A", CLASS_NAME_P));
        relation.put("mutationMethod", commented("Real mutation method A used for mutation-point evidence",
                PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
        relation.put("testEntryClass", commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
        relation.put("testEntryMethod", commented("Callable test entry method B that generated tests should target",
                PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
        relation.put("testEntryKind", commented("Entry type resolved by MethodEntryResolver", TEST_ENTRY_KIND));
        relation.put("useReflectionFallback", commented("Whether generated tests should use reflection fallback",
                USE_REFLECTION_FALLBACK));
        relation.put("testGenerationPackage",
                commented("Package used by generated tests when same-package access is needed",
                        TEST_GENERATION_PACKAGE));
        relation.put("recommendedTestTarget", commented("Recommended strategy for generated tests",
                recommendedTestTarget(TEST_ENTRY_KIND, USE_REFLECTION_FALLBACK)));
        relation.put("entryInvocationKind", commented("How generated tests should invoke callable test entry B",
                entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK, TEST_RECEIVER_STRATEGY,
                        ENTRY_INVOCATION_KIND)));
        relation.put("receiver", commented("Receiver construction metadata for callable test entry B",
                buildReceiverMetadataMap()));
        relation.put("callChain", commentedList("Resolved call chain from test entry B to mutation method A",
                listOf(PromptSignatureFormatter.callChain(TEST_CALL_CHAIN))));
        relation.put("notes", commentedList("Human-readable notes produced during entry resolution",
                TEST_ENTRY_NOTES == null || isBlank(TEST_ENTRY_NOTES) ? listOf() : listOf(TEST_ENTRY_NOTES)));
        relation.put("skipTestGeneration",
                commented("Whether LLM test generation should be skipped for this target", SKIP_TEST_GENERATION));
        relation.put("skipReason", commented("Reason for skipping LLM test generation when skipTestGeneration is true",
                safe(SKIP_REASON)));
        if (dependencyRelationship != null && !dependencyRelationship.isEmpty()) {
            relation.put("dependencyRelationship", dependencyRelationship);
        }
        return relation;
    }

    private Map<String, Object> buildSourceEntrySide(String id,
            boolean reusedFromOrigin,
            String reuseReason,
            boolean sameEntryAndMutation) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("id", commented("Program ID", id == null ? "" : id));
        side.put("entryClass", commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
        side.put("entryMethod",
                commented("Callable test entry method B", PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
        side.put("mutationClass", commented("Class containing the real mutation method A", CLASS_NAME_P));
        side.put("mutationMethod", commented("Real mutation method A reached from entry B",
                PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
        side.put("sameEntryAndMutation", commented("Whether B is the same method as A", sameEntryAndMutation));
        side.put("reusedFromOrigin", commented(
                "Whether this entry evidence reuses the origin-side source plan", reusedFromOrigin));
        side.put("reuseReason",
                commented("Reason for reusing origin-side entry evidence", reuseReason == null ? "" : reuseReason));
        side.put("focusMode", commented(
                "How focus units are selected: affected mutation units if B equals A, otherwise call sites from B to A",
                sameEntryAndMutation ? "MUTATION_AFFECTED_UNITS" : "ENTRY_CALL_SITES_TO_MUTATION"));
        side.put("content", commented("Source text of callable test entry B",
                safeExtractForEntry(SRC_FILE_P, TEST_ENTRY_CLASS_NAME_P, TEST_ENTRY_METHOD_SUBSTR)));
        side.put("IR", commented("Source-level placeholder for the entry graph IR", ""));
        side.put("focusUnits", commentedList(
                "Source-level focus markers for entry evidence",
                listOf(safe(TEST_ENTRY_METHOD_SUBSTR), safe(TEST_CALL_CHAIN))));
        side.put("callSitesToMutation", commentedList(
                "Resolved call chain from entry B to mutation A", listOf(PromptSignatureFormatter.callChain(TEST_CALL_CHAIN))));
        side.put("Paths", commentedList(
                "Source-level path summary for entry evidence",
                listOf(firstNonBlank(PromptSignatureFormatter.callChain(TEST_CALL_CHAIN),
                        TEST_ENTRY_CLASS_NAME_P + "#" + PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)
                                + " -> " + CLASS_NAME_P + "#" + PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)))));
        Map<String, Object> cpg = new LinkedHashMap<>();
        cpg.put("Path", PromptSignatureFormatter.callChain(TEST_CALL_CHAIN));
        cpg.put("CFG", mapOf(
                "path_predicates", listOf("source-level predicate summary only"),
                "control_deps_out", listOf("source-level control dependency summary only")));
        cpg.put("DFG", mapOf(
                "defs_point", listOf("source-level def summary only"),
                "uses_toward_output", listOf("source-level use summary only"),
                "kill_set", listOf("source-level kill summary only")));
        side.put("CPG", commentedList(
                "Source-level entry evidence summary that preserves the expected JSON shape without Jimple generation",
                listOf(cpg)));
        side.put("warnings", commentedList("Non-fatal warnings collected during source-based entry evidence construction",
                sameEntryAndMutation
                        ? listOf("Soot/Jimple disabled; using source-level evidence only for the same method entry.")
                        : listOf("Soot/Jimple disabled; B-side source evidence reuses the same invocation plan as the origin side.")));
        return side;
    }

    private Map<String, Object> buildFallbackEntryGenerationPlan(String entryKind) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("entryInvocationKind",
                commented("How generated tests should invoke callable test entry B", entryKind));
        plan.put("recommendedTestTarget", commented("Recommended invocation target for generated tests",
                recommendedTestTarget(TEST_ENTRY_KIND, USE_REFLECTION_FALLBACK)));
        plan.put("receiver",
                commented("Receiver construction metadata resolved for callable entry B", buildReceiverMetadataMap()));
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("notes",
                "Use DependencyContext.testEntryContext and A-side origin/mutated RIP evidence to generate the test.");
        plan.put("invocationPlan", commented(
                "Fallback invocation plan; real entry-side graph construction failed before a richer plan could be generated",
                invocation));
        plan.put("suggestedTestValues", commented("Fallback suggested test values", mapOf("items", listOf())));
        plan.put("assertionPlan", commented("Fallback assertion plan",
                buildFallbackAssertionPlan(entryKind)));
        return plan;
    }

    private Map<String, Object> buildFallbackAssertionPlan(String entryKind) {
        Map<String, Object> plan = new LinkedHashMap<>();
        List<Object> targets = new ArrayList<>();
        List<Object> assertions = new ArrayList<>();
        List<String> anti = new ArrayList<>();

        String all = (Diff == null ? "" : Diff) + "\n" + METHOD_NAME_SUBSTR + "\n" + TEST_ENTRY_METHOD_SUBSTR + "\n"
                + TEST_ENTRY_CLASS_NAME_P;
        boolean exceptionLike = TEST_ENTRY_CLASS_NAME_P != null && TEST_ENTRY_CLASS_NAME_P.contains("Exception");
        boolean messageLike = all.toLowerCase(Locale.ROOT).contains("message") || all.contains("could be")
                || all.contains("Ambiguous option");
        boolean appendableLike = all.contains("append") || all.contains("Appendable");

        if (exceptionLike && messageLike) {
            targets.add(mapOf(
                    "kind", commented("Observable target kind", "exception_message"),
                    "expression", commented("Java expression to observe", "subject.getMessage()"),
                    "confidence", commented("Confidence", "medium")));
            assertions.add(mapOf(
                    "priority", commented("Priority", "high"),
                    "template", commented("JUnit assertion template", all.contains("could be")
                            ? "assertTrue(subject.getMessage().contains(\"could be\"));"
                            : "assertNotNull(subject.getMessage());"),
                    "reason", commented("Reason", "The mutation appears to affect exception/message construction.")));
            anti.add(
                    "Do not compare getMatchingOptions() with a message string; it returns a Collection, not the exception message.");
        }
        if (appendableLike) {
            targets.add(mapOf(
                    "kind", commented("Observable target kind", "external_mutable_state"),
                    "expression", commented("Java expression to observe", "output.toString()"),
                    "confidence", commented("Confidence", "medium")));
            assertions.add(mapOf(
                    "priority", commented("Priority", "high"),
                    "template", commented("JUnit assertion template", "assertEquals(\"x\", output.toString());"),
                    "reason", commented("Reason", "The mutation may affect Appendable/StringBuilder state.")));
            anti.add(
                    "Do not assert only returned receiver identity when the mutation changes an external side effect.");
        }
        if (targets.isEmpty()) {
            targets.add(mapOf(
                    "kind", commented("Observable target kind", "fallback"),
                    "expression",
                    commented("Java expression to observe", "public return/getter/state/exception output"),
                    "confidence", commented("Confidence", "low")));
        }

        plan.put("comparisonPolicy", commented("General comparison policy for generated tests",
                "Prefer mutation-sensitive public observable sinks over arbitrary object equality."));
        plan.put("primaryObservableTargets", commentedList("Fallback observable targets", targets));
        plan.put("recommendedAssertions", commentedList("Fallback assertion templates", assertions));
        plan.put("antiPatterns", commentedList("Fallback anti-patterns", anti));
        return plan;
    }

    private Map<String, Object> buildReceiverMetadataMap() {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("ownerKind", TEST_ENTRY_OWNER_KIND);
        receiver.put("ownerAbstract", TEST_ENTRY_OWNER_ABSTRACT);
        receiver.put("ownerInterface", TEST_ENTRY_OWNER_INTERFACE);
        receiver.put("ownerInstantiable", TEST_ENTRY_OWNER_INSTANTIABLE);
        receiver.put("strategy", TEST_RECEIVER_STRATEGY);
        receiver.put("construction", TEST_RECEIVER_CONSTRUCTION);
        receiver.put("notes", TEST_RECEIVER_NOTES);
        return receiver;
    }

    private Map<String, Object> buildTestEntryOutput() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", TEST_ENTRY_KIND);
        entry.put("useReflectionFallback", USE_REFLECTION_FALLBACK);
        entry.put("mutationClass", CLASS_NAME_P);
        entry.put("mutationMethod", PromptSignatureFormatter.method(METHOD_NAME_SUBSTR));
        entry.put("testEntryClass", TEST_ENTRY_CLASS_NAME_P);
        entry.put("testEntryMethod", PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR));
        entry.put("callChain", PromptSignatureFormatter.callChain(TEST_CALL_CHAIN));
        entry.put("notes", TEST_ENTRY_NOTES);
        entry.put("testGenerationPackage", TEST_GENERATION_PACKAGE);
        entry.put("recommendedTestTarget", recommendedTestTarget(TEST_ENTRY_KIND, USE_REFLECTION_FALLBACK));
        entry.put("entryInvocationKind", entryInvocationKind(TEST_ENTRY_METHOD_SUBSTR, USE_REFLECTION_FALLBACK,
                TEST_RECEIVER_STRATEGY, ENTRY_INVOCATION_KIND));

        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("ownerKind", TEST_ENTRY_OWNER_KIND);
        receiver.put("ownerAbstract", TEST_ENTRY_OWNER_ABSTRACT);
        receiver.put("ownerInterface", TEST_ENTRY_OWNER_INTERFACE);
        receiver.put("ownerInstantiable", TEST_ENTRY_OWNER_INSTANTIABLE);
        receiver.put("strategy", TEST_RECEIVER_STRATEGY);
        receiver.put("construction", TEST_RECEIVER_CONSTRUCTION);
        receiver.put("notes", TEST_RECEIVER_NOTES);
        entry.put("receiver", receiver);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("originEntryContent",
                safeExtractForEntry(SRC_FILE_P, TEST_ENTRY_CLASS_NAME_P, TEST_ENTRY_METHOD_SUBSTR));
        source.put("mutantEntryContent",
                safeExtractForEntry(SRC_FILE_M, TEST_ENTRY_CLASS_NAME_M, TEST_ENTRY_METHOD_SUBSTR));
        entry.put("entrySource", source);

        return entry;
    }

    private static String entryInvocationKind(String sig, boolean reflection, String receiverStrategy,
            String configured) {
        if (configured != null && !isBlank(configured)) {
            return configured;
        }
        if (reflection) {
            return "REFLECTION_INVOCATION";
        }
        if (sig != null) {
            int lp = sig.indexOf('(');
            int us = sig.indexOf('_');
            if (lp > 0 && !(us > 0 && us < lp)) {
                return "CONSTRUCTOR_INVOCATION";
            }
        }
        if ("STATIC_NO_RECEIVER".equals(receiverStrategy)) {
            return "STATIC_METHOD_INVOCATION";
        }
        return "INSTANCE_METHOD_INVOCATION";
    }

    private static String recommendedTestTarget(String kind, boolean reflection) {
        if (reflection) {
            return "reflect_mutation_method";
        }
        if (kind == null) {
            return "call_test_entry_method";
        }
        switch (kind) {
            case "PUBLIC_DIRECT_ENTRY":
                return "call_public_method";
            case "PACKAGE_PRIVATE_DIRECT_ENTRY":
                return "call_package_private_method";
            case "PROTECTED_SAME_PACKAGE_DIRECT_ENTRY":
                return "call_protected_same_package_method";
            case "ASCENDED_PUBLIC_CALLER":
            case "ASCENDED_PACKAGE_PRIVATE_CALLER":
            case "ASCENDED_PROTECTED_SAME_PACKAGE_CALLER":
                return "call_test_entry_method";
            default:
                return "call_test_entry_method";
        }
    }

    private static String safeExtractForEntry(String javaFile, String className, String methodSig) {
        try {
            return cachedExtractMethod(javaFile, className, methodSig, true);
        } catch (Exception e) {
            return "<entry source extraction failed: " + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()) + ">";
        }
    }

    private static Map<String, Object> commented(String comment, Object item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("item", item);
        return m;
    }

    private static Map<String, Object> commentedList(String comment, Collection<?> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("comment", comment);
        m.put("items", items == null ? listOf() : items);
        return m;
    }

    private Map<String, Object> buildFallbackEntrySide(String id,
            boolean reusedFromOrigin,
            String reuseReason,
            String errorText,
            boolean sameEntryAndMutation) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("id", commented("Program ID", id == null ? "" : id));
        side.put("entryClass", commented("Class containing callable test entry method B", TEST_ENTRY_CLASS_NAME_P));
        side.put("entryMethod",
                commented("Callable test entry method B", PromptSignatureFormatter.method(TEST_ENTRY_METHOD_SUBSTR)));
        side.put("mutationClass", commented("Class containing the real mutation method A", CLASS_NAME_P));
        side.put("mutationMethod", commented("Real mutation method A reached from entry B",
                PromptSignatureFormatter.method(METHOD_NAME_SUBSTR)));
        side.put("sameEntryAndMutation", commented("Whether B is the same method as A", sameEntryAndMutation));
        side.put("reusedFromOrigin", commented(
                "Whether this entry-side evidence reuses the origin-side Soot/Jimple graph", reusedFromOrigin));
        side.put("reuseReason",
                commented("Reason for reusing origin-side entry evidence", reuseReason == null ? "" : reuseReason));
        side.put("focusMode", commented(
                "How focus units are selected: affected mutation units if B equals A, otherwise call sites from B to A",
                sameEntryAndMutation ? "MUTATION_AFFECTED_UNITS" : "ENTRY_CALL_SITES_TO_MUTATION"));
        side.put("content", commented("Source text of callable test entry method B",
                safeExtractForEntry(SRC_FILE_P, TEST_ENTRY_CLASS_NAME_P, TEST_ENTRY_METHOD_SUBSTR)));
        side.put("IR", commented("Jimple intermediate representation of callable test entry method B", ""));
        side.put("focusUnits", commentedList(
                "Jimple units in entry method B used as the focus of entry-lifted RIP evidence", listOf()));
        side.put("callSitesToMutation", commentedList(
                "Invoke statements in entry method B that directly call the real mutation method A", listOf()));
        side.put("Paths", commentedList(
                "Control-flow paths in entry method B that pass through the selected focus units", listOf()));
        side.put("CPG", commentedList(
                "CFG and DFG information related to entry-side paths, forming an entry-level Code Property Graph",
                listOf()));
        side.put("warnings", commentedList("Non-fatal warnings collected during entry-lifted evidence construction",
                listOf("EntryLiftedRIP real graph construction failed: " + errorText)));
        return side;
    }

    private static boolean sameMethodForEntryLiftedOutput(
            String entryClassName,
            String entryMethodSig,
            String mutationClassName,
            String mutationMethodSig) {
        return normalizeClassNameForEntryLifted(entryClassName)
                .equals(normalizeClassNameForEntryLifted(mutationClassName))
                && normalizeSigForEntryLifted(entryMethodSig)
                        .equals(normalizeSigForEntryLifted(mutationMethodSig));
    }

    private static String normalizeClassNameForEntryLifted(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replace('$', '.');
    }

    private static String normalizeSigForEntryLifted(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "").replace('$', '.');
    }

    public static void saveCPG(String clsName, String methodName, String outputDir) throws Exception {

        // Load target class
        Scene.v().loadNecessaryClasses();
        SootClass sc = Scene.v().forceResolve(clsName, SootClass.BODIES);
        sc.setApplicationClass();
        Scene.v().loadClassAndSupport(clsName);

        // Perform AST analysis
        ASTVisualizer ast = new ASTVisualizer(clsName, methodName, outputDir);
        ast.visualize();

        // Perform CFG analysis
        CFGVisualizer cfg = new CFGVisualizer(clsName, methodName, outputDir);
        cfg.visualize();

        // Perform DFG analysis
        DFGVisualizer dfg = new DFGVisualizer(clsName, methodName, outputDir);
        dfg.analyze();

        // // Convert all generated .dot files to images
        // DotToImageConverter.convertDotFilesInDirectory(new File(outputDir +
        // "/graph"));
    }

    /**
     * Cache only origin-side fields that are invariant for the same original
     * method.
     * Do not cache affected/paths/cpg here, because they depend on the current
     * pair-specific ranges.
     */
    private static final class OriginStaticSnapshot {
        String id;
        String content;
        String ir;
    }

    private String originStaticCacheKey() {
        return SRC_FILE_P + "##" + CLASS_NAME_P + "##" + METHOD_NAME_SUBSTR + "##" + CLASSES_DIR_P;
    }

    private static String methodTextKey(String javaFile, String className, String methodSig, boolean includeSignature) {
        return javaFile + "##" + className + "##" + methodSig + "##" + includeSignature;
    }

    private static String cachedExtractMethod(String javaFile, String className, String methodSig,
            boolean includeSignature) throws Exception {
        String key = methodTextKey(javaFile, className, methodSig, includeSignature);
        String hit = METHOD_TEXT_CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        String value = extractMethodAsOneLine(javaFile, className, methodSig, includeSignature);
        METHOD_TEXT_CACHE.put(key, value);
        return value;
    }
}
