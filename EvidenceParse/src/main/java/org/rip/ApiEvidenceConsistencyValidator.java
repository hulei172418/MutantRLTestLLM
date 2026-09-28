package org.rip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict EvidenceParse-time validator for compile-critical callable evidence.
 *
 * <p>Important: this validator never repairs output for the LLM. The parser must
 * emit one internally consistent API view. If the same callable is represented
 * inconsistently (for example static in exactCallableSignatures but instance in
 * PUBLIC_API), evidence generation fails instead of passing contradictory facts
 * downstream.</p>
 */
final class ApiEvidenceConsistencyValidator {
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "^(static\\s+)?(.+?)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\((.*)\\)\\s*$");

    private ApiEvidenceConsistencyValidator() {
    }

    static void validateStrict(Map<String, Object> publicApi,
                               Map<String, Object> compilationFacts) {
        if (publicApi == null || compilationFacts == null) {
            return;
        }

        List<String> exactValues = stringList(compilationFacts.get("exactCallableSignatures"));
        List<String> publicValues = stringList(publicApi.get("availablePublicMethods"));
        List<String> setupValues = stringList(publicApi.get("availableSetupMethods"));

        Map<String, SignatureFact> exact = parseMethods(exactValues);
        Map<String, SignatureFact> visible = parseMethods(publicValues);
        Map<String, SignatureFact> setup = parseMethods(setupValues);

        // CodeKB can be unavailable in degraded mode. In that case there is no
        // independent exact-signature set to cross-check; do not manufacture facts.
        if (exact.isEmpty()) {
            Map<String, Object> status = new LinkedHashMap<String, Object>();
            status.put("status", "NOT_VERIFIED_NO_EXACT_SIGNATURES");
            status.put("validatedAt", "EvidenceParse");
            compilationFacts.put("apiConsistency", status);
            return;
        }

        List<String> errors = new ArrayList<String>();

        // Every exact directly-callable method emitted by CodeKB/source analysis must
        // have the same source-level representation in PUBLIC_API. Constructors are
        // intentionally ignored by SignatureFact.parse().
        for (Map.Entry<String, SignatureFact> entry : exact.entrySet()) {
            SignatureFact expected = entry.getValue();
            SignatureFact actual = visible.get(entry.getKey());
            if (actual == null) {
                errors.add("PUBLIC_API missing callable declared in exactCallableSignatures: " + expected.raw);
                continue;
            }
            compareFacts("PUBLIC_API", expected, actual, errors);
        }

        // Any overlap must agree exactly. Extra PUBLIC_API entries are permitted only
        // for explicitly inherited/supplemental observers (for example Throwable#getMessage)
        // that are not owner-declared exact callables.
        for (Map.Entry<String, SignatureFact> entry : visible.entrySet()) {
            SignatureFact expected = exact.get(entry.getKey());
            if (expected != null) {
                compareFacts("PUBLIC_API", expected, entry.getValue(), errors);
            }
        }

        // Setup methods are a subset of PUBLIC_API and, when owner-declared, must also
        // agree with exact callable facts.
        for (Map.Entry<String, SignatureFact> entry : setup.entrySet()) {
            SignatureFact setupFact = entry.getValue();
            SignatureFact publicFact = visible.get(entry.getKey());
            if (publicFact == null) {
                errors.add("availableSetupMethods contains method absent from availablePublicMethods: "
                        + setupFact.raw);
                continue;
            }
            compareFacts("SETUP_API", publicFact, setupFact, errors);
            SignatureFact exactFact = exact.get(entry.getKey());
            if (exactFact != null) {
                compareFacts("SETUP_API", exactFact, setupFact, errors);
            }
        }

        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("EvidenceParse API consistency validation failed. ")
                    .append("Contradictory compile-critical evidence must not reach the LLM.");
            for (String error : errors) {
                message.append("\n - ").append(error);
            }
            throw new IllegalStateException(message.toString());
        }

        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("status", "VERIFIED_STRICT");
        status.put("validatedAt", "EvidenceParse");
        status.put("exactMethodCount", exact.size());
        status.put("publicMethodCount", visible.size());
        status.put("setupMethodCount", setup.size());
        status.put("policy", "NO_DOWNSTREAM_EVIDENCE_REPAIR");
        compilationFacts.put("apiConsistency", status);
    }

    private static void compareFacts(String label,
                                     SignatureFact expected,
                                     SignatureFact actual,
                                     List<String> errors) {
        if (expected.isStatic != actual.isStatic) {
            errors.add(label + " static/instance mismatch for " + expected.key()
                    + ": exact='" + expected.raw + "', listed='" + actual.raw + "'");
        }
        if (!normalizeType(expected.returnType).equals(normalizeType(actual.returnType))) {
            errors.add(label + " return-type mismatch for " + expected.key()
                    + ": exact='" + expected.raw + "', listed='" + actual.raw + "'");
        }
        if (!normalizeParams(expected.params).equals(normalizeParams(actual.params))) {
            errors.add(label + " parameter mismatch for " + expected.methodName
                    + ": exact='" + expected.raw + "', listed='" + actual.raw + "'");
        }
    }

    private static Map<String, SignatureFact> parseMethods(List<String> values) {
        Map<String, SignatureFact> out = new LinkedHashMap<String, SignatureFact>();
        for (String value : values) {
            SignatureFact fact = SignatureFact.parse(value);
            if (fact == null) {
                continue; // constructors and non-method supplements
            }
            SignatureFact previous = out.put(fact.key(), fact);
            if (previous != null && !normalize(previous.raw).equals(normalize(fact.raw))) {
                throw new IllegalStateException("Duplicate conflicting callable evidence for " + fact.key()
                        + ": '" + previous.raw + "' vs '" + fact.raw + "'");
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<String>();
        if (raw instanceof Collection<?>) {
            for (Object item : (Collection<?>) raw) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null && raw.getClass().isArray()) {
            Object[] values = (Object[]) raw;
            for (Object item : values) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeType(String text) {
        return text == null ? "" : text.trim().replace("...", "[]").replace("java.lang.", "").replaceAll("\\s+", "");
    }

    private static String normalizeParams(String params) {
        List<String> parts = splitParameterTypes(params);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(normalizeType(parts.get(i)));
        }
        return out.toString();
    }

    private static List<String> splitParameterTypes(String params) {
        List<String> out = new ArrayList<String>();
        if (params == null || params.trim().isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (ch == '<') {
                genericDepth++;
            } else if (ch == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            }
            if (ch == ',' && genericDepth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private static final class SignatureFact {
        final String raw;
        final boolean isStatic;
        final String returnType;
        final String methodName;
        final String params;

        private SignatureFact(String raw,
                              boolean isStatic,
                              String returnType,
                              String methodName,
                              String params) {
            this.raw = raw;
            this.isStatic = isStatic;
            this.returnType = returnType;
            this.methodName = methodName;
            this.params = params;
        }

        static SignatureFact parse(String signature) {
            if (signature == null) {
                return null;
            }
            String text = signature.trim();
            Matcher matcher = METHOD_SIGNATURE.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
            return new SignatureFact(
                    text,
                    matcher.group(1) != null,
                    matcher.group(2).trim(),
                    matcher.group(3).trim(),
                    matcher.group(4).trim());
        }

        String key() {
            return methodName.toLowerCase(Locale.ROOT) + "(" + normalizeParams(params) + ")";
        }
    }
}
