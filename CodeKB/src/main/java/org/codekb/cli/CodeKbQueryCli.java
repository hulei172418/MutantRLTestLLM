package org.codekb.cli;

import org.codekb.config.KbConfig;
import org.codekb.model.MutantContextView;
import org.codekb.query.KnowledgeQueryService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class CodeKbQueryCli {
    private CodeKbQueryCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: CodeKbQueryCli <workspaceRoot> <mutantId>");
            return;
        }
        Path workspaceRoot = Paths.get(args[0]);
        String mutantId = args[1];
        KbConfig config = KbConfig.defaults(workspaceRoot);
        MutantContextView view = new KnowledgeQueryService().loadMutantContext(config, mutantId);
        if (view == null) {
            System.out.println("Mutant not found: " + mutantId);
            return;
        }
        print(view);
    }

    private static void print(MutantContextView view) {
        System.out.println("mutant=" + view.getMutant().getMutantId());
        System.out.println("project=" + view.getMutant().getProject());
        System.out.println("operator=" + view.getMutant().getOperator());
        System.out.println("target=" + view.getMutant().getClassName() + "#" + view.getMutant().getMethodName() + ":" + view.getMutant().getLineNumber());
        System.out.println("statement=" + view.getMutant().getMutationStatement());
        if (view.getOriginalFile() != null) {
            System.out.println("original_file=" + view.getOriginalFile().getPath());
        }
        if (view.getMutantFile() != null) {
            System.out.println("mutant_file=" + view.getMutantFile().getPath());
        }
        if (view.getOriginalMethod() != null) {
            System.out.println("original_method=" + formatMethod(view.getOriginalMethod()));
        }
        if (view.getMutantMethod() != null) {
            System.out.println("mutant_method=" + formatMethod(view.getMutantMethod()));
        }
        printMethods("candidate_entries", view.getCandidateEntries());
        printCandidateImports(view.getCandidateImports());
        printCalls(view.getMutantMethodCalls());
        printFieldAccesses(view.getMutantFieldAccesses());
    }

    private static String formatMethod(MutantContextView.MethodLink method) {
        return method.getTypeName() + "#" + method.getSignature()
            + " lines=" + method.getBeginLine() + "-" + method.getEndLine()
            + " score=" + method.getScore()
            + " reason=" + method.getReason();
    }

    private static void printMethods(String label, List<MutantContextView.MethodLink> methods) {
        System.out.println(label + "=" + methods.size());
        for (int i = 0; i < methods.size(); i++) {
            System.out.println("  [" + i + "] " + formatMethod(methods.get(i)));
        }
    }

    private static void printCalls(List<MutantContextView.MethodCallView> calls) {
        System.out.println("mutant_method_calls=" + calls.size());
        for (int i = 0; i < calls.size(); i++) {
            MutantContextView.MethodCallView call = calls.get(i);
            System.out.println("  [" + i + "] line=" + call.getLineNumber() + " " + call.getOwner() + " -> " + call.getSignature());
        }
    }

    private static void printFieldAccesses(List<MutantContextView.FieldAccessView> accesses) {
        System.out.println("mutant_field_accesses=" + accesses.size());
        for (int i = 0; i < accesses.size(); i++) {
            MutantContextView.FieldAccessView access = accesses.get(i);
            System.out.println("  [" + i + "] line=" + access.getLineNumber() + " " + access.getAccessKind() + " " + access.getOwnerType() + "." + access.getFieldName());
        }
    }

    private static void printCandidateImports(List<MutantContextView.CandidateImportView> imports) {
        System.out.println("candidate_imports=" + imports.size());
        for (int i = 0; i < imports.size(); i++) {
            MutantContextView.CandidateImportView candidateImport = imports.get(i);
            System.out.println("  [" + i + "] priority=" + candidateImport.getPriority()
                + " scope=" + candidateImport.getScope()
                + " knowledgeSource=" + candidateImport.getKnowledgeSource()
                + " source=" + candidateImport.getSourceKind()
                + " import=" + candidateImport.getImportValue()
                + " context=" + candidateImport.getUsageContext());
        }
    }
}
