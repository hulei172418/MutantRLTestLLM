package mujava.rl;

import org.json.JSONObject;

public final class SignalLedger {
    public final SignalPath importSignal = new SignalPath();
    public final SignalPath entrySignal = new SignalPath();
    public final SignalPath receiverSignal = new SignalPath();
    public final SignalPath reachabilitySignal = new SignalPath();
    public final SignalPath observableSignal = new SignalPath();
    public final SignalPath assertionSignal = new SignalPath();

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("import_signal", importSignal.toJson());
        obj.put("entry_signal", entrySignal.toJson());
        obj.put("receiver_signal", receiverSignal.toJson());
        obj.put("reachability_signal", reachabilitySignal.toJson());
        obj.put("observable_signal", observableSignal.toJson());
        obj.put("assertion_signal", assertionSignal.toJson());
        return obj;
    }
}
