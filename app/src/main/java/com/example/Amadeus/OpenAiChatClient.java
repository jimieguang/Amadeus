package com.example.Amadeus;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OpenAiChatClient {

    public static final class Config {
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        public final String systemPrompt;
        public final int timeoutSec;

        public Config(String baseUrl, String apiKey, String model, String systemPrompt, int timeoutSec) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.systemPrompt = systemPrompt;
            this.timeoutSec = timeoutSec;
        }

        public String validateError() {
            if (TextUtils.isEmpty(baseUrl)) return "LLM base URL 未配置";
            if (TextUtils.isEmpty(apiKey)) return "LLM API Key 未配置";
            if (TextUtils.isEmpty(model)) return "LLM 模型名未配置";
            return null;
        }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public String chat(Config config, String userText) throws Exception {
        String error = config.validateError();
        if (error != null) throw new IllegalArgumentException(error);
        if (TextUtils.isEmpty(userText)) throw new IllegalArgumentException("用户输入为空");

        String endpoint = normalizeEndpoint(config.baseUrl);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Math.max(5, config.timeoutSec), TimeUnit.SECONDS)
                .readTimeout(Math.max(5, config.timeoutSec), TimeUnit.SECONDS)
                .writeTimeout(Math.max(5, config.timeoutSec), TimeUnit.SECONDS)
                .build();

        JSONObject req = new JSONObject();
        req.put("model", config.model);
        JSONArray messages = new JSONArray();

        if (!TextUtils.isEmpty(config.systemPrompt)) {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", config.systemPrompt);
            messages.put(system);
        }

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userText);
        messages.put(user);
        req.put("messages", messages);
        req.put("temperature", 0.7);

        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(req.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("LLM 请求失败: HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new IOException("LLM 响应为空");
            }

            String body = response.body().string();
            JSONObject obj = new JSONObject(body);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IOException("LLM 无可用回复");
            }

            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.optJSONObject("message");
            String content = message != null ? message.optString("content", "") : "";
            content = content == null ? "" : content.trim();
            if (content.isEmpty()) {
                throw new IOException("LLM 回复内容为空");
            }
            return content;
        }
    }

    private String normalizeEndpoint(String baseUrl) {
        String b = baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/v1/chat/completions")) return b;
        if (b.endsWith("/v1")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }
}
