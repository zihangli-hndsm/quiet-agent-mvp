package app.quietagent.workspace;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolCallEnvelopeTests {
    private static void ok(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static void check(JSONObject input, String id) throws Exception {
        JSONObject normalized = ToolCallEnvelope.normalize(input);
        ok("list".equals(normalized.getString("tool")), "tool");
        ok(id.equals(normalized.getJSONObject("args").getString("rootId")), "args " + id);
    }
    public static void main(String[] ignored) throws Exception {
        check(new JSONObject().put("tool", "list").put("args", new JSONObject().put("rootId", "private")), "private");
        check(new JSONObject().put("name", "list").put("arguments", "{\"rootId\":\"r1\"}"), "r1");
        check(new JSONObject().put("tool_call", new JSONObject().put("name", "list").put("parameters", new JSONObject().put("rootId", "r2"))), "r2");
        check(new JSONObject().put("tool_calls", new JSONArray().put(new JSONObject().put("function", new JSONObject().put("name", "list").put("arguments", "{\"rootId\":\"r3\"}")))), "r3");
        check(new JSONObject().put("tool", "list").put("rootId", "r4").put("offset", 0), "r4");
        try { ToolCallEnvelope.normalize(new JSONObject().put("message", "hello")); throw new AssertionError("invalid accepted"); }
        catch (java.io.IOException expected) { }
        System.out.println("ToolCallEnvelopeTests OK");
    }
}
