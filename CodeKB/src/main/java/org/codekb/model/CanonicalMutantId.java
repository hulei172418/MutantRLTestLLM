package org.codekb.model;

public final class CanonicalMutantId {
    private CanonicalMutantId() {
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

    public static String normalize(String raw) {
        String text = safe(raw);
        if (text.isEmpty()) {
            return "";
        }
        if (text.contains("::")) {
            String[] parts = text.split("::", -1);
            if (parts.length >= 4) {
                return build(parts[0], parts[1], parts[2], parts[3]);
            }
        }
        if (text.contains("##")) {
            String[] parts = text.split("##", -1);
            if (parts.length >= 4) {
                return build(lastPathSegment(parts[0]), parts[1], parts[2], parts[3]);
            }
        }
        return text;
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

    public static String projectFromNormalized(String normalizedId) {
        String text = safe(normalizedId);
        if (!text.contains("::")) {
            return "";
        }
        String[] parts = text.split("::", -1);
        return parts.length >= 4 ? safe(parts[0]) : "";
    }

    public static String targetClassFromNormalized(String normalizedId) {
        String text = safe(normalizedId);
        if (!text.contains("::")) {
            return "";
        }
        String[] parts = text.split("::", -1);
        return parts.length >= 4 ? safe(parts[1]) : "";
    }

    public static String methodSignatureFromNormalized(String normalizedId) {
        String text = safe(normalizedId);
        if (!text.contains("::")) {
            return "";
        }
        String[] parts = text.split("::", -1);
        return parts.length >= 4 ? safe(parts[2]) : "";
    }

    private static String lastPathSegment(String path) {
        String normalized = safe(path).replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
