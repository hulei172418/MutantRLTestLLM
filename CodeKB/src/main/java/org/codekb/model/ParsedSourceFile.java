package org.codekb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParsedSourceFile {
    private final String packageName;
    private final List<String> explicitImports;
    private final List<TypeInfo> types;

    public ParsedSourceFile(String packageName, List<String> explicitImports, List<TypeInfo> types) {
        this.packageName = packageName == null ? "" : packageName;
        this.explicitImports = Collections.unmodifiableList(new ArrayList<String>(explicitImports));
        this.types = Collections.unmodifiableList(new ArrayList<TypeInfo>(types));
    }

    public String getPackageName() {
        return packageName;
    }

    public List<String> getExplicitImports() {
        return explicitImports;
    }

    public List<TypeInfo> getTypes() {
        return types;
    }

    public static final class TypeInfo {
        private final String qualifiedName;
        private final String simpleName;
        private final String kind;
        private final String superClass;
        private final String enclosingQualifiedName;
        private final List<String> implementedTypes;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;
        private final boolean isAbstract;
        private final List<FieldInfo> fields;
        private final List<MethodInfo> methods;

        public TypeInfo(
            String qualifiedName,
            String simpleName,
            String kind,
            String superClass,
            String enclosingQualifiedName,
            List<String> implementedTypes,
            String visibility,
            boolean isStatic,
            boolean isFinal,
            boolean isAbstract,
            List<FieldInfo> fields,
            List<MethodInfo> methods
        ) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.kind = kind;
            this.superClass = superClass;
            this.enclosingQualifiedName = enclosingQualifiedName == null ? "" : enclosingQualifiedName;
            this.implementedTypes = Collections.unmodifiableList(new ArrayList<String>(implementedTypes));
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
            this.isAbstract = isAbstract;
            this.fields = Collections.unmodifiableList(new ArrayList<FieldInfo>(fields));
            this.methods = Collections.unmodifiableList(new ArrayList<MethodInfo>(methods));
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public String getSimpleName() {
            return simpleName;
        }

        public String getKind() {
            return kind;
        }

        public String getSuperClass() {
            return superClass;
        }

        public String getEnclosingQualifiedName() {
            return enclosingQualifiedName;
        }

        public List<String> getImplementedTypes() {
            return implementedTypes;
        }

        public String getVisibility() {
            return visibility;
        }

        public boolean isStatic() {
            return isStatic;
        }

        public boolean isFinal() {
            return isFinal;
        }

        public boolean isAbstract() {
            return isAbstract;
        }

        public List<FieldInfo> getFields() {
            return fields;
        }

        public List<MethodInfo> getMethods() {
            return methods;
        }
    }

    public static final class FieldInfo {
        private final String name;
        private final String fieldType;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;
        private final boolean isPublic;

        public FieldInfo(String name, String fieldType, String visibility, boolean isStatic, boolean isFinal, boolean isPublic) {
            this.name = name;
            this.fieldType = fieldType;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
            this.isPublic = isPublic;
        }

        public String getName() {
            return name;
        }

        public String getFieldType() {
            return fieldType;
        }

        public String getVisibility() {
            return visibility;
        }

        public boolean isStatic() {
            return isStatic;
        }

        public boolean isFinal() {
            return isFinal;
        }

        public boolean isPublic() {
            return isPublic;
        }
    }

    public static final class MethodInfo {
        private final String name;
        private final String signature;
        private final String returnType;
        private final boolean isConstructor;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;
        private final boolean isAbstract;
        private final boolean isPublic;
        private final int beginLine;
        private final int endLine;
        private final List<ParameterInfo> parameters;
        private final List<String> thrownExceptions;
        private final List<ImportHintInfo> importHints;
        private final List<MethodCallInfo> methodCalls;
        private final List<FieldAccessInfo> fieldAccesses;

        public MethodInfo(
            String name,
            String signature,
            String returnType,
            boolean isConstructor,
            String visibility,
            boolean isStatic,
            boolean isFinal,
            boolean isAbstract,
            boolean isPublic,
            int beginLine,
            int endLine,
            List<ParameterInfo> parameters,
            List<String> thrownExceptions,
            List<ImportHintInfo> importHints,
            List<MethodCallInfo> methodCalls,
            List<FieldAccessInfo> fieldAccesses
        ) {
            this.name = name;
            this.signature = signature;
            this.returnType = returnType;
            this.isConstructor = isConstructor;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
            this.isAbstract = isAbstract;
            this.isPublic = isPublic;
            this.beginLine = beginLine;
            this.endLine = endLine;
            this.parameters = Collections.unmodifiableList(new ArrayList<ParameterInfo>(parameters));
            this.thrownExceptions = Collections.unmodifiableList(new ArrayList<String>(thrownExceptions));
            this.importHints = Collections.unmodifiableList(new ArrayList<ImportHintInfo>(importHints));
            this.methodCalls = Collections.unmodifiableList(new ArrayList<MethodCallInfo>(methodCalls));
            this.fieldAccesses = Collections.unmodifiableList(new ArrayList<FieldAccessInfo>(fieldAccesses));
        }

        public String getName() {
            return name;
        }

        public String getSignature() {
            return signature;
        }

        public String getReturnType() {
            return returnType;
        }

        public boolean isConstructor() {
            return isConstructor;
        }

        public String getVisibility() {
            return visibility;
        }

        public boolean isStatic() {
            return isStatic;
        }

        public boolean isFinal() {
            return isFinal;
        }

        public boolean isAbstract() {
            return isAbstract;
        }

        public boolean isPublic() {
            return isPublic;
        }

        public int getBeginLine() {
            return beginLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public List<ParameterInfo> getParameters() {
            return parameters;
        }

        public List<String> getThrownExceptions() {
            return thrownExceptions;
        }

        public List<ImportHintInfo> getImportHints() {
            return importHints;
        }

        public List<MethodCallInfo> getMethodCalls() {
            return methodCalls;
        }

        public List<FieldAccessInfo> getFieldAccesses() {
            return fieldAccesses;
        }
    }

    public static final class ParameterInfo {
        private final int position;
        private final String name;
        private final String type;
        private final boolean varArgs;

        public ParameterInfo(int position, String name, String type, boolean varArgs) {
            this.position = position;
            this.name = name;
            this.type = type;
            this.varArgs = varArgs;
        }

        public int getPosition() {
            return position;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public boolean isVarArgs() {
            return varArgs;
        }
    }

    public static final class ImportHintInfo {
        private final String importValue;
        private final String sourceKind;
        private final String usageContext;
        private final int priority;

        public ImportHintInfo(String importValue, String sourceKind, String usageContext, int priority) {
            this.importValue = importValue;
            this.sourceKind = sourceKind;
            this.usageContext = usageContext;
            this.priority = priority;
        }

        public String getImportValue() {
            return importValue;
        }

        public String getSourceKind() {
            return sourceKind;
        }

        public String getUsageContext() {
            return usageContext;
        }

        public int getPriority() {
            return priority;
        }
    }

    public static final class MethodCallInfo {
        private final String calleeOwner;
        private final String calleeMethodName;
        private final String calleeSignature;
        private final int lineNumber;

        public MethodCallInfo(String calleeOwner, String calleeMethodName, String calleeSignature, int lineNumber) {
            this.calleeOwner = calleeOwner;
            this.calleeMethodName = calleeMethodName;
            this.calleeSignature = calleeSignature;
            this.lineNumber = lineNumber;
        }

        public String getCalleeOwner() {
            return calleeOwner;
        }

        public String getCalleeMethodName() {
            return calleeMethodName;
        }

        public String getCalleeSignature() {
            return calleeSignature;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    public static final class FieldAccessInfo {
        private final String ownerType;
        private final String fieldName;
        private final String accessKind;
        private final int lineNumber;

        public FieldAccessInfo(String ownerType, String fieldName, String accessKind, int lineNumber) {
            this.ownerType = ownerType;
            this.fieldName = fieldName;
            this.accessKind = accessKind;
            this.lineNumber = lineNumber;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getAccessKind() {
            return accessKind;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }
}
