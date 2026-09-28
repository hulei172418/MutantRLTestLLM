package org.rip;

import org.model.MutationConfig;

public final class CanonicalMutantId {
    private CanonicalMutantId() {
    }

    public static String build(MutationConfig config) {
        if (config == null) {
            return "";
        }
        return build(
                config.projectName,
                firstNonBlank(config.rawTargetClassId, config.packageName, config.classNameF, config.className),
                config.methodName,
                firstNonBlank(legacyMutantName(config.filepath), config.operator)
        );
    }

    public static String build(String project, String targetClass, String methodSignature, String mutantName) {
        String p = safe(project);
        String c = safe(targetClass);
        String m = safe(methodSignature);
        String n = safe(mutantName);
        if (p.isEmpty() || c.isEmpty() || m.isEmpty() || n.isEmpty()) {
            return "";
        }
        return p + "::" + c + "::" + m + "::" + n;
    }

    public static String legacyMutantName(String raw) {
        String text = safe(raw);
        if (text.isEmpty()) {
            return "";
        }
        if (text.contains("::")) {
            String[] parts = text.split("::", -1);
            return parts.length >= 4 ? safe(parts[3]) : text;
        }
        if (text.contains("##")) {
            String[] parts = text.split("##", -1);
            return parts.length >= 4 ? safe(parts[3]) : text;
        }
        String normalized = text.replace('\\', '/');
        String[] parts = normalized.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("traditional_mutants".equals(parts[i]) && i + 2 < parts.length) {
                return safe(parts[i + 2]);
            }
        }
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? safe(normalized.substring(idx + 1)) : text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
