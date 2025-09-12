package plethora.llm.openrouter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

public class OpenRouterClient {
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * 发送聊天请求并返回解析后的响应对象
     */
    public ApiResponse chatCompletion(String model, List<Message> messages) throws Exception {
        String requestBody = buildRequestBody(model, messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return objectMapper.readValue(response.body(), ApiResponse.class);
        } else {
            throw new RuntimeException("API请求失败: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    private String buildRequestBody(String model, List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(model).append("\",");
        sb.append("\"messages\":[");

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            sb.append("{\"role\":\"").append(msg.role).append("\",");
            sb.append("\"content\":\"").append(escapeJson(msg.content)).append("\"}");
            if (i < messages.size() - 1) sb.append(",");
        }

        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 请求消息实体
     */
    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * API响应数据结构
     */
    public static class ApiResponse {
        public String id;
        public String provider;
        public String model;
        public String object;
        public long created;
        public List<Choice> choices;
        public Usage usage;

        // 获取第一个回复内容
        public String getFirstContent() {
            if (choices != null && !choices.isEmpty()) {
                return choices.get(0).message.content;
            }
            return null;
        }
    }

    public static class Choice {
        public Object logprobs;
        @JsonProperty("finish_reason")
        public String finishReason;
        @JsonProperty("native_finish_reason")
        public String nativeFinishReason;
        public int index;
        public ResponseMessage message;
    }

    public static class ResponseMessage {
        public String role;
        public String content;
        public String refusal;
        public String reasoning;
    }

    public static class Usage {
        @JsonProperty("prompt_tokens")
        public int promptTokens;
        @JsonProperty("completion_tokens")
        public int completionTokens;
        @JsonProperty("total_tokens")
        public int totalTokens;

        public String toString() {
            return String.format("输入token: %d, 输出token: %d, 总计: %d",
                    promptTokens, completionTokens, totalTokens);
        }
    }
}