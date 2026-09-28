package mujava.testgenerator.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds compiler-specific diagnosis text to repair prompts without duplicating full evidence sections.
 */
public final class CompilerDiagnosisAppender {
    private CompilerDiagnosisAppender() {
    }

    public static void append(StringBuilder sb, PromptEvidence e, String compileError) {
        String err = compileError == null ? "" : compileError.toLowerCase(Locale.ROOT);
        JSONObject receiver = e == null ? null : e.invocation.optJSONObject("receiver");
        String strategy = receiver == null ? "" : receiver.optString("strategy", "");

        CompileErrorKind kind = CompileErrorClassifier.classify(compileError);
        sb.append("Compiler-specific diagnosis:\n");

        boolean wrote = false;

        if (kind == CompileErrorKind.ABSTRACT_STUB_INCOMPLETE) {
            sb.append("- The generated test stub/subclass is incomplete. Use the provided testStubClassTemplate when present, or implement every entry in receiver.abstractMethodsToImplement / receiver.allowedOverrides in one pass. Do not only add the single method reported by javac.\n");
            appendReceiverTemplateHints(sb, receiver);
            wrote = true;
        }

        if (kind == CompileErrorKind.OVERRIDE_FORBIDDEN_OR_SIGNATURE
                || kind == CompileErrorKind.OVERRIDE_ACCESS_WEAKENING) {
            sb.append("- The failure is caused by an invalid override. Override only methods explicitly listed in receiver.allowedOverrides. Remove overrides matching receiver.forbiddenOverrides. Keep the exact access modifier from the provided template.\n");
            appendReceiverTemplateHints(sb, receiver);
            wrote = true;
        }

        if (kind == CompileErrorKind.UNDEFINED_VARIABLE_OR_SETUP_MISMATCH) {
            sb.append("- The previous code referenced a variable that was not declared in setup/call. Keep variable names consistent across EXECUTABLE_TEST_PLAN.requiredSetup, INVOCATION.setup, INVOCATION.call, OBSERVABLE_PLAN, and ASSERTIONS. If an assertion references an undefined variable, either declare it from the evidence setup or use the observablePlan variable instead.\n");
            wrote = true;
        }

        if (kind == CompileErrorKind.INVENTED_GETTER_OR_API
                || (err.contains("cannot find symbol") && err.contains("method"))) {
            sb.append("- The previous code called a method that does not exist on the receiver. Use exact method names from PUBLIC_API.availablePublicMethods / availableSetupMethods. Do not guess JavaBean names such as getX(), isX(), or setX().\n");
            if (e != null && e.codeKbCompilableApiFacts().length() > 0) {
                sb.append("- COMPILABLE_API_FACTS.exactCallableSignatures is the hard whitelist. Match the exact arity/signature instead of inventing a near miss.\n");
                appendCompilableFactHint(sb, e, compileError);
            }
            if (e != null && e.observablePlan.length() > 0) {
                sb.append("- If OBSERVABLE_PLAN.observableCall is present, prefer that observable. If OBSERVABLE_PLAN.kind is REFLECTION_FIELD_READ_AFTER_SETTER or REFLECTION_FIELD_READ_AFTER_CONSTRUCTION, use the reflection setup and do not replace it with a guessed getter.\n");
            }
            wrote = true;
        }

        if (e != null && e.observablePlan.length() > 0) {
            String observableKind = e.observablePlan.optString("kind", "");
            if ("CONSTRUCTOR_PUBLIC_GETTER_OBSERVABLE".equals(observableKind)
                    || "CONSTRUCTOR_PUBLIC_METHOD_DEPENDS_ON_STATE".equals(observableKind)
                    || "RECOVERED_CONSTRUCTOR_PUBLIC_OBSERVER".equals(observableKind)) {
                sb.append("- The constructor mutation already has a post-construction observable plan. Keep the constructor invocation valid and fix only the observable/assertion code after construction.\n");
                wrote = true;
            }
        }

        if (kind == CompileErrorKind.DATAINPUT_ANONYMOUS_STUB) {
            sb.append("- Do not hand-write an anonymous java.io.DataInput implementation. Use java.io.DataInputStream over java.io.ByteArrayInputStream, or the concrete DataInput setup from the evidence.\n");
            wrote = true;
        }

        if ("STATIC_FACTORY_BUILDER".equals(strategy) || CompileErrorClassifier.looksLikeFactoryBuilderError(err)) {
            String setup = e == null ? "" : firstSetupStatement(e.invocation);
            String terminal = receiver == null ? "" : receiver.optString("builderTerminalMethod", "");
            String factory = receiver == null ? "" : receiver.optString("factoryMethod", "");
            sb.append("- The previous code used an invalid constructor or builder terminal method.\n");
            if (!factory.isEmpty()) {
                sb.append("- Use the static factory method from evidence: ").append(factory).append(".\n");
            }
            if (!terminal.isEmpty()) {
                sb.append("- The builder terminal method from evidence is ").append(terminal).append("(); use it exactly.\n");
            }
            if (!setup.isEmpty()) {
                sb.append("- Use this setup statement unless syntax requires line wrapping: ").append(setup).append("\n");
            }
            sb.append("- Do not call new TargetClass(), new Builder(), or builder.build() unless those exact forms appear in INVOCATION.setup.\n");
            wrote = true;
        }

        if (err.contains("must be first statement") || err.contains("super must be first statement")) {
            sb.append("- In a constructor of a nested test stub, super(...) must be the first statement. Move any local declarations after super(...).\n");
            wrote = true;
        }

        if (!wrote) {
            if (e != null && e.codeKbCompilableApiFacts().length() > 0
                    && err.contains("compilable_api_facts")) {
                appendCompilableFactHint(sb, e, compileError);
            }
            sb.append("- Use the compact evidence below as the source of truth. Make the smallest changes necessary for javac while preserving a mutation-sensitive assertion.\n");
        }
        sb.append('\n');
    }

    private static void appendReceiverTemplateHints(StringBuilder sb, JSONObject receiver) {
        if (receiver == null) {
            return;
        }
        String stub = receiver.optString("testStubClassTemplate", "").trim();
        if (!stub.isEmpty()) {
            sb.append("- receiver.testStubClassTemplate is available; prefer copying that template rather than creating a new subclass.\n");
        }
        JSONArray abstractMethods = receiver.optJSONArray("abstractMethodsToImplement");
        if (abstractMethods != null && abstractMethods.length() > 0) {
            sb.append("- Implement all receiver.abstractMethodsToImplement entries, not only the method named in the current javac error.\n");
        }
        JSONArray allowed = receiver.optJSONArray("allowedOverrides");
        if (allowed != null && allowed.length() > 0) {
            sb.append("- allowedOverrides count=").append(allowed.length()).append("; do not add other @Override methods.\n");
        }
    }

    private static String firstSetupStatement(JSONObject invocation) {
        if (invocation == null) {
            return "";
        }
        JSONArray arr = invocation.optJSONArray("setup");
        if (arr == null || arr.length() == 0) {
            return "";
        }
        return arr.optString(0, "").trim();
    }

    private static void appendCompilableFactHint(StringBuilder sb, PromptEvidence e, String compileError) {
        if (e == null) {
            return;
        }
        List<String> allowedStatic = e.codeKbAllowedStaticMethods(8);
        if (!allowedStatic.isEmpty()) {
            sb.append("- Exact allowed static factories/methods from source: ").append(allowedStatic).append("\n");
        }
        List<String> allowedConstructors = e.codeKbAllowedConstructors(4);
        if (!allowedConstructors.isEmpty()) {
            sb.append("- Exact allowed constructors from source: ").append(allowedConstructors).append("\n");
        }
        List<String> forbiddenCalls = e.codeKbForbiddenCalls(6);
        if (!forbiddenCalls.isEmpty()) {
            sb.append("- Known forbidden direct calls: ").append(forbiddenCalls).append("\n");
        }
        OffendingCall offending = parseOffendingCall(compileError);
        if (offending == null) {
            return;
        }
        List<String> alternatives = nearestAllowedCalls(e, offending);
        if (!alternatives.isEmpty()) {
            sb.append("- The failing call looks like a near miss for these real source signatures: ")
                    .append(alternatives).append("\n");
        }
    }

    private static OffendingCall parseOffendingCall(String compileError) {
        if (compileError == null || compileError.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("'([A-Za-z0-9_$]+)\\.([A-Za-z0-9_$]+)\\((\\d+) args\\)'")
                .matcher(compileError);
        if (!matcher.find()) {
            return null;
        }
        return new OffendingCall(matcher.group(1), matcher.group(2), parseInt(matcher.group(3)));
    }

    private static List<String> nearestAllowedCalls(PromptEvidence e, OffendingCall offending) {
        List<String> candidates = new ArrayList<String>(e.codeKbExactCallableSignatures(20));
        if (candidates.isEmpty()) {
            candidates.addAll(e.codeKbAllowedStaticMethods(12));
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ranked = new ArrayList<String>(candidates);
        ranked.sort(new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Integer.compare(score(right, offending), score(left, offending));
            }
        });
        List<String> out = new ArrayList<String>();
        for (String candidate : ranked) {
            if (score(candidate, offending) <= 0) {
                continue;
            }
            out.add(candidate);
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static int score(String candidate, OffendingCall offending) {
        String methodName = extractMethodName(candidate);
        int argCount = extractArgCount(candidate);
        int score = 0;
        if (methodName.equals(offending.methodName)) {
            score += 100;
        }
        if (methodName.startsWith(offending.methodName) || offending.methodName.startsWith(methodName)) {
            score += 60;
        }
        if (!methodName.isEmpty() && !offending.methodName.isEmpty()
                && methodName.charAt(0) == offending.methodName.charAt(0)) {
            score += 10;
        }
        if (argCount == offending.argCount) {
            score += 40;
        } else {
            score -= Math.min(20, Math.abs(argCount - offending.argCount) * 10);
        }
        return score;
    }

    private static String extractMethodName(String signature) {
        if (signature == null) {
            return "";
        }
        int paren = signature.indexOf('(');
        if (paren < 0) {
            return "";
        }
        String head = signature.substring(0, paren).trim();
        int space = head.lastIndexOf(' ');
        if (space >= 0) {
            head = head.substring(space + 1);
        }
        int dot = head.lastIndexOf('.');
        if (dot >= 0) {
            head = head.substring(dot + 1);
        }
        return head.trim();
    }

    private static int extractArgCount(String signature) {
        if (signature == null) {
            return -1;
        }
        int open = signature.indexOf('(');
        int close = signature.indexOf(')', open + 1);
        if (open < 0 || close < 0 || close <= open + 1) {
            return close == open + 1 ? 0 : -1;
        }
        String args = signature.substring(open + 1, close).trim();
        if (args.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < args.length(); i++) {
            if (args.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static final class OffendingCall {
        final String owner;
        final String methodName;
        final int argCount;

        OffendingCall(String owner, String methodName, int argCount) {
            this.owner = owner;
            this.methodName = methodName == null ? "" : methodName;
            this.argCount = argCount;
        }
    }
}
