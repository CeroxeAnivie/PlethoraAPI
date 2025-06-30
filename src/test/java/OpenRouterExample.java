import plethora.llm.openrouter.OpenRouterClient;

import java.util.Arrays;
import java.util.List;

public class OpenRouterExample {
    public static void main(String[] args) {
        // 1. 初始化客户端 (使用你的API密钥)
        OpenRouterClient client = new OpenRouterClient("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");

        // 2. 创建对话消息
        List<OpenRouterClient.Message> messages = Arrays.asList(
                new OpenRouterClient.Message("system", "你是个有用的助手。"),
                new OpenRouterClient.Message("user", "Java中的多线程编程有哪些优势？")
        );

        try {
            // 3. 发送请求并获取响应对象
            OpenRouterClient.ApiResponse response = client.chatCompletion("deepseek/deepseek-r1-0528:free", messages);

            // 4. 解析响应内容
            if (response.choices != null && !response.choices.isEmpty()) {
                OpenRouterClient.ResponseMessage message = response.choices.get(0).message;

                System.out.println("=== 助手回复 ===");
                System.out.println(message.content);

                System.out.println("\n=== 推理过程 ===");
                System.out.println(message.reasoning);

                System.out.println("\n=== Token使用情况 ===");
                System.out.println(response.usage);

                System.out.println("\n=== 完整响应摘要 ===");
                System.out.println("响应ID: " + response.id);
                System.out.println("模型: " + response.model);
                System.out.println("提供方: " + response.provider);
                System.out.println("创建时间: " + response.created);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}