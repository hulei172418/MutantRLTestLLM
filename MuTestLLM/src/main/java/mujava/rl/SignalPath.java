package mujava.rl;

import org.json.JSONObject;

public final class SignalPath {
    public boolean source;
    public boolean codeKb;
    public boolean outputJson;
    public boolean prompt;
    public boolean generatedTest;
    public String note = "";

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("source", source);
        obj.put("codekb", codeKb);
        obj.put("output_json", outputJson);
        obj.put("prompt", prompt);
        obj.put("generated_test", generatedTest);
        obj.put("note", note == null ? "" : note);
        return obj;
    }
}
