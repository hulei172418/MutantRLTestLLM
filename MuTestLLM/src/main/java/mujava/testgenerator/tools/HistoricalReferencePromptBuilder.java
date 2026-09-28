package mujava.testgenerator.tools;

import mujava.rl.SuccessTestReference;
import java.util.Collections;
import java.util.List;

/**
 * Appends a small number of successful prior-round sibling tests to prompts.
 * The current mutation evidence remains authoritative; references are templates
 * for source-compatible setup, invocation, input and assertion structure.
 */
public final class HistoricalReferencePromptBuilder {
    private HistoricalReferencePromptBuilder() {
    }

    public static String appendForInitial(String basePrompt, List<SuccessTestReference> references) {
        return append(basePrompt, references, false);
    }

    public static String appendForRepair(String basePrompt, List<SuccessTestReference> references) {
        return append(basePrompt, references, true);
    }

    private static String append(String basePrompt,
                                 List<SuccessTestReference> references,
                                 boolean repair) {
        List<SuccessTestReference> refs = references == null
                ? Collections.<SuccessTestReference>emptyList()
                : references;
        if (refs.isEmpty()) {
            return basePrompt == null ? "" : basePrompt;
        }
        StringBuilder sb = new StringBuilder((basePrompt == null ? 0 : basePrompt.length()) + 12000);
        if (basePrompt != null) {
            sb.append(basePrompt);
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append("\nPRIOR_ROUND_SUCCESSFUL_TEST_REFERENCES:\n");
        sb.append("- These tests compiled, passed on the original program, and killed sibling mutants in an earlier outer-loop round.\n");
        sb.append("- Priority is SAME_SITE > SAME_METHOD > SAME_CLASS. SAME_SITE means the same class, method, and mutation source line.\n");
        sb.append("- The current CANONICAL_CORE and current mutation evidence remain authoritative; never copy a sibling oracle blindly.\n");
        if (repair) {
            sb.append("- For compile repair, first borrow package/import choices, receiver construction, factory/constructor usage, public API calls, and Java syntax that already compiled.\n");
            sb.append("- Then adapt concrete inputs and the final mutation-sensitive assertion for the current mutant.\n");
        } else {
            sb.append("- Reuse proven setup/invocation structure when compatible, then adapt inputs and assertions to the current mutant.\n");
            sb.append("- Prefer a SAME_SITE sibling shape over inventing a completely new access path.\n");
        }
        sb.append("- Return only the required current test class; do not keep any reference class name.\n\n");
        int index = 1;
        for (SuccessTestReference ref : refs) {
            if (ref == null) {
                continue;
            }
            sb.append("HISTORICAL_REFERENCE_").append(index).append(":\n");
            sb.append("relation = ").append(safe(ref.relation)).append('\n');
            sb.append("sourceMutant = ").append(safe(ref.mutantId)).append('\n');
            sb.append("sourceMethod = ").append(safe(ref.sourceMethod)).append('\n');
            sb.append("sourceLine = ").append(safe(ref.line)).append('\n');
            sb.append("sourceAction = ").append(safe(ref.action)).append('\n');
            sb.append("sourceReward = ").append(ref.reward).append('\n');
            sb.append("sourceCode:\n").append(safe(ref.code)).append("\n\n");
            index++;
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
