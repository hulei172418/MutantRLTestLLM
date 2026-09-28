package mujava.testgenerator.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the Java compiler language level from the tested Maven module.
 *
 * <p>The generated tests belong to {@code sourceModuleHome}, not to MuTestLLM
 * itself. Therefore their javac language level must follow that module's POM
 * whenever the POM declares one. This resolver is deliberately Java 8
 * compatible because it is compiled as part of MuTestLLM.</p>
 */
final class MavenCompilerConfigResolver {
    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";

    private MavenCompilerConfigResolver() {
    }

    static CompilerLevel resolve(String sourceModuleHome) {
        if (sourceModuleHome == null || sourceModuleHome.trim().isEmpty()) {
            return CompilerLevel.unspecified();
        }
        Path pom = Paths.get(sourceModuleHome).toAbsolutePath().normalize().resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return CompilerLevel.unspecified();
        }
        try {
            RawModel model = readEffectiveRawModel(pom, new HashSet<Path>());
            String release = resolveValue(model.release, model.properties);
            String source = resolveValue(model.source, model.properties);
            String target = resolveValue(model.target, model.properties);

            if (isBlank(release)) {
                release = resolveValue(model.properties.get("maven.compiler.release"), model.properties);
            }
            if (isBlank(source)) {
                source = resolveValue(model.properties.get("maven.compiler.source"), model.properties);
            }
            if (isBlank(target)) {
                target = resolveValue(model.properties.get("maven.compiler.target"), model.properties);
            }

            String javaVersion = resolveValue(model.properties.get("java.version"), model.properties);
            if (isBlank(release) && isBlank(source) && isBlank(target) && !isBlank(javaVersion)) {
                source = javaVersion;
                target = javaVersion;
            }

            return new CompilerLevel(normalizeLevel(release), normalizeLevel(source), normalizeLevel(target), pom);
        } catch (Exception ignored) {
            // Compiler-level discovery must never make test generation fail.
            // If the POM cannot be interpreted reliably, javac is invoked without
            // extra language-level flags rather than guessing a Java version.
            return CompilerLevel.unspecified();
        }
    }

    private static RawModel readEffectiveRawModel(Path pom, Set<Path> seen) throws Exception {
        Path normalized = pom.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !seen.add(normalized)) {
            return new RawModel();
        }

        Document document = parseXml(normalized);
        Element project = document.getDocumentElement();

        RawModel out = new RawModel();
        Path parentPom = resolveParentPom(normalized, project);
        if (parentPom != null && Files.isRegularFile(parentPom)) {
            out.copyFrom(readEffectiveRawModel(parentPom, seen));
        }

        Map<String, String> localProperties = readProjectProperties(project);
        out.properties.putAll(localProperties);

        Element configuration = findCompilerConfiguration(project, false);
        if (configuration == null) {
            configuration = findCompilerConfiguration(project, true);
        }
        if (configuration != null) {
            String value = childText(configuration, "release");
            if (!isBlank(value)) {
                out.release = value;
            }
            value = childText(configuration, "source");
            if (!isBlank(value)) {
                out.source = value;
            }
            value = childText(configuration, "target");
            if (!isBlank(value)) {
                out.target = value;
            }
        }
        return out;
    }

    private static Document parseXml(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            factory.setExpandEntityReferences(false);
        } catch (UnsupportedOperationException ignored) {
        }
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }

    private static Path resolveParentPom(Path currentPom, Element project) {
        Element parent = directChild(project, "parent");
        if (parent == null) {
            return null;
        }
        String relativePath = childText(parent, "relativePath");
        if (relativePath == null) {
            relativePath = "../pom.xml";
        }
        relativePath = relativePath.trim();
        if (!relativePath.isEmpty()) {
            Path candidate = currentPom.getParent().resolve(relativePath).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        String groupId = childText(parent, "groupId");
        String artifactId = childText(parent, "artifactId");
        String version = childText(parent, "version");
        if (isBlank(groupId) || isBlank(artifactId) || isBlank(version)
                || version.contains("${") || groupId.contains("${") || artifactId.contains("${")) {
            return null;
        }
        Path localRepo = Paths.get(System.getProperty("user.home", ""), ".m2", "repository");
        String groupPath = groupId.trim().replace('.', java.io.File.separatorChar);
        Path candidate = localRepo.resolve(groupPath)
                .resolve(artifactId.trim())
                .resolve(version.trim())
                .resolve(artifactId.trim() + "-" + version.trim() + ".pom");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static Map<String, String> readProjectProperties(Element project) {
        Map<String, String> out = new HashMap<String, String>();
        Element properties = directChild(project, "properties");
        if (properties == null) {
            return out;
        }
        NodeList children = properties.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(node);
            String value = node.getTextContent();
            if (!isBlank(name) && value != null) {
                out.put(name, value.trim());
            }
        }
        return out;
    }

    private static Element findCompilerConfiguration(Element project, boolean pluginManagement) {
        Element build = directChild(project, "build");
        if (build == null) {
            return null;
        }
        Element pluginsParent;
        if (pluginManagement) {
            Element management = directChild(build, "pluginManagement");
            pluginsParent = management == null ? null : directChild(management, "plugins");
        } else {
            pluginsParent = directChild(build, "plugins");
        }
        if (pluginsParent == null) {
            return null;
        }
        for (Element plugin : directChildren(pluginsParent, "plugin")) {
            if (COMPILER_PLUGIN.equals(childText(plugin, "artifactId"))) {
                return directChild(plugin, "configuration");
            }
        }
        return null;
    }

    private static java.util.List<Element> directChildren(Element parent, String name) {
        java.util.List<Element> out = new java.util.ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(node))) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(node))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        return local == null || local.isEmpty() ? node.getNodeName() : local;
    }

    private static String resolveValue(String raw, Map<String, String> properties) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        for (int depth = 0; depth < 8; depth++) {
            int start = value.indexOf("${");
            if (start < 0) {
                break;
            }
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                break;
            }
            String key = value.substring(start + 2, end);
            String replacement = properties.get(key);
            if (replacement == null) {
                break;
            }
            value = value.substring(0, start) + replacement.trim() + value.substring(end + 1);
        }
        return value.trim();
    }

    private static String normalizeLevel(String value) {
        if (isBlank(value) || value.contains("${")) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("1.") && normalized.length() > 2) {
            return normalized;
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class RawModel {
        final Map<String, String> properties = new HashMap<String, String>();
        String release;
        String source;
        String target;

        void copyFrom(RawModel parent) {
            if (parent == null) {
                return;
            }
            properties.putAll(parent.properties);
            release = parent.release;
            source = parent.source;
            target = parent.target;
        }
    }

    static final class CompilerLevel {
        final String release;
        final String source;
        final String target;
        final Path pom;

        CompilerLevel(String release, String source, String target, Path pom) {
            this.release = release == null ? "" : release;
            this.source = source == null ? "" : source;
            this.target = target == null ? "" : target;
            this.pom = pom;
        }

        static CompilerLevel unspecified() {
            return new CompilerLevel("", "", "", null);
        }

        boolean isSpecified() {
            return !release.isEmpty() || !source.isEmpty() || !target.isEmpty();
        }

        String promptDescription() {
            if (!release.isEmpty()) {
                return "release " + release;
            }
            if (!source.isEmpty() && !target.isEmpty()) {
                return "source " + source + " / target " + target;
            }
            if (!source.isEmpty()) {
                return "source " + source;
            }
            if (!target.isEmpty()) {
                return "target " + target;
            }
            return "unspecified";
        }

        @Override
        public String toString() {
            if (!release.isEmpty()) {
                return "release=" + release;
            }
            return "source=" + source + ",target=" + target;
        }
    }
}
