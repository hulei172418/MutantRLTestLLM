package mujava.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public final class JavacSupport {
    private JavacSupport() {
    }

    public static int compile(String[] args) {
        return compile(args, null);
    }

    public static int compile(String[] args, PrintWriter out) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "System Java compiler not available. Run muJava with a JDK, not a JRE.");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int result = compiler.run(null, buffer, buffer, args);

        if (out != null && buffer.size() > 0) {
            out.write(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            out.flush();
        }

        return result;
    }
}
