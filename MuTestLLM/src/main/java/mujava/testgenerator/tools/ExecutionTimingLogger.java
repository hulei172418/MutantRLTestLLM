package mujava.testgenerator.tools;

/**
 * Optional execution timing logger for LLMTestExecutor and suite verification.
 * Controlled by llm.properties / system properties.
 */
public final class ExecutionTimingLogger {
    private static final LlmRuntimeConfig CONFIG = LlmRuntimeConfigLoader.load();
    private static final boolean ENABLED = CONFIG.isExecutorTimingEnabled();
    private static final long MIN_MILLIS = Math.max(0, CONFIG.getExecutorTimingMinMillis());

    private ExecutionTimingLogger() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static long minMillis() {
        return MIN_MILLIS;
    }

    public static long now() {
        return System.nanoTime();
    }

    public static void logSince(String phase, long startedAtNanos, String detail) {
        if (!ENABLED) {
            return;
        }
        long elapsedMillis = nanosToMillis(System.nanoTime() - startedAtNanos);
        log(phase, elapsedMillis, detail);
    }

    public static void log(String phase, long elapsedMillis, String detail) {
        if (!ENABLED || elapsedMillis < MIN_MILLIS) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[LLM-EXEC-TIMING] phase=").append(phase)
                .append(" elapsedMs=").append(elapsedMillis);
        if (detail != null && !detail.trim().isEmpty()) {
            sb.append(" ").append(detail.trim());
        }
        System.out.println(sb.toString());
    }

    public static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
