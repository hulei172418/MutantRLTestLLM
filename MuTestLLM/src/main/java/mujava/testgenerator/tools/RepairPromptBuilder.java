package mujava.testgenerator.tools;

import static mujava.testgenerator.tools.InitialPromptBuilder.appendCompactSection;
import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Builds targeted repair prompts using javac diagnostics and compact evidence.
 */
public final class RepairPromptBuilder {
    private RepairPromptBuilder() {
    }

    public static String build(Request request,
                               PromptEvidence e,
                               String testSetName,
                               String lastCode,
                               String compileError) {
        String packageName = packageNameOf(testSetName);
        String simpleClassName = simpleNameOf(testSetName);
        CompileErrorKind kind = CompileErrorClassifier.classify(compileError);

        StringBuilder sb = new StringBuilder(16384);
        sb.append("Repair the Java JUnit4 test so it compiles in the existing project.\n");
        sb.append("Return only one complete Java source file. Do not include markdown fences or explanations.\n\n");

        sb.append("Non-negotiable constraints:\n");
        sb.append("- public class name must be: ").append(simpleClassName).append("\n");
        if (!packageName.isEmpty()) {
            sb.append("- package declaration must be: package ").append(packageName).append(";\n");
        }
        sb.append("- JUnit4 only; no extra third-party test libraries.\n");
        sb.append("- Preserve intent: kill mutant ").append(request.mutantName)
                .append(" for method ").append(request.methodSignature).append(".\n");
        sb.append("- Preserve CANONICAL_CORE first; use EXECUTABLE_TEST_PLAN and INVOCATION only as source-compatible syntax templates.\n");
        MavenCompilerConfigResolver.CompilerLevel compilerLevel =
                MavenCompilerConfigResolver.resolve(request == null ? "" : request.sourceModuleHome);
        if (compilerLevel.isSpecified()) {
            sb.append("- Java language/API compatibility must follow the tested module POM: ")
                    .append(compilerLevel.promptDescription())
                    .append(". Do not use syntax or APIs above this level.\n");
        }
        sb.append("- Preserve at least one mutation-sensitive assertion when the evidence makes one available.\n\n");

        sb.append("Javac error category: ").append(kind.name()).append("\n");
        sb.append("Javac error:\n").append(compileError == null ? "" : compileError).append("\n\n");

        CompilerDiagnosisAppender.append(sb, e, compileError);

        sb.append("Repair focus:\n");
        switch (kind) {
            case LLM_OUTPUT_TRUNCATED:
                sb.append("- The previous LLM output was truncated. Return a shorter complete Java file.\n");
                sb.append("- Do not include explanations or reasoning. Return only Java source.\n");
                break;

            case EMPTY_LLM_CONTENT:
                sb.append("- The previous response had no visible Java code. Return one complete Java source file only.\n");
                break;

            case EXPECTED_CLASS_NOT_FOUND:
                sb.append("- javac appeared to run but the expected test .class was not generated.\n");
                sb.append("- Ensure package and public class name exactly match the required test FQN.\n");
                sb.append("- Do not define a different public class name.\n");
                break;

            case VOID_VALUE_MISUSE:
                sb.append("- The previous code treated a void method as a value.\n");
                sb.append("- Do not write result = subject.voidMethod(...).\n");
                sb.append("- Call the void method as a statement, then assert public state, exception, side effect, or OBSERVABLE_PLAN output.\n");
                break;

            case ABSTRACT_STUB_INCOMPLETE:
                sb.append("- The test stub/subclass did not implement all required abstract methods.\n");
                sb.append("- If INVOCATION.receiver.testStubClassTemplate is present, use that nested class template directly.\n");
                sb.append("- Otherwise implement every method listed in INVOCATION.receiver.abstractMethodsToImplement or allowedOverrides in one pass.\n");
                sb.append("- Do not only add the single abstract method mentioned by javac.\n");
                break;

            case UNDEFINED_VARIABLE_OR_SETUP_MISMATCH:
                sb.append("- A variable used by the test was not declared or setup/assertion variable names are inconsistent.\n");
                sb.append("- Rebuild setup from EXECUTABLE_TEST_PLAN.requiredSetup and INVOCATION.setup.\n");
                sb.append("- Use OBSERVABLE_PLAN.observableCall and ASSERTIONS only when their variables are declared.\n");
                sb.append("- Do not reference helper variables that are not created in the generated source.\n");
                break;

            case MISSING_CLASS_OR_IMPORT:
                sb.append("- Fix package/imports/class names using INVOCATION.imports and target package.\n");
                sb.append("- Do not invent classes not listed in the evidence, JDK, JUnit4, or project types.\n");
                sb.append("- If the missing class is a support/stub class, copy EXECUTABLE_TEST_PLAN.supportClasses inside the test class.\n");
                break;

            case TEST_STUB_MISMATCH:
                sb.append("- The generated test subclass/stub is invalid.\n");
                sb.append("- Follow INVOCATION.receiver.testStubClassTemplate and testStubConstructorTemplate if present.\n");
                sb.append("- Put nested stub classes inside the generated test class, outside @Test methods.\n");
                sb.append("- Do not override final methods. Implement every method listed in abstractMethodsToImplement.\n");
                sb.append("- Put super(...) first in any constructor.\n");
                break;

            case FACTORY_BUILDER_MISMATCH:
                sb.append("- The failure is caused by invalid factory/builder construction.\n");
                sb.append("- Use EXECUTABLE_TEST_PLAN.requiredSetup or INVOCATION.setup.\n");
                sb.append("- Do not call new TargetClass() when receiver.strategy says STATIC_FACTORY_BUILDER.\n");
                sb.append("- Do not call new Builder() if the builder constructor is private.\n");
                sb.append("- Use receiver.builderTerminalMethod exactly; do not guess build() when evidence says get().\n");
                break;

            case CONSTRUCTOR_MISMATCH:
                sb.append("- Fix constructor arguments using EXECUTABLE_TEST_PLAN.requiredSetup, INVOCATION.setup, and INVOCATION.call.\n");
                sb.append("- If the entry is a constructor, instantiate with new B(...); do not call it like a normal method.\n");
                sb.append("- If receiver.strategy is STATIC_FACTORY_BUILDER, use the factory/builder setup instead of constructor guessing.\n");
                break;

            case ABSTRACT_INSTANTIATION:
                sb.append("- The receiver/test target is abstract or not directly instantiable.\n");
                sb.append("- Do not instantiate abstract classes directly.\n");
                sb.append("- If receiver.strategy is TEST_STUB_SUBCLASS, use the provided testStubClassTemplate.\n");
                sb.append("- If receiver.strategy is CONCRETE_SUBCLASS_INHERITED_METHOD, instantiate runtimeReceiverClass only if setup evidence gives a valid construction/factory.\n");
                sb.append("- If receiver.strategy is ANONYMOUS_SUBCLASS, implement all required abstract methods supplied by evidence.\n");
                break;

            case METHOD_NOT_FOUND:
                sb.append("- Remove calls to non-existing methods.\n");
                sb.append("- Use exact method names from PUBLIC_API.availablePublicMethods / availableSetupMethods.\n");
                sb.append("- If COMPILABLE_API_FACTS.exactCallableSignatures / allowedStaticFactories / allowedConstructors are present, they are the hard whitelist. Match their parameter counts exactly.\n");
                sb.append("- If you chain calls, only chain when COMPILABLE_API_FACTS.exactCallableSignatures proves the previous call returns a type that actually declares the next method.\n");
                sb.append("- Do not treat a static factory as a fluent builder unless the evidence shows the returned type has the next exact instance method.\n");
                sb.append("- If evidence does not prove a fluent continuation, stop at the factory/constructor result and use that object directly.\n");
                if (!e.codeKbAllowedStaticMethods(8).isEmpty()) {
                    sb.append("- Exact allowed static factories/methods: ").append(e.codeKbAllowedStaticMethods(8)).append("\n");
                }
                if (!e.codeKbAllowedConstructors(4).isEmpty()) {
                    sb.append("- Exact allowed constructors: ").append(e.codeKbAllowedConstructors(4)).append("\n");
                }
                if (!e.codeKbExactCallableSignatures(12).isEmpty()) {
                    sb.append("- Exact callable signatures for chainability checks: ").append(e.codeKbExactCallableSignatures(12)).append("\n");
                }
                sb.append("- Do not invent getters/setters/readers.\n");
                sb.append("- If OBSERVABLE_PLAN.observableCall exists, use it as the observable.\n");
                sb.append("- If EXECUTABLE_TEST_PLAN.entryCall exists, prefer it over guessed method calls.\n");
                sb.append("- If OBSERVABLE_PLAN is constructor-related, keep the constructor call valid and fix only the post-construction observable step.\n");
                break;

            case METHOD_ARGUMENT_MISMATCH:
                sb.append("- The previous call used the wrong argument count/types or confused a static method with an instance method.\n");
                sb.append("- Match COMPILABLE_API_FACTS.exactCallableSignatures exactly: owner, static-vs-instance form, arity, parameter order, and parameter types.\n");
                sb.append("- For a static signature, prefer Owner.method(arg1, arg2, ...); do not shorten it to receiver.method(...) unless the source signature is genuinely instance-based.\n");
                sb.append("- Do not invent overloads to make the old call shape compile. Rewrite the call to a real exact signature.\n");
                break;

            case INCOMPATIBLE_TYPES:
                sb.append("- Fix operand and assertion types.\n");
                sb.append("- For double/float assertions, use a delta. For arrays, use assertArrayEquals.\n");
                sb.append("- Use ASSERTIONS.requiredToKill only when the variables are defined and types are compatible.\n");
                break;

            case CHECKED_EXCEPTION_NOT_HANDLED:
                sb.append("- A checked exception from the constructor or target call was not handled.\n");
                sb.append("- Prefer adding throws Exception to every @Test method over broad try/catch blocks.\n");
                sb.append("- If the test intentionally asserts an exception, catch/assert only the expected behavioral exception and still declare throws Exception for setup/IO.\n");
                break;

            case PRIVATE_ACCESS:
                sb.append("- The previous code directly accessed a private member. This is illegal Java. Preserve the same semantic observable, but change only its access mechanism.\n");
                sb.append("- If PUBLIC_API/COMPILABLE_API_FACTS provides a public getter or observer for the same state, use it instead of the private member.\n");
                sb.append("- Prefer public entry method B from ENTRY/INVOCATION/EXECUTABLE_TEST_PLAN.\n");
                sb.append("- Use reflection only if EXECUTABLE_TEST_PLAN.accessConstraints.reflectionAllowed=true or OBSERVABLE_PLAN explicitly provides reflection.\n");
                break;

            case PROTECTED_ACCESS:
                sb.append("- The previous code directly used a protected constructor/member from an illegal context.\n");
                sb.append("- Prefer same-package access, factory/public setup, or test-stub templates from the evidence.\n");
                sb.append("- Use reflection only if the evidence explicitly allows/requires it.\n");
                break;

            case PACKAGE_PRIVATE_ACCESS:
                sb.append("- Keep the generated test in the exact package shown above.\n");
                sb.append("- Do not import package-private target classes from parent/sibling packages.\n");
                sb.append("- If same-package still cannot access the member, use public entry/stub/template instead of direct access.\n");
                break;

            case INVENTED_GETTER_OR_API:
                sb.append("- The previous test invented a project API that does not exist.\n");
                sb.append("- Remove invented getters/setters/helpers such as getWidth(), getHeight(), getIgnore(), unless they appear in PUBLIC_API.availablePublicMethods.\n");
                sb.append("- If OBSERVABLE_PLAN.kind=REFLECTION_FIELD_READ_AFTER_SETTER, use reflection setup and observableCall.\n");
                sb.append("- If OBSERVABLE_PLAN.kind=REFLECTION_FIELD_READ_AFTER_CONSTRUCTION, use the reflection field-read plan after construction; do not replace it with a guessed getter.\n");
                break;

            case OVERRIDE_FORBIDDEN_OR_SIGNATURE:
                sb.append("- The previous test generated an invalid @Override.\n");
                sb.append("- Override only methods listed in INVOCATION.receiver.allowedOverrides.\n");
                sb.append("- Never override methods listed in INVOCATION.receiver.forbiddenOverrides.\n");
                sb.append("- Remove @Override from helper methods unless the exact signature is listed in allowedOverrides.\n");
                break;

            case OVERRIDE_ACCESS_WEAKENING:
                sb.append("- The previous override reduced method access privileges.\n");
                sb.append("- Copy the exact access modifier from receiver.allowedOverrides or testStubClassTemplate.\n");
                sb.append("- If the superclass method is public, the override must be public.\n");
                break;

            case DATAINPUT_ANONYMOUS_STUB:
                sb.append("- The previous test hand-wrote an anonymous java.io.DataInput implementation.\n");
                sb.append("- Use new java.io.DataInputStream(new java.io.ByteArrayInputStream(new byte[] {...})) or the concrete setup from evidence.\n");
                break;

            case CONSTRUCTOR_AS_METHOD:
                sb.append("- The previous test called a constructor as if it were an instance method.\n");
                sb.append("- Constructors must be invoked with new ClassName(...), not subject.ClassName(...).\n");
                sb.append("- Use INVOCATION.setup, receiver.testStubConstructorTemplate, or the constructor evidence.\n");
                break;

            default:
                sb.append("- Make the smallest changes necessary to compile while preserving the mutation-sensitive assertion.\n");
                sb.append("- Prefer EXECUTABLE_TEST_PLAN and INVOCATION over guessed APIs.\n");
                break;
        }
        sb.append('\n');

        sb.append("Repair evidence priority:\n");
        appendSemanticStageRepairConstraints(sb, request);
        sb.append("1b. Treat COMPILABLE_API_FACTS.exactCallableSignatures and forbiddenCalls as a compile-time whitelist/blacklist, not as optional hints.\n");
        sb.append("1c. For chain calls, require proof from exactCallableSignatures that the previous return type declares the next method; otherwise do not chain.\n");
        sb.append("2. Keep receiver/stub templates and guardrails as constraints, but do not add unnecessary code just because a guardrail mentions it.\n");
        sb.append("3. Use mutation evidence only to preserve the killing intent, not to invent inaccessible internal calls.\n\n");

        if (!e.receiverStrategy().trim().isEmpty()
                && ("FACTORY_OR_REFLECTION_REQUIRED".equals(e.receiverStrategy())
                || "STATIC_FACTORY_BUILDER".equals(e.receiverStrategy())
                || !e.receiverOwnerInstantiable())) {
            sb.append("Soft construction guidance:\n");
            sb.append("- If direct construction looks suspicious, prefer INVOCATION.receiver.factoryMethod, INVOCATION.setup, or a static creator listed in PUBLIC_API.availablePublicMethods.\n");
            sb.append("- Do not force this mechanically if the existing test already compiles; use it as a repair direction.\n\n");
        }
        if (!e.preferredObservableCall().trim().isEmpty() && !e.prefersExceptionAssertion()) {
            sb.append("Soft assertion guidance:\n");
            sb.append("- If the previous test degraded into assertNotNull/simple success, try upgrading the oracle toward ")
                    .append(e.preferredObservableCall()).append(".\n");
            sb.append("- Use this as a preferred repair direction, not as a mandatory exact source form.\n\n");
        }
        if (e.hasLockedPrimaryChainObservable() && !e.prefersExceptionAssertion()) {
            sb.append("Primary-chain oracle guidance:\n");
            if (isObservableRelaxedStage(request)) {
                sb.append("- PRIMARY_CHAIN is a preferred historical C in RELAX_OBSERVABLE_ORACLE, not a lock. Preserve the current candidate's evidence-backed C/O during compile repair.\n");
            } else {
                sb.append("- PRIMARY_CHAIN locks the primary observable C for the current semantic stage: ")
                        .append(e.primaryChainOracleCall()).append(".\n");
            }
            sb.append("- Keep expectedOriginal as the executable assertion target; expectedMutant is only the contrasting mutant behavior.\n");
            sb.append("- Do not weaken an exact String/object assertion into result != baselineValue merely to compile.\n\n");
        }

        appendCompactSection(sb, "ENTRY", e.entry);
        appendCompactSection(sb, "CANONICAL_CORE", e.canonicalCore);
        appendCompactSection(sb, "ENTRY_SELECTION", e.entrySelection);
        appendCompactSection(sb, "EXECUTABLE_TEST_PLAN", e.executableTestPlan);
        appendCompactSection(sb, "INVOCATION_WITH_RECEIVER_AND_STUB_RULES", e.invocation);
        appendCompactSection(sb, "COMPILABLE_API_FACTS", e.codeKbCompilableApiFacts());
        appendCompactSection(sb, "COMPILATION_FACTS", e.compilationFacts);
        appendCompactSection(sb, "PRIMARY_CHAIN", e.primaryChain);
        appendCompactSection(sb, "ALTERNATIVE_CHAINS", e.alternativeChains);
        appendCompactSection(sb, "PUBLIC_API_AND_COMPILATION_GUARDRAILS", e.publicApiEvidence);
        appendCompactSection(sb, "OBSERVABLE_PLAN", e.observablePlan);
        appendCompactSection(sb, "ASSERTIONS", e.assertions);
        appendCompactSection(sb, "RANKED_STRATEGY_CANDIDATES", GenerationStrategyAdvisor.build(request, e));

        appendObservablePlanRepairHints(sb, e);

        if (needsMutationContext(kind)) {
            appendCompactSection(sb, "MUTATION", e.mutation);
            appendCompactSection(sb, "MUTATION_EVIDENCE", e.mutationEvidence);
            appendCompactSection(sb, "MUTATION_GRAPH_EVIDENCE_A_CPG", e.mutationGraphEvidence);
            if (e.needEntryLiftedEvidence && e.entryEvidence.length() > 0) {
                appendCompactSection(sb, "ENTRY_EVIDENCE", e.entryEvidence);
            }
            if (e.needEntryLiftedEvidence && e.entryGraphEvidence.length() > 0) {
                appendCompactSection(sb, "ENTRY_GRAPH_EVIDENCE_B_CPG", e.entryGraphEvidence);
            }
        }

        sb.append("Previous test code:\n");
        sb.append(lastCode == null ? "" : lastCode).append("\n\n");
        sb.append("Return only corrected Java source code.\n");
        return sb.toString();
    }

    private static void appendSemanticStageRepairConstraints(StringBuilder sb, Request request) {
        String stage = request == null || request.semanticStage == null
                ? "STRICT_CORE"
                : request.semanticStage.trim().toUpperCase(java.util.Locale.ROOT);
        sb.append("1. semanticStage=").append(stage)
                .append(". Compilation repair fixes Java legality without undoing the semantic plan selected for this stage.\n");
        if ("RELAX_OBSERVABLE_ORACLE".equals(stage)) {
            sb.append("1a. HARD semantic invariants: selected entry B and mandatory R/I. C/O were intentionally relaxed; preserve the PREVIOUS TEST CODE's evidence-backed observable/oracle instead of forcing CANONICAL_CORE C/O back in. If javac rejects an access mechanism, use an equivalent public getter/observer/call path to the same chosen behavior.\n");
            sb.append("1d. Repair may change imports, declarations, casts, construction syntax, static-vs-instance syntax, and legal access paths. Do not change B/R/I or silently revert the candidate to the old canonical C/O.\n");
        } else if ("RELAX_ORACLE".equals(stage)) {
            sb.append("1a. HARD semantic invariants: selected B, mandatory R/I, and observable C. O was intentionally relaxed; preserve the PREVIOUS TEST CODE's chosen oracle intent instead of restoring the original CANONICAL_CORE oracle.\n");
            sb.append("1d. Repair may change imports, declarations, casts, construction syntax, static-vs-instance syntax, and legal access paths. Do not change B/R/I/C or revert O solely for convenience.\n");
        } else {
            sb.append("1a. STRICT_CORE: preserve CANONICAL_CORE selected B, mandatory R/I, C, and oracle direction while fixing javac errors with executable API evidence.\n");
            sb.append("1d. Repair may change imports, declarations, casts, construction syntax, static-vs-instance syntax, and legal access paths, but must not flip mandatory guards, drop mandatory cofactors, change infection direction, or replace C/O.\n");
        }
    }

    private static boolean isObservableRelaxedStage(Request request) {
        return request != null
                && request.semanticStage != null
                && "RELAX_OBSERVABLE_ORACLE".equalsIgnoreCase(request.semanticStage.trim());
    }

    private static void appendObservablePlanRepairHints(StringBuilder sb, PromptEvidence e) {
        if (e == null || e.observablePlan == null || e.observablePlan.length() == 0) {
            return;
        }
        String kind = e.observablePlan.optString("kind", "").trim();
        if (kind.isEmpty()) {
            return;
        }
        sb.append("Observable-plan repair hints:\n");
        sb.append("- OBSERVABLE_PLAN.kind = ").append(kind).append("\n");
        if ("CONSTRUCTOR_PUBLIC_GETTER_OBSERVABLE".equals(kind)
                || "RECOVERED_CONSTRUCTOR_PUBLIC_OBSERVER".equals(kind)) {
            sb.append("- Repair around the constructor plus public accessor pattern. Do not rewrite it into a guessed helper API.\n");
        } else if ("CONSTRUCTOR_PUBLIC_METHOD_DEPENDS_ON_STATE".equals(kind)) {
            sb.append("- Keep the constructor setup, then call the provided public behavior method after construction.\n");
        } else if ("REFLECTION_FIELD_READ_AFTER_CONSTRUCTION".equals(kind)
                || "REFLECTION_FIELD_READ_AFTER_SETTER".equals(kind)) {
            sb.append("- Preserve the reflection setup/imports and the observableCall exactly. Do not replace reflection with a guessed getter.\n");
        } else if ("THROWABLE_MESSAGE".equals(kind)) {
            sb.append("- Preserve message assertions on the constructed throwable. Do not downgrade to object-not-null or class-only checks.\n");
        }
        sb.append('\n');
    }

    private static boolean needsMutationContext(CompileErrorKind kind) {
        return kind == CompileErrorKind.METHOD_NOT_FOUND
                || kind == CompileErrorKind.METHOD_ARGUMENT_MISMATCH
                || kind == CompileErrorKind.CONSTRUCTOR_MISMATCH
                || kind == CompileErrorKind.INCOMPATIBLE_TYPES
                || kind == CompileErrorKind.PRIVATE_ACCESS
                || kind == CompileErrorKind.PROTECTED_ACCESS
                || kind == CompileErrorKind.PACKAGE_PRIVATE_ACCESS
                || kind == CompileErrorKind.OTHER;
    }
}
