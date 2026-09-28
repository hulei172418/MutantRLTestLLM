package org.codekb.service;

import org.codekb.model.MutantSeed;
import org.codekb.model.ParsedSourceFile;
import org.codekb.parser.JavaStructureExtractor;
import org.codekb.store.MutantRepository;
import org.codekb.store.StructureRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MutantKbAssembler {
    private final JavaStructureExtractor extractor;
    private final StructureRepository structureRepository;
    private final MutantRepository mutantRepository;
    private final Map<String, Long> fileCache;
    private final Map<String, Long> methodResolutionCache;
    private final Map<String, Long> fieldResolutionCache;

    public MutantKbAssembler(JavaStructureExtractor extractor, StructureRepository structureRepository, MutantRepository mutantRepository) {
        this.extractor = extractor;
        this.structureRepository = structureRepository;
        this.mutantRepository = mutantRepository;
        this.fileCache = new HashMap<String, Long>();
        this.methodResolutionCache = new HashMap<String, Long>();
        this.fieldResolutionCache = new HashMap<String, Long>();
    }

    public void assemble(Connection connection, long mutantRowId, long originalVariantId, long mutantVariantId, MutantSeed seed)
        throws IOException, SQLException {
        Path originalJava = seed.resolveOriginalJavaPath();
        Path mutantJava = seed.resolveMutantJavaPath();
        long originalFileId = ingestSourceFile(connection, originalVariantId, originalJava);
        long mutantFileId = ingestSourceFile(connection, mutantVariantId, mutantJava);
        mutantRepository.upsertMutantFileLinks(connection, mutantRowId, originalFileId, mutantFileId);

        long originalMethodId = structureRepository.findMethodIdByNameAndLine(
            connection, originalVariantId, seed.getClassName(), seed.getMethodSimpleName(), seed.getLine());
        long mutantMethodId = structureRepository.findMethodIdByNameAndLine(
            connection, mutantVariantId, seed.getClassName(), seed.getMethodSimpleName(), seed.getLine());
        if (originalMethodId != 0L && mutantMethodId != 0L) {
            mutantRepository.upsertMutantMethodLinks(connection, mutantRowId, originalMethodId, mutantMethodId);
        }
    }

    public long ingestSourceFile(Connection connection, long variantId, Path javaPath) throws IOException, SQLException {
        Path normalized = javaPath.toAbsolutePath().normalize();
        String cacheKey = variantId + "::" + normalized.toString();
        Long cachedId = fileCache.get(cacheKey);
        if (cachedId != null) {
            return cachedId.longValue();
        }
        if (!Files.exists(normalized)) {
            throw new IOException("Java source file not found: " + normalized);
        }
        ParsedSourceFile parsed = extractor.extract(normalized);
        long fileId = structureRepository.upsertFile(connection, variantId, normalized, parsed.getPackageName());
        structureRepository.replaceFileCandidateImports(connection, fileId, parsed.getExplicitImports());
        Map<String, Long> typeIds = new HashMap<String, Long>();
        Map<String, Long> methodIds = new HashMap<String, Long>();
        Map<String, Long> fieldIds = new HashMap<String, Long>();
        for (ParsedSourceFile.TypeInfo type : parsed.getTypes()) {
            long typeId = structureRepository.upsertType(connection, fileId, type);
            typeIds.put(type.getQualifiedName(), Long.valueOf(typeId));
            structureRepository.replaceTypeHierarchy(connection, typeId, type);
            for (ParsedSourceFile.FieldInfo field : type.getFields()) {
                long fieldId = structureRepository.upsertField(connection, typeId, field);
                fieldIds.put(type.getQualifiedName() + "#" + field.getName(), Long.valueOf(fieldId));
            }
            for (ParsedSourceFile.MethodInfo method : type.getMethods()) {
                long methodId = structureRepository.upsertMethod(connection, typeId, method);
                methodIds.put(type.getQualifiedName() + "#" + method.getSignature(), Long.valueOf(methodId));
            }
        }
        for (ParsedSourceFile.TypeInfo type : parsed.getTypes()) {
            for (ParsedSourceFile.MethodInfo method : type.getMethods()) {
                Long methodId = methodIds.get(type.getQualifiedName() + "#" + method.getSignature());
                if (methodId == null) {
                    continue;
                }
                structureRepository.replaceMethodParameters(connection, methodId.longValue(), method.getParameters());
                structureRepository.replaceMethodThrows(connection, methodId.longValue(), method.getThrownExceptions());
                structureRepository.replaceMethodCalls(
                    connection,
                    methodId.longValue(),
                    resolveMethodCalls(connection, variantId, method, methodIds)
                );
                structureRepository.replaceMethodCandidateImports(
                    connection,
                    fileId,
                    methodId.longValue(),
                    method.getImportHints()
                );
                structureRepository.replaceFieldAccesses(
                    connection,
                    methodId.longValue(),
                    resolveFieldAccesses(connection, variantId, method, fieldIds)
                );
            }
        }
        fileCache.put(cacheKey, Long.valueOf(fileId));
        return fileId;
    }

    private List<StructureRepository.ResolvedMethodCall> resolveMethodCalls(
        Connection connection,
        long variantId,
        ParsedSourceFile.MethodInfo method,
        Map<String, Long> localMethodIds
    ) throws SQLException {
        List<StructureRepository.ResolvedMethodCall> calls = new ArrayList<StructureRepository.ResolvedMethodCall>();
        for (ParsedSourceFile.MethodCallInfo call : method.getMethodCalls()) {
            Long calleeMethodId = resolveMethodId(connection, variantId, call, localMethodIds);
            boolean resolved = calleeMethodId != null && calleeMethodId.longValue() != 0L;
            calls.add(new StructureRepository.ResolvedMethodCall(
                calleeMethodId,
                resolved ? null : call.getCalleeOwner(),
                resolved ? null : call.getCalleeMethodName(),
                resolved ? null : call.getCalleeSignature(),
                call.getLineNumber()
            ));
        }
        return calls;
    }

    private List<StructureRepository.ResolvedFieldAccess> resolveFieldAccesses(
        Connection connection,
        long variantId,
        ParsedSourceFile.MethodInfo method,
        Map<String, Long> localFieldIds
    ) throws SQLException {
        List<StructureRepository.ResolvedFieldAccess> accesses = new ArrayList<StructureRepository.ResolvedFieldAccess>();
        for (ParsedSourceFile.FieldAccessInfo access : method.getFieldAccesses()) {
            Long fieldId = resolveFieldId(connection, variantId, access, localFieldIds);
            boolean resolved = fieldId != null && fieldId.longValue() != 0L;
            accesses.add(new StructureRepository.ResolvedFieldAccess(
                fieldId,
                resolved ? null : access.getOwnerType(),
                resolved ? null : access.getFieldName(),
                access.getAccessKind(),
                access.getLineNumber()
            ));
        }
        return accesses;
    }

    private Long resolveMethodId(
        Connection connection,
        long variantId,
        ParsedSourceFile.MethodCallInfo call,
        Map<String, Long> localMethodIds
    ) throws SQLException {
        String owner = call.getCalleeOwner();
        String signature = call.getCalleeSignature();
        if (owner == null || owner.isEmpty() || signature == null || signature.isEmpty()) {
            return null;
        }
        int constructorArity = wildcardConstructorArity(owner, call.getCalleeMethodName(), signature);
        if (constructorArity >= 0) {
            long constructorId = structureRepository.findConstructorIdByOwnerAndArity(
                connection, variantId, owner, constructorArity);
            if (constructorId != 0L) {
                return Long.valueOf(constructorId);
            }
        }
        String key = owner + "#" + signature;
        Long local = localMethodIds.get(key);
        if (local != null) {
            return local;
        }
        String cacheKey = variantId + "::" + key;
        Long cached = methodResolutionCache.get(cacheKey);
        if (cached != null) {
            return cached.longValue() == 0L ? null : cached;
        }
        long resolved = structureRepository.findMethodId(connection, variantId, owner, signature);
        methodResolutionCache.put(cacheKey, Long.valueOf(resolved));
        return resolved == 0L ? null : Long.valueOf(resolved);
    }

    private static int wildcardConstructorArity(String owner, String methodName, String signature) {
        if (owner == null || signature == null || signature.trim().isEmpty()) {
            return -1;
        }
        String simpleOwner = simpleType(owner);
        String name = methodName == null ? "" : methodName.trim();
        if (!name.isEmpty() && !"super".equals(name) && !simpleOwner.equals(name)) {
            return -1;
        }
        String text = signature.trim();
        if (!text.startsWith(simpleOwner + "(") || !text.endsWith(")")) {
            return -1;
        }
        String inside = text.substring(simpleOwner.length() + 1, text.length() - 1).trim();
        if (inside.isEmpty()) {
            return 0;
        }
        String[] parts = inside.split(",");
        for (String part : parts) {
            if (!"*".equals(part.trim())) {
                return -1;
            }
        }
        return parts.length;
    }

    private static String simpleType(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        int generic = text.indexOf('<');
        if (generic >= 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    private Long resolveFieldId(
        Connection connection,
        long variantId,
        ParsedSourceFile.FieldAccessInfo access,
        Map<String, Long> localFieldIds
    ) throws SQLException {
        String owner = access.getOwnerType();
        String fieldName = access.getFieldName();
        if (owner == null || owner.isEmpty() || fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        String key = owner + "#" + fieldName;
        Long local = localFieldIds.get(key);
        if (local != null) {
            return local;
        }
        String cacheKey = variantId + "::" + key;
        Long cached = fieldResolutionCache.get(cacheKey);
        if (cached != null) {
            return cached.longValue() == 0L ? null : cached;
        }
        long resolved = structureRepository.findFieldId(connection, variantId, owner, fieldName);
        fieldResolutionCache.put(cacheKey, Long.valueOf(resolved));
        return resolved == 0L ? null : Long.valueOf(resolved);
    }
}
