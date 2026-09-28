package mujava.testgenerator.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Small centralized feature/config reader for generated-test behavior.
 * System properties override llm.properties so experiments can be changed
 * without editing source files.
 */
public final class GeneratorFeatureFlags {
    private static final Properties DEFAULTS = loadDefaults();

    private GeneratorFeatureFlags() {
    }

    public static boolean benchmarkAdaptersEnabled() {
        return booleanValue("llm.benchmark.adapters.enabled", false);
    }

    /**
     * Legacy escape hatch. By default semantic precheck findings are warnings
     * and javac/original/mutant execution decides the actual outcome.
     */
    public static boolean semanticPrecheckHardReject() {
        return booleanValue("llm.semantic.precheck.hardReject", false);
    }

    private static boolean booleanValue(String key, boolean defaultValue) {
        String value = stringValue(key, Boolean.toString(defaultValue));
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static String stringValue(String key, String defaultValue) {
        String system = System.getProperty(key);
        if (system != null && !system.trim().isEmpty()) {
            return system;
        }
        String value = DEFAULTS.getProperty(key);
        return value == null ? defaultValue : value;
    }

    private static Properties loadDefaults() {
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = GeneratorFeatureFlags.class.getClassLoader().getResourceAsStream("llm.properties");
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // Keep safe built-in defaults when resource loading fails.
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // no-op
                }
            }
        }
        return properties;
    }
}
