package org.rip;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source-level formatter for compile-critical callable signatures.
 *
 * <p>ReceiverResolver PUBLIC_API and CodeKbEvidenceAdapter exact callable facts
 * must use the same representation so static/instance and parameter shapes cannot
 * diverge merely because different evidence builders formatted the same method.</p>
 */
final class CallableSignatureFormatter {
    private CallableSignatureFormatter() {
    }

    static String method(MethodDeclaration method) {
        if (method == null) {
            return "";
        }
        String prefix = method.isStatic() ? "static " : "";
        return prefix + method.getType().asString() + " " + method.getNameAsString()
                + "(" + parameterTypes(method.getParameters()) + ")";
    }

    static String constructor(String ownerName, ConstructorDeclaration constructor) {
        if (constructor == null) {
            return "";
        }
        return safe(ownerName) + "(" + parameterTypes(constructor.getParameters()) + ")";
    }

    private static String parameterTypes(List<Parameter> parameters) {
        List<String> types = new ArrayList<String>();
        if (parameters != null) {
            for (Parameter parameter : parameters) {
                String type = parameter.getType().asString();
                if (parameter.isVarArgs()) {
                    type += "...";
                }
                types.add(type);
            }
        }
        return String.join(", ", types);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
