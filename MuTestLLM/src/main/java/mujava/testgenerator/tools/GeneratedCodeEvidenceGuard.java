package mujava.testgenerator.tools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic compile guard driven only by compile-critical evidence.
 *
 * <p>This class deliberately performs a small set of semantics-preserving AST
 * rewrites before javac:</p>
 * <ul>
 *   <li>private field reads such as {@code v.x} -> {@code v.getX()} only when
 *       an evidence-confirmed zero-argument public accessor exists;</li>
 *   <li>instance-style calls to evidence-confirmed static owner methods are
 *       normalized to {@code Owner.method(...)};</li>
 *   <li>for the common binary-static-utility mistake {@code a.m(b)} where the
 *       exact static signature is {@code m(Owner, Owner)}, the receiver is
 *       moved into the first argument: {@code Owner.m(a, b)}.</li>
 * </ul>
 *
 * <p>No textual search/replace is used. Ambiguous cases are left untouched for
 * javac + the single LLM compile-repair round.</p>
 */
public final class GeneratedCodeEvidenceGuard {
    private GeneratedCodeEvidenceGuard() {
    }

    public static String normalizeAgainstEvidence(String code, PromptEvidence evidence) {
        if (code == null || code.trim().isEmpty() || evidence == null) {
            return code;
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            EvidenceFacts facts = EvidenceFacts.from(evidence);
            if (facts.ownerSimpleName.isEmpty()) {
                return code;
            }

            repairPrivateFieldReads(cu, facts);
            repairStaticInvocations(cu, facts);
            return cu.toString().trim();
        } catch (RuntimeException ignored) {
            // Leave unparseable code untouched. The normal javac/repair path remains
            // authoritative for syntax errors.
            return code;
        }
    }

    private static void repairPrivateFieldReads(CompilationUnit cu, EvidenceFacts facts) {
        if (facts.privateFields.isEmpty() || facts.getterByField.isEmpty()) {
            return;
        }
        List<FieldAccessExpr> accesses = new ArrayList<FieldAccessExpr>(cu.findAll(FieldAccessExpr.class));
        for (FieldAccessExpr access : accesses) {
            String field = access.getNameAsString();
            if (!facts.privateFields.contains(field) || isWriteContext(access)) {
                continue;
            }
            String getter = facts.getterByField.get(field);
            if (getter == null || getter.isEmpty()) {
                continue;
            }
            MethodCallExpr replacement = new MethodCallExpr(access.getScope().clone(), getter);
            access.replace(replacement);
        }
    }

    private static boolean isWriteContext(FieldAccessExpr access) {
        Optional<Node> parent = access.getParentNode();
        if (!parent.isPresent()) {
            return false;
        }
        Node p = parent.get();
        if (p instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) p;
            if (assign.getTarget() == access) {
                return true;
            }
        }
        if (p instanceof UnaryExpr) {
            UnaryExpr.Operator op = ((UnaryExpr) p).getOperator();
            return op == UnaryExpr.Operator.POSTFIX_DECREMENT
                    || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || op == UnaryExpr.Operator.PREFIX_DECREMENT
                    || op == UnaryExpr.Operator.PREFIX_INCREMENT;
        }
        return false;
    }

    private static void repairStaticInvocations(CompilationUnit cu, EvidenceFacts facts) {
        if (facts.staticMethodsByName.isEmpty()) {
            return;
        }
        List<MethodCallExpr> calls = new ArrayList<MethodCallExpr>(cu.findAll(MethodCallExpr.class));
        for (MethodCallExpr call : calls) {
            if (!call.getScope().isPresent()) {
                continue;
            }
            String name = call.getNameAsString().toLowerCase(Locale.ROOT);
            List<MethodFact> staticFacts = facts.staticMethodsByName.get(name);
            if (staticFacts == null || staticFacts.isEmpty()) {
                continue;
            }

            Expression scope = call.getScope().get();
            String scopeText = scope.toString();
            if (facts.ownerSimpleName.equals(scopeText)) {
                continue;
            }
            // An upper-case simple name is probably an explicit class qualifier. Do not
            // rewrite calls on some other class without symbol resolution.
            if (scope.isNameExpr()) {
                String simple = scope.asNameExpr().getNameAsString();
                if (!simple.isEmpty() && Character.isUpperCase(simple.charAt(0))) {
                    continue;
                }
            }

            int currentArity = call.getArguments().size();
            if (facts.hasInstanceMethod(name, currentArity)) {
                continue;
            }

            MethodFact sameArity = uniqueByArity(staticFacts, currentArity);
            if (sameArity != null) {
                call.setScope(new NameExpr(facts.ownerSimpleName));
                continue;
            }

            MethodFact receiverAsFirstArg = uniqueByArity(staticFacts, currentArity + 1);
            if (receiverAsFirstArg != null
                    && receiverAsFirstArg.firstParameterMatchesOwner(facts.ownerSimpleName)) {
                Expression oldScope = scope.clone();
                call.setScope(new NameExpr(facts.ownerSimpleName));
                call.getArguments().add(0, oldScope);
            }
        }
    }

    private static MethodFact uniqueByArity(List<MethodFact> facts, int arity) {
        MethodFact found = null;
        for (MethodFact fact : facts) {
            if (fact.arity != arity) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = fact;
        }
        return found;
    }

    private static final class EvidenceFacts {
        String ownerSimpleName = "";
        final Set<String> privateFields = new LinkedHashSet<String>();
        final Map<String, String> getterByField = new LinkedHashMap<String, String>();
        final Map<String, List<MethodFact>> staticMethodsByName = new LinkedHashMap<String, List<MethodFact>>();
        final Map<String, Set<Integer>> instanceAritiesByName = new LinkedHashMap<String, Set<Integer>>();

        static EvidenceFacts from(PromptEvidence evidence) {
            EvidenceFacts out = new EvidenceFacts();
            JSONObject receiver = evidence.compilationFacts.optJSONObject("receiver");
            if (receiver != null) {
                out.ownerSimpleName = receiver.optString("ownerSimpleName", "").trim();
            }
            JSONArray privateFields = evidence.compilationFacts.optJSONArray("privateFieldNames");
            if (privateFields != null) {
                for (int i = 0; i < privateFields.length(); i++) {
                    String field = privateFields.optString(i, "").trim();
                    if (!field.isEmpty()) {
                        out.privateFields.add(field);
                    }
                }
            }

            JSONArray exact = evidence.compilationFacts.optJSONArray("exactCallableSignatures");
            if (exact != null) {
                for (int i = 0; i < exact.length(); i++) {
                    CallableSignatureFact parsed =
                            CallableSignatureFact.parse(exact.optString(i, ""));
                    if (parsed == null) {
                        continue;
                    }
                    MethodFact fact = MethodFact.from(parsed);
                    String key = fact.name.toLowerCase(Locale.ROOT);
                    if (parsed.isStatic) {
                        List<MethodFact> list = out.staticMethodsByName.get(key);
                        if (list == null) {
                            list = new ArrayList<MethodFact>();
                            out.staticMethodsByName.put(key, list);
                        }
                        list.add(fact);
                    } else {
                        Set<Integer> arities = out.instanceAritiesByName.get(key);
                        if (arities == null) {
                            arities = new LinkedHashSet<Integer>();
                            out.instanceAritiesByName.put(key, arities);
                        }
                        arities.add(fact.arity);
                    }
                    if (fact.arity == 0) {
                        registerAccessor(out, fact.name);
                    }
                }
            }

            JSONArray publicMethods = evidence.publicApiEvidence.optJSONArray("availablePublicMethods");
            if (publicMethods != null) {
                for (int i = 0; i < publicMethods.length(); i++) {
                    CallableSignatureFact parsed =
                            CallableSignatureFact.parse(publicMethods.optString(i, ""));
                    if (parsed != null && parsed.arity() == 0) {
                        registerAccessor(out, parsed.methodName);
                    }
                }
            }
            return out;
        }

        boolean hasInstanceMethod(String name, int arity) {
            Set<Integer> arities = instanceAritiesByName.get(name);
            return arities != null && arities.contains(arity);
        }

        private static void registerAccessor(EvidenceFacts facts, String methodName) {
            if (methodName == null) {
                return;
            }
            String field = "";
            if (methodName.startsWith("get") && methodName.length() > 3) {
                field = decapitalize(methodName.substring(3));
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                field = decapitalize(methodName.substring(2));
            }
            if (!field.isEmpty() && facts.privateFields.contains(field)
                    && !facts.getterByField.containsKey(field)) {
                facts.getterByField.put(field, methodName);
            }
        }

        private static String decapitalize(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            if (text.length() > 1 && Character.isUpperCase(text.charAt(0))
                    && Character.isUpperCase(text.charAt(1))) {
                return text;
            }
            return Character.toLowerCase(text.charAt(0)) + text.substring(1);
        }
    }

    private static final class MethodFact {
        final String name;
        final int arity;
        final String firstParameter;

        MethodFact(String name, int arity, String firstParameter) {
            this.name = name;
            this.arity = arity;
            this.firstParameter = firstParameter;
        }

        static MethodFact from(CallableSignatureFact fact) {
            List<String> params = fact.parameterTypes();
            String first = params.isEmpty() ? "" : simpleType(params.get(0));
            return new MethodFact(fact.methodName, fact.arity(), first);
        }

        boolean firstParameterMatchesOwner(String owner) {
            return owner != null && owner.equals(simpleType(firstParameter));
        }

        private static String simpleType(String raw) {
            if (raw == null) {
                return "";
            }
            String text = raw.trim().replace("...", "[]");
            int generic = text.indexOf('<');
            if (generic >= 0) {
                text = text.substring(0, generic);
            }
            int dot = text.lastIndexOf('.');
            return dot >= 0 ? text.substring(dot + 1) : text;
        }
    }
}
