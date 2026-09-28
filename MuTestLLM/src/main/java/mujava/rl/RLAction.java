package mujava.rl;

/**
 * Mutant-level actions used by transition logging and later policy training.
 */
public enum RLAction {
    INITIAL_GENERATION,
    INPUT_STRENGTHEN,
    BOUNDARY_VALUE_INPUT,
    ASSERTION_STRENGTHEN,
    OBSERVABLE_SWITCH,
    ADD_RIP_PROPAGATION,
    ENTRY_SWITCH,
    EXCEPTION_ORACLE,
    REFERENCE_GUIDED_KILL,
    ROLLBACK_TO_BEST,
    TERMINATE,
    COMPILE_REPAIR,
    CODEKB_KNOWLEDGE_REPAIR
}
