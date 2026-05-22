package com.czqwq.talkwith.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.czqwq.talkwith.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

public class AIClient {

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Gson GSON = new Gson();

    public static void sendAsync(List<ChatMessage> messages, Consumer<String> onSuccess, Consumer<String> onError) {
        sendAsync(messages, Config.baseUrl, Config.apiKey, Config.model, onSuccess, onError);
    }

    public static void sendAsync(List<ChatMessage> messages, String baseUrl, String apiKey, Consumer<String> onSuccess,
        Consumer<String> onError) {
        sendAsync(messages, baseUrl, apiKey, Config.model, onSuccess, onError);
    }

    public static void sendAsync(List<ChatMessage> messages, String baseUrl, String apiKey, String model,
        Consumer<String> onSuccess, Consumer<String> onError) {
        executor.submit(() -> {
            try {
                URL url = new URL(baseUrl + "/v1/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(Config.timeout * 1000);
                conn.setReadTimeout(Config.timeout * 1000);
                conn.setDoOutput(true);

                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                JsonArray msgArray = new JsonArray();
                for (ChatMessage m : messages) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("role", m.role);
                    mo.addProperty("content", m.content);
                    msgArray.add(mo);
                }
                body.add("messages", msgArray);

                byte[] bodyBytes = GSON.toJson(body)
                    .getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    String errorText = new String(readStream(conn.getErrorStream()), StandardCharsets.UTF_8);
                    onError.accept("HTTP " + status + ": " + errorText);
                    return;
                }

                // Read line-by-line to support both SSE/streaming and regular JSON responses.
                // SSE lines have the form "data: {...}" or "data: [DONE]".
                StringBuilder accumulated = new StringBuilder();
                boolean isSSE = false;
                StringBuilder jsonBuffer = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ln;
                    while ((ln = reader.readLine()) != null) {
                        if (ln.startsWith("data: ")) {
                            isSSE = true;
                            String data = ln.substring(6)
                                .trim();
                            if ("[DONE]".equals(data)) break;
                            try {
                                JsonObject chunk = GSON.fromJson(data, JsonObject.class);
                                if (chunk != null && chunk.has("choices")) {
                                    JsonArray choices = chunk.getAsJsonArray("choices");
                                    if (choices != null && choices.size() > 0) {
                                        JsonObject delta = choices.get(0)
                                            .getAsJsonObject()
                                            .getAsJsonObject("delta");
                                        if (delta != null && delta.has("content")
                                            && !delta.get("content")
                                                .isJsonNull()) {
                                            accumulated.append(
                                                delta.get("content")
                                                    .getAsString());
                                        }
                                    }
                                }
                            } catch (JsonSyntaxException ignored) {}
                        } else if (!isSSE && !ln.isEmpty()) {
                            jsonBuffer.append(ln);
                        }
                    }
                }

                if (isSSE) {
                    String content = accumulated.toString()
                        .trim();
                    if (content.isEmpty()) {
                        onError.accept("Streaming completed but no content was received");
                    } else {
                        onSuccess.accept(content);
                    }
                    return;
                }

                // Non-streaming path: parse the full JSON blob.
                String responseText = jsonBuffer.toString();
                JsonObject responseJson = GSON.fromJson(responseText, JsonObject.class);
                if (responseJson == null || !responseJson.has("choices")
                    || responseJson.getAsJsonArray("choices")
                        .size() == 0) {
                    onError.accept("Empty or malformed response: " + responseText);
                    return;
                }
                JsonObject message = responseJson.getAsJsonArray("choices")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("message");
                if (message == null || !message.has("content")) {
                    onError.accept("Missing message.content in response");
                    return;
                }
                onSuccess.accept(
                    message.get("content")
                        .getAsString());
            } catch (Exception e) {
                onError.accept(
                    e.getMessage() != null ? e.getMessage()
                        : e.getClass()
                            .getSimpleName());
            }
        });
    }

    private static byte[] readStream(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
}
