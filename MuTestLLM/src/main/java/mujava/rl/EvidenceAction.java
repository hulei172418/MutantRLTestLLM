package mujava.rl;

/**
 * Discrete evidence/prompt profiles used by the first-stage bandit policy.
 */
public enum EvidenceAction {
    BASELINE,
    MUTATION_HEAVY,
    ENTRY_HEAVY,
    COMPILE_HEAVY,
    ASSERTION_HEAVY,
    COMPACT
}
