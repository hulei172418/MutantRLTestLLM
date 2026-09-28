package org;

public final class ClasspathProbe {
    private ClasspathProbe() {
    }

    public static void main(String[] args) throws Exception {
        String name = args != null && args.length > 0 ? args[0] : "org.eclipse.jdt.core.dom.ASTVisitor";
        Class<?> type = Class.forName(name);
        System.out.println("LOADED\t" + type.getName() + "\t" + type.getProtectionDomain().getCodeSource().getLocation());
    }
}
