package org.codekb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MutantContextView {
    private final MutantInfo mutant;
    private final FileLink originalFile;
    private final FileLink mutantFile;
    private final MethodLink originalMethod;
    private final MethodLink mutantMethod;
    private final List<MethodCallView> mutantMethodCalls;
    private final List<FieldAccessView> mutantFieldAccesses;
    private final List<MethodLink> candidateEntries;
    private final List<CandidateImportView> candidateImports;
    private final TypeView ownerType;
    private final List<MethodFactView> ownerMethods;
    private final List<FieldFactView> ownerFields;
    private final List<FieldFactView> affectedFields;
    private final List<FieldPropagationView> fieldPropagationCandidates;
    private final List<ObservableCandidateView> observableCandidates;
    private final List<FieldObserverLinkView> fieldObserverLinks;
    private final List<WitnessCandidateView> witnessCandidates;

    public MutantContextView(
            MutantInfo mutant,
            FileLink originalFile,
            FileLink mutantFile,
            MethodLink originalMethod,
            MethodLink mutantMethod,
            List<MethodCallView> mutantMethodCalls,
            List<FieldAccessView> mutantFieldAccesses,
            List<MethodLink> candidateEntries,
            List<CandidateImportView> candidateImports,
            TypeView ownerType,
            List<MethodFactView> ownerMethods,
            List<FieldFactView> ownerFields,
            List<FieldFactView> affectedFields,
            List<FieldPropagationView> fieldPropagationCandidates,
            List<ObservableCandidateView> observableCandidates,
            List<FieldObserverLinkView> fieldObserverLinks,
            List<WitnessCandidateView> witnessCandidates) {
        this.mutant = mutant;
        this.originalFile = originalFile;
        this.mutantFile = mutantFile;
        this.originalMethod = originalMethod;
        this.mutantMethod = mutantMethod;
        this.mutantMethodCalls = Collections.unmodifiableList(new ArrayList<MethodCallView>(mutantMethodCalls));
        this.mutantFieldAccesses = Collections.unmodifiableList(new ArrayList<FieldAccessView>(mutantFieldAccesses));
        this.candidateEntries = Collections.unmodifiableList(new ArrayList<MethodLink>(candidateEntries));
        this.candidateImports = Collections.unmodifiableList(new ArrayList<CandidateImportView>(candidateImports));
        this.ownerType = ownerType;
        this.ownerMethods = Collections.unmodifiableList(new ArrayList<MethodFactView>(ownerMethods));
        this.ownerFields = Collections.unmodifiableList(new ArrayList<FieldFactView>(ownerFields));
        this.affectedFields = Collections.unmodifiableList(new ArrayList<FieldFactView>(affectedFields));
        this.fieldPropagationCandidates = Collections.unmodifiableList(new ArrayList<FieldPropagationView>(fieldPropagationCandidates));
        this.observableCandidates = Collections.unmodifiableList(new ArrayList<ObservableCandidateView>(observableCandidates));
        this.fieldObserverLinks = Collections.unmodifiableList(new ArrayList<FieldObserverLinkView>(fieldObserverLinks));
        this.witnessCandidates = Collections.unmodifiableList(new ArrayList<WitnessCandidateView>(witnessCandidates));
    }

    public MutantInfo getMutant() {
        return mutant;
    }

    public FileLink getOriginalFile() {
        return originalFile;
    }

    public FileLink getMutantFile() {
        return mutantFile;
    }

    public MethodLink getOriginalMethod() {
        return originalMethod;
    }

    public MethodLink getMutantMethod() {
        return mutantMethod;
    }

    public List<MethodCallView> getMutantMethodCalls() {
        return mutantMethodCalls;
    }

    public List<FieldAccessView> getMutantFieldAccesses() {
        return mutantFieldAccesses;
    }

    public List<MethodLink> getCandidateEntries() {
        return candidateEntries;
    }

    public List<CandidateImportView> getCandidateImports() {
        return candidateImports;
    }

    public TypeView getOwnerType() {
        return ownerType;
    }

    public List<MethodFactView> getOwnerMethods() {
        return ownerMethods;
    }

    public List<FieldFactView> getOwnerFields() {
        return ownerFields;
    }

    public List<FieldFactView> getAffectedFields() {
        return affectedFields;
    }

    public List<FieldPropagationView> getFieldPropagationCandidates() {
        return fieldPropagationCandidates;
    }

    public List<ObservableCandidateView> getObservableCandidates() {
        return observableCandidates;
    }

    public List<FieldObserverLinkView> getFieldObserverLinks() {
        return fieldObserverLinks;
    }

    public List<WitnessCandidateView> getWitnessCandidates() {
        return witnessCandidates;
    }

    public static final class MutantInfo {
        private final long rowId;
        private final String mutantId;
        private final String project;
        private final String operator;
        private final int lineNumber;
        private final String className;
        private final String methodName;
        private final String mutationStatement;

        public MutantInfo(
                long rowId,
                String mutantId,
                String project,
                String operator,
                int lineNumber,
                String className,
                String methodName,
                String mutationStatement) {
            this.rowId = rowId;
            this.mutantId = mutantId;
            this.project = project;
            this.operator = operator;
            this.lineNumber = lineNumber;
            this.className = className;
            this.methodName = methodName;
            this.mutationStatement = mutationStatement;
        }

        public long getRowId() {
            return rowId;
        }

        public String getMutantId() {
            return mutantId;
        }

        public String getProject() {
            return project;
        }

        public String getOperator() {
            return operator;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getMutationStatement() {
            return mutationStatement;
        }
    }

    public static final class FileLink {
        private final long fileId;
        private final String path;
        private final String packageName;

        public FileLink(long fileId, String path, String packageName) {
            this.fileId = fileId;
            this.path = path;
            this.packageName = packageName;
        }

        public long getFileId() {
            return fileId;
        }

        public String getPath() {
            return path;
        }

        public String getPackageName() {
            return packageName;
        }
    }

    public static final class MethodLink {
        private final long methodId;
        private final long typeId;
        private final String typeName;
        private final String methodName;
        private final String signature;
        private final String returnType;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;
        private final boolean isAbstract;
        private final boolean isConstructor;
        private final boolean isPublic;
        private final int beginLine;
        private final int endLine;
        private final double score;
        private final String reason;

        public MethodLink(
                long methodId,
                long typeId,
                String typeName,
                String methodName,
                String signature,
                String returnType,
                String visibility,
                boolean isStatic,
                boolean isFinal,
                boolean isAbstract,
                boolean isConstructor,
                boolean isPublic,
                int beginLine,
                int endLine,
                double score,
                String reason) {
            this.methodId = methodId;
            this.typeId = typeId;
            this.typeName = typeName;
            this.methodName = methodName;
            this.signature = signature;
            this.returnType = returnType;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
            this.isAbstract = isAbstract;
            this.isConstructor = isConstructor;
            this.isPublic = isPublic;
            this.beginLine = beginLine;
            this.endLine = endLine;
            this.score = score;
            this.reason = reason;
        }

        public long getMethodId() {
            return methodId;
        }

        public long getTypeId() {
            return typeId;
        }

        public String getTypeName() {
            return typeName;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getSignature() {
            return signature;
        }

        public String getReturnType() {
            return returnType;
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

        public boolean isConstructor() {
            return isConstructor;
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

        public double getScore() {
            return score;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class MethodCallView {
        private final String owner;
        private final String methodName;
        private final String signature;
        private final int lineNumber;

        public MethodCallView(String owner, String methodName, String signature, int lineNumber) {
            this.owner = owner;
            this.methodName = methodName;
            this.signature = signature;
            this.lineNumber = lineNumber;
        }

        public String getOwner() {
            return owner;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getSignature() {
            return signature;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    public static final class FieldAccessView {
        private final String ownerType;
        private final String fieldName;
        private final String accessKind;
        private final int lineNumber;

        public FieldAccessView(String ownerType, String fieldName, String accessKind, int lineNumber) {
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

    public static final class CandidateImportView {
        private final String importValue;
        private final String sourceKind;
        private final String usageContext;
        private final int priority;
        private final String scope;
        private final String ownerMethodSignature;
        private final String knowledgeSource;

        public CandidateImportView(
                String importValue,
                String sourceKind,
                String usageContext,
                int priority,
                String scope,
                String ownerMethodSignature,
                String knowledgeSource) {
            this.importValue = importValue;
            this.sourceKind = sourceKind;
            this.usageContext = usageContext;
            this.priority = priority;
            this.scope = scope;
            this.ownerMethodSignature = ownerMethodSignature;
            this.knowledgeSource = knowledgeSource;
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

        public String getScope() {
            return scope;
        }

        public String getOwnerMethodSignature() {
            return ownerMethodSignature;
        }

        public String getKnowledgeSource() {
            return knowledgeSource;
        }
    }

    public static final class TypeView {
        private final long typeId;
        private final String qualifiedName;
        private final String simpleName;
        private final String kind;
        private final String superClass;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;
        private final boolean isAbstract;

        public TypeView(long typeId,
                        String qualifiedName,
                        String simpleName,
                        String kind,
                        String superClass,
                        String visibility,
                        boolean isStatic,
                        boolean isFinal,
                        boolean isAbstract) {
            this.typeId = typeId;
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.kind = kind;
            this.superClass = superClass;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
            this.isAbstract = isAbstract;
        }

        public long getTypeId() {
            return typeId;
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
    }

    public static final class MethodFactView {
        private final long methodId;
        private final String name;
        private final String signature;
        private final String returnType;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;
        private final boolean isAbstract;
        private final boolean isConstructor;

        public MethodFactView(long methodId,
                              String name,
                              String signature,
                              String returnType,
                              String visibility,
                              boolean isStatic,
                              boolean isFinal,
                              boolean isAbstract,
                              boolean isConstructor) {
            this.methodId = methodId;
            this.name = name;
            this.signature = signature;
            this.returnType = returnType;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
            this.isAbstract = isAbstract;
            this.isConstructor = isConstructor;
        }

        public long getMethodId() {
            return methodId;
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

        public boolean isConstructor() {
            return isConstructor;
        }
    }

    public static final class FieldFactView {
        private final long fieldId;
        private final String name;
        private final String fieldType;
        private final String visibility;
        private final boolean isStatic;
        private final boolean isFinal;

        public FieldFactView(long fieldId,
                             String name,
                             String fieldType,
                             String visibility,
                             boolean isStatic,
                             boolean isFinal) {
            this.fieldId = fieldId;
            this.name = name;
            this.fieldType = fieldType;
            this.visibility = visibility;
            this.isStatic = isStatic;
            this.isFinal = isFinal;
        }

        public long getFieldId() {
            return fieldId;
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
    }

    public static final class FieldPropagationView {
        private final FieldFactView field;
        private final MethodLink readerMethod;
        private final String accessKind;
        private final int lineNumber;
        private final double score;
        private final String reason;

        public FieldPropagationView(FieldFactView field,
                                    MethodLink readerMethod,
                                    String accessKind,
                                    int lineNumber,
                                    double score,
                                    String reason) {
            this.field = field;
            this.readerMethod = readerMethod;
            this.accessKind = accessKind;
            this.lineNumber = lineNumber;
            this.score = score;
            this.reason = reason;
        }

        public FieldFactView getField() {
            return field;
        }

        public MethodLink getReaderMethod() {
            return readerMethod;
        }

        public String getAccessKind() {
            return accessKind;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public double getScore() {
            return score;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class ObservableCandidateView {
        private final Long methodId;
        private final String methodSignature;
        private final String observableKind;
        private final String expression;
        private final double priority;
        private final boolean primary;
        private final String reason;

        public ObservableCandidateView(Long methodId,
                                       String methodSignature,
                                       String observableKind,
                                       String expression,
                                       double priority,
                                       boolean primary,
                                       String reason) {
            this.methodId = methodId;
            this.methodSignature = methodSignature;
            this.observableKind = observableKind;
            this.expression = expression;
            this.priority = priority;
            this.primary = primary;
            this.reason = reason;
        }

        public Long getMethodId() {
            return methodId;
        }

        public String getMethodSignature() {
            return methodSignature;
        }

        public String getObservableKind() {
            return observableKind;
        }

        public String getExpression() {
            return expression;
        }

        public double getPriority() {
            return priority;
        }

        public boolean isPrimary() {
            return primary;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class FieldObserverLinkView {
        private final long fieldId;
        private final String fieldName;
        private final long observerMethodId;
        private final String observerMethodSignature;
        private final String observerKind;
        private final int distance;
        private final double priority;
        private final String reason;

        public FieldObserverLinkView(long fieldId,
                                     String fieldName,
                                     long observerMethodId,
                                     String observerMethodSignature,
                                     String observerKind,
                                     int distance,
                                     double priority,
                                     String reason) {
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.observerMethodId = observerMethodId;
            this.observerMethodSignature = observerMethodSignature;
            this.observerKind = observerKind;
            this.distance = distance;
            this.priority = priority;
            this.reason = reason;
        }

        public long getFieldId() {
            return fieldId;
        }

        public String getFieldName() {
            return fieldName;
        }

        public long getObserverMethodId() {
            return observerMethodId;
        }

        public String getObserverMethodSignature() {
            return observerMethodSignature;
        }

        public String getObserverKind() {
            return observerKind;
        }

        public int getDistance() {
            return distance;
        }

        public double getPriority() {
            return priority;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class WitnessCandidateView {
        private final Long entryMethodId;
        private final String entryMethodSignature;
        private final Long observableMethodId;
        private final String observableMethodSignature;
        private final int witnessRank;
        private final String receiverSetupJson;
        private final String argumentSetupJson;
        private final String predicateChainJson;
        private final String expectedOriginalOutcome;
        private final String expectedMutantOutcome;
        private final String outcomeKind;
        private final String assertionSketch;
        private final String reason;

        public WitnessCandidateView(Long entryMethodId,
                                    String entryMethodSignature,
                                    Long observableMethodId,
                                    String observableMethodSignature,
                                    int witnessRank,
                                    String receiverSetupJson,
                                    String argumentSetupJson,
                                    String predicateChainJson,
                                    String expectedOriginalOutcome,
                                    String expectedMutantOutcome,
                                    String outcomeKind,
                                    String assertionSketch,
                                    String reason) {
            this.entryMethodId = entryMethodId;
            this.entryMethodSignature = entryMethodSignature;
            this.observableMethodId = observableMethodId;
            this.observableMethodSignature = observableMethodSignature;
            this.witnessRank = witnessRank;
            this.receiverSetupJson = receiverSetupJson;
            this.argumentSetupJson = argumentSetupJson;
            this.predicateChainJson = predicateChainJson;
            this.expectedOriginalOutcome = expectedOriginalOutcome;
            this.expectedMutantOutcome = expectedMutantOutcome;
            this.outcomeKind = outcomeKind;
            this.assertionSketch = assertionSketch;
            this.reason = reason;
        }

        public Long getEntryMethodId() {
            return entryMethodId;
        }

        public String getEntryMethodSignature() {
            return entryMethodSignature;
        }

        public Long getObservableMethodId() {
            return observableMethodId;
        }

        public String getObservableMethodSignature() {
            return observableMethodSignature;
        }

        public int getWitnessRank() {
            return witnessRank;
        }

        public String getReceiverSetupJson() {
            return receiverSetupJson;
        }

        public String getArgumentSetupJson() {
            return argumentSetupJson;
        }

        public String getPredicateChainJson() {
            return predicateChainJson;
        }

        public String getExpectedOriginalOutcome() {
            return expectedOriginalOutcome;
        }

        public String getExpectedMutantOutcome() {
            return expectedMutantOutcome;
        }

        public String getOutcomeKind() {
            return outcomeKind;
        }

        public String getAssertionSketch() {
            return assertionSketch;
        }

        public String getReason() {
            return reason;
        }
    }
}
