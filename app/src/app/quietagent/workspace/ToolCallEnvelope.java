package app.quietagent.workspace;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Normalizes common JSON tool-call envelopes without weakening executor validation. */
final class ToolCallEnvelope {
    private ToolCallEnvelope() { }

    static JSONObject normalize(JSONObject response) throws IOException {
        if (response == null) throw new IOException("模型工具请求为空");
        JSONObject call = response;
        JSONArray calls = response.optJSONArray("tool_calls");
        if (calls != null && calls.length() > 0 && calls.optJSONObject(0) != null) call = calls.optJSONObject(0);
        for (int depth = 0; depth < 3; depth++) {
            JSONObject nested = firstObject(call, "tool_call", "function", "call", "action");
            if (nested == null) break;
            call = nested;
        }
        String tool = firstString(call, "tool", "name", "action", "tool_name", "function_name");
        Object rawArgs = firstValue(call, "args", "arguments", "parameters", "input", "tool_input", "tool_args", "tool_arguments");
        JSONObject toolObject = call.optJSONObject("tool");
        if (toolObject != null) {
            if (tool.isEmpty()) tool = firstString(toolObject, "name", "tool", "action");
            if (rawArgs == null) rawArgs = firstValue(toolObject, "args", "arguments", "parameters", "input");
        }
        JSONObject args = asObject(rawArgs);
        // JSON-only models sometimes flatten arguments beside the tool name.
        if (!tool.isEmpty() && args == null) {
            args = new JSONObject();
            JSONArray names = call.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String key = names.optString(i);
                if (!"tool".equals(key) && !"name".equals(key) && !"action".equals(key)
                        && !"tool_name".equals(key) && !"function_name".equals(key)) {
                    try { args.put(key, call.opt(key)); } catch (Exception ignored) { }
                }
            }
        }
        if (tool.isEmpty() || args == null) throw new IOException("模型工具请求格式无效：" + shape(response, 0));
        try { return new JSONObject().put("tool", tool).put("args", args); }
        catch (Exception error) { throw new IOException("模型工具请求格式无效", error); }
    }

    private static JSONObject firstObject(JSONObject value, String... keys) {
        for (String key : keys) { JSONObject found = value.optJSONObject(key); if (found != null) return found; }
        return null;
    }
    private static String firstString(JSONObject value, String... keys) {
        for (String key : keys) { Object raw = value.opt(key); if (raw instanceof String && !((String) raw).trim().isEmpty()) return ((String) raw).trim(); }
        return "";
    }
    private static Object firstValue(JSONObject value, String... keys) {
        for (String key : keys) if (value.has(key) && !value.isNull(key)) return value.opt(key);
        return null;
    }
    private static JSONObject asObject(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof String) { String text = ((String) raw).trim(); if (text.startsWith("{") && text.endsWith("}")) try { return new JSONObject(text); } catch (Exception ignored) { } }
        return null;
    }
    private static String shape(JSONObject value, int depth) {
        JSONArray names = value.names();
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; names != null && i < names.length(); i++) {
            if (i > 0) out.append(',');
            String key = names.optString(i);
            Object item = value.opt(key);
            out.append(key).append(':');
            if (item instanceof JSONObject && depth < 2) out.append(shape((JSONObject) item, depth + 1));
            else if (item instanceof JSONArray) out.append("array");
            else if (item instanceof String) out.append("string");
            else out.append(item == null ? "null" : item.getClass().getSimpleName());
        }
        return out.append('}').toString();
    }
}
