package mujava.rl;

public interface BanditPolicy {
    EvidenceAction select(EvidenceState state);

    void update(EvidenceState state, EvidenceAction action, double reward);
}
