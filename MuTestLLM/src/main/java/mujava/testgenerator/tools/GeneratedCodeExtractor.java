package mujava.testgenerator.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mujava.testgenerator.tools.TestNameUtils.packageNameOf;
import static mujava.testgenerator.tools.TestNameUtils.simpleNameOf;

/**
 * Extracts and normalizes Java source from an OpenAI-compatible response.
 */
public final class GeneratedCodeExtractor {
    private GeneratedCodeExtractor() {
    }

    public static String extractJavaCode(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(rawResponse);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice0 = choices.optJSONObject(0);
                if (choice0 != null) {
                    JSONObject msg = choice0.optJSONObject("message");
                    if (msg != null) {
                        String content = msg.optString("content", "");
                        if (!content.trim().isEmpty()) {
                            return stripMarkdownCodeFence(content);
                        }
                        // Some reasoning models may return reasoning_content but empty visible content.
                        // Do not fall back to raw JSON in that case, otherwise the JSON response is written as .java.
                        if (msg.has("reasoning_content")) {
                            return "";
                        }
                    }
                    String text = choice0.optString("text", "");
                    if (!text.trim().isEmpty()) {
                        return stripMarkdownCodeFence(text);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return stripMarkdownCodeFence(rawResponse);
    }

    public static boolean containsExpectedTypeDeclaration(String code, String testFqn) {
        if (code == null || code.trim().isEmpty() || testFqn == null || testFqn.trim().isEmpty()) {
            return false;
        }

        String simple = simpleNameOf(testFqn);
        try {
            CompilationUnit cu = StaticJavaParser.parse(stripMarkdownCodeFence(code));
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (simple.equals(type.getNameAsString())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            // The response may still be syntactically incomplete. Keep a narrow textual
            // fallback only for response-shape detection; semantic Java checks use AST.
            Pattern p = Pattern.compile(
                    "\\b(class|interface|enum)\\s+" + Pattern.quote(simple) + "\\b"
            );
            return p.matcher(code).find();
        }
    }

    public static String finishReason(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "";
        }

        try {
            JSONObject root = new JSONObject(rawResponse);
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice0 = choices.optJSONObject(0);
                if (choice0 != null) {
                    return choice0.optString("finish_reason", "");
                }
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    public static String stripMarkdownCodeFence(String text) {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("```")) {
            Matcher m = Pattern.compile("^```[A-Za-z0-9_+-]*\\s*(.*?)\\s*```$", Pattern.DOTALL).matcher(s);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return s;
    }

    public static String normalizeGeneratedTestCode(String code, String testFqn) {
        if (code == null) {
            return "";
        }
        String s = stripMarkdownCodeFence(code).trim();
        String pkg = packageNameOf(testFqn);
        String simple = simpleNameOf(testFqn);

        String astNormalized = normalizeGeneratedTestCodeWithJavaParser(s, pkg, simple);
        if (astNormalized != null) {
            s = astNormalized;
        } else {
            // Only use legacy textual normalization when the generated candidate is not
            // parseable yet. javac/repair will handle the remaining syntax error.
            s = removeInvalidDefaultPackageImports(s);
            if (!pkg.isEmpty() && !Pattern.compile("(?m)^\\s*package\\s+" + Pattern.quote(pkg) + "\\s*;").matcher(s).find()) {
                s = s.replaceFirst("(?m)^\\s*package\\s+[^;]+;\\s*", "");
                s = "package " + pkg + ";\n\n" + s;
            }
            Matcher m = Pattern.compile("public\\s+class\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(s);
            if (m.find() && !simple.equals(m.group(1))) {
                s = m.replaceFirst("public class " + simple);
            }
            s = addThrowsExceptionToJUnit4TestMethods(s);
        }

        if (GeneratorFeatureFlags.benchmarkAdaptersEnabled()) {
            s = normalizeKnownLegacyApiCalls(s, pkg);
            s = normalizeKnownOotTriangleConstants(s);
        }
        return s;
    }

    private static String normalizeGeneratedTestCodeWithJavaParser(String code,
                                                                     String packageName,
                                                                     String expectedSimpleName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);

            if (packageName == null || packageName.trim().isEmpty()) {
                if (cu.getPackageDeclaration().isPresent()) {
                    cu.getPackageDeclaration().get().remove();
                }
            } else {
                cu.setPackageDeclaration(packageName);
            }

            for (int i = cu.getImports().size() - 1; i >= 0; i--) {
                ImportDeclaration imp = cu.getImports().get(i);
                if (!imp.isAsterisk()
                        && !imp.isStatic()
                        && imp.getNameAsString().indexOf('.') < 0) {
                    imp.remove();
                }
            }

            ensureJUnit4Imports(cu);

            TypeDeclaration<?> target = null;
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (type.isPublic()) {
                    target = type;
                    break;
                }
            }
            if (target == null && !cu.getTypes().isEmpty()) {
                target = cu.getType(0);
            }
            if (target != null
                    && expectedSimpleName != null
                    && !expectedSimpleName.trim().isEmpty()
                    && !expectedSimpleName.equals(target.getNameAsString())) {
                target.setName(expectedSimpleName);
            }

            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (!hasJUnitTestAnnotation(method)) {
                    continue;
                }
                boolean alreadyThrowsException = false;
                for (com.github.javaparser.ast.type.ReferenceType thrown : method.getThrownExceptions()) {
                    if ("Exception".equals(thrown.toString())
                            || thrown.toString().endsWith(".Exception")) {
                        alreadyThrowsException = true;
                        break;
                    }
                }
                if (!alreadyThrowsException) {
                    method.addThrownException(StaticJavaParser.parseClassOrInterfaceType("Exception"));
                }
            }
            return cu.toString().trim();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void ensureJUnit4Imports(CompilationUnit cu) {
        if (cu == null) {
            return;
        }

        boolean usesSimpleTestAnnotation = false;
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (com.github.javaparser.ast.expr.AnnotationExpr annotation : method.getAnnotations()) {
                if ("Test".equals(annotation.getNameAsString())) {
                    usesSimpleTestAnnotation = true;
                    break;
                }
            }
            if (usesSimpleTestAnnotation) {
                break;
            }
        }
        if (usesSimpleTestAnnotation && !hasJUnitTestImport(cu)) {
            cu.addImport("org.junit.Test");
        }

        boolean usesUnqualifiedAssert = false;
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!call.getScope().isPresent() && isJUnitAssertMethod(call.getNameAsString())) {
                usesUnqualifiedAssert = true;
                break;
            }
        }
        if (usesUnqualifiedAssert && !hasJUnitAssertStaticImport(cu)) {
            cu.addImport("org.junit.Assert", true, true);
        }
    }

    private static boolean hasJUnitTestImport(CompilationUnit cu) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic()) {
                continue;
            }
            String name = imp.getNameAsString();
            if ("org.junit.Test".equals(name)
                    || (imp.isAsterisk() && "org.junit".equals(name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJUnitAssertStaticImport(CompilationUnit cu) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isStatic()) {
                continue;
            }
            String name = imp.getNameAsString();
            if ((imp.isAsterisk() && "org.junit.Assert".equals(name))
                    || name.startsWith("org.junit.Assert.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJUnitAssertMethod(String name) {
        return "assertEquals".equals(name)
                || "assertNotEquals".equals(name)
                || "assertTrue".equals(name)
                || "assertFalse".equals(name)
                || "assertNull".equals(name)
                || "assertNotNull".equals(name)
                || "assertSame".equals(name)
                || "assertNotSame".equals(name)
                || "assertArrayEquals".equals(name)
                || "assertThat".equals(name)
                || "fail".equals(name);
    }

    private static boolean hasJUnitTestAnnotation(MethodDeclaration method) {
        if (method == null) {
            return false;
        }
        for (com.github.javaparser.ast.expr.AnnotationExpr annotation : method.getAnnotations()) {
            if ("Test".equals(annotation.getNameAsString())
                    || annotation.getNameAsString().endsWith(".Test")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKnownOotTriangleConstants(String code) {
        String s = code == null ? "" : code;
        // The OOT Triangle benchmark keeps these constants private; generated tests must assert literals.
        s = s.replaceAll("\\bTriangle\\s*\\.\\s*INVALID\\b", "4");
        s = s.replaceAll("\\bTriangle\\s*\\.\\s*SCALENE\\b", "1");
        s = s.replaceAll("\\bTriangle\\s*\\.\\s*ISOSCELES\\b", "2");
        s = s.replaceAll("\\bTriangle\\s*\\.\\s*EQUILATERAL\\b", "3");
        return s;
    }

    private static String normalizeKnownLegacyApiCalls(String code, String testPackage) {
        String s = code == null ? "" : code;
        if (!"org.apache.commons.csv".equals(testPackage)) {
            return s;
        }
        // commons-csv 1.2 exposes Token.type as a package-visible field; it has no getType() accessor.
        s = s.replaceAll("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*getType\\s*\\(\\s*\\)", "$1.type");
        // commons-csv 1.2 Token also exposes content as a field instead of getContent()/getValue().
        s = s.replaceAll("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*getContent\\s*\\(\\s*\\)", "$1.content");
        s = s.replaceAll("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*getValue\\s*\\(\\s*\\)", "$1.content.toString()");
        // Older bad generations often build Lexer with reversed constructor arguments.
        s = s.replaceAll(
                "new\\s+Lexer\\s*\\(\\s*(new\\s+StringReader\\s*\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*,\\s*CSVFormat\\.DEFAULT\\s*\\)",
                "new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader($1))");
        s = s.replaceAll(
                "new\\s+Lexer\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*,\\s*CSVFormat\\.DEFAULT\\s*\\)",
                "new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader($1))");
        // nextToken(null) compiles poorly for the intended commons-csv observable path and loses propagation.
        s = s.replaceAll("\\.nextToken\\s*\\(\\s*null\\s*\\)", ".nextToken(new Token())");
        return s;
    }

    private static String addThrowsExceptionToJUnit4TestMethods(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        Pattern p = Pattern.compile(
                "(@Test(?:\\s*\\([^)]*\\))?\\s*(?:\\r?\\n\\s*)+(?:public\\s+)void\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^)]*\\))(\\s*\\{)",
                Pattern.MULTILINE);
        Matcher m = p.matcher(code);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String declaration = m.group(1);
            if (Pattern.compile("\\bthrows\\b").matcher(declaration).find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(declaration + " throws Exception" + m.group(2)));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String removeInvalidDefaultPackageImports(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Pattern invalidSingleNameImport = Pattern.compile("^\\s*import\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*;\\s*$");

        for (String line : lines) {
            if (invalidSingleNameImport.matcher(line).matches()) {
                // Java does not allow importing classes from the default package, e.g. import Vector3D;
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }
}
