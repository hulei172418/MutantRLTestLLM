package org.utils;

import org.model.MutationConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaTargetMetadata {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    private JavaTargetMetadata() {
    }

    public static void applyDeclaredPackage(MutationConfig config, String sourceJavaPath) {
        if (config == null) {
            return;
        }
        String raw = safe(config.packageName);
        config.rawTargetClassId = firstNonBlank(config.rawTargetClassId, raw);
        config.packageName = readDeclaredPackage(sourceJavaPath);
    }

    public static String readDeclaredPackage(String javaFile) {
        if (javaFile == null || javaFile.trim().isEmpty()) {
            return "";
        }
        File file = new File(javaFile);
        if (!file.isFile()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ") || trimmed.startsWith("public ")
                        || trimmed.startsWith("class ") || trimmed.startsWith("interface ")
                        || trimmed.startsWith("enum ")) {
                    return "";
                }
                Matcher matcher = PACKAGE_PATTERN.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
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
