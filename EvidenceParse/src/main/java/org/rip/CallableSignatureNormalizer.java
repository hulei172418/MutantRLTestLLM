package org.rip;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Canonical matching for the signature forms used across muJava/Soot,
 * CodeKB and JavaParser.
 *
 * <p>The project receives all of the following forms in practice:</p>
 * <ul>
 *   <li>{@code boolean_contains(java.lang.String)}</li>
 *   <li>{@code contains(java.lang.String)}</li>
 *   <li>{@code boolean contains(String)}</li>
 *   <li>{@code Owner#boolean contains(String)}</li>
 *   <li>{@code Owner(String)} for constructors</li>
 * </ul>
 *
 * <p>Java dispatch is determined by callable name + parameter types, so return
 * type prefixes are intentionally ignored for matching. Parameter types are
 * compared after package/generic/varargs normalization. The implementation is
 * Java-8 compatible and does not use regular expressions to infer Java source
 * semantics.</p>
 */
public final class CallableSignatureNormalizer {
    private CallableSignatureNormalizer() {
    }

    public static boolean matches(CallableDeclaration<?> callable, String rawSignature) {
        if (callable == null) {
            return false;
        }
        SignatureSpec spec = parse(rawSignature);
        if (spec == null) {
            return false;
        }
        String actualName = callable.getNameAsString();
        if (!nameMatches(spec.head, actualName, callable instanceof ConstructorDeclaration)) {
            return false;
        }
        if (callable.getParameters().size() != spec.parameterTypes.size()) {
            return false;
        }
        for (int i = 0; i < callable.getParameters().size(); i++) {
            Parameter actual = callable.getParameter(i);
            String actualType = simpleType(actual.getType().asString());
            String expectedType = simpleType(spec.parameterTypes.get(i));
            if (!typeCompatible(actualType, expectedType)) {
                return false;
            }
        }
        return true;
    }

    public static String canonical(CallableDeclaration<?> callable) {
        if (callable == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(callable.getNameAsString()).append('(');
        for (int i = 0; i < callable.getParameters().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(simpleType(callable.getParameter(i).getType().asString()));
        }
        out.append(')');
        return out.toString();
    }

    public static String canonical(String rawSignature) {
        SignatureSpec spec = parse(rawSignature);
        if (spec == null) {
            return "";
        }
        String name = explicitMethodName(spec.head);
        if (name.isEmpty()) {
            name = spec.head;
        }
        StringBuilder out = new StringBuilder();
        out.append(name).append('(');
        for (int i = 0; i < spec.parameterTypes.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(simpleType(spec.parameterTypes.get(i)));
        }
        out.append(')');
        return out.toString();
    }

    public static String simpleType(String raw) {
        if (raw == null) {
            return "";
        }
        String value = stripLeadingTypeDecorations(raw.trim()).replace('$', '.');
        if (value.isEmpty()) {
            return "";
        }
        value = stripGenericArguments(value);
        value = replaceVarargs(value);
        value = removeWhitespace(value);

        int dimensions = 0;
        while (value.endsWith("[]")) {
            dimensions++;
            value = value.substring(0, value.length() - 2);
        }
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < value.length()) {
            value = value.substring(dot + 1);
        }
        StringBuilder out = new StringBuilder(value);
        for (int i = 0; i < dimensions; i++) {
            out.append("[]");
        }
        return out.toString();
    }

    public static List<String> parameterTypes(String rawSignature) {
        SignatureSpec spec = parse(rawSignature);
        if (spec == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (String parameter : spec.parameterTypes) {
            out.add(simpleType(parameter));
        }
        return out;
    }

    private static SignatureSpec parse(String rawSignature) {
        if (rawSignature == null) {
            return null;
        }
        String text = rawSignature.trim();
        if (text.isEmpty()) {
            return null;
        }
        int hash = text.lastIndexOf('#');
        if (hash >= 0 && hash + 1 < text.length()) {
            text = text.substring(hash + 1).trim();
        }
        int lp = text.indexOf('(');
        int rp = text.lastIndexOf(')');
        if (lp <= 0 || rp < lp) {
            return null;
        }
        String head = text.substring(0, lp).trim();
        String inside = text.substring(lp + 1, rp).trim();
        return new SignatureSpec(head, splitTopLevel(inside));
    }

    private static boolean nameMatches(String head, String actualName, boolean constructor) {
        if (head == null || actualName == null) {
            return false;
        }
        String trimmed = head.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String explicit = explicitMethodName(trimmed);
        if (!explicit.isEmpty() && explicit.equals(actualName)) {
            return true;
        }
        if (trimmed.equals(actualName)) {
            return true;
        }
        if (trimmed.endsWith("_" + actualName)) {
            return true;
        }
        return constructor && simpleType(trimmed).equals(actualName);
    }

    private static String explicitMethodName(String head) {
        if (head == null) {
            return "";
        }
        String trimmed = head.trim();
        int lastSpace = lastWhitespaceIndex(trimmed);
        if (lastSpace >= 0 && lastSpace + 1 < trimmed.length()) {
            return trimmed.substring(lastSpace + 1).trim();
        }
        return "";
    }

    private static int lastWhitespaceIndex(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '<') {
                genericDepth++;
            } else if (ch == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            } else if (ch == '(') {
                parenDepth++;
            } else if (ch == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (ch == '[') {
                bracketDepth++;
            } else if (ch == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
            }
            if (ch == ',' && genericDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
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

    private static String stripGenericArguments(String value) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '<') {
                depth++;
                continue;
            }
            if (ch == '>') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String stripLeadingTypeDecorations(String value) {
        String current = value == null ? "" : value.trim();
        boolean changed = true;
        while (changed && !current.isEmpty()) {
            changed = false;
            if (current.startsWith("final ")) {
                current = current.substring("final ".length()).trim();
                changed = true;
            }
            if (current.startsWith("@")) {
                int end = annotationEnd(current);
                if (end > 0 && end < current.length()) {
                    current = current.substring(end).trim();
                    changed = true;
                }
            }
        }
        return current;
    }

    private static int annotationEnd(String value) {
        int i = 1;
        while (i < value.length()) {
            char ch = value.charAt(i);
            if (Character.isJavaIdentifierPart(ch) || ch == '.') {
                i++;
                continue;
            }
            break;
        }
        if (i < value.length() && value.charAt(i) == '(') {
            int depth = 0;
            for (; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
        }
        return i;
    }

    private static String replaceVarargs(String value) {
        return value.endsWith("...") ? value.substring(0, value.length() - 3) + "[]" : value;
    }

    private static String removeWhitespace(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static boolean typeCompatible(String actual, String expected) {
        if (actual.equals(expected)) {
            return true;
        }
        if (isLikelyTypeVariable(actual) || isLikelyTypeVariable(expected)) {
            return true;
        }
        // Varargs are normalized to arrays earlier. Do not collapse array and
        // scalar types here; doing so can resolve the wrong overload.
        return false;
    }

    private static boolean isLikelyTypeVariable(String type) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        String base = type;
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
        }
        if (base.length() == 1) {
            return Character.isUpperCase(base.charAt(0));
        }
        return false;
    }

    private static final class SignatureSpec {
        final String head;
        final List<String> parameterTypes;

        SignatureSpec(String head, List<String> parameterTypes) {
            this.head = head == null ? "" : head;
            this.parameterTypes = parameterTypes == null
                    ? Collections.<String>emptyList()
                    : parameterTypes;
        }
    }
}
