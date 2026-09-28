package mujava.testgenerator.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only parser for callable signatures already produced by EvidenceParse.
 *
 * <p>This class never repairs or changes evidence. It only exposes structured
 * facts to deterministic generated-code guards.</p>
 */
final class CallableSignatureFact {
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "^(static\\s+)?(.+?)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\((.*)\\)\\s*$");

    final String raw;
    final boolean isStatic;
    final String returnType;
    final String methodName;
    final String params;

    private CallableSignatureFact(String raw,
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

    static CallableSignatureFact parse(String signature) {
        if (signature == null) {
            return null;
        }
        String text = signature.trim();
        Matcher matcher = METHOD_SIGNATURE.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        return new CallableSignatureFact(
                text,
                matcher.group(1) != null,
                matcher.group(2).trim(),
                matcher.group(3).trim(),
                matcher.group(4).trim());
    }

    String key() {
        return methodName.toLowerCase(Locale.ROOT) + "(" + normalizeParams(params) + ")";
    }

    int arity() {
        return parameterTypes().size();
    }

    List<String> parameterTypes() {
        return splitParameterTypes(params);
    }

    private static String normalizeParams(String params) {
        List<String> parts = splitParameterTypes(params);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String part = parts.get(i).trim()
                    .replace("java.lang.", "")
                    .replaceAll("\\s+", "");
            out.append(part);
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
        int arrayDepth = 0;
        for (int i = 0; i < params.length(); i++) {
            char ch = params.charAt(i);
            if (ch == '<') {
                genericDepth++;
            } else if (ch == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            } else if (ch == '[') {
                arrayDepth++;
            } else if (ch == ']') {
                arrayDepth = Math.max(0, arrayDepth - 1);
            }
            if (ch == ',' && genericDepth == 0 && arrayDepth == 0) {
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
}
