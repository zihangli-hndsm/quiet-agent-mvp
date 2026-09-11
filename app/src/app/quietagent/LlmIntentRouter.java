package app.quietagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Presentation-only intent layer. It sends only the task sentence to the LLM;
 * URI lists, photos, file contents and audit data never cross this boundary.
 */
public final class LlmIntentRouter {
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";

    public enum Route { TICKET, ORIGINAL, UNSUPPORTED }

    public static final class Decision {
        public final Route route;
        public final String plan;
        public final String risk;
        Decision(Route route, String plan, String risk) {
            this.route = route;
            this.plan = plan;
            this.risk = risk;
        }
    }

    private LlmIntentRouter() { }

    public static Decision understand(Context context, String request) throws IOException {
        String task = clean(request, 600);
        if (task.length() < 2) throw new IOException("请先写一句要处理什么");
        JSONObject body = new JSONObject();
        try {
            body.put("model", MODEL).put("temperature", 0.1).put("max_tokens", 260);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    "你是手机任务路由器。只输出一个 JSON 对象，不要 Markdown。"
                    + "route 只能是 TICKET、ORIGINAL 或 UNSUPPORTED。"
                    + "TICKET 仅用于用户要整理发票、收据或票据照片；ORIGINAL 仅用于用户要只读整理一个文件目录；"
                    + "其他意图一律 UNSUPPORTED。plan 用一句中文概括允许的本地操作。"
                    + "risk 用一句中文提示：任务描述会发送给云端模型；照片、文件内容、文件名和审计内容不会发送。"
                    + "不得承诺执行未列出的操作，不得让用户绕过逐次授权。"));
            messages.put(new JSONObject().put("role", "user").put("content", task));
            body.put("messages", messages);
        } catch (Exception error) { throw new IOException("无法准备模型请求", error); }

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + demoKey(context));
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            close(connection);
            throw new IOException("模型服务暂不可用（" + status + "）");
        }
        String response = read(connection.getInputStream());
        close(connection);
        try {
            JSONObject root = new JSONObject(response);
            String content = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            JSONObject value = new JSONObject(extractJson(content));
            String routeValue = value.optString("route", "UNSUPPORTED").trim();
            Route route = "TICKET".equals(routeValue) ? Route.TICKET
                    : "ORIGINAL".equals(routeValue) ? Route.ORIGINAL : Route.UNSUPPORTED;
            return new Decision(route, clean(value.optString("plan", ""), 260), clean(value.optString("risk", ""), 360));
        } catch (Exception error) {
            throw new IOException("模型没有返回可核对的任务计划", error);
        }
    }

    private static String demoKey(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("demo-llm-key")) {
            String key = read(in).trim();
            if (key.length() < 16) throw new IOException("演示模型密钥不可用");
            return key;
        }
    }

    private static String extractJson(String value) throws IOException {
        int first = value.indexOf('{');
        int last = value.lastIndexOf('}');
        if (first < 0 || last <= first) throw new IOException("模型计划不是 JSON");
        return value.substring(first, last + 1);
    }

    private static String clean(String value, int limit) {
        String out = value == null ? "" : value.replace('\u0000', ' ').trim();
        return out.length() > limit ? out.substring(0, limit) : out;
    }

    private static String read(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[2048];
            int n;
            while ((n = reader.read(buffer)) != -1) out.append(buffer, 0, n);
            return out.toString();
        }
    }

    private static void close(HttpURLConnection connection) { if (connection != null) connection.disconnect(); }
}
