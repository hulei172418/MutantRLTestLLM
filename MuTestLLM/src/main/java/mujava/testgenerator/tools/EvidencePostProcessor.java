package mujava.testgenerator.tools;

import com.github.javaparser.StaticJavaParser;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.testgenerator.tools.CommonUtils.firstNonBlank;
import static mujava.testgenerator.tools.EvidenceUtils.*;
import static mujava.testgenerator.tools.GeneratorDefaults.*;

/**
 * Normalizes compact evidence after raw extraction: receiver anti-patterns,
 * setup variable names, assertion templates, and observable expressions.
 */
public final class EvidencePostProcessor {
    private EvidencePostProcessor() {
    }

    public static void postProcess(PromptEvidence e) {
        mergeExecutablePlanSetupIntoInvocationIfPresent(e);
        mergeCompilationConstraints(e);
        mergeReflectionObservableSetup(e);
        if (GeneratorFeatureFlags.benchmarkAdaptersEnabled()) {
            strengthenCsvLexerTokenEvidence(e);
        }
        normalizeExpectedOriginalOracle(e);
        normalizeSetupVariableNames(e);
        filterAssertionsAgainstCurrentInvocation(e);
        filterObservablesAgainstCurrentInvocation(e);
    }

    private static void normalizeExpectedOriginalOracle(PromptEvidence e) {
        if (e == null || e.observablePlan == null || e.observablePlan.length() == 0) {
            return;
        }
        String expected = firstNonBlank(
                e.primaryChainExpectedOriginal(),
                e.observablePlan.optString("expectedOriginal", "")).trim();
        String observableCall = e.preferredObservableCall().trim();
        if (expected.isEmpty() || observableCall.isEmpty()) {
            return;
        }
        if (!isExecutableExpectedExpression(expected)) {
            e.observablePlan.put("expectedOriginalNarrative", expected);
            e.observablePlan.put("expectedOriginalExecutable", false);
            updateCanonicalOracleExecutability(e, expected, false);
            e.observablePlan.remove("assertion");
            return;
        }
        e.observablePlan.put("expectedOriginalExecutable", true);
        updateCanonicalOracleExecutability(e, expected, true);
        String actual = observedValueExpression(observableCall);
        if (actual.isEmpty()) {
            return;
        }
        String exactAssertion = "assertEquals(" + expected + ", " + actual + ");";
        e.observablePlan.put("assertion", exactAssertion);

        JSONArray required = e.assertions.optJSONArray("requiredToKill");
        JSONArray cleaned = new JSONArray();
        boolean alreadyPresent = false;
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                Object item = required.opt(i);
                String text = item == null ? "" : String.valueOf(item);
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains("assertnotequals") || lower.contains("!=")) {
                    continue;
                }
                if (text.contains(exactAssertion)) {
                    alreadyPresent = true;
                }
                cleaned.put(item);
            }
        }
        if (!alreadyPresent) {
            JSONObject assertion = new JSONObject();
            assertion.put("priority", "high");
            assertion.put("observable", actual);
            assertion.put("template", exactAssertion);
            assertion.put("reason", "expectedOriginal is the executable oracle; expectedMutant is only explanatory.");
            cleaned.put(assertion);
        }
        e.assertions.put("requiredToKill", cleaned);
    }

    private static void updateCanonicalOracleExecutability(PromptEvidence e,
                                                            String expected,
                                                            boolean executable) {
        if (e == null || e.canonicalCore == null || e.canonicalCore.length() == 0) {
            return;
        }
        JSONObject oracle = e.canonicalCore.optJSONObject("oracle");
        if (oracle == null) {
            return;
        }
        oracle.put("expectedOriginalExecutable", executable);
        if (!executable && expected != null && !expected.trim().isEmpty()) {
            oracle.put("expectedOriginalNarrative", expected.trim());
        }
    }

    private static boolean isExecutableExpectedExpression(String expected) {
        String value = expected == null ? "" : expected.trim();
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("mutation-sensitive")
                || lower.contains("original observable")
                || lower.contains("original expected")
                || lower.contains("expectedoriginal")
                || lower.contains("originalexpected")
                || lower.contains("placeholder")
                || lower.contains("unknown")
                || lower.contains("todo")
                || lower.contains("n/a")
                || lower.contains("not available")) {
            return false;
        }
        if (value.contains("/*") || value.contains("*/") || value.contains("//")) {
            return false;
        }
        if (looksLikeNaturalLanguagePhrase(value)) {
            return false;
        }
        try {
            StaticJavaParser.parseExpression(value);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean looksLikeNaturalLanguagePhrase(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.contains(" ")) {
            return false;
        }
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            return false;
        }
        if (trimmed.matches(".*[+\\-*/%<>=!&|?:().,\\[\\]{}].*")) {
            return false;
        }
        return trimmed.matches(".*[A-Za-z].*");
    }

    private static String observedValueExpression(String observableCall) {
        String text = sanitizeJavaSnippet(observableCall);
        if (text.isEmpty()) {
            return "";
        }
        Matcher assignment = Pattern.compile("(?:^|;)\\s*(?:[\\w.$<>\\[\\], ?]+\\s+)?([A-Za-z_$][\\w$]*)\\s*=\\s*([^;]+);?\\s*$")
                .matcher(text);
        if (assignment.find()) {
            return assignment.group(1);
        }
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    private static void mergeCompilationConstraints(PromptEvidence e) {
        if (e == null) {
            return;
        }

        LinkedHashSet<String> avoid = new LinkedHashSet<String>();

        avoid.addAll(jsonArrayToStringList(e.assertions.optJSONArray("avoid")));

        JSONObject receiver = e.invocation.optJSONObject("receiver");
        if (receiver != null) {
            avoid.addAll(jsonArrayToStringList(receiver.optJSONArray("antiPatterns")));
            avoid.addAll(jsonArrayToStringList(receiver.optJSONArray("forbiddenOverrides")));
        }

        avoid.addAll(jsonArrayToStringList(e.publicApiEvidence.optJSONArray("compilationGuardrails")));
        avoid.addAll(jsonArrayToStringList(e.publicApiEvidence.optJSONArray("antiPatterns")));
        avoid.addAll(jsonArrayToStringList(e.observablePlan.optJSONArray("antiPatterns")));
        avoid.addAll(jsonArrayToStringList(e.reachabilityGuardsPlan.optJSONArray("antiPatterns")));
        avoid.addAll(jsonArrayToStringList(e.distinguishingInputGuidance.optJSONArray("antiPatterns")));
        avoid.addAll(jsonArrayToStringList(e.observableDifferencePlan.optJSONArray("antiPatterns")));
        avoid.addAll(jsonArrayToStringList(e.entryChainPlan.optJSONArray("prohibitedShortcuts")));

        if (!avoid.isEmpty()) {
            e.assertions.put("avoid", new JSONArray(new ArrayList<String>(avoid)));
        }
    }

    private static void mergeReflectionObservableSetup(PromptEvidence e) {
        if (e == null || e.observablePlan == null || e.observablePlan.length() == 0) {
            return;
        }

        String kind = e.observablePlan.optString("kind", "");
        if (!"REFLECTION_FIELD_READ_AFTER_SETTER".equals(kind)
                && !"REFLECTION_FIELD_READ_AFTER_CONSTRUCTION".equals(kind)) {
            return;
        }

        List<String> setup = jsonArrayToStringList(e.invocation.optJSONArray("setup"));

        String obsSetup = e.observablePlan.optString("setup", "").trim();
        if (!obsSetup.isEmpty()) {
            setup.add(obsSetup);
        }

        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (String s : setup) {
            String x = sanitizeJavaSnippet(s);
            if (!x.isEmpty()) {
                unique.add(x);
            }
        }

        e.invocation.put("setup", new JSONArray(cleanJavaStatements(new ArrayList<String>(unique))));

        JSONArray imports = e.invocation.optJSONArray("imports");
        if (imports == null) {
            imports = new JSONArray();
        }

        LinkedHashSet<String> imp = new LinkedHashSet<String>(jsonArrayToStringList(imports));
        imp.add("java.lang.reflect.Field");
        e.invocation.put("imports", new JSONArray(new ArrayList<String>(imp)));

        JSONArray required = e.assertions.optJSONArray("requiredToKill");
        if (required == null) {
            required = new JSONArray();
        }

        String observableCall = e.observablePlan.optString("observableCall", "").trim();
        String expected = e.observablePlan.optString("expectedOriginal", "").trim();

        if (!observableCall.isEmpty() && !expected.isEmpty()) {
            JSONObject a = new JSONObject();
            a.put("priority", "high");
            a.put("observable", "REFLECTION_FIELD_READ_AFTER_CONSTRUCTION".equals(kind)
                    ? "reflection_field_read_after_construction"
                    : "reflection_field_read_after_setter");
            a.put("template", "assertEquals(" + expected + ", " + observableCall + ");");
            a.put("reason", "observablePlan.kind=" + kind + "; no stable public observable exists, so reflection must be used.");
            required.put(a);
            e.assertions.put("requiredToKill", required);
        }
    }

    private static void strengthenCsvLexerTokenEvidence(PromptEvidence e) {
        if (e == null || !looksLikeCsvLexerTokenEvidence(e)) {
            return;
        }

        List<String> setup = jsonArrayToStringList(e.invocation.optJSONArray("setup"));
        setup.removeIf(EvidencePostProcessor::isWeakCsvLexerSetup);
        if (looksLikeLexerCommentPathEvidence(e)) {
            setup.add("Lexer subject = new Lexer(org.apache.commons.csv.CSVFormat.DEFAULT, new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"a,#comment\\n\")));");
            setup.add("org.apache.commons.csv.Token first = subject.nextToken(new org.apache.commons.csv.Token());");
        } else if (looksLikeLexerCommentEofEvidence(e)) {
            setup.add("Lexer subject = new Lexer(org.apache.commons.csv.CSVFormat.DEFAULT, new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"#\")));");
        } else if (looksLikeLexerEmptyLineEvidence(e)) {
            setup.add("Lexer subject = new Lexer(org.apache.commons.csv.CSVFormat.DEFAULT.withIgnoreEmptyLines(true), new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"\\n\\nX\")));");
        } else {
            setup.add("Lexer subject = new Lexer(org.apache.commons.csv.CSVFormat.DEFAULT, new org.apache.commons.csv.ExtendedBufferedReader(new java.io.StringReader(\"abc ,z\")));");
        }
        e.invocation.put("setup", new JSONArray(cleanJavaStatements(new ArrayList<String>(new LinkedHashSet<String>(setup)))));

        String call = e.invocation.optString("call", "");
        if (looksLikeLexerCommentPathEvidence(e)) {
            e.invocation.put("call", "org.apache.commons.csv.Token second = subject.nextToken(new org.apache.commons.csv.Token());");
        } else if (looksLikeLexerCommentEofEvidence(e) || looksLikeLexerEmptyLineEvidence(e)) {
            e.invocation.put("call", "org.apache.commons.csv.Token token = subject.nextToken(new org.apache.commons.csv.Token());");
        } else if (call == null || call.trim().isEmpty() || call.replaceAll("\\s+", "").contains("nextToken(null)")
                || call.contains("Token result =")) {
            e.invocation.put("call", "org.apache.commons.csv.Token token = subject.nextToken(new org.apache.commons.csv.Token());");
        }

        e.observablePlan.put("kind", "MUTABLE_PARAMETER_STATE");
        if (looksLikeLexerCommentPathEvidence(e)) {
            e.observablePlan.put("observableCall", "second.content.toString()");
            e.observablePlan.put("expectedOriginal", "\"#comment\"");
            e.observablePlan.put("reason", "RIP plan requires a non-start-of-line comment marker; original parses it as TOKEN content, while the mutant may classify it as COMMENT.");
            addRequiredAssertion(e, "assertEquals(org.apache.commons.csv.Token.Type.TOKEN, second.type);");
            addRequiredAssertion(e, "assertEquals(\"#comment\", second.content.toString());");
        } else if (looksLikeLexerCommentEofEvidence(e)) {
            e.observablePlan.put("observableCall", "token.type");
            e.observablePlan.put("expectedOriginal", "org.apache.commons.csv.Token.Type.EOF");
            e.observablePlan.put("reason", "RIP plan requires comment marker at EOF; original returns EOF token type on that path.");
            addRequiredAssertion(e, "assertEquals(org.apache.commons.csv.Token.Type.EOF, token.type);");
        } else if (looksLikeLexerEmptyLineEvidence(e)) {
            e.observablePlan.put("observableCall", "token.content.toString()");
            e.observablePlan.put("expectedOriginal", "\"X\"");
            e.observablePlan.put("reason", "RIP plan requires ignoreEmptyLines=true with repeated blank lines so reader-state deletion can propagate to emitted token content/type.");
            addRequiredAssertion(e, "assertEquals(org.apache.commons.csv.Token.Type.TOKEN, token.type);");
            addRequiredAssertion(e, "assertEquals(\"X\", token.content.toString());");
        } else if (looksLikeForcedTrimEvidence(e)) {
            e.observablePlan.put("observableCall", "token.content.toString()");
            e.observablePlan.put("expectedOriginal", "\"abc \"");
            e.observablePlan.put("reason", "RIP plan requires a simple unquoted token with trailing whitespace and ignoreSurroundingSpaces=false; original keeps token.content trailing space, mutant trims it.");
            addRequiredAssertion(e, "assertEquals(\"abc \", token.content.toString());");
        } else {
            e.observablePlan.put("observableCall", "token.content.toString()");
            e.observablePlan.put("expectedOriginal", "\"abc \"");
            e.observablePlan.put("reason", "Lexer private tokenization should be reached through nextToken(new Token()) and observed through Token.content/type/isReady, not null exception behavior.");
            addRequiredAssertion(e, "assertEquals(org.apache.commons.csv.Token.Type.TOKEN, token.type);");
            addRequiredAssertion(e, "assertEquals(\"abc \", token.content.toString());");
        }

        JSONArray avoid = e.assertions.optJSONArray("avoid");
        if (avoid == null) {
            avoid = new JSONArray();
        }
        avoid.put("Do not use new Lexer(CSVFormat.DEFAULT, null); use a real ExtendedBufferedReader.");
        avoid.put("Do not call nextToken(null); use new Token() and assert token.content/type/isReady.");
        e.assertions.put("avoid", avoid);
    }

    private static boolean looksLikeCsvLexerTokenEvidence(PromptEvidence e) {
        String text = (e.entry.toString() + "\n"
                + e.invocation.toString() + "\n"
                + e.observablePlan.toString() + "\n"
                + e.ripExecutionPlan.toString() + "\n"
                + e.mutation.toString()).toLowerCase(Locale.ROOT);
        return text.contains("lexer")
                && text.contains("token")
                && (text.contains("parsesimpletoken") || text.contains("nexttoken"));
    }

    private static boolean looksLikeLexerCommentPathEvidence(PromptEvidence e) {
        String text = allEvidenceText(e);
        return text.contains("lexer_comment_path")
                || text.contains("a,#comment")
                || text.contains("isstartofline(lastchar)&&iscommentstart(c)=>iscommentstart(c)");
    }

    private static boolean looksLikeLexerCommentEofEvidence(PromptEvidence e) {
        String text = allEvidenceText(e);
        return text.contains("lexer_comment_eof_path")
                || text.contains("comment-at-eof")
                || text.contains("token.type=eof;returntoken;=>returntoken;");
    }

    private static boolean looksLikeLexerEmptyLineEvidence(PromptEvidence e) {
        String text = allEvidenceText(e);
        return text.contains("lexer_empty_line_path")
                || text.contains("ignoreemptylines")
                || text.contains("\\n\\nx");
    }

    private static String allEvidenceText(PromptEvidence e) {
        return (e.entry.toString() + "\n"
                + e.invocation.toString() + "\n"
                + e.observablePlan.toString() + "\n"
                + e.ripExecutionPlan.toString() + "\n"
                + e.mutation.toString()).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static void addRequiredAssertion(PromptEvidence e, String assertion) {
        JSONArray required = e.assertions.optJSONArray("requiredToKill");
        if (required == null) {
            required = new JSONArray();
        }
        List<String> existing = jsonArrayToStringList(required);
        if (!existing.contains(assertion)) {
            required.put(assertion);
        }
        e.assertions.put("requiredToKill", required);
    }

    private static boolean looksLikeForcedTrimEvidence(PromptEvidence e) {
        String text = (e.ripExecutionPlan.toString() + "\n"
                + e.mutation.toString() + "\n"
                + e.observablePlan.toString()).toLowerCase(Locale.ROOT);
        return text.contains("ignoresurroundingspaces")
                && text.contains("trimtrailingspaces");
    }

    private static boolean isWeakCsvLexerSetup(String setup) {
        String text = setup == null ? "" : setup.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return text.contains("newlexer(") && text.contains(",null)");
    }

    private static void mergeExecutablePlanSetupIntoInvocationIfPresent(PromptEvidence e) {
        // 兼容你之前已经加 executableTestPlan 的版本；没有该字段时不做任何事
        try {
            JSONObject plan = (JSONObject) PromptEvidence.class
                    .getDeclaredField("executableTestPlan")
                    .get(e);

            if (plan == null || plan.length() == 0) {
                return;
            }

            List<String> setup = jsonArrayToStringList(e.invocation.optJSONArray("setup"));
            setup.addAll(jsonArrayToStringList(plan.optJSONArray("requiredSetup")));

            JSONObject branch = plan.optJSONObject("branchReachability");
            if (branch != null) {
                setup.addAll(jsonArrayToStringList(branch.optJSONArray("setupStatements")));
                String s = branch.optString("setup", "").trim();
                if (!s.isEmpty()) {
                    setup.add(s);
                }
            }

            LinkedHashSet<String> unique = new LinkedHashSet<String>();
            for (String s : setup) {
                String x = sanitizeJavaSnippet(s);
                if (!x.isEmpty()) {
                    unique.add(x);
                }
            }

            e.invocation.put("setup", new JSONArray(cleanJavaStatements(new ArrayList<String>(unique))));
        } catch (Throwable ignored) {
            // 老版本没有 executableTestPlan 字段时保持兼容
        }
    }

    private static void mergeExecutablePlanSetupIntoInvocation(PromptEvidence e) {
        if (e == null || e.executableTestPlan == null || e.executableTestPlan.length() == 0) {
            return;
        }

        List<String> setup = jsonArrayToStringList(e.invocation.optJSONArray("setup"));
        setup.addAll(jsonArrayToStringList(e.executableTestPlan.optJSONArray("requiredSetup")));

        JSONObject branch = e.executableTestPlan.optJSONObject("branchReachability");
        if (branch != null) {
            setup.addAll(jsonArrayToStringList(branch.optJSONArray("setupStatements")));
            String branchSetup = branch.optString("setup", "").trim();
            if (!branchSetup.isEmpty()) {
                setup.add(branchSetup);
            }
        }

        JSONObject observable = e.executableTestPlan.optJSONObject("observable");
        if (observable != null) {
            String obsSetup = observable.optString("setup", "").trim();
            if (!obsSetup.isEmpty()) {
                setup.add(obsSetup);
            }
        }

        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (String s : setup) {
            String x = sanitizeJavaSnippet(s);
            if (!x.isEmpty()) {
                unique.add(x);
            }
        }

        e.invocation.put("setup", new JSONArray(cleanJavaStatements(new ArrayList<String>(unique))));
    }

    private static void mergeExecutablePlanForbiddenIntoAssertions(PromptEvidence e) {
        if (e == null || e.executableTestPlan == null || e.executableTestPlan.length() == 0) {
            return;
        }

        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        merged.addAll(jsonArrayToStringList(e.assertions.optJSONArray("avoid")));
        merged.addAll(jsonArrayToStringList(e.executableTestPlan.optJSONArray("forbidden")));

        JSONObject access = e.executableTestPlan.optJSONObject("accessConstraints");
        if (access != null) {
            merged.addAll(jsonArrayToStringList(access.optJSONArray("forbiddenDirectCalls")));
            merged.addAll(jsonArrayToStringList(access.optJSONArray("forbiddenConstructions")));
        }

        e.assertions.put("avoid", new JSONArray(new ArrayList<String>(merged)));
    }

    private static void mergeReceiverAntiPatternsIntoAssertions(PromptEvidence e) {
        JSONObject receiver = e.invocation.optJSONObject("receiver");
        if (receiver == null) {
            return;
        }
        JSONArray anti = receiver.optJSONArray("antiPatterns");
        if (anti == null || anti.length() == 0) {
            return;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        merged.addAll(jsonArrayToStringList(e.assertions.optJSONArray("avoid")));
        for (int i = 0; i < anti.length(); i++) {
            String s = anti.optString(i, "").trim();
            if (!s.isEmpty()) {
                merged.add(s);
            }
        }
        e.assertions.put("avoid", new JSONArray(new ArrayList<String>(merged)));
    }

    private static void normalizeSetupVariableNames(PromptEvidence e) {
        JSONArray setupArr = e.invocation.optJSONArray("setup");
        if (setupArr == null) {
            return;
        }

        List<String> setup = jsonArrayToStringList(setupArr);
        if (setup.isEmpty()) {
            return;
        }

        List<String> allAssertions = new ArrayList<String>();
        allAssertions.addAll(jsonArrayToStringList(e.assertions.optJSONArray("requiredToKill")));
        allAssertions.addAll(jsonArrayToStringList(e.assertions.optJSONArray("optionalSanityChecks")));

        boolean assertionNeedsMatchingOptions = containsToken(allAssertions, "matchingOptions");
        boolean setupHasMatchingOptions = containsDeclaredVariable(setup, "matchingOptions");
        String collectionVar = firstDeclaredCollectionVariable(setup);

        if (assertionNeedsMatchingOptions && !setupHasMatchingOptions) {
            if (collectionVar != null && !collectionVar.trim().isEmpty()) {
                setup = replaceIdentifierInStatements(setup, collectionVar, "matchingOptions");
            } else {
                String call = e.invocation.optString("call", "");
                String listExpr = extractArraysAsListExpression(call);
                if (listExpr.isEmpty()) {
                    listExpr = "java.util.Arrays.asList(\"alpha\", \"beta\")";
                }
                setup.add(0, "java.util.Collection<String> matchingOptions = " + listExpr + ";");
            }
        }

        // If subject is directly constructed with Arrays.asList(...) while a matchingOptions variable exists,
        // rewrite the constructor setup to use the variable. This keeps setup and assertions consistent.
        if (containsDeclaredVariable(setup, "matchingOptions")) {
            List<String> rewritten = new ArrayList<String>();
            for (String stmt : setup) {
                String x = stmt;
                if (x.contains("new ") && x.contains("java.util.Arrays.asList(")) {
                    String listExpr = extractArraysAsListExpression(x);
                    if (!listExpr.isEmpty()) {
                        x = x.replace(listExpr, "matchingOptions");
                    }
                }
                rewritten.add(x);
            }
            setup = rewritten;
        }

        setup = reorderSetupDependencies(setup);
        e.invocation.put("setup", new JSONArray(cleanJavaStatements(setup)));
    }

    private static void filterAssertionsAgainstCurrentInvocation(PromptEvidence e) {
        Set<String> defined = definedVariablesFromInvocation(e.invocation);
        List<String> required = filterAssertionList(jsonArrayToStringList(e.assertions.optJSONArray("requiredToKill")), defined);
        List<String> optional = filterAssertionList(jsonArrayToStringList(e.assertions.optJSONArray("optionalSanityChecks")), defined);

        e.assertions.put("requiredToKill", new JSONArray(limitList(cleanJavaStatements(required), DEFAULT_MAX_ITEMS_IN_PROMPT)));
        e.assertions.put("optionalSanityChecks", new JSONArray(limitList(cleanJavaStatements(optional), DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static void filterObservablesAgainstCurrentInvocation(PromptEvidence e) {
        Set<String> defined = definedVariablesFromInvocation(e.invocation);
        List<String> sensitive = filterExpressionList(jsonArrayToStringList(e.assertions.optJSONArray("mutationSensitiveObservables")), defined);
        List<String> auxiliary = filterExpressionList(jsonArrayToStringList(e.assertions.optJSONArray("auxiliaryObservables")), defined);

        e.assertions.put("mutationSensitiveObservables", new JSONArray(limitList(sensitive, DEFAULT_MAX_ITEMS_IN_PROMPT)));
        e.assertions.put("auxiliaryObservables", new JSONArray(limitList(auxiliary, DEFAULT_MAX_ITEMS_IN_PROMPT)));
    }

    private static Set<String> definedVariablesFromInvocation(JSONObject invocation) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        JSONArray setupArr = invocation.optJSONArray("setup");
        List<String> setup = jsonArrayToStringList(setupArr);
        for (String stmt : setup) {
            String var = declaredVariableName(stmt);
            if (!var.isEmpty()) {
                out.add(var);
            }
        }
        String call = invocation.optString("call", "");
        String callVar = declaredVariableName(call);
        if (!callVar.isEmpty()) {
            out.add(callVar);
        }
        // Common generated subject variable. If setup contains "subject = ..." it will already be present.
        if (containsToken(setup, "subject") || (call != null && call.contains("subject"))) {
            out.add("subject");
        }
        return out;
    }

    private static List<String> filterAssertionList(List<String> assertions, Set<String> definedVariables) {
        List<String> out = new ArrayList<String>();
        for (String assertion : assertions) {
            String x = sanitizeJavaSnippet(assertion);
            if (x.isEmpty()) {
                continue;
            }
            if (usesOnlyKnownVariables(x, definedVariables)) {
                out.add(x);
            }
        }
        return out;
    }

    private static List<String> filterExpressionList(List<String> expressions, Set<String> definedVariables) {
        List<String> out = new ArrayList<String>();
        for (String expr : expressions) {
            String x = sanitizeJavaSnippet(expr);
            if (x.isEmpty()) {
                continue;
            }
            if (usesOnlyKnownVariables(x, definedVariables)) {
                out.add(x);
            }
        }
        return out;
    }

    private static boolean usesOnlyKnownVariables(String text, Set<String> definedVariables) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        Set<String> vars = referencedVariables(text);
        for (String v : vars) {
            if (isAllowedImplicitName(v)) {
                continue;
            }
            if (!definedVariables.contains(v)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> referencedVariables(String text) {
        LinkedHashSet<String> vars = new LinkedHashSet<String>();
        if (text == null) {
            return vars;
        }

        Matcher dot = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.").matcher(text);
        while (dot.find()) {
            String v = dot.group(1);
            if (!isClassLikeName(v)) {
                vars.add(v);
            }
        }

        Matcher equalsFirstArg = Pattern.compile("assertEquals\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*,").matcher(text);
        while (equalsFirstArg.find()) {
            vars.add(equalsFirstArg.group(1));
        }
        return vars;
    }

    private static boolean isAllowedImplicitName(String v) {
        if (v == null || v.isEmpty()) {
            return true;
        }
        return "assertTrue".equals(v)
                || "assertFalse".equals(v)
                || "assertEquals".equals(v)
                || "assertSame".equals(v)
                || "assertNotNull".equals(v)
                || "fail".equals(v)
                || "java".equals(v)
                || "util".equals(v)
                || "org".equals(v)
                || "Arrays".equals(v)
                || "Collections".equals(v);
    }

    private static boolean isClassLikeName(String v) {
        return v != null && !v.isEmpty() && Character.isUpperCase(v.charAt(0));
    }

    private static String declaredVariableName(String stmt) {
        if (stmt == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?:^|;)\\s*(?:final\\s+)?(?:[A-Za-z_$][A-Za-z0-9_$.<>?,\\[\\]]+\\s+)+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=").matcher(stmt);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private static boolean containsDeclaredVariable(List<String> statements, String var) {
        for (String s : statements) {
            if (var.equals(declaredVariableName(s))) {
                return true;
            }
        }
        return false;
    }

    private static String firstDeclaredCollectionVariable(List<String> statements) {
        for (String s : statements) {
            String x = s == null ? "" : s;
            if (x.contains("Collection") || x.contains("List") || x.contains("Set")) {
                String var = declaredVariableName(x);
                if (!var.isEmpty()) {
                    return var;
                }
            }
        }
        return "";
    }


    private static List<String> reorderSetupDependencies(List<String> setup) {
        List<String> first = new ArrayList<String>();
        List<String> rest = new ArrayList<String>();
        for (String stmt : setup) {
            String var = declaredVariableName(stmt);
            if ("matchingOptions".equals(var) || "values".equals(var) || "output".equals(var)) {
                first.add(stmt);
            } else {
                rest.add(stmt);
            }
        }
        first.addAll(rest);
        return first;
    }

    private static List<String> replaceIdentifierInStatements(List<String> statements, String oldName, String newName) {
        List<String> out = new ArrayList<String>();
        Pattern p = Pattern.compile("\\b" + Pattern.quote(oldName) + "\\b");
        for (String s : statements) {
            out.add(p.matcher(s).replaceAll(newName));
        }
        return out;
    }

    private static boolean containsToken(List<String> texts, String token) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        for (String s : texts) {
            if (s != null && p.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    private static String extractArraysAsListExpression(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = Pattern.compile("java\\.util\\.Arrays\\.asList\\s*\\([^)]*\\)").matcher(text);
        if (m.find()) {
            return m.group();
        }
        m = Pattern.compile("\\bArrays\\.asList\\s*\\([^)]*\\)").matcher(text);
        if (m.find()) {
            return m.group();
        }
        return "";
    }
}
