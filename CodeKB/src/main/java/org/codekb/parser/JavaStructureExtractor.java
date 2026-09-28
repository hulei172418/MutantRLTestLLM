package org.codekb.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import org.codekb.model.ParsedSourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JavaStructureExtractor {
    private static final Set<String> PRIMITIVE_TYPES = new LinkedHashSet<String>();
    private static final Set<String> JAVA_LANG_SIMPLE_TYPES = new LinkedHashSet<String>();

    static {
        Collections.addAll(PRIMITIVE_TYPES,
            "byte", "short", "int", "long", "float", "double", "char", "boolean", "void");
        Collections.addAll(JAVA_LANG_SIMPLE_TYPES,
            "String", "Object", "Class", "Throwable", "Exception", "RuntimeException", "Error",
            "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
            "CharSequence", "Appendable", "StringBuilder", "StringBuffer", "Iterable");
    }

    private final JavaParser parser = new JavaParser();

    public ParsedSourceFile extract(Path javaFile) throws IOException {
        ParseResult<CompilationUnit> result = parser.parse(javaFile, StandardCharsets.UTF_8);
        CompilationUnit unit = result.getResult()
            .orElseThrow(() -> new IOException("Unable to parse " + javaFile + ": " + result.getProblems()));
        String packageName = unit.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        ImportResolver importResolver = ImportResolver.from(unit, packageName);
        List<ParsedSourceFile.TypeInfo> types = new ArrayList<ParsedSourceFile.TypeInfo>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            collectType(packageName, "", type, types, importResolver);
        }
        return new ParsedSourceFile(packageName, new ArrayList<String>(importResolver.explicitImports), types);
    }

    private void collectType(String packageName, String enclosingQualifiedName, TypeDeclaration<?> type, List<ParsedSourceFile.TypeInfo> out,
                             ImportResolver importResolver) {
        String qualifiedName = packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
        String superClass = "";
        String kind = "class";
        List<String> implementedTypes = new ArrayList<String>();
        if (type instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) type;
            kind = cid.isInterface() ? "interface" : "class";
            if (!cid.getExtendedTypes().isEmpty()) {
                superClass = cid.getExtendedTypes().get(0).getNameAsString();
            }
            cid.getImplementedTypes().forEach(t -> implementedTypes.add(t.getNameAsString()));
        } else if (type instanceof EnumDeclaration) {
            kind = "enum";
            ((EnumDeclaration) type).getImplementedTypes().forEach(t -> implementedTypes.add(t.getNameAsString()));
        }

        List<ParsedSourceFile.FieldInfo> fields = new ArrayList<ParsedSourceFile.FieldInfo>();
        List<ParsedSourceFile.MethodInfo> methods = new ArrayList<ParsedSourceFile.MethodInfo>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration) {
                collectField((FieldDeclaration) member, fields);
            } else if (member instanceof ConstructorDeclaration) {
                methods.add(toMethodInfo((ConstructorDeclaration) member, importResolver));
            } else if (member instanceof MethodDeclaration) {
                methods.add(toMethodInfo((MethodDeclaration) member, importResolver));
            } else if (member instanceof TypeDeclaration) {
                collectType(qualifiedName, qualifiedName, (TypeDeclaration<?>) member, out, importResolver);
            }
        }
        out.add(new ParsedSourceFile.TypeInfo(
            qualifiedName,
            type.getNameAsString(),
            kind,
            superClass,
            enclosingQualifiedName,
            implementedTypes,
            visibilityOf(type),
            isStaticType(type),
            isFinalType(type),
            isAbstractType(type),
            fields,
            methods
        ));
    }

    private void collectField(FieldDeclaration fieldDeclaration, List<ParsedSourceFile.FieldInfo> out) {
        for (com.github.javaparser.ast.body.VariableDeclarator vd : fieldDeclaration.getVariables()) {
            out.add(new ParsedSourceFile.FieldInfo(
                vd.getNameAsString(),
                vd.getType().asString(),
                visibilityOf(fieldDeclaration),
                fieldDeclaration.isStatic(),
                fieldDeclaration.isFinal(),
                fieldDeclaration.isPublic()
            ));
        }
    }

    private ParsedSourceFile.MethodInfo toMethodInfo(ConstructorDeclaration declaration, ImportResolver importResolver) {
        return new ParsedSourceFile.MethodInfo(
            declaration.getNameAsString(),
            buildSignature(declaration),
            declaration.getNameAsString(),
            true,
            visibilityOf(declaration),
            false,
            false,
            false,
            declaration.isPublic(),
            lineOf(declaration.getBegin()),
            lineOf(declaration.getEnd()),
            collectParameters(declaration),
            collectThrownExceptions(declaration),
            collectImportHints(declaration, importResolver, declaration.getNameAsString()),
            collectMethodCalls(declaration),
            collectFieldAccesses(declaration)
        );
    }

    private ParsedSourceFile.MethodInfo toMethodInfo(MethodDeclaration declaration, ImportResolver importResolver) {
        return new ParsedSourceFile.MethodInfo(
            declaration.getNameAsString(),
            buildSignature(declaration),
            declaration.getType().asString(),
            false,
            visibilityOf(declaration),
            declaration.isStatic(),
            declaration.isFinal(),
            declaration.isAbstract(),
            declaration.isPublic(),
            lineOf(declaration.getBegin()),
            lineOf(declaration.getEnd()),
            collectParameters(declaration),
            collectThrownExceptions(declaration),
            collectImportHints(declaration, importResolver, declaration.getType().asString()),
            collectMethodCalls(declaration),
            collectFieldAccesses(declaration)
        );
    }

    private List<ParsedSourceFile.ParameterInfo> collectParameters(CallableDeclaration<?> declaration) {
        List<ParsedSourceFile.ParameterInfo> parameters = new ArrayList<ParsedSourceFile.ParameterInfo>();
        for (int i = 0; i < declaration.getParameters().size(); i++) {
            Parameter parameter = declaration.getParameter(i);
            parameters.add(new ParsedSourceFile.ParameterInfo(
                i,
                parameter.getNameAsString(),
                parameter.getType().asString(),
                parameter.isVarArgs()
            ));
        }
        return parameters;
    }

    private List<String> collectThrownExceptions(CallableDeclaration<?> declaration) {
        List<String> thrown = new ArrayList<String>();
        declaration.getThrownExceptions().forEach(t -> thrown.add(t.asString()));
        return thrown;
    }

    private List<ParsedSourceFile.ImportHintInfo> collectImportHints(
        CallableDeclaration<?> declaration,
        ImportResolver importResolver,
        String returnType
    ) {
        Map<String, ParsedSourceFile.ImportHintInfo> hints = new LinkedHashMap<String, ParsedSourceFile.ImportHintInfo>();
        registerImportHint(hints, importResolver, returnType, "RETURN_TYPE", declaration.getNameAsString(), 70);
        declaration.getParameters().forEach(parameter ->
            registerImportHint(hints, importResolver, parameter.getType().asString(), "PARAMETER_TYPE",
                parameter.getNameAsString(), 95));
        declaration.getThrownExceptions().forEach(thrownType ->
            registerImportHint(hints, importResolver, thrownType.asString(), "THROWN_EXCEPTION",
                declaration.getNameAsString(), 85));
        for (ObjectCreationExpr creationExpr : declaration.findAll(ObjectCreationExpr.class)) {
            registerImportHint(hints, importResolver, creationExpr.getType().asString(), "OBJECT_CREATION",
                creationExpr.toString(), 75);
        }
        return new ArrayList<ParsedSourceFile.ImportHintInfo>(hints.values());
    }

    private void registerImportHint(
        Map<String, ParsedSourceFile.ImportHintInfo> hints,
        ImportResolver importResolver,
        String rawType,
        String sourceKind,
        String usageContext,
        int priority
    ) {
        String qualified = importResolver.qualifyType(rawType);
        if (qualified.isEmpty() || importResolver.isJavaLangType(qualified) || isPrimitiveLike(qualified)) {
            return;
        }
        String key = qualified + "|" + sourceKind;
        ParsedSourceFile.ImportHintInfo existing = hints.get(key);
        if (existing == null || priority > existing.getPriority()) {
            hints.put(key, new ParsedSourceFile.ImportHintInfo(qualified, sourceKind, usageContext, priority));
        }
    }

    private List<ParsedSourceFile.MethodCallInfo> collectMethodCalls(CallableDeclaration<?> declaration) {
        List<ParsedSourceFile.MethodCallInfo> calls = new ArrayList<ParsedSourceFile.MethodCallInfo>();
        for (MethodCallExpr expr : declaration.findAll(MethodCallExpr.class)) {
            String owner = expr.getScope().map(Node::toString).orElse("");
            calls.add(new ParsedSourceFile.MethodCallInfo(
                owner,
                expr.getNameAsString(),
                buildCallSignature(expr),
                lineOf(expr.getBegin())
            ));
        }
        for (ObjectCreationExpr expr : declaration.findAll(ObjectCreationExpr.class)) {
            String owner = expr.getType().asString();
            calls.add(new ParsedSourceFile.MethodCallInfo(
                owner,
                simpleType(owner),
                buildConstructorCallSignature(owner, expr.getArguments().size()),
                lineOf(expr.getBegin())
            ));
        }
        for (ExplicitConstructorInvocationStmt stmt : declaration.findAll(ExplicitConstructorInvocationStmt.class)) {
            String owner = stmt.isThis() ? declaration.getNameAsString() : "super";
            calls.add(new ParsedSourceFile.MethodCallInfo(
                owner,
                owner,
                buildConstructorCallSignature(owner, stmt.getArguments().size()),
                lineOf(stmt.getBegin())
            ));
        }
        return calls;
    }

    private List<ParsedSourceFile.FieldAccessInfo> collectFieldAccesses(CallableDeclaration<?> declaration) {
        List<ParsedSourceFile.FieldAccessInfo> accesses = new ArrayList<ParsedSourceFile.FieldAccessInfo>();
        Set<String> declaredLocals = collectDeclaredLocals(declaration);
        for (FieldAccessExpr expr : declaration.findAll(FieldAccessExpr.class)) {
            String accessKind = isWrite(expr) ? "WRITE" : "READ";
            String owner = expr.getScope() == null ? "" : expr.getScope().toString();
            accesses.add(new ParsedSourceFile.FieldAccessInfo(owner, expr.getNameAsString(), accessKind, lineOf(expr.getBegin())));
        }
        for (NameExpr expr : declaration.findAll(NameExpr.class)) {
            String name = expr.getNameAsString();
            if (declaredLocals.contains(name) || isPartOfFieldAccess(expr) || isMethodNameReference(expr)) {
                continue;
            }
            String accessKind = isWrite(expr) ? "WRITE" : "READ";
            accesses.add(new ParsedSourceFile.FieldAccessInfo("", name, accessKind, lineOf(expr.getBegin())));
        }
        return accesses;
    }

    private Set<String> collectDeclaredLocals(CallableDeclaration<?> declaration) {
        Set<String> locals = new HashSet<String>();
        for (com.github.javaparser.ast.body.Parameter parameter : declaration.getParameters()) {
            locals.add(parameter.getNameAsString());
        }
        for (VariableDeclarationExpr expr : declaration.findAll(VariableDeclarationExpr.class)) {
            for (com.github.javaparser.ast.body.VariableDeclarator vd : expr.getVariables()) {
                locals.add(vd.getNameAsString());
            }
        }
        return locals;
    }

    private boolean isPartOfFieldAccess(NameExpr expr) {
        return expr.getParentNode().isPresent() && expr.getParentNode().get() instanceof FieldAccessExpr;
    }

    private boolean isMethodNameReference(NameExpr expr) {
        return expr.getParentNode().isPresent() && expr.getParentNode().get() instanceof MethodCallExpr
            && ((MethodCallExpr) expr.getParentNode().get()).getName().getIdentifier().equals(expr.getNameAsString());
    }

    private boolean isWrite(Node node) {
        if (!node.getParentNode().isPresent()) {
            return false;
        }
        Node parent = node.getParentNode().get();
        if (parent instanceof AssignExpr) {
            return ((AssignExpr) parent).getTarget() == node;
        }
        return false;
    }

    private String buildSignature(CallableDeclaration<?> declaration) {
        StringBuilder sb = new StringBuilder();
        sb.append(declaration.getNameAsString()).append("(");
        for (int i = 0; i < declaration.getParameters().size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(declaration.getParameter(i).getType().asString());
        }
        sb.append(")");
        return sb.toString();
    }

    private String buildCallSignature(MethodCallExpr expr) {
        StringBuilder sb = new StringBuilder();
        sb.append(expr.getNameAsString()).append("(");
        for (int i = 0; i < expr.getArguments().size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(expr.getArgument(i).toString());
        }
        sb.append(")");
        return sb.toString();
    }

    private String buildConstructorCallSignature(String rawType, int arity) {
        String name = simpleType(rawType);
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("(");
        for (int i = 0; i < arity; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("*");
        }
        sb.append(")");
        return sb.toString();
    }

    private String simpleType(String rawType) {
        if (rawType == null) {
            return "";
        }
        String text = rawType.trim();
        int generic = text.indexOf('<');
        if (generic >= 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    private int lineOf(Optional<Position> position) {
        return position.isPresent() ? position.get().line : -1;
    }

    private String visibilityOf(FieldDeclaration declaration) {
        if (declaration.isPublic()) {
            return "public";
        }
        if (declaration.isProtected()) {
            return "protected";
        }
        if (declaration.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private String visibilityOf(ConstructorDeclaration declaration) {
        if (declaration.isPublic()) {
            return "public";
        }
        if (declaration.isProtected()) {
            return "protected";
        }
        if (declaration.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private String visibilityOf(MethodDeclaration declaration) {
        if (declaration.isPublic()) {
            return "public";
        }
        if (declaration.isProtected()) {
            return "protected";
        }
        if (declaration.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private String visibilityOf(TypeDeclaration<?> type) {
        if (type.isPublic()) {
            return "public";
        }
        if (type.isProtected()) {
            return "protected";
        }
        if (type.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private boolean isStaticType(TypeDeclaration<?> type) {
        return type.isStatic();
    }

    private boolean isFinalType(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) {
            return true;
        }
        return type instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) type).isFinal();
    }

    private boolean isAbstractType(TypeDeclaration<?> type) {
        return type instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) type).isAbstract();
    }

    private boolean isPrimitiveLike(String typeName) {
        return PRIMITIVE_TYPES.contains(typeName);
    }

    private static final class ImportResolver {
        private final String packageName;
        private final Set<String> explicitImports;
        private final Map<String, String> simpleNameIndex;

        private ImportResolver(String packageName, Set<String> explicitImports, Map<String, String> simpleNameIndex) {
            this.packageName = packageName == null ? "" : packageName;
            this.explicitImports = Collections.unmodifiableSet(new LinkedHashSet<String>(explicitImports));
            this.simpleNameIndex = Collections.unmodifiableMap(new LinkedHashMap<String, String>(simpleNameIndex));
        }

        private static ImportResolver from(CompilationUnit unit, String packageName) {
            Set<String> explicitImports = new LinkedHashSet<String>();
            Map<String, String> simpleNameIndex = new LinkedHashMap<String, String>();
            unit.getImports().forEach(importDecl -> {
                String value = importDecl.getNameAsString();
                if (importDecl.isStatic()) {
                    value = "static " + value + (importDecl.isAsterisk() ? ".*" : "");
                } else if (importDecl.isAsterisk()) {
                    value = value + ".*";
                } else {
                    int dot = value.lastIndexOf('.');
                    simpleNameIndex.put(dot >= 0 ? value.substring(dot + 1) : value, value);
                }
                explicitImports.add(value);
            });
            return new ImportResolver(packageName, explicitImports, simpleNameIndex);
        }

        private String qualifyType(String rawType) {
            if (rawType == null || rawType.trim().isEmpty()) {
                return "";
            }
            String cleaned = stripTypeNoise(rawType);
            if (cleaned.isEmpty()) {
                return "";
            }
            int arrayIndex = cleaned.indexOf('[');
            if (arrayIndex >= 0) {
                cleaned = cleaned.substring(0, arrayIndex);
            }
            if (cleaned.contains(".")) {
                return cleaned;
            }
            if (isPrimitive(cleaned)) {
                return cleaned;
            }
            if (isJavaLangSimple(cleaned)) {
                return "java.lang." + cleaned;
            }
            String imported = simpleNameIndex.get(cleaned);
            if (imported != null) {
                return imported;
            }
            return packageName.isEmpty() ? cleaned : packageName + "." + cleaned;
        }

        private boolean isJavaLangType(String typeName) {
            return typeName.startsWith("java.lang.") || isJavaLangSimple(typeName);
        }

        private boolean isPrimitive(String typeName) {
            return PRIMITIVE_TYPES.contains(typeName);
        }

        private boolean isJavaLangSimple(String typeName) {
            String simpleName = typeName;
            int dot = simpleName.lastIndexOf('.');
            if (dot >= 0) {
                simpleName = simpleName.substring(dot + 1);
            }
            return JAVA_LANG_SIMPLE_TYPES.contains(simpleName);
        }

        private String stripTypeNoise(String rawType) {
            String text = rawType.replace("...", "[]").replace('$', '.').trim();
            StringBuilder out = new StringBuilder();
            int depth = 0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '<') {
                    depth++;
                    continue;
                }
                if (ch == '>') {
                    depth--;
                    continue;
                }
                if (depth == 0) {
                    out.append(ch);
                }
            }
            text = out.toString().replaceAll("@\\w+(\\([^)]*\\))?\\s*", "").replaceAll("\\s+", "");
            if (text.startsWith("?extends")) {
                text = text.substring("?extends".length());
            } else if (text.startsWith("?super")) {
                text = text.substring("?super".length());
            } else if ("?".equals(text)) {
                return "";
            }
            return text;
        }
    }
}
