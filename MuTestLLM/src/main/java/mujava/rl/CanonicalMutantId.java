package mujava.rl;

import mujava.testgenerator.tools.Request;

public final class CanonicalMutantId {
    private CanonicalMutantId() {
    }

    public static String build(Request request) {
        if (request == null) {
            return "";
        }
        return build(
                inferProject(request),
                request.targetClassName,
                request.methodSignature,
                request.mutantName
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

    private static String inferProject(Request request) {
        String explicit = safe(request == null ? "" : request.projectName);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String fromSource = lastPathSegment(request == null ? "" : request.sourceModuleHome);
        if (!fromSource.isEmpty()) {
            return fromSource;
        }
        return lastPathSegment(request == null ? "" : request.resultModuleHome);
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
