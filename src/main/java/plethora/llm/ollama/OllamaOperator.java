package plethora.llm.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * 封装与Ollama API的交互，支持上下文记忆和思考内容提取
 */
public class OllamaOperator {
    private static final int DEFAULT_MAX_HISTORY = 10;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String endpoint;
    private final String modelName;
    private final List<Message> conversationHistory;
    private int maxHistoryEntries = DEFAULT_MAX_HISTORY;

    /**
     * 构造函数（带认证）
     *
     * @param endpoint  API端点URL
     * @param modelName 模型名称
     */
    public OllamaOperator(String endpoint, String modelName) {
        this.endpoint = endpoint;
        this.modelName = modelName;
        this.conversationHistory = new ArrayList<>();
    }

    /**
     * 设置最大历史记录条数
     *
     * @param maxHistoryEntries 最大历史记录条数
     */
    public void setMaxHistoryEntries(int maxHistoryEntries) {
        this.maxHistoryEntries = maxHistoryEntries;
    }

    /**
     * 添加系统提示
     *
     * @param prompt 系统提示内容
     */
    public void setSystemPrompt(String prompt) {
        // 移除现有系统提示
        conversationHistory.removeIf(msg -> "system".equals(msg.role));
        // 添加新系统提示到开头
        conversationHistory.add(0, new Message("system", prompt));
    }

    /**
     * 发送消息给AI并获取复合响应
     *
     * @param userMessage 用户消息
     * @return 包含思考和主体输出的响应对象
     */
    public AiResponse sendMessage(String userMessage) {
        try {
            // 1. 添加用户消息到历史
            conversationHistory.add(new Message("user", userMessage));

            // 2. 管理历史长度
            manageHistoryLength();

            // 3. 构建请求体
            Map<String, Object> request = new HashMap<>();
            request.put("model", modelName);
            request.put("messages", convertToMapList(conversationHistory));
            request.put("stream", false);

            String requestBody = mapper.writeValueAsString(request);

            // 4. 创建HTTP请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            HttpRequest httpRequest = requestBuilder.build();

            // 6. 发送请求
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            // 7. 处理响应
            if (response.statusCode() == 200) {
                Map<String, Object> responseMap = mapper.readValue(
                        response.body(), new TypeReference<Map<String, Object>>() {
                        });

                Map<String, String> message = (Map<String, String>) responseMap.get("message");
                String aiResponse = message.get("content");

                // 8. 添加AI回复到历史
                conversationHistory.add(new Message("assistant", aiResponse));

                // 9. 解析响应并返回结构化对象
                return parseAiResponse(aiResponse);
            } else {
                throw new RuntimeException("API请求失败: " + response.statusCode() + " - " + response.body());
            }
        } catch (Exception e) {
            throw new RuntimeException("与AI通信时出错", e);
        }
    }

    /**
     * 管理对话历史长度
     */
    private void manageHistoryLength() {
        // 计算系统消息数量（需要保留）
        long systemMessageCount = conversationHistory.stream()
                .filter(msg -> "system".equals(msg.role))
                .count();

        // 计算需要保留的对话轮数
        int maxPairs = (maxHistoryEntries - (int) systemMessageCount) / 2;

        // 如果历史记录超出限制
        if (conversationHistory.size() > systemMessageCount + maxPairs * 2) {
            // 保留：系统消息 + 最近的N轮对话
            List<Message> newHistory = new ArrayList<>();

            // 1. 保留所有系统消息
            for (Message msg : conversationHistory) {
                if ("system".equals(msg.role)) {
                    newHistory.add(msg);
                }
            }

            // 2. 保留最近的对话
            int startIndex = Math.max(
                    conversationHistory.size() - maxPairs * 2,
                    (int) systemMessageCount
            );

            for (int i = startIndex; i < conversationHistory.size(); i++) {
                newHistory.add(conversationHistory.get(i));
            }

            conversationHistory.clear();
            conversationHistory.addAll(newHistory);
        }
    }

    /**
     * 将内部消息列表转换为Map列表（用于JSON序列化）
     */
    private List<Map<String, String>> convertToMapList(List<Message> messages) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, String> map = new HashMap<>();
            map.put("role", msg.role);
            map.put("content", msg.content);
            result.add(map);
        }
        return result;
    }

    /**
     * 解析AI响应，提取思考内容和主体输出
     *
     * @param rawResponse 原始响应字符串
     * @return 结构化响应对象
     */
    private AiResponse parseAiResponse(String rawResponse) {
        String thinking = "";
        String content = rawResponse;

        // 尝试提取思考部分
        int thinkStart = rawResponse.indexOf("<think>");
        int thinkEnd = rawResponse.indexOf("</think>");

        if (thinkStart != -1 && thinkEnd != -1 && thinkStart < thinkEnd) {
            // 提取思考内容
            thinking = rawResponse.substring(thinkStart + 7, thinkEnd).trim();

            // 提取主体输出（思考部分之后的内容）
            String afterThink = rawResponse.substring(thinkEnd + 8).trim();

            // 移除可能存在的多余标签
            if (afterThink.startsWith("<think>")) {
                afterThink = afterThink.substring(7);
            }
            if (afterThink.endsWith("</think>")) {
                afterThink = afterThink.substring(0, afterThink.length() - 8);
            }

            content = afterThink;
        }

        return new AiResponse(thinking, content);
    }

    /**
     * 重置对话（清除历史，保留系统提示）
     */
    public void resetConversation() {
        // 保留系统提示
        List<Message> systemMessages = new ArrayList<>();
        for (Message msg : conversationHistory) {
            if ("system".equals(msg.role)) {
                systemMessages.add(msg);
            }
        }
        conversationHistory.clear();
        conversationHistory.addAll(systemMessages);
    }

    /**
     * 获取当前对话历史（只读）
     *
     * @return 对话历史副本
     */
    public List<Map<String, String>> getConversationHistory() {
        List<Map<String, String>> historyCopy = new ArrayList<>();
        for (Message msg : conversationHistory) {
            Map<String, String> entry = new HashMap<>();
            entry.put("role", msg.role);
            entry.put("content", msg.content);
            historyCopy.add(entry);
        }
        return Collections.unmodifiableList(historyCopy);
    }

    /**
     * 获取当前历史记录条数
     */
    public int getHistorySize() {
        return conversationHistory.size();
    }

    /**
     * AI响应对象，包含思考内容和主体输出
     */
    public static class AiResponse {
        private final String thinking;
        private final String content;

        public AiResponse(String thinking, String content) {
            this.thinking = thinking;
            this.content = content;
        }

        /**
         * 获取AI的思考过程
         *
         * @return 思考内容
         */
        public String getThinking() {
            return thinking;
        }

        /**
         * 获取AI的主体输出
         *
         * @return 主体内容
         */
        public String getContent() {
            return content;
        }

        /**
         * 获取完整的响应内容（包含思考标签）
         *
         * @return 完整响应
         */
        public String getFullResponse() {
            if (thinking.isEmpty()) {
                return content;
            }
            return "<think>" + thinking + "</think>\n\n" + content;
        }

        @Override
        public String toString() {
            return getFullResponse();
        }
    }

    // 内部消息类
    private static class Message {
        String role;
        String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}