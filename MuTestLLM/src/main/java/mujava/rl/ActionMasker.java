package mujava.rl;

import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps mutant-level exploration inside actions that make sense for the
 * current execution state.
 */
public final class ActionMasker {
    private ActionMasker() {
    }

    public static Set<RLAction> allowed(RLPhase phase) {
        if (phase == null) {
            return EnumSet.of(RLAction.INITIAL_GENERATION, RLAction.TERMINATE);
        }
        switch (phase) {
            case COMPILE_FAILED:
                return EnumSet.of(RLAction.TERMINATE);
            case ORIGINAL_FAILED:
                return EnumSet.of(RLAction.TERMINATE);
            case TARGET_LIVE:
            case ORIGINAL_PASSED:
                return EnumSet.of(
                        RLAction.INPUT_STRENGTHEN,
                        RLAction.ASSERTION_STRENGTHEN,
                        RLAction.OBSERVABLE_SWITCH,
                        RLAction.EXCEPTION_ORACLE,
                        RLAction.REFERENCE_GUIDED_KILL,
                        RLAction.TERMINATE);
            case TARGET_KILLED:
            case EQUIVALENT_SUSPECTED:
                return EnumSet.of(RLAction.TERMINATE);
            case TIMEOUT:
            case CRASHED:
                return EnumSet.of(RLAction.TERMINATE);
            default:
                return EnumSet.of(RLAction.INITIAL_GENERATION, RLAction.TERMINATE);
        }
    }

    public static boolean isAllowed(RLPhase phase, RLAction action) {
        return action != null && allowed(phase).contains(action);
    }
}
