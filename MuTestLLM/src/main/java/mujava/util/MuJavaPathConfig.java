package mujava.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Resolves external MuJava path settings from JVM properties, environment variables,
 * llm.properties, then limited filesystem fallback.
 */
public final class MuJavaPathConfig {
    private static final String LLM_PROPERTIES = "llm.properties";

    private MuJavaPathConfig() {
    }

    public static String resolveMuJavaConfigPath() {
        String configured = firstNonBlank(
                System.getProperty("mujava.config.path"),
                System.getenv("MUJAVA_CONFIG"),
                loadProperties().getProperty("mujava.config.path")
        );
        String resolvedConfigured = resolveConfiguredFile(configured);
        if (resolvedConfigured != null) {
            return resolvedConfigured;
        }

        List<Path> candidates = candidateRootsForFallback("mujava.config");
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        Path moduleRoot = detectModuleRoot();
        return moduleRoot == null ? null : moduleRoot.resolve("mujava.config").normalize().toString();
    }

    public static String resolveLibrariesPath(String mujavaConfigPath) {
        String configured = firstNonBlank(
                System.getProperty("mujava.libraries.path"),
                System.getenv("MUJAVA_LIBRARIES"),
                loadProperties().getProperty("mujava.libraries.path")
        );
        String resolvedConfigured = resolveConfiguredFile(configured);
        if (resolvedConfigured != null) {
            return resolvedConfigured;
        }

        Path configFile = isBlank(mujavaConfigPath)
                ? null
                : Paths.get(mujavaConfigPath).toAbsolutePath().normalize();
        if (configFile != null && configFile.getParent() != null) {
            Path configDir = configFile.getParent();
            List<Path> candidates = Arrays.asList(
                    configDir.resolve("libraries.json"),
                    configDir.resolve("MuTestLLM").resolve("libraries.json")
            );
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }

        List<Path> candidates = candidateRootsForFallback("libraries.json");
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    public static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(LLM_PROPERTIES)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file: " + LLM_PROPERTIES, e);
        }
        return props;
    }

    private static String normalize(String path) {
        return Paths.get(path).toAbsolutePath().normalize().toString();
    }

    private static String resolveConfiguredFile(String path) {
        if (isBlank(path)) {
            return null;
        }

        Path raw = Paths.get(path.trim());
        if (raw.isAbsolute() && Files.isRegularFile(raw.normalize())) {
            return raw.normalize().toString();
        }

        for (Path root : candidateBaseDirectories()) {
            if (root == null) {
                continue;
            }
            Path candidate = root.resolve(path).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static List<Path> candidateRootsForFallback(String fileName) {
        List<Path> roots = candidateBaseDirectories();
        List<Path> candidates = Arrays.asList(
                roots.size() > 0 ? roots.get(0).resolve(fileName) : null,
                roots.size() > 1 ? roots.get(1).resolve(fileName) : null,
                roots.size() > 2 ? roots.get(2).resolve(fileName) : null,
                roots.size() > 3 ? roots.get(3).resolve(fileName) : null,
                roots.size() > 0 ? roots.get(0).resolve("MuTestLLM").resolve(fileName) : null,
                roots.size() > 1 ? roots.get(1).resolve("MuTestLLM").resolve(fileName) : null
        );
        return candidates;
    }

    private static List<Path> candidateBaseDirectories() {
        Path moduleRoot = detectModuleRoot();
        Path repoRoot = moduleRoot == null ? null : moduleRoot.getParent();
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return Arrays.asList(moduleRoot, repoRoot, userDir);
    }

    private static Path detectModuleRoot() {
        try {
            Path location = Paths.get(MuJavaPathConfig.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(location)) {
                location = location.getParent();
            }
            Path current = location;
            while (current != null) {
                if ("MuTestLLM".equalsIgnoreCase(String.valueOf(current.getFileName()))) {
                    return current;
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
